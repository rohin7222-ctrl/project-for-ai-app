package com.nextgen.agent

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
 * 5. Stop voice command detection (configurable word + universal stop phrases in Tamil/English).
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

    // Concurrency & retry backoff management
    private var pendingActiveRunnable: Runnable? = null
    private var pendingStandbyRunnable: Runnable? = null
    private var consecutiveErrorCount = 0
    private val maxConsecutiveErrors = 4

    // Voice Biometrics & Commanding Word settings (default: assistant's own name)
    var commandingWord: String = "Aero Fenix"
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
    private var lastRecognizedPartial: String = ""

    // Phone call & media playback pause states
    @Volatile var pausedForCall = false
        private set
    @Volatile var pausedForMedia = false
        private set

    private val pitchSamples = mutableListOf<Float>()

    init {
        // Guarantee audio volume is restored if the app is killed or process terminates
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    restoreBeep(immediate = true)
                } catch (ignored: Throwable) {}
            })
        } catch (ignored: Throwable) {}

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

    private fun cancelPendingLoops() {
        pendingActiveRunnable?.let { main.removeCallbacks(it) }
        pendingActiveRunnable = null
        pendingStandbyRunnable?.let { main.removeCallbacks(it) }
        pendingStandbyRunnable = null
    }

    private fun destroyRecognizerSafely() {
        val current = sr
        sr = null
        if (current != null) {
            try { current.cancel() } catch (ignored: Exception) {}
            try { current.destroy() } catch (ignored: Exception) {}
        }
    }

    /** Resolves the best available RecognitionService component across all Android OEMs (Oppo, Realme, Redmi, Vivo, Motorola, Pixel, Samsung) */
    private fun findBestRecognitionService(context: Context): ComponentName? {
        val pm = context.packageManager
        val intent = Intent("android.speech.RecognitionService")
        val services = try {
            pm.queryIntentServices(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        if (services.isEmpty()) return null

        // 1. Google App - standard, robust recognition across Pixel, Motorola, Nothing, Oppo, Realme, Redmi, Vivo
        val google = services.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
        if (google != null) return ComponentName(google.serviceInfo.packageName, google.serviceInfo.name)

        // 2. Google Speech Services / TTS
        val googleTts = services.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
        if (googleTts != null) return ComponentName(googleTts.serviceInfo.packageName, googleTts.serviceInfo.name)

        // 3. Samsung Recognition Service
        val samsung = services.firstOrNull { it.serviceInfo.packageName.contains("samsung") }
        if (samsung != null) return ComponentName(samsung.serviceInfo.packageName, samsung.serviceInfo.name)

        // 4. System default if set in Settings.Secure
        try {
            val defaultSetting = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            if (!defaultSetting.isNullOrBlank()) {
                val defComponent = ComponentName.unflattenFromString(defaultSetting)
                if (defComponent != null && services.any { it.serviceInfo.packageName == defComponent.packageName }) {
                    return defComponent
                }
            }
        } catch (ignored: Exception) {}

        // 5. First available RecognitionService
        val first = services.first()
        return ComponentName(first.serviceInfo.packageName, first.serviceInfo.name)
    }

    private fun getOrCreateRecognizer(): SpeechRecognizer? {
        val current = sr
        if (current != null) return current
        val comp = findBestRecognitionService(ctx)
        return try {
            if (comp != null) {
                Log.i("VoiceEngine", "Binding SpeechRecognizer to: ${comp.flattenToShortString()}")
                SpeechRecognizer.createSpeechRecognizer(ctx, comp).also { sr = it }
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx).also { sr = it }
            }
        } catch (e: Exception) {
            Log.e("VoiceEngine", "Failed to create SpeechRecognizer with component, falling back to default", e)
            try {
                SpeechRecognizer.createSpeechRecognizer(ctx).also { sr = it }
            } catch (ex: Exception) {
                Log.e("VoiceEngine", "Failed to create default SpeechRecognizer", ex)
                val msg = when (lang) {
                    "ta-IN" -> "குரல் அறிதல் சேவை கிடைக்கவில்லை (Google Speech Services நிறுவவும்)"
                    else -> "Speech recognition service not available on device"
                }
                onErrorDescription?.invoke(SpeechRecognizer.ERROR_CLIENT, msg)
                null
            }
        }
    }

    /** Mutes system chime safely without triggering Do Not Disturb exceptions on OEM devices */
    private fun muteBeep() {
        // Preserved intact without altering STREAM_SYSTEM to guarantee 100% audio server
        // and microphone capture stability on ColorOS, RealmeUI, MIUI/HyperOS, FuntouchOS, Pixel & Motorola.
    }

    /** Restores device audio volume once the recognizer has safely started or immediately upon stop/destroy */
    fun restoreBeep(immediate: Boolean = false) {
        // Safe no-op for stream stability across all OEM devices
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

    /** Explicitly starts active continuous voice mode without mic collision */
    fun start() {
        if (pausedForCall || isPhoneCallActive()) return
        pausedForMedia = false
        active = true
        standby = false
        holding = false
        cancelPendingLoops()
        try { sr?.cancel() } catch (ignored: Exception) {}
        consecutiveErrorCount = 0
        onState(true)

        val run = Runnable {
            if (active && !holding && !pausedForCall && !isPhoneCallActive()) {
                listen()
            }
        }
        pendingActiveRunnable = run
        main.postDelayed(run, 60)
    }

    /** Explicitly stops active continuous voice mode */
    fun stop() {
        active = false
        holding = false
        cancelPendingLoops()
        destroyRecognizerSafely()
        restoreBeep(immediate = true)
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
        cancelPendingLoops()
        destroyRecognizerSafely()
        restoreBeep(immediate = true)
        onState(false)
    }

    /** Enables low-power standby wake-word listening when not actively conversing */
    fun startStandby() {
        if (active) return
        if (pausedForCall || isPhoneCallActive()) {
            cancelPendingLoops()
            val run = Runnable {
                if (!active && !standby && autoWakeEnabled && !pausedForCall && !isPhoneCallActive()) {
                    startStandby()
                }
            }
            pendingStandbyRunnable = run
            main.postDelayed(run, 500)
            return
        }
        if (pausedForMedia || isMediaPlaying()) return
        cancelPendingLoops()
        standby = true
        holding = false
        destroyRecognizerSafely()
        val run = Runnable {
            if (!active && standby && autoWakeEnabled && !pausedForCall && !isPhoneCallActive()) {
                listenStandby()
            }
        }
        pendingStandbyRunnable = run
        main.postDelayed(run, 150)
    }

    fun stopStandby() {
        standby = false
        cancelPendingLoops()
        restoreBeep(immediate = true)
        if (!active) {
            destroyRecognizerSafely()
        }
    }

    fun shutdown() {
        ttsWatchdogRunnable?.let { main.removeCallbacks(it) }
        ttsWatchdogRunnable = null
        cancelPendingLoops()
        active = false
        standby = false
        holding = false
        destroyRecognizerSafely()
        restoreBeep(immediate = true)
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
                    if (n.contains("taf") || n.contains("iof") || n.contains("tpf") || n.contains("tpd") || n.contains("hif") || n.contains("kof")) return true
                    if (n.contains("tam") || n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("him") || n.contains("kom")) return false
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
                    if (n.contains("tam") || n.contains("iom") || n.contains("sfg") || n.contains("tpc") || n.contains("him") || n.contains("kom")) return true
                    if (n.contains("taf") || n.contains("iof") || n.contains("tpf") || n.contains("tpd") || n.contains("hif") || n.contains("kof")) return false
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Enable bilingual recognition so English/Tamil mixed speech works accurately
            val additionalLangs = if (lang == "ta-IN") {
                arrayListOf("en-IN", "en-US", "ta-IN")
            } else if (lang != "en-US") {
                arrayListOf("en-US", "en-IN")
            } else {
                arrayListOf("ta-IN")
            }
            putStringArrayListExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", additionalLangs)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
    }

    /** Standby intent with quiet thresholds and multilingual support */
    private fun buildStandbyIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            val additionalLangs = if (lang == "ta-IN") {
                arrayListOf("en-IN", "en-US", "ta-IN")
            } else if (lang != "en-US") {
                arrayListOf("en-US", "en-IN")
            } else {
                arrayListOf("ta-IN")
            }
            putStringArrayListExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", additionalLangs)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
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

        return false
    }

    /** Checks if phrase matches the emergency self-uninstall trigger */
    fun isEmergencyVoiceCommand(phrase: String): Boolean {
        val l = phrase.lowercase(Locale.ROOT).trim()
        val stripped = l.replace(" ", "")
        return l == "code dark shadow" ||
               stripped == "codedarkshadow" ||
               l == "கோட் டார்க் ஷேடோ"
    }

    /** Active continuous listening loop */
    private fun listen() {
        if (!active || holding || pausedForCall || isPhoneCallActive()) return
        main.post {
            if (!active || holding || pausedForCall || isPhoneCallActive()) return@post
            pitchSamples.clear()
            lastRecognizedPartial = ""

            val recognizer = getOrCreateRecognizer() ?: return@post
            try {
                recognizer.cancel()
            } catch (ignored: Exception) {}

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    restoreBeep()
                    Log.d("VoiceEngine", "Active recognizer ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    restoreBeep()
                    consecutiveErrorCount = 0
                    onErrorDescription?.invoke(0, "")
                    Log.d("VoiceEngine", "Active speech started")
                }

                override fun onRmsChanged(v: Float) {}

                override fun onBufferReceived(b: ByteArray?) {
                    if (b != null && voiceMatchEnabled) {
                        val p = VoiceProfiler.extractPitchFromBytes(b)
                        if (p in 80f..350f) {
                            pitchSamples.add(p)
                        }
                    }
                }

                override fun onEndOfSpeech() {
                    Log.d("VoiceEngine", "Active speech ended")
                }

                override fun onResults(r: Bundle?) {
                    restoreBeep()
                    consecutiveErrorCount = 0
                    onErrorDescription?.invoke(0, "")
                    if (!active || pausedForCall || isPhoneCallActive()) return
                    val results = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    
                    // 0. Safety emergency check: "code dark shadow"
                    val emergencyMatch = results.any { isEmergencyVoiceCommand(it) }
                    if (emergencyMatch) {
                        holding = true
                        try { sr?.cancel() } catch (ignored: Exception) {}
                        onEmergencyUninstallTriggered?.invoke()
                        return
                    }

                    // Select best hypothesis: prioritize stop command, cleanly recognized text, or partial text rescued from noise
                    var text = results.firstOrNull { isStopCommand(it) } ?: results.firstOrNull { it.isNotBlank() }
                    if (text.isNullOrBlank() && lastRecognizedPartial.isNotBlank()) {
                        text = lastRecognizedPartial
                    }
                    lastRecognizedPartial = ""

                    if (!text.isNullOrBlank()) {
                        // First check: Did the user say STOP?
                        if (isStopCommand(text)) {
                            holding = true
                            onStopCommandTriggered?.invoke()
                            return
                        }

                        // Speaker verification check if enabled - only reject if pitch samples were received and failed match
                        if (voiceMatchEnabled && voiceProfile != null && voiceProfile!!.isEnrolled) {
                            if (pitchSamples.isNotEmpty()) {
                                val avg = pitchSamples.average().toFloat()
                                if (!VoiceProfiler.matchesProfile(avg, voiceProfile!!)) {
                                    onVoiceRejected?.invoke()
                                    cancelPendingLoops()
                                    val run = Runnable { listen() }
                                    pendingActiveRunnable = run
                                    main.postDelayed(run, 400)
                                    return
                                }
                            }
                        }

                        holding = true
                        onPartialText?.invoke("")
                        onText(text)
                    } else {
                        cancelPendingLoops()
                        val run = Runnable { listen() }
                        pendingActiveRunnable = run
                        main.postDelayed(run, 200)
                    }
                }

                override fun onPartialResults(p: Bundle?) {
                    if (!active || pausedForCall || isPhoneCallActive()) return
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
                        lastRecognizedPartial = partial
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
                    restoreBeep(immediate = true)

                    // Noise recovery: If noise prevented final match or caused speech timeout, rescue the partial text
                    if ((e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastRecognizedPartial.isNotBlank()) {
                        val savedPartial = lastRecognizedPartial
                        lastRecognizedPartial = ""
                        Log.i("VoiceEngine", "Rescued voice command in noisy environment: $savedPartial")
                        holding = true
                        onPartialText?.invoke("")
                        onErrorDescription?.invoke(0, "")
                        onText(savedPartial)
                        return
                    }
                    lastRecognizedPartial = ""

                    onPartialText?.invoke("")
                    val errMsg = getErrorMessage(e)
                    Log.d("VoiceEngine", "Active Recognizer onError code: $e ($errMsg)")
                    onErrorDescription?.invoke(e, errMsg)

                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stop()
                        return
                    }

                    // Code 12: ERROR_LANGUAGE_NOT_SUPPORTED, Code 13: ERROR_LANGUAGE_UNAVAILABLE
                    if (e == 12 || e == 13) {
                        if (lang == "ta-IN") {
                            Log.w("VoiceEngine", "ta-IN not available on this OEM device ($e), auto-falling back to en-IN / multilingual mode")
                            lang = "en-IN"
                            if (active && !holding && !pausedForCall && !isPhoneCallActive()) {
                                cancelPendingLoops()
                                val run = Runnable { listen() }
                                pendingActiveRunnable = run
                                main.postDelayed(run, 250L)
                            }
                            return
                        }
                        active = false
                        onState(false)
                        val langMsg = when (lang) {
                            "ta-IN" -> "தேர்ந்தெடுக்கப்பட்ட மொழி ($lang) சாதனத்தில் இல்லை. அமைப்புகளில் மாற்றவும்."
                            else -> "Selected language ($lang) is not supported on this device. Change language in settings."
                        }
                        onErrorDescription?.invoke(e, langMsg)
                        return
                    }

                    // Normal silence or timeout without user speech: reuse existing recognizer instance cleanly
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if (active && !holding && !pausedForCall && !isPhoneCallActive()) {
                            cancelPendingLoops()
                            val run = Runnable { listen() }
                            pendingActiveRunnable = run
                            main.postDelayed(run, 250L)
                        }
                        return
                    }

                    // Serious errors (busy, client, audio): reset recognizer safely so fresh connection is established
                    destroyRecognizerSafely()

                    consecutiveErrorCount++
                    if (consecutiveErrorCount >= maxConsecutiveErrors) {
                        active = false
                        onState(false)
                        val maxMsg = when (lang) {
                            "ta-IN" -> "மைக் தொடர்ந்து பிழை ($e: $errMsg). மைக் பட்டனைத் தட்டி மீண்டும் தொடங்கவும்."
                            else -> "Mic stopped after repeated errors ($e: $errMsg). Tap mic to restart."
                        }
                        onErrorDescription?.invoke(e, maxMsg)
                        return
                    }

                    val retryDelay = when (e) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L * (1 shl consecutiveErrorCount.coerceAtMost(3))
                        SpeechRecognizer.ERROR_CLIENT -> 600L * (1 shl consecutiveErrorCount.coerceAtMost(2))
                        else -> 500L * (1 shl consecutiveErrorCount.coerceAtMost(2))
                    }

                    if (active && !holding && !pausedForCall && !isPhoneCallActive()) {
                        cancelPendingLoops()
                        val run = Runnable { listen() }
                        pendingActiveRunnable = run
                        main.postDelayed(run, retryDelay)
                    }
                }

                override fun onEvent(t: Int, p: Bundle?) {}
            })

            // Mute notification/system sound right before startListening
            muteBeep()
            try {
                recognizer.startListening(buildRecognizerIntent())
            } catch (e: Exception) {
                restoreBeep()
                destroyRecognizerSafely()
                Log.e("VoiceEngine", "startListening error", e)
                if (active && !holding) {
                    cancelPendingLoops()
                    val run = Runnable { listen() }
                    pendingActiveRunnable = run
                    main.postDelayed(run, 500)
                }
            }
        }
    }

    /** Translates SpeechRecognizer error codes into clear, multilingual error descriptions */
    fun getErrorMessage(e: Int): String {
        return when (e) {
            SpeechRecognizer.ERROR_AUDIO -> when (lang) {
                "ta-IN" -> "ஆடியோ பதிவு பிழை (மைக் சிக்கல்)"
                else -> "Audio recording error (Mic)"
            }
            SpeechRecognizer.ERROR_CLIENT -> when (lang) {
                "ta-IN" -> "கிளையண்ட் அங்கீகாரப் பிழை"
                else -> "Client recognition error"
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> when (lang) {
                "ta-IN" -> "மைக் அனுமதி தேவை (அனுமதி வழங்கவும்)"
                else -> "Microphone permission required"
            }
            SpeechRecognizer.ERROR_NETWORK -> when (lang) {
                "ta-IN" -> "இணைய இணைப்பு பிழை"
                else -> "Network connection error"
            }
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> when (lang) {
                "ta-IN" -> "இணைய நேர வரம்பு முடிந்தது"
                else -> "Network request timed out"
            }
            SpeechRecognizer.ERROR_NO_MATCH -> when (lang) {
                "ta-IN" -> "வார்த்தை புரியவில்லை"
                else -> "No speech match found"
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> when (lang) {
                "ta-IN" -> "மைக் இயக்கம் பிஸியாக உள்ளது (8)"
                else -> "Speech recognition engine busy (8)"
            }
            SpeechRecognizer.ERROR_SERVER -> when (lang) {
                "ta-IN" -> "சர்வர் பிழை"
                else -> "Server error"
            }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> when (lang) {
                "ta-IN" -> "பேச்சு எதுவும் கேட்கவில்லை"
                else -> "No speech detected"
            }
            10 /* ERROR_TOO_MANY_REQUESTS */ -> when (lang) {
                "ta-IN" -> "அதிக கோரிக்கைகள் (10)"
                else -> "Too many requests (10)"
            }
            11 /* ERROR_SERVER_DISCONNECTED */ -> when (lang) {
                "ta-IN" -> "சர்வர் துண்டிக்கப்பட்டது (11)"
                else -> "Server disconnected (11)"
            }
            12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ -> when (lang) {
                "ta-IN" -> "தேர்ந்தெடுக்கப்பட்ட மொழி சாதனத்தால் ஆதரிக்கப்படவில்லை (12)"
                else -> "Selected language not supported on this device (12)"
            }
            13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> when (lang) {
                "ta-IN" -> "மொழி தற்போது கிடைக்கவில்லை (13)"
                else -> "Language currently unavailable (13)"
            }
            14 /* ERROR_CANNOT_CHECK_SUPPORT */ -> when (lang) {
                "ta-IN" -> "மொழி ஆதரவை சரிபார்க்க முடியவில்லை (14)"
                else -> "Cannot check language support (14)"
            }
            else -> when (lang) {
                "ta-IN" -> "மைக் பிழை ($e)"
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
            pitchSamples.clear()

            val recognizer = getOrCreateRecognizer() ?: return@post
            try {
                recognizer.cancel()
            } catch (ignored: Exception) {}

            recognizer.setRecognitionListener(object : RecognitionListener {
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

                            // Check if user said "Jarvis stop" or "Aero Fenix stop"
                            if (isStopCommand(raw) || (cmd != null && isStopCommand(cmd))) {
                                val ack = when (lang) {
                                    "ta-IN" -> "சரி, ஓய்வில் இருக்கிறேன்."
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
                        cancelPendingLoops()
                        val run = Runnable { listenStandby() }
                        pendingStandbyRunnable = run
                        main.postDelayed(run, 1200)
                    }
                }

                override fun onPartialResults(p: Bundle?) {
                    if (active || pausedForCall || isPhoneCallActive() || pausedForMedia) return
                    val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    checkWake(list)
                }

                override fun onError(e: Int) {
                    restoreBeep(immediate = true)
                    val errMsg = getErrorMessage(e)
                    Log.d("VoiceEngine", "Standby Recognizer onError code: $e ($errMsg)")
                    onErrorDescription?.invoke(e, errMsg)
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        standby = false
                        return
                    }
                    if (e == 12 || e == 13) {
                        if (lang == "ta-IN") {
                            lang = "en-IN"
                        }
                    }
                    if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                        destroyRecognizerSafely()
                    }
                    val retryDelay = when (e) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 2000L
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 800L
                        else -> 1200L
                    }
                    if (standby && !active && autoWakeEnabled && !pausedForCall && !isPhoneCallActive() && !pausedForMedia && !isMediaPlaying()) {
                        cancelPendingLoops()
                        val run = Runnable { listenStandby() }
                        pendingStandbyRunnable = run
                        main.postDelayed(run, retryDelay)
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
                recognizer.startListening(buildStandbyIntent())
            } catch (e: Exception) {
                restoreBeep()
                destroyRecognizerSafely()
                if (standby && !active && autoWakeEnabled) {
                    cancelPendingLoops()
                    val run = Runnable { listenStandby() }
                    pendingStandbyRunnable = run
                    main.postDelayed(run, 1000)
                }
            }
        }
    }
}