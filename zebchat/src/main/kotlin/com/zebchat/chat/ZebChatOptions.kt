package com.zebchat.chat

import androidx.annotation.DrawableRes

/** Settings for [ZebChat.configure]. The defaults suit production apps. */
public data class ZebChatOptions @JvmOverloads constructor(
    /** Chat language: `auto` (device language) or a code such as `fr`. See [ZebChat.setLocale]. */
    val locale: String = "auto",
    /** ZebChat API origin. Override only for staging or local development. */
    val apiUrl: String = DEFAULT_API_URL,
    /** The mobile chat page. Override only for staging or local development. */
    val widgetUrl: String = DEFAULT_WIDGET_URL,
    /** Small icon for chat notifications; 0 uses the app icon. */
    @param:DrawableRes val notificationIcon: Int = 0,
    /**
     * `<name>/<version>` reported to ZebChat. Set by the Flutter and React Native wrappers
     * (`flutter/1.0.0`); apps leave it null (`android/<sdk version>`).
     */
    val sdk: String? = null,
    /** Logs requests and bridge messages (never tokens or keys) under the `ZebChat` tag. */
    val debugLogging: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_API_URL: String = "https://api.zebchat.com"
        public const val DEFAULT_WIDGET_URL: String = "https://cdn.zebchat.com/widget/v1/mobile.html"
    }
}
