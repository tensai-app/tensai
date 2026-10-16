package io.github.hokanosekai.tensai.agent

/**
 * What a tool needs before the model may call it (TEN-172). The user grants it by tier in Settings
 * (TEN-174), see [ToolRegistry].
 *
 * @param timeoutMs how long [AgentRuntime] lets one call of a tool of this tier run.
 */
enum class ToolTier(val timeoutMs: Long) {
    /** Reads or writes only inside the app and the device state it exposes, no permission. */
    Local(10_000),

    /** Opens another app through an intent, the user validates there. */
    PhoneAction(10_000),

    /** Needs a runtime Android permission, asked at first use. */
    AndroidPermission(10_000),

    /** Sends something off the phone. Slower, and the destination is shown to the user. */
    Network(30_000),
}

/**
 * What a [Tool] answers. The text goes back to the model as the `tool` message, so it should be
 * short: [AgentRuntime] truncates it to a token budget anyway.
 *
 * @param text the result (plain text or JSON) or, when [isError], what went wrong, written for the model.
 * @param destination for a [ToolTier.Network] tool, where the request went ("DuckDuckGo"), shown on the call.
 */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val destination: String? = null,
) {
    companion object {
        fun ok(text: String, destination: String? = null) = ToolResult(text, destination = destination)

        fun error(message: String, destination: String? = null) = ToolResult(message, isError = true, destination = destination)
    }
}

/** A capability the model can call (TEN-172). Registered in a [ToolRegistry]. */
interface Tool {
    /** Name the model calls it by: `[a-z0-9_]`, unique in a registry. */
    val name: String

    /** What it does and when to use it, as the prompt shows it to the model. */
    val description: String

    /** JSON Schema of the arguments, as a string. [AgentRuntime] only checks `required` against it. */
    val parametersSchema: String

    val tier: ToolTier

    /** The call sends something out or deletes something: [AgentRuntime] asks its [ConfirmationGate] first. */
    val sendsOrDeletes: Boolean get() = false

    /**
     * Runs the tool, on [kotlinx.coroutines.Dispatchers.IO]. [argsJson] is a JSON object that passed the
     * `required` check. May throw: the runtime turns an exception into an error result for the model.
     *
     * Must be cancellable: the runtime cancels it at the tier's timeout and on Stop, so blocking I/O has to be
     * interruptible (use `suspendCancellableCoroutine`, `runInterruptible`, a client with call timeouts). A
     * tool that ignores cancellation is abandoned at the timeout: the call is reported as a timeout and the
     * loop goes on, while the work finishes on its own thread and its result is discarded.
     */
    suspend fun execute(argsJson: String): ToolResult
}
