package com.nextgen.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

data class NotificationItem(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val replyAction: Notification.Action? = null,
    val replyRemoteInput: RemoteInput? = null
)

/**
 * Captures incoming notifications (WhatsApp, Telegram, SMS, Gmail, etc.)
 * allows the assistant to read them aloud ("வந்த செய்தியைப் படி"),
 * and enables direct replies via RemoteInput.
 */
class AgentNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: AgentNotificationListenerService? = null
            private set

        private val recentList = mutableListOf<NotificationItem>()

        @Synchronized
        fun getRecentNotifications(): List<NotificationItem> {
            return recentList.toList()
        }

        @Synchronized
        fun addNotification(item: NotificationItem) {
            recentList.removeAll { it.id == item.id }
            recentList.add(0, item)
            if (recentList.size > 50) {
                recentList.removeAt(recentList.size - 1)
            }
        }

        @Synchronized
        fun clearNotifications() {
            recentList.clear()
        }

        fun isListenerEnabled(ctx: Context): Boolean {
            val cn = ComponentName(ctx, AgentNotificationListenerService::class.java)
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(cn.flattenToString())
        }

        fun openSettings(ctx: Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("NotifListener", "Connected")
        // Load active notifications
        try {
            val active = activeNotifications
            if (active != null) {
                for (sbn in active) {
                    processNotification(sbn)
                }
            }
        } catch (ignored: Exception) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d("NotifListener", "Disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            // Ignore ongoing media/foreground notifications or our own app
            if (pkg == packageName) return
            val n = sbn.notification ?: return
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

            val extras = n.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""

            if (title.isBlank() && text.isBlank()) return

            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            // Extract reply action and remote input if available
            var replyAction: Notification.Action? = null
            var replyRemoteInput: RemoteInput? = null
            val actions = n.actions
            if (actions != null) {
                for (action in actions) {
                    val inputs = action.remoteInputs
                    if (inputs != null && inputs.isNotEmpty()) {
                        replyAction = action
                        replyRemoteInput = inputs[0]
                        break
                    }
                }
            }

            val item = NotificationItem(
                id = "${sbn.id}_${sbn.postTime}",
                packageName = pkg,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                replyAction = replyAction,
                replyRemoteInput = replyRemoteInput
            )
            addNotification(item)
        } catch (e: Exception) {
            Log.e("NotifListener", "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        synchronized(recentList) {
            recentList.removeAll { it.id.startsWith("${sbn.id}_") }
        }
    }

    /**
     * Replies directly to the specified notification action using RemoteInput.
     */
    fun reply(item: NotificationItem, replyText: String): Boolean {
        val action = item.replyAction ?: return false
        val remoteInput = item.replyRemoteInput ?: return false
        val pi = action.actionIntent ?: return false

        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
            pi.send(this, 0, intent)
            true
        } catch (e: Exception) {
            Log.e("NotifListener", "Error replying to notification", e)
            false
        }
    }
}
