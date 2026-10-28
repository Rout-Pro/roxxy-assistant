package com.rout.roxxy.core

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Collections

/**
 * Reads incoming notifications and captures direct-reply actions (WhatsApp etc).
 * Ghost auto-reply is OFF by default and rate-limited per sender when enabled.
 */
class RoxxyNotificationService : NotificationListenerService() {

    companion object {
        val liveNotifications = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val feedListeners = mutableListOf<() -> Unit>()

        private var lastReplyAction: Notification.Action? = null
        private var lastReplySender: String = ""
        private var lastReplyApp: String = ""
        private val lastGhostReplyAt = mutableMapOf<String, Long>()
        private const val GHOST_RATE_LIMIT_MS = 60_000L

        fun isListenerEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(context.packageName)
        }

        fun lastReplyTarget(): Triple<String, String, Boolean> =
            Triple(lastReplyApp, lastReplySender, lastReplyAction != null)

        fun sendDirectBackgroundReply(context: Context, replyMessage: String): Boolean {
            val action = lastReplyAction ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            val localIntent = Intent()
            val bundle = Bundle()
            for (remoteInput in remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, replyMessage)
            }
            RemoteInput.addResultsToIntent(remoteInputs, localIntent, bundle)
            return try {
                action.actionIntent.send(context, 0, localIntent)
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            }
        }

        fun notifyFeed() { feedListeners.toList().forEach { it() } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val extras = notif.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isNotBlank() && text.isNotBlank()) {
            synchronized(liveNotifications) {
                if (liveNotifications.size > 12) liveNotifications.removeAt(0)
                liveNotifications.add(mapOf("app" to pkg, "sender" to title, "message" to text.take(200)))
            }
            notifyFeed()
        }

        // Capture the newest direct-reply action (WhatsApp / Telegram / SMS style)
        notif.actions?.forEach { act ->
            if (!act.remoteInputs.isNullOrEmpty()) {
                lastReplyAction = act
                lastReplySender = title
                lastReplyApp = pkg
            }
        }

        // Ghost auto-reply: only when the user switched it on in Settings.
        if (KeyStore.isGhostModeEnabled(this) &&
            (pkg == "com.whatsapp" || pkg == "com.instagram.android" || pkg.contains("messaging"))
        ) {
            val key = "$pkg|$title"
            val now = System.currentTimeMillis()
            val last = lastGhostReplyAt[key] ?: 0L
            if (now - last > GHOST_RATE_LIMIT_MS && title.isNotBlank()) {
                lastGhostReplyAt[key] = now
                val reply = KeyStore.getGhostReplyText(this)
                // Reply only to THIS notification's action if it has one.
                notif.actions?.forEach { act ->
                    if (!act.remoteInputs.isNullOrEmpty()) {
                        val localIntent = Intent()
                        val bundle = Bundle()
                        for (ri in act.remoteInputs!!) bundle.putCharSequence(ri.resultKey, reply)
                        RemoteInput.addResultsToIntent(act.remoteInputs, localIntent, bundle)
                        try { act.actionIntent.send(this, 0, localIntent) } catch (_: Exception) {}
                        return@forEach
                    }
                }
            }
        }
    }
}
