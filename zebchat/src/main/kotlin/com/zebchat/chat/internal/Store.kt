package com.zebchat.chat.internal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The SDK's persisted values (visitor key, user, cached session, push token, configuration). */
internal interface KeyValueStore {
    fun get(key: String): String?

    /** Stores [value]; null removes the key. */
    fun put(key: String, value: String?)

    /**
     * True when [key] holds a value that cannot be read right now (a Keystore error that may
     * pass) but may be readable later: callers must not replace it with a new one.
     */
    fun isUnreadable(key: String): Boolean = false
}

internal object StoreKeys {
    const val VISITOR_KEY = "visitorKey"
    const val USER = "user"
    const val SESSION = "session"
    const val PUSH_TOKEN = "pushToken"

    /** `<visitorId>|<push token>` last registered with `POST /widget/devices`. */
    const val DEVICE = "device"

    /** A logout could not delete the device: move the token to the next visitor. */
    const val DEVICE_MOVE = "deviceMove"
    const val CONFIG = "config"
}

internal class MemoryStore : KeyValueStore {
    private val values = HashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Seals values for storage. */
internal interface ValueCipher {
    fun seal(plain: String): String

    /**
     * The plain text, or null when [sealed] can never be opened on this install (another
     * device's key after a backup restore, a missing key, corrupt data). Throws
     * [GeneralSecurityException] or [RuntimeException] for failures that may pass (a busy or
     * locked Keystore): the stored value is then kept for the next try.
     */
    fun open(sealed: String): String?
}

/**
 * Private SharedPreferences (`com.zebchat.chat.xml`). Values are wrapped with an AES-GCM key in
 * the Android Keystore when it works (`k1:`), else stored as is (`p1:`): wrapping is best
 * effort, so keeping the file out of backups is what really protects it. Keystore keys never
 * leave the device, so a copy restored from a backup onto another install cannot be opened and
 * is dropped: that install starts as a new visitor. A Keystore error that may pass keeps the
 * stored value and reads it again next time.
 */
internal class PrefsStore(context: Context, private val cipher: ValueCipher?) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cache = HashMap<String, String?>()
    private val unreadable = HashSet<String>()

    @Synchronized
    override fun get(key: String): String? {
        if (cache.containsKey(key)) return cache[key]
        val raw = prefs.getString(key, null)
        val value = when {
            raw == null -> null
            raw.startsWith(PLAIN) -> raw.substring(PLAIN.length)
            raw.startsWith(SEALED) -> when (val opened = open(raw.substring(SEALED.length))) {
                is Opened.Value -> opened.plain
                Opened.Never -> null
                is Opened.NotNow -> {
                    // Neither dropped nor cached: the next read tries again.
                    ZcLog.w("Cannot read a stored value now; keeping it ($key)", opened.error)
                    unreadable.add(key)
                    return null
                }
            }
            else -> null
        }
        if (raw != null && value == null) {
            ZcLog.w("Dropped a stored value that cannot be read on this device ($key)")
            prefs.edit { remove(key) }
        }
        unreadable.remove(key)
        cache[key] = value
        return value
    }

    @Synchronized
    override fun isUnreadable(key: String): Boolean = key in unreadable

    @Synchronized
    override fun put(key: String, value: String?) {
        unreadable.remove(key)
        cache[key] = value
        if (value == null) {
            prefs.edit { remove(key) }
        } else {
            val sealed = try {
                cipher?.seal(value)?.let { SEALED + it }
            } catch (e: GeneralSecurityException) {
                ZcLog.w("Keystore unavailable, storing without wrapping", e)
                null
            } catch (e: RuntimeException) {
                ZcLog.w("Keystore unavailable, storing without wrapping", e)
                null
            }
            prefs.edit { putString(key, sealed ?: (PLAIN + value)) }
        }
    }

    private sealed class Opened {
        class Value(val plain: String) : Opened()

        data object Never : Opened()

        class NotNow(val error: Exception?) : Opened()
    }

    /** Tried twice: a busy Keystore often answers the second call. */
    private fun open(sealed: String): Opened {
        val cipher = cipher ?: return Opened.NotNow(null)
        var error: Exception? = null
        repeat(2) {
            try {
                return cipher.open(sealed)?.let { Opened.Value(it) } ?: Opened.Never
            } catch (e: GeneralSecurityException) {
                error = e
            } catch (e: RuntimeException) {
                error = e
            }
        }
        return Opened.NotNow(error)
    }

    companion object {
        const val PREFS_NAME = "com.zebchat.chat"
        private const val SEALED = "k1:"
        private const val PLAIN = "p1:"
    }
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
internal class KeystoreCipher : ValueCipher {
    private val sealKey: SecretKey by lazy { loadOrCreateKey() }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sealKey)
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? {
        val bytes = try {
            Base64.decode(sealed, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size <= IV_BYTES) return null
        // No key (a restored copy, or the Keystore was reset): never readable here.
        val key = loadKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return try {
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            null // sealed with another key
        }
    }

    private fun loadKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private fun loadOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "com.zebchat.chat.store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
