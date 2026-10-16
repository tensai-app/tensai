package io.github.hokanosekai.tensai.agent

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            SearchChatsTool(object : io.github.hokanosekai.tensai.data.chat.ChatSearchDao {
                override suspend fun search(pattern: String, limit: Int, excludeConversationId: String?) = emptyList<io.github.hokanosekai.tensai.data.chat.ChatSearchHit>()
            }),
            ClipboardTool(object : ClipboardAccess {
                override fun read(): String? = null
                override fun write(text: String) = Unit
            }),
            TorchTool(object : TorchControl {
                override val available = false
                override fun set(on: Boolean) = Unit
            }),
            MemoryTool(object : io.github.hokanosekai.tensai.data.memory.MemoryDao {
                override suspend fun insert(fact: io.github.hokanosekai.tensai.data.memory.MemoryFactEntity) = 1L
                override suspend fun recent(limit: Int) = emptyList<io.github.hokanosekai.tensai.data.memory.MemoryFactEntity>()
                override fun observeAll() = kotlinx.coroutines.flow.emptyFlow<List<io.github.hokanosekai.tensai.data.memory.MemoryFactEntity>>()
                override suspend fun count() = 0
                override fun observeCount() = kotlinx.coroutines.flow.emptyFlow<Int>()
                override suspend fun findIdByText(text: String): Long? = null
                override suspend fun delete(id: Long) = 0
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
    fun datetimeIsALocalToolWithNoRequiredArgument() {
        val tool = DateTimeTool()

        assertEquals(ToolTier.Local, tool.tier)
        assertFalse(tool.sendsOrDeletes)
        assertEquals(null, ToolArgs.check(tool.parametersSchema, "{}"))
    }
}
