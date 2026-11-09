package io.github.tensaiapp.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.tensai.llamakt.ThreadPolicy
import io.github.tensaiapp.BuildConfig
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.CpuLoadOverride
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.QueueBusyException
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The benchmark of Settings > This device, in every build (TEN-208): [runDeviceBench] on the inference queue, then a
 * [DeviceReport] to copy. On the application scope, so leaving the screen does not stop it; one bench at a time
 * ([BenchGate]). As the debug decode curve does, the open conversation's session is saved before the run and before
 * each size. Nothing is stored or sent: the report only lives in [state] until the next run.
 */
object DeviceBench {
    private const val TAG = "tensai.bench"

    /** Where the benchmark is. */
    sealed interface State {
        data object Idle : State

        /** [progress] `null` until the sizes are known. */
        data class Running(val progress: DeviceBenchProgress?) : State

        data class Finished(val report: DeviceReport) : State

        data class NotRun(val outcome: DeviceBenchOutcome) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    private val sampling = SamplingConfig(
        temperature = 0f,
        repeatPenalty = 1f,
        maxTokens = DeviceBenchPlan.EvalTokens,
        enableThinking = false,
        constrainToolCalls = false,
    )

    /** Starts a run. `false` when another bench holds the gate. */
    fun start(context: Context): Boolean {
        if (!BenchGate.tryEnter()) return false
        val app = context.applicationContext
        stopRequested = false
        _state.value = State.Running(progress = null)
        job = RuntimeGraph.applicationScope.launch {
            try {
                val outcome = runDeviceBench(QueueEngine, { stopRequested }, nonce = ::nonce, onProgress = {
                    _state.value = State.Running(it)
                })
                _state.value = when (outcome) {
                    is DeviceBenchOutcome.Done -> when {
                        outcome.points.isNotEmpty() -> State.Finished(report(app, outcome))
                        outcome.stopped -> State.Idle
                        else -> State.NotRun(outcome)
                    }
                    else -> {
                        Log.e(TAG, benchErrorLine("device bench not run: $outcome"))
                        State.NotRun(outcome)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, benchErrorLine("device bench threw ${e::class.simpleName}"))
                _state.value = State.NotRun(DeviceBenchOutcome.Failed(e::class.simpleName ?: "error"))
            } finally {
                job = null
                BenchGate.leave()
            }
        }
        return true
    }

    /** Stops the run, cutting the size being measured short: the sizes before it make the report. */
    fun stop() {
        stopRequested = true
        decodeJob?.cancel()
    }

    /** The decode going on, cancelled by [stop]: cancelling the collection stops the generation. */
    @Volatile
    private var decodeJob: Job? = null

    /** Back to the start screen, once a run has ended. */
    fun dismiss() {
        if (job == null) _state.value = State.Idle
    }

    private fun report(context: Context, done: DeviceBenchOutcome.Done): DeviceReport {
        val profile = runCatching { RuntimeGraph.deviceProfiler.profile() }.getOrNull()
        // The sticky battery status, not BatteryManager.isCharging: that one stays false on a slow USB charge.
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return DeviceReport(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            phone = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            soc = profile?.socModel,
            ramBytes = profile?.totalRamBytes ?: 0,
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = done.model,
            points = done.points,
            charging = if (batteryStatus < 0) null else batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
            thermal = power?.currentThermalStatus?.let(DeviceReport::thermalName),
            stopped = done.stopped,
        )
    }

    /** The inference queue as [runDeviceBench] sees it. */
    private object QueueEngine : DeviceBenchEngine {
        private val queue get() = RuntimeGraph.inferenceQueue
        private var loaded: LoadedModelInfo? = null

        /** [io.github.tensaiapp.runtime.InferenceQueue.generationsStarted] right after the last decode. */
        private var lastGeneration = -1L

        override suspend fun model(): DeviceBenchModel? {
            val info = queue.state.value.residentModel ?: return null
            loaded = info
            val entity = RuntimeGraph.modelRepository.models.first().firstOrNull { it.id == info.modelId }
            return DeviceBenchModel(
                name = entity?.shownName ?: info.name,
                quant = entity?.quantLabel?.takeIf { it.isNotBlank() },
                backend = backendName(info.backend),
                threads = CpuLoadOverride.applied?.decodeThreads
                    ?: ThreadPolicy.resolve(ThreadPolicy.readCoreSpeeds()).decode.takeIf { it > 0 },
                nCtx = info.nCtxEffective,
            )
        }

        override fun idle(): Boolean = queue.state.value is QueueState.ModelReady && !queue.isWarmingUp()

        override fun stillLoaded(model: DeviceBenchModel): Boolean = queue.state.value.residentModel == loaded

        override suspend fun handOver(): Boolean = withContext(NonCancellable) {
            val owner = ActiveSessionTracker.current.value
            handOverCache(
                owner?.let { it.conversationId to it.key },
                save = { id, key ->
                    RuntimeGraph.sessionRepository.save(
                        id,
                        key,
                        ownsCache = { ActiveSessionTracker.current.value == owner },
                        saveOnEngine = queue::saveSession,
                    )
                },
                forget = ActiveSessionTracker::clearIf,
            )
        }

        override suspend fun paragraphTokens(): Int? = queue.countTokens(listOf(DecodeCurve.Paragraph))?.firstOrNull()

        override suspend fun decode(text: String, maxTokens: Int): DeviceBenchDecode {
            var stats: DecodeStats? = null
            var error: Throwable? = null
            val request = DecodeRequest(listOf(ChatMessage(MessageRole.User, text)), sampling.copy(maxTokens = maxTokens))
            coroutineScope {
                val collecting = launch {
                    queue.generate(request).collect { event ->
                        when (event) {
                            is DecodeEvent.Done -> stats = event.stats
                            is DecodeEvent.Error -> error = event.cause
                            else -> Unit
                        }
                    }
                }
                decodeJob = collecting
                if (stopRequested) collecting.cancel()
                collecting.join()
                decodeJob = null
            }
            lastGeneration = queue.generationsStarted()
            // Cut short (a stop, the notification's Stop) or never started (the queue taken meanwhile): not a measure.
            val interrupted = stats?.stopReason == StopReason.Cancelled || error is QueueBusyException || (stats == null && error == null)
            return DeviceBenchDecode(stats, error?.let { it.message ?: it::class.simpleName }, interrupted)
        }

        override suspend fun releaseCache() {
            withContext(NonCancellable) { queue.releaseBenchCache(lastGeneration) }
        }
    }

    private fun nonce() = UUID.randomUUID().toString().take(8)
}
