package com.zebchat.chat.internal

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.zebchat.chat.ZebChat
import com.zebchat.chat.ZebChatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationsTest {
    private lateinit var app: Application
    private val data = mapOf("zebchat" to "1", "conversationId" to "conv_1", "siteKey" to "zc_test_site")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ZebChat.resetForTests()
    }

    private fun manager() = app.getSystemService(NotificationManager::class.java)

    @Test
    fun `recognises ZebChat pushes only`() {
        assertTrue(ZebChat.isZebChatNotification(data))
        assertFalse(ZebChat.isZebChatNotification(data + ("zebchat" to "0")))
        assertFalse(ZebChat.isZebChatNotification(data - "conversationId"))
        assertFalse(ZebChat.isZebChatNotification(data - "siteKey"))
        assertFalse(ZebChat.isZebChatNotification(mapOf("title" to "Sale!")))
        assertFalse(ZebChat.isZebChatNotification(null as Map<String, String>?))

        val extras = Bundle().apply {
            putString("zebchat", "1")
            putString("conversationId", "conv_1")
            putString("siteKey", "zc_test_site")
            putString("google.message_id", "0:1")
        }
        assertTrue(ZebChat.isZebChatNotification(extras))
        assertFalse(ZebChat.isZebChatNotification(Bundle()))
        assertFalse(ZebChat.isZebChatNotification(null as Bundle?))
    }

    @Test
    fun `creates the zebchat_chat channel`() {
        Notifications.ensureChannel(app)
        val channel = manager().getNotificationChannel("zebchat_chat")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Chat messages", channel.name.toString())
    }

    @Test
    fun `shows a notification that opens the chat when the host allows notifications`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(ZebChat.showNotification(app, data, "Olivia", "Hi! How can I help?"))
        val shown = shadowOf(manager()).allNotifications
        assertEquals(1, shown.size)
        val notification = shown[0]
        assertEquals("zebchat_chat", notification.channelId)
        assertEquals("Olivia", notification.extras.getString("android.title"))
        assertEquals("Hi! How can I help?", notification.extras.getCharSequence("android.text").toString())
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ZebChatActivity::class.java.name, intent.component?.className)
        val reuse = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals("an open chat is reused, not stacked", reuse, intent.flags and reuse)

        // Same conversation: replaced, not stacked. Title and body come from the push data.
        assertTrue(ZebChat.showNotification(app, data + mapOf("title" to "Sam", "body" to "Sent a file")))
        assertEquals(1, shadowOf(manager()).allNotifications.size)
        val replaced = shadowOf(manager()).allNotifications[0]
        assertEquals("Sam", replaced.extras.getString("android.title"))
        assertEquals("Sent a file", replaced.extras.getCharSequence("android.text").toString())

        // Without them: the app name and a generic text.
        assertTrue(ZebChat.showNotification(app, data))
        assertEquals("New message", shadowOf(manager()).allNotifications[0].extras.getCharSequence("android.text").toString())

        Notifications.cancelAll(app)
        assertEquals(0, shadowOf(manager()).allNotifications.size)
    }

    @Test
    fun `never shows without POST_NOTIFICATIONS or for other pushes`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ZebChat.showNotification(app, data))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ZebChat.showNotification(app, mapOf("title" to "Sale!")))
        assertEquals(0, shadowOf(manager()).allNotifications.size)
    }
}
