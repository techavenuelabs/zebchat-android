package com.zebchat.chat

/**
 * Your signed-in user, sent to `POST /widget/identify` (like `ZebChat.setUser()` on the web).
 *
 * An [id] is only accepted with its [hash]: hex HMAC-SHA256 of the id keyed with the website's
 * identity secret, computed on **your server** (never ship the secret in the app). Without an id
 * the name, email and phone are saved on the visitor unverified.
 */
public data class ZebChatUser @JvmOverloads constructor(
    val id: String? = null,
    val email: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val hash: String? = null,
) {
    /** Keeps personal data and the hash out of logs. */
    override fun toString(): String =
        "ZebChatUser(id=${mask(id)}, email=${mask(email)}, name=${mask(name)}, " +
            "phone=${mask(phone)}, hash=${mask(hash)})"

    private fun mask(value: String?): String = if (value == null) "null" else "…"
}
