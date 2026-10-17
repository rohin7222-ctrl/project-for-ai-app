package com.nextgen.agent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.WindowManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.nextgen.agent.shortcut.ShortcutManager
import com.nextgen.agent.shortcut.VoiceShortcut
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { LANG, NAME, BRAIN, MAIN }
data class Msg(
    val mine: Boolean,
    val text: String,
    val time: String = "",
    val actions: List<String> = emptyList(),
    val isError: Boolean = false,
    val rawMarkdown: String = "",
    val spokenSummary: String = "",
    val isAiResponse: Boolean = false
)

internal val Indigo = Color(0xFF6366F1)
internal val Violet = Color(0xFF8B5CF6)
internal val Cyan = Color(0xFF06B6D4)
internal val Amber = Color(0xFFF59E0B)
internal val Magenta = Color(0xFFD946EF)

internal val GeminiBlue = Color(0xFF4285F4)
internal val GeminiPurple = Color(0xFF9B51E0)
internal val GeminiCyan = Color(0xFF24C8F0)
internal val GeminiPink = Color(0xFFFF6D00)
internal val GeminiGradient = Brush.linearGradient(listOf(GeminiBlue, GeminiPurple, GeminiCyan))
internal val GeminiAuraGradient = Brush.horizontalGradient(listOf(GeminiBlue, GeminiPurple, GeminiCyan, GeminiPink))
internal val PROVIDERS = listOf(
    "gemini" to "Google Gemini", "groq" to "Groq", "openai" to "OpenAI", "anthropic" to "Claude",
    "openrouter" to "OpenRouter", "mistral" to "Mistral", "deepseek" to "DeepSeek"
)

internal val DEFAULT_MODELS_MAP = mapOf(
    "gemini" to listOf(
        "gemini-2.0-flash" to "Gemini 2.0 Flash (Recommended)",
        "gemini-1.5-flash" to "Gemini 1.5 Flash",
        "gemini-1.5-pro" to "Gemini 1.5 Pro",
        "gemini-2.0-flash-lite" to "Gemini 2.0 Flash Lite"
    ),
    "groq" to listOf(
        "llama-3.3-70b-versatile" to "Llama 3.3 70B Versatile",
        "deepseek-r1-distill-llama-70b" to "DeepSeek R1 Distill 70B",
        "llama-3.1-8b-instant" to "Llama 3.1 8B Instant",
        "mixtral-8x7b-32768" to "Mixtral 8x7B"
    ),
    "openai" to listOf(
        "gpt-4o-mini" to "GPT-4o Mini (Fast & Smart)",
        "gpt-4o" to "GPT-4o",
        "o3-mini" to "o3-mini (High Reasoning)",
        "gpt-3.5-turbo" to "GPT-3.5 Turbo"
    ),
    "anthropic" to listOf(
        "claude-3-5-sonnet-20241022" to "Claude 3.5 Sonnet",
        "claude-3-5-haiku-20241022" to "Claude 3.5 Haiku"
    ),
    "deepseek" to listOf(
        "deepseek-chat" to "DeepSeek V3 (Chat)",
        "deepseek-reasoner" to "DeepSeek R1 (Reasoner)"
    ),
    "mistral" to listOf(
        "mistral-small-latest" to "Mistral Small",
        "mistral-large-latest" to "Mistral Large",
        "codestral-latest" to "Codestral"
    ),
    "openrouter" to listOf(
        "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B",
        "google/gemini-2.0-flash-exp:free" to "Gemini 2.0 Flash (Free)",
        "deepseek/deepseek-chat" to "DeepSeek V3"
    )
)

class MainActivity : FragmentActivity() {
    companion object {
        private const val KEY_UNLOCKED = "agent_unlocked_state"
        private var sessionUnlocked = false
    }

    internal lateinit var store: Store
    internal lateinit var voice: Voice
    internal lateinit var device: DeviceController
    internal lateinit var shortcutManager: ShortcutManager

    internal var cfg by mutableStateOf(Config())
    internal var screen by mutableStateOf(Screen.LANG)
    internal var changeModel by mutableStateOf(false)   // true when the model screen was opened from the settings menu
    internal var listening by mutableStateOf(false)
    internal var thinking by mutableStateOf(false)
    internal var unlocked by mutableStateOf(true)
    internal var executingAction by mutableStateOf("")
    internal var liveSpeechText by mutableStateOf("")
    internal var pending by mutableStateOf<List<JSONObject>>(emptyList())
    internal val chat = mutableStateListOf<Msg>()

    internal fun getAllRequiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    internal val perms get() = getAllRequiredPermissions()
    internal val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.RECORD_AUDIO)) startVoice()
    }

    private var wasActiveBeforeCall = false
    private var wasStandbyBeforeCall = false
    internal var isInPhoneCall = false
    private var telephonyCallback: Any? = null
    private var phoneStateReceiver: BroadcastReceiver? = null

    private var wasActiveBeforeMedia = false
    private var wasStandbyBeforeMedia = false
    internal var isMediaPlayingState by mutableStateOf(false)
    private var audioPlaybackCallback: Any? = null
    private var mediaMonitorJob: Job? = null
    private var mediaStopHandler: Handler? = null
    private var mediaStopRunnable: Runnable? = null

    internal fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    internal fun tr(k: String) = S.t(cfg.lang, k)
    internal fun upd(b: (Config) -> Config) {
        cfg = b(cfg)
        store.save(cfg)
        syncVoiceConfig()
    }

    internal fun syncVoiceConfig() {
        voice.lang = cfg.lang
        voice.female = cfg.female
        voice.commandingWord = cfg.commandingWord
        voice.commandingStopWord = cfg.commandingStopWord
        voice.autoWakeEnabled = cfg.autoWakeEnabled
        voice.voiceMatchEnabled = cfg.voiceMatchEnabled
        voice.voiceProfile = VoiceProfile(cfg.voicePitchMean, cfg.voicePitchStd, cfg.voiceEnrolled)
    }

    internal fun nowTime(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    internal fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("AI Text", text))
    }

    internal fun isSystemDark(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.install(this)

        // Lockscreen interaction & waking (item 13)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        store = Store(this)
        device = DeviceController(this)
        shortcutManager = ShortcutManager(this)
        cfg = store.load(isSystemDark())
        voice = Voice(this, onText = { 
            liveSpeechText = ""
            handleUser(it) 
        }, onState = { 
            listening = it 
            if (!it) liveSpeechText = ""
        })
        voice.onPartialText = { liveSpeechText = it }
        syncVoiceConfig()
        voice.onWakeWordTriggered = { cmd ->
            if (!cmd.isNullOrBlank()) {
                handleUser(cmd)
            } else {
                val ack = when (cfg.lang) {
                    "ta-IN" -> "சொல்லுங்க"
                    "ja-JP" -> "はい、聞いています"
                    else -> "Yes, I'm listening"
                }
                voice.speak(ack)
            }
        }
        voice.onStopCommandTriggered = {
            stopVoiceWithAck()
        }
        voice.onEmergencyUninstallTriggered = {
            triggerEmergencyUninstall()
        }
        voice.onVoiceRejected = {
            Toast.makeText(this@MainActivity, tr("voice_rejected"), Toast.LENGTH_SHORT).show()
        }
        setupCallStateMonitoring()
        setupMediaPlaybackMonitoring()
        // Attempt automatic accessibility configuration silently
        AgentAccessibilityService.autoEnable(this)
        MouseButtonManager.getInstance(this).syncWithConfig(cfg)

        val wasUnlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED, false) == true || sessionUnlocked
        if (store.isReady()) {
            screen = Screen.MAIN
            if (wasUnlocked) {
                unlocked = true
                sessionUnlocked = true
            } else {
                unlocked = false
                authenticate()
            }
            if (cfg.autoWakeEnabled) {
                VoiceForegroundService.start(this)
            }
        }
        setContent { App() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            teardownMediaPlaybackMonitoring()
            teardownCallStateMonitoring()
            voice.shutdown()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ security: biometric / PIN lock
    private fun authenticate() {
        val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true
            sessionUnlocked = true
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(cfg.name.ifBlank { "AI" }).setAllowedAuthenticators(auth).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked = true
                sessionUnlocked = true
            }
        }).authenticate(info)
    }

    internal fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (ignored: Exception) {}
                }
            } else {
                Toast.makeText(this, "Battery optimization already ignored! (பின்னணி இயக்கம் பாதுகாக்கப்பட்டது)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------ assistant logic
    internal fun toggle() {
        if (voice.active) {
            voice.stop()
            return
        }
        val missing = getAllRequiredPermissions().filter { !has(it) }
        if (missing.isEmpty()) startVoice() else permLauncher.launch(missing.toTypedArray())
    }

    internal fun startVoice() {
        syncVoiceConfig()
        voice.start()
    }

    internal fun stopVoiceWithAck() {
        val ack = when (cfg.lang) {
            "ta-IN" -> "சரி, நிறுத்துகிறேன்."
            "ja-JP" -> "わかりました。停止します。"
            else -> "Stopping assistant."
        }
        chat.add(Msg(mine = false, text = ack, time = nowTime()))
        voice.speak(ack)
        voice.stop()
    }

    internal fun triggerEmergencyUninstall() {
        val warn = tr("emergency_uninstall_desc")
        chat.add(Msg(mine = true, text = "code dark shadow", time = nowTime()))
        chat.add(Msg(mine = false, text = "🛡️ $warn", time = nowTime(), isError = true))
        voice.stopRecognition()
        voice.speak(warn)
        unlocked = false
        sessionUnlocked = false
        store.logout()
        shortcutManager.executeSelfUninstall()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            finishAffinity()
        }, 3000)
    }

    internal var isTorchActive by mutableStateOf(false)

    internal fun toggleTorch() {
        val next = !isTorchActive
        lifecycleScope.launch(Dispatchers.IO) {
            val res = device.execute(JSONObject().put("type", "flashlight").put("on", next))
            withContext(Dispatchers.Main) {
                if (res == "OK") {
                    isTorchActive = next
                    Toast.makeText(this@MainActivity, if (next) "🔦 Torch ON" else "🔦 Torch OFF", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun toggleBluetooth() {
        lifecycleScope.launch(Dispatchers.IO) {
            val adapter = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter() } catch (e: Exception) { null }
            val isCurrentlyOn = adapter?.isEnabled == true
            val next = !isCurrentlyOn
            val res = device.execute(JSONObject().put("type", "bluetooth").put("on", next))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, if (next) "🎧 Bluetooth ON" else "🎧 Bluetooth OFF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun quickCall() {
        val prompt = if (cfg.lang == "ta-IN") "அப்பாவுக்கு கால் பண்ணு" else "Call Contact"
        handleUser(prompt)
    }

    internal fun quickAlarm() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.execute(JSONObject().put("type", "alarm").put("hour", 7).put("minute", 0).put("label", "Morning Alarm"))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "⏰ Alarm set for 7:00 AM", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun quickWhatsApp() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = device.execute(JSONObject().put("type", "open_app").put("name", "whatsapp"))
            if (res.startsWith("ERR")) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun quickWifi() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.execute(JSONObject().put("type", "wifi").put("on", true))
        }
    }

    internal fun quickScreenshot() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = device.execute(JSONObject().put("type", "system").put("name", "screenshot"))
            withContext(Dispatchers.Main) {
                if (res.startsWith("ERR") || res == "NEED_ACCESSIBILITY") {
                    Toast.makeText(this@MainActivity, tr("need_a11y"), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "📸 Screenshot taken", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun quickVolume() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.execute(JSONObject().put("type", "volume").put("dir", "toggle"))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "🔊 Volume toggled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun quickYouTube() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.execute(JSONObject().put("type", "youtube").put("query", "trending songs"))
        }
    }

    internal fun quickHotspot() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.execute(JSONObject().put("type", "hotspot").put("on", true))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "📶 Hotspot settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun quickInstagram() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = device.execute(JSONObject().put("type", "instagram"))
            if (res.startsWith("ERR")) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, res, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun quickGoogleSearch() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.execute(JSONObject().put("type", "google_search").put("query", "trending news"))
        }
    }

    internal fun quickReels() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.openInstagramReels()
        }
    }

    internal fun quickMusic() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.playMusic("trending songs", "spotify")
        }
    }

    internal fun quickCamera() {
        lifecycleScope.launch(Dispatchers.IO) {
            device.openApp("camera")
        }
    }

    internal fun quickScreenRead() {
        val prompt = if (cfg.lang == "ta-IN") "திரையை படி" else "Read Screen"
        handleUser(prompt)
    }

    internal fun cycleLanguage() {
        val langs = listOf("ta-IN", "en-US", "ja-JP")
        val currentIndex = langs.indexOf(cfg.lang).coerceAtLeast(0)
        val nextLang = langs[(currentIndex + 1) % langs.size]
        upd { it.copy(lang = nextLang) }
        val langName = when (nextLang) {
            "ta-IN" -> "தமிழ் (Tamil)"
            "ja-JP" -> "日本語 (Japanese)"
            else -> "English (US)"
        }
        Toast.makeText(this, "Language: $langName", Toast.LENGTH_SHORT).show()
    }

    var currentlySpeakingText by mutableStateOf<String?>(null)

    fun speakText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (currentlySpeakingText == clean) {
            voice.stopSpeakingOnly()
            currentlySpeakingText = null
        } else {
            currentlySpeakingText = clean
            voice.speak(clean)
        }
    }

    fun stopSpeaking() {
        voice.stopSpeakingOnly()
        currentlySpeakingText = null
    }

    fun shareText(text: String) {
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share AI Response")
            startActivity(shareIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun quickReadNotifications() {
        val prompt = if (cfg.lang == "ta-IN") "வந்த செய்திகளைப் படி" else "Read notifications"
        handleUser(prompt)
    }

    internal fun quickReadSms() {
        val prompt = if (cfg.lang == "ta-IN") "சமீபத்திய SMS படி" else "Read recent SMS"
        handleUser(prompt)
    }

    internal fun quickMobileData() {
        val prompt = if (cfg.lang == "ta-IN") "மொபைல் டேட்டா ஆன்" else "Turn on mobile data"
        handleUser(prompt)
    }

    internal fun handleUser(text: String) {
        val t = text.trim()
        if (t.isBlank()) return

        // 1. Safety Emergency Command Check: "code dark shadow"
        if (shortcutManager.isEmergencyUninstall(t)) {
            triggerEmergencyUninstall()
            return
        }

        // 2. Stop assistant command check
        if (voice.isStopCommand(t)) {
            chat.add(Msg(mine = true, text = t, time = nowTime()))
            stopVoiceWithAck()
            return
        }

        // 3. Custom Voice Shortcut resolution
        val resolvedAction = shortcutManager.resolve(t)
        val effectiveText = if (resolvedAction != null) {
            chat.add(Msg(mine = true, text = "$t  ➔  $resolvedAction", time = nowTime()))
            resolvedAction
        } else {
            chat.add(Msg(mine = true, text = t, time = nowTime()))
            t
        }

        thinking = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Brain.chat(cfg, effectiveText) }
            thinking = false
            if (r.ok) {
                val actionDescs = r.actions.mapNotNull { it.optString("desc").takeIf { s -> s.isNotBlank() } }
                val displayText = r.content.ifBlank { r.speech }
                val isAi = r.actions.isEmpty()
                chat.add(
                    Msg(
                        mine = false,
                        text = displayText,
                        time = nowTime(),
                        actions = actionDescs,
                        rawMarkdown = r.content,
                        spokenSummary = r.speech,
                        isAiResponse = isAi
                    )
                )
                voice.speak(r.speech)
                delay(1200)                       // let the voice reply start before apps open
                runList(r.actions)                // Run all actions directly without blocking modal!
            } else {
                val m = tr("error")
                chat.add(Msg(mine = false, text = "$m\n${r.error}", time = nowTime(), isError = true))
                voice.speak(m)
            }
        }
    }

    private suspend fun runList(list: List<JSONObject>) {
        try {
            for (a in list) {
                val type = a.optString("type")
                if (type == "stop_assistant") {
                    stopVoiceWithAck()
                    return
                }

                // Sensitive action check (Item 14): Confirmation required for money transfers, delete, bulk SMS
                if (DeviceController.isSensitive(a)) {
                    pending = listOf(a)
                    val confirmPrompt = tr("confirm_sensitive_action")
                    chat.add(Msg(mine = false, text = "⚠️ $confirmPrompt", time = nowTime()))
                    voice.speak(confirmPrompt)
                    return
                }

                if (type == "call") {
                    isInPhoneCall = true
                    wasActiveBeforeCall = voice.active
                    wasStandbyBeforeCall = voice.standby
                    voice.pauseForCall()
                }
                val d = a.optString("desc")
                if (d.isNotBlank()) {
                    executingAction = d
                    AgentAccessibilityService.instance?.showFloatingHud(d)
                }
                if (type.startsWith("ui_") || type == "click" || type == "tap" || type == "type" || type == "scroll" || type == "swipe") {
                    AgentAccessibilityService.instance?.uiDismissPopup()
                }
                val r = withContext(Dispatchers.IO) { device.execute(a) }   // ui_* actions block while they wait for the screen
                if (type == "open_app" || type == "chat" || type == "music_play") delay(2500)          // let the app finish opening before the next step
                else if (type == "call") delay(1000)
                else if (type.startsWith("ui_") || type == "click" || type == "tap" || type == "type" || type == "scroll" || type == "swipe") delay(600)
                executingAction = ""
                if (type == "ui_read_screen" && r.startsWith("{")) {
                    try {
                        val sObj = JSONObject(r)
                        val pkg = sObj.optString("package", "App")
                        val textsArr = sObj.optJSONArray("texts")
                        val summaryList = mutableListOf<String>()
                        if (textsArr != null) {
                            for (ti in 0 until minOf(textsArr.length(), 4)) {
                                val t = textsArr.optString(ti)
                                if (t.isNotBlank()) summaryList.add(t)
                            }
                        }
                        val spoken = if (summaryList.isNotEmpty()) {
                            "Screen ($pkg): " + summaryList.joinToString(", ")
                        } else {
                            "Active app: $pkg"
                        }
                        chat.add(Msg(mine = false, text = "📱 $spoken", time = nowTime()))
                        voice.speak(spoken)
                    } catch (ignored: Exception) {}
                }
                when {
                    r == "NEED_ACCESSIBILITY" -> {
                        chat.add(Msg(mine = false, text = tr("need_a11y"), time = nowTime(), isError = true))
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    r.startsWith("SPEECH:") -> {
                        val speechText = r.substring(7)
                        chat.add(Msg(mine = false, text = speechText, time = nowTime()))
                        voice.speak(speechText)
                    }
                    r.startsWith("ERR:NOT_FOUND_RETRY") -> {
                        val msg = tr("action_failed_retry")
                        chat.add(Msg(mine = false, text = "⚠ $msg", time = nowTime(), isError = true))
                        voice.speak(msg)
                    }
                    r.startsWith("ERR:NEED_NOTIFICATION_LISTENER") -> {
                        val msg = tr("notif_perm_btn")
                        chat.add(Msg(mine = false, text = "⚠ $msg", time = nowTime(), isError = true))
                        AgentNotificationListenerService.openSettings(this@MainActivity)
                    }
                    r.startsWith("ERR") -> {
                        val errMsg = r.removePrefix("ERR:")
                        chat.add(Msg(mine = false, text = "⚠ $errMsg", time = nowTime(), isError = true))
                        voice.speak(errMsg)
                    }
                }
            }
        } finally {
            executingAction = ""
            delay(1000)
            AgentAccessibilityService.instance?.hideFloatingHud()
        }
    }

    internal fun answerIncomingCall() {
        lifecycleScope.launch(Dispatchers.Main) {
            EarToCallManager.getInstance(this@MainActivity).executeAnswerCall()
        }
    }

    private fun setupCallStateMonitoring() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onPhoneCallStateChanged(state)
                }
            }
            try {
                tm.registerTelephonyCallback(mainExecutor, cb)
                telephonyCallback = cb
            } catch (ignored: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    onPhoneCallStateChanged(state)
                }
            }
            try {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = listener
            } catch (ignored: Exception) {}
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val state = when (stateStr) {
                        TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                        TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                        else -> -1
                    }
                    if (state >= 0) onPhoneCallStateChanged(state)
                }
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        try {
            registerReceiver(receiver, filter)
            phoneStateReceiver = receiver
        } catch (ignored: Exception) {}
    }

    private fun teardownCallStateMonitoring() {
        try {
            phoneStateReceiver?.let { unregisterReceiver(it) }
            phoneStateReceiver = null
        } catch (ignored: Exception) {}
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            (telephonyCallback as? PhoneStateListener)?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        telephonyCallback = null
    }

    private fun onPhoneCallStateChanged(state: Int) {
        EarToCallManager.getInstance(this).onCallStateChanged(state, this)
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!isInPhoneCall) {
                    isInPhoneCall = true
                    wasActiveBeforeCall = voice.active
                    wasStandbyBeforeCall = voice.standby
                    voice.pauseForCall()
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!isInPhoneCall) {
                    isInPhoneCall = true
                    wasActiveBeforeCall = voice.active
                    wasStandbyBeforeCall = voice.standby
                    voice.pauseForCall()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isInPhoneCall) {
                    isInPhoneCall = false
                    val wasActive = wasActiveBeforeCall
                    val wasStandby = wasStandbyBeforeCall
                    wasActiveBeforeCall = false
                    wasStandbyBeforeCall = false
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!isInPhoneCall) {
                            voice.resumeAfterCall(wasActive, wasStandby)
                        }
                    }, 350)
                }
            }
        }
    }

    private fun setupMediaPlaybackMonitoring() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    super.onPlaybackConfigChanged(configs)
                    checkMediaState()
                }
            }
            try {
                am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
                audioPlaybackCallback = cb
            } catch (ignored: Exception) {}
        }

        // Active background monitor: polls every 500ms to detect media play/pause across all apps
        mediaMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                checkMediaState()
            }
        }
    }

    private fun teardownMediaPlaybackMonitoring() {
        mediaMonitorJob?.cancel()
        mediaMonitorJob = null
        mediaStopRunnable?.let { mediaStopHandler?.removeCallbacks(it) }
        mediaStopRunnable = null
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (audioPlaybackCallback as? AudioManager.AudioPlaybackCallback)?.let {
                am?.unregisterAudioPlaybackCallback(it)
            }
        }
        audioPlaybackCallback = null
    }

    private fun checkMediaState() {
        if (isInPhoneCall) return
        if (voice.holding) return // Assistant's own speech/TTS should not pause the assistant

        val playing = voice.isMediaPlaying()
        if (playing) {
            mediaStopRunnable?.let { mediaStopHandler?.removeCallbacks(it) }
            mediaStopRunnable = null
            if (!isMediaPlayingState) {
                isMediaPlayingState = true
                wasActiveBeforeMedia = voice.active
                wasStandbyBeforeMedia = voice.standby
                voice.pauseForMedia()
            }
        } else {
            if (isMediaPlayingState && mediaStopRunnable == null) {
                if (mediaStopHandler == null) mediaStopHandler = Handler(Looper.getMainLooper())
                val r = Runnable {
                    mediaStopRunnable = null
                    if (!voice.isMediaPlaying() && isMediaPlayingState && !isInPhoneCall) {
                        isMediaPlayingState = false
                        voice.resumeAfterMedia(wasActiveBeforeMedia, wasStandbyBeforeMedia)
                        wasActiveBeforeMedia = false
                        wasStandbyBeforeMedia = false
                    }
                }
                mediaStopRunnable = r
                mediaStopHandler?.postDelayed(r, 300)
            }
        }
    }


    private fun describe(a: JSONObject): String = when (a.optString("type")) {
        "call" -> tr("c_call").format(a.optString("target"))
        "sms" -> tr("c_sms").format(a.optString("target"), a.optString("text"))
        else -> tr("c_end")
    }

    internal fun logout() {
        voice.stopStandby()
        voice.stop()
        store.logout()
        Brain.reset()
        chat.clear()
        cfg = store.load(isSystemDark())
        syncVoiceConfig()
        unlocked = false
        sessionUnlocked = false
        screen = Screen.LANG
    }

    // ------------------------------------------------------------------ UI
    @Composable
    private fun App() {
        val systemDark = isSystemInDarkTheme()
        val isDark = if (cfg.darkCustom) cfg.dark else systemDark
        val scheme = if (isDark)
            darkColorScheme(primary = Indigo, secondary = Violet, background = Color(0xFF0E0E12),
                surface = Color(0xFF1A1A21), surfaceVariant = Color(0xFF26262F),
                onBackground = Color(0xFFEDEDF2), onSurface = Color(0xFFEDEDF2))
        else
            lightColorScheme(primary = Indigo, secondary = Violet, background = Color(0xFFF3F4FA),
                surface = Color.White, surfaceVariant = Color(0xFFE6E8F6))
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f))))) {
                if (!unlocked) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = Indigo)
                        Button(onClick = { authenticate() }) { Text(tr("unlock")) }
                    }
                } else {
                    AnimatedContent(targetState = screen, label = "nav", transitionSpec = {
                        (fadeIn(tween(350)) + slideInHorizontally(tween(350)) { it / 8 }) togetherWith
                            (fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 8 })
                    }) { s ->
                        when (s) {
                            Screen.LANG -> LangScreen()
                            Screen.NAME -> NameScreen()
                            Screen.BRAIN -> BrainScreen()
                            Screen.MAIN -> MainScreen()
                        }
                    }
                }
                if (pending.isNotEmpty()) AlertDialog(
                    onDismissRequest = { pending = emptyList() },
                    title = { Text(tr("confirm_title")) },
                    text = { Column { pending.forEach { Text("• " + describe(it)) } } },
                    confirmButton = {
                        TextButton(onClick = { val l = pending; pending = emptyList(); lifecycleScope.launch { runList(l) } }) { Text(tr("allow")) }
                    },
                    dismissButton = { TextButton(onClick = { pending = emptyList() }) { Text(tr("deny")) } }
                )
            }
        }
    }

    @Composable
    private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
        Surface(
            shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp, shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }

    @Composable
    private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    internal fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun BackButton(onClick: () -> Unit) {
        Box(Modifier.padding(20.dp)) { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, onClick) }
    }

    // ---- Step 0: language
    @Composable
    private fun LangScreen() {
        BackHandler(enabled = store.isReady()) {
            screen = Screen.MAIN
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassCard {
                Icon(Icons.Default.Language, null, Modifier.size(48.dp), tint = Indigo)
                Text("Choose Language", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("மொழியைத் தேர்ந்தெடுக்கவும் · 言語を選択", fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                listOf(Triple("ta-IN", "தமிழ்", "Tamil"), Triple("en-US", "English", "US English"),
                    Triple("ja-JP", "日本語", "Japanese")).forEach { (code, native, en) ->
                    val sel = cfg.lang == code
                    val shape = RoundedCornerShape(20.dp)
                    Row(Modifier.fillMaxWidth().clip(shape)
                        .background(if (sel) Indigo.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                        .border(2.dp, if (sel) Indigo else Color.Transparent, shape)
                        .clickable { upd { it.copy(lang = code) }; voice.lang = code }
                        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(native, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(en, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        }
                        if (sel) Icon(Icons.Default.CheckCircle, null, tint = Indigo)
                    }
                }
                PrimaryButton(tr("continue")) {
                    if (store.isReady()) screen = Screen.MAIN else screen = Screen.NAME
                }
            }
        }
    }

    // ---- Step 1: name
    @Composable
    private fun NameScreen() {
        var n by remember { mutableStateOf(cfg.name) }
        BackHandler {
            if (store.isReady()) screen = Screen.MAIN else screen = Screen.LANG
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopStart)) { 
                BackButton { 
                    if (store.isReady()) screen = Screen.MAIN else screen = Screen.LANG 
                } 
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassCard {
                    val logoPulse by rememberInfiniteTransition(label = "nameLogoPulse").animateFloat(
                        initialValue = 1f,
                        targetValue = 1.05f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )
                    Surface(
                        modifier = Modifier
                            .size(92.dp)
                            .scale(logoPulse),
                        shape = CircleShape,
                        shadowElevation = 16.dp,
                        border = BorderStroke(2.5.dp, Brush.sweepGradient(listOf(Cyan, Indigo, Violet, Magenta, Cyan))),
                        color = Color.Black
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "Aero Fenix Logo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(1.22f)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Text(tr("name_title"), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Text(tr("name_sub"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    OutlinedTextField(
                        value = n, onValueChange = { 
                            n = it.take(20)
                            if (shortcutManager.isEmergencyUninstall(it)) {
                                n = ""
                                triggerEmergencyUninstall()
                            }
                        }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                        placeholder = { Text(tr("name_hint"), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    )
                    PrimaryButton(tr("continue"), enabled = n.isNotBlank()) {
                        upd { it.copy(name = n.trim()) }
                        if (store.isReady()) screen = Screen.MAIN else screen = Screen.BRAIN
                    }
                }
            }
        }
    }

    // ---- Step 2: brain (API or local LLM)
    @Composable
    private fun BrainScreen() {
        var type by remember { mutableStateOf(cfg.type) }
        var provider by remember { mutableStateOf(cfg.provider) }
        var key by remember { mutableStateOf(cfg.key) }          // kept when changing model from settings (empty after logout)
        var model by remember { mutableStateOf(if (cfg.type == "api") cfg.model else "") }
        var url by remember { mutableStateOf(cfg.url) }
        var lmodel by remember { mutableStateOf(if (cfg.type == "local") cfg.model else "") }
        var models by remember { mutableStateOf(DEFAULT_MODELS_MAP[provider] ?: emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        var isCustom by remember { mutableStateOf(false) }
        var customModelInput by remember { mutableStateOf("") }
        var tick by remember { mutableStateOf(0) }

        // When provider changes, immediately populate default standard models
        LaunchedEffect(provider) {
            val defs = DEFAULT_MODELS_MAP[provider] ?: emptyList()
            models = defs
            if (model.isEmpty() || defs.none { it.first == model }) {
                model = if (cfg.provider == provider && cfg.model.isNotEmpty()) cfg.model
                        else defs.firstOrNull()?.first ?: ""
            }
            err = ""
        }

        // Live model list from provider
        LaunchedEffect(provider, key, tick) {
            if (key.trim().length >= 8) {
                if (tick == 0) delay(700)
                loading = true
                try {
                    withContext(Dispatchers.IO) { Brain.models(provider, key.trim()) }
                        .onSuccess { res ->
                            if (res.models.isNotEmpty()) {
                                models = res.models
                                if (model.isEmpty() || models.none { it.first == model }) {
                                    model = models.firstOrNull()?.first ?: ""
                                }
                            }
                            if (res.isFallback && res.error.isNotEmpty()) {
                                err = if (res.error == "OFFLINE") tr("net_offline_warning")
                                      else if (res.error == "INVALID_KEY") tr("api_key_invalid")
                                      else res.error
                            } else {
                                err = ""
                            }
                        }
                        .onFailure {
                            val msg = it.message ?: ""
                            err = if (msg.contains("OFFLINE", ignoreCase = true) ||
                                      msg.contains("hostname", ignoreCase = true) ||
                                      msg.contains("network", ignoreCase = true) ||
                                      msg.contains("errno 7", ignoreCase = true)) {
                                tr("net_offline_warning")
                            } else if (msg.contains("400") || msg.contains("401") || msg.contains("403")) {
                                tr("api_key_invalid")
                            } else {
                                msg.ifEmpty { "Error fetching models" }
                            }
                            if (models.isEmpty()) {
                                models = DEFAULT_MODELS_MAP[provider] ?: emptyList()
                            }
                            if (model.isEmpty()) {
                                model = models.firstOrNull()?.first ?: ""
                            }
                        }
                } finally { loading = false }
            }
        }

        BackHandler {
            if (store.isReady()) {
                changeModel = false
                screen = Screen.MAIN
            } else {
                screen = Screen.NAME
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackButton {
                    if (store.isReady()) {
                        changeModel = false
                        screen = Screen.MAIN
                    } else {
                        screen = Screen.NAME
                    }
                }
                GlassCard {
                    Text(tr("brain_title"), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(tr("brain_sub"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
                        listOf("local" to tr("local"), "api" to tr("cloud")).forEach { (t, label) ->
                            val sel = type == t
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                                .clickable { type = t }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontWeight = FontWeight.Bold, color = if (sel) Indigo else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                            }
                        }
                    }

                    if (type == "api") {
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PROVIDERS) { (id, label) ->
                                FilterChip(selected = provider == id, onClick = { provider = id }, label = { Text(label) })
                            }
                        }
                        OutlinedTextField(value = key, onValueChange = { 
                            key = it 
                            if (shortcutManager.isEmergencyUninstall(it)) {
                                key = ""
                                triggerEmergencyUninstall()
                            }
                        }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            visualTransformation = PasswordVisualTransformation(), label = { Text(tr("api_key")) })
                        OutlinedButton(onClick = { tick++ }, enabled = key.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(tr("fetch"))
                        }
                        if (err.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFE53935).copy(alpha = 0.12f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(err, fontSize = 12.sp, color = Color(0xFFE53935), lineHeight = 16.sp)
                            }
                        }
                        Text(tr("models"), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                        if (loading && models.isEmpty()) CircularProgressIndicator()
                        else if (models.isEmpty()) Text(if (err.isNotEmpty()) err else tr("no_models"), fontSize = 13.sp,
                            color = if (err.isNotEmpty()) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        else Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            models.forEach { (id, name) ->
                                val sel = !isCustom && model == id
                                val shape = RoundedCornerShape(16.dp)
                                Row(Modifier.fillMaxWidth().clip(shape)
                                    .background(if (sel) Indigo.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                                    .border(2.dp, if (sel) Indigo else Color.Transparent, shape)
                                    .clickable {
                                        isCustom = false
                                        model = id
                                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontWeight = FontWeight.Bold)
                                        if (name != id) Text(id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                                    }
                                    if (sel) Icon(Icons.Default.Check, null, tint = Indigo)
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { isCustom = !isCustom }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isCustom, onCheckedChange = { isCustom = it })
                            Spacer(Modifier.width(4.dp))
                            Text(tr("use_custom_model"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        if (isCustom) {
                            OutlinedTextField(
                                value = customModelInput,
                                onValueChange = { customModelInput = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                placeholder = { Text(tr("custom_model_hint"), fontSize = 12.sp) }
                            )
                        }
                    } else {
                        OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), label = { Text(tr("local_url")) })
                        OutlinedTextField(value = lmodel, onValueChange = { lmodel = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), label = { Text(tr("model_name")) })
                    }

                    val chosenModel = if (isCustom) customModelInput.trim() else model
                    val ready = if (type == "api") (chosenModel.isNotEmpty() && key.isNotBlank()) else url.isNotBlank() && lmodel.isNotBlank()
                    PrimaryButton(tr("ok"), enabled = ready) {
                        upd {
                            it.copy(type = type, provider = provider, key = key.trim(),
                                model = if (type == "api") chosenModel else lmodel.trim(), url = url.trim())
                        }
                        store.setReady(true); Brain.reset(); changeModel = false; screen = Screen.MAIN
                        VoiceForegroundService.start(this@MainActivity)
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            upd {
                                it.copy(type = "local", provider = "rule_based", key = "", model = "offline", url = "")
                            }
                            store.setReady(true)
                            Brain.reset()
                            changeModel = false
                            screen = Screen.MAIN
                            VoiceForegroundService.start(this@MainActivity)
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Brush.horizontalGradient(listOf(Cyan, Indigo, Violet)))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = tr("continue_without_api"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tr("continue_without_api_sub"),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }

}
