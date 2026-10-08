package com.zebchat.chat

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.zebchat.chat.internal.Fixtures
import com.zebchat.chat.internal.Fixtures.apiUrl
import com.zebchat.chat.internal.JsBridge
import com.zebchat.chat.internal.MemoryStore
import com.zebchat.chat.internal.StoreKeys
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class ZebChatActivityTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryStore
    private lateinit var controller: ActivityController<ZebChatActivity>
    private lateinit var activity: ZebChatActivity
    private lateinit var webView: WebView

    private val cdn = "https://cdn.zebchat.test"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        store = MemoryStore()
        ZebChat.resetForTests()
        ZebChat.executor = java.util.concurrent.Executor { it.run() }
        ZebChat.storeFactory = { store }
        ZebChat.configure(
            app,
            Fixtures.SITE_KEY,
            ZebChatOptions(apiUrl = server.apiUrl(), widgetUrl = "$cdn/widget/v1/mobile.html"),
        )
        controller = Robolectric.buildActivity(ZebChatActivity::class.java).setup()
        activity = controller.get()
        webView = findWebView(activity.window.decorView)!!
    }

    @After
    fun tearDown() {
        ZebChat.resetForTests()
        server.shutdown()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Posts like `ZebChatAndroid.postMessage(json)` from the page. */
    private fun post(json: String) {
        val bridge = shadowOf(webView).getJavascriptInterface("ZebChatAndroid") as JsBridge
        bridge.postMessage(json)
        idle()
    }

    private fun lastInit(): JSONObject? {
        val script = shadowOf(webView).lastEvaluatedJavascript ?: return null
        val quoted = script.substringAfter("receive(").substringBeforeLast(");")
        return JSONObject(org.json.JSONTokener(quoted).nextValue() as String)
    }

    private fun request(url: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame() = mainFrame

        override fun isRedirect() = false

        override fun hasGesture() = true

        override fun getMethod() = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun client(): WebViewClient = shadowOf(webView).webViewClient

    @Test
    fun `loads the CDN page with a locked-down WebView`() {
        val url = Uri.parse(shadowOf(webView).lastLoadedUrl)
        assertEquals("cdn.zebchat.test", url.host)
        assertEquals("android", url.getQueryParameter("platform"))
        assertEquals(app.packageName, url.getQueryParameter("app"))
        assertFalse("the visitor key never goes into the URL", shadowOf(webView).lastLoadedUrl.contains("visitorKey"))
        val settings = webView.settings
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.domStorageEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
    }

    @Test
    fun `ready gets an init with the session and the full visitor`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        post("""{"type":"ready","v":1}""")
        val sessionRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/widget/sessions", sessionRequest.path)
        val init = lastInit()!!
        assertEquals("init", init.getString("type"))
        assertEquals(1, init.getInt("v"))
        assertEquals("tok-1", init.getString("token"))
        assertEquals(Fixtures.EXPIRES, init.getString("expiresAt"))
        val visitor = init.getJSONObject("visitor")
        assertEquals("vis_1", visitor.getString("id"))
        listOf("name", "email", "phone", "verified").forEach { assertTrue(it, visitor.has(it)) }
        assertFalse("the visitor key never enters the WebView", init.toString().contains("k".repeat(43)))
    }

    @Test
    fun `renewed updates the cached token, expired re-creates the session`() {
        server.enqueue(Fixtures.session("tok-1", "vis_1", visitorKey = "k".repeat(43)))
        post("""{"type":"ready","v":1}""")
        post("""{"type":"renewed","token":"tok-renewed","expiresAt":"2026-10-09T06:00:00.000Z"}""")
        assertTrue(store.get(StoreKeys.SESSION)!!.contains("tok-renewed"))

        server.enqueue(Fixtures.session("tok-2", "vis_1"))
        post("""{"type":"expired"}""")
        assertEquals("tok-2", lastInit()!!.getString("token"))
    }

    @Test
    fun `setUser while the chat is open sends a new init`() {
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        post("""{"type":"ready","v":1}""")
        server.enqueue(Fixtures.session("tok-anon-2", "vis_anon"))
        server.enqueue(Fixtures.session("tok-user", "vis_user", verified = true))
        server.enqueue(Fixtures.noContent()) // unread: no conversation
        ZebChat.setUser(ZebChatUser(id = "42", hash = "abc"))
        idle()
        val init = lastInit()!!
        assertEquals("tok-user", init.getString("token"))
        assertEquals("vis_user", init.getJSONObject("visitor").getString("id"))
    }

    @Test
    fun `ignores malformed messages and messages from other origins`() {
        post("not json")
        post("""{"type":"ready","v":2}""")
        post("""{"type":"init","v":1}""")
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

        webView.loadUrl("https://evil.test/")
        post("""{"type":"ready","v":1}""")
        post("""{"type":"close"}""")
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `close and back dismiss the chat`() {
        post("""{"type":"close"}""")
        assertTrue(activity.isFinishing)

        val again = Robolectric.buildActivity(ZebChatActivity::class.java).setup().get()
        again.onBackPressedDispatcher.onBackPressed()
        assertTrue(again.isFinishing)
    }

    @Test
    fun `the page is hidden while the Activity is stopped`() {
        // Real WebView visibility drives document.visibilityState in the page.
        assertEquals(View.VISIBLE, webView.visibility)
        controller.pause().stop()
        assertEquals(View.INVISIBLE, webView.visibility)
        controller.start().resume()
        assertEquals(View.VISIBLE, webView.visibility)
    }

    @Test
    fun `only the CDN loads in the WebView, links leave the app, files download`() {
        assertFalse(client().shouldOverrideUrlLoading(webView, request("$cdn/widget/v1/mobile.html?x=1")))
        assertFalse("frames (Turnstile) load", client().shouldOverrideUrlLoading(webView, request("https://challenges.cloudflare.com/x", mainFrame = false)))

        assertTrue(client().shouldOverrideUrlLoading(webView, request("https://example.com/help")))
        val external = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, external.action)
        assertEquals("https://example.com/help", external.dataString)

        assertTrue(client().shouldOverrideUrlLoading(webView, request("javascript:alert(1)")))
        assertNull(shadowOf(activity).nextStartedActivity)

        val file = "${server.apiUrl()}/api/v1/files/signed.token"
        assertTrue(client().shouldOverrideUrlLoading(webView, request(file)))
        val downloads = shadowOf(app.getSystemService(DownloadManager::class.java))
        assertEquals(1, downloads.requestCount)
        assertEquals(file, shadowOf(downloads.getRequest(0)).uri.toString())
    }

    @Test
    fun `the file chooser always answers the page`() {
        var answered = false
        var result: Array<Uri>? = arrayOf(Uri.EMPTY)
        val callback = ValueCallback<Array<Uri>> {
            answered = true
            result = it
        }
        val params = object : WebChromeClient.FileChooserParams() {
            override fun getMode() = MODE_OPEN

            override fun getAcceptTypes() = arrayOf("image/*")

            override fun isCaptureEnabled() = false

            override fun getTitle(): CharSequence? = null

            override fun getFilenameHint(): String? = null

            override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        assertTrue(shadowOf(webView).webChromeClient.onShowFileChooser(webView, callback, params))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started.intent.action)
        assertNull("no camera without the host's CAMERA permission", started.intent.extras?.containsKey(Intent.EXTRA_INITIAL_INTENTS)?.takeIf { it })

        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        assertTrue(answered)
        assertNull(result)

        // A picked file is delivered.
        answered = false
        shadowOf(webView).webChromeClient.onShowFileChooser(webView, callback, params)
        val next = shadowOf(activity).nextStartedActivityForResult
        val picked = Uri.parse("content://media/external/images/1")
        shadowOf(activity).receiveResult(next.intent, Activity.RESULT_OK, Intent().setData(picked))
        assertTrue(answered)
        assertEquals(listOf(picked), result?.toList())
    }

    @Test
    fun `a second chat Activity does not cut the first off`() {
        server.enqueue(Fixtures.session("tok-anon", "vis_anon", visitorKey = "k".repeat(43)))
        post("""{"type":"ready","v":1}""")
        assertEquals("tok-anon", lastInit()!!.getString("token"))

        // A second chat on top (e.g. started by the host without show()), then closed.
        controller.pause()
        val second = Robolectric.buildActivity(ZebChatActivity::class.java).setup()
        controller.stop()
        second.pause().stop().destroy()

        // setUser while the first one is still in the background: its page gets the new session.
        server.enqueue(Fixtures.session("tok-anon-2", "vis_anon"))
        server.enqueue(Fixtures.session("tok-user", "vis_user", verified = true))
        server.enqueue(Fixtures.noContent()) // unread
        ZebChat.setUser(ZebChatUser(id = "42", hash = "abc"))
        idle()
        assertEquals("tok-user", lastInit()!!.getString("token"))

        // Back on screen: still the chat that suppresses notifications.
        controller.start().resume()
        assertTrue(ZebChat.chatStarted())
    }

    @Test
    fun `no chat notification while the chat is open, and none left when it comes back`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val data = mapOf("zebchat" to "1", "conversationId" to "conv_1", "siteKey" to Fixtures.SITE_KEY)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertFalse(ZebChat.showNotification(app, data))
        controller.pause() // a permission prompt or a share sheet over the chat
        assertFalse(ZebChat.showNotification(app, data))
        assertEquals(0, notifications.allNotifications.size)

        controller.stop() // the app went to the background
        assertTrue(ZebChat.showNotification(app, data))
        assertEquals(1, notifications.allNotifications.size)
        controller.start().resume()
        assertEquals("cleared when the chat is back", 0, notifications.allNotifications.size)
    }

    @Test
    fun `a refused app sees a configuration message, not a connection error`() {
        server.enqueue(Fixtures.error(403, "app_not_allowed"))
        post("""{"type":"ready","v":1}""")
        assertTrue(texts(activity.window.decorView).contains(app.getString(R.string.zebchat_error_refused)))

        server.enqueue(Fixtures.error(503))
        activity.window.decorView.findViewWithText(app.getString(R.string.zebchat_retry)).performClick()
        post("""{"type":"ready","v":1}""")
        val shown = texts(activity.window.decorView)
        assertTrue(shown.contains(app.getString(R.string.zebchat_error_body)))
        assertFalse(shown.contains(app.getString(R.string.zebchat_error_refused)))
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> if (view.isShown) listOf(view.text.toString()) else emptyList()
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun View.findViewWithText(text: String): View {
        val found = ArrayList<View>()
        findViewsWithText(found, text, View.FIND_VIEWS_WITH_TEXT)
        return found.first()
    }

    @Test
    fun `with the fallback bridge, frames stay on the CDN and Turnstile`() {
        // Robolectric has no WebMessageListener: the JavaScript interface fallback is in use.
        assertNotNull(shadowOf(webView).getJavascriptInterface("ZebChatAndroid"))
        assertFalse(client().shouldOverrideUrlLoading(webView, request("$cdn/widget/v1/frame.html", mainFrame = false)))
        assertFalse(client().shouldOverrideUrlLoading(webView, request("https://challenges.cloudflare.com/x", mainFrame = false)))
        assertTrue(client().shouldOverrideUrlLoading(webView, request("https://evil.test/frame", mainFrame = false)))
        assertNull("a blocked frame opens nothing", shadowOf(activity).nextStartedActivity)
    }

    private fun windowMessage(): Message =
        Message.obtain(Handler(Looper.getMainLooper())).apply { obj = webView.WebViewTransport() }

    @Test
    fun `window open needs a user gesture and never leaks the catcher WebView`() {
        val chrome = shadowOf(webView).webChromeClient
        assertFalse(chrome.onCreateWindow(webView, false, false, windowMessage()))
        assertEquals(0, activity.pendingPopups())
        assertNull(shadowOf(activity).nextStartedActivity)

        // A window that never navigates is dropped after the timeout.
        assertTrue(chrome.onCreateWindow(webView, false, true, windowMessage()))
        assertEquals(1, activity.pendingPopups())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ZebChatActivity.POPUP_TIMEOUT_MS + 1))
        assertEquals(0, activity.pendingPopups())

        // ... and with the chat.
        assertTrue(chrome.onCreateWindow(webView, false, true, windowMessage()))
        assertEquals(1, activity.pendingPopups())
        controller.pause().stop().destroy()
        assertEquals(0, activity.pendingPopups())
    }

    @Test
    fun `downloads outside the signed files path open in the browser`() {
        val listener = shadowOf(webView).downloadListener
        val config = "${server.apiUrl()}/api/v1/widget/config"
        listener.onDownloadStart(config, "ua", null, "application/json", 10)
        val external = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, external.action)
        assertEquals(config, external.dataString)

        listener.onDownloadStart("javascript:alert(1)", "ua", null, "text/html", 10)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, shadowOf(app.getSystemService(DownloadManager::class.java)).requestCount)
    }

    @Test
    fun `the file chooser offers the camera when the host declares and holds CAMERA`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val packages = shadowOf(app.packageManager)
        packages.getInternalMutablePackageInfo(app.packageName).requestedPermissions = arrayOf(Manifest.permission.CAMERA)
        @Suppress("DEPRECATION") // the simplest way to make an Intent resolvable in Robolectric
        packages.addResolveInfoForIntent(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.example.camera"
                    name = "com.example.camera.Capture"
                }
            },
        )
        val params = object : WebChromeClient.FileChooserParams() {
            override fun getMode() = MODE_OPEN

            override fun getAcceptTypes() = arrayOf("image/*")

            override fun isCaptureEnabled() = false

            override fun getTitle(): CharSequence? = null

            override fun getFilenameHint(): String? = null

            override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        assertTrue(shadowOf(webView).webChromeClient.onShowFileChooser(webView, {}, params))
        val chooser = shadowOf(activity).nextStartedActivityForResult.intent
        @Suppress("DEPRECATION")
        val initial = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)
        if (File.separatorChar != '/') {
            // FileProvider matches its roots with a literal '/', so it cannot work on a Windows
            // file system: check the capture got as far as FileProvider (CAMERA declared + held).
            val logs = ShadowLog.getLogsForTag("ZebChat").map { it.msg }
            assertTrue(logs.toString(), logs.any { it.contains("configured root") })
            return
        }
        assertEquals(1, initial?.size)
        val capture = initial!![0] as Intent
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, capture.action)
        @Suppress("DEPRECATION")
        val output = capture.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        assertEquals("content", output?.scheme)
        assertEquals("${app.packageName}.zebchat.files", output?.authority)
    }

    @Test
    fun `the page is kept clear of the system bars and the keyboard`() {
        val root = webView.parent as ViewGroup
        fun apply(imeBottom: Int) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        apply(imeBottom = 0)
        assertEquals(24, root.paddingTop)
        assertEquals(48, root.paddingBottom)
        apply(imeBottom = 300)
        assertEquals("the keyboard, not keyboard + navigation bar", 300, root.paddingBottom)
    }

    @Test
    fun `opened before configure, the chat closes itself`() {
        ZebChat.resetForTests()
        ZebChat.storeFactory = { MemoryStore() }
        val orphan = Robolectric.buildActivity(ZebChatActivity::class.java).setup().get()
        assertTrue(orphan.isFinishing)
    }
}
