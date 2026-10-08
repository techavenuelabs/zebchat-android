package com.zebchat.chat

/** Unread agent messages in the visitor's current conversation. Called on the main thread. */
public fun interface UnreadListener {
    public fun onUnread(count: Int)
}
