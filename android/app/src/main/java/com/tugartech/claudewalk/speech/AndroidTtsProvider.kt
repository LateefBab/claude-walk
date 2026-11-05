package com.tugartech.claudewalk.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's built-in speech engine.
 *
 * Free, offline once the voice data is present, and `stop()` returns effectively
 * instantly, which barge-in needs. Because synthesis is local, a mobile
 * dead zone costs nothing: the text already arrived.
 */
class AndroidTtsProvider(
    context: Context,
    initialPrefs: VoicePrefs = VoicePrefs(),
    private val onReady: (Boolean) -> Unit,
) : SpeechProvider {

    override var onSpoken: (id: Long) -> Unit = {}

    private val pending = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    private val lock = Any()

    /** Guarded by [lock]. */
    private var ready = false

    /**
     * The user's choice, applied on init and again on every change. Held rather
     * than pushed straight at the engine because a preference can be set from the
     * Settings screen before, or while, the engine is still binding.
     */
    @Volatile
    private var prefs: VoicePrefs = initialPrefs

    /**
     * Text of every utterance handed to the engine but not yet finished, so one
     * that fails for want of a network can be said again in a different voice.
     * Bounded by how many utterances the engine holds at once, which is small.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * Ids for the leading parts of a split utterance.
     *
     * Deliberately far below the preview's -1 and nowhere near the bridge's ids,
     * which start at 1, so completing one of these can never ack a real utterance.
     */
    private val fillerIds = java.util.concurrent.atomic.AtomicLong(-1000)

    /** Ids already retried once, so a permanent failure cannot loop. */
    private val retryable = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /**
     * Elapsed-realtime deadline until which the offline fallback stays in force.
     * After it passes the preferred voice is tried again, so walking back into
     * signal restores it with no action from the user.
     */
    @Volatile
    private var offlineUntil = 0L

    /**
     * Guarded by [lock]. Binding to the system TTS engine takes on the order of a
     * second, while the WebSocket connects in milliseconds and the bridge replays
     * unacked utterances immediately. Anything arriving in that window is held
     * here and flushed once the engine is up, rather than dropped: on a walk that
     * window is exactly the moment you come out of a dead zone.
     */
    private val waiting = ArrayDeque<Pair<Long, String>>()

    // TextToSpeech's OnInitListener is not guaranteed to fire after the
    // constructor returns, so nothing in the callback may touch this field
    // directly. Configuration is posted to the main looper instead, by which
    // point assignment has definitely happened.
    private val tts = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (!ok) Log.e(TAG, "TextToSpeech init failed with status $status")
        main.post {
            val flush: List<Pair<Long, String>>
            synchronized(lock) {
                ready = ok
                flush = waiting.toList()
                waiting.clear()
            }
            if (ok) {
                // configure() installs the progress listener, so it must run
                // before anything is enqueued or those utterances never ack.
                configure()
                Log.i(TAG, "engine ready, max utterance length = ${TextToSpeech.getMaxSpeechInputLength()} chars")
                flush.forEach { (id, text) -> enqueue(text, id) }
                if (flush.isNotEmpty()) Log.i(TAG, "flushed ${flush.size} utterance(s) held during init")
            }
            onReady(ok)
        }
    }

    private fun configure() {
        // Language first: setting it resets the voice, so applying the user's
        // choice afterwards is the only order that survives.
        tts.language = Locale.getDefault()
        // USAGE_MEDIA routes to the media stream, which is where Bluetooth
        // earbuds expect audio and what keeps playing with the screen off.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        applyPrefs(prefs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                pending.decrementAndGet()
                Log.i(TAG, "onDone id=$utteranceId at ${android.os.SystemClock.elapsedRealtime()}")
                utteranceId?.toLongOrNull()?.let {
                    inFlight.remove(it)
                    retryable.remove(it)
                    onSpoken(it)
                }
            }

            @Deprecated("Required by the platform base class")
            override fun onError(utteranceId: String?) {
                pending.decrementAndGet()
                inFlight.remove(utteranceId?.toLongOrNull())
                Log.e(TAG, "utterance error: $utteranceId")
            }

            /**
             * The overload that carries a reason, and the whole point of the
             * fallback: a network voice fails here when the signal drops.
             */
            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.decrementAndGet()
                val id = utteranceId?.toLongOrNull()
                val text = inFlight.remove(id)
                Log.e(TAG, "utterance error id=$utteranceId code=$errorCode")

                val networkFailure = errorCode == TextToSpeech.ERROR_NETWORK ||
                    errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT
                if (!networkFailure || id == null || text == null) return
                if (!retryable.add(id)) {
                    Log.e(TAG, "id=$id already retried once, giving up")
                    return
                }
                main.post { fallBackToOffline(text, id) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                pending.set(0)
                // An interrupted utterance is deliberately left unacked. The
                // bridge replays unacked items on reconnect, so a deliberate
                // stop never silently loses an update the user cut short.
            }
        })
    }

    override fun configure(prefs: VoicePrefs) {
        this.prefs = prefs
        // A deliberate choice supersedes any fallback currently in force.
        offlineUntil = 0L
        val isReady = synchronized(lock) { ready }
        // Not ready yet is not a failure: configure() runs again out of init.
        if (isReady) main.post { applyPrefs(prefs) }
    }

    /**
     * Re-speak an utterance in an offline voice after the chosen one failed for
     * want of a network.
     *
     * A network voice is the best-sounding option on the device and the worst
     * possible one in a dead zone, which is precisely where a walk takes you. Rather
     * than refusing to let it be chosen, the failure is caught and the words are
     * said anyway, in the best offline voice available. The preferred voice is
     * retried after a cool-off, so coming back into signal restores it without the
     * user doing anything.
     */
    private fun fallBackToOffline(text: String, id: Long) {
        val fallback = bestOfflineVoice()
        if (fallback == null) {
            Log.e(TAG, "network voice failed and no offline voice is installed; id=$id is lost")
            return
        }
        offlineUntil = SystemClock.elapsedRealtime() + PREFERRED_RETRY_MS
        Log.w(TAG, "network voice failed, falling back to ${fallback.name} and re-speaking id=$id")
        runCatching { tts.setVoice(fallback) }
        enqueue(text, id)
    }

    /** Best offline voice, preferring the locale of whatever the user chose. */
    private fun bestOfflineVoice(): Voice? {
        val installed = installedVoices().filterNot { it.isNetworkConnectionRequired }
        if (installed.isEmpty()) return null
        val wantedLocale = installed.firstOrNull { it.name == prefs.voiceId }?.locale
            ?: Locale.getDefault()
        return installed
            .filter { it.locale.language.equals(wantedLocale.language, ignoreCase = true) }
            .maxByOrNull { it.quality }
            ?: installed.maxByOrNull { it.quality }
    }

    /** Main thread only. Rate and pitch take effect on the next [enqueue]. */
    private fun applyPrefs(p: VoicePrefs) {
        tts.setSpeechRate(p.rate.coerceIn(VoicePrefs.MIN_RATE, VoicePrefs.MAX_RATE))
        tts.setPitch(p.pitch.coerceIn(VoicePrefs.MIN_PITCH, VoicePrefs.MAX_PITCH))
        if (p.voiceId.isBlank()) return

        val match = installedVoices().firstOrNull { it.name == p.voiceId }
        if (match == null) {
            // Voice data can be uninstalled by the system between launches. Keeping
            // the engine default is right: silence would be much worse outdoors.
            Log.w(TAG, "voice ${p.voiceId} is not installed, keeping the engine default")
            return
        }
        val result = runCatching { tts.setVoice(match) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) Log.e(TAG, "setVoice failed for ${p.voiceId}")
        else Log.i(TAG, "voice set to ${p.voiceId}, rate=${p.rate}, pitch=${p.pitch}")
    }

    override fun voices(): List<VoiceOption> {
        if (!synchronized(lock) { ready }) return emptyList()
        val language = Locale.getDefault().language
        return installedVoices()
            .filter { it.locale.language.equals(language, ignoreCase = true) }
            .map { v ->
                VoiceOption(
                    id = v.name,
                    label = v.locale.getDisplayName(Locale.getDefault()),
                    detail = detailFor(v),
                    requiresNetwork = v.isNetworkConnectionRequired,
                )
            }
            // Offline first: a network voice goes silent in a dead zone, which is
            // the one failure this app exists to avoid. Then best quality first.
            .sortedWith(compareBy({ it.requiresNetwork }, { it.label }, { it.detail }))
    }

    /** [TextToSpeech.getVoices] is documented as nullable and throws on some engines. */
    private fun installedVoices(): List<Voice> =
        runCatching { tts.voices }
            .onFailure { Log.e(TAG, "getVoices() failed", it) }
            .getOrNull()
            .orEmpty()
            .filterNotNull()
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }

    /**
     * Google's voice names look like `en-gb-x-gbb-network`, and a locale usually
     * has several. The variant token is the only thing that tells them apart, so it
     * goes in the subtitle next to the two facts that decide the choice.
     */
    private fun detailFor(v: Voice): String {
        val variant = v.name.substringAfter("-x-", "").substringBefore("-").ifBlank { v.name }
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "very high"
            v.quality >= Voice.QUALITY_HIGH -> "high"
            v.quality >= Voice.QUALITY_NORMAL -> "normal"
            else -> "low"
        }
        val reach = if (v.isNetworkConnectionRequired) "needs network" else "offline"
        return "$variant · $quality · $reach"
    }

    override fun speak(text: String, id: Long) {
        synchronized(lock) {
            if (!ready) {
                // Unbounded growth would only happen if the engine never binds,
                // in which case the oldest updates are the least worth hearing.
                if (waiting.size >= MAX_WAITING) waiting.removeFirst()
                waiting.addLast(id to text)
                Log.i(TAG, "engine still binding, holding id=$id")
                return
            }
        }
        val limit = maxUtteranceLength()
        if (text.length <= limit) {
            enqueue(text, id)
            return
        }

        // Over the engine's limit, tts.speak returns ERROR and the utterance is
        // simply gone: nothing is spoken, nothing is acked, and the bridge has
        // already told Claude "spoken" because it reports delivery to the phone
        // rather than playback. The result is silence that Claude believes was
        // speech. Splitting is the only honest option.
        val parts = splitForSpeech(text, limit)
        Log.i(TAG, "id=$id is ${text.length} chars, over the $limit limit: speaking as ${parts.size} parts")
        // Only the final part carries the real id, so the ack still means "all of
        // it was heard". The earlier parts get throwaway ids that no session owns,
        // so their completion acks nothing.
        parts.dropLast(1).forEach { enqueue(it, fillerIds.decrementAndGet()) }
        enqueue(parts.last(), id)
    }

    /** The engine's own cap, asked rather than assumed. 4000 on a Pixel. */
    private fun maxUtteranceLength(): Int =
        runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(DEFAULT_MAX_CHARS)
            .coerceAtLeast(MIN_SENSIBLE_CHARS)

    /**
     * Break text into pieces the engine will accept, on sentence boundaries.
     *
     * Sentences, because the seam is audible: splitting mid-clause puts a pause
     * where the meaning does not, and over several minutes of explanation that is
     * the difference between listening and decoding. A single sentence longer than
     * the whole limit is vanishingly rare and falls back to breaking on spaces,
     * which is still better than saying nothing at all.
     */
    private fun splitForSpeech(text: String, limit: Int): List<String> {
        val sentences = Regex("(?<=[.!?]) +").split(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                parts.add(current.toString().trim())
                current.setLength(0)
            }
        }

        for (sentence in sentences) {
            if (sentence.length > limit) {
                flush()
                parts.addAll(sentence.chunkedOnSpaces(limit))
                continue
            }
            if (current.length + sentence.length + 1 > limit) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return parts.filter { it.isNotEmpty() }
    }

    /** Last resort for a single sentence longer than the engine accepts. */
    private fun String.chunkedOnSpaces(limit: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < length) {
            val end = minOf(start + limit, length)
            val cut = if (end == length) end else lastIndexOf(' ', end).takeIf { it > start } ?: end
            out.add(substring(start, cut).trim())
            start = cut
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun enqueue(text: String, id: Long) {
        restorePreferredVoiceIfDue()
        pending.incrementAndGet()
        inFlight[id] = text
        Log.i(TAG, "enqueue id=$id chars=${text.length} at ${SystemClock.elapsedRealtime()}")
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id.toString())
        if (result != TextToSpeech.SUCCESS) {
            pending.decrementAndGet()
            inFlight.remove(id)
            Log.e(TAG, "speak failed for id=$id")
        }
    }

    /**
     * Try the user's chosen voice again once the cool-off has passed.
     *
     * Checked per utterance rather than on a timer: there is nothing to restore
     * unless something is about to be said, and a timer would wake the device for
     * no reason while it is in a pocket.
     */
    private fun restorePreferredVoiceIfDue() {
        if (offlineUntil == 0L || SystemClock.elapsedRealtime() < offlineUntil) return
        offlineUntil = 0L
        Log.i(TAG, "cool-off over, trying the preferred voice again")
        applyPrefs(prefs)
    }

    override fun stop() {
        runCatching { tts.stop() }
        pending.set(0)
        inFlight.clear()
        synchronized(lock) { waiting.clear() }
    }

    override fun status(): SpeechStatus {
        val held = synchronized(lock) { if (!ready) return SpeechStatus.UNAVAILABLE else waiting.size }
        return if (pending.get() > 0 || held > 0) SpeechStatus.SPEAKING else SpeechStatus.IDLE
    }

    override fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val TAG = "AndroidTtsProvider"
        const val MAX_WAITING = 20

        /** Used only if the engine refuses to report its own limit. */
        const val DEFAULT_MAX_CHARS = 4000

        /** Guards against an engine reporting an absurdly small or zero limit. */
        const val MIN_SENSIBLE_CHARS = 200

        /**
         * How long to stay on the offline fallback before trying the chosen voice
         * again. Long enough not to retry into the same dead zone on every
         * utterance, short enough that walking back into signal restores the good
         * voice within a minute or two rather than for the rest of the walk.
         */
        const val PREFERRED_RETRY_MS = 120_000L
    }
}
