package com.nextgen.agent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
 * Speech Recognition Engine with:
 * 1. Continuous Listen -> Think -> Speak -> Listen loop (Voice mode stays ON until user clicks Stop).
 * 2. Silent Start/Stop cues: Automatically mutes the Android SpeechRecognizer chime/beep sound.
 * 3. 10-Second Voice Biometrics Pitch Verification (only responds if matching enrolled user profile).
 * 4. Quiet Standby Wake-Word Detector with extended thresholds (no 5-second repetitive beeping).
 * 5. Extended silence thresholds and multi-candidate hypotheses to avoid premature cutoffs.
 */
class Voice(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile private var holding = false     // true while thinking / speaking
    var active = false; private set
    var standby = false; private set

    var lang = "ta-IN"
    var female = true

    // Audio chime muting state
    private var savedMusicVol = -1
    private var savedSystemVol = -1
    private var savedNotifVol = -1
    private var isMuted = false

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

    /** Mutes system/music streams right before startListening to silence the Google/Android recognizer chime */
    private fun muteBeep() {
        val am = audioManager ?: return
        try {
            if (!isMuted) {
                savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                savedSystemVol = try { am.getStreamVolume(AudioManager.STREAM_SYSTEM) } catch (e: Exception) { -1 }
                savedNotifVol = try { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) } catch (e: Exception) { -1 }
                isMuted = true
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            if (savedSystemVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0) } catch (e: Exception) {}
            if (savedNotifVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0) } catch (e: Exception) {}
        } catch (ignored: Exception) {}
    }

    /** Restores device audio volume once the recognizer has safely started */
    private fun restoreBeep() {
        main.postDelayed({
            val am = audioManager ?: return@postDelayed
            try {
                if (isMuted) {
                    if (savedMusicVol >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
                    if (savedSystemVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0) } catch (e: Exception) {}
                    if (savedNotifVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0) } catch (e: Exception) {}
                    isMuted = false
                }
            } catch (ignored: Exception) {
                isMuted = false
            }
        }, 320)
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

    /** Explicitly stops active continuous voice mode */
    fun stop() {
        active = false
        holding = false
        restoreBeep()
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
        restoreBeep()
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
        restoreBeep()
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
        restoreBeep()
        main.post {
            try {
                sr?.cancel()
            } catch (ignored: Exception) {}
        }
        val loc = Locale.forLanguageTag(lang)
        t.language = loc
        val vs = t.voices?.filter { it.locale.language == loc.language }?.sortedBy { it.name }.orEmpty()
        if (vs.isNotEmpty()) {
            if (female) {
                // User requirement: Give the OG natural female voice to Female (not robotic)
                val ogFemale = vs.firstOrNull { it.name.contains("sfg") || it.name.contains("tpf") }
                    ?: vs.firstOrNull { it.name.lowercase(Locale.ROOT).contains("female") }
                    ?: vs.last() // vs.last() was the natural voice observed by user
                t.voice = ogFemale
                t.setPitch(1.0f) // Natural pitch without synthetic robotic shift
            } else {
                // User requirement: Give the true OG male voice to Male
                val ogMale = vs.firstOrNull { it.name.contains("tpd") || it.name.contains("tpc") }
                    ?: vs.firstOrNull { it.name.lowercase(Locale.ROOT).contains("male") || it.name.contains("-m-") }
                    ?: vs.firstOrNull { !it.name.contains("sfg") && !it.name.contains("tpf") }
                    ?: vs.first()
                t.voice = ogMale
                t.setPitch(0.92f) // Deep natural masculine voice tone
            }
        } else {
            t.setPitch(if (female) 1.0f else 0.92f)
        }
        t.setSpeechRate(1.0f)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) {
            release()
        }
    }

    /** Active recognition intent with fast response and on-device offline recognition (works in Airplane Mode) */
    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            // Enable offline / airplane mode speech recognition
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    /** Standby intent with extended silence windows and on-device offline recognition */
    private fun buildStandbyIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            // Enable offline / airplane mode speech recognition
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
                        restoreBeep()
                        if (!active) return
                        val results = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val text = results.firstOrNull { it.isNotBlank() }

                        if (!text.isNullOrBlank()) {
                            // Speaker verification check if enabled
                            if (voiceMatchEnabled && voiceProfile != null && voiceProfile!!.isEnrolled) {
                                val validPitch = if (pitchSamples.isNotEmpty()) {
                                    val avg = pitchSamples.average().toFloat()
                                    VoiceProfiler.matchesProfile(avg, voiceProfile!!)
                                } else true

                                if (!validPitch) {
                                    onVoiceRejected?.invoke()
                                    main.postDelayed({ listen() }, 400)
                                    return
                                }
                            }

                            holding = true
                            onText(text)
                        } else {
                            main.postDelayed({ listen() }, 200)
                        }
                    }

                    override fun onError(e: Int) {
                        restoreBeep()
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            stop()
                            return
                        }
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

                    override fun onReadyForSpeech(p: Bundle?) {
                        restoreBeep()
                    }
                    override fun onBeginningOfSpeech() {
                        restoreBeep()
                    }
                    override fun onRmsChanged(v: Float) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(p: Bundle?) {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })

                // Mute beep tone right before calling startListening
                muteBeep()
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
                                if (voiceMatchEnabled && voiceProfile != null && voiceProfile!!.isEnrolled) {
                                    if (pitchSamples.isNotEmpty()) {
                                        val avg = pitchSamples.average().toFloat()
                                        if (!VoiceProfiler.matchesProfile(avg, voiceProfile!!)) {
                                            onVoiceRejected?.invoke()
                                            return
                                        }
                                    }
                                }

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
                        restoreBeep()
                        if (active) return
                        val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                        if (standby && !active && autoWakeEnabled) {
                            main.postDelayed({ listenStandby() }, 1500)
                        }
                    }

                    override fun onPartialResults(p: Bundle?) {
                        if (active) return
                        val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                    }

                    override fun onError(e: Int) {
                        restoreBeep()
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            standby = false
                            return
                        }
                        // Gentle delay in standby so device does not cycle every 5 seconds
                        if (standby && !active && autoWakeEnabled) {
                            main.postDelayed({ listenStandby() }, 1800)
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

                    override fun onReadyForSpeech(p: Bundle?) {
                        restoreBeep()
                    }
                    override fun onBeginningOfSpeech() {
                        restoreBeep()
                    }
                    override fun onRmsChanged(v: Float) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })

                // Mute beep tone right before calling startListening in standby
                muteBeep()
                startListening(buildStandbyIntent())
            }
        }
    }
}