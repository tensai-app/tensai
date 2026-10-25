package io.github.tensaiapp.agent

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A question waiting for the user's answer, see [AskUserGate.pending]. */
class PendingQuestion internal constructor(
    val callId: String,
    val question: String,
    val options: List<String>,
    val multiple: Boolean,
    internal val reply: CompletableDeferred<List<String>?>,
)

/**
 * Where `ask_user` waits (TEN-187), the sibling of [UiConfirmationGate]: [ask] publishes the question in
 * [pending], the chat swaps its input bar for the question card, and the call suspends until [answer] (the
 * card), [dismiss] (the X, or the user left the chat). There is no timeout, the user may take their time.
 *
 * Stop cancels the turn, which cancels the suspended [ask] and drops the question. [pending] is keyed by call
 * id, which the runtime makes unique, so turns of different conversations never answer each other's.
 */
class AskUserGate {
    private val _pending = MutableStateFlow<Map<String, PendingQuestion>>(emptyMap())

    /** The questions the user is asked, by call id. */
    val pending: StateFlow<Map<String, PendingQuestion>> = _pending.asStateFlow()

    /** The chosen options, then the typed text if any, `null` when the question was dismissed. */
    suspend fun ask(callId: String, question: String, options: List<String>, multiple: Boolean): List<String>? {
        val request = PendingQuestion(callId, question, options, multiple, CompletableDeferred())
        var replaced: PendingQuestion? = null
        _pending.update { current ->
            replaced = current[callId]
            current + (callId to request)
        }
        replaced?.reply?.complete(null)
        try {
            return request.reply.await()
        } finally {
            _pending.update { current -> if (current[callId] === request) current - callId else current }
        }
    }

    /** The user's answer for [callId]: ignored when that question is not pending or [answers] is empty. */
    fun answer(callId: String, answers: List<String>) {
        if (answers.isEmpty()) return
        _pending.value[callId]?.reply?.complete(answers)
    }

    fun dismiss(callId: String) {
        _pending.value[callId]?.reply?.complete(null)
    }

    /** Dismisses the pending questions among [callIds]: nobody is left to answer them. */
    fun dismiss(callIds: Collection<String>) {
        callIds.forEach(::dismiss)
    }
}

/**
 * Asks the user to pick among options (TEN-187). The app always offers a free text answer on top, which is not
 * declared. Not a confirmation: nothing is sent or deleted.
 *
 * The result the model reads: `{"answer": "Jade Martin"}` for a single choice or a typed answer,
 * `{"answers": ["Terraform", "Ansible"]}` when [MULTIPLE] (typed text is one more answer), `{"dismissed": true}`
 * when the user closed the question. [waitsForUser]: the runtime's timeout does not apply.
 */
class AskUserTool(private val gate: AskUserGate) : Tool {
    override val name = NAME
    override val description = "Asks the user to pick among options when the request is ambiguous."
    override val parametersSchema =
        """{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}},"multiple":{"type":"boolean"}},"required":["question","options"]}"""
    override val tier = ToolTier.Local
    override val waitsForUser = true

    /** What [parse] reads out of the arguments. */
    class Args(val question: String, val options: List<String>, val multiple: Boolean)

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            parse(argsJson)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "invalid arguments")
        }
        val callId = coroutineContext[ToolCallContext]?.callId ?: return ToolResult.error("no chat to ask in")
        return ToolResult.ok(result(gate.ask(callId, args.question, args.options, args.multiple), args.multiple))
    }

    companion object {
        const val NAME = "ask_user"
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 6

        /**
         * Caps that keep a result inside the runtime's budget ([AgentRuntime.ResultTokenBudget], 4 chars a token): the worst
         * result (6 options of [MAX_OPTION_CHARS] and a typed answer of [MAX_ANSWER_CHARS], every char escaped) is under
         * 1.7k chars, so it is never cut and [AskUserResult] can always read it back. The card enforces [MAX_ANSWER_CHARS].
         */
        const val MAX_QUESTION_CHARS = 200
        const val MAX_OPTION_CHARS = 80
        const val MAX_ANSWER_CHARS = 300

        /** The question as the card, the line and the answer bubble show it: trimmed, cut at [MAX_QUESTION_CHARS]. */
        fun capQuestion(raw: String): String = raw.trim().capped(MAX_QUESTION_CHARS)

        private fun String.capped(max: Int): String {
            if (length <= max) return this
            val cut = take(max - 1).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
            return cut.trimEnd() + "\u2026"
        }

        /**
         * Options are trimmed, blanks and duplicates dropped, then [MIN_OPTIONS] to [MAX_OPTIONS] must be left.
         * @throws IllegalArgumentException with what to tell the model.
         */
        internal fun parse(argsJson: String): Args {
            val json = try {
                JSONObject(argsJson)
            } catch (e: JSONException) {
                throw IllegalArgumentException("arguments are not a JSON object")
            }
            val question = capQuestion(json.stringArg("question").orEmpty())
            require(question.isNotEmpty()) { "question is empty" }
            val array = json.optJSONArray("options") ?: throw IllegalArgumentException("options must be an array of strings")
            val options = (0 until array.length()).mapNotNull { array.opt(it)?.takeUnless { v -> v === JSONObject.NULL }?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .map { it.capped(MAX_OPTION_CHARS) }
                .distinct()
            require(options.size >= MIN_OPTIONS) { "options needs at least $MIN_OPTIONS different non-empty choices" }
            require(options.size <= MAX_OPTIONS) { "options has ${options.size} choices, at most $MAX_OPTIONS" }
            return Args(question, options, json.stringArg("multiple")?.toBooleanStrictOrNull() ?: false)
        }

        /** The text the model gets for the user's [answers] (`null`: dismissed). */
        internal fun result(answers: List<String>?, multiple: Boolean): String = when {
            answers == null -> JSONObject().put("dismissed", true).toString()
            multiple -> JSONObject().put("answers", JSONArray(answers)).toString()
            else -> JSONObject().put("answer", answers.first()).toString()
        }
    }
}

/** Reads back what [AskUserTool] answered, for the chat to draw the question and its answer. */
object AskUserResult {
    /** The answers of a stored result, empty for a dismissed or unreadable one. */
    fun answers(output: String?): List<String> {
        val json = parse(output) ?: return emptyList()
        json.optJSONArray("answers")?.let { array ->
            return (0 until array.length()).map { array.get(it).toString() }
        }
        return if (json.has("answer")) listOf(json.get("answer").toString()) else emptyList()
    }

    fun isDismissed(output: String?): Boolean = parse(output)?.optBoolean("dismissed") == true

    private fun parse(output: String?): JSONObject? = try {
        output?.let(::JSONObject)
    } catch (_: JSONException) {
        null
    }
}
