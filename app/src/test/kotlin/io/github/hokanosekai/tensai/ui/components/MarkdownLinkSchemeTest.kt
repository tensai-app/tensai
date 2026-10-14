package io.github.hokanosekai.tensai.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkSchemeTest {

    @Test
    fun onlyHttpAndHttpsLinksAreLive() {
        listOf("http://a.b", "https://a.b/c", "HTTPS://A.B", "  Http://a.b").forEach { assertTrue(it, isWebLink(it)) }
    }

    @Test
    fun everyOtherSchemeAndEmptyIsPlainText() {
        listOf(
            "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "content://x", "tel:123", "mailto:a@b.c",
            "market://details?id=x", "ftp://a.b", "//a.b", "a.b/c", "", "httpx://a.b", "data:text/html;base64,AA==",
        ).forEach { assertFalse(it, isWebLink(it)) }
        assertFalse(isWebLink(null))
    }
}
