package io.github.tensaiapp.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `reply_notification` (TEN-213): the action chosen, the results handed over, the card always shown, the failures. */
class ReplyNotificationToolTest {

    private val now = 1_800_000_000_000L

    private class FakeAccess(
        var enabled: Boolean = true,
        var shade: List<NotificationEntry> = emptyList(),
        var outcome: ReplyOutcome = ReplyOutcome.Sent,
    ) : NotificationAccess {
        val sent = mutableListOf<Pair<String, String>>()

        override fun enabled() = enabled

        override suspend fun active() = shade

        override fun replyable() = shade.map { it.key }.toSet()

        override fun reply(key: String, text: String): ReplyOutcome {
            if (outcome == ReplyOutcome.Sent) sent += key to text
            return outcome
        }
    }

    private val alice = NotificationEntry("0|com.whatsapp|1|chat|10", "com.whatsapp", "WhatsApp", now - 60_000, "Alice", "on your way?")
    private val access = FakeAccess(shade = listOf(alice))
    private val tool = ReplyNotificationTool(access)
    private val id = notificationId(alice.key)

    private var calls = 0

    /** A call as the runtime makes it: the card first ([Tool.confirmTarget]), then [Tool.execute] under the call's id. */
    private fun run(args: String, beforeSend: () -> Unit = {}) = runBlocking {
        val callId = "c${++calls}"
        tool.confirmTarget(callId, args)
        beforeSend()
        withContext(ToolCallContext(callId)) { tool.execute(args) }
    }

    // ---- choosing the action

    @Test
    fun theAppsReplyActionComesFirstThenTheFirstThatTakesText() {
        val plain = ReplyActionInfo(semanticReply = false, freeFormInput = true)
        val reply = ReplyActionInfo(semanticReply = true, freeFormInput = true)
        val markRead = ReplyActionInfo(semanticReply = false, freeFormInput = false)

        assertEquals(1, pickReplyAction(listOf(plain, reply)))
        assertEquals(1, pickReplyAction(listOf(markRead, plain, plain)))
        // Marked as a reply but with nowhere to put the text: not one.
        assertEquals(1, pickReplyAction(listOf(ReplyActionInfo(semanticReply = true, freeFormInput = false), plain)))
        assertNull(pickReplyAction(listOf(markRead)))
        assertNull(pickReplyAction(emptyList()))
    }

    @Test
    fun theTextGoesUnderEveryInputThatTakesTypedText() {
        val results = replyResults(listOf(RemoteInputInfo("key_text_reply", freeForm = true), RemoteInputInfo("quick_choice", freeForm = false)), "On my way")

        assertEquals(mapOf("key_text_reply" to "On my way"), results)
    }

    // ---- confirmation

    @Test
    fun itAlwaysAsksEvenInACleanConversation() {
        val args = """{"id":"$id","text":"On my way"}"""

        assertTrue(tool.requiresConfirmation(args, ConversationTrust()))
        assertTrue(tool.asksFirst(args, ConversationTrust()))
        assertTrue(AgentRuntime(ToolRegistry(listOf(tool)) { ToolTier.entries.toSet() }).requiresConfirmation(tool.name, args, ConversationTrust()))
        assertEquals(ToolTier.PersonalData, tool.tier)
    }

    @Test
    fun theCardNamesTheAppAndTheConversation() = runBlocking {
        assertEquals(ConfirmTarget(id, "WhatsApp · Alice"), tool.confirmTarget("c1", """{"id":"$id","text":"x"}"""))
        assertNull(tool.confirmTarget("c2", """{"id":"ffffff","text":"x"}"""))
    }

    // ---- sending

    @Test
    fun aSentReplySaysWhereItWentAndClaimsNoDelivery() {
        val result = run("""{"id":"$id","text":"  On my way, 10 min  "}""")

        assertFalse(result.isError)
        assertEquals(listOf(alice.key to "On my way, 10 min"), access.sent)
        assertTrue(result.text, result.text.contains("WhatsApp") && result.text.contains("\"Alice\"") && result.text.contains("\"On my way, 10 min\""))
        assertTrue(result.text.contains("not known whether it was delivered or read"))
    }

    @Test
    fun aGoneNotificationOrAMissingOrCancelledActionSendsNothing() {
        val unknown = run("""{"id":"ffffff","text":"hi"}""")
        assertTrue(unknown.refused)
        assertEquals(ReplyNotificationTool.GoneText, unknown.text)

        val cases = mapOf(ReplyOutcome.Gone to "no longer showing", ReplyOutcome.NoAction to "does not accept a reply from tensai", ReplyOutcome.Cancelled to "no longer accepts")
        for ((outcome, says) in cases) {
            access.outcome = outcome
            val result = run("""{"id":"$id","text":"hi"}""")
            assertTrue(outcome.name, result.refused)
            assertTrue(result.text, result.text.contains(says))
        }
        assertTrue(access.sent.isEmpty())
    }

    @Test
    fun aTextOverTheCapIsNotCutButRefused() {
        val long = run("""{"id":"$id","text":"${"a".repeat(ReplyNotificationTool.MaxTextChars + 1)}"}""")
        val max = run("""{"id":"$id","text":"${"a".repeat(ReplyNotificationTool.MaxTextChars)}"}""")

        assertTrue(long.isError)
        assertFalse(max.isError)
        assertEquals(listOf(ReplyNotificationTool.MaxTextChars), access.sent.map { it.second.length })
    }

    @Test
    fun withAccessOffNothingIsSent() {
        access.enabled = false

        val result = run("""{"id":"$id","text":"hi"}""")

        assertTrue(result.refused)
        assertEquals(ReadNotificationsTool.AccessOffText, result.text)
        assertTrue(access.sent.isEmpty())
    }

    @Test
    fun anEmptyTextOrIdIsAnError() {
        assertTrue(run("""{"id":"$id","text":"   "}""").isError)
        assertTrue(run("""{"text":"hi"}""").isError)
        assertTrue(access.sent.isEmpty())
    }

    // ---- the recipient the card showed

    /** The app posts Bob's message on the key Alice's had while the card is open: nothing goes to Bob. */
    @Test
    fun anotherConversationOnTheSameKeyIsRefused() {
        val result = run("""{"id":"$id","text":"On my way"}""") {
            access.shade = listOf(alice.copy(title = "Bob", text = "who is this?", postTime = now))
        }

        assertTrue(result.refused)
        assertEquals(ReplyNotificationTool.ChangedText, result.text)
        assertTrue(access.sent.isEmpty())
    }

    @Test
    fun aNewerMessageInTheSameConversationStillSends() {
        val result = run("""{"id":"$id","text":"On my way"}""") {
            access.shade = listOf(alice.copy(text = "and bring bread", postTime = now))
        }

        assertFalse(result.text, result.isError)
        assertEquals(listOf(alice.key to "On my way"), access.sent)
    }

    @Test
    fun withoutACardNothingIsSent() = runBlocking {
        val result = withContext(ToolCallContext("never-shown")) { tool.execute("""{"id":"$id","text":"On my way"}""") }

        assertTrue(result.refused)
        assertEquals(ReplyNotificationTool.ChangedText, result.text)
        assertTrue(access.sent.isEmpty())
    }

    @Test
    fun aConversationThatBecameAGroupIsRefused() {
        val result = run("""{"id":"$id","text":"On my way"}""") { access.shade = listOf(alice.copy(group = true)) }

        assertEquals(ReplyNotificationTool.ChangedText, result.text)
        assertTrue(access.sent.isEmpty())
    }

    // ---- exact text

    @Test
    fun aTextThatIsNotAStringSendsNothing() {
        for (args in listOf("""{"id":"$id","text":["ok","see you"]}""", """{"id":"$id","text":42}""")) {
            val result = run(args)
            assertTrue(args, result.isError)
            assertFalse(result.refused)
        }
        assertTrue(access.sent.isEmpty())
    }

    // ---- groups

    @Test
    fun aGroupShowsAsAGroupWithItsLastSender() = runBlocking {
        val mom = NotificationEntry("0|com.whatsapp|1|grp|11", "com.whatsapp", "WhatsApp", now, "Mom", "Alice: dinner at 8", group = true, lastSender = "Alice")
        access.shade = listOf(mom)

        assertEquals(ConfirmTarget(notificationId(mom.key), "WhatsApp · Mom", group = true, sender = "Alice"), tool.confirmTarget("c1", """{"id":"${notificationId(mom.key)}"}"""))
    }

    @Test
    fun aMessagingGroupIsReadAsAGroupWithItsLastSender() {
        val posted = PostedNotification(
            key = "k", packageName = "com.whatsapp", appLabel = "WhatsApp", postTime = now, title = "Mom", text = null,
            messages = listOf(NotificationMessage("Bob", "hi"), NotificationMessage("Alice", "dinner at 8")), conversationTitle = "Mom", group = true,
        )

        val entry = NotificationReader.entryOf(posted)!!

        assertTrue(entry.group)
        assertEquals("Alice", entry.lastSender)
        assertEquals("Mom", entry.title)
    }

    /** A card's notification text goes with the buffer: once it is cleared, a call confirmed before is refused. */
    @Test
    fun clearingTheBufferForgetsWhatTheCardsShowed() = runBlocking {
        val buffer = NotificationBuffer(clock = { now })
        val cleared = ReplyNotificationTool(access, buffer)
        val args = """{"id":"$id","text":"On my way"}"""
        cleared.confirmTarget("c1", args)

        buffer.clear()
        val result = withContext(ToolCallContext("c1")) { cleared.execute(args) }

        assertEquals(ReplyNotificationTool.ChangedText, result.text)
        assertTrue(access.sent.isEmpty())
    }
}
