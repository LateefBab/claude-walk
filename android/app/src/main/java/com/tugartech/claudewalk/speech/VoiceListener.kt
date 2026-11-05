package com.tugartech.claudewalk.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Captures a whole spoken turn, including the pauses in the middle of it.
 *
 * Android's SpeechRecognizer is built for one short utterance, so holding a turn
 * that spans thinking pauses takes care. Three things went wrong before this was
 * right, and the design below exists to prevent each of them:
 *
 *  1. **Partials are a cumulative hypothesis for the CURRENT utterance, not the
 *     turn.** When the speaker pauses and resumes, the engine starts a fresh
 *     hypothesis. Assigning it over the stored one deleted the earlier sentence.
 *     Each utterance is now banked into [chunks] when the engine reports it, and
 *     only from the hypothesis if the engine never does.
 *  4. **The engine revises an utterance after it has been heard.** Banking at end
 *     of speech AND on the result that follows delivered every sentence twice, in
 *     two slightly different wordings. One utterance now produces one chunk.
 *  2. **Restarting the recognizer closes the microphone.** Words spoken during the
 *     few hundred milliseconds of teardown and startup were lost outright. A
 *     segmented session (API 31+) keeps the engine open across pauses instead, and
 *     the restart loop is now only a fallback for engines that ignore it.
 *  3. **A silent failure is indistinguishable from a broken app.** Every path that
 *     ends a turn with no text now says so, rather than quietly sending nothing.
 */
class VoiceListener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onEnd: () -> Unit,
    /** Something was dropped by [undoLast]; the caller confirms it audibly. */
    private val onUndo: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /**
     * One entry per utterance committed so far this turn.
     *
     * A list rather than a StringBuilder because an utterance can be revised after
     * it is banked, and replacing the last entry is only possible if the boundaries
     * between utterances still exist.
     */
    private val chunks = mutableListOf<String>()

    /** The engine's current hypothesis for the utterance in progress. */
    private var hypothesis = ""

    /**
     * Banks the hypothesis only if the engine never reports the utterance itself.
     *
     * End of speech is not an authoritative boundary: it means the audio stopped,
     * and a segment or final result for that same utterance follows a moment later.
     * Committing at end of speech and again on that result is what made a turn read
     * "so we can do one on two so we can do one and two", because the engine had
     * revised a word in the middle and the revision was no longer a substring of
     * what had been banked, so it appended instead of replacing.
     */
    private val bankHypothesis = Runnable {
        if (hypothesis.isNotEmpty()) {
            Log.w(TAG, "no result for utterance, banking the hypothesis")
            commit(hypothesis)
            hypothesis = ""
        }
    }

    /** Set when the user taps to send, so the next boundary finishes the turn. */
    @Volatile
    private var finishing = false

    /**
     * True for the whole turn, including gaps between utterances. [isListening] is
     * only true while the engine is actually running, so using it to interpret a
     * tap made a tap landing in a gap start a new turn and discard the old one.
     */
    @Volatile
    var isTurnActive: Boolean = false
        private set

    @Volatile
    var isListening: Boolean = false
        private set

    private var consecutiveErrors = 0
    private var turnStartedAt = 0L
    private var spokeThisTurn = false

    /** Guards against a turn being delivered twice by racing final callbacks. */
    private var delivered = false

    /**
     * Hands-free: a pause ends the turn and sends it, instead of waiting for a tap.
     *
     * Set by the service from the user's setting. Kept here rather than decided by
     * the caller because the boundary that matters, the end of an utterance, is
     * only visible from inside the recognizer callbacks.
     */
    @Volatile
    var continuous: Boolean = false

    /**
     * Fires when a pause has lasted long enough to count as "finished talking".
     *
     * Deliberately longer than the engine's own end-of-speech detection: that fires
     * at every gap between sentences, and ending a turn there would chop a thought
     * in half every time the speaker drew breath.
     */
    private val autoSend = Runnable {
        if (!continuous || !isTurnActive || finishing) return@Runnable
        // Silence with nothing said is not the end of a turn, it is waiting. Ending
        // it delivered an empty turn every couple of seconds, and each delivery
        // restarted the recogniser, which is what produced a start-of-recognition
        // tone on a loop for the length of a walk. Nothing was ever sent, so the
        // churn was invisible except as the chirp and the battery.
        if (!hasContent()) {
            Log.i(TAG, "silence, but nothing said yet: staying open")
            return@Runnable
        }
        Log.i(TAG, "silence reached, sending the turn")
        stop()
    }

    /** Anything worth delivering, banked or still forming. */
    private fun hasContent(): Boolean = chunks.isNotEmpty() || hypothesis.isNotBlank()

    /**
     * Set when [undoLast] drops an utterance the engine has not reported yet, so
     * the result that follows is thrown away instead of resurrecting the words.
     */
    private var suppressNextResult = false

    /**
     * We just cancelled the recogniser ourselves, so the error it is about to
     * report is our own doing.
     *
     * Cancelling a running SpeechRecognizer makes Android deliver ERROR_CLIENT.
     * Nothing has gone wrong: it is the engine acknowledging the instruction. Muting
     * therefore announced "Recognizer client error" at the exact moment the user had
     * asked for silence, which reads as the mute button breaking something.
     */
    private var selfCancelled = false

    /** True once the engine has proved it honours segmented sessions. */
    private var segmentedWorks = false

    /**
     * Whether to ask for a segmented session at all.
     *
     * Disabled permanently after one turn that requested it and never received a
     * segment callback, because the extras it needs are actively harmful to an
     * engine that ignores segmentation.
     */
    private var trySegmented = true

    /**
     * On-device recognition is off by default. It returned neither final nor partial
     * results for fourteen seconds of speech on this device, most likely because
     * no on-device model covered the device's regional English locale. It also buys nothing here:
     * the phone needs network to reach the bridge at all, so there is no offline
     * case where local recognition helps and network recognition does not.
     */
    private var useOnDevice = false

    private val onDeviceAvailable =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    // -----------------------------------------------------------------------
    // Turn lifecycle
    // -----------------------------------------------------------------------

    /** Begin a turn. Keeps listening across pauses until [stop]. */
    fun start() = main.post {
        if (isTurnActive) return@post
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDeviceAvailable) {
            onError("No speech recognition available on this device")
            return@post
        }
        main.removeCallbacks(bankHypothesis)
        main.removeCallbacks(autoSend)
        chunks.clear()
        hypothesis = ""
        suppressNextResult = false
        selfCancelled = false
        finishing = false
        spokeThisTurn = false
        delivered = false
        consecutiveErrors = 0
        isTurnActive = true
        turnStartedAt = SystemClock.elapsedRealtime()
        listen()
    }

    /**
     * Finish the turn and deliver everything gathered.
     *
     * Return type is explicit because [autoSend] calls this, and inferring it from a
     * body that in turn references [autoSend] is a cycle the compiler will not solve.
     */
    fun stop(): Boolean = main.post {
        if (!isTurnActive) return@post
        main.removeCallbacks(autoSend)
        finishing = true
        if (isListening) {
            runCatching { recognizer?.stopListening() }
            // Backstop: if the engine never produces a final callback, the turn
            // would hang with the text captured but never sent.
            main.postDelayed({ deliver() }, FINALISE_TIMEOUT_MS)
        } else {
            // Tapped between utterances: nothing is running to produce a final
            // callback, so nothing would ever finish the turn on its own.
            deliverSoon()
        }
    }

    /**
     * Drop the last thing said, without ending the turn.
     *
     * The eyes-free correction: flub a sentence, drop it, carry on talking. Only
     * possible because a turn is stored as separate utterances; against a single
     * accumulated string there would be no boundary to remove.
     *
     * If an utterance is still in progress that one goes, and the engine's result
     * for it is suppressed when it arrives, or the words would reappear a second
     * later. Otherwise the last banked utterance goes.
     */
    fun undoLast() = main.post {
        if (!isTurnActive) return@post
        main.removeCallbacks(bankHypothesis)

        val dropped = if (hypothesis.isNotEmpty()) {
            val text = hypothesis
            hypothesis = ""
            suppressNextResult = true
            text
        } else {
            chunks.removeLastOrNull().orEmpty()
        }

        if (dropped.isEmpty()) {
            Log.i(TAG, "undo with nothing to drop")
            return@post
        }
        Log.i(TAG, "undo dropped '${dropped.take(40)}', ${chunks.size} chunk(s) left")
        onUndo()
        onPartial(visibleText())
    }

    /** Throw the turn away without sending it. */
    fun discard() = main.post {
        if (!isTurnActive) return@post
        Log.i(TAG, "discarding turn (${chunks.size} chunk(s))")
        selfCancelled = true
        delivered = true
        isListening = false
        isTurnActive = false
        finishing = true
        main.removeCallbacks(bankHypothesis)
        main.removeCallbacks(autoSend)
        chunks.clear()
        hypothesis = ""
        runCatching { recognizer?.cancel() }
        onEnd()
    }

    /** Abandon the turn without delivering. */
    fun cancel() = main.post {
        selfCancelled = true
        isListening = false
        isTurnActive = false
        finishing = true
        main.removeCallbacks(bankHypothesis)
        main.removeCallbacks(autoSend)
        chunks.clear()
        hypothesis = ""
        runCatching { recognizer?.cancel() }
    }

    fun destroy() = main.post {
        isListening = false
        isTurnActive = false
        // A leaked recognizer holds the microphone and blocks the next session.
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    // -----------------------------------------------------------------------
    // Engine
    // -----------------------------------------------------------------------

    private fun listen() {
        hypothesis = ""
        val r = recognizer ?: createRecognizer().also { recognizer = it }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Ask for en-US rather than inheriting the device locale: a regional English
            // locale returned nothing on the test device.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
            // Hands-free finalises real speech itself, via autoSend at
            // AUTO_SEND_SILENCE_MS, so the engine's own patience only governs how
            // long it waits before giving up on a silent room. Two seconds meant a
            // teardown and rebuild every few seconds while waiting, each one
            // audible. Press-to-talk keeps the short value, where a turn is bounded
            // by the button rather than by waiting.
            val silenceMs = if (continuous) IDLE_SILENCE_TOLERANCE_MS else 2000
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)

            // Keep the microphone open across pauses and report each utterance,
            // instead of ending at the first silence and forcing a restart that
            // closes the mic and drops whatever is said during it.
            //
            // The SDK requires the controlling extra to be named here AND set, and
            // warns the whole thing "may have no effect" depending on the engine,
            // hence trySegmented and the fallback in deliver(). Note the minimum
            // length only means "session length" in segmented mode; in ordinary
            // mode it makes the engine listen past the end of speech and return
            // NO_MATCH, which is exactly how it broke before.
            if (trySegmented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    MAX_TURN_MS.toInt(),
                )
            }
        }

        isListening = true
        runCatching { r.startListening(intent) }
            .onFailure {
                isListening = false
                onError(it.message ?: "Could not start listening")
                deliver()
            }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val networkAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val r = if (useOnDevice || !networkAvailable) {
            Log.i(TAG, "using on-device recognizer")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.i(TAG, "using network recognizer")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(listener)
        return r
    }

    private fun switchEngine(toOnDevice: Boolean) {
        if (useOnDevice == toOnDevice) return
        useOnDevice = toOnDevice
        runCatching { recognizer?.destroy() }
        recognizer = null
        Log.i(TAG, "switched to ${if (toOnDevice) "on-device" else "network"} recognizer")
    }

    // -----------------------------------------------------------------------
    // Accumulation
    // -----------------------------------------------------------------------

    /**
     * Commit an utterance to the turn.
     *
     * Second line of defence against the same words landing twice. The engine grows
     * its text for one utterance as it hears more of it, so a later version of the
     * utterance already banked supersedes it rather than following it.
     */
    private fun commit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // The engine keeps calling back for a moment after a turn ends. Observed
        // on device: a discard was immediately followed by onError committing the
        // hypothesis, refilling a turn that had just been thrown away and pushing
        // the discarded words back onto the screen.
        if (!isTurnActive) {
            Log.i(TAG, "commit ignored, turn is over: '${t.take(30)}'")
            return
        }

        val last = chunks.lastOrNull()
        if (last != null) {
            if (last == t) return
            // Grown: the same utterance, heard further. Replace, never append.
            if (t.startsWith(last)) {
                chunks[chunks.lastIndex] = t
                Log.i(TAG, "commit extended last chunk -> '${t.take(40)}'")
                onPartial(joined())
                return
            }
            // Shrunk: a late partial that is behind what is already banked.
            if (last.startsWith(t)) return
        }
        if (chunks.any { it.contains(t) }) {
            Log.i(TAG, "commit skipped, already have '${t.take(30)}'")
            return
        }

        chunks.add(t)
        Log.i(TAG, "commit '${t.take(40)}' -> turn now ${chunks.size} chunk(s)")
        onPartial(joined())
    }

    private fun joined(): String = chunks.joinToString(" ")

    /** True once, if an undo is waiting to swallow the next engine result. */
    private fun consumeSuppression(): Boolean {
        if (!suppressNextResult) return false
        suppressNextResult = false
        hypothesis = ""
        Log.i(TAG, "suppressed the result for an undone utterance")
        return true
    }

    /** Everything so far, including the utterance still in progress. */
    private fun visibleText(): String {
        val done = joined()
        return when {
            done.isEmpty() -> hypothesis
            hypothesis.isEmpty() -> done
            else -> "$done $hypothesis"
        }
    }

    private fun continueOrFinish() {
        val tooLong = SystemClock.elapsedRealtime() - turnStartedAt > MAX_TURN_MS
        Log.i(
            TAG,
            "continueOrFinish finishing=$finishing tooLong=$tooLong errors=$consecutiveErrors " +
                "segmented=$segmentedWorks chunks=${chunks.size}",
        )
        if (finishing || tooLong || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            deliver()
            return
        }
        // Restarting the engine is not free: Android plays its start-of-recognition
        // tone every time, so a tight restart loop is an audible chirp in the user's
        // ear. That was tolerable when a turn began with a button press and ended
        // seconds later, and unbearable in hands-free, where silence is the resting
        // state and this path runs for as long as nobody is talking.
        //
        // The previous comment claimed a working segmented session meant this never
        // ran. That was wrong: onError routes here for NO_MATCH and SPEECH_TIMEOUT
        // regardless of segmentation, and those are exactly what silence produces.
        //
        // So the delay depends on what we are waiting for. Mid-sentence, restart
        // fast, because the microphone is shut while we do it and words spoken in
        // that gap are lost outright. Waiting for someone who has not started
        // speaking, nothing can be lost by waiting a little longer.
        val delay = if (spokeThisTurn) RESTART_DELAY_MS else IDLE_RESTART_DELAY_MS
        main.postDelayed({ if (!finishing && isTurnActive) listen() }, delay)
    }

    /**
     * Finish the turn after a short grace period.
     *
     * Ending the session and delivering in the same breath truncated the last word:
     * stopListening() returns before the engine has emitted the final segment for
     * the utterance just spoken, so the tail arrived after delivery had already
     * happened. Waiting briefly lets that final callback land and commit itself.
     */
    private fun deliverSoon() {
        if (delivered) return
        main.postDelayed({ deliver() }, FINALISE_GRACE_MS)
    }

    private fun deliver() {
        if (delivered) return
        delivered = true
        isListening = false
        main.removeCallbacks(bankHypothesis)
        main.removeCallbacks(autoSend)
        // Banked before the turn closes, because commit() now refuses to add to a
        // turn that is already over, and this last one is legitimate.
        commit(hypothesis)
        isTurnActive = false
        val text = joined().trim()
        Log.i(TAG, "deliver len=${text.length} '${text.take(80)}' segmented=$segmentedWorks")
        chunks.clear()
        hypothesis = ""

        // Asked for segmentation and never got a segment: this engine ignores it,
        // so stop sending extras that hurt it and use the restart loop instead.
        if (trySegmented && !segmentedWorks) {
            trySegmented = false
            Log.i(TAG, "engine ignored segmented session, falling back to restart mode")
        }

        if (text.isEmpty()) {
            if (spokeThisTurn) {
                Log.w(TAG, "turn produced no text despite detected speech")
                onError(
                    if (useOnDevice) "Heard you, but the on-device recogniser returned nothing. Switching to the network recogniser."
                    else "Heard you, but no words came back. Try again, or type instead.",
                )
                if (useOnDevice) switchEngine(toOnDevice = false)
            }
            onEnd()
            return
        }

        onResult(text)
        onEnd()
    }

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            spokeThisTurn = true
            // Still talking, so this is a gap for breath, not the end of a turn.
            main.removeCallbacks(autoSend)
        }

        /**
         * End of one utterance, not of the turn, and not authoritative.
         *
         * The engine follows this with a segment or final result carrying its own,
         * often revised, text for the utterance. Committing here as well produced
         * every sentence twice. The hypothesis is only banked if that result never
         * arrives, which is the case the original commit here existed to cover.
         */
        override fun onEndOfSpeech() {
            main.removeCallbacks(bankHypothesis)
            main.postDelayed(bankHypothesis, HYPOTHESIS_FALLBACK_MS)
            if (continuous) {
                main.removeCallbacks(autoSend)
                main.postDelayed(autoSend, AUTO_SEND_SILENCE_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            // Never replace real text with nothing: engines emit a trailing empty
            // partial as an utterance closes.
            if (text.isNotBlank()) hypothesis = text
            onPartial(visibleText())
        }

        /** Segmented session: one utterance finished, the session continues. */
        override fun onSegmentResults(segmentResults: Bundle) {
            segmentedWorks = true
            main.removeCallbacks(bankHypothesis)
            val text = segmentResults
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.i(TAG, "onSegmentResults '${text.take(40)}'")
            if (consumeSuppression()) return
            commit(text.ifEmpty { hypothesis })
            hypothesis = ""
        }

        /** Segmented session ended, normally because stopListening was called. */
        override fun onEndOfSegmentedSession() {
            Log.i(TAG, "onEndOfSegmentedSession")
            isListening = false
            // Not deliver(): the segment for the utterance just spoken can still be
            // in flight, and delivering here cut the last word off.
            deliverSoon()
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            consecutiveErrors = 0
            main.removeCallbacks(bankHypothesis)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            Log.i(TAG, "onResults '${text.take(40)}' (len=${text.length})")
            // Suppressed only for the commit: the control flow below still has to
            // run or the turn never continues, and never finishes.
            if (!consumeSuppression()) commit(text.ifEmpty { hypothesis })
            hypothesis = ""
            // In a segmented session the session end drives delivery, not this.
            if (!segmentedWorks) continueOrFinish() else if (finishing) deliverSoon()
        }

        override fun onError(error: Int) {
            isListening = false
            main.removeCallbacks(bankHypothesis)

            if (selfCancelled) {
                // Our own cancel coming back. Not a failure, not worth counting
                // towards the consecutive-failure limit, and certainly not worth
                // telling the user about.
                selfCancelled = false
                Log.i(TAG, "ignoring error $error from our own cancel")
                return
            }

            // The flag above is a single boolean and cannot survive reordering:
            // cancel() and start() are both posted to the looper, so switching
            // listening mode can run cancel then start before the engine's
            // ERROR_CLIENT arrives, by which point start() has cleared the flag and
            // the error surfaces as though something had failed.
            //
            // Outside a turn there is nothing to report by definition. Nobody is
            // listening, no words are at risk, and every error that genuinely
            // matters, permissions or a dead microphone, happens while a turn is
            // running. So the turn itself is the better guard.
            if (!isTurnActive) {
                Log.i(TAG, "ignoring error $error: no turn is active")
                return
            }
            Log.i(TAG, "onError code=$error hypothesis='${hypothesis.take(30)}'")
            // Silence is a pause for thought, not a failure. Bank what was heard:
            // an error path produces no result of its own, so this is the only
            // chance to keep the words.
            commit(hypothesis)
            hypothesis = ""

            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                continueOrFinish()
                return
            }
            consecutiveErrors++
            // Hands-free restarts the recognizer after every turn and after every
            // thing Claude says, and a recognizer being torn down and rebuilt
            // reports BUSY and CLIENT as a matter of course. Surfacing each one
            // painted an alarming red error during entirely normal operation, for a
            // condition the very next restart fixes. Only speak up for something
            // the user can act on, or for a failure that has stopped going away.
            val worthReporting = isActionable(error) || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS
            if (worthReporting) {
                this@VoiceListener.onError(describe(error))
            } else {
                Log.i(TAG, "transient recognizer error $error, retrying quietly")
            }
            continueOrFinish()
        }
    }

    /**
     * Whether the user could do anything about this if told.
     *
     * Permissions and a failing microphone are worth interrupting someone on a walk
     * for. A busy recogniser, a dropped request or a server hiccup are not: they are
     * the normal texture of a restart loop and they fix themselves.
     */
    private fun isActionable(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_AUDIO -> true
        else -> false
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Recognition server unreachable"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        else -> "Recognition error $error"
    }

    private companion object {
        const val TAG = "VoiceListener"

        /** Hard ceiling on one spoken turn, so a stuck microphone cannot loop forever. */
        const val MAX_TURN_MS = 120_000L
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val RESTART_DELAY_MS = 150L

        /**
         * Restart delay while nobody has said anything yet.
         *
         * Ten times the mid-sentence delay, which cuts the start-of-recognition tone
         * from a constant chirp to an occasional one, and stops the engine being
         * rebuilt several times a second for hours of a walk. Nothing is at risk:
         * the words that a restart can lose are the ones spoken during it, and by
         * definition nobody is speaking.
         */
        const val IDLE_RESTART_DELAY_MS = 1_500L

        /**
         * How long the engine itself waits in silence before giving up, in
         * hands-free only. Every giving-up costs a rebuild and an audible tone, and
         * nothing is lost by waiting: our own timer ends turns that have content.
         */
        const val IDLE_SILENCE_TOLERANCE_MS = 10_000

        /** Let a final segment land after the session ends, so the last word survives. */
        const val FINALISE_GRACE_MS = 600L

        /**
         * How long to wait after end of speech for the engine's own text for that
         * utterance before falling back to the hypothesis. Long enough that a normal
         * result always wins the race, short enough that an engine which never
         * reports one does not lose the sentence.
         */
        const val HYPOTHESIS_FALLBACK_MS = 1200L

        /**
         * Silence that ends a turn in continuous mode. Long enough to think
         * mid-sentence without being cut off, short enough that finishing a thought
         * does not leave you waiting on a reply that has not been sent yet.
         */
        const val AUTO_SEND_SILENCE_MS = 2200L

        /** Backstop if the engine never produces a final callback at all. */
        const val FINALISE_TIMEOUT_MS = 2500L
    }
}
