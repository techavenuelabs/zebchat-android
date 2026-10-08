package com.zebchat.chat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import com.zebchat.chat.internal.ApiClient
import com.zebchat.chat.internal.ClientInfo
import com.zebchat.chat.internal.KeyValueStore
import com.zebchat.chat.internal.KeystoreCipher
import com.zebchat.chat.internal.LinkPolicy
import com.zebchat.chat.internal.Notifications
import com.zebchat.chat.internal.PrefsStore
import com.zebchat.chat.internal.Session
import com.zebchat.chat.internal.SessionManager
import com.zebchat.chat.internal.StoreKeys
import com.zebchat.chat.internal.ZcLog
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject

/**
 * ZebChat for Android: the full-screen chat for your app's users (docs/MOBILE_SDK.md §5).
 *
 * Call [configure] once, ideally in `Application.onCreate`. Every other call is safe before
 * that: it logs a warning and does nothing. Network work runs on a background thread; unread
 * listeners are called on the main thread.
 */
public object ZebChat {
    /** The SDK version (`com.zebchat:chat`). */
    @JvmField
    public val VERSION: String = BuildConfig.SDK_VERSION

    private val SITE_KEY = Regex("^zc_[A-Za-z0-9_]{3,64}$")
    private val LOCALE = Regex("^(auto|[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,3})$")
    private val SDK_NAME = Regex("^[a-z0-9-]{1,20}/[0-9A-Za-z.+-]{1,19}$")

    /** The CONFIG field holding the language chosen with [setLocale]. */
    private const val KEY_CHAT_LOCALE = "chatLocale"

    private class State(
        val siteKey: String,
        val options: ZebChatOptions,
        val client: ClientInfo,
        val store: KeyValueStore,
        val manager: SessionManager,
        val cdnOrigin: String,
        val apiOrigin: String,
    )

    /** What the chat Activity needs to load the page. */
    internal data class ChatConfig(val pageUrl: String, val cdnOrigin: String, val apiOrigin: String)

    @Volatile
    private var state: State? = null

    @Volatile
    private var locale: String = "auto"

    @Volatile
    private var unread: Int = 0

    /** Every chat Activity between onCreate and onDestroy (normally one: it is singleTop). */
    private val chats = CopyOnWriteArrayList<WeakReference<ZebChatActivity>>()

    private val listeners = CopyOnWriteArraySet<UnreadListener>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var callbacksRegistered = false
    private var startedActivities = 0

    @Volatile
    private var sharedStore: KeyValueStore? = null

    /** Runs the SDK's blocking work in order. Replaced in tests. */
    @Volatile
    internal var executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZebChat").apply { isDaemon = true }
    }

    /** Creates the persistent store. Replaced in tests. */
    @Volatile
    internal var storeFactory: (Context) -> KeyValueStore = { PrefsStore(it, KeystoreCipher()) }

    // ---- public API ------------------------------------------------------------------------

    /**
     * Sets up ZebChat for the website with [siteKey] (`zc_…`, dashboard → Websites). Add your
     * app id (package name) to the website's allowed apps first. Calling it again with the
     * same values does nothing.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, siteKey: String, options: ZebChatOptions = ZebChatOptions()) {
        if (!SITE_KEY.matches(siteKey)) {
            ZcLog.w("configure: \"$siteKey\" is not a site key (zc_…); ZebChat stays off")
            return
        }
        val cdnOrigin = LinkPolicy.originOf(options.widgetUrl)
        val apiOrigin = LinkPolicy.originOf(options.apiUrl)
        if (cdnOrigin == null || apiOrigin == null) {
            ZcLog.w("configure: apiUrl and widgetUrl must be http(s) URLs; ZebChat stays off")
            return
        }
        val app = context.applicationContext ?: context
        val visible: Boolean
        val next: State
        val configuredLocale: String
        synchronized(this) {
            val current = state
            if (current != null && current.siteKey == siteKey && current.options == options) return
            ZcLog.debug = options.debugLogging
            // In memory only: disk and Keystore work runs first on the SDK's ordered thread.
            val store = store(app)
            val sdk = options.sdk?.takeIf { SDK_NAME.matches(it) } ?: "android/$VERSION"
            val client = ClientInfo.from(app, sdk)
            val manager = SessionManager(ApiClient(options.apiUrl, userAgent(client)), store, siteKey, client)
            configuredLocale = options.locale.takeIf { LOCALE.matches(it) } ?: "auto"
            locale = configuredLocale
            next = State(siteKey, options, client, store, manager, cdnOrigin, apiOrigin)
            state = next
            registerCallbacks(app)
            visible = startedActivities > 0
        }
        background {
            applyStoredConfig(next, configuredLocale)
            Notifications.ensureChannel(app)
        }
        // Already in the foreground (configured late): do now what the next foreground would do.
        if (visible) onForeground()
    }

    /** Identifies your signed-in user ([ZebChatUser.hash] is computed on your server). */
    @JvmStatic
    public fun setUser(user: ZebChatUser) {
        val s = state ?: return notConfigured("setUser")
        background {
            if (s.manager.setUser(user)) {
                updateUnread(s.manager.unreadCount())
                notifyChatSessionChanged()
            }
        }
    }

    /** Signs the user out of the chat: removes this device's push registration, then forgets the visitor. */
    @JvmStatic
    public fun logout() {
        val s = state ?: return notConfigured("logout")
        background {
            s.manager.logout()
            updateUnread(0)
            notifyChatSessionChanged()
        }
    }

    /** Opens the full-screen chat. */
    @JvmStatic
    public fun show(activity: Activity) {
        val s = state ?: return notConfigured("show")
        activity.startActivity(chatIntent(activity))
        background { s.manager.session() }
    }

    /** Records a screen view agents see live (`app://<app id>/<screen>`). Buffered offline (≤ 20). */
    @JvmStatic
    public fun trackScreen(screen: String) {
        val s = state ?: return notConfigured("trackScreen")
        background { s.manager.trackScreen(screen) }
    }

    /**
     * Records a custom event agents see in the visitor's journey, like `ZebChat.track()` on the
     * web: [name] is 1–64 of `A-Z a-z 0-9 _ . : -`, [properties] at most 2 KB as JSON. Sent with
     * the app's client info; buffered offline (≤ 20). Invalid events are logged and ignored.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, String>? = null) {
        val s = state ?: return notConfigured("track")
        background { s.manager.track(name, properties) }
    }

    /**
     * The FCM registration token from your `FirebaseMessagingService.onNewToken` (and
     * `FirebaseMessaging.getToken()` at start). Null removes this device's registration.
     */
    @JvmStatic
    public fun setPushToken(token: String?) {
        val s = state ?: return notConfigured("setPushToken")
        background {
            if (s.manager.setPushToken(token)) s.manager.syncDevice()
        }
    }

    /** True for a push sent by ZebChat (`RemoteMessage.data`). Works before [configure]. */
    @JvmStatic
    public fun isZebChatNotification(data: Map<String, String>?): Boolean = Notifications.isZebChat(data)

    /** True for the extras of an Intent opened from a ZebChat notification. */
    @JvmStatic
    public fun isZebChatNotification(extras: Bundle?): Boolean =
        Notifications.isZebChat(Notifications.fromBundle(extras))

    /**
     * Shows a ZebChat push received while the app is in the foreground (FCM does not display
     * those) on the `zebchat_chat` channel. Pass `RemoteMessage.data`: its `title` and `body` are
     * shown ([title] / [body] override them; else the app name and a generic text). Returns false
     * when it is not a ZebChat push, the chat is open (even behind a permission prompt or a
     * share sheet), or notifications are not allowed.
     */
    @JvmStatic
    @JvmOverloads
    public fun showNotification(context: Context, data: Map<String, String>, title: String? = null, body: String? = null): Boolean {
        if (!Notifications.isZebChat(data)) return false
        restore(context)
        refreshUnreadCount()
        if (chatStarted()) return false
        return Notifications.show(
            context.applicationContext ?: context,
            data,
            title ?: data[Notifications.KEY_TITLE],
            body ?: data[Notifications.KEY_BODY],
            state?.options?.notificationIcon ?: 0,
        )
    }

    /** Opens the chat when [data] is a ZebChat push. Returns true when it did. */
    @JvmStatic
    public fun handleNotificationTap(activity: Activity, data: Map<String, String>?): Boolean =
        openFromNotification(activity, data)

    /**
     * Opens the chat when [extras] (your launcher Activity's `intent.extras` after a tap on a
     * notification FCM displayed) belong to a ZebChat push. Returns true when it did.
     */
    @JvmStatic
    public fun handleNotificationTap(activity: Activity, extras: Bundle?): Boolean =
        openFromNotification(activity, Notifications.fromBundle(extras))

    /**
     * Opens the chat when [intent] (your launcher Activity's intent, also in `onNewIntent`) comes
     * from a tap on a ZebChat notification FCM displayed. The same as [handleNotificationTap]
     * with `intent.extras`, without the overload ambiguity of a literal `null` in Java.
     */
    @JvmStatic
    public fun handleNotificationIntent(activity: Activity, intent: Intent?): Boolean =
        openFromNotification(activity, Notifications.fromBundle(intent?.extras))

    /** True for an Intent opened from a ZebChat notification (see [handleNotificationIntent]). */
    @JvmStatic
    public fun isZebChatIntent(intent: Intent?): Boolean =
        Notifications.isZebChat(Notifications.fromBundle(intent?.extras))

    /** Adds a listener; it is called at once with the current count, then on every change. */
    @JvmStatic
    public fun addUnreadListener(listener: UnreadListener): UnreadListener {
        listeners.add(listener)
        main.post { if (listeners.contains(listener)) listener.onUnread(unread) }
        return listener
    }

    @JvmStatic
    public fun removeUnreadListener(listener: UnreadListener) {
        listeners.remove(listener)
    }

    /** The last known unread count. */
    @JvmStatic
    public val unreadCount: Int
        get() = unread

    /** Reads the unread count from ZebChat now (listeners hear about changes). */
    @JvmStatic
    public fun refreshUnreadCount() {
        val s = state ?: return
        background { updateUnread(s.manager.unreadCount()) }
    }

    /**
     * Chat language for the next [show]: `auto` (device language) or a code such as `fr`. Kept
     * across process death until [configure] is called with another `locale` option.
     */
    @JvmStatic
    public fun setLocale(locale: String) {
        val s = state ?: return notConfigured("setLocale")
        if (!LOCALE.matches(locale)) {
            ZcLog.w("setLocale: \"$locale\" is not a language code")
            return
        }
        synchronized(this) { this.locale = locale }
        background { if (state === s) persistConfig(s) }
    }

    // ---- chat Activity hooks ---------------------------------------------------------------

    /** The page to load, restoring the configuration after process death; null when never configured. */
    internal fun chatConfig(context: Context): ChatConfig? {
        if (!restore(context)) return null
        val s = state ?: return null
        val url = s.options.widgetUrl.toUri().buildUpon()
            .appendQueryParameter("site", s.siteKey)
            .appendQueryParameter("app", s.client.appId)
            .appendQueryParameter("platform", ClientInfo.PLATFORM)
            .appendQueryParameter("sdk", s.client.sdk)
            .appendQueryParameter("locale", locale)
            .build()
            .toString()
        return ChatConfig(url, s.cdnOrigin, s.apiOrigin)
    }

    /** Registers a chat Activity (idempotent: called in onCreate and onStart). */
    internal fun attachChat(activity: ZebChatActivity) {
        synchronized(chats) {
            chats.removeAll { it.get() == null }
            if (chats.none { it.get() === activity }) chats.add(WeakReference(activity))
        }
    }

    /** Unregisters only [activity]: other chat Activities stay reachable. */
    internal fun detachChat(activity: ZebChatActivity) {
        synchronized(chats) {
            chats.removeAll { it.get().let { chat -> chat == null || chat === activity } }
        }
    }

    /** True while a chat Activity is started (on screen, maybe behind a dialog or a chooser). */
    internal fun chatStarted(): Boolean = chats.any { it.get()?.isStarted == true }

    /** The Intent opening the chat: reuses an open chat instead of stacking a second one. */
    internal fun chatIntent(context: Context): Intent =
        Intent(context, ZebChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * The session for `init` ([fresh] after `expired`), delivered on the main thread. Without
     * one, `refused` says the API refused this app for good (a configuration problem).
     */
    internal fun loadSession(fresh: Boolean, callback: (session: Session?, refused: Boolean) -> Unit) {
        val s = state
        if (s == null) {
            main.post { callback(null, false) }
            return
        }
        background {
            val session = if (fresh) s.manager.freshSession() else s.manager.session()
            val refused = session == null && s.manager.refusedStatus != null
            main.post { callback(session, refused) }
        }
    }

    internal fun onRenewed(token: String, expiresAt: String) {
        val s = state ?: return
        background { s.manager.renewed(token, expiresAt) }
    }

    internal fun onChatClosed() {
        refreshUnreadCount()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun openFromNotification(activity: Activity, data: Map<String, String?>?): Boolean {
        if (!Notifications.isZebChat(data)) return false
        if (!restore(activity)) {
            notConfigured("handleNotificationTap")
            return false
        }
        show(activity)
        return true
    }

    /** Re-applies the last configuration (process death before the host configured). */
    private fun restore(context: Context): Boolean {
        if (state != null) return true
        val app = context.applicationContext ?: context
        val config = readConfig(store(app)) ?: return false
        configure(
            app,
            config.optString("siteKey"),
            ZebChatOptions(
                locale = config.optString("locale", "auto"),
                apiUrl = config.optString("apiUrl", ZebChatOptions.DEFAULT_API_URL),
                widgetUrl = config.optString("widgetUrl", ZebChatOptions.DEFAULT_WIDGET_URL),
                notificationIcon = 0,
                sdk = config.optString("sdk").ifEmpty { null },
                debugLogging = config.optBoolean("debugLogging"),
            ),
        )
        // The chat page is built right away: apply a setLocale choice now, not on the executor.
        val chosen = config.optString(KEY_CHAT_LOCALE)
        val s = state
        if (s != null && LOCALE.matches(chosen) && config.optString("locale", "auto") == s.options.locale) {
            synchronized(this) { locale = chosen }
        }
        return s != null
    }

    /**
     * First task after [configure], on the SDK's thread: leaves the previous site when the site
     * key changed, restores a [setLocale] choice, then stores the configuration for [restore].
     */
    private fun applyStoredConfig(s: State, configuredLocale: String) {
        val previous = readConfig(s.store)
        val previousSite = previous?.optString("siteKey").orEmpty()
        if (previous != null && previousSite.isNotEmpty() && previousSite != s.siteKey) {
            leavePreviousSite(s, previousSite, previous.optString("apiUrl", ZebChatOptions.DEFAULT_API_URL))
        } else if (previous != null && previous.optString("locale", "auto") == s.options.locale) {
            val chosen = previous.optString(KEY_CHAT_LOCALE)
            synchronized(this) {
                // Unless setLocale was called meanwhile (or another configure replaced this one).
                if (state === s && locale == configuredLocale && LOCALE.matches(chosen)) locale = chosen
            }
        }
        if (state === s) persistConfig(s)
    }

    /**
     * A visitor key belongs to one website: switching site keys removes the push registration
     * from the old site's visitor (else that site keeps pushing here), then starts a new visitor.
     */
    private fun leavePreviousSite(s: State, previousSite: String, previousApiUrl: String) {
        if (SITE_KEY.matches(previousSite) && LinkPolicy.originOf(previousApiUrl) != null) {
            SessionManager(ApiClient(previousApiUrl, userAgent(s.client)), s.store, previousSite, s.client).leaveSite()
        }
        listOf(StoreKeys.VISITOR_KEY, StoreKeys.USER, StoreKeys.SESSION, StoreKeys.DEVICE, StoreKeys.DEVICE_MOVE)
            .forEach { s.store.put(it, null) }
    }

    private fun persistConfig(s: State) {
        val json = JSONObject()
            .put("siteKey", s.siteKey)
            .put("locale", s.options.locale)
            .put("apiUrl", s.options.apiUrl)
            .put("widgetUrl", s.options.widgetUrl)
            .put("debugLogging", s.options.debugLogging)
            .put(KEY_CHAT_LOCALE, locale)
        s.options.sdk?.let { json.put("sdk", it) }
        s.store.put(StoreKeys.CONFIG, json.toString())
    }

    private fun readConfig(store: KeyValueStore): JSONObject? {
        val raw = store.get(StoreKeys.CONFIG) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: JSONException) {
            null
        }
    }

    private fun store(context: Context): KeyValueStore =
        sharedStore ?: synchronized(this) {
            sharedStore ?: storeFactory(context).also { sharedStore = it }
        }

    private fun registerCallbacks(app: Context) {
        if (callbacksRegistered) return
        val application = app as? Application ?: run {
            ZcLog.w(
                "configure: the application context is not an Application; screen views, push " +
                    "registration and the unread count are not refreshed on app foreground",
            )
            return
        }
        callbacksRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** App came to the foreground: send buffered screens and events, sync the device, read unread. */
    private fun onForeground() {
        val s = state ?: return
        background {
            s.manager.syncDevice()
            s.manager.flush()
            updateUnread(s.manager.unreadCount())
        }
    }

    private fun updateUnread(count: Int?) {
        if (count == null || count == unread) return
        unread = count
        main.post { listeners.forEach { it.onUnread(count) } }
    }

    /** `setUser` / `logout`: every open chat page gets the new session. */
    private fun notifyChatSessionChanged() {
        main.post { chats.forEach { it.get()?.onSessionChanged() } }
    }

    private fun background(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (e: RuntimeException) {
                ZcLog.w("Unexpected error", e)
            }
        }
    }

    private fun notConfigured(call: String) {
        ZcLog.w("$call: call ZebChat.configure(context, siteKey) first")
    }

    private fun userAgent(client: ClientInfo): String {
        val raw = "ZebChat-Android/$VERSION (Android ${Build.VERSION.RELEASE}; ${client.deviceModel.orEmpty()}) " +
            "${client.appId}/${client.appVersion.orEmpty()}"
        return raw.filter { it in ' '..'~' }
    }

    /** Clears all state (tests only). */
    internal fun resetForTests() {
        synchronized(this) {
            state = null
            sharedStore = null
            locale = "auto"
            unread = 0
            chats.clear()
            listeners.clear()
            callbacksRegistered = false
            startedActivities = 0
        }
    }
}
