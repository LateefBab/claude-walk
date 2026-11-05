package com.tugartech.claudewalk.media

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Short tones for actions taken with the phone in a pocket.
 *
 * A control you cannot see needs to answer back, or you have no way of knowing
 * whether the earbud press registered at all. Spoken confirmation is not an option
 * here: the microphone is open during a turn, so anything said aloud would be
 * transcribed straight back into the message it was confirming. A tone is short,
 * unmistakable, and not words.
 */
class AudioCue {

    // Half volume: this plays into earbuds that are already in someone's ears, and
    // it interrupts speech rather than accompanying it.
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 50) }
        .onFailure { Log.w(TAG, "tone generator unavailable: ${it.message}") }
        .getOrNull()

    /** Something you said was dropped. */
    fun undo() {
        runCatching { tones?.startTone(ToneGenerator.TONE_PROP_NACK, TONE_MS) }
    }

    /**
     * The link to the session is gone.
     *
     * Until this existed, a working agent and a dead line sounded identical: both
     * were silence. Distinguishing them is the entire point of the sound.
     */
    fun disconnected() {
        runCatching { tones?.startTone(ToneGenerator.TONE_SUP_ERROR, ERROR_MS) }
    }

    /** The link is back. Deliberately brief and pleasant. */
    fun reconnected() {
        runCatching { tones?.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS) }
    }

    /**
     * The tailnet is down, which is the only failure the user can fix from the
     * phone. Deliberately a different tone from [disconnected]: telling them apart
     * by ear is the entire point, since one means "go tap Tailscale" and the other
     * means "the session ended, nothing to do out here".
     */
    fun tailnetDown() {
        runCatching { tones?.startTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY, ERROR_MS) }
    }

    fun release() {
        runCatching { tones?.release() }
    }

    private companion object {
        const val TAG = "AudioCue"
        const val TONE_MS = 200
        const val ERROR_MS = 400
    }
}
