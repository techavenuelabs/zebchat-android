package com.zebchat.chat.internal

import android.webkit.JavascriptInterface

/**
 * Fallback page → native channel (`ZebChatAndroid.postMessage(json)`) for WebViews without
 * `WEB_MESSAGE_LISTENER`. Called on a WebView thread; the receiver checks the page origin on the
 * main thread before trusting anything.
 */
internal class JsBridge(private val receiver: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(message: String?) {
        if (message != null && message.length <= Bridge.MAX_MESSAGE_CHARS) receiver(message)
    }
}
