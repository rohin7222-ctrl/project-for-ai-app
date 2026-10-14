package com.nextgen.agent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speech Recognition Engine with:
 * 1. Continuous Listen -> Think -> Speak -> Listen loop (Active voice mode).
 * 2. Responsive online recognition with graceful offline fallback when internet is disabled.
 * 3. Safe muting of system recognizer chime without muting media/TTS audio stream.
 * 4. Stop voice command detection (configurable word + universal stop phrases in Tamil/English/Japanese).
 * 5. Automatic suspension and resumption during active phone calls.
 * 6. Quiet Standby Wake-Word Detector with voice profile biometric verification.
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

    // Audio chime muting state (Notification & System only - STREAM_MUSIC is NEVER muted)
    private var savedSystemVol = -1
    private var savedNotifVol = -1
    private var isMuted = false

    // Voice Biometrics & Commanding Word settings
    var commandingWord: String = "Jarvis"
    var commandingStopWord: String = "Stop"
    var autoWakeEnabled: Boolean = true
    var voiceMatchEnabled: Boolean = false
    var voiceProfile: VoiceProfile? = null

    var onWakeWordTriggered: ((String?) -> Unit)? = null
    var onStopCommandTriggered: (() -> Unit)? = null
    var onVoiceRejected: (() -> Unit)? = null

    // Phone call pause state
    @Volatile var pausedForCall = false
        private set

    private val pitchSamples = mutableListOf<Float>()

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { release() }
                    override fun onError(id: String?) { release() }
                })
            }
        }.also { t ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { release() }
                override fun onError(id: String?) { release() }
            })
        }
    }

    /** Mutes system/notification streams right before startListening to silence the chime without muting media */
    private fun muteBeep() {
        val am = audioManager ?: return
        try {
            if (!isMuted) {
                savedSystemVol = try { am.getStreamVolume(AudioManager.STREAM_SYSTEM) } catch (e: Exception) { -1 }
                savedNotifVol = try { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) } catch (e: Exception) { -1 }
                isMuted = true
            }
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
                    if (savedSystemVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0) } catch (e: Exception) {}
                    if (savedNotifVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0) } catch (e: Exception) {}
                    isMuted = false
                }
            } catch (ignored: Exception) {
                isMuted = false
            }
        }, 320)
    }

    /** Returns true if active phone call is ringing or ongoing */
    fun isPhoneCallActive(): Boolean {
        val am = audioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL ||
               mode == AudioManager.MODE_IN_COMMUNICATION ||
               mode == AudioManager.MODE_RINGTONE
    }

    /** Pause voice engine during a phone call */
    fun pauseForCall() {
        pausedForCall = true
        restoreBeep()
        tts?.stop()
        main.post {
            try { sr?.destroy() } catch (ignored: Exception) {}
            sr = null
        }
    }

    /** Resume voice engine after a phone call has ended */
    fun resumeAfterCall(wasActive: Boolean, wasStandby: Boolean) {
        pausedForCall = false
        if (wasActive) {
            start()
        } else if (wasStandby && autoWakeEnabled) {
            startStandby()
        }
    }

    private fun release() {
        holding = false
        if (pausedForCall || isPhoneCallActive()) return
        if (active) {
            listen()
        } else if (standby && autoWakeEnabled) {
            listenStandby()
        }
    }

    /** Explicitly starts active continuous voice mode */
    fun start() {
        if (pausedForCall || isPhoneCallActive()) return
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
        if (autoWakeEnabled && !pausedForCall && !isPhoneCallActive()) {
            startStandby()
        }
    }

    /** Enables low-power standby wake-word listening when not actively conversing */
    fun startStandby() {
        if (active || pausedForCall || isPhoneCallActive()) return
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

        // Guarantee media stream volume is audible so the speech isn't silent
        try {
            audioManager?.let { am ->
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (currentVol <= 0) {
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.70f).toInt().coerceAtLeast(1), 0)
                }
            }
        } catch (ignored: Exception) {}

        val loc = Locale.forLanguageTag(lang)
        t.language = loc
        val vs = t.voices?.filter { it.locale.language == loc.language }?.sortedBy { it.name }.orEmpty()
        if (vs.isNotEmpty()) {
            if (female) {
                val ogFemale = vs.firstOrNull { it.name.contains("sfg") || it.name.contains("tpf") }
                    ?: vs.firstOrNull { it.name.lowercase(Locale.ROOT).contains("female") }
                    ?: vs.last()
                t.voice = ogFemale
                t.setPitch(1.0f)
            } else {
                val ogMale = vs.firstOrNull { it.name.contains("tpd") || it.name.contains("tpc") }
                    ?: vs.firstOrNull { it.name.lowercase(Locale.ROOT).contains("male") || it.name.contains("-m-") }
                    ?: vs.firstOrNull { !it.name.contains("sfg") && !it.name.contains("tpf") }
                    ?: vs.first()
                t.voice = ogMale
                t.setPitch(0.92f)
            }
        } else {
            t.setPitch(if (female) 1.0f else 0.92f)
        }
        t.setSpeechRate(1.0f)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) {
            release()
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(net) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    /** Active recognition intent with fast responsive thresholds */
    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            // Only request offline recognition if device has no network at all
            if (!isOnline()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    /** Standby intent with quiet thresholds */
    private fun buildStandbyIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            if (!isOnline()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    /** Checks whether a spoken phrase is a stop command */
    fun isStopCommand(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        if (t.isBlank()) return false

        // 1. User-configured custom stop command
        val customStop = commandingStopWord.trim().lowercase(Locale.ROOT)
        if (customStop.isNotBlank()) {
            if (t == customStop || t.contains(customStop)) return true
        }

        // 2. Universal stop phrases across Tamil, English, and Japanese
        val stopPhrases = listOf(
            // English
            "stop", "exit", "pause", "sleep", "shut down", "shutdown", "quit", "cancel",
            "go to sleep", "stop listening", "stop assistant", "bye", "goodbye", "good night",
            // Tamil
            "நிறுத்து", "போதும்", "நில்", "ஸ்டாப்", "முடி", "தூங்கு",
            "stop pannu", "stop panu", "stop pannunga", "stop panunga",
            "off pannu", "off panu", "off pannunga", "close pannu", "podhum",
            // Japanese
            "ストップ", "止めて", "おやすみ", "終了", "やめて"
        )
        for (p in stopPhrases) {
            if (t == p || t.startsWith("$p ") || t.endsWith(" $p") || t.contains(" $p ") || t.contains(p)) {
                return true
            }
        }
        return false
    }

    /** Active continuous listening loop */
    private fun listen() {
        if (!active || holding || pausedForCall || isPhoneCallActive()) return
        main.post {
            if (!active || holding || pausedForCall || isPhoneCallActive()) return@post
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            pitchSamples.clear()

            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(r: Bundle?) {
                        restoreBeep()
                        if (!active || pausedForCall || isPhoneCallActive()) return
                        val results = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val text = results.firstOrNull { it.isNotBlank() }

                        if (!text.isNullOrBlank()) {
                            // First check: Did the user say STOP?
                            if (isStopCommand(text)) {
                                holding = true
                                onStopCommandTriggered?.invoke()
                                return
                            }

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

                    override fun onPartialResults(p: Bundle?) {
                        if (!active || pausedForCall || isPhoneCallActive()) return
                        val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val partial = list.firstOrNull { it.isNotBlank() }
                        if (partial != null && isStopCommand(partial)) {
                            holding = true
                            try { sr?.cancel() } catch (ignored: Exception) {}
                            onStopCommandTriggered?.invoke()
                        }
                    }

                    override fun onError(e: Int) {
                        restoreBeep()
                        Log.d("VoiceEngine", "Active Recognizer onError code: $e")
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            stop()
                            return
                        }
                        val retryDelay = when (e) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 800L
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 250L
                            else -> 400L
                        }
                        if (active && !holding && !pausedForCall && !isPhoneCallActive()) {
                            main.postDelayed({ listen() }, retryDelay)
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

                // Mute notification/system sound right before startListening
                muteBeep()
                try {
                    startListening(buildRecognizerIntent())
                } catch (e: Exception) {
                    restoreBeep()
                    Log.e("VoiceEngine", "startListening error", e)
                    if (active && !holding) {
                        main.postDelayed({ listen() }, 500)
                    }
                }
            }
        }
    }

    /** Standby wake-word listening loop */
    private fun listenStandby() {
        if (active || !standby || !autoWakeEnabled || holding || pausedForCall || isPhoneCallActive()) return
        main.post {
            if (active || !standby || !autoWakeEnabled || holding || pausedForCall || isPhoneCallActive()) return@post
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

                                // Check if user said "Jarvis stop"
                                if (isStopCommand(raw) || (cmd != null && isStopCommand(cmd))) {
                                    val ack = when (lang) {
                                        "ta-IN" -> "சரி, ஓய்வில் இருக்கிறேன்."
                                        "ja-JP" -> "休止中です。"
                                        else -> "Assistant is in standby."
                                    }
                                    speak(ack)
                                    return
                                }

                                standby = false
                                start()
                                onWakeWordTriggered?.invoke(cmd)
                                return
                            }
                        }
                    }

                    override fun onResults(r: Bundle?) {
                        restoreBeep()
                        if (active || pausedForCall || isPhoneCallActive()) return
                        val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                        if (standby && !active && autoWakeEnabled && !pausedForCall && !isPhoneCallActive()) {
                            main.postDelayed({ listenStandby() }, 1200)
                        }
                    }

                    override fun onPartialResults(p: Bundle?) {
                        if (active || pausedForCall || isPhoneCallActive()) return
                        val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                    }

                    override fun onError(e: Int) {
                        restoreBeep()
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            standby = false
                            return
                        }
                        val retryDelay = when (e) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1200L
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 800L
                            else -> 1000L
                        }
                        if (standby && !active && autoWakeEnabled && !pausedForCall && !isPhoneCallActive()) {
                            main.postDelayed({ listenStandby() }, retryDelay)
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

                muteBeep()
                try {
                    startListening(buildStandbyIntent())
                } catch (e: Exception) {
                    restoreBeep()
                    if (standby && !active && autoWakeEnabled) {
                        main.postDelayed({ listenStandby() }, 1000)
                    }
                }
            }
        }
    }
}