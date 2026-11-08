package io.github.tensaiapp.agent

import io.github.tensaiapp.data.chat.ChatSearchDao
import io.github.tensaiapp.data.chat.ChatSearchHit
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

    private object Calendar : CalendarSource {
        override fun isGranted() = true
        override suspend fun access() = PermissionResult.Granted
        override fun events(startMillis: Long, endMillis: Long, limit: Int) = emptyList<CalendarEvent>()
    }

    private object Location : LocationSource {
        override fun isGranted() = true
        override suspend fun access() = PermissionResult.Granted
        override suspend fun current() = error("not run")
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
    private val searchChats = SearchChatsTool(object : ChatSearchDao {
        override suspend fun search(pattern: String, limit: Int, excludeConversationId: String?) = emptyList<ChatSearchHit>()
    })
    private val memory = MemoryTool(FakeMemoryDao())
    private val readCalendar = ReadCalendarTool(Calendar, inForeground = { true })
    private val findContact = FindContactTool(Contacts, inForeground = { true })
    private val location = LocationTool(Location, inForeground = { true })
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
    private val registry = ToolRegistry(
        listOf(notifications, clipboard, memory, searchChats, readCalendar, findContact, location) + network + phone,
        grants = { ToolTier.entries.toSet() },
    )

    /** The conversation after the user asked for something and [tool] answered. */
    private fun after(tool: Tool, result: String = "result", args: String = "{}"): ConversationTrust =
        listOf(
            ChatMessage(MessageRole.User, "check https://example.com/page and my messages"),
            ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall("c1", tool.name, args))),
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

    /** read_sms is in the same tier: a search after it asks too. */
    @Test
    fun afterReadingSmsAWebSearchAsks() {
        val sms = ReadSmsTool(
            object : SmsSource {
                override fun isGranted() = true
                override suspend fun access() = PermissionResult.Granted
                override fun query(sinceMillis: Long, box: SmsBox, maxRows: Int) = emptyList<SmsMessage>()
            },
            Contacts,
            inForeground = { true },
        )
        val trust = listOf(
            ChatMessage(MessageRole.User, "my texts"),
            ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall("c1", sms.name, "{}"))),
            ChatMessage(MessageRole.Tool, "result", toolCallId = "c1"),
        ).fold(ConversationTrust()) { t, m -> t.with(m, ToolRegistry(listOf(sms)) { ToolTier.entries.toSet() }::find) }

        assertTrue(trust.readPersonal)
        assertTrue(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
    }

    /** TEN-215: past chats can hold a quoted code or message, so a search in them is a personal read too. */
    @Test
    fun afterSearchingPastChatsANetworkOrPhoneToolAsks() {
        val trust = after(searchChats)

        assertTrue(trust.readPersonal)
        assertTrue(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
        assertTrue(AgentRuntime(registry).requiresConfirmation("draft_sms", """{"to":"Bob","body":"x"}""", trust))
    }

    /** Folded from the replayed history like the tier: a search in an earlier turn still counts in a later one. */
    @Test
    fun aSearchInAnEarlierTurnStillCounts() {
        val trust = listOf(
            ChatMessage(MessageRole.User, "what was that code?"),
            ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall("c1", searchChats.name, """{"query":"code"}"""))),
            ChatMessage(MessageRole.Tool, """[{"chat":"Bank","snippet":"code 123456"}]""", toolCallId = "c1"),
            ChatMessage(MessageRole.Assistant, "It was 123456."),
            ChatMessage(MessageRole.User, "now look up the weather"),
        ).fold(ConversationTrust()) { t, m -> t.with(m, registry::find) }

        assertTrue(trust.readPersonal)
        assertTrue(network.first { it.name == "weather" }.asksFirst("""{"place":"Paris"}""", trust))
    }

    /** TEN-216: a clipboard `get` can be a password or a code just copied: nothing sends or opens unasked after it. */
    @Test
    fun afterAClipboardGetANetworkOrPhoneToolAsks() {
        val trust = after(clipboard, result = "123456", args = """{"action":"get"}""")

        assertTrue(trust.readPersonal)
        assertTrue(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
        assertTrue(AgentRuntime(registry).requiresConfirmation("draft_sms", """{"to":"Bob","body":"x"}""", trust))
    }

    @Test
    fun aClipboardSetAloneChangesNothing() {
        val trust = after(clipboard, args = """{"action":"set","text":"x"}""")

        assertFalse(trust.readPersonal)
        assertFalse(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
        assertFalse(clipboard.asksFirst("""{"action":"set","text":"y"}""", trust))
    }

    /** Folded from the replayed history: a `get` in an earlier turn still counts in a later one. */
    @Test
    fun aClipboardGetInAnEarlierTurnStillCounts() {
        val trust = listOf(
            ChatMessage(MessageRole.User, "what did I copy?"),
            ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall("c1", clipboard.name, """{"action":"get"}"""))),
            ChatMessage(MessageRole.Tool, "hunter2", toolCallId = "c1"),
            ChatMessage(MessageRole.Assistant, "You copied a password."),
            ChatMessage(MessageRole.User, "search the web for it"),
        ).fold(ConversationTrust()) { t, m -> t.with(m, registry::find) }

        assertTrue(trust.readPersonal)
        assertTrue(network.first { it.name == "web_search" }.asksFirst("""{"query":"hunter2"}""", trust))
    }

    /** Each result is read against its own call, by id: a `set` and a `get` in one reply count apart. */
    @Test
    fun eachResultIsReadAgainstItsOwnCall() {
        val reply = listOf(
            ChatMessage(MessageRole.User, "copy this, then tell me what was there"),
            ChatMessage(
                MessageRole.Assistant,
                "",
                toolCalls = listOf(ToolCall("c1", clipboard.name, """{"action":"set","text":"x"}"""), ToolCall("c2", clipboard.name, """{"action":"get"}""")),
            ),
        ).fold(ConversationTrust()) { t, m -> t.with(m, registry::find) }

        val afterSet = reply.with(ChatMessage(MessageRole.Tool, "copied", toolCallId = "c1"), registry::find)
        val afterGet = afterSet.with(ChatMessage(MessageRole.Tool, "x", toolCallId = "c2"), registry::find)

        assertFalse(afterSet.readPersonal)
        assertTrue(afterGet.readPersonal)
    }

    /** TEN-217: the agenda is the user's personal data: a search after reading it asks. */
    @Test
    fun afterReadingTheCalendarAWebSearchAsks() {
        val trust = after(readCalendar, result = "10:00-11:00 Doctor", args = """{"day":"today"}""")

        assertTrue(trust.readPersonal)
        assertTrue(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
    }

    /** "text Jade I'm late" as find_contact then draft_sms: the draft asks. draft_sms alone, resolving the name itself, does not. */
    @Test
    fun afterFindingAContactADraftSmsAsks() {
        val found = """{"contacts":[{"name":"Jade","phones":[{"type":"mobile","number":"0600000000"}]}]}"""
        val trust = after(findContact, result = found, args = """{"name":"Jade"}""")

        assertTrue(trust.readPersonal)
        assertTrue(AgentRuntime(registry).requiresConfirmation("draft_sms", """{"to":"0600000000","body":"I'm late"}""", trust))
    }

    /** Location stays outside the rule: weather right after it is what it is for. */
    @Test
    fun locationThenWeatherDoesNotAsk() {
        val trust = after(location, result = """{"latitude":48.1,"longitude":-1.7}""")

        assertFalse(trust.readPersonal)
        assertFalse(network.first { it.name == "weather" }.asksFirst("""{"place":"here"}""", trust))
    }

    /** A chat that read none of these: a draft by name and a search run unasked, as before. */
    @Test
    fun aFreshChatWithoutAPersonalReadIsUnchanged() {
        val trust = listOf(ChatMessage(MessageRole.User, "text Jade I'm late"))
            .fold(ConversationTrust()) { t, m -> t.with(m, registry::find) }

        assertFalse(trust.readPersonal)
        assertFalse(AgentRuntime(registry).requiresConfirmation("draft_sms", """{"to":"Jade","body":"I'm late"}""", trust))
        assertFalse(network.first { it.name == "web_search" }.asksFirst("""{"query":"x"}""", trust))
    }

    /** Folded from the replayed history: a calendar read or a contact lookup in an earlier turn still counts in a later one. */
    @Test
    fun aCalendarOrContactReadInAnEarlierTurnStillCounts() {
        for ((tool, args) in listOf(readCalendar to """{"day":"today"}""", findContact to """{"name":"Jade"}""")) {
            val trust = listOf(
                ChatMessage(MessageRole.User, "what do I have today?"),
                ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall("c1", tool.name, args))),
                ChatMessage(MessageRole.Tool, "result", toolCallId = "c1"),
                ChatMessage(MessageRole.Assistant, "Here it is."),
                ChatMessage(MessageRole.User, "now look up the weather"),
            ).fold(ConversationTrust()) { t, m -> t.with(m, registry::find) }

            assertTrue(tool.name, trust.readPersonal)
            assertTrue(tool.name, network.first { it.name == "weather" }.asksFirst("""{"place":"Paris"}""", trust))
        }
    }
}
