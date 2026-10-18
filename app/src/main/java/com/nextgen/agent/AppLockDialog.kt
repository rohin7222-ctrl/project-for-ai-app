package com.nextgen.agent

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.security.MessageDigest

object SecurityUtils {
    @Volatile
    var lockoutUntilMillis: Long = 0L

    fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isLockedOut(): Boolean = SystemClock.elapsedRealtime() < lockoutUntilMillis

    fun getRemainingLockoutSeconds(): Int {
        val rem = lockoutUntilMillis - SystemClock.elapsedRealtime()
        return if (rem > 0) (rem / 1000).toInt() + 1 else 0
    }

    fun recordFailedAttempt() {
        Store.failedBiometricAttempts++
        if (Store.failedBiometricAttempts >= 5) {
            lockoutUntilMillis = SystemClock.elapsedRealtime() + 30_000L
        }
    }
}

enum class PinMode {
    UNLOCK,
    SET_NEW,
    CONFIRM_NEW
}

@Composable
fun MainActivity.AppLockDialog(
    mode: PinMode = PinMode.UNLOCK,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val activity = this
    var currentPin by remember { mutableStateOf("") }
    var firstEnteredPin by remember { mutableStateOf("") }
    var currentMode by remember { mutableStateOf(mode) }
    var errorMessage by remember { mutableStateOf("") }
    var lockoutSeconds by remember { mutableStateOf(SecurityUtils.getRemainingLockoutSeconds()) }

    LaunchedEffect(lockoutSeconds) {
        while (lockoutSeconds > 0) {
            delay(1000)
            lockoutSeconds = SecurityUtils.getRemainingLockoutSeconds()
        }
    }

    fun handlePinComplete(entered: String) {
        if (SecurityUtils.isLockedOut()) {
            errorMessage = "Locked out. Wait $lockoutSeconds seconds."
            currentPin = ""
            return
        }

        when (currentMode) {
            PinMode.UNLOCK -> {
                val inputHash = SecurityUtils.hashPin(entered)
                if (inputHash == cfg.appLockPinHash || cfg.appLockPinHash.isEmpty()) {
                    Store.resetBiometrics()
                    onSuccess()
                    onDismiss()
                } else {
                    SecurityUtils.recordFailedAttempt()
                    lockoutSeconds = SecurityUtils.getRemainingLockoutSeconds()
                    if (lockoutSeconds > 0) {
                        errorMessage = "Too many failed attempts. Locked out for 30s."
                    } else {
                        errorMessage = tr("pin_incorrect")
                    }
                    currentPin = ""
                }
            }
            PinMode.SET_NEW -> {
                firstEnteredPin = entered
                currentPin = ""
                currentMode = PinMode.CONFIRM_NEW
                errorMessage = ""
            }
            PinMode.CONFIRM_NEW -> {
                if (entered == firstEnteredPin) {
                    val hash = SecurityUtils.hashPin(entered)
                    upd { it.copy(appLockPinHash = hash, appLockEnabled = true) }
                    Toast.makeText(activity, "PIN updated successfully", Toast.LENGTH_SHORT).show()
                    onSuccess()
                    onDismiss()
                } else {
                    errorMessage = tr("pin_mismatch")
                    currentPin = ""
                    firstEnteredPin = ""
                    currentMode = PinMode.SET_NEW
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(28.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top close & icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Indigo.copy(alpha = 0.15f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                tint = Indigo,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                // Title & subtitle
                Text(
                    text = when (currentMode) {
                        PinMode.UNLOCK -> tr("enter_pin")
                        PinMode.SET_NEW -> tr("set_pin")
                        PinMode.CONFIRM_NEW -> tr("confirm_pin")
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        fontSize = 12.5.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (lockoutSeconds > 0) {
                    Text(
                        text = "Cooldown: $lockoutSeconds seconds remaining",
                        fontSize = 12.sp,
                        color = Amber,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 4-Dot PIN Indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < currentPin.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isFilled) Indigo
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .then(
                                    if (!isFilled) Modifier.background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    ) else Modifier
                                )
                        )
                    }
                }

                // Custom Numpad (0-9 + Backspace)
                val isBlocked = lockoutSeconds > 0
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "DEL")
                    )
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { digit ->
                                if (digit.isEmpty()) {
                                    Spacer(Modifier.size(64.dp))
                                } else if (digit == "DEL") {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = currentPin.isNotEmpty() && !isBlocked) {
                                                if (currentPin.isNotEmpty()) {
                                                    currentPin = currentPin.dropLast(1)
                                                    errorMessage = ""
                                                }
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Backspace,
                                                "Delete",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = !isBlocked) {
                                                if (currentPin.length < 4) {
                                                    val newPin = currentPin + digit
                                                    currentPin = newPin
                                                    errorMessage = ""
                                                    if (newPin.length == 4) {
                                                        handlePinComplete(newPin)
                                                    }
                                                }
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = digit,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
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
}
