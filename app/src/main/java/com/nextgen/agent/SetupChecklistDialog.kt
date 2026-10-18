package com.nextgen.agent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat

data class SetupItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val isCritical: Boolean = false,
    val onClickAction: () -> Unit
)

@Composable
fun MainActivity.SetupChecklistDialog(onDismiss: () -> Unit) {
    val activity = this
    val pm = remember { activity.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    fun isPermGranted(p: String): Boolean =
        ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED

    val a11yGranted = AgentAccessibilityService.isEnabled(activity)
    val notifGranted = AgentNotificationListenerService.isListenerEnabled(activity)
    val overlayGranted = Settings.canDrawOverlays(activity)
    val batteryGranted = pm?.isIgnoringBatteryOptimizations(activity.packageName) == true
    val micGranted = isPermGranted(Manifest.permission.RECORD_AUDIO)
    val contactsGranted = isPermGranted(Manifest.permission.READ_CONTACTS)
    val phoneGranted = isPermGranted(Manifest.permission.CALL_PHONE)
    val smsGranted = isPermGranted(Manifest.permission.READ_SMS)
    val btGranted = try {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        adapter != null && adapter.isEnabled
    } catch (e: Exception) {
        false
    }

    val items = listOf(
        SetupItem(
            id = "a11y",
            title = tr("sys_control"),
            description = tr("need_a11y"),
            icon = Icons.Default.Settings,
            isGranted = a11yGranted,
            isCritical = true
        ) {
            val ok = AgentAccessibilityService.autoEnable(activity)
            if (!ok && !AgentAccessibilityService.isEnabled(activity)) {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        },
        SetupItem(
            id = "overlay",
            title = "Display Over Other Apps (Overlay)",
            description = "Required for Trackpad HUD, Ball, and pointer cursor over all applications",
            icon = Icons.Default.Build,
            isGranted = overlayGranted,
            isCritical = true
        ) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        },
        SetupItem(
            id = "notif",
            title = "Notification Listener",
            description = "Reads incoming notifications, OTPs, and incoming messages hands-free",
            icon = Icons.Default.Notifications,
            isGranted = notifGranted
        ) {
            AgentNotificationListenerService.openSettings(activity)
        },
        SetupItem(
            id = "battery",
            title = "Battery Optimization Exemption",
            description = "Prevents Android OS from killing background wake-word recognition",
            icon = Icons.Default.Star,
            isGranted = batteryGranted
        ) {
            activity.requestIgnoreBatteryOptimization()
        },
        SetupItem(
            id = "mic",
            title = "Microphone",
            description = "Required for hands-free wake word, voice navigation, and commanding",
            icon = Icons.Default.PlayArrow,
            isGranted = micGranted,
            isCritical = true
        ) {
            activity.permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        },
        SetupItem(
            id = "contacts",
            title = "Contacts",
            description = "Enables resolving contact names when you say 'Call Amma' or 'Missed call John'",
            icon = Icons.Default.Person,
            isGranted = contactsGranted
        ) {
            activity.permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        },
        SetupItem(
            id = "phone",
            title = "Phone & Calls",
            description = "Autonomous direct calling and 3-ring missed call auto-cut execution",
            icon = Icons.Default.Phone,
            isGranted = phoneGranted
        ) {
            activity.permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
        },
        SetupItem(
            id = "sms",
            title = "SMS & OTP Reader",
            description = "Reads verification codes and enables automatic OTP form filling",
            icon = Icons.Default.Email,
            isGranted = smsGranted
        ) {
            activity.permLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
        },
        SetupItem(
            id = "bt",
            title = "Bluetooth Adapter",
            description = "Hardware switch control and Bluetooth headset audio routing",
            icon = Icons.Default.Share,
            isGranted = btGranted
        ) {
            try {
                activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    )

    val allGrantedCount = items.count { it.isGranted }
    val totalCount = items.size
    val progress = allGrantedCount.toFloat() / totalCount

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(26.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.List, null, tint = Indigo, modifier = Modifier.size(22.dp))
                            }
                        }
                        Column {
                            Text(
                                text = tr("setup_checklist"),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$allGrantedCount / $totalCount permissions ready",
                                fontSize = 12.sp,
                                color = if (allGrantedCount == totalCount) Color(0xFF10B981) else Amber
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (progress == 1f) Color(0xFF10B981) else Indigo,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(Modifier.height(14.dp))

                // Android 13+ Restricted Settings Warning Card
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !a11yGranted) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Amber.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Amber.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
                                Text(
                                    text = tr("restricted_guide_title"),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Amber
                                )
                            }
                            Text(
                                text = tr("restricted_guide_desc"),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", activity.packageName, null)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        activity.startActivity(intent)
                                    } catch (e: Exception) {
                                        activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Build, null, Modifier.size(16.dp), tint = Color.Black)
                                Spacer(Modifier.width(6.dp))
                                Text(tr("open_app_info"), fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Checklist Items Scrollable List
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                1.dp,
                                if (item.isGranted) Color(0xFF10B981).copy(alpha = 0.35f)
                                else if (item.isCritical) Color(0xFFEF4444).copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { item.onClickAction() }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (item.isGranted) Color(0xFF10B981).copy(alpha = 0.15f)
                                    else if (item.isCritical) Color(0xFFEF4444).copy(alpha = 0.15f)
                                    else Indigo.copy(alpha = 0.12f),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            item.icon,
                                            null,
                                            tint = if (item.isGranted) Color(0xFF10B981)
                                            else if (item.isCritical) Color(0xFFEF4444)
                                            else Indigo,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (item.isCritical) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFFEF4444).copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = "REQUIRED",
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFEF4444),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = item.description,
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (item.isGranted) Color(0xFF10B981).copy(alpha = 0.12f)
                                    else Color(0xFFEF4444).copy(alpha = 0.12f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (item.isGranted) Color(0xFF10B981).copy(alpha = 0.4f)
                                        else Color(0xFFEF4444).copy(alpha = 0.4f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            null,
                                            tint = if (item.isGranted) Color(0xFF10B981) else Color(0xFFEF4444),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = if (item.isGranted) tr("perm_granted") else tr("perm_missing"),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.isGranted) Color(0xFF10B981) else Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tr("continue"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
