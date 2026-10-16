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
    fun theDefaultGrantsAreLocalAndPhoneActionOnly() {
        assertEquals(listOf("local", "phone"), ToolRegistry(all).active().map { it.name })
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
    fun theDefaultRegistryHoldsOnlyDatetime() {
        assertEquals(listOf("datetime"), ToolRegistry.default().active().map { it.name })
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
