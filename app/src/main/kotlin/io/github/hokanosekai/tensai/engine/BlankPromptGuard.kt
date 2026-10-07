package io.github.hokanosekai.tensai.engine

/**
 * TEN-113. `nativeFormatChat` never aborts the process, but when both the jinja render and the
 * legacy fallback fail it answers `""`. Tokenizing that and decoding from an empty context yields an
 * incoherent reply with no error at all. A blank prompt for a non-empty conversation is a failed
 * render, not a prompt, so [LlamaKtEngineImpl.decode] reports it as one instead of decoding.
 *
 * Its own object so the check is testable off-device (the impl cannot be constructed on the JVM).
 */
internal object BlankPromptGuard {

    /** The error to emit, or `null` when [prompt] is usable (or there was nothing to render). */
    fun check(messageCount: Int, prompt: String): InferenceException? =
        if (messageCount > 0 && prompt.isBlank()) {
            InferenceException("Chat template rendering failed: the formatted prompt is empty")
        } else {
            null
        }
}
