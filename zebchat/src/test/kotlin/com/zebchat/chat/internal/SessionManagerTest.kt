package com.zebchat.chat.internal

import com.zebchat.chat.ZebChatUser
import com.zebchat.chat.internal.Fixtures.apiUrl
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryStore
    private lateinit var manager: SessionManager
    private var now = Fixtures.NOW

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = MemoryStore()
        manager = newManager()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newManager() = SessionManager(
        ApiClient(server.apiUrl(), "ZebChat-Android/test", connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
        store,
        Fixtures.SITE_KEY,
        Fixtures.client,
        clock = { now },
    )

    private fun take(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request")

    private fun assertNoMoreRequests() = assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

    @Test
    fun `creates a session for the app, stores the visitor key and caches the token`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        val first = manager.session()
        assertEquals("tok-1", first?.token)
        assertEquals("vis_1", first?.visitorId)

        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/widget/sessions", request.path)
        val body = Fixtures.json(request)
        assertEquals(Fixtures.SITE_KEY, body.getString("siteKey"))
        assertEquals(Fixtures.APP_ID, body.getString("app"))
        assertEquals("android", body.getString("platform"))
        assertFalse("no host for apps", body.has("host"))
        assertFalse(body.has("visitorKey"))
        assertEquals("k".repeat(43), store.get(StoreKeys.VISITOR_KEY))

        // Cached: a second call (and a new manager on a cold start) makes no request.
        assertEquals("tok-1", manager.session()?.token)
        assertEquals("tok-1", newManager().session()?.token)
        assertNoMoreRequests()
    }

    @Test
    fun `a token close to expiry is replaced using the stored visitor key`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        manager.session()
        take()
        now = IsoTime.parse(Fixtures.EXPIRES)!! - 5 * 60 * 1000 // 5 minutes left
        server.enqueue(Fixtures.session("tok-2", "vis_1"))
        assertEquals("tok-2", manager.session()?.token)
        assertEquals("k".repeat(43), Fixtures.json(take()).getString("visitorKey"))
        assertEquals("k".repeat(43), store.get(StoreKeys.VISITOR_KEY))
    }

    @Test
    fun `identify adopts the visitor it returns and re-posts the push token for it`() {
        // Anonymous visitor with a registered push device.
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        assertEquals("vis_anon", manager.session()?.visitorId)
        assertEquals("/api/v1/widget/sessions", take().path)
        val firstDevice = take()
        assertEquals("/api/v1/widget/devices", firstDevice.path)
        assertEquals("Bearer tok-anon", firstDevice.getHeader("Authorization"))
        assertEquals(
            JSONObject().put("token", "fcm-1").put("platform", "android").put("appId", Fixtures.APP_ID).toString(),
            Fixtures.json(firstDevice).toString(),
        )

        // setUser: sessions (stored key) → identify returns another (verified) visitor.
        assertTrue(manager.setUser(ZebChatUser(id = "42", email = "a@b.com", name = "Ana", hash = "abc123")))
        assertFalse("same user again is a no-op", manager.setUser(ZebChatUser(id = "42", email = "a@b.com", name = "Ana", hash = "abc123")))
        server.enqueue(Fixtures.session("tok-anon-2", "vis_anon"))
        server.enqueue(Fixtures.session("tok-user", "vis_user", verified = true))
        server.enqueue(Fixtures.noContent())
        val session = manager.session()
        assertEquals("vis_user", session?.visitorId)
        assertEquals("tok-user", session?.token)
        assertTrue(JSONObject(session!!.visitorJson).getBoolean("verified"))

        assertEquals("k".repeat(43), Fixtures.json(take()).getString("visitorKey"))
        val identify = take()
        assertEquals("/api/v1/widget/identify", identify.path)
        assertEquals("Bearer tok-anon-2", identify.getHeader("Authorization"))
        val identity = Fixtures.json(identify)
        assertEquals("42", identity.getString("id"))
        assertEquals("abc123", identity.getString("hash"))
        assertEquals("a@b.com", identity.getString("email"))
        assertFalse(identity.has("phone"))
        val moved = take()
        assertEquals("/api/v1/widget/devices", moved.path)
        assertEquals("Bearer tok-user", moved.getHeader("Authorization"))
        // The anonymous key stays: the verified identity is the credential for that visitor.
        assertEquals("k".repeat(43), store.get(StoreKeys.VISITOR_KEY))
        assertEquals("tok-user", manager.session()?.token)
        assertNoMoreRequests()
    }

    @Test
    fun `a refused identity keeps the anonymous session without retrying`() {
        assertTrue(manager.setUser(ZebChatUser(id = "42", hash = "bad")))
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.error(403, "Invalid identity hash"))
        assertEquals("vis_anon", manager.session()?.visitorId)
        take()
        take()
        assertEquals("tok-anon", manager.session()?.token)
        assertNoMoreRequests()
    }

    @Test
    fun `an identify network failure keeps the anonymous session and retries only identify`() {
        manager.setUser(ZebChatUser(email = "a@b.com"))
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals("tok-anon", manager.session()?.token)
        take()
        take()

        // Still offline: the cached anonymous session is used, no new /sessions.
        server.enqueue(Fixtures.error(503))
        assertEquals("tok-anon", manager.session()?.token)
        assertEquals("/api/v1/widget/identify", take().path)

        server.enqueue(Fixtures.session("tok-ident", "vis_anon"))
        assertEquals("tok-ident", manager.session()?.token)
        val identify = take()
        assertEquals("/api/v1/widget/identify", identify.path)
        assertEquals("Bearer tok-anon", identify.getHeader("Authorization"))
        // Identified now: cached.
        assertEquals("tok-ident", manager.session()?.token)
        assertNoMoreRequests()
    }

    @Test
    fun `a 401 on the identify retry starts a new session`() {
        manager.setUser(ZebChatUser(email = "a@b.com"))
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.error(503))
        manager.session()
        repeat(2) { take() }

        server.enqueue(Fixtures.error(401))
        server.enqueue(Fixtures.session("tok-anon-2", "vis_anon"))
        server.enqueue(Fixtures.session("tok-ident", "vis_anon"))
        assertEquals("tok-ident", manager.session()?.token)
        assertEquals("/api/v1/widget/identify", take().path)
        assertEquals("/api/v1/widget/sessions", take().path)
        assertEquals("Bearer tok-anon-2", take().getHeader("Authorization"))
    }

    @Test
    fun `a 401 from devices drops the cached session`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.error(401))
        manager.session()
        repeat(2) { take() }
        assertNull(store.get(StoreKeys.SESSION))
        assertNull(store.get(StoreKeys.DEVICE))

        // The next call starts a new session and registers the device with it.
        server.enqueue(Fixtures.session("tok-2", "vis_1"))
        server.enqueue(Fixtures.noContent())
        assertEquals("tok-2", manager.session()?.token)
        take()
        assertEquals("Bearer tok-2", take().getHeader("Authorization"))
    }

    @Test
    fun `a logout delete answered 401 retries with a new session`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        repeat(2) { take() }

        server.enqueue(Fixtures.error(401)) // DELETE with the revoked token
        server.enqueue(Fixtures.session("tok-2", "vis_1"))
        server.enqueue(Fixtures.noContent()) // DELETE again
        manager.logout()
        assertEquals("Bearer tok-1", take().getHeader("Authorization"))
        assertEquals("/api/v1/widget/sessions", take().path)
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("Bearer tok-2", delete.getHeader("Authorization"))
        assertNull("deleted: nothing to move", store.get(StoreKeys.DEVICE_MOVE))
        assertNull(store.get(StoreKeys.VISITOR_KEY))
    }

    @Test
    fun `a rotated push token removes the old one from the visitor`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        repeat(2) { take() }

        server.enqueue(Fixtures.noContent()) // DELETE fcm-1
        server.enqueue(Fixtures.noContent()) // POST fcm-2
        assertTrue(manager.setPushToken("fcm-2"))
        manager.syncDevice()
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("fcm-1", Fixtures.json(delete).getString("token"))
        val register = take()
        assertEquals("POST", register.method)
        assertEquals("fcm-2", Fixtures.json(register).getString("token"))
    }

    @Test
    fun `leaving a site removes the device from its visitor`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        repeat(2) { take() }

        server.enqueue(Fixtures.noContent())
        manager.leaveSite()
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("Bearer tok-1", delete.getHeader("Authorization"))
        assertNull(store.get(StoreKeys.DEVICE))
    }

    @Test
    fun `an unreadable visitor key never becomes a new visitor`() {
        val unreadable = object : KeyValueStore by store {
            override fun isUnreadable(key: String) = key == StoreKeys.VISITOR_KEY
        }
        val blocked = SessionManager(
            ApiClient(server.apiUrl(), "ZebChat-Android/test"),
            unreadable,
            Fixtures.SITE_KEY,
            Fixtures.client,
            clock = { now },
        )
        assertNull(blocked.session())
        assertNoMoreRequests()
    }

    @Test
    fun `a permanent refusal of the session is reported, a success clears it`() {
        server.enqueue(Fixtures.error(403, "app_not_allowed"))
        assertNull(manager.session())
        assertEquals(403, manager.refusedStatus)
        server.enqueue(Fixtures.error(503))
        assertNull(manager.session())
        assertNull("a server error is not a refusal", manager.refusedStatus)
        server.enqueue(Fixtures.error(403, "app_not_allowed"))
        manager.session()
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        assertNotNull(manager.session())
        assertNull(manager.refusedStatus)
    }

    @Test
    fun `identify sends an id only with its hash`() {
        val body = ApiClient.identityBody(ZebChatUser(id = "42", name = " Ana ", phone = ""))
        assertFalse(body.has("id"))
        assertFalse(body.has("hash"))
        assertFalse(body.has("phone"))
        assertEquals("Ana", body.getString("name"))
    }

    @Test
    fun `logout deletes the device before forgetting the visitor key`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        manager.setUser(ZebChatUser(id = "42", hash = "abc"))
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.session("tok-user", "vis_user", verified = true))
        server.enqueue(Fixtures.noContent()) // device
        manager.session()
        repeat(3) { take() }

        server.enqueue(Fixtures.noContent()) // DELETE /devices
        manager.logout()
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/widget/devices", delete.path)
        assertEquals("Bearer tok-user", delete.getHeader("Authorization"))
        assertEquals("fcm-1", Fixtures.json(delete).getString("token"))
        assertNull(store.get(StoreKeys.VISITOR_KEY))
        assertNull(store.get(StoreKeys.USER))
        assertNull(store.get(StoreKeys.SESSION))
        assertNull(store.get(StoreKeys.DEVICE_MOVE))
        assertEquals("the push token stays with the app", "fcm-1", store.get(StoreKeys.PUSH_TOKEN))

        // Next session: a new anonymous visitor (no key sent), and the device goes to it.
        server.enqueue(Fixtures.session("tok-new", "vis_new", visitorKey = "n".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        assertFalse(Fixtures.json(take()).has("visitorKey"))
        assertEquals("Bearer tok-new", take().getHeader("Authorization"))
    }

    @Test
    fun `logout with an expired session re-creates it to delete the device first`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        repeat(2) { take() }
        now = IsoTime.parse(Fixtures.EXPIRES)!! + 1

        server.enqueue(Fixtures.session("tok-2", "vis_1"))
        server.enqueue(Fixtures.noContent())
        manager.logout()
        val session = take()
        assertEquals("/api/v1/widget/sessions", session.path)
        assertEquals("the stored key is still there for the delete", "k".repeat(43), Fixtures.json(session).getString("visitorKey"))
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("Bearer tok-2", delete.getHeader("Authorization"))
        assertNull(store.get(StoreKeys.VISITOR_KEY))
    }

    @Test
    fun `a logout that cannot delete the device moves it on the next sync`() {
        store.put(StoreKeys.PUSH_TOKEN, "fcm-1")
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        repeat(2) { take() }

        server.enqueue(Fixtures.error(503))
        manager.logout()
        take()
        assertEquals("1", store.get(StoreKeys.DEVICE_MOVE))
        assertNull(store.get(StoreKeys.VISITOR_KEY))

        server.enqueue(Fixtures.session("tok-new", "vis_new", visitorKey = "n".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.syncDevice()
        take()
        val device = take()
        assertEquals("POST", device.method)
        assertEquals("/api/v1/widget/devices", device.path)
        assertNull(store.get(StoreKeys.DEVICE_MOVE))
    }

    @Test
    fun `a push token is registered once per visitor and only when a visitor exists`() {
        assertTrue(manager.setPushToken("fcm-1"))
        manager.syncDevice()
        assertNoMoreRequests() // no visitor yet: nothing to register

        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.session()
        take()
        assertEquals("/api/v1/widget/devices", take().path)
        manager.syncDevice()
        assertFalse(manager.setPushToken("fcm-1"))
        assertNoMoreRequests()

        server.enqueue(Fixtures.noContent()) // DELETE fcm-1 (rotated)
        assertTrue(manager.setPushToken("fcm-2"))
        assertEquals("DELETE", take().method)
        server.enqueue(Fixtures.noContent())
        manager.syncDevice()
        assertEquals("fcm-2", Fixtures.json(take()).getString("token"))
    }

    @Test
    fun `screen views carry the client info and wait offline`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.trackScreen("Checkout")
        take()
        val view = take()
        assertEquals("/api/v1/widget/pageviews", view.path)
        assertEquals("Bearer tok-1", view.getHeader("Authorization"))
        val body = Fixtures.json(view)
        assertEquals("app://com.example.shop/Checkout", body.getString("url"))
        assertEquals("Checkout", body.getString("title"))
        val client = body.getJSONObject("client")
        assertEquals("android", client.getString("platform"))
        assertEquals(Fixtures.APP_ID, client.getString("appId"))
        assertEquals("2.1.0", client.getString("appVersion"))
        assertEquals("android/1.0.0", client.getString("sdk"))
        assertEquals("15", client.getString("osVersion"))
        assertEquals("Google Pixel 9", client.getString("deviceModel"))
        assertEquals("phone", client.getString("deviceType"))

        // Offline: kept (at most 20, oldest dropped), sent in order once back.
        repeat(25) { i ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            manager.trackScreen("Screen $i")
            take()
        }
        assertEquals(SessionManager.MAX_SCREENS, manager.pendingScreens())
        repeat(20) { server.enqueue(Fixtures.noContent()) }
        manager.flush()
        val titles = (0 until 20).map { Fixtures.json(take()).getString("title") }
        assertEquals((5 until 25).map { "Screen $it" }, titles)
        assertEquals(0, manager.pendingScreens())
    }

    @Test
    fun `custom events carry name, properties and client info, and wait offline`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.noContent())
        manager.track("added_to_cart", mapOf("sku" to "A1", "price" to "9.99"))
        take()
        val event = take()
        assertEquals("POST", event.method)
        assertEquals("/api/v1/widget/events", event.path)
        assertEquals("Bearer tok-1", event.getHeader("Authorization"))
        val body = Fixtures.json(event)
        assertEquals("added_to_cart", body.getString("name"))
        assertEquals("A1", body.getJSONObject("properties").getString("sku"))
        assertEquals("9.99", body.getJSONObject("properties").getString("price"))
        assertEquals("android", body.getJSONObject("client").getString("platform"))
        assertEquals(Fixtures.APP_ID, body.getJSONObject("client").getString("appId"))

        // No properties: none sent.
        server.enqueue(Fixtures.noContent())
        manager.track("signed_up", null)
        assertFalse(Fixtures.json(take()).has("properties"))

        // Invalid names and properties over 2 KB never reach the API.
        manager.track("", null)
        manager.track("has space", null)
        manager.track("x".repeat(65), null)
        manager.track("big", mapOf("blob" to "x".repeat(2100)))
        assertNoMoreRequests()
        assertEquals(0, manager.pendingEvents())

        // Offline: kept (at most 20, oldest dropped), sent in order once back, after screens.
        repeat(25) { i ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            manager.track("event_$i", null)
            take()
        }
        assertEquals(SessionManager.MAX_EVENTS, manager.pendingEvents())
        repeat(20) { server.enqueue(Fixtures.noContent()) }
        manager.flush()
        val names = (0 until 20).map { Fixtures.json(take()).getString("name") }
        assertEquals((5 until 25).map { "event_$it" }, names)
        assertEquals(0, manager.pendingEvents())
    }

    @Test
    fun `an event the API refuses is dropped, a 401 keeps it for a new session`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.error(400, "name is invalid"))
        manager.track("refused", null)
        repeat(2) { take() }
        assertEquals(0, manager.pendingEvents())

        server.enqueue(Fixtures.error(401))
        manager.track("later", null)
        take()
        assertEquals(1, manager.pendingEvents())
        assertNull(store.get(StoreKeys.SESSION))
    }

    @Test
    fun `a screen view the API refuses is dropped, a 401 drops the session`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        server.enqueue(Fixtures.error(400, "url is invalid"))
        manager.trackScreen("x".repeat(400))
        repeat(2) { take() }
        assertEquals(0, manager.pendingScreens())

        server.enqueue(Fixtures.error(401))
        manager.trackScreen("Home")
        take()
        assertEquals(1, manager.pendingScreens())
        assertNull(store.get(StoreKeys.SESSION))
        manager.trackScreen("   ")
        assertEquals(1, manager.pendingScreens())
    }

    @Test
    fun `unread count reads the current conversation`() {
        assertEquals("no visitor: 0 without a request", 0, manager.unreadCount())
        assertNoMoreRequests()

        store.put(StoreKeys.VISITOR_KEY, "k".repeat(43))
        server.enqueue(Fixtures.session("tok-1", "vis_1"))
        server.enqueue(MockResponse().setBody("""{"id":"conv_1","status":"open","lastSeq":4,"visitorUnreadCount":3}"""))
        assertEquals(3, manager.unreadCount())
        take()
        val current = take()
        assertEquals("GET", current.method)
        assertEquals("/api/v1/widget/conversations/current", current.path)

        server.enqueue(MockResponse().setBody(""))
        assertEquals(0, manager.unreadCount())
        server.enqueue(MockResponse().setBody("null"))
        assertEquals(0, manager.unreadCount())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertNull("offline: unknown", manager.unreadCount())
    }

    @Test
    fun `renewed tokens replace the cached one and expired forces a new session`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        manager.session()
        take()
        manager.renewed("tok-renewed", "2026-10-09T06:00:00.000Z")
        val renewed = manager.session()
        assertEquals("tok-renewed", renewed?.token)
        assertEquals("2026-10-09T06:00:00.000Z", renewed?.expiresAt)
        manager.renewed("tok-bad", "never")
        assertEquals("tok-renewed", manager.session()?.token)

        server.enqueue(Fixtures.session("tok-fresh", "vis_1"))
        assertEquals("tok-fresh", manager.freshSession()?.token)
    }

    @Test
    fun `unexpected session answers and network errors give no session`() {
        server.enqueue(MockResponse().setBody("""{"visitorToken":"t","visitorTokenExpiresAt":"bad","visitor":{"id":"v"}}"""))
        assertNull(manager.session())
        server.enqueue(MockResponse().setBody("""{"visitorToken":"t","visitorTokenExpiresAt":"2026-10-09T00:00:00Z","visitor":{}}"""))
        assertNull(manager.session())
        server.enqueue(Fixtures.error(403, "app_not_allowed"))
        assertNull(manager.session())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertNull(manager.session())
        assertNull(store.get(StoreKeys.VISITOR_KEY))
        assertNotNull(server)
    }
}
