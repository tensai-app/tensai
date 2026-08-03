package io.github.hokanosekai.tensai.ui.screens.loading

import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.DeviceProfiler
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.engine.OutOfMemoryException
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.LoadPolicy
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI-facing state for [LoadModelViewModel] — see its KDoc for the flow this drives. */
sealed class LoadUiState {
    /** Reading the model's metadata and running the compatibility check — expected to be brief. */
    data object Preparing : LoadUiState()

    /** [LoadModelViewModel]'s `modelId` didn't resolve to a [ModelEntity] (e.g. deleted meanwhile). */
    data object ModelNotFound : LoadUiState()

    /**
     * [compatibility] is [Verdict.WontFit] and the user hasn't confirmed "Load anyway" yet — the
     * load is held here instead of being attempted. See [LoadModelViewModel.loadAnyway].
     */
    data class Blocked(val model: ModelEntity, val compatibility: Compatibility) : LoadUiState()

    /**
     * An [InferenceQueue.loadModel] call is in flight. [progress] mirrors
     * [io.github.hokanosekai.tensai.runtime.QueueState.Loading].
     */
    data class InProgress(val model: ModelEntity, val progress: Float) : LoadUiState()

    /** Load succeeded — the screen reacts by navigating to chat. */
    data object Success : LoadUiState()

    /** Load failed with [OutOfMemoryException] — see the "Not enough memory" dialog. */
    data class OomFailed(val model: ModelEntity) : LoadUiState()

    /** Load failed with anything else. */
    data class GenericFailed(val model: ModelEntity, val message: String) : LoadUiState()
}

/**
 * Orchestrates one [ModelEntity]'s load for the `loading/{modelId}` route (TEN-30): reads the
 * model, runs the compatibility check, derives an adaptive [LoadPolicy] plan, and drives
 * [InferenceQueue.loadModel] — see [LoadUiState] for every state this can be in.
 *
 * Every attempt runs on [scope] (defaulting to [RuntimeGraph.applicationScope]), **not** a
 * Compose-scoped coroutine: the ticket's "Continue in background" button — and the system back
 * gesture, treated the same way, see `LoadingScreen` — navigates away from this screen while the
 * load keeps running, the same way generation already outlives the Chat screen via the shared
 * [InferenceQueue]. Scoping this to `rememberCoroutineScope()` (or an `androidx.lifecycle.ViewModel`'s
 * `viewModelScope`) would cancel the in-flight [InferenceQueue.loadModel] call the instant the
 * screen is left, defeating that feature. This class is deliberately not an
 * `androidx.lifecycle.ViewModel` — the project has no such dependency yet (see `RuntimeGraph`'s
 * "framework-free composition root" KDoc) — it's a plain state holder, `remember`-ed per `modelId`
 * by `LoadingScreen`.
 */
class LoadModelViewModel(
    private val modelId: String,
    private val modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    private val deviceProfiler: DeviceProfiler = RuntimeGraph.deviceProfiler,
    private val queue: InferenceQueue = RuntimeGraph.inferenceQueue,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    private val _state = MutableStateFlow<LoadUiState>(LoadUiState.Preparing)
    val state: StateFlow<LoadUiState> = _state.asStateFlow()

    /** Manual backend retry from a failure dialog — see [retryOnCpu]. `null` = use the checker's recommendation. */
    private var backendOverride: BackendHint? = null

    /** The in-flight attempt, if any — guards [start] against being launched twice concurrently. */
    private var job: Job? = null

    /**
     * Starts (or retries) the load. A no-op while a previous attempt from this same instance is
     * still active.
     *
     * @param confirmedOverride bypasses the [LoadUiState.Blocked] soft-fail — set by
     *   [loadAnyway]/[retryOnCpu]/[retry], never by the initial call from `LoadingScreen`.
     */
    fun start(confirmedOverride: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = LoadUiState.Preparing
            val model = modelRepository.models.first().find { it.id == modelId }
            if (model == null) {
                _state.value = LoadUiState.ModelNotFound
                return@launch
            }

            val profile = deviceProfiler.profile()
            val compatibility = CompatibilityChecker.check(model, profile)
            if (compatibility.verdict == Verdict.WontFit && !confirmedOverride) {
                _state.value = LoadUiState.Blocked(model, compatibility)
                return@launch
            }

            val plan = LoadPolicy.compute(model, profile)
            val config = ModelLoadConfig(
                path = model.filePath,
                nCtx = plan.nCtx,
                backend = backendOverride ?: compatibility.recommendedBackend,
                kvCacheType = plan.kvCacheType,
                onProgress = { progress -> _state.value = LoadUiState.InProgress(model, progress) },
            )
            _state.value = LoadUiState.InProgress(model, 0f)

            try {
                queue.loadModel(config)
                _state.value = LoadUiState.Success
            } catch (_: OutOfMemoryException) {
                _state.value = LoadUiState.OomFailed(model)
            } catch (e: Exception) {
                _state.value = LoadUiState.GenericFailed(model, e.message ?: "Load failed")
            }
        }
    }

    /** "Load anyway" from [LoadUiState.Blocked] — retries bypassing the WontFit soft-block. */
    fun loadAnyway() = start(confirmedOverride = true)

    /** "Retry on CPU" from a failure dialog — forces [BackendHint.Cpu] and retries. */
    fun retryOnCpu() {
        backendOverride = BackendHint.Cpu
        start(confirmedOverride = true)
    }

    /** Generic retry from a failure dialog, same backend as the last attempt. */
    fun retry() = start(confirmedOverride = true)
}
