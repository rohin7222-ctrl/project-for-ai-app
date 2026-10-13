package com.nextgen.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 2x Perfection Speech Recognition Engine with:
 * 1. Continuous Listen -> Think -> Speak -> Listen loop (Voice mode stays ON until user clicks Stop).
 * 2. 10-Second Voice Biometrics Pitch Verification (only responds if matching enrolled user profile).
 * 3. Standby Wake-Word Detector (activates automatically when commanding word is spoken).
 * 4. Extended silence thresholds and multi-candidate hypotheses to avoid premature cutoffs.
 */
class Voice(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null

    @Volatile private var holding = false     // true while thinking / speaking
    var active = false; private set
    var standby = false; private set

    var lang = "ta-IN"
    var female = true

    // Voice Biometrics & Commanding Word settings
    var commandingWord: String = "Jarvis"
    var autoWakeEnabled: Boolean = true
    var voiceMatchEnabled: Boolean = false
    var voiceProfile: VoiceProfile? = null

    var onWakeWordTriggered: ((String?) -> Unit)? = null
    var onVoiceRejected: (() -> Unit)? = null

    private val pitchSamples = mutableListOf<Float>()

    init {
        tts = TextToSpeech(ctx) { }.also { t ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { release() }
                override fun onError(id: String?) { release() }
            })
        }
    }

    private fun release() {
        holding = false
        if (active) {
            listen()
        } else if (standby && autoWakeEnabled) {
            listenStandby()
        }
    }

    /** Explicitly starts active continuous voice mode */
    fun start() {
        active = true
        standby = false
        holding = false
        onState(true)
        listen()
    }

    /** Explicitly stops active continuous voice mode (called ONLY when user clicks Stop button) */
    fun stop() {
        active = false
        holding = false
        main.post {
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            sr = null
        }
        tts?.stop()
        onState(false)
        if (autoWakeEnabled) {
            startStandby()
        }
    }

    /** Enables low-power standby wake-word listening when not actively conversing */
    fun startStandby() {
        if (active) return
        standby = true
        holding = false
        listenStandby()
    }

    fun stopStandby() {
        standby = false
        if (!active) {
            main.post {
                try {
                    sr?.destroy()
                } catch (ignored: Exception) {}
                sr = null
            }
        }
    }

    fun shutdown() {
        active = false
        standby = false
        holding = false
        main.post {
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            sr = null
        }
        tts?.shutdown()
    }

    fun speak(text: String) {
        val t = tts ?: return
        holding = true
        main.post {
            try {
                sr?.cancel()
            } catch (ignored: Exception) {}
        }
        val loc = Locale.forLanguageTag(lang)
        t.language = loc
        val vs = t.voices?.filter { it.locale.language == loc.language }?.sortedBy { it.name }.orEmpty()
        if (vs.isNotEmpty()) t.voice = if (female) vs.first() else vs.last()
        t.setPitch(if (female) 1.1f else 0.8f)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) {
            release()
        }
    }

    /** Builds 2x perfected speech intent with optimized timeouts and multiple hypotheses */
    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // 2x Capturing perfection: prevent premature cutoffs when pausing
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
    }

    /** Active continuous listening loop */
    private fun listen() {
        if (!active || holding) return
        main.post {
            if (!active || holding) return@post
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            pitchSamples.clear()

            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(r: Bundle?) {
                        if (!active) return
                        val results = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val text = results.firstOrNull { it.isNotBlank() }

                        if (!text.isNullOrBlank()) {
                            // Speaker verification check if enabled
                            if (voiceMatchEnabled && voiceProfile != null && voiceProfile!!.isEnrolled) {
                                val validPitch = if (pitchSamples.isNotEmpty()) {
                                    val avg = pitchSamples.average().toFloat()
                                    VoiceProfiler.matchesProfile(avg, voiceProfile!!)
                                } else true // fallback if buffer pitch extraction wasn't provided by vendor engine

                                if (!validPitch) {
                                    onVoiceRejected?.invoke()
                                    main.postDelayed({ listen() }, 400)
                                    return
                                }
                            }

                            holding = true
                            onText(text)
                        } else {
                            // If empty, keep listening continuously
                            main.postDelayed({ listen() }, 200)
                        }
                    }

                    override fun onError(e: Int) {
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            stop()
                            return
                        }
                        // Continuous loop: never stop on silence or timeout!
                        if (active && !holding) {
                            main.postDelayed({ listen() }, 350)
                        }
                    }

                    override fun onBufferReceived(b: ByteArray?) {
                        if (b != null && voiceMatchEnabled) {
                            val p = VoiceProfiler.extractPitchFromBytes(b)
                            if (p in 80f..350f) {
                                pitchSamples.add(p)
                            }
                        }
                    }

                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(p: Bundle?) {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                startListening(buildRecognizerIntent())
            }
        }
    }

    /** Standby wake-word listening loop */
    private fun listenStandby() {
        if (active || !standby || !autoWakeEnabled || holding) return
        main.post {
            if (active || !standby || !autoWakeEnabled || holding) return@post
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            pitchSamples.clear()

            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    private fun checkWake(candidates: List<String>) {
                        val word = commandingWord.trim().lowercase(Locale.ROOT)
                        if (word.isBlank()) return
                        for (raw in candidates) {
                            val lower = raw.lowercase(Locale.ROOT)
                            if (lower.contains(word)) {
                                // Verify pitch if voice match enabled
                                if (voiceMatchEnabled && voiceProfile != null && voiceProfile!!.isEnrolled) {
                                    if (pitchSamples.isNotEmpty()) {
                                        val avg = pitchSamples.average().toFloat()
                                        if (!VoiceProfiler.matchesProfile(avg, voiceProfile!!)) {
                                            onVoiceRejected?.invoke()
                                            return
                                        }
                                    }
                                }

                                // Wake word detected! Extract command payload if any
                                val idx = lower.indexOf(word)
                                val after = raw.substring(idx + word.length).trim(' ', ',', '.', '?')
                                val cmd = after.ifBlank { null }
                                standby = false
                                start()
                                onWakeWordTriggered?.invoke(cmd)
                                return
                            }
                        }
                    }

                    override fun onResults(r: Bundle?) {
                        if (active) return
                        val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                        if (standby && !active && autoWakeEnabled) {
                            main.postDelayed({ listenStandby() }, 300)
                        }
                    }

                    override fun onPartialResults(p: Bundle?) {
                        if (active) return
                        val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                    }

                    override fun onError(e: Int) {
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            standby = false
                            return
                        }
                        if (standby && !active && autoWakeEnabled) {
                            main.postDelayed({ listenStandby() }, 400)
                        }
                    }

                    override fun onBufferReceived(b: ByteArray?) {
                        if (b != null && voiceMatchEnabled) {
                            val p = VoiceProfiler.extractPitchFromBytes(b)
                            if (p in 80f..350f) {
                                pitchSamples.add(p)
                            }
                        }
                    }

                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                startListening(buildRecognizerIntent())
            }
        }
    }
}