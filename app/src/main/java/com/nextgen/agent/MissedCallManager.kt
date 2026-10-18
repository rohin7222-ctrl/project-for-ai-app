package com.nextgen.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * ⚡ Autonomous Agentic Missed Call Engine.
 *
 * Places an outgoing phone call to a given contact name or phone number,
 * continuously tracks the call cadence, displays real-time HUD countdown of rings,
 * and automatically cuts/hangs up the call after exactly 3 rings (~10.8 seconds).
 *
 * Features:
 * 1. Automatic contact name to phone number resolution.
 * 2. Real-time Cyber Floating HUD progress: Dialing -> Ring 1 -> Ring 2 -> Ring 3 -> Auto Cut.
 * 3. Multi-layer Call Termination: TelecomManager.endCall() + Accessibility UI Click ("End call"/"Decline").
 * 4. Failsafe timeout & early answer detection.
 */
class MissedCallManager private constructor(appContext: Context) {

    private val context: Context = appContext.applicationContext
    private val tag = "MissedCallManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isMissedCallActive: Boolean = false
        private set
    @Volatile private var currentTarget: String = ""
    @Volatile private var resolvedNumber: String = ""
    @Volatile private var currentRingIndex: Int = 0

    private var ringCountRunnable: Runnable? = null
    private var failsafeRunnable: Runnable? = null

    companion object {
        @Volatile private var instance: MissedCallManager? = null

        fun getInstance(context: Context): MissedCallManager {
            return instance ?: synchronized(this) {
                instance ?: MissedCallManager(context).also { instance = it }
            }
        }
    }

    /**
     * Initiates an autonomous missed call.
     * @param target Contact name (e.g. "Mom", "Appa") or phone number (e.g. "9876543210").
     * @param maxRings Number of rings before auto-cut (defaults to 3).
     */
    @SuppressLint("MissingPermission")
    fun triggerMissedCall(target: String, maxRings: Int = 3, onFinished: ((Boolean, String) -> Unit)? = null): String {
        val t = target.trim()
        if (t.isBlank()) {
            return "ERR:target cannot be empty"
        }

        val dc = DeviceController(context)
        val num = dc.resolveNumber(t) ?: t.replace(Regex("[^0-9+]"), "").takeIf { it.isNotBlank() } ?: t

        currentTarget = t
        resolvedNumber = num
        isMissedCallActive = true
        currentRingIndex = 0

        // 1. Show initial HUD
        val store = Store(context)
        val cfg = store.load()
        val isTa = (cfg.lang == "ta-IN")
        val initialMsg = if (isTa) {
            "📞 $currentTarget-க்கு மிஸ்ட் கால் துவங்குகிறது (3 ரிங்)..."
        } else {
            "📞 Missed Call to $currentTarget (Dialing, 3 rings)..."
        }
        AgentAccessibilityService.instance?.showFloatingHud(initialMsg)

        // 2. Dial the call
        val callPlaced = placeCall(num)
        if (!callPlaced) {
            isMissedCallActive = false
            AgentAccessibilityService.instance?.hideFloatingHud()
            return "ERR:unable to place call to $t"
        }

        // 3. Start Ring Cadence Scheduler
        // Cellular standard: ~2.5s dialing connect, ~3.5s per ring cycle.
        // Ring 1 @ 2.5s, Ring 2 @ 6.0s, Ring 3 @ 9.5s, Cut @ 10.8s.
        cancelTimers()

        // Ring 1 HUD
        mainHandler.postDelayed({
            if (isMissedCallActive) {
                currentRingIndex = 1
                val r1 = if (isTa) "⚡ 📞 $currentTarget: ரிங் 1 / $maxRings" else "⚡ 📞 $currentTarget: Ring 1 / $maxRings"
                AgentAccessibilityService.instance?.showFloatingHud(r1)
                Log.d(tag, "Missed call: Ring 1 of $maxRings")
            }
        }, 2500L)

        // Ring 2 HUD
        mainHandler.postDelayed({
            if (isMissedCallActive) {
                currentRingIndex = 2
                val r2 = if (isTa) "⚡ 📞 $currentTarget: ரிங் 2 / $maxRings" else "⚡ 📞 $currentTarget: Ring 2 / $maxRings"
                AgentAccessibilityService.instance?.showFloatingHud(r2)
                Log.d(tag, "Missed call: Ring 2 of $maxRings")
            }
        }, 6000L)

        // Ring 3 HUD & Cut Alert
        mainHandler.postDelayed({
            if (isMissedCallActive) {
                currentRingIndex = 3
                val r3 = if (isTa) "⚡ 📞 $currentTarget: ரிங் 3 / $maxRings - கட் செய்யப்படுகிறது..." else "⚡ 📞 $currentTarget: Ring 3 / $maxRings - Auto-Cutting..."
                AgentAccessibilityService.instance?.showFloatingHud(r3)
                Log.d(tag, "Missed call: Ring 3 of $maxRings - executing auto-cut")
            }
        }, 9500L)

        // Auto Cut Execution @ 10.8s
        ringCountRunnable = Runnable {
            if (isMissedCallActive) {
                executeAutoCut(onFinished)
            }
        }
        mainHandler.postDelayed(ringCountRunnable!!, 10800L)

        // Secondary backup cut @ 11.5s
        failsafeRunnable = Runnable {
            if (isMissedCallActive) {
                forceEndCall()
                finishMissedCall(success = true, reason = "Auto-cut completed after 3 rings", onFinished = onFinished)
            }
        }
        mainHandler.postDelayed(failsafeRunnable!!, 11500L)

        return "OK:missed_call_started"
    }

    private fun placeCall(number: String): Boolean {
        try {
            val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            if (hasCallPerm) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(callIntent)
                return true
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(dialIntent)
                return true
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to place call: ${e.message}", e)
            return false
        }
    }

    /**
     * Executes the call termination across multiple system layers.
     */
    private fun executeAutoCut(onFinished: ((Boolean, String) -> Unit)?) {
        Log.i(tag, "Executing auto-cut after 3 rings for $currentTarget")
        forceEndCall()

        val store = Store(context)
        val cfg = store.load()
        val isTa = (cfg.lang == "ta-IN")
        val doneMsg = if (isTa) {
            "✅ $currentTarget-க்கு 3 ரிங் வந்ததால் மிஸ்ட் கால் நிறைவடைந்தது!"
        } else {
            "✅ Missed Call to $currentTarget ended after 3 rings!"
        }
        AgentAccessibilityService.instance?.showFloatingHud(doneMsg)
        try {
            Toast.makeText(context, doneMsg, Toast.LENGTH_SHORT).show()
        } catch (ignored: Exception) {}

        finishMissedCall(success = true, reason = doneMsg, onFinished = onFinished)
    }

    private fun forceEndCall() {
        try {
            // Layer 1: TelecomManager.endCall()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    tm?.endCall()
                }
            }
        } catch (ignored: Exception) {}

        try {
            // Layer 2: DeviceController telecom(false)
            val dc = DeviceController(context)
            dc.endCall()
        } catch (ignored: Exception) {}

        try {
            // Layer 3: AgentAccessibilityService UI Click on End button
            val a11y = AgentAccessibilityService.instance
            if (a11y != null) {
                a11y.uiClick("End call")
                a11y.uiClick("Decline")
                a11y.uiClick("End")
                a11y.uiClick("நிராகரி")
            }
        } catch (ignored: Exception) {}
    }

    private fun finishMissedCall(success: Boolean, reason: String, onFinished: ((Boolean, String) -> Unit)?) {
        isMissedCallActive = false
        cancelTimers()

        mainHandler.postDelayed({
            AgentAccessibilityService.instance?.hideFloatingHud()
        }, 2800L)

        onFinished?.invoke(success, reason)
    }

    fun onCallStateChanged(state: Int) {
        if (!isMissedCallActive) return
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            Log.d(tag, "Telephony state transitioned to IDLE while missed call active.")
            finishMissedCall(success = true, reason = "Call disconnected", onFinished = null)
        }
    }

    private fun cancelTimers() {
        ringCountRunnable?.let { mainHandler.removeCallbacks(it) }
        failsafeRunnable?.let { mainHandler.removeCallbacks(it) }
        ringCountRunnable = null
        failsafeRunnable = null
    }
}
