package io.github.tensaiapp.bench

import android.content.Context
import android.content.Intent

/** The agent bench is debug-only (see the debug source set): a release ignores the launch extra and stores nothing. */
object AgentBench {
    @Suppress("UNUSED_PARAMETER")
    fun offer(intent: Intent?, context: Context) = Unit
}
