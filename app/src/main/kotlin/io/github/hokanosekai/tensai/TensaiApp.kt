package io.github.hokanosekai.tensai

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.hokanosekai.tensai.runtime.InferenceService
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.launch

/**
 * Starts the process-wide observer that keeps [InferenceService] running
 * for exactly as long as [RuntimeGraph.inferenceQueue] holds a model.
 *
 * This side only ever needs to *start* the service: it stops itself once it
 * observes [QueueState.Idle] (see [InferenceService]'s own `onCreate`
 * observer), and a stopped service cannot restart itself from the inside.
 * The observer lives on [RuntimeGraph.applicationScope] rather than any
 * `Activity`'s lifecycle scope, since the service must keep running across
 * activity recreation and even while no activity exists at all.
 */
class TensaiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RuntimeGraph.applicationScope.launch {
            RuntimeGraph.inferenceQueue.state.collect { state ->
                if (state !is QueueState.Idle) {
                    ContextCompat.startForegroundService(
                        this@TensaiApp,
                        Intent(this@TensaiApp, InferenceService::class.java),
                    )
                }
            }
        }
    }
}
