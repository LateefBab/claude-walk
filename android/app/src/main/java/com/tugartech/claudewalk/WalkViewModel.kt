package com.tugartech.claudewalk

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tugartech.claudewalk.net.DiscoveredSession
import com.tugartech.claudewalk.net.SessionDiscovery
import com.tugartech.claudewalk.speech.VoicePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val host: String = "",
    val port: Int = WalkSettings.DEFAULT_PORT,
    val token: String = "",
    val isConfigured: Boolean = false,
    val continuousListening: Boolean = false,
    val voice: VoicePrefs = VoicePrefs(),
)

data class SessionsUiState(
    val scanning: Boolean = false,
    val sessions: List<DiscoveredSession> = emptyList(),
    val activePort: Int = WalkSettings.DEFAULT_PORT,
    val error: String? = null,
)

class WalkViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = WalkSettings(app)

    val state = WalkRepository.state

    private val _settingsState = MutableStateFlow(settings.snapshot())
    val settingsState = _settingsState.asStateFlow()

    private val discovery = SessionDiscovery()

    private val _sessions = MutableStateFlow(SessionsUiState())
    val sessions = _sessions.asStateFlow()

    /** Scan the bridge port range for live sessions on the configured host. */
    fun refreshSessions() {
        if (!settings.isConfigured) return
        _sessions.value = _sessions.value.copy(scanning = true, error = null)
        viewModelScope.launch {
            val found = runCatching { discovery.scan(settings.host, settings.token) }
            _sessions.value = found.fold(
                onSuccess = { list ->
                    SessionsUiState(scanning = false, sessions = list, activePort = settings.port)
                },
                onFailure = { e ->
                    SessionsUiState(scanning = false, error = e.message ?: "Scan failed")
                },
            )
        }
    }

    /**
     * Switch the phone to a different live session, mid-walk.
     *
     * The previous session's transcript is deliberately left alone. It used to be
     * cleared here, which destroyed the conversation you were reading the moment you
     * looked at another project. State is keyed per session so both survive, and the
     * session you left keeps queueing its speech on its own bridge until you return.
     */
    fun connectToSession(session: DiscoveredSession) {
        settings.port = session.port
        _settingsState.value = settings.snapshot()
        _sessions.value = _sessions.value.copy(activePort = session.port)
        WalkRepository.activate(session.sessionId, session.project, session.port)
        WalkRepository.send(WalkCommand.Reconnect)
    }

    fun togglePause() = WalkRepository.send(WalkCommand.TogglePause)

    /** Walking or at the desk. Off means Claude does not speak at all. */
    fun setVoiceActive(active: Boolean) = WalkRepository.send(WalkCommand.SetVoiceActive(active))

    fun saveSettings(host: String, port: Int, token: String) {
        settings.host = host
        settings.port = port
        settings.token = token
        _settingsState.value = settings.snapshot()
        WalkRepository.send(WalkCommand.Reconnect)
    }

    /**
     * Voice changes apply the moment they are made rather than on a save button.
     * Judging a voice means hearing it, and hearing it means the engine already has
     * the setting, so a deferred save would only get in the way.
     */
    fun setVoice(prefs: VoicePrefs) {
        settings.voicePrefs = prefs
        _settingsState.value = settings.snapshot()
        WalkRepository.send(WalkCommand.SetVoice(prefs))
    }

    fun previewVoice() = WalkRepository.send(WalkCommand.PreviewVoice)
    fun openTailscale() = WalkRepository.send(WalkCommand.OpenTailscale)
    fun toggleMute() = WalkRepository.send(WalkCommand.ToggleMute)

    /** Hands-free listening, or press-to-talk. Applies at once and persists. */
    fun setContinuous(on: Boolean) {
        settings.continuousListening = on
        _settingsState.value = settings.snapshot()
        WalkRepository.send(WalkCommand.SetContinuous(on))
    }

    fun tap() = WalkRepository.send(WalkCommand.Tap)
    fun stopAudio() = WalkRepository.send(WalkCommand.StopAudio)
    fun discardTurn() = WalkRepository.send(WalkCommand.DiscardTurn)
    fun undoLast() = WalkRepository.send(WalkCommand.UndoLast)
    fun listen() = WalkRepository.send(WalkCommand.StartListening)
    fun reconnect() = WalkRepository.send(WalkCommand.Reconnect)

    /** Manual text entry, the fallback for when speech fails. */
    fun sendText(text: String) {
        if (text.isNotBlank()) WalkRepository.send(WalkCommand.SendText(text))
    }

    // Named rather than positional: two Booleans and a data class next to each
    // other made a field reorder a silent type error waiting to happen.
    private fun WalkSettings.snapshot() = SettingsUiState(
        host = host,
        port = port,
        token = token,
        isConfigured = isConfigured,
        voice = voicePrefs,
        continuousListening = continuousListening,
    )
}
