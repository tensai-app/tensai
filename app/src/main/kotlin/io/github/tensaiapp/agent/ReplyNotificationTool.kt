package io.github.tensaiapp.agent

import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Replies through a notification's own reply action (TEN-213), the inline "Reply" a messaging app puts in the shade.
 * The action is read from the notification showing now, never kept: its PendingIntent does not outlive the notification.
 */

/** One action of a notification, as far as choosing the reply goes. */
data class ReplyActionInfo(val semanticReply: Boolean, val freeFormInput: Boolean)

/**
 * The reply action among [actions], by index: the one the app marks as a reply (`SEMANTIC_ACTION_REPLY`) that takes
 * text, else the first that takes text, `null` when none does.
 */
internal fun pickReplyAction(actions: List<ReplyActionInfo>): Int? =
    actions.indexOfFirst { it.semanticReply && it.freeFormInput }.takeIf { it >= 0 }
        ?: actions.indexOfFirst { it.freeFormInput }.takeIf { it >= 0 }

/** One `RemoteInput` of the reply action: the key the app reads the text under, and whether it takes typed text. */
data class RemoteInputInfo(val resultKey: String, val freeForm: Boolean)

/** The results to hand the app, by result key: [text] under every input that takes typed text, the standard contract. */
internal fun replyResults(inputs: List<RemoteInputInfo>, text: String): Map<String, String> =
    inputs.filter { it.freeForm }.associate { it.resultKey to text }

/** How a reply went: only [Sent] reached the app. */
enum class ReplyOutcome {
    Sent,

    /** The notification is no longer showing (read, dismissed, replaced by the app). */
    Gone,

    /** It shows, but with no action that takes text, or one tensai cannot fill (an immutable PendingIntent). */
    NoAction,

    /** The app cancelled the reply's PendingIntent. */
    Cancelled,
}

/**
 * `reply_notification` (TEN-213): sends a text through the reply action of a notification `read_notifications` listed
 * with `can_reply: yes`, by its id. Always asks first, whatever the conversation vouched for: the card names the app and
 * the conversation (a group as a group) and shows the exact text. Behind [ToolTier.PersonalData].
 *
 * An app reuses one notification key for every conversation, so the key alone does not hold the recipient: what the
 * card showed is kept by call id ([confirmTarget]), and [execute] sends only while the notification still shows that
 * app and conversation. A newer message in it is fine; another conversation on the same key, or no card, sends nothing.
 * What a card showed holds a message's text: it goes with the [buffer] whenever that is cleared (tier off, access
 * revoked, Clear), so a denied card does not keep it.
 *
 * The result says what was handed to which app, never that it was delivered or read: the app gives no receipt.
 */
class ReplyNotificationTool(private val access: NotificationAccess, buffer: NotificationBuffer? = null) : Tool {
    override val name = "reply_notification"
    override val description =
        "Replies to a notification that read_notifications listed with can_reply: yes. The user sees the exact text and approves first."
    override val parametersSchema =
        """{"type":"object","properties":{"id":{"type":"string","description":"The id read_notifications gave."},""" +
            """"text":{"type":"string","description":"The reply, at most $MaxTextChars characters."}},"required":["id","text"]}"""
    override val tier = ToolTier.PersonalData
    override val sendsOrDeletes = true

    /** Its result names a conversation a third party titled. */
    override val returnsUntrustedContent = true

    /** What each card showed, by call id. Bounded: a denied call never comes to take its own. */
    private val shown = object : LinkedHashMap<String, NotificationEntry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NotificationEntry>) = size > MaxShown
    }

    init {
        buffer?.onClear(::forgetAll)
    }

    /** Drops what every card showed: a call confirmed before is then refused. */
    fun forgetAll() {
        synchronized(shown) { shown.clear() }
    }

    override fun requiresConfirmation(argsJson: String, trust: ConversationTrust) = true

    /**
     * The app and the conversation the reply goes to, "WhatsApp · Alice", for the card, `null` when it is not showing.
     * Kept for [callId]: [execute] sends only to that.
     */
    override suspend fun confirmTarget(callId: String, argsJson: String): ConfirmTarget? {
        val id = runCatching { JSONObject(argsJson).stringArg("id")?.trim() }.getOrNull() ?: return null
        val entry = showing(id) ?: return null
        synchronized(shown) { shown[callId] = entry }
        return ConfirmTarget(id, where(entry).take(MaxTargetChars), entry.group, entry.lastSender?.take(MaxTargetChars))
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val id = args.stringArg("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.error("id is required")
        // A string and nothing else: a list or a number would be sent as some text the card never showed.
        val text = (args.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.error("text must be a non-empty string")
        // Never cut: the user approved this exact text.
        if (text.length > MaxTextChars) return ToolResult.error("text is over $MaxTextChars characters, write a shorter reply")
        val confirmed = currentCoroutineContext()[ToolCallContext]?.callId?.let { synchronized(shown) { shown.remove(it) } }
        if (!access.enabled()) return ToolResult.refused(ReadNotificationsTool.AccessOffText)
        val entry = showing(id) ?: return ToolResult.refused(GoneText)
        if (confirmed == null || !sameConversation(confirmed, entry)) return ToolResult.refused(ChangedText)
        return when (access.reply(entry.key, text)) {
            ReplyOutcome.Sent -> ToolResult.ok(
                "Handed the reply to ${entry.appLabel} for ${quoted(entry.title)}: ${quoted(text)}. " +
                    "The app gives no receipt: it is not known whether it was delivered or read.",
            )
            ReplyOutcome.Gone -> ToolResult.refused(GoneText)
            ReplyOutcome.NoAction -> ToolResult.refused("${entry.appLabel} does not accept a reply from tensai on this notification. Nothing was sent.")
            ReplyOutcome.Cancelled -> ToolResult.refused("${entry.appLabel} no longer accepts this reply. Nothing was sent.")
        }
    }

    private suspend fun showing(id: String): NotificationEntry? = access.active().firstOrNull { notificationId(it.key) == id }

    /** The same app and conversation as the card showed; its newest message and time may have moved. */
    private fun sameConversation(a: NotificationEntry, b: NotificationEntry) =
        a.key == b.key && a.packageName == b.packageName && a.appLabel == b.appLabel && a.title == b.title && a.group == b.group

    private fun where(entry: NotificationEntry) = if (entry.title.isEmpty()) entry.appLabel else "${entry.appLabel} · ${entry.title}"

    companion object {
        const val MaxTextChars = 500
        const val GoneText = "that notification is no longer showing, so it cannot be replied to. Nothing was sent. Read the notifications again."
        const val ChangedText =
            "the conversation changed since the user approved the reply, so nothing was sent. Read the notifications again."
        private const val MaxTargetChars = 80
        private const val MaxShown = 32
    }
}
