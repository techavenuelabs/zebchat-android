package com.zebchat.chat.internal

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Page → native messages (bridge v1, `packages/types/src/mobile.ts`). */
internal sealed class PageMessage {
    data object Ready : PageMessage()

    data class Renewed(val token: String, val expiresAt: String) : PageMessage()

    data object Expired : PageMessage()

    data object Close : PageMessage()

    data class Error(val status: Int, val message: String) : PageMessage()
}

/** Mirrors `isMobilePageMessage` and builds the native → page `init`. */
internal object Bridge {
    const val VERSION = 1

    /** The JavaScript name of the page → native channel. */
    const val CHANNEL = "ZebChatAndroid"

    /** Bigger messages are refused before parsing (a `renewed` token is at most 4096 chars). */
    const val MAX_MESSAGE_CHARS = 16_384

    /** The message, or null for anything malformed or unknown (the caller ignores it). */
    fun parse(raw: String?): PageMessage? {
        if (raw == null || raw.length > MAX_MESSAGE_CHARS) return null
        val obj = try {
            JSONTokener(raw).nextValue() as? JSONObject
        } catch (_: JSONException) {
            null
        } ?: return null
        return when (obj.opt("type")) {
            "ready" -> if (integer(obj.opt("v")) == VERSION) PageMessage.Ready else null
            "renewed" -> {
                val token = text(obj.opt("token"), 4096)
                val expiresAt = text(obj.opt("expiresAt"), 40)?.takeIf { IsoTime.parse(it) != null }
                if (token != null && expiresAt != null) PageMessage.Renewed(token, expiresAt) else null
            }
            "expired" -> PageMessage.Expired
            "close" -> PageMessage.Close
            "error" -> {
                val status = integer(obj.opt("status"))
                val message = obj.opt("message")
                if (status != null && message is String && message.length <= 500) {
                    PageMessage.Error(status, message)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** `{type:'init', v:1, token, expiresAt, visitor}` for `window.ZebChatBridge.receive`. */
    fun initMessage(token: String, expiresAt: String, visitorJson: String): String =
        JSONObject()
            .put("type", "init")
            .put("v", VERSION)
            .put("token", token)
            .put("expiresAt", expiresAt)
            .put("visitor", JSONObject(visitorJson))
            .toString()

    /**
     * The WebMessageListener rule: only the main frame of the CDN page talks to native (the
     * Turnstile iframe shares the WebView).
     */
    fun accepts(sourceOrigin: String?, isMainFrame: Boolean, cdnOrigin: String): Boolean =
        isMainFrame && sourceOrigin != null && LinkPolicy.originOf(sourceOrigin) == cdnOrigin

    /** JavaScript handing [message] to the page; a no-op before the page installed its bridge. */
    fun receiveScript(message: String): String {
        // U+2028 / U+2029 end a line in older JavaScript engines: escape them in the literal.
        val literal = JSONObject.quote(message).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return "window.ZebChatBridge&&window.ZebChatBridge.receive($literal);"
    }

    private fun text(value: Any?, max: Int): String? =
        if (value is String && value.isNotEmpty() && value.length <= max) value else null

    private fun integer(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
        is Double ->
            if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else null
        else -> null
    }
}
