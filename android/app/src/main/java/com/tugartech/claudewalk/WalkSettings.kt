package com.tugartech.claudewalk

import android.content.Context
import com.tugartech.claudewalk.speech.VoicePrefs

/**
 * Where the bridge lives, how to authenticate to it, and how it should sound.
 *
 * Host is normally the Tailscale name of the development machine, so the phone
 * reaches it from any network without anything being exposed publicly.
 */
class WalkSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("claude-walk", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * A blank voice id means the engine default for the device locale, which is
     * what every install starts on. Rate and pitch default to the engine's own 1.0
     * so a fresh install sounds exactly as it did before this setting existed.
     */
    var voicePrefs: VoicePrefs
        get() = VoicePrefs(
            voiceId = prefs.getString(KEY_VOICE_ID, "") ?: "",
            rate = prefs.getFloat(KEY_RATE, 1f),
            pitch = prefs.getFloat(KEY_PITCH, 1f),
        )
        set(value) = prefs.edit()
            .putString(KEY_VOICE_ID, value.voiceId)
            .putFloat(KEY_RATE, value.rate)
            .putFloat(KEY_PITCH, value.pitch)
            .apply()

    /**
     * Hands-free listening: the microphone stays open and a pause sends the turn,
     * rather than requiring a press before every sentence. Off by default, because
     * outdoor noise makes an always-open microphone a reliable way to send garbage,
     * which is why this was deferred in the first place.
     */
    var continuousListening: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS, value).apply()

    val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank()

    val webSocketUrl: String
        get() = "ws://$host:$port/ws"

    companion object {
        const val DEFAULT_PORT = 8710
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_PITCH = "speech_pitch"
        private const val KEY_CONTINUOUS = "continuous_listening"
    }
}
