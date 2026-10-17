package com.nextgen.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings Tab 4: Mouse & Buttons Composable UI
 * Handles floating ball toggle, size customizer with live preview, cursor speed,
 * overlay and accessibility permissions, and interactive instructions.
 */
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

        // 3. BALL SIZE CUSTOMIZATION CARD
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
                    // Visual Preview Circle
                    Box(
                        modifier = Modifier
                            .size(70.dp),
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

                    // Slider
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

        // 4. CURSOR SPEED CARD
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

        // 5. PERMISSION CARDS (If required)
        if (!overlayGranted || !a11yActive) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "⚡ Permissions Required",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B)
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

        // 6. QUICK GUIDE / INSTRUCTIONS
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
                    S.t(cfg.lang, "mouse_step5")
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
