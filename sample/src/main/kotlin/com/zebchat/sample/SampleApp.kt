package com.zebchat.sample

import android.app.Application
import com.zebchat.chat.ZebChat
import com.zebchat.chat.ZebChatOptions

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure once, here: the chat screen is restored after process death from this.
        ZebChat.configure(
            this,
            siteKey = BuildConfig.ZEBCHAT_SITE_KEY,
            options = ZebChatOptions(
                apiUrl = BuildConfig.ZEBCHAT_API_URL,
                widgetUrl = BuildConfig.ZEBCHAT_WIDGET_URL,
                debugLogging = BuildConfig.DEBUG,
            ),
        )
    }
}
