package com.zebchat.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zebchat.chat.UnreadListener
import com.zebchat.chat.ZebChat
import com.zebchat.chat.ZebChatUser

/**
 * Shows every SDK call. Push needs Firebase, which this sample leaves out so it builds without
 * google-services.json: see README.md → "Push notifications (Firebase)" for the service to add.
 */
class MainActivity : Activity() {
    private lateinit var openChat: Button
    private var unreadListener: UnreadListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        ZebChat.trackScreen("Home")
        // Opened from a ZebChat notification FCM displayed while the app was in the background.
        ZebChat.handleNotificationTap(this, intent.extras)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ZebChat.handleNotificationTap(this, intent.extras)
    }

    override fun onStart() {
        super.onStart()
        unreadListener = ZebChat.addUnreadListener { count ->
            openChat.text = if (count > 0) getString(R.string.open_chat_unread, count) else getString(R.string.open_chat)
        }
    }

    override fun onStop() {
        unreadListener?.let { ZebChat.removeUnreadListener(it) }
        unreadListener = null
        super.onStop()
    }

    private fun buildContent(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(text: Int, onClick: () -> Unit) = Button(this).apply {
            setText(text)
            setOnClickListener { onClick() }
            column.addView(this)
        }
        fun title(text: Int) = column.addView(TextView(this).apply {
            setText(text)
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        fun field(hint: Int, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            setHint(hint)
            inputType = type
            column.addView(this)
        }

        openChat = button(R.string.open_chat) { ZebChat.show(this) }

        title(R.string.screens_title)
        button(R.string.screen_home) { ZebChat.trackScreen("Home") }
        button(R.string.screen_product) { ZebChat.trackScreen("Product/Sneakers") }
        button(R.string.screen_checkout) { ZebChat.trackScreen("Checkout") }

        title(R.string.user_title)
        val id = field(R.string.user_id)
        val name = field(R.string.user_name)
        val email = field(R.string.user_email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val hash = field(R.string.user_hash)
        button(R.string.set_user) {
            fun value(edit: EditText) = edit.text.toString().trim().ifEmpty { null }
            // In a real app the hash comes from your server with the signed-in user.
            ZebChat.setUser(ZebChatUser(id = value(id), email = value(email), name = value(name), hash = value(hash)))
        }
        button(R.string.logout) { ZebChat.logout() }

        title(R.string.app_name)
        button(R.string.language_fr) { ZebChat.setLocale("fr") }
        button(R.string.permissions) {
            val wanted = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(wanted.toTypedArray(), 1)
        }
        column.addView(TextView(this).apply { setText(R.string.firebase_note) })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
    }
}
