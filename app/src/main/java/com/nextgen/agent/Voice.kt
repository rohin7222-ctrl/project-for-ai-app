package com.nextgen.agent

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
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
    @Volatile private var lastTtsEndTime: Long = 0L
    private var ttsWatchdogRunnable: Runnable? = null

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
    var onPartialText: ((String) -> Unit)? = null
    var onErrorDescription: ((errorCode: Int, description: String) -> Unit)? = null

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
        if (pausedForCall) return true
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            try {
                @Suppress("DEPRECATION")
                if (tm.callState != TelephonyManager.CALL_STATE_IDLE) {
                    return true
                }
            } catch (ignored: Exception) {}
        }
        val am = audioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    /** Returns true if third-party media or video is currently playing audio */
    fun isMediaPlaying(): Boolean {
        if (holding) return false // Assistant's own speech should not count as external media
        if (System.currentTimeMillis() - lastTtsEndTime < 1500L) return false // Trailing TTS AudioTrack grace period
        val am = audioManager ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = try { am.activePlaybackConfigurations } catch (e: Exception) { null }
            if (configs != null) {
                val hasActive = configs.any { c ->
                    val usage = c.audioAttributes?.usage ?: return@any false
                    val isMedia = usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
                    if (!isMedia) return@any false

                    val str = c.toString().lowercase(Locale.ROOT)
                    if (str.contains("paused") || str.contains("stopped") || str.contains("idle") || str.contains("released")) {
                        return@any false
                    }
                    if (str.contains("started")) {
                        return@any true
                    }
                    try {
                        val field = c.javaClass.getDeclaredField("mPlayerState").apply { isAccessible = true }
                        val state = field.getInt(c)
                        return@any state == 2 // 2 = PLAYER_STATE_STARTED
                    } catch (ignored: Exception) {}

                    true
                }
                return hasActive
            }
        }
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
        try {
            audioManager?.let { am ->
                if (am.mode != AudioManager.MODE_NORMAL) {
                    am.mode = AudioManager.MODE_NORMAL
                }
            }
        } catch (ignored: Exception) {}

        if (wasActive) {
            start()
        } else if (wasStandby || autoWakeEnabled) {
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
        } else if (wasStandby || autoWakeEnabled) {
            startStandby()
        }
    }

    private fun release() {
        ttsWatchdogRunnable?.let { main.removeCallbacks(it) }
        ttsWatchdogRunnable = null
        holding = false
        lastTtsEndTime = System.currentTimeMillis()
        if (pausedForCall || isPhoneCallActive()) return
        if (pausedForMedia || isMediaPlaying()) return
        if (active) {
            listen()
        } else if (standby && autoWakeEnabled) {
            listenStandby()
        } else if (autoWakeEnabled && !active && !standby) {
            startStandby()
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
        if (active) return
        if (pausedForCall || isPhoneCallActive()) {
            main.postDelayed({
                if (!active && !standby && autoWakeEnabled && !pausedForCall && !isPhoneCallActive()) {
                    startStandby()
                }
            }, 500)
            return
        }
        if (pausedForMedia || isMediaPlaying()) return
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
        ttsWatchdogRunnable?.let { main.removeCallbacks(it) }
        ttsWatchdogRunnable = null
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
        val t = tts ?: run {
            release()
            return
        }
        val clean = text.trim()
        if (clean.isBlank()) {
            release()
            return
        }
        holding = true
        restoreBeep()
        main.post {
            try {
                sr?.cancel()
            } catch (ignored: Exception) {}
        }

        ttsWatchdogRunnable?.let { main.removeCallbacks(it) }
        val maxDurationMs = (clean.length * 120L + 4000L).coerceIn(4000L, 20000L)
        val watchdog = Runnable {
            if (holding) {
                Log.w("VoiceEngine", "TTS watchdog triggered after ${maxDurationMs}ms")
                release()
            }
        }
        ttsWatchdogRunnable = watchdog
        main.postDelayed(watchdog, maxDurationMs)

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
        val utteranceId = "u" + System.nanoTime()
        if (t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
            release()
        }
    }

    /** Stops current TTS speech playback immediately without disturbing recognizer */
    fun stopSpeakingOnly() {
        try {
            tts?.stop()
        } catch (ignored: Exception) {}
        ttsWatchdogRunnable?.let { main.removeCallbacks(it) }
        ttsWatchdogRunnable = null
        holding = false
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
                    // Tamil: taf, ta-in-x-taf are female; tam, ta-in-x-tam are male
                    if (n.contains("taf") || n.contains("-taf") || n.contains("_taf")) return true
                    if (n.contains("tam") || n.contains("-tam") || n.contains("_tam")) return false
                }
                "hi" -> {
                    // Hindi: hif, hie, hid, wavenet-a/b/e are female; him, hic, hia are male
                    if (n.contains("hif") || n.contains("-hie") || n.contains("-hid") || n.contains("wavenet-a") || n.contains("wavenet-e")) return true
                    if (n.contains("him") || n.contains("-hic") || n.contains("-hia") || n.contains("wavenet-b") || n.contains("wavenet-d")) return false
                }
                "ko" -> {
                    // Korean: kof, kod, wavenet-a/b are female; kom, koc, kob are male
                    if (n.contains("kof") || n.contains("-kof") || n.contains("-kod") || n.contains("wavenet-a") || n.contains("wavenet-b")) return true
                    if (n.contains("kom") || n.contains("-kom") || n.contains("-koc") || n.contains("-kob") || n.contains("wavenet-c")) return false
                }
                "en" -> {
                    // English: iof, tpf, iol, tpd, htf are female
                    if (n.contains("iof") || n.contains("tpf") || n.contains("iol") || n.contains("tpd") || n.contains("htf")) return true
                    if (n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("iob")) return false
                }
                else -> {
                    if (n.contains("taf") || n.contains("jab") || n.contains("htm") || n.contains("iof") || n.contains("tpf") || n.contains("tpd") || n.contains("hif") || n.contains("kof")) return true
                    if (n.contains("tam") || n.contains("jad") || n.contains("jac") || n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("him") || n.contains("kom")) return false
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
                    if (n.contains("tam") || n.contains("-tam") || n.contains("_tam")) return true
                    if (n.contains("taf") || n.contains("-taf") || n.contains("_taf")) return false
                }
                "hi" -> {
                    // Hindi: him, hic, hia are male; hif, hie are female
                    if (n.contains("him") || n.contains("-hic") || n.contains("-hia") || n.contains("wavenet-b") || n.contains("wavenet-d")) return true
                    if (n.contains("hif") || n.contains("-hie") || n.contains("-hid") || n.contains("wavenet-a") || n.contains("wavenet-e")) return false
                }
                "ko" -> {
                    // Korean: kom, koc, kob are male; kof, kod are female
                    if (n.contains("kom") || n.contains("-kom") || n.contains("-koc") || n.contains("-kob") || n.contains("wavenet-c")) return true
                    if (n.contains("kof") || n.contains("-kof") || n.contains("-kod") || n.contains("wavenet-a") || n.contains("wavenet-b")) return false
                }
                "en" -> {
                    // English: iom, sfg, tpc, iob are male
                    if (n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("iob")) return true
                    if (n.contains("iof") || n.contains("tpf") || n.contains("iol") || n.contains("tpd") || n.contains("htf")) return false
                }
                else -> {
                    if (n.contains("tam") || n.contains("jad") || n.contains("jac") || n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("him") || n.contains("kom")) return true
                    if (n.contains("taf") || n.contains("jab") || n.contains("htm") || n.contains("iof") || n.contains("tpf") || n.contains("tpd") || n.contains("hif") || n.contains("kof")) return false
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

    /** Active recognition intent with fast responsive thresholds and multilingual support */
    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            // Enable bilingual recognition so English/Tamil mixed speech works accurately
            if (lang == "ta-IN") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US", "ta-IN"))
            } else if (lang != "en-US") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN"))
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            // EXTRA_PREFER_OFFLINE removed: prevents SpeechRecognizer errors on devices without offline packages
        }
    }

    /** Standby intent with quiet thresholds and multilingual support */
    private fun buildStandbyIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            if (lang == "ta-IN") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US", "ta-IN"))
            } else if (lang != "en-US") {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN"))
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            // EXTRA_PREFER_OFFLINE removed: ensures reliable wake-word standby across all Android devices
        }
    }

    /** Checks whether a spoken phrase is a stop command - Supports user settings, colloquial Tamil, English, and Japanese */
    fun isStopCommand(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        if (t.isBlank()) return false

        // User-configured stop command from user settings (default: "Stop")
        val customStop = commandingStopWord.trim().lowercase(Locale.ROOT).ifEmpty { "stop" }

        // 1. Exact command match
        if (t == customStop) return true

        // 2. Exact phrase matching user setting command with conversational affixes
        val escaped = Regex.escape(customStop)
        val stopRegex = Regex("^(?:hey\\s+|ok\\s+|please\\s+)?$escaped(?:\\s+(?:please|pannu|panu|podu|now|pa|da))?$", RegexOption.IGNORE_CASE)
        if (stopRegex.matches(t)) return true

        // 3. Wake word + stop command: e.g. "Jarvis stop"
        val cmdWord = commandingWord.trim().lowercase(Locale.ROOT)
        if (cmdWord.isNotBlank()) {
            val wakeStopRegex = Regex("^${Regex.escape(cmdWord)}\\s+$escaped(?:\\s+(?:please|pannu|panu|now))?$", RegexOption.IGNORE_CASE)
            if (wakeStopRegex.matches(t)) return true
        }

        // 4. Universal Tamil stop phrases & colloquial variants
        val tamilStopWords = listOf(
            "நிறுத்து", "நிறுத்துங்க", "போதும்", "நில்", "ஸ்டாப்", "நிறுத்துப்பா",
            "நிறுத்துடா", "பேசாத", "அமைதியா இரு", "வாயை மூடு", "போதும் நிறுத்து",
            "niruthu", "niruthunga", "pothum", "stop pannu", "stop panu", "stop podu"
        )
        for (w in tamilStopWords) {
            if (t == w || t.startsWith("$w ") || t.endsWith(" $w") || t.contains(" $w ") ||
                t.startsWith("ஜார்விஸ் $w") || t.startsWith("ஹே ஜார்விஸ் $w") ||
                t.startsWith("jarvis $w") || t.startsWith("hey jarvis $w")) {
                return true
            }
        }

        // 5. Universal English stop words & colloquial variants
        val englishStopWords = listOf(
            "stop", "stop it", "shut up", "be quiet", "cancel", "pause", "enough",
            "halt", "stop talking", "stop now", "please stop", "quit", "silence", "shutup"
        )
        for (w in englishStopWords) {
            if (t == w || t.startsWith("$w ") || t.endsWith(" $w") || t.contains(" $w ") ||
                t.startsWith("jarvis $w") || t.startsWith("hey jarvis $w")) {
                return true
            }
        }

        // 6. Japanese stop words
        val jaStopWords = listOf("ストップ", "止めて", "やめて", "静かに", "終了")
        for (w in jaStopWords) {
            if (t.contains(w)) return true
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
                            onPartialText?.invoke("")
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
                        if (partial != null) {
                            onPartialText?.invoke(partial)
                            if (isStopCommand(partial)) {
                                holding = true
                                onPartialText?.invoke("")
                                try { sr?.cancel() } catch (ignored: Exception) {}
                                onStopCommandTriggered?.invoke()
                            }
                        }
                    }

                    override fun onError(e: Int) {
                        restoreBeep()
                        onPartialText?.invoke("")
                        val errMsg = getErrorMessage(e)
                        Log.d("VoiceEngine", "Active Recognizer onError code: $e ($errMsg)")
                        onErrorDescription?.invoke(e, errMsg)
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

    /** Translates SpeechRecognizer error codes into clear, multilingual error descriptions */
    fun getErrorMessage(e: Int): String {
        return when (e) {
            SpeechRecognizer.ERROR_AUDIO -> when (lang) {
                "ta-IN" -> "ஆடியோ பதிவு பிழை (மைக் சிக்கல்)"
                "ja-JP" -> "音声録音エラー (マイク)"
                else -> "Audio recording error (Mic)"
            }
            SpeechRecognizer.ERROR_CLIENT -> when (lang) {
                "ta-IN" -> "கிளையண்ட் அங்கீகாரப் பிழை"
                "ja-JP" -> "クライアント認識エラー"
                else -> "Client recognition error"
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> when (lang) {
                "ta-IN" -> "மைக் அனுமதி தேவை (அனுமதி வழங்கவும்)"
                "ja-JP" -> "マイクの権限が必要です"
                else -> "Microphone permission required"
            }
            SpeechRecognizer.ERROR_NETWORK -> when (lang) {
                "ta-IN" -> "இணைய இணைப்பு பிழை"
                "ja-JP" -> "ネットワークエラー"
                else -> "Network connection error"
            }
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> when (lang) {
                "ta-IN" -> "இணைய நேர வரம்பு முடிந்தது"
                "ja-JP" -> "ネットワークタイムアウト"
                else -> "Network request timed out"
            }
            SpeechRecognizer.ERROR_NO_MATCH -> when (lang) {
                "ta-IN" -> "வார்த்தை புரியவில்லை"
                "ja-JP" -> "音声が一致しませんでした"
                else -> "No speech match found"
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> when (lang) {
                "ta-IN" -> "மைக் இயக்கம் பிஸியாக உள்ளது"
                "ja-JP" -> "音声認識エンジンがビジー状態です"
                else -> "Speech recognition engine busy"
            }
            SpeechRecognizer.ERROR_SERVER -> when (lang) {
                "ta-IN" -> "சர்வர் பிழை"
                "ja-JP" -> "サーバーエラー"
                else -> "Server error"
            }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> when (lang) {
                "ta-IN" -> "பேச்சு எதுவும் கேட்கவில்லை"
                "ja-JP" -> "音声入力が検出されませんでした"
                else -> "No speech detected"
            }
            else -> when (lang) {
                "ta-IN" -> "மைக் பிழை ($e)"
                "ja-JP" -> "認識エラー ($e)"
                else -> "Voice recognition error ($e)"
            }
        }
    }

    /** Returns list of accepted phonetic aliases for the user's wake word */
    fun getWakeWordAliases(): List<String> {
        val word = commandingWord.trim().lowercase(Locale.ROOT)
        val list = mutableListOf<String>()
        if (word.isNotBlank()) {
            list.addAll(listOf(word, "hey $word", "hi $word", "ok $word", "hello $word", "ஹே $word"))
        }
        // Always include jarvis, aero fenix, and fenix aliases as standard wake words
        list.addAll(listOf(
            "aero fenix", "hey aero fenix", "aerofenix", "hey fenix", "fenix", "phoenix", "hey phoenix",
            "ஏரோ பீனிக்ஸ்", "பீனிக்ஸ்", "ஹே பீனிக்ஸ்", "ஹே ஃபீனிக்ஸ்", "ஃபீனிக்ஸ்",
            "फेनिक्स", "हे फेनिक्स", "एरो फेनिक्स",
            "フェニックス", "エアロフェニックス",
            "jarvis", "hey jarvis", "hi jarvis", "ok jarvis", "hello jarvis",
            "jarvish", "jarves", "javis", "charvis",
            "ஜார்விஸ்", "ஜார்வி", "சார்விஸ்", "ஜார்வீஸ்",
            "ஹே ஜார்விஸ்", "ஹே ஜார்வி",
            "ジャーヴィス", "ジャービス"
        ))
        return list.distinct()
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

                        val aliases = getWakeWordAliases()
                        if (aliases.isEmpty()) return
                        for (raw in candidates) {
                            val lower = raw.lowercase(Locale.ROOT)
                            val matchedAlias = aliases.firstOrNull { lower.contains(it) }
                            if (matchedAlias != null) {
                                if (voiceMatchEnabled && voiceProfile != null && voiceProfile!!.isEnrolled) {
                                    if (pitchSamples.isNotEmpty()) {
                                        val avg = pitchSamples.average().toFloat()
                                        if (!VoiceProfiler.matchesProfile(avg, voiceProfile!!)) {
                                            onVoiceRejected?.invoke()
                                            return
                                        }
                                    }
                                }

                                val idx = lower.indexOf(matchedAlias)
                                val after = raw.substring(idx + matchedAlias.length).trim(' ', ',', '.', '?')
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
                        val errMsg = getErrorMessage(e)
                        Log.d("VoiceEngine", "Standby Recognizer onError code: $e ($errMsg)")
                        onErrorDescription?.invoke(e, errMsg)
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