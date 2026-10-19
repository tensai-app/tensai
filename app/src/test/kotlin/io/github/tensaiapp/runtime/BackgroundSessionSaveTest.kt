package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionModelKey
import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.engine.LoadedModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEN-78's third save trigger — see [saveSessionAfterBackgroundedGeneration].
 *
 * Everything here is a flow and two lambdas: the unit under test is deliberately free of
 * [InferenceQueue], [ActiveSessionTracker] and [AppForegroundTracker], so the transition it watches
 * for can be driven exactly.
 *
 * ### Why a rendezvous flow and not a [kotlinx.coroutines.flow.MutableStateFlow]
 *
 * The real wiring passes [InferenceQueue.state], which is conflated — `Generating` set and cleared
 * between two of the collector's turns is simply never seen, which is the best-effort caveat the
 * function's KDoc states. Driving these tests through a conflated flow would make every one of them
 * a race the test usually wins, and "usually" is not a test. A [MutableSharedFlow] with no buffer
 * makes each [Driver.emit] wait for the collector to take the value, so the sequence a test writes
 * is the sequence the collector sees.
 *
 * One consequence to hold on to, because getting it wrong silently breaks these tests rather than
 * failing them: an [Driver.emit] returning means the collector **took** that value, not that it
 * finished reacting to it. Taking the *next* one is what proves the previous reaction ran, since
 * the collector is a sequential loop. So a test that changes an input mid-sequence changes it after
 * the emission *following* the one it must not affect — see [markControl].
 *
 * ### Why the negative cases end with a save that *should* happen
 *
 * A test asserting "nothing was saved" against a collector on another dispatcher passes just as
 * well when the collector never started. Each one therefore drives the case under test, then drives
 * a transition that must save, under a distinct conversation id ([markControl]). Waiting for one
 * save then asserting it is the *control's* catches both ways of being wrong: a collector that
 * never ran times out, and a collector that saved when it should not have is holding the watched
 * id first.
 */
class BackgroundSessionSaveTest {

    private val key = SessionModelKey(modelId = "model-1", nCtx = 4096, kvCacheType = KvCacheType.F16, backend = "CPU")
    private val info = LoadedModelInfo(name = "m", nCtxEffective = 4096, backend = "CPU", kvCacheType = KvCacheType.F16)

    /**
     * The scenario the whole ticket is about: ask for a long answer, put the phone down. `onStop`
     * fires mid-decode, where the queue declines to dump (see [InferenceQueue.saveSession]), and
     * nothing else was ever going to fire — backgrounding swaps no model, so `onBeforeModelSwap`
     * stays silent too. The reply lands into a KV cache holding the whole conversation, and without
     * this the cache is dropped at the next unload and re-prefilled cold for 107 s (TEN-63's
     * measurement, cited in `SessionStoragePolicy`'s KDoc).
     */
    @Test
    fun aGenerationEndingWhileBackgroundedSavesTheActiveConversation() = runBlocking {
        val run = Driver(isForeground = { false })

        run {
            emit(QueueState.Generating(info))
            emit(QueueState.ModelReady(info))
            awaitSaves(1)
        }

        assertEquals(listOf("c1" to key), run.saved)
    }

    /**
     * The gate that keeps this trigger affordable. A session file runs to hundreds of MB
     * (~115 KiB/token on the measured model), so writing one at the end of every reply the user
     * sits and watches would be the dominant cost of using the app — and would buy nothing, since
     * the existing triggers already fire the moment they leave.
     */
    @Test
    fun aGenerationEndingInTheForegroundSavesNothing() = runBlocking {
        var foreground = true
        val run = Driver(isForeground = { foreground })

        run {
            emit(QueueState.Generating(info))
            emit(QueueState.ModelReady(info)) // watched: must not save.
            emit(QueueState.Generating(info)) // returning proves the watched reaction has run.
            foreground = false
            markControl()
            emit(QueueState.ModelReady(info)) // control: must save.
            awaitSaves(1)
        }

        assertEquals(listOf(CONTROL to key), run.saved)
    }

    /**
     * Ending on [QueueState.Idle] means the model went away, and [InferenceQueue.onBeforeModelSwap]
     * has already saved on its way past (TEN-34). Saving again would race that save for the same
     * conversation's temp file — the one interleaving `SessionRepository`'s commit lock explicitly
     * does not protect against (see its KDoc).
     */
    @Test
    fun aGenerationEndingBecauseTheModelWasUnloadedSavesNothing() = runBlocking {
        val run = Driver(isForeground = { false })

        run {
            emit(QueueState.Generating(info))
            emit(QueueState.Idle) // watched: the model was released, not a reply finishing.
            emit(QueueState.ModelReady(info))
            emit(QueueState.Generating(info))
            markControl()
            emit(QueueState.ModelReady(info)) // control.
            awaitSaves(1)
        }

        assertEquals(listOf(CONTROL to key), run.saved)
    }

    /** A load completing is not a generation ending. Only the one transition counts. */
    @Test
    fun aLoadCompletingSavesNothing() = runBlocking {
        val run = Driver(isForeground = { false })

        run {
            emit(QueueState.Loading(0.5f))
            emit(QueueState.ModelReady(info)) // watched.
            emit(QueueState.Generating(info))
            markControl()
            emit(QueueState.ModelReady(info)) // control.
            awaitSaves(1)
        }

        assertEquals(listOf(CONTROL to key), run.saved)
    }

    /**
     * [ActiveSessionTracker] is best-effort and can be empty — it is cleared on every model swap.
     * With no conversation to attribute the KV cache to there is nothing this could correctly
     * write, and a guess would file one conversation's state under another's id.
     */
    @Test
    fun aGenerationEndingWithNoActiveConversationSavesNothing() = runBlocking {
        val run = Driver(isForeground = { false })
        run.activeConversationId = null

        run {
            emit(QueueState.Generating(info))
            emit(QueueState.ModelReady(info)) // watched: nobody has reported.
            emit(QueueState.Generating(info))
            markControl()
            emit(QueueState.ModelReady(info)) // control.
            awaitSaves(1)
        }

        assertEquals(listOf(CONTROL to key), run.saved)
    }

    /**
     * Foreground is sampled when the reply *lands*, not when it started — which is the difference
     * between the scenario this exists for and every other one. The user was here when they asked.
     */
    @Test
    fun theForegroundCheckIsMadeWhenTheGenerationEndsNotWhenItStarted() = runBlocking {
        var foreground = true
        val run = Driver(isForeground = { foreground })

        run {
            emit(QueueState.Generating(info))
            foreground = false // they leave mid-reply.
            emit(QueueState.ModelReady(info))
            awaitSaves(1)
        }

        assertEquals(listOf("c1" to key), run.saved)
    }

    /** Not a one-shot: every backgrounded generation gets its state written. */
    @Test
    fun everyBackgroundedGenerationSaves() = runBlocking {
        val run = Driver(isForeground = { false })

        run {
            emit(QueueState.Generating(info))
            emit(QueueState.ModelReady(info))
            emit(QueueState.Generating(info))
            emit(QueueState.ModelReady(info))
            awaitSaves(2)
        }

        assertEquals(listOf("c1" to key, "c1" to key), run.saved)
    }

    /**
     * Drives [saveSessionAfterBackgroundedGeneration] on a scope of its own — the function collects
     * forever by design, so nothing may await it — and records what it asked to save.
     */
    private inner class Driver(private val isForeground: () -> Boolean) {
        val saved = mutableListOf<Pair<String, SessionModelKey>>()

        /** What [ActiveSessionTracker] would be reporting right now — `null` for "nobody has". */
        var activeConversationId: String? = "c1"

        private val states = MutableSharedFlow<QueueState>()

        /** Publishes [state] and returns once the collector has taken it — see the class KDoc. */
        suspend fun emit(state: QueueState) = states.emit(state)

        /**
         * Moves the active conversation to [CONTROL], so every save from here on is distinguishable
         * from one the case under test should never have made. Must only be called after an [emit]
         * that follows the transition being watched — see the class KDoc.
         */
        fun markControl() {
            activeConversationId = CONTROL
        }

        /** Waits for [count] saves rather than sleeping a guessed interval for them. */
        suspend fun awaitSaves(count: Int) = withTimeout(AWAIT_TIMEOUT_MS) {
            while (saved.size < count) delay(POLL_MS)
        }

        suspend operator fun invoke(body: suspend Driver.() -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                scope.launch {
                    saveSessionAfterBackgroundedGeneration(
                        queueState = states,
                        isForeground = isForeground,
                        activeSession = { activeConversationId?.let { it to key } },
                        save = { conversationId, sessionKey -> saved += conversationId to sessionKey },
                    )
                }
                // An emit into a MutableSharedFlow with no subscribers is dropped, not buffered, so
                // the first one has to wait for the collector to actually be there.
                withTimeout(AWAIT_TIMEOUT_MS) { states.subscriptionCount.first { it > 0 } }
                body()
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        /** The conversation a negative test's *control* transition saves — see [Driver.markControl]. */
        const val CONTROL = "control"

        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
    }
}
