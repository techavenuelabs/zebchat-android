package com.zebchat.chat.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private lateinit var context: Context

    /** Reversible stand-in for the Keystore cipher; `device` names the Keystore it belongs to. */
    private class FakeCipher(private val device: String) : ValueCipher {
        override fun seal(plain: String) = "$device:" + plain.reversed()

        override fun open(sealed: String): String? =
            if (sealed.startsWith("$device:")) sealed.removePrefix("$device:").reversed() else null
    }

    private fun raw(key: String): String? =
        context.getSharedPreferences(PrefsStore.PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PrefsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `values are sealed in private preferences and read back`() {
        val store = PrefsStore(context, FakeCipher("phone-a"))
        store.put(StoreKeys.VISITOR_KEY, "secret-visitor-key")
        assertEquals("k1:phone-a:yek-rotisiv-terces", raw(StoreKeys.VISITOR_KEY))
        assertFalse(raw(StoreKeys.VISITOR_KEY)!!.contains("secret-visitor-key"))
        // A new instance (cold start) reads the same value.
        assertEquals("secret-visitor-key", PrefsStore(context, FakeCipher("phone-a")).get(StoreKeys.VISITOR_KEY))
        store.put(StoreKeys.VISITOR_KEY, null)
        assertNull(raw(StoreKeys.VISITOR_KEY))
        assertNull(PrefsStore(context, FakeCipher("phone-a")).get(StoreKeys.VISITOR_KEY))
    }

    @Test
    fun `a value restored from another device's backup is dropped`() {
        PrefsStore(context, FakeCipher("phone-a")).put(StoreKeys.VISITOR_KEY, "secret")
        val restored = PrefsStore(context, FakeCipher("phone-b"))
        assertNull(restored.get(StoreKeys.VISITOR_KEY))
        assertNull("removed from storage", raw(StoreKeys.VISITOR_KEY))
    }

    /** The real cipher's transient failure: [failures] calls throw KeyStoreException first. */
    private class FlakyCipher(var failures: Int) : ValueCipher {
        private val inner = FakeCipher("phone-a")

        override fun seal(plain: String) = inner.seal(plain)

        override fun open(sealed: String): String? {
            if (failures > 0) {
                failures--
                throw java.security.KeyStoreException("Keystore busy")
            }
            return inner.open(sealed)
        }
    }

    @Test
    fun `a Keystore error that passes keeps the stored value`() {
        PrefsStore(context, FakeCipher("phone-a")).put(StoreKeys.VISITOR_KEY, "secret")
        val stored = raw(StoreKeys.VISITOR_KEY)

        // Fails once: the second try inside the same read succeeds.
        assertEquals("secret", PrefsStore(context, FlakyCipher(failures = 1)).get(StoreKeys.VISITOR_KEY))

        // Keeps failing: nothing returned, nothing dropped, nothing cached.
        val cipher = FlakyCipher(failures = 2)
        val store = PrefsStore(context, cipher)
        assertNull(store.get(StoreKeys.VISITOR_KEY))
        assertEquals("the stored value is intact", stored, raw(StoreKeys.VISITOR_KEY))
        assertTrue(store.isUnreadable(StoreKeys.VISITOR_KEY))
        // The Keystore recovers: the same instance reads it now.
        assertEquals("secret", store.get(StoreKeys.VISITOR_KEY))
        assertFalse(store.isUnreadable(StoreKeys.VISITOR_KEY))
    }

    @Test
    fun `without a working Keystore values are stored unwrapped`() {
        // Robolectric has no AndroidKeyStore provider: the real cipher fails and the store falls back.
        val store = PrefsStore(context, KeystoreCipher())
        store.put(StoreKeys.PUSH_TOKEN, "fcm-token")
        assertEquals("p1:fcm-token", raw(StoreKeys.PUSH_TOKEN))
        assertEquals("fcm-token", PrefsStore(context, KeystoreCipher()).get(StoreKeys.PUSH_TOKEN))
        assertEquals("fcm-token", PrefsStore(context, null).get(StoreKeys.PUSH_TOKEN))
    }

    @Test
    fun `unknown formats are ignored`() {
        context.getSharedPreferences(PrefsStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(StoreKeys.USER, "legacy").commit()
        assertNull(PrefsStore(context, null).get(StoreKeys.USER))
    }

    @Test
    fun `the backup rules exclude the preferences file`() {
        val name = PrefsStore.PREFS_NAME + ".xml"
        listOf(com.zebchat.chat.R.xml.zebchat_backup_rules, com.zebchat.chat.R.xml.zebchat_data_extraction_rules).forEach { id ->
            val parser = context.resources.getXml(id)
            var excludes = 0
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude") {
                    assertEquals("sharedpref", parser.getAttributeValue(null, "domain"))
                    assertEquals(name, parser.getAttributeValue(null, "path"))
                    excludes++
                }
            }
            assertTrue(excludes > 0)
        }
    }

    @Test
    fun `the memory store behaves like the real one`() {
        val store = MemoryStore()
        store.put("a", "1")
        assertEquals("1", store.get("a"))
        store.put("a", null)
        assertNull(store.get("a"))
    }
}
