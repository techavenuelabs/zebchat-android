package com.zebchat.chat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureHelpersTest {
    @Test
    fun `parses API date-times`() {
        assertEquals(1_791_460_800_000L, IsoTime.parse("2026-10-08T12:00:00.000Z"))
        assertEquals(1_791_460_800_123L, IsoTime.parse("2026-10-08T12:00:00.123Z"))
        assertEquals(1_791_460_800_000L, IsoTime.parse("2026-10-08T14:00:00+02:00"))
        assertEquals(1_791_460_800_000L, IsoTime.parse("2026-10-08T07:00:00-0500"))
        assertEquals(1_791_460_800_000L, IsoTime.parse("2026-10-08T12:00Z"))
        assertNull(IsoTime.parse("2026-02-30T12:00:00Z"))
        assertNull(IsoTime.parse("2026-10-08 12:00:00Z"))
        assertNull(IsoTime.parse("2026-10-08T12:00:00"))
        assertNull(IsoTime.parse("2026-13-08T12:00:00Z"))
        assertNull(IsoTime.parse("soon"))
    }

    @Test
    fun `screen URLs match appScreenUrl in packages-types`() {
        assertEquals("app://com.shop.app/Checkout", ScreenUrl.of("com.shop.app", " Checkout "))
        assertEquals("app://com.shop.app/Cart/Step%202", ScreenUrl.of("com.shop.app", "Cart/Step 2"))
        assertEquals("app://a.b/Caf%C3%A9%20%26%20more%3F", ScreenUrl.of("a.b", "Café & more?"))
        assertEquals("-_.!~*'()", ScreenUrl.encodeUriComponent("-_.!~*'()"))
        assertEquals("%F0%9F%98%80", ScreenUrl.encodeUriComponent("😀"))
    }

    @Test
    fun `navigation policy keeps the WebView on the CDN`() {
        val cdn = "https://cdn.zebchat.com"
        val api = "https://api.zebchat.com"
        fun classify(url: String) = LinkPolicy.classify(url, cdn, api)
        assertEquals(LinkPolicy.Action.LOAD, classify("https://cdn.zebchat.com/widget/v1/mobile.html?site=zc_x"))
        assertEquals(LinkPolicy.Action.LOAD, classify("https://CDN.zebchat.com:443/widget/v1/mobile.html"))
        assertEquals(LinkPolicy.Action.DOWNLOAD, classify("https://api.zebchat.com/api/v1/files/abc.def"))
        assertEquals(LinkPolicy.Action.EXTERNAL, classify("https://api.zebchat.com/api/v1/widget/config"))
        assertEquals(LinkPolicy.Action.EXTERNAL, classify("https://example.com/help"))
        assertEquals(LinkPolicy.Action.EXTERNAL, classify("http://cdn.zebchat.com/widget/v1/mobile.html"))
        assertEquals(LinkPolicy.Action.EXTERNAL, classify("https://cdn.zebchat.com:8443/x"))
        assertEquals(LinkPolicy.Action.EXTERNAL, classify("mailto:help@example.com"))
        assertEquals(LinkPolicy.Action.EXTERNAL, classify("tel:+4912345"))
        assertEquals(LinkPolicy.Action.BLOCK, classify("javascript:alert(1)"))
        assertEquals(LinkPolicy.Action.BLOCK, classify("file:///sdcard/x"))
        assertEquals(LinkPolicy.Action.BLOCK, classify("content://com.other/x"))
        assertEquals(LinkPolicy.Action.BLOCK, classify("intent://x#Intent;scheme=https;end"))
        assertEquals(LinkPolicy.Action.BLOCK, classify("data:text/html,hi"))
        assertEquals(LinkPolicy.Action.BLOCK, classify("not a url"))
    }

    @Test
    fun `frames allowed with the fallback bridge are the CDN and Turnstile`() {
        val cdn = "https://cdn.zebchat.com"
        assertTrue(LinkPolicy.isAllowedFrame("https://cdn.zebchat.com/widget/v1/frame.html", cdn))
        assertTrue(LinkPolicy.isAllowedFrame("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/x", cdn))
        assertTrue(LinkPolicy.isAllowedFrame("about:blank", cdn))
        assertFalse(LinkPolicy.isAllowedFrame("https://evil.test/frame", cdn))
        assertFalse(LinkPolicy.isAllowedFrame("http://challenges.cloudflare.com/x", cdn))
        assertFalse(LinkPolicy.isAllowedFrame("javascript:alert(1)", cdn))
        assertFalse(LinkPolicy.isAllowedFrame("data:text/html,hi", cdn))
    }

    @Test
    fun `origins drop default ports and keep others`() {
        assertEquals("https://cdn.zebchat.com", LinkPolicy.originOf("https://cdn.zebchat.com/widget/v1/mobile.html"))
        assertEquals("http://10.0.2.2:5173", LinkPolicy.originOf("http://10.0.2.2:5173/mobile.html"))
        assertNull(LinkPolicy.originOf("ftp://x.y/z"))
        assertTrue(LinkPolicy.isOrigin("https://cdn.zebchat.com/a", "https://cdn.zebchat.com"))
        assertFalse(LinkPolicy.isOrigin("https://cdn.zebchat.com.evil.com/a", "https://cdn.zebchat.com"))
        assertFalse(LinkPolicy.isOrigin(null, "https://cdn.zebchat.com"))
    }

    @Test
    fun `permanent API errors are the 4xx a retry cannot fix`() {
        assertTrue(ApiException(400, "x").isPermanent)
        assertTrue(ApiException(403, "x").isPermanent)
        assertFalse(ApiException(401, "x").isPermanent)
        assertFalse(ApiException(429, "x").isPermanent)
        assertFalse(ApiException(503, "x").isPermanent)
    }
}
