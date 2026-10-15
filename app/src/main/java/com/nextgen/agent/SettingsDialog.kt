package com.nextgen.agent

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.nextgen.agent.shortcut.VoiceShortcut
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun MainActivity.SettingsDialog(onDismiss: () -> Unit) {
    val activity = this
    var isTraining by remember { mutableStateOf(false) }
    var trainProgress by remember { mutableStateOf(0f) }
    var trainSecondsLeft by remember { mutableStateOf(10) }
    var a11yActive by remember { mutableStateOf(AgentAccessibilityService.isEnabled(activity)) }
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
                                                Toast.makeText(activity, tr("shortcut_fill_err"), Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            if (shortcutManager.isReservedCommand(c) || shortcutManager.isReservedCommand(w)) {
                                                Toast.makeText(activity, tr("shortcut_reserved_err"), Toast.LENGTH_LONG).show()
                                                return@Button
                                            }
                                            val res = shortcutManager.addShortcut(w, c)
                                            if (res.isSuccess) {
                                                shortcutList = shortcutManager.getShortcuts()
                                                inputWord = ""
                                                inputCmd = ""
                                                Toast.makeText(activity, tr("shortcut_added_success"), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(activity, res.exceptionOrNull()?.message ?: tr("error"), Toast.LENGTH_SHORT).show()
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
                                                            Toast.makeText(activity, tr("shortcut_deleted"), Toast.LENGTH_SHORT).show()
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
                                            val ok = AgentAccessibilityService.autoEnable(activity)
                                            a11yActive = AgentAccessibilityService.isEnabled(activity)
                                            if (ok || a11yActive) {
                                                Toast.makeText(activity, tr("a11y_active"), Toast.LENGTH_SHORT).show()
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
                                                    data = android.net.Uri.fromParts("package", activity.packageName, null)
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
                                                activity.lifecycleScope.launch {
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
                                                        Toast.makeText(activity, tr("train_success"), Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(activity, tr("train_failed"), Toast.LENGTH_LONG).show()
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
