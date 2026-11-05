package com.tugartech.claudewalk.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.tugartech.claudewalk.R

/**
 * Makes the earbud button reach the app.
 *
 * Android routes MEDIA_BUTTON events to the most recently active media session,
 * so the app holds one and reports STATE_PLAYING for as long as the walk service
 * runs. That is what turns a single earbud tap into a hands-free control while
 * the phone is in a pocket with the screen off.
 *
 * Known limitation: if the user starts music, that app becomes the active
 * session and takes the button until Claude Walk speaks again.
 */
class MediaButtonSession(
    context: Context,
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
) {
    private val session = MediaSessionCompat(context, TAG).apply {
        setCallback(object : MediaSessionCompat.Callback() {
            // A single tap on most earbuds arrives as play or pause depending on
            // the reported playback state. Both mean the same thing here: the
            // service decides what a tap does based on what is happening.
            override fun onPlay() = onTap()
            override fun onPause() = onTap()

            // Double tap. Also context-sensitive: it stops playback while Claude
            // is speaking, and undoes the last thing said while you are talking.
            override fun onSkipToNext() = onDoubleTap()
            override fun onStop() = onDoubleTap()
        })
        isActive = true
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Holding audio focus is what actually makes this the system's "media button
     * session". Verified on hardware: with an active session and STATE_PLAYING but
     * no focus, `dumpsys media_session` reports `Media button session is null` and
     * earbud presses go to whoever held focus last. Setting the playback state is
     * necessary but not sufficient.
     */
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Claude Walk is the audio app for the duration of a walk, so it does not
        // hand focus back on transient loss; it re-asserts on the next utterance.
        .setOnAudioFocusChangeListener { change ->
            Log.i(TAG, "audio focus changed: $change")
        }
        .build()

    /**
     * A silent, looping, zero-volume track.
     *
     * Verified on hardware that this is unavoidable: `TextToSpeech` output is not
     * attributed to our MediaSession, so with only an active session,
     * STATE_PLAYING and audio focus, `dumpsys media_session` still reported
     * `Media button session is null` and earbud presses were routed to YouTube,
     * the last app to genuinely play media. Android promotes a session to receive
     * media buttons based on real playback, so the app has to actually be
     * playing something. It costs almost nothing next to a foreground service
     * that is already awake.
     */
    private val keepAlive: MediaPlayer? = if (!KEEPALIVE_ENABLED) null else runCatching {
        MediaPlayer.create(context, R.raw.silence)?.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            isLooping = true
            setVolume(0f, 0f)
            start()
        }
    }.onFailure { Log.w(TAG, "keepalive track failed: ${it.message}") }.getOrNull()

    init {
        acquireFocus()
        publishState()
    }

    private fun acquireFocus() {
        if (!KEEPALIVE_ENABLED) return
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus not granted ($result); earbud buttons may go elsewhere")
        }
    }

    /**
     * Reported as PLAYING throughout. The app is not playing a track, but this is
     * what keeps media buttons routed here rather than to the last music app.
     */
    private fun publishState() {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                .build(),
        )
    }

    /** Re-assert priority, e.g. after another app has been playing. */
    fun refresh() {
        acquireFocus()
        session.isActive = true
        publishState()
    }

    val sessionToken: MediaSessionCompat.Token get() = session.sessionToken

    fun release() {
        runCatching { keepAlive?.stop() }
        runCatching { keepAlive?.release() }
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        session.isActive = false
        session.release()
    }

    private companion object {
        const val TAG = "ClaudeWalk"

        /**
         * The silent keepalive is what wins the media button. It was briefly suspected
         * of breaking TTS playback, because a 2 second looping file rebuilt the
         * AudioTrack every two seconds and filled logcat with stop/pauseAndWait churn.
         * It was not the cause (the real fault was in the test harness), but the churn
         * was real, so the silence is now 30 seconds and loops 15x less often.
         */
        const val KEEPALIVE_ENABLED = true
    }
}
