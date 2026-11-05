package com.tugartech.claudewalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tugartech.claudewalk.media.AudioCue
import com.tugartech.claudewalk.media.MediaButtonSession
import com.tugartech.claudewalk.net.BridgeClient
import com.tugartech.claudewalk.net.TailnetMonitor
import com.tugartech.claudewalk.speech.AndroidTtsProvider
import com.tugartech.claudewalk.speech.SpeechProvider
import com.tugartech.claudewalk.speech.SpeechStatus
import com.tugartech.claudewalk.speech.VoiceListener
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The whole app, really. The UI is a window onto this.
 *
 * Runs in the foreground so speech playback and microphone capture survive the
 * screen going off and the phone going into a pocket, which is the entire reason
 * this is a native app rather than a web page.
 */
class WalkService : LifecycleService() {

    private lateinit var settings: WalkSettings
    private lateinit var speech: SpeechProvider
    private lateinit var listener: VoiceListener
    private lateinit var bridge: BridgeClient
    private var mediaSession: MediaButtonSession? = null

    /** Audible acknowledgement for controls used with the screen off. */
    private val cue = AudioCue()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Last state actually announced, so only real transitions make a sound. */
    private var lastAnnounced: ConnectionState? = null
    private var dropoutReminder: Runnable? = null
    private var modeSwitchTimeout: Runnable? = null

    /** Delivers a staged message once its cancel window closes. */
    private var pendingSendTask: Runnable? = null

    /**
     * Reopens the microphone a beat after Claude stops, never instantly.
     *
     * TextToSpeech reports an utterance done when the engine has finished with it,
     * which is not when the sound stops coming out of the earbuds: Bluetooth keeps
     * a few hundred milliseconds of audio in flight. Reopening on the callback let
     * the recogniser hear the tail of Claude's own sentence and send it back as if
     * the user had said it. Held as a field so a second schedule cancels the first
     * rather than queueing another.
     */
    private val reopenMic = Runnable { resumeContinuousListening() }

    /**
     * The last thing Claude said, and when the audio stopped.
     *
     * Kept solely so Claude's own voice can be recognised coming back in. On the
     * phone's speaker the microphone is centimetres from the source, so the tail of
     * an utterance, and the room's reverb after it, are picked up cleanly. That is
     * an air path, not a pipeline delay, and no amount of waiting reliably closes
     * it: knowing the words is what closes it.
     */
    @Volatile
    private var lastSpoken: String = ""

    @Volatile
    private var lastSpokenEndedAt: Long = 0L

    /** Tells a dropped tailnet apart from a session that simply ended. */
    private lateinit var tailnet: TailnetMonitor

    /** Utterance id to the bridge session that issued it, so acks can be scoped. */
    private val utteranceSessions = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** Session key of whichever bridge this phone is currently attached to. */
    @Volatile
    private var activeKey: String = ""

    /**
     * Held while paused: not played, and deliberately not acked, so the utterance
     * stays outstanding on the bridge and the unheard count is true from both ends.
     */
    private val paused = java.util.concurrent.atomic.AtomicBoolean(false)
    private val heldWhilePaused = java.util.Collections.synchronizedList(mutableListOf<Held>())

    /**
     * Every (session, id) already played or held.
     *
     * The bridge replays unacked items whenever a phone connects, so a pause that
     * spans a reconnect would otherwise deliver the same utterance twice and speak
     * it twice on resume.
     */
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class Held(val id: Long, val session: String, val text: String)

    override fun onCreate() {
        super.onCreate()
        settings = WalkSettings(this)

        createNotificationChannel()
        startForeground(buildNotification())

        speech = AndroidTtsProvider(this, settings.voicePrefs) { ok ->
            if (!ok) {
                WalkRepository.update { it.copy(error = "Speech engine unavailable") }
            } else {
                // Enumeration is only meaningful once the engine has bound, which
                // is why the picker is driven from state rather than queried by the UI.
                val voices = speech.voices()
                Log.i(TAG, "speech engine offers ${voices.size} voice(s) for this language")
                WalkRepository.update { it.copy(voices = voices) }
            }
        }.apply {
            onSpoken = { id ->
                // Ack against the session that issued the utterance. Ids restart at 1
                // in each bridge process, so an unscoped ack can clear a different
                // session's outstanding item and mark an unheard update as delivered.
                val session = utteranceSessions.remove(id)
                if (session != null) {
                    bridge.send(
                        JSONObject().put("type", "ack").put("id", id).put("session", session),
                    )
                }
                lastSpokenEndedAt = android.os.SystemClock.elapsedRealtime()
                refreshActivity()
                // Claude has stopped talking. Wait for the audio to drain before
                // reopening, or the recogniser transcribes the tail of it.
                scheduleMicReopen()
            }
        }

        listener = VoiceListener(
            context = this,
            onPartial = { partial ->
                // A partial result is proof the microphone is live, and a more
                // reliable one than the activity bookkeeping: refreshActivity can
                // leave the state IDLE across a restart while words are genuinely
                // arriving. The display used to be gated on that state, so live
                // text showed sometimes and not others. Say so here instead.
                WalkRepository.update {
                    if (partial.isNotBlank()) it.copy(partial = partial, activity = Activity.LISTENING)
                    else it.copy(partial = partial)
                }
            },
            onResult = { text ->
                // Self-reporting interlock test. If a whole turn is recognised while
                // Claude is audible, the microphone was open when it should not have
                // been, and the text is very likely Claude's own voice. Logged loudly
                // rather than silently accepted, so an end-to-end check is one grep.
                if (speech.status() == SpeechStatus.SPEAKING) {
                    Log.e(TAG, "INTERLOCK VIOLATION: recognised '${text.take(40)}' while speaking")
                }
                stageSpokenMessage(text)
            },
            onError = { message -> WalkRepository.update { it.copy(error = message) } },
            onEnd = {
                refreshActivity()
                // The loop that makes hands-free hands-free: one turn ends, the
                // next begins, without a press.
                resumeContinuousListening()
            },
            onUndo = { cue.undo() },
        ).apply { continuous = settings.continuousListening }

        // Hands-free starts muted, always. An open microphone that nobody asked for
        // is a recording device: switching to a session used to hand it the room,
        // silently, and the first anyone knew was reading their own private
        // conversation in the transcript. Arriving muted costs one tap and cannot
        // do that.
        WalkRepository.update {
            it.copy(continuous = settings.continuousListening, micMuted = settings.continuousListening)
        }

        mediaSession = MediaButtonSession(
            context = this,
            onTap = { handleTap() },
            // Double tap is contextual. While you are talking it drops the last
            // thing you said, not the whole turn: a flubbed sentence is the common
            // mistake, and an accidental double tap should cost one sentence rather
            // than everything. Tapping repeatedly walks back utterance by utterance
            // and ends at an empty turn, so binning the lot is still reachable
            // without a second gesture to remember. Otherwise it stops the audio.
            onDoubleTap = {
                when {
                    cancelPendingSend() -> Unit
                    listener.isTurnActive -> listener.undoLast()
                    else -> stopAudio()
                }
            },
        )

        tailnet = TailnetMonitor(this, ::onTailnetChanged).also { it.start() }

        bridge = BridgeClient(
            settings = settings,
            onFrame = ::handleFrame,
            onConnectionState = { state ->
                announceConnection(state)
                WalkRepository.update {
                    // Clear any stale failure once the link is back. A leftover
                    // "not connected" on a screen glanced at mid-walk reads as
                    // broken even when the green dot says otherwise.
                    if (state == ConnectionState.CONNECTED) it.copy(connection = state, error = null)
                    else it.copy(connection = state)
                }
                updateNotification()
            },
        )
        bridge.connect()

        lifecycleScope.launch {
            WalkRepository.commands.collect(::handleCommand)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_AUDIO -> stopAudio()
            ACTION_LISTEN -> startListening()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }
        // Restart if the system kills us mid-walk; the bridge replays anything missed.
        return START_STICKY
    }

    override fun onDestroy() {
        listener.destroy()
        speech.shutdown()
        bridge.shutdown()
        mediaSession?.release()
        mediaSession = null
        dropoutReminder?.let(handler::removeCallbacks)
        modeSwitchTimeout?.let(handler::removeCallbacks)
        handler.removeCallbacks(::announceStartupFailure)
        handler.removeCallbacks(reopenMic)
        pendingSendTask?.let(handler::removeCallbacks)
        tailnet.stop()
        cue.release()
        WalkRepository.update { it.copy(connection = ConnectionState.DISCONNECTED, activity = Activity.IDLE) }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    private fun handleCommand(command: WalkCommand) = when (command) {
        WalkCommand.Tap -> handleTap()
        WalkCommand.StopAudio -> stopAudio()
        WalkCommand.StartListening -> startListening()
        WalkCommand.TogglePause -> togglePause()
        WalkCommand.Reconnect -> bridge.reconnectNow()
        WalkCommand.DiscardTurn -> discardTurn()
        is WalkCommand.SetVoiceActive -> setVoiceActive(command.active)
        WalkCommand.UndoLast -> listener.undoLast()
        is WalkCommand.SendText -> sendSpokenMessage(command.text)
        is WalkCommand.SetVoice -> speech.configure(command.prefs)
        WalkCommand.PreviewVoice -> previewVoice()
        WalkCommand.OpenTailscale -> openTailscale()
        is WalkCommand.SetContinuous -> setContinuous(command.on)
        WalkCommand.ToggleMute -> toggleMute()
    }

    /**
     * Speak a sample so a voice can be judged by ear.
     *
     * The id is deliberately absent from [utteranceSessions], so when it completes
     * the ack lookup misses and nothing is sent to the bridge. A preview must never
     * clear a real outstanding utterance.
     */
    /**
     * Switch between hands-free and press-to-talk.
     *
     * Turning it off closes an open microphone immediately rather than waiting for
     * the current turn to end, because the press-to-talk contract is that the
     * microphone is shut unless you are holding it open.
     */
    private fun setContinuous(on: Boolean) {
        settings.continuousListening = on
        listener.continuous = on
        // Switching into hands-free starts muted too. Choosing the mode is not the
        // same as choosing to be listened to, and the difference matters most in the
        // moment you are choosing, which is usually mid-conversation with someone.
        WalkRepository.update { it.copy(continuous = on, micMuted = on) }
        listener.cancel()
        refreshActivity()
    }

    /** Close or reopen the microphone without leaving hands-free mode. */
    private fun toggleMute() {
        val muted = !WalkRepository.state.value.micMuted
        // Clear any stale message as well: pressing a control and being left looking
        // at an old error is indistinguishable from the control having caused it.
        WalkRepository.update { it.copy(micMuted = muted, error = null) }
        cue.undo()
        if (muted) listener.cancel() else resumeContinuousListening()
        refreshActivity()
        updateNotification()
    }

    /**
     * Hand the microphone back, if everything that could want it is finished.
     *
     * This is the whole of hands-free mode: after each turn is sent, and after each
     * thing Claude says, the microphone reopens by itself. Every condition here is a
     * reason it must not: muted is a deliberate choice to stay shut, desk mode means
     * the phone is out of the session entirely, and reopening while Claude is
     * speaking would transcribe Claude's own voice straight back through the
     * earbuds and send it to Claude as if the user had said it.
     */
    /**
     * Whether this is Claude's own voice, heard through the phone's own speaker.
     *
     * Deliberately narrow, because the cost of a false positive is swallowing
     * something the user actually said. All three must hold: it arrived within a
     * few seconds of Claude finishing, it is short enough to be a fragment rather
     * than a sentence someone meant, and its words appear in what Claude just said.
     * Repeating a phrase back at Claude on purpose is nearly always longer than the
     * limit, and quoting it after a pause falls outside the window.
     */
    private fun isEchoOfClaude(text: String): Boolean {
        val spoken = lastSpoken
        if (spoken.isEmpty()) return false

        val since = android.os.SystemClock.elapsedRealtime() - lastSpokenEndedAt
        if (lastSpokenEndedAt == 0L || since > ECHO_WINDOW_MS) return false

        val heard = normalise(text)
        if (heard.isEmpty()) return false
        if (heard.split(' ').size > ECHO_MAX_WORDS) return false

        return normalise(spoken).contains(heard)
    }

    /** Lowercase, punctuation stripped, single-spaced, so wording can be compared. */
    private fun normalise(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    /** Reopen after the audio pipeline has drained, replacing any pending attempt. */
    private fun scheduleMicReopen(delayMs: Long = AUDIO_DRAIN_MS) {
        handler.removeCallbacks(reopenMic)
        handler.postDelayed(reopenMic, delayMs)
    }

    /**
     * The single authority on whether the microphone may be open.
     *
     * Returns null when it may, or the reason it may not. Every path that wants to
     * open the microphone asks this, rather than each one carrying its own partial
     * copy of the rules. The previous arrangement had five callers and three of the
     * conditions were checked in only one of them.
     *
     * The drain is the subtle one. TextToSpeech reports an utterance done when the
     * engine has finished with it, so `speech.status()` is already IDLE while sound
     * is still leaving the earbuds or the speaker. A timer alone does not cover it,
     * because the timer only guards the path that sets it: any other caller opening
     * the microphone in that window walks straight into Claude's own audio. So the
     * drain is a condition here, checked by everyone, rather than a delay owned by
     * one caller.
     */
    private fun micBlockedBecause(): String? {
        val state = WalkRepository.state.value
        if (!listener.continuous) return "press-to-talk"
        if (!state.voiceActive) return "desk mode"
        if (state.micMuted) return "muted"
        if (state.paused) return "paused"
        if (speech.status() == SpeechStatus.SPEAKING) return "Claude is speaking"
        if (drainRemainingMs() > 0) return DRAINING
        return null
    }

    /** Milliseconds of Claude's audio that may still be in the air. */
    private fun drainRemainingMs(): Long {
        if (lastSpokenEndedAt == 0L) return 0
        val since = android.os.SystemClock.elapsedRealtime() - lastSpokenEndedAt
        return (AUDIO_DRAIN_MS - since).coerceAtLeast(0)
    }

    /**
     * Hand the microphone back, if everything that could want it is finished.
     *
     * A request blocked only by the drain is rescheduled rather than dropped: the
     * caller asked for the microphone and is entitled to it a moment later, and
     * silently discarding the request left hands-free deaf until something else
     * happened to ask again.
     */
    private fun resumeContinuousListening() {
        if (listener.isTurnActive) return

        val blocked = micBlockedBecause()
        if (blocked != null) {
            if (blocked == DRAINING) {
                val remaining = drainRemainingMs()
                // Logged like every other refusal. This branch used to reschedule in
                // silence, which made the one path worth watching the only one that
                // left no trace: a test could not tell "the drain held" apart from
                // "nothing ever asked during the drain".
                Log.i(TAG, "microphone stays shut: $blocked (${remaining}ms left)")
                scheduleMicReopen(remaining + DRAIN_MARGIN_MS)
            } else {
                Log.i(TAG, "microphone stays shut: $blocked")
            }
            return
        }

        Log.i(TAG, "MIC OPEN")
        listener.start()
        WalkRepository.update { it.copy(activity = Activity.LISTENING, partial = "", error = null) }
        updateNotification()
    }

    /**
     * Close the microphone because Claude is about to be audible.
     *
     * Anything already said is delivered rather than binned, so words spoken right
     * up to the moment Claude starts are not lost.
     */
    private fun closeMicForSpeech() {
        if (listener.continuous && listener.isTurnActive) {
            Log.i(TAG, "MIC CLOSED: Claude is about to speak")
            listener.stop()
        }
    }

    private fun openTailscale() {
        if (!tailnet.openTailscale()) {
            WalkRepository.update { it.copy(error = "Tailscale does not appear to be installed") }
        }
    }

    /**
     * The tailnet went up or down under us.
     *
     * Only meaningful when the configured host is a tailnet address; on a host that
     * is reachable without a VPN this says nothing useful, so nothing is claimed.
     * Announced immediately rather than waiting for the socket to time out, because
     * the whole value is knowing before you have stood there wondering.
     */
    private fun onTailnetChanged(up: Boolean) {
        if (!TailnetMonitor.isTailnetHost(settings.host)) return

        WalkRepository.update {
            if (up) it.copy(tailnetDown = false, error = null)
            else it.copy(tailnetDown = true, error = "Tailnet is down. The laptop is unreachable until Tailscale is back on.")
        }
        if (up) cue.reconnected() else cue.tailnetDown()
        updateNotification()
    }

    /**
     * Say out loud when the link dies and when it comes back.
     *
     * Silence used to mean two completely different things: the agent is working,
     * or the connection is gone and nothing will ever arrive. From a pocket they
     * were indistinguishable, so a walk could be spent waiting on a dead line.
     *
     * Only transitions are announced, and CONNECTING is ignored: reconnection
     * attempts cycle through it constantly on a walk and beeping at each one would
     * be unbearable. A dropout repeats at a slow interval, because the one you
     * miss is the one that costs you the walk, then falls silent rather than
     * nagging forever once the session is genuinely over.
     */
    private fun announceConnection(state: ConnectionState) {
        if (state == ConnectionState.CONNECTING) return
        if (state == lastAnnounced) return

        val first = lastAnnounced == null
        lastAnnounced = state

        dropoutReminder?.let(handler::removeCallbacks)
        dropoutReminder = null

        when (state) {
            ConnectionState.CONNECTED -> {
                // Silent on the first connect of a session: you just opened the
                // app, you already know it worked.
                if (!first) cue.reconnected()
            }
            ConnectionState.DISCONNECTED -> {
                // The tailnet has its own, different tone and its own message; two
                // sounds for one cause would just be noise.
                if (WalkRepository.state.value.tailnetDown) return
                if (first) {
                    // The app is always briefly disconnected while the first socket
                    // opens, so beeping instantly would cry wolf on every launch.
                    // But staying silent forever was worse: opening the app to a
                    // session that never connects produced no sound at all, because
                    // the rule meant to skip a *successful* first connect also
                    // swallowed a failed one. Give it a grace period, then say so.
                    handler.postDelayed(::announceStartupFailure, STARTUP_GRACE_MS)
                    return
                }
                cue.disconnected()
                scheduleDropoutReminder(1)
            }
            ConnectionState.CONNECTING -> Unit
        }
    }

    /**
     * Still not connected a little after launch, so this is not the normal
     * momentary gap while the first socket opens. Worth a sound and a sentence,
     * because otherwise opening the app to a dead link looks identical to opening
     * it to a working one that simply has nothing to say yet.
     */
    private fun announceStartupFailure() {
        val s = WalkRepository.state.value
        if (s.connection == ConnectionState.CONNECTED || s.tailnetDown) return
        cue.disconnected()
        WalkRepository.update {
            it.copy(error = "No session reachable. Check a voice-enabled session is running, and that Tailscale is on.")
        }
        updateNotification()
        scheduleDropoutReminder(1)
    }

    private fun scheduleDropoutReminder(count: Int) {
        if (count > MAX_DROPOUT_REMINDERS) return
        val task = Runnable {
            if (WalkRepository.state.value.connection != ConnectionState.CONNECTED) {
                cue.disconnected()
                scheduleDropoutReminder(count + 1)
            }
        }
        dropoutReminder = task
        handler.postDelayed(task, DROPOUT_REMINDER_MS)
    }

    /**
     * Ask the bridge to switch between walking and being at the desk.
     *
     * The bridge is the source of truth: it echoes the new state back in a status
     * frame and tells Claude, so the button never sets local state directly.
     *
     * That round trip is also why a failure used to be invisible. A bridge from
     * before this feature existed does not recognise the frame, drops it, and
     * answers nothing, so the button appeared dead with no explanation anywhere.
     * A pressed control that does nothing and says nothing is the worst outcome
     * available, so an unanswered switch now reports itself.
     */
    private fun setVoiceActive(active: Boolean) {
        // Going quiet has to be instant and local. The bridge stops *producing*
        // speech when it hears about the change, but that says nothing about the
        // sentence already playing in your ears, and waiting for a round trip
        // before silencing it is a round trip of Claude still talking at you.
        // Anything queued goes too: a backlog assembled while walking is worthless
        // by the time you have sat down.
        if (!active) {
            speech.stop()
            listener.cancel()
            heldWhilePaused.clear()
            WalkRepository.update { it.copy(pendingWhilePaused = 0, partial = "") }
            refreshActivity()
        }

        val delivered = bridge.send(JSONObject().put("type", "voice").put("active", active))
        if (!delivered) {
            WalkRepository.update { it.copy(error = "Not connected, cannot switch mode") }
            return
        }
        modeSwitchTimeout?.let(handler::removeCallbacks)
        val task = Runnable {
            if (WalkRepository.state.value.voiceActive != active) {
                Log.w(TAG, "bridge did not acknowledge voice=$active")
                WalkRepository.update {
                    it.copy(error = "This session's bridge did not respond. It may predate this feature: restart it with /mcp reconnect claude-walk.")
                }
            }
        }
        modeSwitchTimeout = task
        handler.postDelayed(task, MODE_SWITCH_TIMEOUT_MS)
    }

    private fun previewVoice() {
        speech.stop()
        speech.speak(getString(R.string.voice_preview), PREVIEW_UTTERANCE_ID)
        WalkRepository.update { it.copy(activity = Activity.CLAUDE_SPEAKING) }
    }

    /**
     * Pause holds incoming speech without acking it, so the bridge keeps counting it
     * as outstanding and nothing can be lost. Resume plays the backlog oldest first.
     */
    private fun togglePause() {
        if (paused.compareAndSet(false, true)) {
            speech.stop()
            WalkRepository.update { it.copy(paused = true) }
        } else {
            paused.set(false)
            val backlog: List<Held>
            synchronized(heldWhilePaused) {
                backlog = heldWhilePaused.toList()
                heldWhilePaused.clear()
            }
            Log.i(TAG, "resuming, playing ${backlog.size} held utterance(s)")
            // The backlog is Claude's audio like any other, so the microphone has to
            // shut for it. This path bypassed the interlock entirely: it called
            // speak() directly while hands-free held the microphone wide open.
            if (backlog.isNotEmpty()) closeMicForSpeech()
            backlog.forEach { held ->
                WalkRepository.markHeard(held.session, held.text)
                speech.speak(held.text, held.id)
            }
            WalkRepository.update { it.copy(paused = false, pendingWhilePaused = 0) }
        }
        refreshActivity()
    }

    /**
     * One button, three meanings, chosen by what is happening right now:
     * speaking means stop, listening means send, otherwise start listening.
     */
    private fun handleTap() {
        when {
            speech.status() == SpeechStatus.SPEAKING -> stopAudio()
            // isTurnActive, not isListening: a turn spans the gaps between
            // recognition segments, and a tap landing in one of those gaps must
            // send what has been said rather than start over and discard it.
            listener.isTurnActive -> listener.stop()
            else -> startListening()
        }
    }

    private fun stopAudio() {
        speech.stop()
        // Cutting a sentence off leaves the same tail in flight as letting it end,
        // and onDone never fires on this path, so the drain clock is set here.
        lastSpokenEndedAt = android.os.SystemClock.elapsedRealtime()
        refreshActivity()
        // Cutting Claude off mid-sentence leaves the same audio tail in flight as
        // letting the sentence finish, so the same wait applies.
        scheduleMicReopen()
    }

    /** Bin the turn being spoken. Nothing is sent and nothing is logged as said. */
    private fun discardTurn() {
        // A staged message is the more recent thing to throw away, and the one the
        // user is almost certainly reaching for.
        if (cancelPendingSend()) return
        listener.discard()
        WalkRepository.update { it.copy(partial = "", activity = Activity.IDLE, error = null) }
        refreshActivity()
    }

    private fun startListening() {
        // The earbud button reaches this too, so the mode has to be enforced here
        // and not only by grey buttons: at the desk, opening the microphone would
        // send words to a session that answers in a terminal you are not holding.
        if (!WalkRepository.state.value.voiceActive) {
            Log.i(TAG, "ignoring listen request: voice is off")
            return
        }
        // Never record our own speech back into the recognizer.
        if (speech.status() == SpeechStatus.SPEAKING) speech.stop()
        mediaSession?.refresh()
        listener.start()
        WalkRepository.update { it.copy(activity = Activity.LISTENING, partial = "", error = null) }
        updateNotification()
    }

    /**
     * Stage a hands-free message, then send it unless it is cancelled first.
     *
     * Press-to-talk needs none of this: the button press IS the decision. Hands-free
     * makes the decision for you the moment you stop talking, so without a pause
     * there is no point at which a mishearing can be caught, and the recogniser
     * mishears often enough that this matters.
     */
    private fun stageSpokenMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!listener.continuous) {
            sendSpokenMessage(trimmed)
            return
        }

        pendingSendTask?.let(handler::removeCallbacks)
        WalkRepository.update { it.copy(pendingSend = trimmed, partial = trimmed) }
        val task = Runnable {
            val staged = WalkRepository.state.value.pendingSend
            if (staged.isNotEmpty()) {
                WalkRepository.update { it.copy(pendingSend = "") }
                sendSpokenMessage(staged)
            }
        }
        pendingSendTask = task
        handler.postDelayed(task, SEND_HOLD_MS)
    }

    /** Throw away a staged message before it is delivered. Returns true if there was one. */
    private fun cancelPendingSend(): Boolean {
        if (WalkRepository.state.value.pendingSend.isEmpty()) return false
        pendingSendTask?.let(handler::removeCallbacks)
        pendingSendTask = null
        WalkRepository.update { it.copy(pendingSend = "", partial = "") }
        cue.undo()
        Log.i(TAG, "staged message cancelled before sending")
        refreshActivity()
        return true
    }

    private fun sendSpokenMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            Log.w(TAG, "sendSpokenMessage: empty text, nothing to send")
            return
        }
        if (isEchoOfClaude(trimmed)) {
            Log.i(TAG, "dropping '${trimmed.take(40)}': it is Claude's own audio coming back")
            WalkRepository.update { it.copy(partial = "") }
            refreshActivity()
            return
        }
        // Deliberately keeps `partial`. Blanking it here removed the text at the
        // one moment it is worth reading, leaving nothing on screen until the
        // bridge echoed the line back. It is cleared when the next turn starts.
        WalkRepository.update { it.copy(activity = Activity.SENDING) }
        val delivered = bridge.send(JSONObject().put("type", "user").put("text", trimmed))
        // Handing Claude something is the clearest evidence it is about to work.
        if (delivered) WalkRepository.update { it.copy(working = true) }
        Log.i(TAG, "sendSpokenMessage len=${trimmed.length} delivered=$delivered")
        if (!delivered) {
            WalkRepository.update {
                it.copy(error = "Not connected, message not sent", activity = Activity.IDLE)
            }
        }
        refreshActivity()
    }

    // -----------------------------------------------------------------------
    // Bridge frames
    // -----------------------------------------------------------------------

    private fun handleFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "speak" -> {
                val id = frame.optLong("id")
                val text = frame.optString("text")
                val session = frame.optString("session").ifEmpty { activeKey }
                if (text.isBlank()) return

                // Defensive: the bridge stops producing speech when voice is off,
                // but a frame already in flight when the mode changed must not be
                // spoken into a room the user is sitting in.
                if (!WalkRepository.state.value.voiceActive) {
                    Log.i(TAG, "dropping speak $id: voice is off")
                    return
                }

                // The bridge replays unacked items on every connect, so the same
                // utterance can legitimately arrive more than once.
                if (!seen.add("$session#$id")) {
                    Log.i(TAG, "ignoring duplicate speak $session#$id")
                    return
                }
                utteranceSessions[id] = session

                if (paused.get()) {
                    heldWhilePaused.add(Held(id, session, text))
                    WalkRepository.addLine(session, "claude", text, heard = false)
                    WalkRepository.update { it.copy(pendingWhilePaused = heldWhilePaused.size) }
                    updateNotification()
                    return
                }

                closeMicForSpeech()

                lastSpoken = text
                WalkRepository.addLine(session, "claude", text)
                speech.speak(text, id)
                WalkRepository.update { it.copy(activity = Activity.CLAUDE_SPEAKING, error = null) }
                updateNotification()
            }

            "stop" -> stopAudio()

            "status" -> {
                // Broadcast by the Stop hook at every turn boundary, and on connect.
                // Both are moments when Claude is definitively not mid-turn.
                WalkRepository.update { it.copy(working = false) }
                // First frame after a successful hello: this is how the phone learns
                // which session it is attached to, and therefore where to file frames.
                val key = frame.optString("session_id").ifEmpty { frame.optString("project") }
                if (key.isNotEmpty()) {
                    // Arriving somewhere new closes the microphone. Only on a change
                    // of session, never on the status frames that arrive at every
                    // turn boundary, or hands-free would mute itself mid-sentence.
                    if (key != activeKey && listener.continuous) {
                        Log.i(TAG, "arrived at a new session: starting muted")
                        WalkRepository.update { it.copy(micMuted = true) }
                        listener.cancel()
                    }
                    activeKey = key
                    WalkRepository.activate(key, frame.optString("project"), settings.port)
                }
                // The bridge owns this state, so the phone reflects rather than
                // assumes it. That keeps the two in step across a reconnect.
                val active = frame.optBoolean("voice_active")
                WalkRepository.update { it.copy(voiceActive = active) }
                updateNotification()
                // Setting off on a walk with hands-free on should open the
                // microphone straight away, without a press to get started.
                if (active) resumeContinuousListening() else listener.cancel()
            }

            "display" -> {
                val delta = frame.optString("delta")
                WalkRepository.update { it.copy(working = true) }
                val key = activeKey
                WalkRepository.updateSession(key) { s ->
                    val next = if (frame.optBoolean("final")) "" else s.currentText + delta
                    s.copy(currentText = next.takeLast(CURRENT_TEXT_LIMIT))
                }
            }

            "transcript" -> {
                // Sent on connect. This is what makes a transcript survive an app
                // restart: the bridge keeps its own ring buffer and replays it.
                val entries = frame.optJSONArray("entries") ?: return
                val lines = (0 until entries.length()).mapNotNull { i ->
                    entries.optJSONObject(i)?.let { TranscriptLine(it.optString("role"), it.optString("text")) }
                }
                if (lines.isNotEmpty()) {
                    WalkRepository.updateSession(activeKey) { it.copy(transcript = lines) }
                }
            }

            "transcript_add" ->
                WalkRepository.addLine(activeKey, frame.optString("role"), frame.optString("text"))

            "silent_turn" -> {
                WalkRepository.update { it.copy(working = false) }
                refreshActivity()
            }

            else -> Log.d(TAG, "ignoring frame: ${frame.optString("type")}")
        }
    }

    private fun refreshActivity() {
        val activity = when {
            speech.status() == SpeechStatus.SPEAKING -> Activity.CLAUDE_SPEAKING
            // Turn-level, so the indicator does not flicker to "waiting" during
            // the gaps between recognition segments.
            listener.isTurnActive -> Activity.LISTENING
            else -> Activity.IDLE
        }
        WalkRepository.update { it.copy(activity = activity) }
        updateNotification()
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun action(name: String, label: String, icon: Int): NotificationCompat.Action {
        val intent = Intent(this, WalkService::class.java).setAction(name)
        val pending = PendingIntent.getService(
            this, name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }

    private fun buildNotification(): Notification {
        val state = WalkRepository.state.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val status = when {
            state.paused -> {
                val n = state.pendingWhilePaused
                if (n > 0) "${getString(R.string.status_paused)} · $n waiting" else getString(R.string.status_paused)
            }
            else -> when (state.connection) {
            ConnectionState.CONNECTED -> when (state.activity) {
                Activity.CLAUDE_SPEAKING -> getString(R.string.status_speaking)
                Activity.LISTENING -> getString(R.string.status_listening)
                Activity.SENDING -> getString(R.string.status_sending)
                Activity.IDLE -> getString(R.string.status_connected)
            }
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.project.ifBlank { getString(R.string.app_name) })
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Reachable from the lock screen, which is where they are needed.
            .addAction(action(ACTION_LISTEN, getString(R.string.action_listen), android.R.drawable.ic_btn_speak_now))
            .addAction(action(ACTION_STOP_AUDIO, getString(R.string.action_stop_audio), android.R.drawable.ic_media_pause))
            .addAction(
                action(
                    ACTION_TOGGLE_PAUSE,
                    if (state.paused) {
                        val n = state.pendingWhilePaused
                        if (n > 0) getString(R.string.action_resume_count, n) else getString(R.string.action_resume)
                    } else {
                        getString(R.string.action_pause)
                    },
                    android.R.drawable.ic_media_play,
                ),
            )
            .build()
    }

    private fun startForeground(notification: Notification) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "WalkService"
        private const val CHANNEL_ID = "claude_walk"
        private const val NOTIFICATION_ID = 1
        private const val CURRENT_TEXT_LIMIT = 2000

        /** Negative so it can never collide with a bridge-issued id, which start at 1. */
        private const val PREVIEW_UTTERANCE_ID = -1L

        /** Slow enough not to nag, often enough that a missed dropout is caught. */
        private const val DROPOUT_REMINDER_MS = 90_000L

        /** After this many, the session is presumably over and you know. */
        private const val MAX_DROPOUT_REMINDERS = 4

        /** Long enough to cover a normal first connect, short enough to still be feedback. */
        private const val STARTUP_GRACE_MS = 20_000L

        /**
         * How long Bluetooth keeps playing after TextToSpeech says it has finished.
         * Generous on purpose: waiting an extra fraction of a second before you can
         * talk costs nothing, and reopening early puts Claude's own words into your
         * next message.
         */
        private const val AUDIO_DRAIN_MS = 700L

        /** Reason string for a microphone blocked only by audio still in the air. */
        private const val DRAINING = "audio still draining"

        /** Small cushion so a rescheduled reopen lands after the drain, not on it. */
        private const val DRAIN_MARGIN_MS = 50L

        /**
         * How long a hands-free message is held before delivery, so it can be
         * stopped. Long enough to read the line and react, short enough that a
         * conversation does not feel laggy.
         */
        private const val SEND_HOLD_MS = 2_500L

        /** How long after Claude stops that its own words can still arrive. */
        private const val ECHO_WINDOW_MS = 6_000L

        /**
         * Longest fragment treated as an echo. A tail caught by the microphone is a
         * few words; a sentence the user meant is usually more, and swallowing one
         * of those is much worse than letting a stray fragment through.
         */
        private const val ECHO_MAX_WORDS = 12

        /** Long enough for a round trip over a mobile link, short enough to feel like feedback. */
        private const val MODE_SWITCH_TIMEOUT_MS = 4_000L

        const val ACTION_STOP_AUDIO = "com.tugartech.claudewalk.STOP_AUDIO"
        const val ACTION_LISTEN = "com.tugartech.claudewalk.LISTEN"
        const val ACTION_TOGGLE_PAUSE = "com.tugartech.claudewalk.TOGGLE_PAUSE"

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, WalkService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, WalkService::class.java))
        }
    }
}
