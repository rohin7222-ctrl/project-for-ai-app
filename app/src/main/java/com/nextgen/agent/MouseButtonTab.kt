package com.nextgen.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings Tab: Mouse & Buttons Composable UI
 * Provides full user customization for:
 * - Precision Trackpad with 4-Way Directional Controls (◀, ▲, ▼, ▶)
 * - 2-Finger Multi-Touch Scroll & Dedicated Right-Edge Scroll Strip
 * - Pointer Speed & Sensitivity
 * - Controller Scale (HUD Size)
 * - Controller Opacity / Transparency
 * - Cursor Theme (Color & Pointer Size)
 * - Haptic Vibration Feedback
 * - Accessibility & Overlay Permissions
 */
/**
 * Real-time animated canvas preview of the native mouse cursor arrow
 */
@Composable
fun CursorPreview(
    scale: Float,
    colorName: String,
    modifier: Modifier = Modifier
) {
    val cursorColor = when (colorName.lowercase()) {
        "cyan" -> Color(0xFF00E5FF)
        "green" -> Color(0xFF10B981)
        "black" -> Color(0xFF0F172A)
        else -> Color.White
    }
    val outlineColor = if (colorName.lowercase() == "black") Color.White else Color(0xFF111622)

    Box(
        modifier = modifier
            .size(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF090D1A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(44.dp)) {
            val s = density * 0.95f * scale.coerceIn(0.5f, 2.2f)
            val path = Path().apply {
                moveTo(2f, 2f)
                lineTo(2f, 2f + 18.5f * s)
                lineTo(2f + 4.6f * s, 2f + 14.8f * s)
                lineTo(2f + 7.6f * s, 2f + 21.8f * s)
                lineTo(2f + 10.2f * s, 2f + 20.6f * s)
                lineTo(2f + 7.2f * s, 2f + 13.8f * s)
                lineTo(2f + 12.8f * s, 2f + 13.8f * s)
                close()
            }
            // Shadow
            val shadowPath = Path().apply {
                addPath(path, Offset(2.5f, 3.5f))
            }
            drawPath(shadowPath, color = Color.Black.copy(alpha = 0.5f))
            // Body
            drawPath(path, color = cursorColor)
            // Outline
            drawPath(path, color = outlineColor, style = Stroke(width = 1.3f * density))
        }
    }
}

@Composable
fun MouseButtonsTabContent(
    cfg: Config,
    upd: ((Config) -> Config) -> Unit,
    activity: MainActivity
) {
    val mouseManager = remember { MouseButtonManager.getInstance(activity) }
    var overlayGranted by remember { mutableStateOf(mouseManager.canDrawOverlays()) }
    var a11yActive by remember { mutableStateOf(AgentAccessibilityService.isEnabled(activity)) }

    // Colors
    val cyan = Color(0xFF00E5FF)
    val indigo = Color(0xFF6366F1)
    val amber = Color(0xFFF59E0B)
    val emerald = Color(0xFF10B981)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // 1. HEADER CARD
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cyan.copy(alpha = 0.15f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.TouchApp, null, tint = cyan, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = S.t(cfg.lang, "mouse_tab_title"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = S.t(cfg.lang, "mouse_tab_sub"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 2. FLOATING BALL TOGGLE CARD
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = indigo.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_logo),
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(24.dp).clip(CircleShape)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = S.t(cfg.lang, "mouse_floating_ball"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = S.t(cfg.lang, "mouse_floating_ball_sub"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                    Switch(
                        checked = cfg.mouseBallEnabled,
                        onCheckedChange = { checked ->
                            if (checked && !mouseManager.canDrawOverlays()) {
                                overlayGranted = false
                                mouseManager.setBallEnabled(true, activity)
                            } else {
                                upd { it.copy(mouseBallEnabled = checked) }
                                mouseManager.setBallEnabled(checked, activity)
                                overlayGranted = mouseManager.canDrawOverlays()
                            }
                        }
                    )
                }
            }
        }

        // 3. PRECISION TRACKPAD & 4-WAY D-PAD OVERVIEW
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.t(cfg.lang, "mouse_mode_title"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = S.t(cfg.lang, "mouse_mode_sub"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cyan.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, cyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "⚡ ACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = cyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // 6 Feature Pills Grid
                val trackpadFeatures = listOf(
                    "🖱️ 1:1 Laptop Glide" to "Sub-pixel precision ballistics with flick inertia",
                    "🧭 4-Way D-Pad" to "◀, ▲, ▼, ▶ buttons (Hold down for auto-repeat scroll)",
                    "📜 Scroll Strip" to "Dedicated right-edge vertical scroll slider strip",
                    "✌️ 2-Finger Scroll" to "Slide 2 fingers anywhere on the pad to scroll pages",
                    "✋ Drag & Drop Lock" to "Lock target item & glide to destination with DROP",
                    "📐 In-HUD Quick Resize" to "Tap [ ⤢ ] inside the trackpad header to cycle size"
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trackpadFeatures.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { (title, desc) ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = cyan)
                                        Text(
                                            desc,
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. BALL SIZE CUSTOMIZATION CARD
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.t(cfg.lang, "mouse_ball_size"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = S.t(cfg.lang, "mouse_ball_size_sub"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cyan.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, cyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${cfg.mouseBallSize} dp",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = cyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Interactive Live Size Preview & Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier.size(70.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val animatedSize by animateFloatAsState(targetValue = cfg.mouseBallSize.toFloat(), label = "previewSize")
                        Box(
                            modifier = Modifier
                                .size(animatedSize.dp.coerceIn(36.dp, 64.dp))
                                .clip(CircleShape)
                                .background(Color(0xFF0B0F19))
                                .border(2.dp, cyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "Preview",
                                modifier = Modifier.fillMaxSize(0.75f).clip(CircleShape)
                            )
                        }
                    }

                    Slider(
                        value = cfg.mouseBallSize.toFloat(),
                        onValueChange = { newSize ->
                            val s = newSize.toInt()
                            upd { it.copy(mouseBallSize = s) }
                            mouseManager.setBallSize(s)
                        },
                        valueRange = 36f..84f,
                        steps = 11,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Quick Presets (Small, Medium, Large)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        40 to S.t(cfg.lang, "mouse_preset_small"),
                        52 to S.t(cfg.lang, "mouse_preset_med"),
                        68 to S.t(cfg.lang, "mouse_preset_large")
                    )
                    presets.forEach { (size, label) ->
                        val isSel = cfg.mouseBallSize == size
                        OutlinedButton(
                            onClick = {
                                upd { it.copy(mouseBallSize = size) }
                                mouseManager.setBallSize(size)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSel) cyan.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            border = BorderStroke(1.dp, if (isSel) cyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) cyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 5. CURSOR SPEED & SENSITIVITY CARD
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.t(cfg.lang, "mouse_speed"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = S.t(cfg.lang, "mouse_speed_sub"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = indigo.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, indigo.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = String.format("%.1fx", cfg.mouseSpeed),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = indigo,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Slider(
                    value = cfg.mouseSpeed,
                    onValueChange = { spd ->
                        upd { it.copy(mouseSpeed = spd) }
                        mouseManager.setPointerSpeed(spd)
                    },
                    valueRange = 0.5f..2.5f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 6. TRACKPAD HUD SIZE & OPACITY CUSTOMIZATION
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Scale / Dimensions Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.t(cfg.lang, "mouse_pad_scale"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = S.t(cfg.lang, "mouse_pad_scale_sub"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cyan.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, cyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${(248 * cfg.mousePadScale).toInt()} × ${(250 * cfg.mousePadScale).toInt()} dp",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = cyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Slider(
                    value = cfg.mousePadScale,
                    onValueChange = { scale ->
                        upd { it.copy(mousePadScale = scale) }
                        mouseManager.setPadScale(scale)
                    },
                    valueRange = 0.70f..1.50f,
                    modifier = Modifier.fillMaxWidth()
                )

                // 4 Presets: Compact, Standard, Large, Ultra
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val scalePresets = listOf(
                        0.85f to "Compact (85%)",
                        1.00f to "Standard (100%)",
                        1.20f to "Large (120%)",
                        1.40f to "Ultra (140%)"
                    )
                    scalePresets.forEach { (scale, label) ->
                        val isSel = kotlin.math.abs(cfg.mousePadScale - scale) < 0.05f
                        OutlinedButton(
                            onClick = {
                                upd { it.copy(mousePadScale = scale) }
                                mouseManager.setPadScale(scale)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSel) cyan.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            border = BorderStroke(1.dp, if (isSel) cyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) cyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                // Opacity / Transparency
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.t(cfg.lang, "mouse_pad_alpha"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = S.t(cfg.lang, "mouse_pad_alpha_sub"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = amber.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, amber.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${(cfg.mousePadAlpha * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = amber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Slider(
                    value = cfg.mousePadAlpha,
                    onValueChange = { alpha ->
                        upd { it.copy(mousePadAlpha = alpha) }
                        mouseManager.setPadAlpha(alpha)
                    },
                    valueRange = 0.45f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 7. CURSOR STYLE & THEME CUSTOMIZATION
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = S.t(cfg.lang, "mouse_pointer_color"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                // Cursor Color Themes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val colors = listOf(
                        Triple("white", "Classic", Color.White),
                        Triple("cyan", "Cyber", cyan),
                        Triple("green", "Neon", emerald),
                        Triple("black", "Stealth", Color(0xFF1E293B))
                    )
                    colors.forEach { (key, name, dotCol) ->
                        val isSel = cfg.mousePointerColor.equals(key, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) dotCol.copy(alpha = 0.18f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isSel) dotCol else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    upd { it.copy(mousePointerColor = key) }
                                    mouseManager.setPointerColor(key)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotCol)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                // Cursor Pointer Size Slider & Live Preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = S.t(cfg.lang, "mouse_pointer_size"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Control arrow pointer scale & hotspot tip size",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cyan.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, cyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = String.format("%.2fx", cfg.mousePointerSize),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = cyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Live Animated Cursor Preview
                    CursorPreview(scale = cfg.mousePointerSize, colorName = cfg.mousePointerColor)

                    Slider(
                        value = cfg.mousePointerSize,
                        onValueChange = { sz ->
                            upd { it.copy(mousePointerSize = sz) }
                            mouseManager.setPointerScale(sz)
                        },
                        valueRange = 0.50f..2.20f,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4 Presets (S, M, L, XL)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val pointerPresets = listOf(
                        0.75f to "Small (0.75x)",
                        1.00f to "Medium (1.0x)",
                        1.40f to "Large (1.4x)",
                        1.80f to "XL (1.8x)"
                    )
                    pointerPresets.forEach { (sz, label) ->
                        val isSel = kotlin.math.abs(cfg.mousePointerSize - sz) < 0.08f
                        OutlinedButton(
                            onClick = {
                                upd { it.copy(mousePointerSize = sz) }
                                mouseManager.setPointerScale(sz)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSel) cyan.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            border = BorderStroke(1.dp, if (isSel) cyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Text(
                                label,
                                fontSize = 10.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) cyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 8. HAPTIC FEEDBACK TOGGLE
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Vibration, null, tint = cyan, modifier = Modifier.size(24.dp))
                    Column {
                        Text(
                            text = S.t(cfg.lang, "mouse_haptics_label"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = S.t(cfg.lang, "mouse_haptics_sub"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
                Switch(
                    checked = cfg.mouseHaptics,
                    onCheckedChange = { checked ->
                        upd { it.copy(mouseHaptics = checked) }
                        mouseManager.setHapticsEnabled(checked)
                    }
                )
            }
        }

        // 9. PERMISSION CARDS (If required)
        if (!overlayGranted || !a11yActive) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = amber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, amber.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "⚡ Permissions Required",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = amber
                    )

                    // Overlay permission
                    if (!overlayGranted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = S.t(cfg.lang, "mouse_perm_overlay"),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + activity.packageName)
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    activity.startActivity(intent)
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(S.t(cfg.lang, "mouse_grant_perm"), fontSize = 11.sp)
                            }
                        }
                    }

                    // Accessibility permission
                    if (!a11yActive) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = S.t(cfg.lang, "mouse_perm_a11y"),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val ok = AgentAccessibilityService.autoEnable(activity)
                                    a11yActive = AgentAccessibilityService.isEnabled(activity)
                                    if (!ok) {
                                        activity.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(S.t(cfg.lang, "mouse_grant_perm"), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 10. QUICK GUIDE / INSTRUCTIONS
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = S.t(cfg.lang, "mouse_guide_title"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = cyan
                )
                listOf(
                    S.t(cfg.lang, "mouse_step1"),
                    S.t(cfg.lang, "mouse_step2"),
                    S.t(cfg.lang, "mouse_step3"),
                    S.t(cfg.lang, "mouse_step4"),
                    S.t(cfg.lang, "mouse_step5"),
                    S.t(cfg.lang, "mouse_step6")
                ).forEach { step ->
                    Text(
                        text = step,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
