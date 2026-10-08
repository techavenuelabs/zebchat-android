package com.zebchat.chat.internal

import com.zebchat.chat.ZebChatUser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** A widget API call answered with a non-2xx status. */
internal class ApiException(val status: Int, message: String) : IOException("HTTP $status: $message") {
    /** A client error that repeating the same request will not fix. */
    val isPermanent: Boolean get() = status in 400..499 && status != 401 && status != 408 && status != 429
}

/** The answer of `POST /widget/sessions` and `/widget/identify` (`WidgetSession`). */
internal data class SessionResponse(
    val token: String,
    val expiresAt: String,
    val expiresAtMillis: Long,
    val visitorKey: String?,
    val visitorId: String,
    /** The `WidgetVisitor` JSON, handed to the page as is. */
    val visitorJson: String,
)

/**
 * The widget endpoints the SDK calls, on HttpURLConnection (the platform HTTP stack; no
 * OkHttp). Blocking: called on the SDK's background thread only.
 */
internal class ApiClient(
    apiUrl: String,
    private val userAgent: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) {
    private val base = apiUrl.trimEnd('/') + "/api/v1/widget"

    fun createSession(siteKey: String, appId: String, visitorKey: String?): SessionResponse {
        val body = JSONObject()
            .put("siteKey", siteKey)
            .put("app", appId)
            .put("platform", ClientInfo.PLATFORM)
        visitorKey?.let { body.put("visitorKey", it) }
        return parseSession(request("POST", "/sessions", null, body))
    }

    fun identify(token: String, user: ZebChatUser): SessionResponse =
        parseSession(request("POST", "/identify", token, identityBody(user)))

    fun pageview(token: String, url: String, title: String, client: ClientInfo) {
        val body = JSONObject().put("url", url).put("title", title).put("client", client.toJson())
        request("POST", "/pageviews", token, body)
    }

    /** `POST /widget/events`: a custom event; [propertiesJson] is a JSON object (≤ 2 KB) or null. */
    fun event(token: String, name: String, propertiesJson: String?, client: ClientInfo) {
        val body = JSONObject().put("name", name).put("client", client.toJson())
        propertiesJson?.let { body.put("properties", JSONObject(it)) }
        request("POST", "/events", token, body)
    }

    fun registerDevice(token: String, pushToken: String, appId: String) {
        val body = JSONObject()
            .put("token", pushToken)
            .put("platform", ClientInfo.PLATFORM)
            .put("appId", appId)
        request("POST", "/devices", token, body)
    }

    fun deleteDevice(token: String, pushToken: String) {
        request("DELETE", "/devices", token, JSONObject().put("token", pushToken))
    }

    /** `visitorUnreadCount` of the current conversation; 0 without one. */
    fun unreadCount(token: String): Int {
        val text = request("GET", "/conversations/current", token, null).trim()
        if (text.isEmpty() || text == "null") return 0
        val obj = parseObject(text)
        val count = obj.opt("visitorUnreadCount")
        return if (count is Int && count >= 0) count else 0
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject?): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            ZcLog.d("$method $path → $status")
            if (status in 200..299) return readText(connection.inputStream)
            val error = readText(connection.errorStream)
            throw ApiException(status, errorMessage(error))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        /** Only the fields that were set; an id without its hash is dropped (the API refuses it). */
        fun identityBody(user: ZebChatUser): JSONObject {
            val body = JSONObject()
            val id = user.id?.takeIf { it.isNotEmpty() }
            val hash = user.hash?.takeIf { it.isNotEmpty() }
            if (id != null && hash != null) {
                body.put("id", id).put("hash", hash)
            } else if (id != null || hash != null) {
                ZcLog.w("ZebChatUser.id needs its hash (and the reverse): sending the profile without the id")
            }
            user.name?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("name", it) }
            user.email?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("email", it) }
            user.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("phone", it) }
            return body
        }

        fun parseSession(text: String): SessionResponse {
            val obj = parseObject(text)
            val token = obj.opt("visitorToken")
            val expiresAt = obj.opt("visitorTokenExpiresAt")
            val visitor = obj.opt("visitor")
            val visitorKey = obj.opt("visitorKey")
            val expiresAtMillis = (expiresAt as? String)?.let { IsoTime.parse(it) }
            if (token !is String || token.isEmpty() || token.length > 4096 ||
                expiresAt !is String || expiresAtMillis == null ||
                visitor !is JSONObject
            ) {
                throw IOException("Unexpected session response")
            }
            val visitorId = visitor.opt("id")
            if (visitorId !is String || visitorId.isEmpty() || visitorId.length > 100) {
                throw IOException("Unexpected session response")
            }
            return SessionResponse(
                token = token,
                expiresAt = expiresAt,
                expiresAtMillis = expiresAtMillis,
                visitorKey = (visitorKey as? String)?.takeIf { it.length in 20..100 },
                visitorId = visitorId,
                visitorJson = visitor.toString(),
            )
        }

        private fun parseObject(text: String): JSONObject = try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (_: JSONException) {
            null
        } ?: throw IOException("Unexpected response")

        private fun errorMessage(text: String): String {
            val obj = try {
                JSONTokener(text).nextValue() as? JSONObject
            } catch (_: JSONException) {
                null
            }
            return when (val message = obj?.opt("message")) {
                is String -> message.take(300)
                is org.json.JSONArray -> message.optString(0).take(300)
                else -> "request failed"
            }
        }

        private fun readText(stream: InputStream?): String {
            if (stream == null) return ""
            stream.use {
                val bytes = it.readBytesLimited(MAX_RESPONSE_BYTES)
                return String(bytes, Charsets.UTF_8)
            }
        }

        private fun InputStream.readBytesLimited(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("Response too large")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
