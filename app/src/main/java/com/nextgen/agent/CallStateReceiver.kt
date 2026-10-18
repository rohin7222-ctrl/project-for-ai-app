package com.nextgen.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Manifest-declared BroadcastReceiver for PHONE_STATE changes.
 *
 * This receiver wakes up even when the app is completely killed or swiped away from Recents.
 * As long as the user is logged in (Store.isReady() == true) and raiseToAnswer is enabled,
 * it immediately triggers EarToCallManager to listen for proximity and answer the call on ear raise.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val store = Store(context)
        // User rule: only work if logged in, do not work after logout or uninstall
        if (!store.isReady()) {
            return
        }

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> -1
        }

        if (state >= 0) {
            Log.d("CallStateReceiver", "Phone state changed: $stateStr ($state)")
            MissedCallManager.getInstance(context).onCallStateChanged(state)
            val cfg = store.load()
            if (cfg.raiseToAnswer) {
                EarToCallManager.getInstance(context).onCallStateChanged(state, context)
            }
        }
    }
}
