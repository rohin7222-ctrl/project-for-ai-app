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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.CameraAlt
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
            id = "clear_chat",
            label = tr("clear_chat"),
            icon = Icons.Default.DeleteSweep,
            accentColor = Color(0xFFEF4444),
            onClick = { showClearDialog = true }
        ),
        WheelItem(
            id = "raise_to_ear",
            label = tr("raise_to_ear_wheel"),
            icon = Icons.Default.PhoneInTalk,
            accentColor = if (cfg.raiseToAnswer) Color(0xFF10B981) else Color(0xFF94A3B8),
            onClick = {
                val next = !cfg.raiseToAnswer
                upd { it.copy(raiseToAnswer = next) }
                val msg = if (next) {
                    if (cfg.lang == "ta-IN") "காதுக்கு கொண்டுவா இயக்கம்: ஆன்" else "Raise to Ear Answer: ON"
                } else {
                    if (cfg.lang == "ta-IN") "காதுக்கு கொண்டுவா இயக்கம்: ஆஃப்" else "Raise to Ear Answer: OFF"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        ),
        WheelItem(
            id = "theme_toggle",
            label = if (isDark) "Light Mode" else "Dark Mode",
            icon = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
            accentColor = Amber,
            onClick = { upd { it.copy(dark = !isDark, darkCustom = true) } }
        ),
        WheelItem(
            id = "lang_switch",
            label = tr("app_lang"),
            icon = Icons.Default.Language,
            accentColor = Cyan,
            onClick = { cycleLanguage() }
        ),
        WheelItem(
            id = "brain_config",
            label = tr("ai_model"),
            icon = Icons.Default.Bolt,
            accentColor = Indigo,
            onClick = { changeModel = true; screen = Screen.BRAIN }
        ),
        WheelItem(
            id = "name_config",
            label = tr("name_title"),
            icon = Icons.Default.Edit,
            accentColor = Violet,
            onClick = { screen = Screen.NAME }
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
            id = "accessibility",
            label = "Accessibility",
            icon = Icons.Default.AdminPanelSettings,
            accentColor = Color(0xFFF97316),
            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
            id = "quick_bluetooth",
            label = tr("chip_bt"),
            icon = Icons.Default.Bluetooth,
            accentColor = Color(0xFF3B82F6),
            onClick = { toggleBluetooth() }
        ),
        WheelItem(
            id = "quick_torch",
            label = tr("chip_torch"),
            icon = Icons.Default.FlashlightOn,
            accentColor = Amber,
            onClick = { toggleTorch() }
        ),
        WheelItem(
            id = "quick_alarm",
            label = tr("chip_alarm"),
            icon = Icons.Default.Alarm,
            accentColor = Color(0xFFEC4899),
            onClick = { quickAlarm() }
        ),
        WheelItem(
            id = "quick_whatsapp",
            label = tr("chip_whatsapp"),
            icon = Icons.AutoMirrored.Filled.Chat,
            accentColor = Color(0xFF22C55E),
            onClick = { quickWhatsApp() }
        ),
        WheelItem(
            id = "quick_wifi",
            label = tr("chip_wifi"),
            icon = Icons.Default.Wifi,
            accentColor = Cyan,
            onClick = { quickWifi() }
        ),
        WheelItem(
            id = "quick_screenshot",
            label = tr("chip_screenshot"),
            icon = Icons.Default.PhotoCamera,
            accentColor = Violet,
            onClick = { quickScreenshot() }
        ),
        WheelItem(
            id = "quick_volume",
            label = "Volume",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            accentColor = Color(0xFF06B6D4),
            onClick = { quickVolume() }
        ),
        WheelItem(
            id = "quick_youtube",
            label = tr("chip_yt"),
            icon = Icons.Default.PlayCircle,
            accentColor = Color(0xFFEF4444),
            onClick = { quickYouTube() }
        ),
        WheelItem(
            id = "quick_instagram",
            label = tr("chip_insta"),
            icon = Icons.Default.CameraAlt,
            accentColor = Color(0xFFE1306C),
            onClick = { quickInstagram() }
        ),
        WheelItem(
            id = "quick_hotspot",
            label = tr("chip_hotspot"),
            icon = Icons.Default.WifiTethering,
            accentColor = Color(0xFFF59E0B),
            onClick = { quickHotspot() }
        ),
        WheelItem(
            id = "quick_google",
            label = tr("chip_google"),
            icon = Icons.Default.Search,
            accentColor = Color(0xFF4285F4),
            onClick = { quickGoogleSearch() }
        )
    )

    Box(Modifier.fillMaxSize()) {
        // Futuristic Ambient Atmosphere Orbs
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Top-right ambient orb
            drawCircle(
                brush = Brush.radialGradient(
                    listOf((if (isDark) Color(0xFF4F46E5) else Color(0xFF818CF8)).copy(alpha = if (isDark) 0.14f else 0.08f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.12f),
                    radius = w * 0.65f
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.12f),
                radius = w * 0.65f
            )
            // Bottom-left ambient orb
            drawCircle(
                brush = Brush.radialGradient(
                    listOf((if (isDark) Color(0xFF06B6D4) else Color(0xFF38BDF8)).copy(alpha = if (isDark) 0.12f else 0.07f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.88f),
                    radius = w * 0.65f
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.88f),
                radius = w * 0.65f
            )
        }

        Column(Modifier.fillMaxSize()) {
            // Enhanced Top Bar Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Pill Badge matching wireframe [ name ]
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        border = BorderStroke(1.5.dp, Brush.horizontalGradient(listOf(Indigo, Violet, Cyan))),
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { screen = Screen.NAME }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dotColor = when {
                                isInPhoneCall || isMediaPlayingState -> Amber
                                executingAction.isNotBlank() -> Amber
                                thinking -> Violet
                                listening -> Cyan
                                else -> Color(0xFF10B981)
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = cfg.name.ifBlank { "NextGen AI" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            GeminiSparkle(size = 15.dp)
                        }
                    }

                    // Right: Top Helm Wheel Button
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
                            accentColor = Indigo,
                            size = 46.dp
                        )
                    }
                }
            }

            // Middle Content: Logo vs Chat Feed
            // "chat panna start pannita antha center la iruka pora app logo disappear agidanum."
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.isEmpty()) {
                    // Center Hero App Logo (Visible only before chatting)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val logoPulse by rememberInfiniteTransition(label = "logoPulse").animateFloat(
                            initialValue = 1f, targetValue = 1.05f,
                            animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                            label = "lp"
                        )
                        val ringRotate by rememberInfiniteTransition(label = "ringRotate").animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart),
                            label = "rr"
                        )
                        val counterRotate by rememberInfiniteTransition(label = "counterRotate").animateFloat(
                            initialValue = 360f, targetValue = 0f,
                            animationSpec = infiniteRepeatable(tween(32000, easing = LinearEasing), RepeatMode.Restart),
                            label = "cr"
                        )

                        // Circular logo area matching wireframe "logo"
                        Box(contentAlignment = Alignment.Center) {
                            // Outer Astrolabe Orbit Ring Canvas
                            Canvas(modifier = Modifier.size(196.dp)) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val orbitR = cx - 6.dp.toPx()

                                // Soft ambient cosmic orbit ring
                                drawCircle(
                                    color = if (isDark) GeminiBlue.copy(alpha = 0.22f) else GeminiBlue.copy(alpha = 0.14f),
                                    radius = orbitR,
                                    style = Stroke(width = 1.4.dp.toPx())
                                )

                                // 12 Astrolabe Compass Notches
                                for (step in 0 until 12) {
                                    val angleRad = Math.toRadians((step * 30.0 + ringRotate)).toFloat()
                                    val cosA = cos(angleRad)
                                    val sinA = sin(angleRad)
                                    val tickLen = if (step % 3 == 0) 6.dp.toPx() else 3.5.dp.toPx()
                                    val startX = cx + (orbitR - tickLen) * cosA
                                    val startY = cy + (orbitR - tickLen) * sinA
                                    val endX = cx + (orbitR + tickLen) * cosA
                                    val endY = cy + (orbitR + tickLen) * sinA
                                    drawLine(
                                        color = if (step % 3 == 0) GeminiPurple.copy(alpha = 0.70f) else Cyan.copy(alpha = 0.40f),
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY),
                                        strokeWidth = 1.6.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }

                                // Satellite Glowing Orbiting Particle
                                val dotRad = Math.toRadians(counterRotate.toDouble()).toFloat()
                                val dotX = cx + orbitR * cos(dotRad)
                                val dotY = cy + orbitR * sin(dotRad)
                                drawCircle(
                                    color = Cyan.copy(alpha = 0.25f),
                                    radius = 8.dp.toPx(),
                                    center = Offset(dotX, dotY)
                                )
                                drawCircle(
                                    color = Cyan.copy(alpha = 0.90f),
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(dotX, dotY)
                                )
                            }

                            // Pulsing ambient glow
                            Box(
                                Modifier
                                    .size(164.dp)
                                    .scale(logoPulse)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                GeminiBlue.copy(alpha = 0.38f),
                                                GeminiPurple.copy(alpha = 0.24f),
                                                Cyan.copy(alpha = 0.12f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )

                            // Outer ring card
                            Surface(
                                modifier = Modifier.size(142.dp),
                                shape = CircleShape,
                                shadowElevation = 18.dp,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(3.dp, GeminiAuraGradient)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.app_logo),
                                        contentDescription = "NextGen AI Logo",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(22.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("hello").format(cfg.name),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            GeminiSparkle(size = 22.dp)
                        }

                        Spacer(Modifier.height(10.dp))

                        // Status Badge
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (cfg.autoWakeEnabled) Color(0xFF10B981).copy(alpha = 0.12f) else GeminiBlue.copy(alpha = 0.10f),
                            border = BorderStroke(
                                1.dp,
                                if (cfg.autoWakeEnabled) Color(0xFF10B981).copy(alpha = 0.38f) else GeminiBlue.copy(alpha = 0.28f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (cfg.autoWakeEnabled) Color(0xFF10B981) else Color(0xFF38BDF8))
                                )
                                Text(
                                    text = if (cfg.autoWakeEnabled) "Wake Word Ready: \"${cfg.commandingWord}\"" else "NextGen AI Assistant Ready",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (cfg.autoWakeEnabled) Color(0xFF10B981) else if (isDark) Color(0xFF7DD3FC) else Color(0xFF0284C7)
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = tr("welcome_sub"),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(horizontal = 12.dp)
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
                                        border = BorderStroke(1.dp, GeminiPurple.copy(alpha = 0.5f)),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            GeminiSparkle(size = 18.dp)
                                        }
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
                                                        Toast.makeText(activity, tr("copy_done"), Toast.LENGTH_SHORT).show()
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
                                        border = BorderStroke(1.dp, GeminiPurple.copy(alpha = 0.5f)),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            GeminiSparkle(size = 18.dp)
                                        }
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

            // Bottom Bar matching user wireframe:
            // [ give command                   🎤 ]     ( ☰ )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val capsuleBg = if (isDark) Color(0xFF1E1F24) else Color(0xFFF0F4F9)

                // Left: Command / Gemini Live Capsule
                Surface(
                    modifier = Modifier.weight(1f),
                    color = capsuleBg,
                    shape = RoundedCornerShape(32.dp),
                    shadowElevation = if (listening) 12.dp else 4.dp,
                    border = if (listening) {
                        BorderStroke(2.dp, GeminiAuraGradient)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                ) {
                    if (listening) {
                        // Gemini Live Voice Listening Mode
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
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (liveSpeechText.isNotBlank()) "Recognizing voice..." else "Gemini Live",
                                        fontSize = 11.sp,
                                        color = GeminiCyan,
                                        fontWeight = FontWeight.SemiBold
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
                        // Modern Command Capsule: Text + Clear/Send + Mic
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GeminiSparkle(size = 20.dp)

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
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
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
                                    color = GeminiBlue,
                                    shadowElevation = 3.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            "Send",
                                            Modifier.size(18.dp),
                                            tint = Color.White
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
                                    color = if (isDark) Color(0xFF2B2D35) else Color(0xFFE2E7EF),
                                    border = BorderStroke(1.dp, GeminiPurple.copy(alpha = 0.4f)),
                                    shadowElevation = 2.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Voice Input",
                                            Modifier.size(20.dp),
                                            tint = GeminiBlue
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
