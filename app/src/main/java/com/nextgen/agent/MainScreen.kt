package com.nextgen.agent

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ---- Helpers & Visualizers
@Composable
internal fun BouncingDots() {
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
internal fun EqualizerWave() {
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
internal fun GeminiSparkle(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            quadraticTo(cx, cy, w, cy)
            quadraticTo(cx, cy, cx, h)
            quadraticTo(cx, cy, 0f, cy)
            quadraticTo(cx, cy, cx, 0f)
            close()
        }
        drawPath(path, GeminiGradient)
    }
}

@Composable
internal fun GeminiLiveWave() {
    val inf = rememberInfiniteTransition(label = "gemini_wave")
    val h1 by inf.animateFloat(4f, 20f, infiniteRepeatable(tween(280, 0, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh1")
    val h2 by inf.animateFloat(6f, 28f, infiniteRepeatable(tween(320, 60, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh2")
    val h3 by inf.animateFloat(10f, 34f, infiniteRepeatable(tween(260, 120, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh3")
    val h4 by inf.animateFloat(12f, 38f, infiniteRepeatable(tween(310, 180, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh4")
    val h5 by inf.animateFloat(14f, 42f, infiniteRepeatable(tween(350, 240, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh5")
    val h6 by inf.animateFloat(12f, 38f, infiniteRepeatable(tween(300, 180, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh6")
    val h7 by inf.animateFloat(10f, 34f, infiniteRepeatable(tween(270, 120, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh7")
    val h8 by inf.animateFloat(6f, 28f, infiniteRepeatable(tween(330, 60, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh8")
    val h9 by inf.animateFloat(4f, 20f, infiniteRepeatable(tween(290, 0, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh9")

    val colors = listOf(GeminiBlue, GeminiPurple, GeminiCyan, GeminiPink, Color(0xFFF59E0B), GeminiPink, GeminiCyan, GeminiPurple, GeminiBlue)
    val heights = listOf(h1, h2, h3, h4, h5, h6, h7, h8, h9)

    Row(
        modifier = Modifier.height(38.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(3.2.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors[i % colors.size])
            )
        }
    }
}



// ---- Main page
@Composable
internal fun MainActivity.MainScreen() {
    val activity = this
    var showSettingsModal by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var showTopWheel by remember { mutableStateOf(false) }
    var showBottomWheel by remember { mutableStateOf(false) }
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

    val isDark = if (cfg.darkCustom) cfg.dark else isSystemInDarkTheme()

    // 1. TOP WHEEL ITEMS (Settings & Preferences Wheel)
    val topWheelItems = listOf(
        WheelItem(
            id = "full_settings",
            label = tr("settings_title"),
            icon = Icons.Default.Settings,
            accentColor = Color(0xFF8B5CF6),
            onClick = { showSettingsModal = true }
        ),
        WheelItem(
            id = "brain_config",
            label = tr("ai_model"),
            icon = Icons.Default.Bolt,
            accentColor = Indigo,
            onClick = { changeModel = true; screen = Screen.BRAIN }
        ),
        WheelItem(
            id = "lang_switch",
            label = tr("app_lang"),
            icon = Icons.Default.Language,
            accentColor = Cyan,
            onClick = { cycleLanguage() }
        ),
        WheelItem(
            id = "theme_toggle",
            label = if (isDark) "Light Mode" else "Dark Mode",
            icon = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
            accentColor = Amber,
            onClick = { upd { it.copy(dark = !isDark, darkCustom = true) } }
        ),
        WheelItem(
            id = "accessibility",
            label = "Accessibility",
            icon = Icons.Default.AdminPanelSettings,
            accentColor = Color(0xFFF97316),
            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ),
        WheelItem(
            id = "battery_ignore",
            label = tr("battery_ignore_btn"),
            icon = Icons.Default.BatteryChargingFull,
            accentColor = Color(0xFF10B981),
            onClick = { requestIgnoreBatteryOptimization() }
        ),
        WheelItem(
            id = "raise_to_ear",
            label = tr("raise_to_ear_wheel"),
            icon = Icons.Default.PhoneInTalk,
            accentColor = if (cfg.raiseToAnswer) Color(0xFF10B981) else Color(0xFF94A3B8),
            onClick = {
                val next = !cfg.raiseToAnswer
                if (next) {
                    val needed = mutableListOf<String>()
                    if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) needed.add(Manifest.permission.ANSWER_PHONE_CALLS)
                    if (!has(Manifest.permission.READ_PHONE_STATE)) needed.add(Manifest.permission.READ_PHONE_STATE)
                    if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
                }
                upd { it.copy(raiseToAnswer = next) }
                val msg = if (next) {
                    if (cfg.lang == "ta-IN") "⚡ காதுக்கு கொண்டுவா இயக்கம்: ஆன் (24/7 இயங்கும்)" else "⚡ Raise to Ear Answer: ON (24/7 Background Ready)"
                } else {
                    if (cfg.lang == "ta-IN") "காதுக்கு கொண்டுவா இயக்கம்: ஆஃப்" else "Raise to Ear Answer: OFF"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        ),
        WheelItem(
            id = "auto_wake",
            label = tr("auto_wake"),
            icon = Icons.Default.RecordVoiceOver,
            accentColor = if (cfg.autoWakeEnabled) Color(0xFF10B981) else Color.Gray,
            onClick = {
                val next = !cfg.autoWakeEnabled
                upd { it.copy(autoWakeEnabled = next) }
                if (next) voice.startStandby() else voice.stopStandby()
                Toast.makeText(activity, if (next) "Auto-Wake ON" else "Auto-Wake OFF", Toast.LENGTH_SHORT).show()
            }
        ),
        WheelItem(
            id = "crash_logs",
            label = tr("crash_log_title"),
            icon = Icons.Default.BugReport,
            accentColor = Color(0xFFF43F5E),
            onClick = { showSettingsModal = true }
        ),
        WheelItem(
            id = "clear_chat",
            label = tr("clear_chat"),
            icon = Icons.Default.DeleteSweep,
            accentColor = Color(0xFFEF4444),
            onClick = { showClearDialog = true }
        )
    )

    // 2. BOTTOM WHEEL ITEMS (Quick Actions Menu Wheel)
    val bottomWheelItems = listOf(
        WheelItem(
            id = "quick_call",
            label = tr("chip_call"),
            icon = Icons.Default.Phone,
            accentColor = Color(0xFF10B981),
            onClick = { quickCall() }
        ),
        WheelItem(
            id = "quick_whatsapp",
            label = tr("chip_whatsapp"),
            icon = Icons.AutoMirrored.Filled.Chat,
            accentColor = Color(0xFF22C55E),
            onClick = { quickWhatsApp() }
        ),
        WheelItem(
            id = "quick_notif",
            label = tr("chip_notif"),
            icon = Icons.Default.Notifications,
            accentColor = Color(0xFF38BDF8),
            onClick = { quickReadNotifications() }
        ),
        WheelItem(
            id = "quick_sms",
            label = tr("chip_sms"),
            icon = Icons.Default.Sms,
            accentColor = Color(0xFF6366F1),
            onClick = { quickReadSms() }
        ),
        WheelItem(
            id = "quick_camera",
            label = tr("chip_camera"),
            icon = Icons.Default.CameraAlt,
            accentColor = Color(0xFFE1306C),
            onClick = { quickCamera() }
        ),
        WheelItem(
            id = "quick_torch",
            label = tr("chip_torch"),
            icon = Icons.Default.FlashlightOn,
            accentColor = Amber,
            onClick = { toggleTorch() }
        ),
        WheelItem(
            id = "quick_wifi",
            label = tr("chip_wifi"),
            icon = Icons.Default.Wifi,
            accentColor = Cyan,
            onClick = { quickWifi() }
        ),
        WheelItem(
            id = "quick_data",
            label = tr("chip_data"),
            icon = Icons.Default.SignalCellularAlt,
            accentColor = Color(0xFF0284C7),
            onClick = { quickMobileData() }
        ),
        WheelItem(
            id = "quick_bluetooth",
            label = tr("chip_bt"),
            icon = Icons.Default.Bluetooth,
            accentColor = Color(0xFF3B82F6),
            onClick = { toggleBluetooth() }
        ),
        WheelItem(
            id = "quick_screen_read",
            label = tr("chip_screen_read"),
            icon = Icons.Default.Visibility,
            accentColor = Color(0xFFA855F7),
            onClick = { quickScreenRead() }
        ),
        WheelItem(
            id = "quick_music",
            label = tr("chip_music"),
            icon = Icons.Default.MusicNote,
            accentColor = Color(0xFF1DB954),
            onClick = { quickMusic() }
        ),
        WheelItem(
            id = "quick_reels",
            label = tr("chip_reels"),
            icon = Icons.Default.VideoLibrary,
            accentColor = Color(0xFFEC4899),
            onClick = { quickReels() }
        ),
        WheelItem(
            id = "quick_google",
            label = tr("chip_google"),
            icon = Icons.Default.Search,
            accentColor = Color(0xFF4285F4),
            onClick = { quickGoogleSearch() }
        ),
        WheelItem(
            id = "quick_alarm",
            label = tr("chip_alarm"),
            icon = Icons.Default.Alarm,
            accentColor = Color(0xFFA855F7),
            onClick = { quickAlarm() }
        ),
        WheelItem(
            id = "quick_stop",
            label = tr("chip_stop"),
            icon = Icons.Default.Stop,
            accentColor = Color(0xFFEF4444),
            onClick = { stopVoiceWithAck() }
        )
    )

    // Futuristic animation transitions
    val infTransition = rememberInfiniteTransition(label = "hudTransition")
    val ringRotate by infTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart),
        label = "rr"
    )
    val counterRotate by infTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "cr"
    )
    val logoPulse by infTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "lp"
    )
    val beaconPulse by infTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bp"
    )

    Box(Modifier.fillMaxSize().background(if (isDark) Color(0xFF080B11) else Color(0xFFF4F7FC))) {
        // Futuristic Cyber HUD Background: Grid Matrix & Cosmic Blooms
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Subtle Cyber Isometric Grid Lines
            val gridStep = 44.dp.toPx()
            val gridColor = if (isDark) Color(0xFF00E5FF).copy(alpha = 0.035f) else Color(0xFF4F46E5).copy(alpha = 0.025f)
            var x = 0f
            while (x < w) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                x += gridStep
            }
            var y = 0f
            while (y < h) {
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += gridStep
            }

            // 3. Volumetric Cyber Energy Nebulae
            // Top-right energetic bloom (Anchoring Top Wheel)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf((if (isDark) Color(0xFF7C3AED) else Color(0xFF818CF8)).copy(alpha = if (isDark) 0.18f else 0.09f), Color.Transparent),
                    center = Offset(w * 0.92f, h * 0.10f),
                    radius = w * 0.70f
                ),
                center = Offset(w * 0.92f, h * 0.10f),
                radius = w * 0.70f
            )
            // Bottom-right energetic bloom (Anchoring Bottom Wheel)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf((if (isDark) Color(0xFF00E5FF) else Color(0xFF38BDF8)).copy(alpha = if (isDark) 0.16f else 0.08f), Color.Transparent),
                    center = Offset(w * 0.90f, h * 0.92f),
                    radius = w * 0.65f
                ),
                center = Offset(w * 0.90f, h * 0.92f),
                radius = w * 0.65f
            )
            // Center Quantum Plasma Core Bloom
            drawCircle(
                brush = Brush.radialGradient(
                    listOf((if (isDark) Color(0xFF06B6D4) else Color(0xFF6366F1)).copy(alpha = if (isDark) 0.09f else 0.04f), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.45f),
                    radius = w * 0.55f
                ),
                center = Offset(w * 0.5f, h * 0.45f),
                radius = w * 0.55f
            )
        }

        Column(Modifier.fillMaxSize()) {
            // Enhanced Cyber HUD Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) Color(0xFF0D121F).copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, if (isDark) Color(0x3300E5FF) else Color(0x224F46E5))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: High-Tech Cyber Telemetry Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isDark) Color(0xFF131A2B) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        border = BorderStroke(1.4.dp, Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Indigo, Violet))),
                        shadowElevation = 4.dp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dotColor = when {
                                isInPhoneCall || isMediaPlayingState -> Amber
                                executingAction.isNotBlank() -> Amber
                                thinking -> Violet
                                listening -> Color(0xFF00E5FF)
                                else -> Color(0xFF10B981)
                            }
                            // Pulsing Cyber Beacon
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .scale(beaconPulse)
                                        .clip(CircleShape)
                                        .background(dotColor.copy(alpha = 0.28f))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.5.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                            Text(
                                text = cfg.name.ifBlank { "Aero Fenix" }.uppercase(Locale.ROOT),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                                color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
                            )
                            GeminiSparkle(size = 14.dp)
                        }
                    }

                    // Right: Actions & Top Helm Wheel Launcher
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (chat.isNotEmpty()) {
                            RoundIcon(Icons.Default.DeleteSweep) { showClearDialog = true }
                        }

                        // Compact Top Helm Button (opens top wheel menu)
                        CompactHelmButton(
                            onClick = { showTopWheel = true },
                            icon = Icons.Default.Settings,
                            accentColor = Color(0xFF8B5CF6),
                            size = 46.dp
                        )
                    }
                }
            }

            // Middle Content: Futuristic Arc Reactor vs Chat Feed
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.isEmpty()) {
                    // Center Hero: Quantum Arc Reactor (Visible only before chatting)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // High-Tech Concentric Quantum Arc Reactor Visualizer
                        Box(contentAlignment = Alignment.Center) {
                            // Layer 1 & 2: Outer & Middle Holographic HUD Rings
                            Canvas(modifier = Modifier.size(220.dp)) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val outerR = cx - 8.dp.toPx()
                                val midR = cx - 22.dp.toPx()

                                // Outer Precision Orbit Ring
                                drawCircle(
                                    color = if (isDark) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color(0xFF4F46E5).copy(alpha = 0.18f),
                                    radius = outerR,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )

                                // 24 Holographic Compass Ticks (Clockwise rotation)
                                for (step in 0 until 24) {
                                    val angleRad = Math.toRadians((step * 15.0 + ringRotate)).toFloat()
                                    val cosA = cos(angleRad)
                                    val sinA = sin(angleRad)
                                    val isMajor = step % 6 == 0
                                    val tickLen = if (isMajor) 7.5.dp.toPx() else 3.5.dp.toPx()
                                    val startX = cx + (outerR - tickLen) * cosA
                                    val startY = cy + (outerR - tickLen) * sinA
                                    val endX = cx + (outerR + tickLen) * cosA
                                    val endY = cy + (outerR + tickLen) * sinA
                                    drawLine(
                                        color = if (isMajor) Color(0xFF00E5FF).copy(alpha = 0.85f) else Color(0xFF8B5CF6).copy(alpha = 0.40f),
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY),
                                        strokeWidth = if (isMajor) 2.dp.toPx() else 1.2.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }

                                // Middle Segmented Arc Gauge (Counter-Clockwise rotation)
                                drawCircle(
                                    color = if (isDark) Color(0xFF8B5CF6).copy(alpha = 0.20f) else Color(0xFF8B5CF6).copy(alpha = 0.14f),
                                    radius = midR,
                                    style = Stroke(width = 1.2.dp.toPx())
                                )
                                for (step in 0 until 12) {
                                    val angleRad = Math.toRadians((step * 30.0 + counterRotate)).toFloat()
                                    val dotX = cx + midR * cos(angleRad)
                                    val dotY = cy + midR * sin(angleRad)
                                    drawCircle(
                                        color = if (step % 3 == 0) Color(0xFF00E5FF) else Color(0xFFD946EF).copy(alpha = 0.70f),
                                        radius = if (step % 3 == 0) 3.dp.toPx() else 1.8.dp.toPx(),
                                        center = Offset(dotX, dotY)
                                    )
                                }

                                // Satellite Glowing Orbiting Particle
                                val dotRad = Math.toRadians(ringRotate.toDouble()).toFloat()
                                val dotX = cx + outerR * cos(dotRad)
                                val dotY = cy + outerR * sin(dotRad)
                                drawCircle(
                                    color = Color(0xFF00E5FF).copy(alpha = 0.30f),
                                    radius = 9.dp.toPx(),
                                    center = Offset(dotX, dotY)
                                )
                                drawCircle(
                                    color = Color(0xFF00E5FF),
                                    radius = 4.dp.toPx(),
                                    center = Offset(dotX, dotY)
                                )
                            }

                            // Layer 3: Pulsing Plasma Glow
                            Box(
                                Modifier
                                    .size(175.dp)
                                    .scale(logoPulse)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                Color(0xFF00E5FF).copy(alpha = if (isDark) 0.35f else 0.20f),
                                                Color(0xFF8B5CF6).copy(alpha = if (isDark) 0.25f else 0.15f),
                                                Color(0xFFEC4899).copy(alpha = if (isDark) 0.12f else 0.08f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )

                            // Layer 4: Deep Obsidian Core with Logo
                            Surface(
                                modifier = Modifier.size(146.dp),
                                shape = CircleShape,
                                shadowElevation = 24.dp,
                                color = Color(0xFF05070D),
                                border = BorderStroke(2.8.dp, Brush.sweepGradient(listOf(Color(0xFF00E5FF), Color(0xFF8B5CF6), Color(0xFFD946EF), Color(0xFF00E5FF))))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(Color(0xFF05070D)),
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
                        }

                        Spacer(Modifier.height(20.dp))

                        // Futuristic Cyber Title
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "AERO FENIX",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.5.sp,
                                textAlign = TextAlign.Center,
                                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            GeminiSparkle(size = 20.dp)
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = tr("welcome_sub"),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    // Active Chat Conversation History
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
                                            shape = RoundedCornerShape(18.dp, 18.dp, 3.dp, 18.dp),
                                            color = if (isDark) Color(0xFF1E1B4B) else Color(0xFF4F46E5),
                                            border = BorderStroke(1.2.dp, Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFF00E5FF)))),
                                            shadowElevation = 4.dp,
                                            modifier = Modifier.widthIn(max = 300.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                Text(
                                                    text = "// USER",
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                                                    modifier = Modifier.padding(bottom = 2.dp)
                                                )
                                                Text(
                                                    text = m.text,
                                                    color = Color.White,
                                                    fontSize = 14.5.sp,
                                                    lineHeight = 20.sp
                                                )
                                            }
                                        }
                                        if (m.time.isNotBlank()) {
                                            Text(
                                                text = m.time,
                                                fontSize = 10.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                AiAnswerCard(
                                    msg = m,
                                    activity = activity,
                                    isDark = isDark,
                                    onFollowUp = { handleUser(it) }
                                )
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
                                        modifier = Modifier.size(32.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.2.dp, Color(0xFF8B5CF6)),
                                        color = if (isDark) Color(0xFF171333) else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            GeminiSparkle(size = 18.dp)
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Surface(
                                        shape = RoundedCornerShape(3.dp, 16.dp, 16.dp, 16.dp),
                                        color = if (isDark) Color(0xFF0F1523) else MaterialTheme.colorScheme.surface,
                                        tonalElevation = 2.dp,
                                        shadowElevation = 3.dp,
                                        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Color(0xFF8B5CF6).copy(alpha = 0.6f), Color(0xFF00E5FF).copy(alpha = 0.6f))))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            BouncingDots()
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "// QUANTUM REASONING...",
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                fontSize = 11.5.sp,
                                                color = Color(0xFF00E5FF),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Cyber Command Deck Bar
            // [ // give command                 🎤 ]     ( ☰ )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val capsuleBg = if (isDark) Color(0xFF0E131F) else Color(0xFFEFF3FA)

                // Left: Command Console / Gemini Live Capsule
                Surface(
                    modifier = Modifier.weight(1f),
                    color = capsuleBg,
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = if (listening) 14.dp else 4.dp,
                    border = if (listening) {
                        BorderStroke(2.dp, Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF8B5CF6), Color(0xFFD946EF), Color(0xFF00E5FF))))
                    } else {
                        BorderStroke(1.2.dp, if (isDark) Color(0x4400E5FF) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                ) {
                    if (listening) {
                        // Gemini Live Voice Listening Mode with High-Tech Frequency Wave
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                GeminiLiveWave()
                                Column {
                                    Text(
                                        text = if (liveSpeechText.isNotBlank()) liveSpeechText else tr("listening"),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (liveSpeechText.isNotBlank()) "[ VOICE STREAMING ]" else "[ GEMINI LIVE // READY ]",
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Compact Stop button
                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .clickable { toggle() },
                                shape = CircleShape,
                                color = Color(0xFFEF4444),
                                shadowElevation = 4.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        Modifier.size(19.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    } else {
                        // Modern Cyber Command Input Capsule
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GeminiSparkle(size = 19.dp)

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
                                placeholder = {
                                    Text(
                                        "give command",
                                        fontSize = 14.sp,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = if (isDark) Color.White else Color(0xFF0F172A),
                                    unfocusedTextColor = if (isDark) Color.White else Color(0xFF0F172A)
                                )
                            )

                            if (input.isNotEmpty()) {
                                IconButton(
                                    onClick = { input = "" },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "Clear",
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            val t = input.trim()
                                            input = ""
                                            handleUser(t)
                                        },
                                    shape = CircleShape,
                                    color = Color(0xFF00E5FF),
                                    shadowElevation = 3.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            "Send",
                                            Modifier.size(17.dp),
                                            tint = Color(0xFF05070D)
                                        )
                                    }
                                }
                            } else {
                                // Mic Button inside capsule
                                Surface(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .clickable { toggle() },
                                    shape = CircleShape,
                                    color = if (isDark) Color(0xFF172033) else Color(0xFFE2E8F0),
                                    border = BorderStroke(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                                    shadowElevation = 3.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Voice Input",
                                            Modifier.size(20.dp),
                                            tint = Color(0xFF00E5FF)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Right: Dedicated Circle Menu Button [ ☰ ] triggering Bottom Helm Wheel
                CompactHelmButton(
                    onClick = { showBottomWheel = true },
                    icon = Icons.Default.Menu,
                    accentColor = Color(0xFF10B981),
                    size = 48.dp
                )
            }
        }

        // Top Corner Half-Wheel Menu (Settings & Preferences)
        CornerHelmWheel(
            isExpanded = showTopWheel,
            onDismissRequest = { showTopWheel = false },
            position = WheelPosition.TOP_RIGHT,
            hubIcon = Icons.Default.Settings,
            hubLabel = "Settings",
            onHubClick = { showTopWheel = false; showSettingsModal = true },
            items = topWheelItems
        )

        // Bottom Corner Half-Wheel Menu (Quick Menu Actions)
        CornerHelmWheel(
            isExpanded = showBottomWheel,
            onDismissRequest = { showBottomWheel = false },
            position = WheelPosition.BOTTOM_RIGHT,
            hubIcon = Icons.Default.Menu,
            hubLabel = "Quick Actions",
            onHubClick = { showBottomWheel = false },
            items = bottomWheelItems
        )
    }
}
