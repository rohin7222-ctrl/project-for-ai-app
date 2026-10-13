package com.nextgen.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Screen { LANG, NAME, BRAIN, MAIN }
data class Msg(val mine: Boolean, val text: String)

private val Indigo = Color(0xFF6366F1)
private val Violet = Color(0xFF8B5CF6)
private val PROVIDERS = listOf(
    "gemini" to "Google Gemini", "groq" to "Groq", "openai" to "OpenAI", "anthropic" to "Claude",
    "openrouter" to "OpenRouter", "mistral" to "Mistral", "deepseek" to "DeepSeek"
)

class MainActivity : FragmentActivity() {
    private lateinit var store: Store
    private lateinit var voice: Voice
    private lateinit var device: DeviceController

    private var cfg by mutableStateOf(Config())
    private var screen by mutableStateOf(Screen.LANG)
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var unlocked by mutableStateOf(true)
    private var pending by mutableStateOf<List<JSONObject>>(emptyList())
    private val chat = mutableStateListOf<Msg>()

    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS
    )
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.RECORD_AUDIO)) startVoice()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun tr(k: String) = S.t(cfg.lang, k)
    private fun upd(b: (Config) -> Config) { cfg = b(cfg); store.save(cfg) }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        device = DeviceController(this)
        cfg = store.load()
        voice = Voice(this, onText = { handleUser(it) }, onState = { listening = it })
        voice.lang = cfg.lang; voice.female = cfg.female
        if (store.isReady()) { screen = Screen.MAIN; unlocked = false; authenticate() }
        setContent { App() }
    }

    override fun onDestroy() { voice.shutdown(); super.onDestroy() }

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
        if (voice.active) { voice.stop(); return }
        val missing = perms.filter { !has(it) }
        if (missing.isEmpty()) startVoice() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startVoice() { voice.lang = cfg.lang; voice.female = cfg.female; voice.start() }

    private fun handleUser(text: String) {
        chat.add(Msg(true, text)); thinking = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Brain.chat(cfg, text) }
            thinking = false
            if (r.ok) {
                chat.add(Msg(false, r.speech)); voice.speak(r.speech)
                val (sens, safe) = r.actions.partition { DeviceController.isSensitive(it.optString("type")) }
                delay(1200)                       // let the voice reply start before apps open
                runList(safe)
                if (sens.isNotEmpty()) pending = sens   // calls / SMS need an explicit tap
            } else {
                val m = tr("error"); chat.add(Msg(false, "$m\n${r.error}")); voice.speak(m)
            }
        }
    }

    private suspend fun runList(list: List<JSONObject>) {
        for (a in list) {
            val r = withContext(Dispatchers.IO) { device.execute(a) }   // ui_* actions block while they wait for the screen
            if (a.optString("type") == "open_app") delay(2500)          // let the app finish opening before the next step
            else if (a.optString("type").startsWith("ui_")) delay(600)
            when {
                r == "NEED_ACCESSIBILITY" -> {
                    chat.add(Msg(false, tr("need_a11y")))
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                r.startsWith("ERR") -> chat.add(Msg(false, "⚠ " + r.removePrefix("ERR:")))
            }
        }
    }

    private fun describe(a: JSONObject): String = when (a.optString("type")) {
        "call" -> tr("c_call").format(a.optString("target"))
        "sms" -> tr("c_sms").format(a.optString("target"), a.optString("text"))
        else -> tr("c_end")
    }

    private fun logout() {
        voice.stop(); store.logout(); Brain.reset(); chat.clear()
        cfg = store.load(); screen = Screen.LANG
    }

    // ------------------------------------------------------------------ UI
    @Composable
    private fun App() {
        val scheme = if (cfg.dark)
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
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Indigo, Violet))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SmartToy, null, Modifier.size(38.dp), tint = Color.White)
                    }
                    Text(tr("name_title"), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Text(tr("name_sub"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    OutlinedTextField(
                        value = n, onValueChange = { n = it.take(20) }, singleLine = true,
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
        var key by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        var url by remember { mutableStateOf(cfg.url) }
        var lmodel by remember { mutableStateOf("") }
        var models by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var loading by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        var tick by remember { mutableStateOf(0) }

        // Real, live model list from the provider (so retired models never show up).
        LaunchedEffect(provider, key, tick) {
            models = emptyList(); model = ""; err = ""
            if (key.trim().length >= 8) {
                if (tick == 0) delay(700)
                loading = true
                try {
                    withContext(Dispatchers.IO) { Brain.models(provider, key.trim()) }
                        .onSuccess { models = it }.onFailure { err = it.message ?: "error" }
                } finally { loading = false }
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackButton { screen = Screen.NAME }
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
                        OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            visualTransformation = PasswordVisualTransformation(), label = { Text(tr("api_key")) })
                        OutlinedButton(onClick = { tick++ }, enabled = key.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(tr("fetch")) }
                        Text(tr("models"), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                        if (loading) CircularProgressIndicator()
                        else if (models.isEmpty()) Text(if (err.isNotEmpty()) err else tr("no_models"), fontSize = 13.sp,
                            color = if (err.isNotEmpty()) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        else Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            models.forEach { (id, name) ->
                                val sel = model == id
                                val shape = RoundedCornerShape(16.dp)
                                Row(Modifier.fillMaxWidth().clip(shape)
                                    .background(if (sel) Indigo.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                                    .border(2.dp, if (sel) Indigo else Color.Transparent, shape)
                                    .clickable { model = id }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontWeight = FontWeight.Bold)
                                        if (name != id) Text(id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                                    }
                                    if (sel) Icon(Icons.Default.Check, null, tint = Indigo)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), label = { Text(tr("local_url")) })
                        OutlinedTextField(value = lmodel, onValueChange = { lmodel = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), label = { Text(tr("model_name")) })
                    }

                    val ready = if (type == "api") model.isNotEmpty() else url.isNotBlank() && lmodel.isNotBlank()
                    PrimaryButton(tr("ok"), enabled = ready) {
                        upd {
                            it.copy(type = type, provider = provider, key = key.trim(),
                                model = if (type == "api") model else lmodel.trim(), url = url.trim())
                        }
                        store.setReady(true); Brain.reset(); screen = Screen.MAIN
                    }
                }
            }
        }
    }

    // ---- Main page
    @Composable
    private fun MainScreen() {
        var menu by remember { mutableStateOf(false) }
        var page by remember { mutableStateOf("root") }
        var input by remember { mutableStateOf("") }
        val ls = rememberLazyListState()
        LaunchedEffect(chat.size) { if (chat.isNotEmpty()) ls.animateScrollToItem(chat.size - 1) }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(cfg.name, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                RoundIcon(if (cfg.dark) Icons.Default.LightMode else Icons.Default.DarkMode) { upd { it.copy(dark = !it.dark) } }
                Spacer(Modifier.width(8.dp))
                Box {
                    RoundIcon(Icons.Default.Settings) { page = "root"; menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        when (page) {
                            "root" -> {
                                DropdownMenuItem(text = { Text(tr("voice")) }, leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) },
                                    onClick = { page = "voice" })
                                DropdownMenuItem(text = { Text(tr("app_lang")) }, leadingIcon = { Icon(Icons.Default.Language, null) },
                                    onClick = { page = "lang" })
                                DropdownMenuItem(text = { Text(tr("sys_control")) }, leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null) },
                                    onClick = { menu = false; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(tr("logout"), color = Color(0xFFE53935)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Color(0xFFE53935)) },
                                    onClick = { menu = false; logout() })
                            }
                            "voice" -> listOf(true to tr("female"), false to tr("male")).forEach { (f, label) ->
                                DropdownMenuItem(text = { Text(label) },
                                    trailingIcon = { if (cfg.female == f) Icon(Icons.Default.Check, null, tint = Indigo) },
                                    onClick = {
                                        upd { it.copy(female = f) }; voice.female = f; menu = false
                                        voice.speak(tr("hello").format(cfg.name))
                                    })
                            }
                            else -> listOf("ta-IN" to "தமிழ்", "en-US" to "English", "ja-JP" to "日本語").forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label) },
                                    trailingIcon = { if (cfg.lang == code) Icon(Icons.Default.Check, null, tint = Indigo) },
                                    onClick = { upd { it.copy(lang = code) }; voice.lang = code; menu = false })
                            }
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(32.dp))
                            .background(Brush.linearGradient(listOf(Indigo, Violet))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SmartToy, null, Modifier.size(52.dp), tint = Color.White)
                        }
                        Text(tr("hello").format(cfg.name), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                LazyColumn(state = ls, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(chat) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                            Surface(
                                shape = RoundedCornerShape(20.dp, 20.dp, if (m.mine) 4.dp else 20.dp, if (m.mine) 20.dp else 4.dp),
                                color = if (m.mine) Indigo else MaterialTheme.colorScheme.surface,
                                shadowElevation = 2.dp, modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Text(m.text, Modifier.padding(14.dp), color = if (m.mine) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (thinking) tr("thinking") else if (listening) tr("listening") else " ", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp),
                        placeholder = { Text(tr("type_here")) })
                    IconButton(onClick = { if (input.isNotBlank()) { handleUser(input.trim()); input = "" } }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = Indigo)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 1f, targetValue = 1.4f, label = "p",
                    animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse))
                Box(contentAlignment = Alignment.Center) {
                    if (listening) Box(Modifier.size(84.dp).scale(pulse).clip(CircleShape).background(Indigo.copy(alpha = .25f)))
                    Box(Modifier.size(84.dp).shadow(16.dp, CircleShape).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Indigo, Violet))).clickable { toggle() },
                        contentAlignment = Alignment.Center) {
                        Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, null, Modifier.size(38.dp), tint = Color.White)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(if (listening) tr("stop") else tr("start"), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
