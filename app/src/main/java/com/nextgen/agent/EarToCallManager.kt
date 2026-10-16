package com.nextgen.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 24/7 Ear-To-Call (Raise to Ear to Answer Call) Engine.
 *
 * Runs autonomously even when the app is completely closed or killed from recents.
 * Activated by incoming calls via CallStateReceiver or AgentAccessibilityService.
 * Survives process kills and device restarts.
 * Only stops if the user logs out (Store.isReady() == false) or uninstalls the app.
 */
class EarToCallManager private constructor(appContext: Context) {

    private val context: Context = appContext.applicationContext
    private val tag = "EarToCallManager"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isListening = false
    @Volatile private var proximityWasFar = false
    @Volatile private var ringingStartTime = 0L
    @Volatile private var isCallRinging = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY) return
            val distance = event.values[0]
            val max = proximitySensor?.maximumRange ?: 5f
            val threshold = if (max > 5f) 5f else max
            val isNear = distance < threshold

            Log.d(tag, "Proximity change: distance=$distance, max=$max, isNear=$isNear, wasFar=$proximityWasFar")

            if (!isNear) {
                proximityWasFar = true
            } else if (isNear && proximityWasFar) {
                val elapsed = System.currentTimeMillis() - ringingStartTime
                // Minimum debounce of 200ms so picking up phone face-down doesn't immediately false-trigger
                if (elapsed >= 200 && isCallRinging) {
                    Log.i(tag, "Ear to call gesture detected! Answering incoming call...")
                    stopListening()
                    mainHandler.post {
                        executeAnswerCall()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    companion object {
        @Volatile private var instance: EarToCallManager? = null

        fun getInstance(context: Context): EarToCallManager {
            return instance ?: synchronized(this) {
                instance ?: EarToCallManager(context).also { instance = it }
            }
        }
    }

    /**
     * Called whenever phone call state changes.
     */
    fun onCallStateChanged(state: Int, ctx: Context = context) {
        val store = Store(ctx)
        // User rule: app ah uninstall pannalo or logout pannalo matum thaan athu work aga kudatthu
        if (!store.isReady()) {
            Log.d(tag, "Ignored: user is not logged in / ready")
            stopListening()
            return
        }

        val cfg = store.load()
        if (!cfg.raiseToAnswer) {
            Log.d(tag, "Ignored: raiseToAnswer is disabled in settings")
            stopListening()
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(tag, "Incoming call ringing detected - starting background proximity monitoring")
                isCallRinging = true
                startListening()
            }
            TelephonyManager.CALL_STATE_OFFHOOK,
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(tag, "Call ended or answered (state=$state) - stopping proximity monitoring")
                isCallRinging = false
                stopListening()
            }
        }
    }

    /**
     * Initializes background threads and starts proximity sensor monitoring with a safety WakeLock.
     */
    @Synchronized
    private fun startListening() {
        if (isListening) return
        val sensor = proximitySensor ?: run {
            Log.w(tag, "Proximity sensor not available on this device")
            return
        }

        proximityWasFar = false
        ringingStartTime = System.currentTimeMillis()

        // Acquire partial wakelock with 45s safety timeout
        acquireWakeLock(45_000L)

        try {
            if (sensorThread == null || !sensorThread!!.isAlive) {
                sensorThread = HandlerThread("EarToCallSensorThread").apply { start() }
                sensorHandler = Handler(sensorThread!!.looper)
            }

            val registered = sensorManager?.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                sensorHandler
            ) ?: false

            isListening = registered
            Log.d(tag, "Proximity listener registered: $registered")
        } catch (e: Exception) {
            Log.e(tag, "Failed to register proximity listener", e)
            releaseWakeLock()
        }
    }

    /**
     * Stops sensor listening and cleans up background thread and wake lock.
     */
    @Synchronized
    fun stopListening() {
        if (!isListening && sensorThread == null) return
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (ignored: Exception) {}

        isListening = false
        proximityWasFar = false
        isCallRinging = false

        try {
            sensorThread?.quitSafely()
        } catch (ignored: Exception) {}
        sensorThread = null
        sensorHandler = null

        releaseWakeLock()
    }

    /**
     * Called when the user logs out from Settings / MainActivity.
     */
    fun onLogout() {
        Log.i(tag, "User logged out. Halting all EarToCall background services.")
        stopListening()
    }

    /**
     * Called on device boot to ensure services are warm.
     */
    fun onBoot(ctx: Context) {
        val store = Store(ctx)
        if (!store.isReady()) return
        Log.i(tag, "EarToCallManager initialized on device boot. Standing by for incoming calls.")
    }

    /**
     * Executes multi-tiered call answering:
     * 1. Official TelecomManager API (API 26+)
     * 2. AgentAccessibilityService automated UI answering
     * 3. DeviceController fallback
     */
    @SuppressLint("MissingPermission")
    fun executeAnswerCall() {
        var answered = false

        // 1. TelecomManager acceptRingingCall
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    @Suppress("DEPRECATION")
                    tm?.acceptRingingCall()
                    answered = true
                    Log.i(tag, "Call accepted via TelecomManager")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "TelecomManager answer call failed: ${e.message}")
        }

        // 2. Accessibility Service Fast Path
        if (!answered) {
            val a11y = AgentAccessibilityService.instance
            if (a11y != null) {
                answered = a11y.answerIncomingCall()
                if (answered) Log.i(tag, "Call accepted via AgentAccessibilityService UI")
            }
        }

        // 3. Fallback via DeviceController
        if (!answered) {
            try {
                val dc = DeviceController(context)
                val res = dc.answerCall()
                answered = (res == "OK")
                if (answered) Log.i(tag, "Call accepted via DeviceController: $res")
            } catch (e: Exception) {
                Log.w(tag, "DeviceController answerCall failed: ${e.message}")
            }
        }

        // Vibrate to provide tactile confirmation
        vibrateConfirm()

        // User feedback toast
        val store = Store(context)
        val cfg = store.load()
        val msg = if (cfg.lang == "ta-IN") "📞 காதில் வைத்ததால் அழைப்பு ஏற்கப்பட்டது" else "📞 Call answered automatically"
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (ignored: Exception) {}
    }

    private fun vibrateConfirm() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(160)
            }
        } catch (ignored: Exception) {}
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NextGenAI:EarToCallWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
    }
}
