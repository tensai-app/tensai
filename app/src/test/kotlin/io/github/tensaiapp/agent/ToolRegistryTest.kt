package io.github.tensaiapp.agent

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ToolRegistry]'s tier filtering and the built-in `datetime` tool (TEN-172). */
class ToolRegistryTest {

    private class StubTool(override val name: String, override val tier: ToolTier) : Tool {
        override val description = "stub"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(argsJson: String) = ToolResult.ok("")
    }

    private val all = listOf(
        StubTool("local", ToolTier.Local),
        StubTool("phone", ToolTier.PhoneAction),
        StubTool("perm", ToolTier.AndroidPermission),
        StubTool("net", ToolTier.Network),
    )

    @Test
    fun theDefaultGrantsAreEverythingButTheNetwork() {
        assertEquals(listOf("local", "phone", "perm"), ToolRegistry(all).active().map { it.name })
    }

    @Test
    fun activeFollowsTheGrantedTiersAtEachCall() {
        var granted = setOf(ToolTier.Network)
        val registry = ToolRegistry(all) { granted }

        assertEquals(listOf("net"), registry.active().map { it.name })

        granted = ToolTier.entries.toSet()
        assertEquals(listOf("local", "phone", "perm", "net"), registry.active().map { it.name })

        granted = emptySet()
        assertTrue(registry.active().isEmpty())
    }

    @Test
    fun registeringTheSameNameTwiceIsRefused() {
        val registry = ToolRegistry(listOf(StubTool("a", ToolTier.Local)))

        assertThrows(IllegalArgumentException::class.java) { registry.register(StubTool("a", ToolTier.Network)) }
    }

    @Test
    fun theDefaultRegistryHoldsTheToolsThatNeedNoPlatform() {
        assertEquals(listOf("datetime", "calculate", "convert", "convert_time"), ToolRegistry.default().active().map { it.name })
    }

    @Test
    fun everyLocalToolIsRegisteredUnderTierLocalAndNeedsNoPermission() {
        val platform = listOf(
            DeviceInfoTool { error("not read here") },
            SearchChatsTool(object : io.github.tensaiapp.data.chat.ChatSearchDao {
                override suspend fun search(pattern: String, limit: Int, excludeConversationId: String?) = emptyList<io.github.tensaiapp.data.chat.ChatSearchHit>()
            }),
            ClipboardTool(object : ClipboardAccess {
                override fun read(): String? = null
                override fun write(text: String) = Unit
            }),
            TorchTool(object : TorchControl {
                override val available = false
                override fun set(on: Boolean) = Unit
            }),
            MemoryTool(object : io.github.tensaiapp.data.memory.MemoryDao {
                override suspend fun insert(fact: io.github.tensaiapp.data.memory.MemoryFactEntity) = 1L
                override suspend fun recent(limit: Int) = emptyList<io.github.tensaiapp.data.memory.MemoryFactEntity>()
                override fun observeAll() = kotlinx.coroutines.flow.emptyFlow<List<io.github.tensaiapp.data.memory.MemoryFactEntity>>()
                override suspend fun count() = 0
                override fun observeCount() = kotlinx.coroutines.flow.emptyFlow<Int>()
                override suspend fun findIdByText(text: String): Long? = null
                override suspend fun textOf(id: Long): String? = null
                override suspend fun delete(id: Long) = 0
                override suspend fun deleteIfText(id: Long, text: String) = 0
                override suspend fun deleteAll() = Unit
            }),
        )

        // Only the default grants (everything but Network): a Local tool shows up with no extra grant.
        val active = ToolRegistry.default(platformTools = platform).active()

        assertEquals(
            listOf("datetime", "calculate", "convert", "convert_time", "device_info", "search_chats", "clipboard", "torch", "memory"),
            active.map { it.name },
        )
        assertTrue(active.all { it.tier == ToolTier.Local })
        assertEquals(listOf("memory"), active.filter { it.sendsOrDeletes }.map { it.name })
        assertEquals(emptyList<String>(), active.filter { it.requiresConfirmation("""{"action":"list"}""") }.map { it.name })
    }

    @Test
    fun datetimeAnswersWithTheDeviceClock() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T12:03:22Z"), ZoneId.of("Europe/Paris"))

        val result = DateTimeTool(clock).execute("{}")

        assertFalse(result.isError)
        val json = JSONObject(result.text)
        assertEquals("2026-10-16T14:03:22+02:00", json.getString("iso"))
        assertEquals("2026-10-16", json.getString("date"))
        assertEquals("14:03:22", json.getString("time"))
        assertEquals("Friday", json.getString("dayOfWeek"))
        assertEquals("Europe/Paris", json.getString("timezone"))
        assertEquals("+02:00", json.getString("utcOffset"))
    }

    @Test
    fun datetimeGivesTheTimeInTheTimezoneAsked() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), ZoneId.of("Europe/Paris"))

        val json = JSONObject(DateTimeTool(clock).execute("""{"timezone":"Asia/Tokyo"}""").text)

        assertEquals("2026-10-17T05:00:00+09:00", json.getString("iso"))
        assertEquals("2026-10-17", json.getString("date"))
        assertEquals("05:00", json.getString("time"))
        assertEquals("Saturday", json.getString("dayOfWeek"))
        assertEquals("Asia/Tokyo", json.getString("timezone"))
        assertEquals("+09:00", json.getString("utcOffset"))
    }

    /** TEN-204: what the chat shows when the model's answer after the call came out empty twice. */
    @Test
    fun datetimeFallsBackOnItsOwnResult() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), ZoneId.of("Europe/Paris"))
        val tool = DateTimeTool(clock)

        assertEquals("2026-10-17 05:00 (Asia/Tokyo)", tool.fallbackAnswer(tool.execute("""{"timezone":"Asia/Tokyo"}""").text))
        assertEquals("2026-10-16 22:00 (Europe/Paris)", tool.fallbackAnswer(tool.execute("{}").text))
        assertNull("not its JSON: nothing to read", tool.fallbackAnswer("Error: timezone: unknown"))
    }

    @Test
    fun aBlankTimezoneIsTheDeviceOne() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T12:03:22Z"), ZoneId.of("Europe/Paris"))
        val device = DateTimeTool(clock).execute("{}")

        assertEquals(device, DateTimeTool(clock).execute("""{"timezone":""}"""))
        assertEquals(device, DateTimeTool(clock).execute("""{"timezone":"  "}"""))
    }

    @Test
    fun aTimezoneNamingNoPlaceIsTheDeviceOne() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T12:03:22Z"), ZoneId.of("Europe/Paris"))
        val device = DateTimeTool(clock).execute("{}")

        listOf(
            "auto", "Auto", "System", " local ", "device", "current", "default", "here", "now", "user", "mine",
            "my timezone", "local time", "Local Timezone", "system_default", "the user's timezone", "current time zone",
        ).forEach { zone ->
            assertEquals(zone, device, DateTimeTool(clock).execute(JSONObject().put("timezone", zone).toString()))
        }
    }

    @Test
    fun aRealZoneOrAnOffsetIsNotAPlaceholder() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T12:03:22Z"), ZoneId.of("Europe/Paris"))

        mapOf(
            "UTC" to "UTC", "Etc/UTC" to "Etc/UTC", "GMT+2" to "GMT+02:00", "US/Pacific" to "US/Pacific",
            "America/Los_Angeles" to "America/Los_Angeles", "+09:00" to "+09:00",
        ).forEach { (zone, id) ->
            val result = DateTimeTool(clock).execute(JSONObject().put("timezone", zone).toString())
            assertFalse(zone, result.isError)
            assertEquals(zone, id, JSONObject(result.text).getString("timezone"))
        }
        val nonAscii = DateTimeTool(clock).execute("""{"timezone":"東京"}""")
        assertTrue(nonAscii.text, nonAscii.text.startsWith("timezone: unknown time zone '東京'"))
    }

    @Test
    fun aZoneOtherThanTheDevicesAlsoCarriesTheDeviceTime() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T22:03:22Z"), ZoneId.of("Europe/Paris"))

        val utc = JSONObject(DateTimeTool(clock).execute("""{"timezone":"UTC"}""").text)

        assertEquals("22:03:22", utc.getString("time"))
        assertEquals("UTC", utc.getString("timezone"))
        assertEquals("2026-10-17T00:03:22", utc.getString("deviceTime"))
        assertEquals("Europe/Paris", utc.getString("deviceTimezone"))
        val tokyo = JSONObject(DateTimeTool(clock).execute("""{"timezone":"Asia/Tokyo"}""").text)
        assertEquals("07:03:22", tokyo.getString("time"))
        assertEquals("2026-10-17T00:03:22", tokyo.getString("deviceTime"))
        val onTheMinute = Clock.fixed(Instant.parse("2026-10-16T22:03:00Z"), ZoneId.of("Europe/Paris"))
        assertEquals("2026-10-17T00:03:00", JSONObject(DateTimeTool(onTheMinute).execute("""{"timezone":"UTC"}""").text).getString("deviceTime"))
    }

    @Test
    fun theDevicesOwnZoneCarriesNoDeviceFields() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-16T12:03:22Z"), ZoneId.of("Europe/Paris"))

        listOf("{}", """{"timezone":"Europe/Paris"}""", """{"timezone":"Europe/Berlin"}""").forEach { args ->
            val json = JSONObject(DateTimeTool(clock).execute(args).text)
            assertFalse(args, json.has("deviceTime"))
            assertFalse(args, json.has("deviceTimezone"))
        }
        // London is on UTC in winter: the same wall time, nothing to add.
        val london = Clock.fixed(Instant.parse("2026-12-16T12:03:22Z"), ZoneId.of("Europe/London"))
        assertFalse(JSONObject(DateTimeTool(london).execute("""{"timezone":"UTC"}""").text).has("deviceTime"))
    }

    @Test
    fun withoutAClockTheDeviceZoneIsReadAtEachCall() = runBlocking {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tool = DateTimeTool()
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))

            assertEquals("Europe/Paris", JSONObject(tool.execute("{}").text).getString("timezone"))
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun theSchemaSaysToOmitTheTimezoneForTheLocalTime() {
        assertTrue(DateTimeTool().parametersSchema.contains("Omit for the user's local time."))
    }

    @Test
    fun anUnknownTimezoneIsAToolErrorNamingTheArgument() = runBlocking {
        val result = DateTimeTool().execute("""{"timezone":"Tokyo"}""")

        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("timezone: unknown time zone 'Tokyo'"))
        val placeLike = DateTimeTool().execute("""{"timezone":"my office"}""")
        assertTrue(placeLike.text, placeLike.text.startsWith("timezone: unknown time zone 'my office'"))
    }

    @Test
    fun datetimeIsALocalToolWithNoRequiredArgument() {
        val tool = DateTimeTool()

        assertEquals(ToolTier.Local, tool.tier)
        assertFalse(tool.sendsOrDeletes)
        assertEquals(null, ToolArgs.check(tool.parametersSchema, "{}"))
    }
}
