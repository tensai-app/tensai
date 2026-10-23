package io.github.tensaiapp.engine

/**
 * Starts a blocking native decode, [call], so that a stop can never be lost on the way in.
 *
 * llama.kt's interrupt is sticky: it stays pending until `LlamaEngine.resetInterrupt`, and no
 * native call clears it on its own any more. The reset comes first, then [ensureActive], the
 * caller's last look at cancellation, then [call]. A cancel landing before the reset is caught by
 * [ensureActive], since every cancel here comes before the interrupt it triggers (`decode`'s
 * `awaitClose`, the warm-ups' [interruptUntilDone]). One landing after the reset sets a flag that
 * nothing clears before [call] reads it, however long the JNI entry, the prompt copy and the
 * tokenisation take.
 *
 * The order is the fix. The native side used to clear the flag itself in `rewind()`, inside [call]:
 * a stop landing between [ensureActive] and that point was erased, and the decode ran to EOS or
 * `nPredict` with the channel already closed and the engine thread held for nothing.
 */
internal inline fun <T> startInterruptible(resetInterrupt: () -> Unit, ensureActive: () -> Unit, call: () -> T): T {
    resetInterrupt()
    ensureActive()
    return call()
}
