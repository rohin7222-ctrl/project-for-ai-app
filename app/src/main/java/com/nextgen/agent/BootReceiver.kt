package com.nextgen.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot and package replacement receiver to keep Ear-To-Call ready across device reboots.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            val store = Store(context)
            if (!store.isReady()) return

            Log.i("BootReceiver", "Boot completed - initializing background agent components")
            EarToCallManager.getInstance(context).onBoot(context)
            AgentAccessibilityService.autoEnable(context)
        }
    }
}
