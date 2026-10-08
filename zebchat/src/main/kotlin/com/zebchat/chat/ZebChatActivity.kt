package com.zebchat.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.zebchat.chat.internal.Bridge
import com.zebchat.chat.internal.JsBridge
import com.zebchat.chat.internal.LinkPolicy
import com.zebchat.chat.internal.Notifications
import com.zebchat.chat.internal.PageMessage
import com.zebchat.chat.internal.Session
import com.zebchat.chat.internal.ZcLog
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * The full-screen chat: `mobile.html` from the ZebChat CDN in a WebView, handed the visitor
 * session over bridge v1 (docs/MOBILE_SDK.md §2). Open it with [ZebChat.show].
 */
public class ZebChatActivity : ComponentActivity() {
    private lateinit var config: ZebChat.ChatConfig
    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorBody: TextView
    private var webView: WebView? = null

    /** True with the JavaScript-interface fallback: frames are then limited to the CDN and Turnstile. */
    private var fallbackBridge = false

    /** `target=_blank` catchers not yet resolved (destroyed on resolve, timeout or destroy). */
    private val popups = mutableSetOf<WebView>()

    /** Between onStart and onStop: chat notifications are not shown then. */
    @Volatile
    internal var isStarted: Boolean = false
        private set

    /** Bumped on every (re)load, so a late session never reaches a newer page. */
    private var generation = 0
    private var pageReady = false
    private var destroyed = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onFileChooserResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        config = ZebChat.chatConfig(this) ?: run {
            ZcLog.w("The chat was opened before ZebChat.configure")
            finish()
            return
        }
        buildViews()
        onBackPressedDispatcher.addCallback(this) { close() }
        ZebChat.attachChat(this)
        load()
    }

    /** singleTop: `show()` or a notification tap while the chat is open reuses it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep "opened from a notification" once set: closing then still lands in the app.
        if (!this.intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        // Idempotent: keeps this chat reachable for setUser / logout whatever else was opened.
        ZebChat.attachChat(this)
        if (::errorView.isInitialized && errorView.visibility != View.VISIBLE) webView?.visibility = View.VISIBLE
    }

    override fun onStop() {
        isStarted = false
        // Real WebView visibility drives the page's `visibilitychange`: hidden → it disconnects its
        // visitor socket, so the visitor counts as offline and gets pushes (docs/MOBILE_SDK.md §2).
        // The window going away on stop hides it too; this makes it explicit on every WebView.
        webView?.visibility = View.INVISIBLE
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // Also after a stop (file picker, another app): drops what arrived meanwhile.
        Notifications.cancelAll(this)
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        isStarted = false
        ZebChat.detachChat(this)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        deleteCameraFile()
        popups.toList().forEach { destroyPopup(it) }
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        if (isFinishing) ZebChat.onChatClosed()
        super.onDestroy()
    }

    /** `setUser` / `logout` while the chat is open: hand the page the new session. */
    internal fun onSessionChanged() {
        if (pageReady) sendSession(fresh = false)
    }

    // ---- views -----------------------------------------------------------------------------

    private fun buildViews() {
        root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@ZebChatActivity, R.color.zebchat_background))
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        errorView = buildErrorView().apply { visibility = View.GONE }
        root.addView(errorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        // Edge-to-edge (targetSdk 35): keep the page clear of the system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        createWebView()
    }

    private fun buildErrorView(): View {
        val text = ContextCompat.getColor(this, R.color.zebchat_text)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                setText(R.string.zebchat_error_title)
                textSize = 18f
                setTextColor(text)
                gravity = Gravity.CENTER
            })
            errorBody = TextView(context).apply {
                setText(R.string.zebchat_error_body)
                setTextColor(text)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(16))
            }
            addView(errorBody)
            addView(Button(context).apply {
                setText(R.string.zebchat_retry)
                setOnClickListener { retry() }
            })
            addView(Button(context).apply {
                setText(R.string.zebchat_close)
                setOnClickListener { close() }
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val view = try {
            WebView(this)
        } catch (e: RuntimeException) {
            // No WebView provider (being updated or disabled).
            ZcLog.w("WebView unavailable", e)
            showError()
            return
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
        }
        view.webViewClient = PageClient()
        view.webChromeClient = ChromeClient()
        view.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            onDownload(url, contentDisposition, mimeType)
        }
        installBridge(view)
        root.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = view
    }

    /**
     * Page → native channel: a WebMessageListener limited to the CDN origin and the main frame,
     * else (old WebView) a JavaScript interface checked against the loaded page's origin.
     */
    private fun installBridge(view: WebView) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(view, Bridge.CHANNEL, setOf(config.cdnOrigin)) { _, message, sourceOrigin, isMainFrame, _ ->
                    if (Bridge.accepts(sourceOrigin.toString(), isMainFrame, config.cdnOrigin)) onPageMessage(message.data)
                }
                return
            }
        } catch (e: RuntimeException) {
            // Origin rule refused, or the WebView provider does not implement it after all.
            ZcLog.w("Web message listener unavailable; using the fallback", e)
        }
        fallbackBridge = true
        view.addJavascriptInterface(
            JsBridge { raw -> runOnUiThread { if (isCdnPage()) onPageMessage(raw) } },
            Bridge.CHANNEL,
        )
    }

    private fun isCdnPage(): Boolean = LinkPolicy.isOrigin(webView?.url, config.cdnOrigin)

    private fun load() {
        generation++
        pageReady = false
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        val view = webView ?: return
        view.visibility = View.VISIBLE
        view.loadUrl(config.pageUrl)
    }

    private fun retry() {
        if (webView == null) createWebView()
        if (webView != null) load()
    }

    /** [refused]: the API refused this app for good (site key, allowed apps), not a network error. */
    private fun showError(refused: Boolean = false) {
        generation++
        pageReady = false
        errorBody.setText(if (refused) R.string.zebchat_error_refused else R.string.zebchat_error_body)
        progress.visibility = View.GONE
        webView?.visibility = View.INVISIBLE
        errorView.visibility = View.VISIBLE
    }

    private fun close() {
        if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) && isTaskRoot) {
            // Opened from a notification with the app closed: land in the app, not the home screen.
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                try {
                    startActivity(launch)
                } catch (e: ActivityNotFoundException) {
                    ZcLog.w("Cannot open the app", e)
                }
            }
        }
        finish()
    }

    // ---- bridge ----------------------------------------------------------------------------

    internal fun onPageMessage(raw: String?) {
        if (destroyed) return
        when (val message = Bridge.parse(raw)) {
            PageMessage.Ready -> {
                pageReady = true
                sendSession(fresh = false)
            }
            is PageMessage.Renewed -> ZebChat.onRenewed(message.token, message.expiresAt)
            PageMessage.Expired -> sendSession(fresh = true)
            PageMessage.Close -> close()
            is PageMessage.Error -> ZcLog.w("Chat page error ${message.status}: ${message.message.take(200)}")
            null -> ZcLog.d("Ignored a bridge message")
        }
    }

    private fun sendSession(fresh: Boolean) {
        val expected = generation
        ZebChat.loadSession(fresh) { session, refused -> deliver(expected, session, refused) }
    }

    private fun deliver(expected: Int, session: Session?, refused: Boolean) {
        if (destroyed || expected != generation) return
        if (session == null) {
            showError(refused)
            return
        }
        val view = webView ?: return
        if (!isCdnPage()) return
        val init = Bridge.initMessage(session.token, session.expiresAt, session.visitorJson)
        view.evaluateJavascript(Bridge.receiveScript(init), null)
        progress.visibility = View.GONE
    }

    // ---- navigation --------------------------------------------------------------------------

    /** True when the navigation was handled natively (the WebView must not load it). */
    private fun route(url: String): Boolean = when (LinkPolicy.classify(url, config.cdnOrigin, config.apiOrigin)) {
        LinkPolicy.Action.LOAD -> false
        LinkPolicy.Action.DOWNLOAD -> {
            download(url, null, null)
            true
        }
        LinkPolicy.Action.EXTERNAL -> {
            openExternal(url)
            true
        }
        LinkPolicy.Action.BLOCK -> true
    }

    private fun openExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            ZcLog.w("No app opens this link", e)
        }
    }

    private fun onDownload(url: String, contentDisposition: String?, mimeType: String?) {
        when (LinkPolicy.classify(url, config.cdnOrigin, config.apiOrigin)) {
            LinkPolicy.Action.DOWNLOAD -> download(url, contentDisposition, mimeType)
            LinkPolicy.Action.EXTERNAL -> openExternal(url)
            else -> ZcLog.d("Ignored a download")
        }
    }

    /** Signed `/api/v1/files/` links: DownloadManager into Downloads (or the browser if not allowed). */
    private fun download(url: String, contentDisposition: String?, mimeType: String?) {
        val manager = getSystemService(DownloadManager::class.java)
        val canWritePublic = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (manager == null || !canWritePublic) {
            openExternal(url)
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(url.toUri())
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            mimeType?.takeIf { it.isNotBlank() }?.let { request.setMimeType(it) }
            manager.enqueue(request)
            Toast.makeText(this, R.string.zebchat_download_started, Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            ZcLog.w("Download failed; opening the link instead", e)
            openExternal(url)
        }
    }

    // ---- file chooser ------------------------------------------------------------------------

    private fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val content = try {
            params.createIntent()
        } catch (e: RuntimeException) {
            ZcLog.w("File chooser intent failed", e)
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        }
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val chooser = Intent.createChooser(content, params.title ?: getString(R.string.zebchat_choose_file))
        cameraIntent(params.acceptTypes)?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
        try {
            fileChooser.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            ZcLog.w("No app picks files", e)
            deliverFiles(null)
        }
        return true
    }

    /**
     * A photo capture entry, only when the host app declares and holds CAMERA (the SDK does not
     * declare it: with CAMERA declared but not granted, the capture intent would fail).
     */
    private fun cameraIntent(acceptTypes: Array<String>?): Intent? {
        val types = acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val wantsImages = types.isEmpty() || types.any { it.startsWith("image/") || it == "*/*" }
        if (!wantsImages || !hostHoldsCamera()) return null
        return try {
            val dir = File(cacheDir, "zebchat_camera").apply { mkdirs() }
            val file = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.zebchat.files", file)
            cameraFile = file
            cameraUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("", uri) }
                .takeIf { it.resolveActivity(packageManager) != null }
        } catch (e: IOException) {
            ZcLog.w("Camera capture unavailable", e)
            null
        } catch (e: IllegalArgumentException) {
            ZcLog.w("Camera capture unavailable", e)
            null
        }
    }

    private fun hostHoldsCamera(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val requested = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }.requestedPermissions
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return requested?.contains(Manifest.permission.CAMERA) == true
    }

    private fun onFileChooserResult(result: ActivityResult) {
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val picked = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { picked.add(it) }
            }
            if (picked.isEmpty()) data?.data?.let { picked.add(it) }
            val photo = cameraUri
            val photoTaken = (cameraFile?.length() ?: 0L) > 0L
            if (photo != null && photoTaken && (picked.isEmpty() || picked == listOf(photo))) {
                uris = arrayOf(photo)
                cameraFile = null // in use by the upload; the cache directory is cleaned next time
            } else if (picked.isNotEmpty()) {
                uris = picked.filter { it != photo }.toTypedArray().takeIf { it.isNotEmpty() }
            }
        }
        deleteCameraFile()
        deliverFiles(uris)
    }

    /** Always answers the page (null on cancel), or the WebView stops offering the chooser. */
    private fun deliverFiles(uris: Array<Uri>?) {
        val callback = fileCallback ?: return
        fileCallback = null
        callback.onReceiveValue(uris)
    }

    private fun deleteCameraFile() {
        cameraFile?.delete()
        cameraFile = null
        cameraUri = null
    }

    // ---- clients -----------------------------------------------------------------------------

    private inner class PageClient : WebViewClientCompat() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames (the Turnstile check) load normally; only the main frame is locked. With the
            // JavaScript-interface fallback (visible to every frame) frames stay on the CDN and
            // Turnstile too.
            if (!request.isForMainFrame) {
                return fallbackBridge && !LinkPolicy.isAllowedFrame(request.url.toString(), config.cdnOrigin)
            }
            return route(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null && !LinkPolicy.isOrigin(url, config.cdnOrigin) && url != "about:blank") {
                // Never show anything but the chat page (e.g. a server-side redirect elsewhere).
                view.stopLoading()
                showError()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (LinkPolicy.isOrigin(url, config.cdnOrigin) && errorView.visibility != View.VISIBLE) {
                progress.visibility = View.GONE
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
            if (request.isForMainFrame) showError()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) showError()
        }

        @RequiresApi(26)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer crashed or was killed: drop this WebView and offer a reload.
            if (view === webView) {
                root.removeView(view)
                view.destroy()
                webView = null
                showError()
            }
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            // `target=_blank` / window.open: catch the URL in a throwaway WebView and route it.
            val popup = WebView(this@ZebChatActivity)
            popups.add(popup)
            popup.webViewClient = object : WebViewClientCompat() {
                private var handled = false

                private fun handle(target: WebView, url: String?): Boolean {
                    if (!handled && url != null && url != "about:blank") {
                        handled = true
                        val action = LinkPolicy.classify(url, config.cdnOrigin, config.apiOrigin)
                        if (action == LinkPolicy.Action.LOAD) openExternal(url) else route(url)
                        target.stopLoading()
                        // Never attached to a window, so its own post() would not run.
                        root.post { destroyPopup(target) }
                    }
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handle(view, request.url.toString())

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    handle(view, url)
                }
            }
            // A window that never navigates (window.open() with no URL) is dropped after a while.
            root.postDelayed({ destroyPopup(popup) }, POPUP_TIMEOUT_MS)
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = showFileChooser(filePathCallback, fileChooserParams)
    }

    private fun destroyPopup(popup: WebView) {
        if (popups.remove(popup)) popup.destroy()
    }

    /** Open `target=_blank` catchers (tests). */
    internal fun pendingPopups(): Int = popups.size

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal companion object {
        /** Set on the notification Intent: closing lands in the app when it was not running. */
        const val EXTRA_FROM_NOTIFICATION: String = "com.zebchat.chat.FROM_NOTIFICATION"

        const val POPUP_TIMEOUT_MS: Long = 10_000
    }
}
