package io.github.tensaiapp.agent

import io.github.tensaiapp.data.memory.FakeMemoryDao
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.websearch.PageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-211: once the user's messages are in the conversation, nothing that can carry them off the phone or into another
 * app runs unasked. Checked on the real tools, as the registry builds them.
 */
class PersonalReadGateTest {

    private object Phone : PhoneActions {
        override val inForeground = true
        override fun handlerLabel(spec: IntentSpec): String? = "App"
        override fun start(spec: IntentSpec) = Unit
        override fun launchableApps(): List<InstalledApp> = emptyList()
        override fun launchApp(packageName: String) = Unit
        override val isMusicActive = false
        override fun sendMediaKey(key: MediaKey) = Unit
    }

    private object Contacts : ContactsSource {
        override fun isGranted() = true
        override suspend fun access() = error("not run")
        override fun load(): List<Contact> = emptyList()
    }

    private object Clipboard : ClipboardAccess {
        override fun read(): String? = null
        override fun write(text: String) = Unit
    }

    private val notifications = ReadNotificationsTool(
        object : NotificationAccess {
            override fun enabled() = true
            override suspend fun active() = emptyList<NotificationEntry>()
        },
        NotificationBuffer(),
    )
    private val clipboard = ClipboardTool(Clipboard)
    private val memory = MemoryTool(FakeMemoryDao())
    private val network = listOf(
        WebSearchTool { error("not run") },
        WebFetchTool(PageReader(FakeHttp())),
        WikipediaTool(FakeHttp(), "ua"),
        WeatherTool(FakeHttp()),
    )
    private val phone = listOf(
        SetTimerTool(Phone), SetAlarmTool(Phone), CreateEventTool(Phone), DraftSmsTool(Phone, Contacts), DraftEmailTool(Phone, Contacts),
        OpenUrlTool(Phone), OpenAppTool(Phone), MediaControlTool(Phone),
    )
    private val registry = ToolRegistry(listOf(notifications, clipboard, memory) + network + phone, grants = { ToolTier.entries.toSet() })

    /** The conversation after the user asked for something and [tool] answered. */
    private fun after(tool: Tool, result: String = "result"): ConversationTrust =
        listOf(
            ChatMessage(MessageRole.User, "check https://example.com/page and my messages"),
            ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall("c1", tool.name, "{}"))),
            ChatMessage(MessageRole.Tool, result, toolCallId = "c1"),
        ).fold(ConversationTrust()) { t, m -> t.with(m, registry::find) }

    private val args = """{"query":"x","url":"https://example.com/page","topic":"x","place":"Paris","minutes":5,"time":"07:00",""" +
        """"title":"x","start":"2026-11-08T10:00","to":"Bob","body":"x","app":"Clock","action":"play","text":"x"}"""

    @Test
    fun afterReadingNotificationsEveryNetworkAndPhoneToolAsks() {
        val trust = after(notifications)

        assertTrue(trust.readPersonal)
        assertEquals(emptyList<String>(), (network + phone).filterNot { it.asksFirst(args, trust) }.map { it.name })
        assertTrue(clipboard.asksFirst("""{"action":"set","text":"123456"}""", trust))
        assertTrue(memory.asksFirst("""{"action":"remember","text":"code 123456"}""", trust))
    }

    @Test
    fun theRuntimesCheckIsTheSameAsTheCards() {
        val runtime = AgentRuntime(registry)
        val trust = after(notifications)

        assertTrue(runtime.requiresConfirmation("draft_sms", """{"to":"Bob","body":"x"}""", trust))
        assertTrue(runtime.requiresConfirmation("web_search", """{"query":"x"}""", trust))
    }

    /** Without a personal read, nothing changes: a draft and a search run as before, a clipboard set too. */
    @Test
    fun withoutAPersonalReadTheToolsAskAsBefore() {
        val trust = after(clipboard)

        assertFalse(trust.readPersonal)
        assertFalse(phone.first { it.name == "draft_sms" }.asksFirst("""{"to":"Bob","body":"x"}""", trust))
        assertFalse(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
        assertFalse(network.first { it.name == "weather" }.asksFirst("""{"place":"Paris"}""", trust))
        assertFalse(clipboard.asksFirst("""{"action":"set","text":"x"}""", ConversationTrust()))
        assertTrue("open_url always asked", phone.first { it.name == "open_url" }.asksFirst("""{"url":"https://example.com"}""", ConversationTrust()))
        assertTrue("a remember after untrusted text asked already", memory.asksFirst("""{"action":"remember","text":"x"}""", trust))
    }
}
