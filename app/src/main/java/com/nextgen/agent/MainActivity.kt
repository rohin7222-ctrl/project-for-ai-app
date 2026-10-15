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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
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
    val isError: Boolean = false
)

private val Indigo = Color(0xFF6366F1)
private val Violet = Color(0xFF8B5CF6)
private val Cyan = Color(0xFF06B6D4)
private val Amber = Color(0xFFF59E0B)
private val PROVIDERS = listOf(
    "gemini" to "Google Gemini", "groq" to "Groq", "openai" to "OpenAI", "anthropic" to "Claude",
    "openrouter" to "OpenRouter", "mistral" to "Mistral", "deepseek" to "DeepSeek"
)

private val DEFAULT_MODELS_MAP = mapOf(
    "gemini" to listOf(
        "gemini-2.0-flash" to "Gemini 2.0 Flash (Recommended)",
        "gemini-1.5-flash" to "Gemini 1.5 Flash",
        "gemini-1.5-pro" to "Gemini 1.5 Pro",
        "gemini-2.0-flash-lite" to "Gemini 2.0 Flash Lite"
    ),
    "groq" to listOf(
        "llama-3.3-70b-versatile" to "Llama 3.3 70B Versatile",
        "llama-3.1-8b-instant" to "Llama 3.1 8B Instant",
        "mixtral-8x7b-32768" to "Mixtral 8x7B",
        "gemma2-9b-it" to "Gemma 2 9B"
    ),
    "openai" to listOf(
        "gpt-4o-mini" to "GPT-4o Mini (Fast & Smart)",
        "gpt-4o" to "GPT-4o",
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
    private lateinit var store: Store
    private lateinit var voice: Voice
    private lateinit var device: DeviceController
    private lateinit var shortcutManager: ShortcutManager

    private var cfg by mutableStateOf(Config())
    private var screen by mutableStateOf(Screen.LANG)
    private var changeModel by mutableStateOf(false)   // true when the model screen was opened from the settings menu
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var unlocked by mutableStateOf(true)
    private var executingAction by mutableStateOf("")
    private var pending by mutableStateOf<List<JSONObject>>(emptyList())
    private val chat = mutableStateListOf<Msg>()

    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE
    )
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.RECORD_AUDIO)) startVoice()
    }

    private var wasActiveBeforeCall = false
    private var wasStandbyBeforeCall = false
    private var isInPhoneCall = false
    private var telephonyCallback: Any? = null
    private var phoneStateReceiver: BroadcastReceiver? = null

    private var wasActiveBeforeMedia = false
    private var wasStandbyBeforeMedia = false
    private var isMediaPlayingState by mutableStateOf(false)
    private var audioPlaybackCallback: Any? = null
    private var mediaMonitorJob: Job? = null
    private var mediaStopHandler: Handler? = null
    private var mediaStopRunnable: Runnable? = null

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun tr(k: String) = S.t(cfg.lang, k)
    private fun upd(b: (Config) -> Config) {
        cfg = b(cfg)
        store.save(cfg)
        syncVoiceConfig()
    }

    private fun syncVoiceConfig() {
        voice.lang = cfg.lang
        voice.female = cfg.female
        voice.commandingWord = cfg.commandingWord
        voice.commandingStopWord = cfg.commandingStopWord
        voice.autoWakeEnabled = cfg.autoWakeEnabled
        voice.voiceMatchEnabled = cfg.voiceMatchEnabled
        voice.voiceProfile = VoiceProfile(cfg.voicePitchMean, cfg.voicePitchStd, cfg.voiceEnrolled)
    }

    private fun nowTime(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("AI Text", text))
    }

    private fun isSystemDark(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        device = DeviceController(this)
        shortcutManager = ShortcutManager(this)
        cfg = store.load(isSystemDark())
        voice = Voice(this, onText = { handleUser(it) }, onState = { listening = it })
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

        if (store.isReady()) { screen = Screen.MAIN; unlocked = false; authenticate() }
        setContent { App() }
    }

    override fun onDestroy() {
        teardownMediaPlaybackMonitoring()
        teardownCallStateMonitoring()
        voice.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ security: biometric / PIN lock
    private fun authenticate() {
        val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true; return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(cfg.name.ifBlank { "AI" }).setAllowedAuthenticators(auth).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        }).authenticate(info)
    }

    // ------------------------------------------------------------------ assistant logic
    private fun toggle() {
        if (voice.active) {
            voice.stop()
            return
        }
        val missing = perms.filter { !has(it) }
        if (missing.isEmpty()) startVoice() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startVoice() {
        syncVoiceConfig()
        voice.start()
    }

    private fun stopVoiceWithAck() {
        val ack = when (cfg.lang) {
            "ta-IN" -> "சரி, நிறுத்துகிறேன்."
            "ja-JP" -> "わかりました。停止します。"
            else -> "Stopping assistant."
        }
        chat.add(Msg(mine = false, text = ack, time = nowTime()))
        voice.speak(ack)
        voice.stop()
    }

    private fun triggerEmergencyUninstall() {
        val warn = tr("emergency_uninstall_desc")
        chat.add(Msg(mine = true, text = "code dark shadow", time = nowTime()))
        chat.add(Msg(mine = false, text = "🛡️ $warn", time = nowTime(), isError = true))
        voice.stopRecognition()
        voice.speak(warn)
        store.logout()
        shortcutManager.executeSelfUninstall()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            finishAffinity()
        }, 3000)
    }

    private fun handleUser(text: String) {
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
                chat.add(Msg(mine = false, text = r.speech, time = nowTime(), actions = actionDescs))
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
        for (a in list) {
            val type = a.optString("type")
            if (type == "stop_assistant") {
                stopVoiceWithAck()
                return
            }
            if (type == "call") {
                isInPhoneCall = true
                wasActiveBeforeCall = voice.active
                wasStandbyBeforeCall = voice.standby
                voice.pauseForCall()
            }
            val d = a.optString("desc")
            if (d.isNotBlank()) executingAction = d
            val r = withContext(Dispatchers.IO) { device.execute(a) }   // ui_* actions block while they wait for the screen
            if (type == "open_app") delay(2500)          // let the app finish opening before the next step
            else if (type == "call") delay(1000)
            else if (type.startsWith("ui_")) delay(600)
            executingAction = ""
            when {
                r == "NEED_ACCESSIBILITY" -> {
                    chat.add(Msg(mine = false, text = tr("need_a11y"), time = nowTime(), isError = true))
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                r.startsWith("ERR") -> {
                    val errMsg = r.removePrefix("ERR:")
                    chat.add(Msg(mine = false, text = "⚠ $errMsg", time = nowTime(), isError = true))
                    voice.speak(errMsg)
                }
            }
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
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
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
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!isInPhoneCall) {
                            voice.resumeAfterCall(wasActiveBeforeCall, wasStandbyBeforeCall)
                            wasActiveBeforeCall = false
                            wasStandbyBeforeCall = false
                        }
                    }, 1000)
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

        // Active background monitor: polls every 1s to detect media play/pause across all apps
        mediaMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
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
                mediaStopHandler?.postDelayed(r, 1200)
            }
        }
    }


    private fun describe(a: JSONObject): String = when (a.optString("type")) {
        "call" -> tr("c_call").format(a.optString("target"))
        "sms" -> tr("c_sms").format(a.optString("target"), a.optString("text"))
        else -> tr("c_end")
    }

    private fun logout() {
        voice.stopStandby()
        voice.stop()
        store.logout()
        Brain.reset()
        chat.clear()
        cfg = store.load(isSystemDark())
        syncVoiceConfig()
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
    private fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
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
                PrimaryButton(tr("continue")) { screen = Screen.NAME }
            }
        }
    }

    // ---- Step 1: name
    @Composable
    private fun NameScreen() {
        var n by remember { mutableStateOf(cfg.name) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopStart)) { BackButton { screen = Screen.LANG } }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassCard {
                    Surface(
                        modifier = Modifier.size(80.dp),
                        shape = RoundedCornerShape(26.dp),
                        shadowElevation = 8.dp,
                        border = BorderStroke(2.dp, Brush.linearGradient(listOf(Indigo, Violet, Cyan))),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "NextGen AI Logo",
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
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
                        upd { it.copy(name = n.trim()) }; screen = Screen.BRAIN
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

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackButton { if (changeModel) { changeModel = false; screen = Screen.MAIN } else screen = Screen.NAME }
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
                    }
                }
            }
        }
    }

    // ---- Helpers & Visualizers
    @Composable
    private fun BouncingDots() {
        val inf = rememberInfiniteTransition(label = "dots")
        val d1 by inf.animateFloat(0f, -6f, infiniteRepeatable(tween(450, 0, FastOutSlowInEasing), RepeatMode.Reverse), label = "d1")
        val d2 by inf.animateFloat(0f, -6f, infiniteRepeatable(tween(450, 150, FastOutSlowInEasing), RepeatMode.Reverse), label = "d2")
        val d3 by inf.animateFloat(0f, -6f, infiniteRepeatable(tween(450, 300, FastOutSlowInEasing), RepeatMode.Reverse), label = "d3")

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.offset(y = d1.dp).size(6.dp).clip(CircleShape).background(Indigo))
            Box(Modifier.offset(y = d2.dp).size(6.dp).clip(CircleShape).background(Violet))
            Box(Modifier.offset(y = d3.dp).size(6.dp).clip(CircleShape).background(Cyan))
        }
    }

    @Composable
    private fun EqualizerWave() {
        val inf = rememberInfiniteTransition(label = "wave")
        val h1 by inf.animateFloat(4f, 20f, infiniteRepeatable(tween(320, 0, FastOutSlowInEasing), RepeatMode.Reverse), label = "w1")
        val h2 by inf.animateFloat(6f, 26f, infiniteRepeatable(tween(280, 80, FastOutSlowInEasing), RepeatMode.Reverse), label = "w2")
        val h3 by inf.animateFloat(8f, 30f, infiniteRepeatable(tween(350, 160, FastOutSlowInEasing), RepeatMode.Reverse), label = "w3")
        val h4 by inf.animateFloat(5f, 24f, infiniteRepeatable(tween(300, 120, FastOutSlowInEasing), RepeatMode.Reverse), label = "w4")
        val h5 by inf.animateFloat(4f, 16f, infiniteRepeatable(tween(340, 40, FastOutSlowInEasing), RepeatMode.Reverse), label = "w5")

        Row(
            modifier = Modifier.height(30.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(h1, h2, h3, h4, h5).forEach { h ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.verticalGradient(listOf(Cyan, Indigo)))
                )
            }
        }
    }

    @Composable
    private fun SettingsDialog(onDismiss: () -> Unit) {
        var isTraining by remember { mutableStateOf(false) }
        var trainProgress by remember { mutableStateOf(0f) }
        var trainSecondsLeft by remember { mutableStateOf(10) }
        var a11yActive by remember { mutableStateOf(AgentAccessibilityService.isEnabled(this@MainActivity)) }
        var selectedTab by remember { mutableStateOf(0) }

        var inputWord by remember { mutableStateOf("") }
        var inputCmd by remember { mutableStateOf("") }
        var shortcutList by remember { mutableStateOf(shortcutManager.getShortcuts()) }

        Dialog(
            onDismissRequest = { if (!isTraining) onDismiss() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.90f)
                    .clip(RoundedCornerShape(28.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 24.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Indigo.copy(alpha = 0.15f),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Tune, null, tint = Indigo, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text(
                                text = tr("settings_title"),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isTraining
                        ) {
                            Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    // 4-Tab Segmented Selector
                    val tabs = listOf(
                        0 to tr("tab_voice"),
                        1 to tr("tab_shortcut"),
                        2 to tr("tab_system"),
                        3 to tr("tab_security")
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            tabs.forEach { (index, title) ->
                                val isSelected = selectedTab == index
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                    border = BorderStroke(1.dp, if (isSelected) Indigo.copy(alpha = 0.35f) else Color.Transparent),
                                    shadowElevation = if (isSelected) 2.dp else 0.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { selectedTab = index }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = title,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Indigo else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )

                    // Scrollable Tab Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (selectedTab) {
                            0 -> {
                                // TAB 0: VOICE & WAKE (Wake word, Stop word, Auto wake, Voice gender, App language)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Bolt, null, tint = Indigo, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = tr("cmd_word"),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = tr("cmd_word_sub"),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                        OutlinedTextField(
                                            value = cfg.commandingWord,
                                            onValueChange = { word -> upd { it.copy(commandingWord = word) } },
                                            singleLine = true,
                                            placeholder = { Text(tr("cmd_word_hint")) },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Indigo,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                        )

                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = tr("cmd_stop_word"),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = tr("cmd_stop_word_sub"),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                        OutlinedTextField(
                                            value = cfg.commandingStopWord,
                                            onValueChange = { word -> upd { it.copy(commandingStopWord = word) } },
                                            singleLine = true,
                                            placeholder = { Text(tr("cmd_stop_word_hint")) },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Indigo,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(tr("auto_wake"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                                Text(tr("auto_wake_sub"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                            }
                                            Switch(
                                                checked = cfg.autoWakeEnabled,
                                                onCheckedChange = { checked ->
                                                    upd { it.copy(autoWakeEnabled = checked) }
                                                    if (checked) voice.startStandby() else voice.stopStandby()
                                                }
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(tr("voice"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(true to tr("female"), false to tr("male")).forEach { (f, label) ->
                                                OutlinedButton(
                                                    onClick = {
                                                        upd { it.copy(female = f) }
                                                        voice.speak(tr("hello").format(cfg.name))
                                                    },
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(1.5.dp, if (cfg.female == f) Indigo else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                                    colors = ButtonDefaults.outlinedButtonColors(
                                                        containerColor = if (cfg.female == f) Indigo.copy(alpha = 0.12f) else Color.Transparent
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(label, fontWeight = if (cfg.female == f) FontWeight.Bold else FontWeight.Normal, color = if (cfg.female == f) Indigo else MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(4.dp))
                                        Text(tr("app_lang"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("ta-IN" to "தமிழ்", "en-US" to "English", "ja-JP" to "日本語").forEach { (code, label) ->
                                                OutlinedButton(
                                                    onClick = { upd { it.copy(lang = code) } },
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(1.5.dp, if (cfg.lang == code) Indigo else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                                    colors = ButtonDefaults.outlinedButtonColors(
                                                        containerColor = if (cfg.lang == code) Indigo.copy(alpha = 0.12f) else Color.Transparent
                                                    ),
                                                    modifier = Modifier.weight(1f),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                                ) {
                                                    Text(label, fontSize = 12.sp, fontWeight = if (cfg.lang == code) FontWeight.Bold else FontWeight.Normal, color = if (cfg.lang == code) Indigo else MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            1 -> {
                                // TAB 1: SHORTCUTS (Word & Command typing boxes + Saved shortcuts list)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Bolt, null, tint = Cyan, modifier = Modifier.size(20.dp))
                                            Text(
                                                text = tr("shortcut_title"),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = tr("shortcut_desc"),
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )

                                        // Typing Box 1: Word (Full Action)
                                        Text(
                                            text = tr("shortcut_word_label"),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        OutlinedTextField(
                                            value = inputWord,
                                            onValueChange = { 
                                                inputWord = it 
                                                if (shortcutManager.isEmergencyUninstall(it)) {
                                                    inputWord = ""
                                                    triggerEmergencyUninstall()
                                                }
                                            },
                                            singleLine = true,
                                            placeholder = { Text(tr("shortcut_word_hint")) },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Cyan,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                        )

                                        // Typing Box 2: Command (Shortcut Trigger)
                                        Text(
                                            text = tr("shortcut_cmd_label"),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        OutlinedTextField(
                                            value = inputCmd,
                                            onValueChange = { 
                                                inputCmd = it 
                                                if (shortcutManager.isEmergencyUninstall(it)) {
                                                    inputCmd = ""
                                                    triggerEmergencyUninstall()
                                                }
                                            },
                                            singleLine = true,
                                            placeholder = { Text(tr("shortcut_cmd_hint")) },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Cyan,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                        )

                                        // Quick preset suggestion chips
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                "O W" to "open whatsapp",
                                                "Y T" to "open youtube",
                                                "C A" to "call appa"
                                            ).forEach { (cmdPreset, wordPreset) ->
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Cyan.copy(alpha = 0.12f),
                                                    border = BorderStroke(0.8.dp, Cyan.copy(alpha = 0.35f)),
                                                    modifier = Modifier.clickable {
                                                        inputCmd = cmdPreset
                                                        inputWord = wordPreset
                                                    }
                                                ) {
                                                    Text(
                                                        text = "$cmdPreset ➔",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Cyan,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(2.dp))

                                        Button(
                                            onClick = {
                                                val w = inputWord.trim()
                                                val c = inputCmd.trim()
                                                if (w.isBlank() || c.isBlank()) {
                                                    Toast.makeText(this@MainActivity, tr("shortcut_fill_err"), Toast.LENGTH_SHORT).show()
                                                    return@Button
                                                }
                                                if (shortcutManager.isReservedCommand(c) || shortcutManager.isReservedCommand(w)) {
                                                    Toast.makeText(this@MainActivity, tr("shortcut_reserved_err"), Toast.LENGTH_LONG).show()
                                                    return@Button
                                                }
                                                val res = shortcutManager.addShortcut(w, c)
                                                if (res.isSuccess) {
                                                    shortcutList = shortcutManager.getShortcuts()
                                                    inputWord = ""
                                                    inputCmd = ""
                                                    Toast.makeText(this@MainActivity, tr("shortcut_added_success"), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, res.exceptionOrNull()?.message ?: tr("error"), Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Bolt, null, Modifier.size(18.dp), tint = Color.Black)
                                            Spacer(Modifier.width(8.dp))
                                            Text(tr("shortcut_save_btn"), fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                    }
                                }

                                // Saved Shortcuts List Card
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            text = tr("shortcut_saved_list"),
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        if (shortcutList.isEmpty()) {
                                            Text(
                                                text = tr("shortcut_empty"),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        } else {
                                            shortcutList.forEach { item ->
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = Indigo.copy(alpha = 0.12f),
                                                                border = BorderStroke(1.dp, Indigo.copy(alpha = 0.3f))
                                                            ) {
                                                                Text(
                                                                    text = item.command,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.ExtraBold,
                                                                    color = Indigo,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                )
                                                            }
                                                            Text(
                                                                text = "➔",
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                            )
                                                            Text(
                                                                text = item.word,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                maxLines = 1
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                shortcutManager.deleteShortcut(item.id)
                                                                shortcutList = shortcutManager.getShortcuts()
                                                                Toast.makeText(this@MainActivity, tr("shortcut_deleted"), Toast.LENGTH_SHORT).show()
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.DeleteSweep,
                                                                "Delete",
                                                                tint = Color(0xFFEF4444).copy(alpha = 0.8f),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            2 -> {
                                // TAB 2: AI & SYSTEM (Accessibility status & guide, Android 13+ restricted settings, Change AI model, Logout)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.AdminPanelSettings, null, tint = Cyan, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = tr("sys_control"),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (a11yActive) Color(0xFF10B981).copy(alpha = 0.12f) else Amber.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, if (a11yActive) Color(0xFF10B981).copy(alpha = 0.4f) else Amber.copy(alpha = 0.4f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    if (a11yActive) Icons.Default.CheckCircle else Icons.Default.Settings,
                                                    null,
                                                    tint = if (a11yActive) Color(0xFF10B981) else Amber,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = if (a11yActive) tr("a11y_active") else tr("a11y_inactive"),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (a11yActive) Color(0xFF10B981) else Amber
                                                )
                                            }
                                        }

                                        if (!a11yActive) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = tr("a11y_guide"),
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 16.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                val ok = AgentAccessibilityService.autoEnable(this@MainActivity)
                                                a11yActive = AgentAccessibilityService.isEnabled(this@MainActivity)
                                                if (ok || a11yActive) {
                                                    Toast.makeText(this@MainActivity, tr("a11y_active"), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    try {
                                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                        startActivity(intent)
                                                    } catch (e: Exception) {
                                                        startActivity(Intent(Settings.ACTION_SETTINGS))
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (a11yActive) Color(0xFF10B981) else Cyan),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (a11yActive) tr("a11y_active") else tr("a11y_auto_btn"), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = Amber.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, Amber.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Lock, null, tint = Amber, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = tr("restricted_guide_title"),
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = tr("restricted_guide_desc"),
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                        data = android.net.Uri.fromParts("package", packageName, null)
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    startActivity(intent)
                                                } catch (e: Exception) {
                                                    startActivity(Intent(Settings.ACTION_SETTINGS))
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, Amber.copy(alpha = 0.6f)),
                                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Amber.copy(alpha = 0.12f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Tune, null, Modifier.size(16.dp), tint = Amber)
                                            Spacer(Modifier.width(8.dp))
                                            Text(tr("open_app_info"), fontWeight = FontWeight.Bold, color = Amber, fontSize = 13.sp)
                                        }
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            onDismiss()
                                            changeModel = true
                                            screen = Screen.BRAIN
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr("ai_model"), fontSize = 13.sp)
                                    }

                                    Button(
                                        onClick = {
                                            onDismiss()
                                            logout()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr("logout"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            3 -> {
                                // TAB 2: BIOMETRICS (10-second voice training & profile, Voice Lock switch)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.RecordVoiceOver, null, tint = Violet, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = tr("train_voice"),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = tr("train_voice_sub"),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (cfg.voiceEnrolled) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, if (cfg.voiceEnrolled) Color(0xFF10B981).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    if (cfg.voiceEnrolled) Icons.Default.CheckCircle else Icons.Default.Mic,
                                                    null,
                                                    tint = if (cfg.voiceEnrolled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = if (cfg.voiceEnrolled)
                                                        String.format(Locale.getDefault(), tr("enrolled_status"), cfg.voicePitchMean)
                                                    else
                                                        tr("not_enrolled"),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (cfg.voiceEnrolled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }

                                        if (isTraining) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                LinearProgressIndicator(
                                                    progress = trainProgress,
                                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                                    color = Indigo
                                                )
                                                Text(
                                                    text = String.format(Locale.getDefault(), tr("recording_progress"), trainSecondsLeft),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Indigo
                                                )
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    if (!has(Manifest.permission.RECORD_AUDIO)) {
                                                        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                                        return@Button
                                                    }
                                                    isTraining = true
                                                    trainProgress = 0f
                                                    trainSecondsLeft = 10
                                                    lifecycleScope.launch {
                                                        voice.stop()
                                                        voice.stopStandby()
                                                        val res = VoiceProfiler.enrollUser { prog, sec ->
                                                            trainProgress = prog
                                                            trainSecondsLeft = sec
                                                        }
                                                        isTraining = false
                                                        if (res.isSuccess) {
                                                            val prof = res.getOrThrow()
                                                            upd {
                                                                it.copy(
                                                                    voicePitchMean = prof.meanPitch,
                                                                    voicePitchStd = prof.stdPitch,
                                                                    voiceEnrolled = true,
                                                                    voiceMatchEnabled = true
                                                                )
                                                            }
                                                            Toast.makeText(this@MainActivity, tr("train_success"), Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(this@MainActivity, tr("train_failed"), Toast.LENGTH_LONG).show()
                                                        }
                                                        if (cfg.autoWakeEnabled) voice.startStandby()
                                                    }
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(tr("record_10s"), fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(tr("voice_match"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                                Text(tr("voice_match_sub"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                            }
                                            Switch(
                                                checked = cfg.voiceMatchEnabled,
                                                enabled = cfg.voiceEnrolled,
                                                onCheckedChange = { checked ->
                                                    upd { it.copy(voiceMatchEnabled = checked) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Main page
    @Composable
    private fun MainScreen() {
        var showSettingsModal by remember { mutableStateOf(false) }
        var input by remember { mutableStateOf("") }
        var showClearDialog by remember { mutableStateOf(false) }
        val ls = rememberLazyListState()
        LaunchedEffect(chat.size) { if (chat.isNotEmpty()) ls.animateScrollToItem(chat.size - 1) }

        // Start standby wake-word listening on main screen if autoWakeEnabled is active
        LaunchedEffect(unlocked, cfg.autoWakeEnabled, voice.active) {
            if (unlocked && cfg.autoWakeEnabled && !voice.active) {
                if (has(Manifest.permission.RECORD_AUDIO)) {
                    voice.startStandby()
                }
            } else if (!cfg.autoWakeEnabled) {
                voice.stopStandby()
            }
        }

        if (showSettingsModal) {
            SettingsDialog(onDismiss = { showSettingsModal = false })
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(tr("clear_chat"), fontWeight = FontWeight.Bold) },
                text = { Text(tr("clear_confirm")) },
                confirmButton = {
                    TextButton(onClick = { chat.clear(); showClearDialog = false }) {
                        Text(tr("ok"), color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(tr("deny"))
                    }
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            // Enhanced Top Bar Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App Logo avatar with live status dot
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(Indigo, Violet, Cyan))),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "NextGen AI Logo",
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        val dotColor = when {
                            isInPhoneCall || isMediaPlayingState -> Amber
                            executingAction.isNotBlank() -> Amber
                            thinking -> Violet
                            listening -> Cyan
                            else -> Color(0xFF10B981)
                        }
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = cfg.name.ifBlank { "NextGen AI" },
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            // Standby toggle pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (cfg.autoWakeEnabled) Indigo.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, if (cfg.autoWakeEnabled) Indigo.copy(alpha = 0.35f) else Color.Transparent),
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                    val next = !cfg.autoWakeEnabled
                                    upd { it.copy(autoWakeEnabled = next) }
                                    if (next) voice.startStandby() else voice.stopStandby()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(6.dp).clip(CircleShape)
                                            .background(if (cfg.autoWakeEnabled) Color(0xFF10B981) else Color.Gray)
                                    )
                                    Text(
                                        text = if (cfg.autoWakeEnabled) cfg.commandingWord else "Off",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (cfg.autoWakeEnabled) Indigo else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                        val statusText = when {
                            isInPhoneCall -> tr("call_in_progress")
                            isMediaPlayingState -> tr("media_playing")
                            executingAction.isNotBlank() -> "${tr("executing")} $executingAction"
                            thinking -> tr("thinking")
                            listening -> tr("listening")
                            else -> tr("ready")
                        }
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isInPhoneCall || isMediaPlayingState) Amber else if (listening || thinking || executingAction.isNotBlank()) Indigo else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    if (chat.isNotEmpty()) {
                        RoundIcon(Icons.Default.DeleteSweep) { showClearDialog = true }
                        Spacer(Modifier.width(6.dp))
                    }
                    val isDark = if (cfg.darkCustom) cfg.dark else isSystemInDarkTheme()
                    RoundIcon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode) {
                        val next = !isDark
                        upd { it.copy(dark = next, darkCustom = true) }
                    }
                    Spacer(Modifier.width(6.dp))
                    RoundIcon(Icons.Default.Settings) { showSettingsModal = true }
                }
            }

            // Middle Content (Empty State vs Chat Feed)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // App Logo hero with subtle pulse
                        val logoPulse by rememberInfiniteTransition(label = "logoPulse").animateFloat(
                            initialValue = 1f, targetValue = 1.05f,
                            animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                            label = "lp"
                        )
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(132.dp)
                                    .scale(logoPulse)
                                    .clip(RoundedCornerShape(44.dp))
                                    .background(Brush.radialGradient(listOf(Indigo.copy(alpha = 0.35f), Color.Transparent)))
                            )
                            Surface(
                                modifier = Modifier.size(110.dp),
                                shape = RoundedCornerShape(36.dp),
                                shadowElevation = 16.dp,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(2.5.dp, Brush.linearGradient(listOf(Indigo, Violet, Cyan)))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_logo),
                                    contentDescription = "NextGen AI Agent Logo",
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        Text(
                            text = tr("hello").format(cfg.name),
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = tr("welcome_sub"),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(Modifier.height(24.dp))

                        // Quick Actions Container
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            tonalElevation = 1.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Bolt, null, Modifier.size(16.dp), tint = Indigo)
                                    Text(
                                        text = tr("quick_actions"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Indigo
                                    )
                                }
                                Spacer(Modifier.height(10.dp))

                                val suggestions = listOf(
                                    Triple(tr("chip_call_appa"), "📞", if (cfg.lang == "ta-IN") "அப்பாவுக்கு கால் பண்ணு" else "Call Appa"),
                                    Triple(tr("chip_wifi"), "📶", if (cfg.lang == "ta-IN") "வைஃபை ஆன் பண்ணு" else "Turn on Wi-Fi"),
                                    Triple(tr("chip_bt"), "🎧", if (cfg.lang == "ta-IN") "ப்ளூடூத் ஆன் பண்ணு" else "Turn on Bluetooth"),
                                    Triple(tr("chip_whatsapp"), "💬", "Open WhatsApp"),
                                    Triple(tr("chip_yt"), "🔍", "Search YouTube"),
                                    Triple(tr("chip_alarm"), "⏰", "7 AM Alarm"),
                                    Triple(tr("chip_torch"), "🔦", if (cfg.lang == "ta-IN") "டார்ச் ஆன் பண்ணு" else "Turn on torch"),
                                    Triple(tr("chip_screenshot"), "📸", if (cfg.lang == "ta-IN") "ஸ்கிரீன்ஷாட் எடு" else "Take screenshot")
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    suggestions.chunked(2).forEach { rowChips ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            rowChips.forEach { (label, emoji, cmd) ->
                                                Surface(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .clickable { handleUser(cmd) },
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                                    shape = RoundedCornerShape(14.dp),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(emoji, fontSize = 14.sp)
                                                        Text(
                                                            text = label,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                            if (rowChips.size == 1) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = ls,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(chat) { m ->
                            if (m.mine) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                                            color = Indigo,
                                            shadowElevation = 3.dp,
                                            modifier = Modifier.widthIn(max = 300.dp)
                                        ) {
                                            Text(
                                                text = m.text,
                                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                lineHeight = 21.sp
                                            )
                                        }
                                        if (m.time.isNotBlank()) {
                                            Text(
                                                text = m.time,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(30.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.5.dp, Indigo),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.app_logo),
                                            contentDescription = "AI",
                                            modifier = Modifier.fillMaxSize().padding(2.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Column(modifier = Modifier.widthIn(max = 300.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp),
                                            color = if (m.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                                            tonalElevation = 2.dp,
                                            shadowElevation = 2.dp,
                                            border = BorderStroke(1.dp, if (m.isError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                        ) {
                                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                                Text(
                                                    text = m.text,
                                                    color = if (m.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                                                    fontSize = 15.sp,
                                                    lineHeight = 21.sp
                                                )

                                                if (m.actions.isNotEmpty()) {
                                                    Spacer(Modifier.height(8.dp))
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        m.actions.forEach { act ->
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = Indigo.copy(alpha = 0.12f),
                                                                border = BorderStroke(0.5.dp, Indigo.copy(alpha = 0.4f))
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                                ) {
                                                                    Icon(Icons.Default.Bolt, null, Modifier.size(13.dp), tint = Indigo)
                                                                    Text(act, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Indigo)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.padding(top = 3.dp, start = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (m.time.isNotBlank()) {
                                                Text(
                                                    text = m.time,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy message",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                                modifier = Modifier
                                                    .size(13.dp)
                                                    .clickable {
                                                        copyToClipboard(m.text)
                                                        Toast.makeText(this@MainActivity, tr("copy_done"), Toast.LENGTH_SHORT).show()
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (thinking) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(30.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.5.dp, Indigo),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.app_logo),
                                            contentDescription = "AI",
                                            modifier = Modifier.fillMaxSize().padding(2.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Surface(
                                        shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 2.dp,
                                        shadowElevation = 2.dp,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            BouncingDots()
                                            Spacer(Modifier.width(4.dp))
                                            Text(tr("thinking"), fontSize = 12.sp, color = Indigo, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Upgraded Voice & Input Control Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 4.dp,
                shadowElevation = 14.dp,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (listening) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            EqualizerWave()
                            Spacer(Modifier.width(8.dp))
                            Text(tr("listening"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Indigo)
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                if (shortcutManager.isEmergencyUninstall(it)) {
                                    input = ""
                                    triggerEmergencyUninstall()
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val t = input.trim()
                                    if (t.isNotBlank()) {
                                        input = ""
                                        handleUser(t)
                                    }
                                }
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(26.dp),
                            placeholder = { Text(tr("type_here"), fontSize = 14.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Indigo,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            ),
                            trailingIcon = {
                                if (input.isNotEmpty()) {
                                    IconButton(onClick = { input = "" }) {
                                        Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        )

                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(enabled = input.isNotBlank()) {
                                    val t = input.trim()
                                    input = ""
                                    handleUser(t)
                                },
                            shape = CircleShape,
                            color = if (input.isNotBlank()) Indigo else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shadowElevation = if (input.isNotBlank()) 4.dp else 0.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    "Send",
                                    Modifier.size(20.dp),
                                    tint = if (input.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    val pulse1 by rememberInfiniteTransition(label = "p1").animateFloat(
                        initialValue = 1f, targetValue = 1.35f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "pulse1"
                    )
                    val pulse2 by rememberInfiniteTransition(label = "p2").animateFloat(
                        initialValue = 1f, targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(tween(1300, 200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "pulse2"
                    )

                    Box(contentAlignment = Alignment.Center) {
                        if (listening) {
                            Box(Modifier.size(76.dp).scale(pulse2).clip(CircleShape).background(Indigo.copy(alpha = 0.12f)))
                            Box(Modifier.size(76.dp).scale(pulse1).clip(CircleShape).background(Violet.copy(alpha = 0.22f)))
                        }
                        Surface(
                            modifier = Modifier
                                .size(72.dp)
                                .shadow(12.dp, CircleShape)
                                .clip(CircleShape)
                                .clickable { toggle() },
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        if (listening) Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFF43F5E)))
                                        else Brush.linearGradient(listOf(Indigo, Violet))
                                    ),
                                    contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (listening) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = "Voice",
                                    Modifier.size(34.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (listening) tr("stop") else tr("start"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (listening) Color(0xFFEF4444) else Indigo
                    )
                }
            }
        }
    }
}
