package com.zebchat.chat.internal

import com.zebchat.chat.ZebChatUser
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** A visitor session handed to the page as `init`. */
internal data class Session(
    val token: String,
    val expiresAt: String,
    val expiresAtMillis: Long,
    val visitorId: String,
    val visitorJson: String,
    /** The stored user JSON this session was identified with ("" = anonymous). */
    val user: String,
    /** A user whose identify failed for a reason that may pass: retried on this session. */
    val pendingUser: String = "",
)

/**
 * Owns the visitor session (docs/MOBILE_SDK.md §5): stored visitor key (+ user) →
 * `POST /widget/sessions` → `POST /widget/identify` (adopting the visitor it returns) → cached
 * until shortly before expiry. Also registers the push device, buffers screen views and reads
 * the unread count.
 *
 * Every method blocks on the network: call them on the SDK's background thread. They are
 * synchronized, so calls run one at a time in order (e.g. logout's device delete always comes
 * before the key is forgotten).
 */
internal class SessionManager(
    private val api: ApiClient,
    private val store: KeyValueStore,
    private val siteKey: String,
    private val client: ClientInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val screens = ArrayDeque<Pair<String, String>>()

    /** Custom events waiting to be sent: name to properties JSON (or null). */
    private val events = ArrayDeque<Pair<String, String?>>()

    /**
     * The status of the last `POST /widget/sessions` refused for good (e.g. 403
     * `app_not_allowed`, unknown site key); null after a success or a failure that may pass.
     */
    @Volatile
    var refusedStatus: Int? = null
        private set

    /** The cached session while valid, else a new one; null when the API cannot be reached. */
    @Synchronized
    fun session(): Session? = session(registerDevice = true)

    /** After the page reported `expired`: always a new session. */
    @Synchronized
    fun freshSession(): Session? {
        store.put(StoreKeys.SESSION, null)
        return session(registerDevice = true)
    }

    /** The page renewed its token (`renewed`). */
    @Synchronized
    fun renewed(token: String, expiresAt: String) {
        val current = cached() ?: return
        val millis = IsoTime.parse(expiresAt) ?: return
        save(current.copy(token = token, expiresAt = expiresAt, expiresAtMillis = millis))
    }

    /** Drops the cached session (a 401 from the API). */
    @Synchronized
    fun invalidate() {
        store.put(StoreKeys.SESSION, null)
    }

    /** True when a visitor exists (or will, for a set user), so network calls make sense. */
    @Synchronized
    fun hasVisitor(): Boolean =
        store.get(StoreKeys.VISITOR_KEY) != null || store.get(StoreKeys.USER) != null ||
            store.get(StoreKeys.SESSION) != null

    /** Stores the user; the next [session] identifies it. Returns false when nothing changed. */
    @Synchronized
    fun setUser(user: ZebChatUser): Boolean {
        val json = userJson(user)
        if (json == store.get(StoreKeys.USER)) return false
        store.put(StoreKeys.USER, json)
        return true
    }

    /** Deletes the push device for the current visitor, then forgets the user and visitor key. */
    @Synchronized
    fun logout() {
        // Registering the token for the next visitor moves it away from this one.
        if (!deleteDevice()) store.put(StoreKeys.DEVICE_MOVE, "1")
        store.put(StoreKeys.VISITOR_KEY, null)
        store.put(StoreKeys.USER, null)
        store.put(StoreKeys.SESSION, null)
        store.put(StoreKeys.DEVICE, null)
        screens.clear()
        events.clear()
    }

    /**
     * The app switched to another site key: removes this device's push registration from this
     * site's visitor (best effort), so the old site stops sending pushes.
     */
    @Synchronized
    fun leaveSite() {
        if (!deleteDevice()) ZcLog.w("Could not remove the push device from the previous site")
    }

    /** Stores the FCM token. Returns true when it changed (the caller then registers it). */
    @Synchronized
    fun setPushToken(token: String?): Boolean {
        val value = token?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 }
        val old = store.get(StoreKeys.PUSH_TOKEN)
        if (value == old) return false
        // Removed or rotated: the old token leaves this visitor (only with a session at hand).
        if (old != null && store.get(StoreKeys.DEVICE) != null) {
            cached()?.takeIf { it.expiresAtMillis > clock() }?.let {
                try {
                    api.deleteDevice(it.token, old)
                } catch (e: ApiException) {
                    if (e.status == 401) invalidate()
                    ZcLog.w("Could not remove the previous push token", e)
                } catch (e: IOException) {
                    ZcLog.w("Could not remove the previous push token", e)
                }
            }
        }
        store.put(StoreKeys.PUSH_TOKEN, value)
        store.put(StoreKeys.DEVICE, null)
        return true
    }

    /** Registers the push token when it is not registered for the current visitor yet. */
    @Synchronized
    fun syncDevice() {
        if (store.get(StoreKeys.PUSH_TOKEN) == null) return
        if (!hasVisitor() && store.get(StoreKeys.DEVICE_MOVE) == null) return
        session(registerDevice = true)
    }

    /** Queues a screen view (at most [MAX_SCREENS], oldest dropped) and sends what it can. */
    @Synchronized
    fun trackScreen(screen: String) {
        val name = screen.trim().take(MAX_TITLE)
        if (name.isEmpty()) return
        screens.addLast(ScreenUrl.of(client.appId, name) to name)
        while (screens.size > MAX_SCREENS) screens.removeFirst()
        flush()
    }

    /**
     * Queues a custom event (at most [MAX_EVENTS], oldest dropped) and sends what it can. An
     * invalid name, or properties over 2 KB of JSON, is logged and ignored (the API refuses them).
     */
    @Synchronized
    fun track(name: String, properties: Map<String, String>?) {
        if (!EVENT_NAME.matches(name)) {
            ZcLog.w("track: \"$name\" is not a valid event name (1-64 of A-Z a-z 0-9 _ . : -); ignored")
            return
        }
        val json = properties?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() }
        if (json != null && json.toByteArray(Charsets.UTF_8).size > MAX_EVENT_PROPERTIES_BYTES) {
            ZcLog.w("track: properties of \"$name\" are over 2 KB of JSON; ignored")
            return
        }
        events.addLast(name to json)
        while (events.size > MAX_EVENTS) events.removeFirst()
        flush()
    }

    /**
     * Sends buffered screen views, then buffered events; keeps them on network errors (retried
     * on the next call).
     */
    @Synchronized
    fun flush() {
        if (screens.isEmpty() && events.isEmpty()) return
        val current = session() ?: return
        val sentScreens = drain(screens, "Screen view") { (url, title) ->
            api.pageview(current.token, url, title, client)
        }
        if (!sentScreens) return
        drain(events, "Event") { (name, properties) ->
            api.event(current.token, name, properties, client)
        }
    }

    /**
     * Sends [queue] in order, dropping what the API refuses for good. False when it stopped early
     * (offline, a server error, or a 401 that dropped the session): the rest stays queued.
     */
    private inline fun <T> drain(queue: ArrayDeque<T>, what: String, send: (T) -> Unit): Boolean {
        while (queue.isNotEmpty()) {
            try {
                send(queue.first())
                queue.removeFirst()
            } catch (e: ApiException) {
                when {
                    e.status == 401 -> {
                        invalidate()
                        return false
                    }
                    e.isPermanent -> {
                        ZcLog.w("$what refused", e)
                        queue.removeFirst()
                    }
                    else -> return false
                }
            } catch (e: IOException) {
                ZcLog.d("$what kept for later: ${e.javaClass.simpleName}")
                return false
            }
        }
        return true
    }

    /** Buffered screen views (tests). */
    @Synchronized
    fun pendingScreens(): Int = screens.size

    /** Buffered events (tests). */
    @Synchronized
    fun pendingEvents(): Int = events.size

    /** Unread agent messages; 0 without a visitor, null when unknown (offline). */
    @Synchronized
    fun unreadCount(): Int? {
        if (!hasVisitor()) return 0
        val current = session() ?: return null
        return try {
            api.unreadCount(current.token)
        } catch (e: ApiException) {
            if (e.status == 401) invalidate()
            ZcLog.w("Could not read the unread count", e)
            null
        } catch (e: IOException) {
            ZcLog.d("Unread count unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun session(registerDevice: Boolean): Session? {
        val user = store.get(StoreKeys.USER).orEmpty()
        val valid = cached()?.takeIf { it.expiresAtMillis - clock() > RENEW_MARGIN_MS }
        val current = when {
            valid == null -> create(user)
            valid.user == user -> valid
            // Identify failed earlier for a reason that may pass: retry it alone.
            user.isNotEmpty() && valid.pendingUser == user -> retryIdentify(valid, user)
            else -> create(user)
        } ?: return null
        if (registerDevice) registerDevice(current)
        return current
    }

    private fun create(userJson: String): Session? {
        if (store.isUnreadable(StoreKeys.VISITOR_KEY)) {
            // Never start a new visitor over a key the Keystore may give back later.
            ZcLog.w("The visitor key cannot be read right now; no session")
            return null
        }
        val created = try {
            api.createSession(siteKey, client.appId, store.get(StoreKeys.VISITOR_KEY))
        } catch (e: ApiException) {
            if (e.isPermanent) {
                refusedStatus = e.status
                ZcLog.w(
                    "ZebChat refused this app (${e.status}): check the site key and that " +
                        "\"${client.appId}\" is in the website's allowed Android apps",
                    e,
                )
            } else {
                refusedStatus = null
                ZcLog.w("Could not start the chat session", e)
            }
            return null
        } catch (e: IOException) {
            refusedStatus = null
            ZcLog.w("Could not start the chat session", e)
            return null
        }
        refusedStatus = null
        created.visitorKey?.let { store.put(StoreKeys.VISITOR_KEY, it) }
        val anonymous = Session(
            token = created.token,
            expiresAt = created.expiresAt,
            expiresAtMillis = created.expiresAtMillis,
            visitorId = created.visitorId,
            visitorJson = created.visitorJson,
            user = "",
        )
        if (userJson.isEmpty()) return anonymous.also { save(it) }
        // A 401 on a token this fresh is unexpected: retried like a network error.
        return identify(anonymous, userJson) ?: anonymous.copy(pendingUser = userJson).also { save(it) }
    }

    private fun retryIdentify(cached: Session, userJson: String): Session? =
        identify(cached, userJson) ?: run {
            // The cached token is no longer valid: start over.
            invalidate()
            create(userJson)
        }

    /**
     * Identifies [userJson] on [base] and saves the result; null after a 401. It may return
     * another visitor (the same user on another device): adopted.
     */
    private fun identify(base: Session, userJson: String): Session? {
        val user = parseUser(userJson) ?: return base.copy(user = userJson, pendingUser = "").also { save(it) }
        val session = try {
            val result = api.identify(base.token, user)
            Session(
                token = result.token,
                expiresAt = result.expiresAt,
                expiresAtMillis = result.expiresAtMillis,
                visitorId = result.visitorId,
                visitorJson = result.visitorJson,
                user = userJson,
            )
        } catch (e: ApiException) {
            when {
                e.status == 401 -> return null
                e.isPermanent -> {
                    // Refused for good (e.g. a bad hash): continue anonymously, never retried.
                    ZcLog.w("setUser was refused; continuing anonymously", e)
                    base.copy(user = userJson, pendingUser = "")
                }
                else -> {
                    ZcLog.w("setUser failed; retried on the next call", e)
                    base.copy(user = "", pendingUser = userJson)
                }
            }
        } catch (e: IOException) {
            ZcLog.w("setUser failed; retried on the next call", e)
            base.copy(user = "", pendingUser = userJson)
        }
        save(session)
        return session
    }

    private fun registerDevice(session: Session) {
        val push = store.get(StoreKeys.PUSH_TOKEN) ?: return
        val mark = "${session.visitorId}|$push"
        if (store.get(StoreKeys.DEVICE) == mark) return
        try {
            api.registerDevice(session.token, push, client.appId)
            store.put(StoreKeys.DEVICE, mark)
            store.put(StoreKeys.DEVICE_MOVE, null)
        } catch (e: ApiException) {
            // The token is no longer valid: the next call starts a new session.
            if (e.status == 401) invalidate()
            ZcLog.w("Could not register the push device", e)
        } catch (e: IOException) {
            ZcLog.w("Could not register the push device", e)
        }
    }

    /**
     * Deletes this device's push registration for the current visitor (a 401 retries once with
     * a new session). True when deleted or when nothing was registered.
     */
    private fun deleteDevice(): Boolean {
        val push = store.get(StoreKeys.PUSH_TOKEN) ?: return true
        if (store.get(StoreKeys.DEVICE) == null) return true
        repeat(2) { attempt ->
            val current = session(registerDevice = false) ?: return false
            try {
                api.deleteDevice(current.token, push)
                store.put(StoreKeys.DEVICE, null)
                return true
            } catch (e: ApiException) {
                if (e.status != 401 || attempt == 1) {
                    ZcLog.w("Could not remove the push device", e)
                    return false
                }
                invalidate()
            } catch (e: IOException) {
                ZcLog.w("Could not remove the push device", e)
                return false
            }
        }
        return false
    }

    private fun cached(): Session? {
        val raw = store.get(StoreKeys.SESSION) ?: return null
        val obj = parseObject(raw) ?: return null
        val token = obj.optString("token")
        val expiresAt = obj.optString("expiresAt")
        val millis = IsoTime.parse(expiresAt)
        val visitor = obj.optJSONObject("visitor")
        val visitorId = visitor?.opt("id")
        if (token.isEmpty() || millis == null || visitor == null || visitorId !is String) return null
        return Session(token, expiresAt, millis, visitorId, visitor.toString(), obj.optString("user"), obj.optString("pendingUser"))
    }

    private fun save(session: Session) {
        val json = JSONObject()
            .put("token", session.token)
            .put("expiresAt", session.expiresAt)
            .put("visitor", JSONObject(session.visitorJson))
            .put("user", session.user)
            .put("pendingUser", session.pendingUser)
        store.put(StoreKeys.SESSION, json.toString())
    }

    companion object {
        const val MAX_SCREENS = 20
        const val MAX_TITLE = 300
        const val MAX_EVENTS = 20

        /** `POST /widget/events` limits (docs/MOBILE_SDK.md §5, as on the web). */
        const val MAX_EVENT_PROPERTIES_BYTES = 2048
        val EVENT_NAME = Regex("^[A-Za-z0-9_.:-]{1,64}$")

        /** A cached token is used until 10 minutes before it expires (tokens last ~12 h). */
        const val RENEW_MARGIN_MS = 10 * 60 * 1000L

        fun userJson(user: ZebChatUser): String = JSONObject().apply {
            user.id?.let { put("id", it) }
            user.email?.let { put("email", it) }
            user.name?.let { put("name", it) }
            user.phone?.let { put("phone", it) }
            user.hash?.let { put("hash", it) }
        }.toString()

        fun parseUser(json: String): ZebChatUser? {
            if (json.isEmpty()) return null
            val obj = parseObject(json) ?: return null
            fun field(name: String): String? = (obj.opt(name) as? String)?.takeIf { it.isNotEmpty() }
            return ZebChatUser(
                id = field("id"),
                email = field("email"),
                name = field("name"),
                phone = field("phone"),
                hash = field("hash"),
            )
        }

        private fun parseObject(text: String): JSONObject? = try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (_: JSONException) {
            null
        }
    }
}
