package com.zebchat.chat

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.zebchat.chat.internal.Fixtures
import com.zebchat.chat.internal.Fixtures.apiUrl
import com.zebchat.chat.internal.KeyValueStore
import com.zebchat.chat.internal.MemoryStore
import com.zebchat.chat.internal.StoreKeys
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ZebChatTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        store = MemoryStore()
        ZebChat.resetForTests()
        ZebChat.executor = java.util.concurrent.Executor { it.run() }
        ZebChat.storeFactory = { store }
    }

    @After
    fun tearDown() {
        ZebChat.resetForTests()
        server.shutdown()
    }

    private fun options(locale: String = "auto", sdk: String? = null) = ZebChatOptions(
        locale = locale,
        apiUrl = server.apiUrl(),
        widgetUrl = "https://cdn.zebchat.test/widget/v1/mobile.html",
        sdk = sdk,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun noRequests() = assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

    @Test
    fun `every call before configure is a safe no-op`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ZebChat.setUser(ZebChatUser(id = "1", hash = "h"))
        ZebChat.logout()
        ZebChat.show(activity)
        ZebChat.trackScreen("Home")
        ZebChat.setPushToken("fcm")
        ZebChat.setLocale("fr")
        ZebChat.refreshUnreadCount()
        val data = mapOf("zebchat" to "1", "conversationId" to "conv_1", "siteKey" to Fixtures.SITE_KEY)
        assertFalse(ZebChat.handleNotificationTap(activity, data))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(ZebChat.chatConfig(app))
        noRequests()
    }

    @Test
    fun `an invalid site key leaves ZebChat off`() {
        ZebChat.configure(app, "not-a-key", options())
        assertNull(ZebChat.chatConfig(app))
        ZebChat.configure(app, Fixtures.SITE_KEY, options().copy(apiUrl = "ftp://x"))
        assertNull(ZebChat.chatConfig(app))
    }

    @Test
    fun `the page URL carries site, app, platform, sdk and locale`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        var url = Uri.parse(ZebChat.chatConfig(app)!!.pageUrl)
        assertEquals("https", url.scheme)
        assertEquals("cdn.zebchat.test", url.host)
        assertEquals("/widget/v1/mobile.html", url.path)
        assertEquals(Fixtures.SITE_KEY, url.getQueryParameter("site"))
        assertEquals(app.packageName, url.getQueryParameter("app"))
        assertEquals("android", url.getQueryParameter("platform"))
        assertEquals("android/${ZebChat.VERSION}", url.getQueryParameter("sdk"))
        assertEquals("auto", url.getQueryParameter("locale"))

        ZebChat.setLocale("fr")
        assertEquals("fr", Uri.parse(ZebChat.chatConfig(app)!!.pageUrl).getQueryParameter("locale"))
        ZebChat.setLocale("<script>")
        assertEquals("fr", Uri.parse(ZebChat.chatConfig(app)!!.pageUrl).getQueryParameter("locale"))

        // Wrappers report themselves.
        ZebChat.configure(app, Fixtures.SITE_KEY, options(locale = "de", sdk = "flutter/1.0.0"))
        url = Uri.parse(ZebChat.chatConfig(app)!!.pageUrl)
        assertEquals("flutter/1.0.0", url.getQueryParameter("sdk"))
        assertEquals("de", url.getQueryParameter("locale"))
        assertEquals("https://cdn.zebchat.test", ZebChat.chatConfig(app)!!.cdnOrigin)
    }

    @Test
    fun `the configuration survives process death`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options(locale = "es"))
        ZebChat.resetForTests() // a new process: nothing configured, storage kept
        ZebChat.storeFactory = { store }
        val config = ZebChat.chatConfig(app)
        assertNotNull(config)
        assertEquals("es", Uri.parse(config!!.pageUrl).getQueryParameter("locale"))
    }

    @Test
    fun `a different site key starts a new visitor`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        store.put(StoreKeys.VISITOR_KEY, "k".repeat(43))
        ZebChat.configure(app, Fixtures.SITE_KEY, options(locale = "fr"))
        assertEquals("k".repeat(43), store.get(StoreKeys.VISITOR_KEY))
        ZebChat.configure(app, "zc_other_site", options())
        assertNull(store.get(StoreKeys.VISITOR_KEY))
    }

    @Test
    fun `unread listeners hear the current count at once, then changes, on the main thread`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        val heard = mutableListOf<Int>()
        val threads = mutableSetOf<Thread>()
        val listener = ZebChat.addUnreadListener { count ->
            heard.add(count)
            threads.add(Thread.currentThread())
        }
        idle()
        assertEquals(listOf(0), heard)

        // setUser: session → identify → unread count.
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.session("tok-user", "vis_user", verified = true))
        server.enqueue(MockResponse().setBody("""{"id":"conv_1","visitorUnreadCount":2}"""))
        ZebChat.setUser(ZebChatUser(id = "42", hash = "abc"))
        idle()
        assertEquals(listOf(0, 2), heard)
        assertEquals(2, ZebChat.unreadCount)
        assertEquals(setOf(Looper.getMainLooper().thread), threads)

        // Logout: delete nothing (no push token), forget the visitor, count back to 0.
        ZebChat.logout()
        idle()
        assertEquals(listOf(0, 2, 0), heard)
        assertNull(store.get(StoreKeys.VISITOR_KEY))

        ZebChat.removeUnreadListener(listener)
        server.enqueue(Fixtures.session("tok-3", "vis_3", visitorKey = "n".repeat(43)))
        server.enqueue(Fixtures.session("tok-3b", "vis_3"))
        server.enqueue(MockResponse().setBody("""{"id":"conv_2","visitorUnreadCount":5}"""))
        ZebChat.setUser(ZebChatUser(email = "x@y.com"))
        idle()
        assertEquals(listOf(0, 2, 0), heard)
    }

    @Test
    fun `show opens the chat Activity and a ZebChat notification tap does too`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        server.enqueue(Fixtures.session("tok", "vis", visitorKey = "k".repeat(43)))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ZebChat.show(activity)
        val shown = shadowOf(activity).nextStartedActivity
        assertEquals(ZebChatActivity::class.java.name, shown.component?.className)
        val reuse = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals("an open chat is reused, not stacked", reuse, shown.flags and reuse)

        assertFalse(ZebChat.handleNotificationTap(activity, mapOf("title" to "Sale")))
        assertNull(shadowOf(activity).nextStartedActivity)
        val data = mapOf("zebchat" to "1", "conversationId" to "conv_1", "siteKey" to Fixtures.SITE_KEY)
        assertTrue(ZebChat.handleNotificationTap(activity, data))
        assertEquals(ZebChatActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)

        // A tap on a notification FCM displayed: the launcher Activity's extras / Intent.
        val extras = Bundle().apply {
            putString("zebchat", "1")
            putString("conversationId", "conv_1")
            putString("siteKey", Fixtures.SITE_KEY)
            putString("google.message_id", "0:1")
        }
        assertTrue(ZebChat.handleNotificationTap(activity, extras))
        assertEquals(ZebChatActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertFalse(ZebChat.handleNotificationTap(activity, Bundle()))
        assertNull(shadowOf(activity).nextStartedActivity)

        val launch = Intent(Intent.ACTION_MAIN).putExtras(extras)
        assertTrue(ZebChat.isZebChatIntent(launch))
        assertTrue(ZebChat.handleNotificationIntent(activity, launch))
        assertEquals(ZebChatActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertFalse(ZebChat.isZebChatIntent(null))
        assertFalse(ZebChat.handleNotificationIntent(activity, null))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `configure keeps disk and Keystore work off the calling thread`() {
        val queued = mutableListOf<Runnable>()
        ZebChat.executor = Executor { queued.add(it) }
        var touched = 0
        val counting = object : KeyValueStore {
            override fun get(key: String): String? {
                touched++
                return store.get(key)
            }

            override fun put(key: String, value: String?) {
                touched++
                store.put(key, value)
            }
        }
        ZebChat.storeFactory = { counting }
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        assertNotNull("usable at once", ZebChat.chatConfig(app))
        assertEquals("no store access on the calling thread", 0, touched)
        assertNull(store.get(StoreKeys.CONFIG))

        queued.toList().forEach { it.run() }
        assertTrue(touched > 0)
        assertEquals(Fixtures.SITE_KEY, JSONObject(store.get(StoreKeys.CONFIG)!!).getString("siteKey"))
    }

    @Test
    fun `setLocale survives process death until configure brings another locale`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        ZebChat.setLocale("fr")
        fun locale() = Uri.parse(ZebChat.chatConfig(app)!!.pageUrl).getQueryParameter("locale")
        fun newProcess() {
            ZebChat.resetForTests()
            ZebChat.storeFactory = { store }
        }

        // The host configures again in Application.onCreate.
        newProcess()
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        assertEquals("fr", locale())

        // The chat screen is restored before the host configured.
        newProcess()
        assertEquals("fr", locale())

        // The host now ships another locale option: it wins.
        newProcess()
        ZebChat.configure(app, Fixtures.SITE_KEY, options(locale = "de"))
        assertEquals("de", locale())
        newProcess()
        assertEquals("de", locale())
    }

    @Test
    fun `switching site keys removes the push device from the old site's visitor`() {
        ZebChat.configure(app, Fixtures.SITE_KEY, options())
        ZebChat.setPushToken("fcm-1") // no visitor yet: nothing sent
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent()) // device
        server.enqueue(Fixtures.noContent()) // pageview
        ZebChat.trackScreen("Home")
        repeat(3) { server.takeRequest(2, TimeUnit.SECONDS)!! }

        server.enqueue(Fixtures.noContent())
        ZebChat.configure(app, "zc_other_site", options())
        val delete = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/widget/devices", delete.path)
        assertEquals("Bearer tok-1", delete.getHeader("Authorization"))
        assertNull(store.get(StoreKeys.VISITOR_KEY))
        assertNull(store.get(StoreKeys.DEVICE))
        assertEquals("the app's token stays", "fcm-1", store.get(StoreKeys.PUSH_TOKEN))
    }
}
