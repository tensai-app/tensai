package io.github.tensaiapp.agent

import io.github.tensaiapp.data.chat.ChatSearchDao
import io.github.tensaiapp.data.chat.ChatSearchHit
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `device_info`, `search_chats`, `clipboard` and `torch` (TEN-175), against fakes of what they read. */
class LocalToolsTest {

    // device_info

    private val snapshot = DeviceSnapshot(
        batteryPercent = 83,
        charging = true,
        storageFreeBytes = 42_349_000_000,
        storageTotalBytes = 128_000_000_000,
        ramFreeBytes = 3_400_000_000,
        ramTotalBytes = 8_000_000_000,
        minutesToFull = 85,
        network = "wifi",
        androidVersion = "14 (API 34)",
        model = "Google Pixel 8",
    )

    @Test
    fun deviceInfoFormatsTheSnapshot() = runBlocking {
        val result = DeviceInfoTool { snapshot }.execute("{}")

        val json = JSONObject(result.text)
        assertEquals(83, json.getInt("batteryPercent"))
        assertTrue(json.getBoolean("charging"))
        assertEquals(42.3, json.getDouble("storageFreeGB"), 1e-9)
        assertEquals(128.0, json.getDouble("storageTotalGB"), 1e-9)
        assertEquals(3.4, json.getDouble("ramFreeGB"), 1e-9)
        assertEquals(8.0, json.getDouble("ramTotalGB"), 1e-9)
        assertEquals("1h 25min", json.getString("timeToFull"))
        assertEquals("wifi", json.getString("network"))
        assertEquals("14 (API 34)", json.getString("android"))
        assertEquals("Google Pixel 8", json.getString("model"))
    }

    @Test
    fun deviceInfoLeavesTheBatteryOutWhenUnknownAndHasNoIdentifierField() {
        val json = JSONObject(DeviceInfoTool.format(snapshot.copy(batteryPercent = null, charging = null, network = "none")))

        assertFalse(json.has("batteryPercent"))
        assertFalse(json.has("charging"))
        assertFalse(json.has("timeToFull"))
        assertEquals("none", json.getString("network"))
        assertEquals(setOf("storageFreeGB", "storageTotalGB", "ramFreeGB", "ramTotalGB", "network", "android", "model"), json.keys().asSequence().toSet())
    }

    // search_chats

    @Test
    fun theLikePatternTakesWildcardsLiterally() {
        assertEquals("%rust%", ChatSearch.likePattern("  rust "))
        assertEquals("%100\\%%", ChatSearch.likePattern("100%"))
        assertEquals("%snake\\_case%", ChatSearch.likePattern("snake_case"))
        assertEquals("%a\\\\b%", ChatSearch.likePattern("a\\b"))
    }

    @Test
    fun aSnippetIsCenteredOnTheMatch() {
        val content = "a".repeat(100) + " the NEEDLE is here " + "b".repeat(100)

        val snippet = ChatSearch.snippet(content, "needle", radius = 10)

        assertTrue(snippet.startsWith("...") && snippet.endsWith("..."))
        assertTrue(snippet.contains("NEEDLE"))
        assertEquals(6 + 10 + 10 + 6, snippet.length)
    }

    @Test
    fun aShortMessageIsKeptWholeAndATitleOnlyMatchStartsAtTheTop() {
        assertEquals("hello world", ChatSearch.snippet("hello \n world", "world"))
        assertEquals("one two", ChatSearch.snippet("one two", "absent"))
    }

    private class FakeSearchDao(private val hits: List<ChatSearchHit>) : ChatSearchDao {
        var pattern: String? = null
        var limit: Int? = null
        var excluded: String? = "unset"
        override suspend fun search(pattern: String, limit: Int, excludeConversationId: String?): List<ChatSearchHit> {
            this.pattern = pattern
            this.limit = limit
            this.excluded = excludeConversationId
            return hits
        }
    }

    private val utc = { ZoneId.of("UTC") }

    @Test
    fun searchChatsQueriesTheDaoAndReturnsTitleDateAndSnippet() = runBlocking {
        val dao = FakeSearchDao(listOf(ChatSearchHit("c1", "Rust lifetimes", 1_760_000_000_000, "we talked about borrowing and lifetimes")))

        val result = SearchChatsTool(dao, zone = utc).execute("""{"query":"lifetimes"}""")

        assertEquals("%lifetimes%", dao.pattern)
        assertEquals(ChatSearch.DEFAULT_LIMIT, dao.limit)
        val hit = JSONArray(result.text).getJSONObject(0)
        assertEquals("Rust lifetimes", hit.getString("chat"))
        assertEquals("2025-10-09", hit.getString("date"))
        assertTrue(hit.getString("snippet").contains("lifetimes"))
    }

    @Test
    fun searchChatsClampsTheLimitAndRejectsAnEmptyQuery() = runBlocking {
        val dao = FakeSearchDao(emptyList())
        val tool = SearchChatsTool(dao, zone = utc)

        assertEquals("no match", tool.execute("""{"query":"x","limit":500}""").text)
        assertEquals(ChatSearch.MAX_LIMIT, dao.limit)
        tool.execute("""{"query":"x","limit":0}""")
        assertEquals(1, dao.limit)
        assertTrue(tool.execute("""{"query":"  "}""").isError)
        assertTrue(tool.execute("{").isError)
    }

    @Test
    fun searchChatsLeavesOutTheConversationAsking() = runBlocking {
        val dao = FakeSearchDao(emptyList())

        SearchChatsTool(dao, conversationId = { "current" }).execute("""{"query":"x"}""")
        assertEquals("current", dao.excluded)

        SearchChatsTool(dao).execute("""{"query":"x"}""")
        assertEquals(null, dao.excluded)
    }

    @Test
    fun deviceInfoDescriptionNamesWhatItReturns() {
        val description = DeviceInfoTool { snapshot }.description.lowercase()

        assertTrue("battery" in description && "storage" in description && "ram" in description)
    }

    @Test
    fun aJsonNullIsAMissingArgumentNotTheStringNull() = runBlocking {
        val clipboard = FakeClipboard()

        assertTrue(ClipboardTool(clipboard).execute("""{"action":"set","text":null}""").isError)
        assertEquals(null, clipboard.text)
        assertTrue(TorchTool(FakeTorch()).execute("""{"state":null}""").isError)
        assertTrue(SearchChatsTool(FakeSearchDao(emptyList())).execute("""{"query":null}""").isError)
    }

    // clipboard

    private class FakeClipboard(var text: String? = null, var denied: Boolean = false) : ClipboardAccess {
        override fun read(): String? = if (denied) throw SecurityException("background") else text
        override fun write(text: String) {
            if (denied) throw SecurityException("background")
            this.text = text
        }
    }

    @Test
    fun clipboardSetsThenGets() = runBlocking {
        val clipboard = FakeClipboard()
        val tool = ClipboardTool(clipboard)

        assertEquals(ClipboardTool.EMPTY, tool.execute("""{"action":"get"}""").text)
        assertEquals("copied", tool.execute("""{"action":"set","text":"hello"}""").text)
        assertEquals("hello", clipboard.text)
        assertEquals("hello", tool.execute("""{"action":"get"}""").text)
    }

    @Test
    fun clipboardReportsAClearErrorWhenAndroidRefusesAndOnBadInput() = runBlocking {
        val tool = ClipboardTool(FakeClipboard(denied = true))

        assertEquals(ClipboardTool.UNAVAILABLE, tool.execute("""{"action":"get"}""").text)
        assertTrue(tool.execute("""{"action":"get"}""").isError)
        assertTrue(tool.execute("""{"action":"set","text":"x"}""").isError)
        assertTrue(tool.execute("""{"action":"set"}""").isError)
        assertTrue(tool.execute("""{"action":"paste"}""").isError)
    }

    @Test
    fun aLongClipboardIsTruncated() = runBlocking {
        val result = ClipboardTool(FakeClipboard("x".repeat(5000))).execute("""{"action":"get"}""")

        assertTrue(result.text.length < 2100)
        assertTrue(result.text.endsWith("(truncated, 5000 characters)"))
    }

    // torch

    private class FakeTorch(override val available: Boolean = true, val failure: String? = null) : TorchControl {
        var on: Boolean? = null
        override fun set(on: Boolean) {
            failure?.let { throw IllegalStateException(it) }
            this.on = on
        }
    }

    @Test
    fun torchSwitchesOnAndOff() = runBlocking {
        val torch = FakeTorch()
        val tool = TorchTool(torch)

        assertEquals("torch on", tool.execute("""{"state":"on"}""").text)
        assertEquals(true, torch.on)
        assertEquals("torch off", tool.execute("""{"state":"OFF"}""").text)
        assertEquals(false, torch.on)
    }

    @Test
    fun torchErrorsAreClear() = runBlocking {
        val noFlash = TorchTool(FakeTorch(available = false)).execute("""{"state":"on"}""")
        assertTrue(noFlash.isError)
        assertEquals("this device has no flashlight", noFlash.text)

        val busy = TorchTool(FakeTorch(failure = "in use")).execute("""{"state":"on"}""")
        assertEquals("in use", busy.text)
        assertTrue(busy.isError)

        assertTrue(TorchTool(FakeTorch()).execute("""{"state":"blink"}""").isError)
    }

    @Test
    fun schemasAreShortAndRequiredArgumentsAreChecked() {
        val tools = listOf(
            DeviceInfoTool { snapshot },
            SearchChatsTool(FakeSearchDao(emptyList())),
            ClipboardTool(FakeClipboard()),
            TorchTool(FakeTorch()),
        )

        tools.forEach {
            assertEquals(ToolTier.Local, it.tier)
            assertFalse("${it.name} must not ask for confirmation", it.sendsOrDeletes)
            assertTrue("${it.name} schema is ${it.parametersSchema.length} chars", it.parametersSchema.length < 260)
            assertTrue(it.description.length < 200)
            JSONObject(it.parametersSchema)
        }
        assertEquals("missing required: query", ToolArgs.check(tools[1].parametersSchema, "{}"))
        assertEquals("missing required: action", ToolArgs.check(tools[2].parametersSchema, "{}"))
        assertEquals("missing required: state", ToolArgs.check(tools[3].parametersSchema, "{}"))
    }
}
