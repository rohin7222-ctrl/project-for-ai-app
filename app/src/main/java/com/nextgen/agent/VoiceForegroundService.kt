package com.nextgen.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service for keeping the AI Assistant active in background and when screen is off.
 * Uses FOREGROUND_SERVICE_MICROPHONE for Android 14+ compliance.
 */
class VoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_foreground_channel"
        const val NOTIF_ID = 2001
        const val ACTION_START = "com.nextgen.agent.action.START_FOREGROUND"
        const val ACTION_STOP = "com.nextgen.agent.action.STOP_FOREGROUND"

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            val store = Store(ctx)
            if (!store.isReady()) return
            val intent = Intent(ctx, VoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("VoiceForegroundService", "Failed to start foreground service", e)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                ctx.startService(intent)
            } catch (ignored: Exception) {}
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                }
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isRunning = true
            Log.d("VoiceForegroundService", "Service started in foreground")
        } catch (e: Exception) {
            Log.e("VoiceForegroundService", "Error in startForeground", e)
            // Fallback: start regular foreground if type threw exception
            try {
                startForeground(NOTIF_ID, notification)
                isRunning = true
            } catch (ex: Exception) {
                Log.e("VoiceForegroundService", "Critical failure in startForeground", ex)
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Standby & Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps microphone wake-word listening active in background"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val store = Store(this)
        val cfg = store.load()
        val appName = cfg.name.ifBlank { "Aero Fenix" }
        val wakeWord = cfg.commandingWord.ifBlank { appName }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "$appName Active"
        val text = if (cfg.lang == "ta-IN") {
            "'$wakeWord' என்று கூறினால் உதவிக்கு வரும்"
        } else {
            "Listening for '$wakeWord' wake word"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pOpenIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AeroFenix:VoiceForegroundWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 minute chunks, refreshed
            }
        } catch (ignored: Exception) {}
    }

    private fun registerScreenStateReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("VoiceForegroundService", "Screen turned OFF, maintaining wake-word")
                        // Ensure wakelock is held
                        wakeLock?.let {
                            if (!it.isHeld) it.acquire(10 * 60 * 1000L)
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("VoiceForegroundService", "Screen turned ON")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun stopForegroundService() {
        isRunning = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (ignored: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (ignored: Exception) {}
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (ignored: Exception) {}
        super.onDestroy()
    }
}
