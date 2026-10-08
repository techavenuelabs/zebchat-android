package com.zebchat.chat.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeTest {
    @Test
    fun `parses the five page messages`() {
        assertEquals(PageMessage.Ready, Bridge.parse("""{"type":"ready","v":1}"""))
        assertEquals(
            PageMessage.Renewed("tok-2", "2026-10-08T12:00:00.000Z"),
            Bridge.parse("""{"type":"renewed","token":"tok-2","expiresAt":"2026-10-08T12:00:00.000Z"}"""),
        )
        assertEquals(PageMessage.Expired, Bridge.parse("""{"type":"expired"}"""))
        assertEquals(PageMessage.Close, Bridge.parse("""{"type":"close"}"""))
        assertEquals(PageMessage.Error(503, "down"), Bridge.parse("""{"type":"error","status":503,"message":"down"}"""))
        assertEquals(PageMessage.Error(0, ""), Bridge.parse("""{"type":"error","status":0.0,"message":""}"""))
    }

    @Test
    fun `refuses what the TypeScript guard refuses`() {
        val refused = listOf(
            null,
            "",
            "not json",
            "[]",
            "\"ready\"",
            """{"type":"ready"}""",
            """{"type":"ready","v":2}""",
            """{"type":"ready","v":"1"}""",
            """{"type":"renewed","token":"","expiresAt":"2026-10-08T12:00:00.000Z"}""",
            """{"type":"renewed","token":"t","expiresAt":"tomorrow"}""",
            """{"type":"renewed","token":"t"}""",
            """{"type":"renewed","token":"${"x".repeat(4097)}","expiresAt":"2026-10-08T12:00:00.000Z"}""",
            """{"type":"error","status":1.5,"message":"x"}""",
            """{"type":"error","status":"500","message":"x"}""",
            """{"type":"error","status":500}""",
            """{"type":"error","status":500,"message":"${"x".repeat(501)}"}""",
            """{"type":"init","v":1}""",
            """{"type":"unknown"}""",
            """{"v":1}""",
            """{"type":"ready","v":1,"pad":"${"x".repeat(Bridge.MAX_MESSAGE_CHARS)}"}""",
        )
        refused.forEach { assertNull("should refuse $it", Bridge.parse(it)) }
    }

    @Test
    fun `builds the init message the page guard accepts`() {
        val visitor = """{"id":"vis_1","name":"Ana","email":null,"phone":null,"verified":true}"""
        val init = JSONObject(Bridge.initMessage("tok", "2026-10-08T12:00:00.000Z", visitor))
        assertEquals("init", init.getString("type"))
        assertEquals(1, init.getInt("v"))
        assertEquals("tok", init.getString("token"))
        assertEquals("2026-10-08T12:00:00.000Z", init.getString("expiresAt"))
        assertEquals("vis_1", init.getJSONObject("visitor").getString("id"))
        assertTrue(init.getJSONObject("visitor").getBoolean("verified"))
    }

    @Test
    fun `the receive script passes the message as one quoted string`() {
        val script = Bridge.receiveScript("""{"name":"</script><b>'\"x"}""")
        assertTrue(script.startsWith("window.ZebChatBridge&&window.ZebChatBridge.receive(\""))
        assertTrue(script.endsWith("\");"))
        assertTrue("no raw closing tag", !script.contains("</script>"))
    }

    @Test
    fun `the receive script escapes JavaScript line separators`() {
        val script = Bridge.receiveScript("{\"name\":\"a b c\"}")
        assertTrue(!script.contains(' ') && !script.contains(' '))
        assertTrue(script.contains("a\\u2028b\\u2029c"))
    }

    @Test
    fun `the message listener accepts only the CDN main frame`() {
        val cdn = "https://cdn.zebchat.com"
        assertTrue(Bridge.accepts("https://cdn.zebchat.com", isMainFrame = true, cdnOrigin = cdn))
        assertTrue(Bridge.accepts("https://CDN.zebchat.com:443", isMainFrame = true, cdnOrigin = cdn))
        assertFalse("the Turnstile iframe", Bridge.accepts("https://challenges.cloudflare.com", isMainFrame = false, cdnOrigin = cdn))
        assertFalse("a CDN subframe", Bridge.accepts(cdn, isMainFrame = false, cdnOrigin = cdn))
        assertFalse(Bridge.accepts("https://evil.test", isMainFrame = true, cdnOrigin = cdn))
        assertFalse(Bridge.accepts("http://cdn.zebchat.com", isMainFrame = true, cdnOrigin = cdn))
        assertFalse(Bridge.accepts("null", isMainFrame = true, cdnOrigin = cdn))
        assertFalse(Bridge.accepts(null, isMainFrame = true, cdnOrigin = cdn))
    }
}
