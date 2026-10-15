package io.github.hokanosekai.tensai.ui.screens.loading

import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.ContextPrefs
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadStage
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.engine.OutOfMemoryException
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.LoadPlan
import io.github.hokanosekai.tensai.runtime.LoadPolicy
import io.github.hokanosekai.tensai.runtime.LoadProgressMap
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI-facing state for [LoadModelViewModel] — see its KDoc for the flow this drives. */
sealed class LoadUiState {
    /** Reading the model's metadata and running the compatibility check — expected to be brief. */
    data object Preparing : LoadUiState()

    /** [LoadModelViewModel]'s `modelId` didn't resolve to a [ModelEntity] (e.g. deleted meanwhile). */
    data object ModelNotFound : LoadUiState()

    /**
     * [model]'s row resolved, but its [ModelEntity.filePath] no longer exists on disk — e.g. the
     * app's storage was wiped outside the app, leaving an orphaned DB row. Distinct from
     * [ModelNotFound]: the model is still listed, it just can't be loaded until re-imported.
     */
    data class FileMissing(val model: ModelEntity) : LoadUiState()

    /**
     * [compatibility] is [Verdict.WontFit] and the user hasn't confirmed "Load anyway" yet — the
     * load is held here instead of being attempted. See [LoadModelViewModel.loadAnyway].
     */
    data class Blocked(val model: ModelEntity, val compatibility: Compatibility) : LoadUiState()

    /**
     * An [InferenceQueue.loadModel] call is in flight (TEN-123). [stage] and [weightsProgress]
     * mirror [io.github.hokanosekai.tensai.runtime.QueueState.Loading]; what the screen draws is
     * [progress]/[percent], the overall value [LoadProgressMap] derives from them. Also the state
     * shown for the short "Ready" hold after the engine finishes, before [Success].
     *
     * [backendLabel] names the backend for the warm-up line ("Vulkan", "OpenCL", "CPU").
     */
    data class InProgress(
        val model: ModelEntity,
        val stage: LoadStage = LoadStage.Preparing,
        val weightsProgress: Float = 0f,
        val backendLabel: String = "CPU",
    ) : LoadUiState() {
        val progress: Float get() = LoadProgressMap.overall(stage, weightsProgress)
        val percent: Int get() = LoadProgressMap.percent(stage, weightsProgress)
    }

    /** Load succeeded — the screen reacts by navigating to chat. */
    data object Success : LoadUiState()

    /** Load failed with [OutOfMemoryException] — see the "Not enough memory" dialog. */
    data class OomFailed(val model: ModelEntity) : LoadUiState()

    /**
     * Load failed with anything else, classified into a [LoadFailure] (TEN-39).
     *
     * Used to carry the exception's own `message`, which the dialog printed verbatim: the strings
     * reaching it come off the JNI boundary and read like `llama_model_load: error loading model:
     * check_tensor_dims: tensor 'token_embd.weight' has wrong shape`. Nothing a person can act on,
     * and precisely the "no technical jargon in an error state" this ticket rules out. The
     * throwable is not kept at all — see [LoadFailure] for why classifying beats carrying.
     */
    data class GenericFailed(
        val model: ModelEntity,
        val failure: LoadFailure,
        /**
         * Whether the failed attempt offloaded to a GPU, so that "Retry on CPU" would change
         * anything (TEN-100). Not just `backend != Cpu`: the engine runs [BackendHint.Auto] on CPU
         * when the device has no GPU backend, and [BackendHint.Gpu] silently falls back the same way.
         */
        val canRetryOnCpu: Boolean,
    ) : LoadUiState()
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
    private val deviceProfile: () -> DeviceProfile = { RuntimeGraph.deviceProfiler.profile() },
    private val backendPreference: suspend () -> BackendPreference = {
        RuntimeGraph.backendRepository.preference.first()
    },
    /**
     * The global context setting and Auto's memory share (TEN-150); the model's own override, if any,
     * wins over the setting in [LoadPolicy.compute].
     */
    private val contextPrefs: suspend () -> ContextPrefs = {
        RuntimeGraph.contextRepository.prefs.first()
    },
    private val queue: InferenceQueue = RuntimeGraph.inferenceQueue,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
    /**
     * How long "Ready · 100%" stays up once the engine is done, before [LoadUiState.Success]. A status
     * the user gets to read, not an animation, so it is not skipped under reduced motion.
     */
    private val readyHoldMillis: Long = READY_HOLD_MILLIS,
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
            if (!File(model.filePath).exists()) {
                _state.value = LoadUiState.FileMissing(model)
                return@launch
            }

            val profile = deviceProfile()
            val prefs = contextPrefs()
            val compatibility = CompatibilityChecker.check(model, profile, prefs.setting, prefs.autoShare)
            if (compatibility.verdict == Verdict.WontFit && !confirmedOverride) {
                _state.value = LoadUiState.Blocked(model, compatibility)
                return@launch
            }

            // A plan that can't be honoured is a block, not a quiet downgrade (TEN-77): the old
            // policy answered "256 tokens, good luck" and the screen went straight to a chat that
            // accepted a prompt and never replied.
            //
            // A backstop, and knowingly so. Since TEN-150 [CompatibilityChecker.check] is decided from this
            // very [LoadPolicy.compute] (same model, profile, setting and Auto share): WontFit is exactly
            // "the plan is Infeasible", so any model that reaches here un-overridden has a feasible plan and
            // this branch cannot fire. It stays as a guard against the two ever being computed apart again,
            // which is the bug TEN-77 fixed. `compatibility` is the right thing to show it with: if the plan
            // is infeasible, that Compatibility says WontFit.
            //
            // Never after "Load anyway" — a confirmation that lands back on the dialog it came
            // from is a dead button. That path loads at `floor` and lets the engine be the one to
            // refuse, which surfaces as the "Not enough memory" dialog rather than as silence.
            val plan = LoadPolicy.compute(model, profile, prefs.setting, prefs.autoShare)
            if (plan is LoadPlan.Infeasible && !confirmedOverride) {
                _state.value = LoadUiState.Blocked(model, compatibility)
                return@launch
            }
            val effective = when (plan) {
                is LoadPlan.Feasible -> plan
                is LoadPlan.Infeasible -> plan.floor
            }
            val config = ModelLoadConfig(
                path = model.filePath,
                // The one place the row's id and the file about to be loaded are both in hand, and
                // so the only place the two can be tied together (TEN-80). Everything downstream
                // that has to name the loaded model exactly — the session key above all — reads it
                // back off `LoadedModelInfo.modelId`; without it the only handle is a GGUF display
                // name, which two quantisations of one model share.
                modelId = model.id,
                nCtx = effective.nCtx,
                // The user's persisted choice wins over the checker's recommendation, and a
                // manual retry from a failure dialog wins over both — a "Retry on CPU" that
                // silently loaded on GPU again because a preference said so would be a dead
                // button. BackendPreference.Auto is exactly "no opinion, use the recommendation".
                backend = backendOverride ?: backendPreference().resolve(compatibility.recommendedBackend),
                kvCacheType = effective.kvCacheType,
                name = model.shownName,
            )
            val backendLabel = LoadProgressMap.backendLabel(config.backend, profile.hasVulkan, profile.hasOpenCl)
            // Already resident for this exact config: loadModel below is the dedup no-op, so there is
            // no load to show and no "Ready" to hold. Otherwise seed from the queue, so re-entering
            // the screen (rotation, back then forward) resumes at the real stage instead of 0%.
            val resident = queue.isResident(config)
            val inFlight = queue.state.value as? QueueState.Loading
            _state.value = if (inFlight != null) {
                LoadUiState.InProgress(model, inFlight.stage, inFlight.progress, backendLabel)
            } else {
                LoadUiState.InProgress(model, LoadStage.Preparing, 0f, backendLabel)
            }

            // Progress and stage come from queue.state, not config.onProgress/onStage: when this call is the dedup
            // no-op for a load another (now-gone) instance started, our own onProgress never fires
            // but the queue's progress still does. See InferenceQueue.loadModel (TEN-50).
            val progressJob = launch {
                queue.state.collect { queueState ->
                    if (queueState is QueueState.Loading) {
                        _state.value = LoadUiState.InProgress(
                            model,
                            queueState.stage,
                            queueState.progress,
                            backendLabel,
                        )
                    }
                }
            }
            try {
                // Out of memory is the one failure a smaller context can cure, and the estimate that picked
                // this one is an estimate: so first retry the same context at Q8_0 if it was F16, then step down one
                // ladder rung at a time at Q8_0, down to the floor, before the user sees a dialog. Safe in this path because a failed [InferenceQueue.loadModel]
                // leaves the queue as it was, which is what "Retry" and "Retry on CPU" already rely on.
                var attempt = config
                while (true) {
                    try {
                        queue.loadModel(attempt)
                        break
                    } catch (e: OutOfMemoryException) {
                        attempt = if (attempt.kvCacheType == KvCacheType.F16) {
                            // First the cheaper cache at the context the user asked for, then rungs.
                            attempt.copy(kvCacheType = KvCacheType.Q8_0)
                        } else {
                            attempt.copy(nCtx = LoadPolicy.stepDown(model, attempt.nCtx) ?: throw e)
                        }
                    }
                }
                // Stop mirroring the queue first, and wait for it: a late Loading value landing
                // after the Ready write below would drag the screen back a stage.
                progressJob.cancelAndJoin()
                if (!resident) {
                    _state.value = LoadUiState.InProgress(model, LoadStage.Ready, 1f, backendLabel)
                    delay(readyHoldMillis)
                }
                _state.value = LoadUiState.Success
            } catch (_: OutOfMemoryException) {
                _state.value = LoadUiState.OomFailed(model)
            } catch (e: CancellationException) {
                // Same guard `ModelsScreen`/`OnboardingScreen` put on their imports (TEN-53), here
                // for the same reason (TEN-73): a cancellation is not a load failure and must not
                // be posted as one, nor swallowed. [LoadFailure.of] re-throws it too — this states
                // the intent where the decision is, rather than leaving it to the classifier.
                throw e
            } catch (e: Exception) {
                _state.value = LoadUiState.GenericFailed(
                    model,
                    LoadFailure.of(e),
                    canRetryOnCpu = config.backend != BackendHint.Cpu && (profile.hasVulkan || profile.hasOpenCl),
                )
            } finally {
                progressJob.cancel()
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

    companion object {
        const val READY_HOLD_MILLIS = 500L
    }
}
