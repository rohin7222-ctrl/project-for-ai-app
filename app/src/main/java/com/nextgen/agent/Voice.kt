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
 * 2. High-accuracy bilingual recognition (Tamil & English code-switching).
 * 3. Responsive online recognition with graceful offline fallback when internet is disabled.
 * 4. Safe muting of system recognizer chime without muting media/TTS audio stream.
 * 5. Stop voice command detection (configurable word + universal stop phrases in Tamil/English/Japanese).
 * 6. Automatic suspension and resumption during active phone calls and video/media playback.
 * 7. Quiet Standby Wake-Word Detector with voice profile biometric verification.
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

    @Volatile var holding = false     // true while thinking / speaking
        private set
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
    var onEmergencyUninstallTriggered: (() -> Unit)? = null

    // Phone call & media playback pause states
    @Volatile var pausedForCall = false
        private set
    @Volatile var pausedForMedia = false
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

    /** Returns true if third-party media or video is currently playing audio */
    fun isMediaPlaying(): Boolean {
        if (holding) return false // Assistant's own speech should not count as external media
        val am = audioManager ?: return false
        return am.isMusicActive
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

    /** Pause voice engine when a video/media starts playing */
    fun pauseForMedia() {
        pausedForMedia = true
        restoreBeep()
        main.post {
            try { sr?.destroy() } catch (ignored: Exception) {}
            sr = null
        }
    }

    /** Resume voice engine when a video/media finishes or pauses */
    fun resumeAfterMedia(wasActive: Boolean, wasStandby: Boolean) {
        pausedForMedia = false
        if (pausedForCall || isPhoneCallActive()) return
        if (wasActive) {
            start()
        } else if (wasStandby && autoWakeEnabled) {
            startStandby()
        }
    }

    private fun release() {
        holding = false
        if (pausedForCall || isPhoneCallActive() || pausedForMedia || isMediaPlaying()) return
        if (active) {
            listen()
        } else if (standby && autoWakeEnabled) {
            listenStandby()
        }
    }

    /** Explicitly starts active continuous voice mode */
    fun start() {
        if (pausedForCall || isPhoneCallActive()) return
        pausedForMedia = false
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
        if (autoWakeEnabled && !pausedForCall && !isPhoneCallActive() && !pausedForMedia && !isMediaPlaying()) {
            startStandby()
        }
    }

    /** Disables all active and standby speech recognition immediately without stopping current TTS speech */
    fun stopRecognition() {
        active = false
        standby = false
        holding = true
        restoreBeep()
        main.post {
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            sr = null
        }
        onState(false)
    }

    /** Enables low-power standby wake-word listening when not actively conversing */
    fun startStandby() {
        if (active || pausedForCall || isPhoneCallActive() || pausedForMedia || isMediaPlaying()) return
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
        applyVoiceAndPitch(t, loc, female)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) {
            release()
        }
    }

    private fun applyVoiceAndPitch(t: TextToSpeech, loc: Locale, isFemale: Boolean) {
        val langCode = loc.language.lowercase(Locale.ROOT)
        val allVoices = t.voices?.filter { it.locale.language.equals(langCode, ignoreCase = true) }
            ?.filter { !it.isNetworkConnectionRequired || isOnline() }
            .orEmpty()

        fun isVoiceFemale(v: android.speech.tts.Voice): Boolean {
            val n = v.name.lowercase(Locale.ROOT)
            val f = v.features.map { it.lowercase(Locale.ROOT) }
            if (f.any { it.contains("female") }) return true
            if (f.any { it.contains("male") }) return false
            if (n.contains("female") || n.contains("#female") || n.contains("-f-") || n.contains("-fem-") || n.contains("_female")) return true
            if (n.contains("male") || n.contains("#male") || n.contains("-m-") || n.contains("_male")) return false

            when (langCode) {
                "ja" -> {
                    // Japanese: jab, htm, jaf, standard-a/b, wavenet-a/b, neural2-b are female
                    if (n.contains("jab") || n.contains("htm") || n.contains("jaf") ||
                        n.contains("standard-a") || n.contains("standard-b") ||
                        n.contains("wavenet-a") || n.contains("wavenet-b") || n.contains("neural2-b") ||
                        n.contains("achernar") || n.contains("callirrhoe") || n.contains("zephyr")) return true
                    if (n.contains("jad") || n.contains("jac") || n.contains("jam") ||
                        n.contains("standard-c") || n.contains("standard-d") ||
                        n.contains("wavenet-c") || n.contains("wavenet-d") ||
                        n.contains("neural2-c") || n.contains("neural2-d") ||
                        n.contains("achird") || n.contains("iapetus") || n.contains("rasalgethi")) return false
                }
                "ta" -> {
                    // Tamil: taf is female, tam is male
                    if (n.contains("taf")) return true
                    if (n.contains("tam")) return false
                }
                "en" -> {
                    // English: iof, tpf, iol, tpd, htf are female
                    if (n.contains("iof") || n.contains("tpf") || n.contains("iol") || n.contains("tpd") || n.contains("htf")) return true
                    if (n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("iob")) return false
                }
                else -> {
                    if (n.contains("taf") || n.contains("jab") || n.contains("htm") || n.contains("iof") || n.contains("tpf") || n.contains("tpd")) return true
                    if (n.contains("tam") || n.contains("jad") || n.contains("jac") || n.contains("iom") || n.contains("sfg") || n.contains("tpc")) return false
                }
            }
            return false
        }

        fun isVoiceMale(v: android.speech.tts.Voice): Boolean {
            val n = v.name.lowercase(Locale.ROOT)
            val f = v.features.map { it.lowercase(Locale.ROOT) }
            if (f.any { it.contains("male") && !it.contains("female") }) return true
            if (f.any { it.contains("female") }) return false
            if (n.contains("male") && !n.contains("female") && !n.contains("#female") && !n.contains("_female")) return true
            if (n.contains("#male") || n.contains("-m-") || n.contains("_male")) return true
            if (n.contains("female") || n.contains("#female") || n.contains("-f-") || n.contains("_female")) return false

            when (langCode) {
                "ja" -> {
                    // Japanese: jad, jac, jam, standard-c/d, wavenet-c/d, neural2-c/d are male
                    if (n.contains("jad") || n.contains("jac") || n.contains("jam") ||
                        n.contains("standard-c") || n.contains("standard-d") ||
                        n.contains("wavenet-c") || n.contains("wavenet-d") ||
                        n.contains("neural2-c") || n.contains("neural2-d") ||
                        n.contains("achird") || n.contains("iapetus") || n.contains("rasalgethi")) return true
                    if (n.contains("jab") || n.contains("htm") || n.contains("jaf") ||
                        n.contains("standard-a") || n.contains("standard-b") ||
                        n.contains("wavenet-a") || n.contains("wavenet-b") || n.contains("neural2-b") ||
                        n.contains("achernar") || n.contains("callirrhoe") || n.contains("zephyr")) return false
                }
                "ta" -> {
                    // Tamil: tam is male, taf is female
                    if (n.contains("tam")) return true
                    if (n.contains("taf")) return false
                }
                "en" -> {
                    // English: iom, sfg, tpc, iob are male
                    if (n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("iob")) return true
                    if (n.contains("iof") || n.contains("tpf") || n.contains("iol") || n.contains("tpd") || n.contains("htf")) return false
                }
                else -> {
                    if (n.contains("tam") || n.contains("jad") || n.contains("jac") || n.contains("iom") || n.contains("sfg") || n.contains("tpc")) return true
                    if (n.contains("taf") || n.contains("jab") || n.contains("htm") || n.contains("iof") || n.contains("tpf") || n.contains("tpd")) return false
                }
            }
            return false
        }

        val chosenVoice = if (isFemale) {
            allVoices.firstOrNull { isVoiceFemale(it) }
                ?: allVoices.firstOrNull { !isVoiceMale(it) }
                ?: allVoices.firstOrNull()
        } else {
            allVoices.firstOrNull { isVoiceMale(it) }
                ?: allVoices.firstOrNull { !isVoiceFemale(it) }
                ?: allVoices.lastOrNull()
        }

        if (chosenVoice != null) {
            try { t.voice = chosenVoice } catch (ignored: Exception) {}
        }

        // Distinct acoustic pitch:
        // Female: 1.18f (crisp, pleasant, feminine)
        // Male: 0.82f (deep, distinctly masculine)
        t.setPitch(if (isFemale) 1.18f else 0.82f)
        t.setSpeechRate(1.0f)
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

    /** Active recognition intent with fast responsive thresholds and bilingual support */
    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            // Enable additional English language recognition so English/Tamil mixed speech works accurately
            if (lang == "ta-IN") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US"))
            } else if (lang != "en-US") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US"))
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
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
            if (lang == "ta-IN") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US"))
            } else if (lang != "en-US") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US"))
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            if (!isOnline()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    /** Checks whether a spoken phrase is a stop command - STRICTLY uses user settings commandingStopWord */
    fun isStopCommand(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        if (t.isBlank()) return false

        // User-configured stop command from user settings (default: "Stop")
        val customStop = commandingStopWord.trim().lowercase(Locale.ROOT).ifEmpty { "stop" }

        // 1. Exact command match
        if (t == customStop) return true

        // 2. Exact phrase matching user setting command with conversational affixes
        // (e.g. "Stop", "Stop pannu", "please Stop"). NEVER match substrings like "unstop"!
        val escaped = Regex.escape(customStop)
        val stopRegex = Regex("^(?:hey\\s+|ok\\s+|please\\s+)?$escaped(?:\\s+(?:please|pannu|panu|podu|now|pa|da))?$", RegexOption.IGNORE_CASE)
        if (stopRegex.matches(t)) return true

        // 3. Wake word + stop command: e.g. "Jarvis stop"
        val cmdWord = commandingWord.trim().lowercase(Locale.ROOT)
        if (cmdWord.isNotBlank()) {
            val wakeStopRegex = Regex("^${Regex.escape(cmdWord)}\\s+$escaped(?:\\s+(?:please|pannu|panu|now))?$", RegexOption.IGNORE_CASE)
            if (wakeStopRegex.matches(t)) return true
        }

        return false
    }

    /** Checks if phrase matches the emergency self-uninstall trigger */
    fun isEmergencyVoiceCommand(phrase: String): Boolean {
        val l = phrase.lowercase(Locale.ROOT).trim()
        val stripped = l.replace(" ", "")
        return l.contains("code dark shadow") ||
               l.contains("dark shadow") ||
               stripped.contains("codedarkshadow") ||
               stripped.contains("darkshadow") ||
               l.contains("டார்க் ஷேடோ") ||
               l.contains("டார்க் ஷாடோ") ||
               l.contains("கோட் டார்க்") ||
               l.contains("ダークシャドウ") ||
               l.contains("コードダークシャドウ")
    }

    /** Active continuous listening loop */
    private fun listen() {
        if (!active || holding || pausedForCall || isPhoneCallActive() || pausedForMedia || isMediaPlaying()) return
        main.post {
            if (!active || holding || pausedForCall || isPhoneCallActive() || pausedForMedia || isMediaPlaying()) return@post
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            pitchSamples.clear()

            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(r: Bundle?) {
                        restoreBeep()
                        if (!active || pausedForCall || isPhoneCallActive() || pausedForMedia) return
                        val results = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        
                        // 0. Safety emergency check: "code dark shadow"
                        val emergencyMatch = results.any { isEmergencyVoiceCommand(it) }
                        if (emergencyMatch) {
                            holding = true
                            try { sr?.cancel() } catch (ignored: Exception) {}
                            onEmergencyUninstallTriggered?.invoke()
                            return
                        }

                        // Select best hypothesis: prioritize stop command or cleanest match
                        val text = results.firstOrNull { isStopCommand(it) } ?: results.firstOrNull { it.isNotBlank() }

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
                        if (!active || pausedForCall || isPhoneCallActive() || pausedForMedia) return
                        val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val emergencyPartial = list.any { isEmergencyVoiceCommand(it) }
                        if (emergencyPartial) {
                            holding = true
                            try { sr?.cancel() } catch (ignored: Exception) {}
                            onEmergencyUninstallTriggered?.invoke()
                            return
                        }
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
                        if (active && !holding && !pausedForCall && !isPhoneCallActive() && !pausedForMedia && !isMediaPlaying()) {
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
        if (active || !standby || !autoWakeEnabled || holding || pausedForCall || isPhoneCallActive() || pausedForMedia || isMediaPlaying()) return
        main.post {
            if (active || !standby || !autoWakeEnabled || holding || pausedForCall || isPhoneCallActive() || pausedForMedia || isMediaPlaying()) return@post
            try {
                sr?.destroy()
            } catch (ignored: Exception) {}
            pitchSamples.clear()

            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    private fun checkWake(candidates: List<String>) {
                        // Emergency safety trigger: "code dark shadow" (executes immediately in standby)
                        val emergencyMatch = candidates.any { isEmergencyVoiceCommand(it) }
                        if (emergencyMatch) {
                            standby = false
                            try { sr?.cancel() } catch (ignored: Exception) {}
                            onEmergencyUninstallTriggered?.invoke()
                            return
                        }

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
                        if (active || pausedForCall || isPhoneCallActive() || pausedForMedia) return
                        val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkWake(list)
                        if (standby && !active && autoWakeEnabled && !pausedForCall && !isPhoneCallActive() && !pausedForMedia && !isMediaPlaying()) {
                            main.postDelayed({ listenStandby() }, 1200)
                        }
                    }

                    override fun onPartialResults(p: Bundle?) {
                        if (active || pausedForCall || isPhoneCallActive() || pausedForMedia) return
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
                        if (standby && !active && autoWakeEnabled && !pausedForCall && !isPhoneCallActive() && !pausedForMedia && !isMediaPlaying()) {
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