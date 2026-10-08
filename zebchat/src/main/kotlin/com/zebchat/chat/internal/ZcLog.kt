package com.zebchat.chat.internal

import android.util.Log

/** Logging under the `ZebChat` tag. Never pass tokens, keys or personal data. */
internal object ZcLog {
    private const val TAG = "ZebChat"

    @Volatile
    var debug: Boolean = false

    fun d(message: String) {
        if (debug) Log.d(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, "$message (${error.javaClass.simpleName}: ${error.message})")
        }
    }
}
