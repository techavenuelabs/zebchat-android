package com.zebchat.chat.internal

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Navigation policy of the chat WebView: only the CDN origin loads in it, signed
 * `/api/v1/files/` links go to the downloader, other links open outside the app.
 */
internal object LinkPolicy {
    enum class Action { LOAD, DOWNLOAD, EXTERNAL, BLOCK }

    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "geo")

    fun classify(url: String, cdnOrigin: String, apiOrigin: String): Action {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return Action.BLOCK
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Action.BLOCK
        if (scheme in EXTERNAL_SCHEMES) return Action.EXTERNAL
        if (scheme != "https" && scheme != "http") return Action.BLOCK
        val origin = originOf(uri) ?: return Action.BLOCK
        if (origin == cdnOrigin) return Action.LOAD
        if (origin == apiOrigin && uri.rawPath.orEmpty().startsWith(FILES_PATH)) return Action.DOWNLOAD
        return Action.EXTERNAL
    }

    /** Cloudflare Turnstile, the only frame the chat page embeds besides its own. */
    const val TURNSTILE_ORIGIN = "https://challenges.cloudflare.com"

    /** Frames allowed in the chat page when every frame can reach the fallback bridge. */
    fun isAllowedFrame(url: String, cdnOrigin: String): Boolean {
        if (url == "about:blank" || url == "about:srcdoc") return true
        val origin = originOf(url) ?: return false
        return origin == cdnOrigin || origin == TURNSTILE_ORIGIN
    }

    /** True when [url] is a page on [cdnOrigin] (the only content allowed to talk to native). */
    fun isOrigin(url: String?, origin: String): Boolean {
        if (url == null) return false
        return try {
            originOf(URI(url)) == origin
        } catch (_: URISyntaxException) {
            false
        }
    }

    /** `scheme://host[:port]` (lowercase, default ports dropped), or null. */
    fun originOf(url: String): String? = try {
        originOf(URI(url))
    } catch (_: URISyntaxException) {
        null
    }

    private fun originOf(uri: URI): String? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        val port = uri.port
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    const val FILES_PATH = "/api/v1/files/"
}
