package com.zebchat.chat.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zebchat.chat.R
import com.zebchat.chat.ZebChatActivity

/** Visitor push helpers (data keys from `VisitorPushData` in `packages/types/src/mobile.ts`). */
internal object Notifications {
    const val CHANNEL_ID = "zebchat_chat"
    const val KEY_FLAG = "zebchat"
    const val KEY_CONVERSATION = "conversationId"
    const val KEY_SITE = "siteKey"
    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"
    private const val TAG = "zebchat"
    private const val MAX_TEXT = 500

    fun isZebChat(data: Map<String, String?>?): Boolean {
        if (data == null || data[KEY_FLAG] != "1") return false
        val conversation = data[KEY_CONVERSATION]
        val site = data[KEY_SITE]
        return !conversation.isNullOrEmpty() && conversation.length <= 100 &&
            !site.isNullOrEmpty() && site.length <= 100
    }

    /** The push data keys from a notification-tap Intent's extras. */
    fun fromBundle(extras: Bundle?): Map<String, String?>? {
        if (extras == null) return null
        return listOf(KEY_FLAG, KEY_CONVERSATION, KEY_SITE).associateWith { extras.getString(it) }
    }

    /** Creates the `zebchat_chat` channel (Android 8+); safe to call repeatedly. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.zebchat_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = context.getString(R.string.zebchat_channel_description)
        manager.createNotificationChannel(channel)
    }

    fun notificationId(conversationId: String): Int = "zebchat:$conversationId".hashCode()

    /**
     * Shows a chat notification that opens the chat. Returns false when it cannot be shown
     * (notifications off or POST_NOTIFICATIONS not granted: the host app decides that).
     */
    @SuppressLint("MissingPermission")
    fun show(context: Context, data: Map<String, String?>, title: String?, body: String?, icon: Int): Boolean {
        val conversation = data[KEY_CONVERSATION] ?: return false
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)
        // SINGLE_TOP + CLEAR_TOP: an open chat is reused, never stacked under a second one.
        val intent = Intent(context, ZebChatActivity::class.java)
            .putExtra(ZebChatActivity.EXTRA_FROM_NOTIFICATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            notificationId(conversation),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val smallIcon = when {
            icon != 0 -> icon
            context.applicationInfo.icon != 0 -> context.applicationInfo.icon
            else -> android.R.drawable.stat_notify_chat
        }
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: label)
            .setContentText(
                body?.takeIf { it.isNotBlank() }?.take(MAX_TEXT)
                    ?: context.getString(R.string.zebchat_notification_default_body),
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return try {
            manager.notify(TAG, notificationId(conversation), notification)
            true
        } catch (e: SecurityException) {
            ZcLog.w("Notification not shown", e)
            false
        }
    }

    /** Removes shown chat notifications (when the chat opens). */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.tag == TAG }
            .forEach { manager.cancel(TAG, it.id) }
    }
}
