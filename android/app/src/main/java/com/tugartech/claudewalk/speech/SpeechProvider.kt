package com.tugartech.claudewalk.speech

/**
 * The speech engine sits behind an interface so the application is not
 * coupled to one vendor.
 *
 * The MVP ships [AndroidTtsProvider], which is free, offline and stops instantly.
 * Swapping in a premium cloud voice means adding one implementation and changing
 * one construction site; nothing else in the app knows the difference.
 */
interface SpeechProvider {

    /** Queue [text] for playback. [id] is echoed back through [onSpoken]. */
    fun speak(text: String, id: Long)

    /** Stop immediately and drop anything queued. Must be effectively instant. */
    fun stop()

    fun status(): SpeechStatus

    fun shutdown()

    /**
     * Voices this engine can offer, best first. Empty until the engine is ready, and
     * empty for good on an engine that offers no choice.
     */
    fun voices(): List<VoiceOption>

    /**
     * Apply the user's voice, rate and pitch. Safe to call before the engine binds:
     * the preference is held and applied on init.
     */
    fun configure(prefs: VoicePrefs)

    /** Called when an utterance finishes playing, so the caller can ack it. */
    var onSpoken: (id: Long) -> Unit
}

enum class SpeechStatus { UNAVAILABLE, IDLE, SPEAKING }

/**
 * How the user wants to be spoken to. [voiceId] is opaque and engine-specific;
 * blank means "whatever the engine picks for the device locale".
 */
data class VoicePrefs(
    val voiceId: String = "",
    val rate: Float = 1f,
    val pitch: Float = 1f,
) {
    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 1.5f
    }
}

/**
 * One selectable voice.
 *
 * [requiresNetwork] is the field that matters outdoors: a network voice goes silent
 * in a dead zone, which is the single failure mode this app was built to avoid, so
 * it is surfaced in the UI rather than buried.
 */
data class VoiceOption(
    val id: String,
    val label: String,
    val detail: String,
    val requiresNetwork: Boolean,
)
