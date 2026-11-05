package com.tugartech.claudewalk

import com.tugartech.claudewalk.speech.VoiceOption
import com.tugartech.claudewalk.speech.VoicePrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** What the session is doing right now, as far as the phone can tell. */
enum class Activity { IDLE, CLAUDE_SPEAKING, LISTENING, SENDING }

data class TranscriptLine(
    val role: String,
    val text: String,
    /** False while an utterance is queued but not yet played, e.g. during a pause. */
    val heard: Boolean = true,
)

/**
 * One Claude Code session's view, kept even while another session is active.
 *
 * Transcripts used to live in a single flat list that was cleared on every switch,
 * so moving between two projects destroyed the first conversation. Keying by the
 * bridge session id gives each one somewhere to persist.
 */
data class SessionState(
    val key: String,
    val project: String = "",
    val port: Int = 0,
    val transcript: List<TranscriptLine> = emptyList(),
    /** Live text from the MessageDisplay hook, i.e. what is on the terminal now. */
    val currentText: String = "",
)

data class WalkUiState(
    val sessions: Map<String, SessionState> = emptyMap(),
    val activeKey: String? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val activity: Activity = Activity.IDLE,
    /**
     * Whether Claude should be speaking at all, i.e. walking rather than at the desk.
     *
     * Distinct from [paused]. Off means Claude does not produce speech in the first
     * place, so no backlog accumulates while you work at the terminal. Paused means
     * it keeps producing and the phone holds it to replay in a moment.
     */
    val voiceActive: Boolean = false,
    /** Held utterances are not played and not acked, so nothing is lost. */
    val paused: Boolean = false,
    val pendingWhilePaused: Int = 0,
    /** Live partial transcription while the user is speaking. */
    val partial: String = "",
    /**
     * Published by the service once the speech engine binds. The picker only
     * exists after that, because before it there is genuinely nothing to choose.
     */
    val voices: List<VoiceOption> = emptyList(),
    /**
     * The tailnet is down, so the laptop is unreachable from here.
     *
     * Kept apart from [connection] because it is the one failure the user can
     * actually fix while walking. A plain "disconnected" covers three things and
     * only this one is worth taking the phone out for.
     */
    val tailnetDown: Boolean = false,
    /**
     * Claude is mid-turn: it has been given something and has not finished.
     *
     * Inferred from evidence the phone actually has, rather than guessed: a message
     * was just sent, or the terminal is producing output through the display hook.
     * Cleared at the turn boundary. Without it "Waiting" covered both "working hard"
     * and "idle, nothing happening", which are the two states you most want to tell
     * apart when you cannot see the screen.
     */
    val working: Boolean = false,
    /** Hands-free listening is on: no press needed before speaking. */
    val continuous: Boolean = false,
    /**
     * The microphone is deliberately closed in continuous mode.
     *
     * Distinct from simply not listening: muted means stay shut until told
     * otherwise, and survives Claude finishing an utterance, which would normally
     * hand the microphone straight back.
     */
    val micMuted: Boolean = false,
    /**
     * Recognised text held briefly before delivery, so it can still be stopped.
     *
     * Hands-free sends by itself, which left no moment to intervene: by the time
     * you reached for Discard the turn was already over and undoLast, which only
     * works inside a live turn, did nothing. The undo window was described in the
     * design and never actually existed. This is it.
     */
    val pendingSend: String = "",
    val error: String? = null,
) {
    val active: SessionState? get() = activeKey?.let { sessions[it] }
    val project: String get() = active?.project.orEmpty()
    val transcript: List<TranscriptLine> get() = active?.transcript.orEmpty()
    val currentText: String get() = active?.currentText.orEmpty()
}

/**
 * Single source of truth shared by the service and the UI.
 *
 * The service owns every long-lived resource and writes [state]; the UI reads it
 * and pushes user intent back through [commands]. This avoids binder plumbing
 * for what is a one-activity, one-service app.
 */
object WalkRepository {
    private const val TRANSCRIPT_LIMIT = 60

    private val _state = MutableStateFlow(WalkUiState())
    val state = _state.asStateFlow()

    private val _commands = MutableSharedFlow<WalkCommand>(extraBufferCapacity = 16)
    val commands = _commands.asSharedFlow()

    fun update(block: (WalkUiState) -> WalkUiState) = _state.update(block)

    /** Make [key] the active session, creating its state if this is the first sight of it. */
    fun activate(key: String, project: String, port: Int) = _state.update { s ->
        val existing = s.sessions[key] ?: SessionState(key)
        s.copy(
            activeKey = key,
            sessions = s.sessions + (key to existing.copy(project = project, port = port)),
        )
    }

    /** Append to a specific session's transcript, which may not be the active one. */
    fun addLine(key: String, role: String, text: String, heard: Boolean = true) = _state.update { s ->
        val session = s.sessions[key] ?: SessionState(key)
        val lines = (session.transcript + TranscriptLine(role, text, heard)).takeLast(TRANSCRIPT_LIMIT)
        s.copy(sessions = s.sessions + (key to session.copy(transcript = lines)))
    }

    /** Mark a previously queued line as played, so its unheard styling clears. */
    fun markHeard(key: String, text: String) = _state.update { s ->
        val session = s.sessions[key] ?: return@update s
        var done = false
        val lines = session.transcript.map {
            if (!done && !it.heard && it.text == text) { done = true; it.copy(heard = true) } else it
        }
        s.copy(sessions = s.sessions + (key to session.copy(transcript = lines)))
    }

    fun updateSession(key: String, block: (SessionState) -> SessionState) = _state.update { s ->
        val session = s.sessions[key] ?: SessionState(key)
        s.copy(sessions = s.sessions + (key to block(session)))
    }

    fun send(command: WalkCommand) {
        _commands.tryEmit(command)
    }
}

sealed interface WalkCommand {
    /** Context-sensitive earbud tap: stop if speaking, send if listening, else listen. */
    data object Tap : WalkCommand
    data object StopAudio : WalkCommand
    data object StartListening : WalkCommand
    data object TogglePause : WalkCommand

    /**
     * Switch between walking and being at the desk.
     *
     * Sessions are launched with the channel as a matter of course so voice is
     * there when wanted; this is what makes it wanted. Claude is told, so it knows
     * to start or stop speaking rather than talking to nobody.
     */
    data class SetVoiceActive(val active: Boolean) : WalkCommand
    data class SendText(val text: String) : WalkCommand
    data object Reconnect : WalkCommand

    /**
     * Throw away the turn being spoken instead of sending it.
     *
     * Recognition is imperfect, and noticing mid-sentence that it has garbled what
     * you said should not force you to send it and correct it afterwards.
     */
    data object DiscardTurn : WalkCommand

    /**
     * Drop the last utterance without ending the turn.
     *
     * The lighter-weight sibling of [DiscardTurn]: a flubbed sentence is the common
     * mistake, and losing the whole turn to fix one of them is a bad trade.
     */
    data object UndoLast : WalkCommand

    /** Apply a voice, rate and pitch change immediately, without waiting for a save. */
    data class SetVoice(val prefs: VoicePrefs) : WalkCommand

    /** Speak a sample line so a choice can be judged by ear rather than by name. */
    data object PreviewVoice : WalkCommand

    /** Bring Tailscale to the foreground so the tailnet can be switched back on. */
    data object OpenTailscale : WalkCommand

    /** Switch between hands-free listening and press-to-talk. */
    data class SetContinuous(val on: Boolean) : WalkCommand

    /** Close or reopen the microphone without leaving continuous mode. */
    data object ToggleMute : WalkCommand
}
