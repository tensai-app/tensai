package io.github.tensaiapp.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Sent to <host> · <what>" line of the call detail (TEN-181): what the model put in the request. */
class SentDetailTest {

    @Test fun eachNetworkToolShowsItsMainArgument() {
        assertEquals("kotlin coroutines", ToolUiCatalog.sentDetail("web_search", """{"query":"kotlin coroutines","limit":3}"""))
        assertEquals("Kotlin", ToolUiCatalog.sentDetail("wikipedia", """{"query":"Kotlin","lang":"fr"}"""))
        assertEquals("Rennes", ToolUiCatalog.sentDetail("weather", """{"place":"Rennes"}"""))
        assertEquals("https://example.com/a?b=1", ToolUiCatalog.sentDetail("web_fetch", """{"url":"https://example.com/a?b=1"}"""))
    }

    @Test fun itIsOneFlatLine() {
        assertEquals("a b c", ToolUiCatalog.sentDetail("web_search", "{\"query\":\"  a\\n b \\t c  \"}"))
    }

    @Test fun nothingWhenThereIsNothingToShow() {
        assertNull(ToolUiCatalog.sentDetail("web_search", "{}"))
        assertNull(ToolUiCatalog.sentDetail("web_search", """{"query":"   "}"""))
        assertNull(ToolUiCatalog.sentDetail("web_search", "not json"))
        assertNull(ToolUiCatalog.sentDetail("datetime", """{"query":"x"}"""))
    }
}
