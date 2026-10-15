package com.nextgen.agent

import android.Manifest
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            quadraticBezierTo(cx, cy, w, cy)
            quadraticBezierTo(cx, cy, cx, h)
            quadraticBezierTo(cx, cy, 0f, cy)
            quadraticBezierTo(cx, cy, cx, 0f)
            close()
        }
        drawPath(path, GeminiGradient)
    }
}

@Composable
internal fun GeminiLiveWave() {
    val inf = rememberInfiniteTransition(label = "gemini_wave")
    val h1 by inf.animateFloat(4f, 18f, infiniteRepeatable(tween(300, 0, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh1")
    val h2 by inf.animateFloat(6f, 26f, infiniteRepeatable(tween(340, 60, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh2")
    val h3 by inf.animateFloat(8f, 30f, infiniteRepeatable(tween(280, 120, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh3")
    val h4 by inf.animateFloat(10f, 34f, infiniteRepeatable(tween(320, 180, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh4")
    val h5 by inf.animateFloat(8f, 28f, infiniteRepeatable(tween(360, 240, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh5")
    val h6 by inf.animateFloat(6f, 22f, infiniteRepeatable(tween(290, 100, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh6")
    val h7 by inf.animateFloat(4f, 16f, infiniteRepeatable(tween(310, 40, FastOutSlowInEasing), RepeatMode.Reverse), label = "gh7")

    val colors = listOf(GeminiBlue, GeminiPurple, GeminiCyan, GeminiPink, GeminiCyan, GeminiPurple, GeminiBlue)
    val heights = listOf(h1, h2, h3, h4, h5, h6, h7)

    Row(
        modifier = Modifier.height(34.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(3.dp)
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
                        GeminiSparkle(size = 18.dp)
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
                                .background(Brush.radialGradient(listOf(GeminiBlue.copy(alpha = 0.28f), GeminiPurple.copy(alpha = 0.18f), Color.Transparent)))
                        )
                        Surface(
                            modifier = Modifier.size(110.dp),
                            shape = RoundedCornerShape(36.dp),
                            shadowElevation = 16.dp,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(2.dp, GeminiGradient)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                GeminiSparkle(size = 54.dp)
                            }
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

        // Gemini Floating Prompt Capsule & Voice Control Bar
        val isDarkTheme = if (cfg.darkCustom) cfg.dark else isSystemInDarkTheme()
        val capsuleBg = if (isDarkTheme) Color(0xFF1E1F20) else Color(0xFFF0F4F9)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = capsuleBg,
            shape = RoundedCornerShape(30.dp),
            shadowElevation = if (listening) 10.dp else 3.dp,
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
                                text = tr("listening"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Gemini Live",
                                fontSize = 11.sp,
                                color = GeminiCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Compact modern Stop button (Gemini red circle, 40.dp)
                    Surface(
                        modifier = Modifier
                            .size(40.dp)
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
                                Modifier.size(20.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            } else {
                // Gemini Modern Capsule: Text & Mic
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
                                tr("type_here"),
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

                        // Send Button
                        Surface(
                            modifier = Modifier
                                .size(38.dp)
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
                    }

                    // Compact Gemini Mic Button (40.dp)
                    Surface(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { toggle() },
                        shape = CircleShape,
                        color = if (isDarkTheme) Color(0xFF2B2D30) else Color(0xFFE2E7EF),
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
}
