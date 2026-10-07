package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.LoadStage
import kotlin.math.roundToInt

/**
 * Turns what the engine really reports during a load (a [LoadStage] plus the weights' own 0..1
 * progress) into one overall fraction for the loading screen and the notification (TEN-123).
 *
 * Nothing here is a timer: the bar only moves when [LoadStage.Weights] reports progress, and
 * jumps to a stage's floor the moment the engine crosses into it. Inside [LoadStage.Context] and
 * [LoadStage.Warmup] the engine has no progress to give, so the value is flat there and the screen
 * shows an indeterminate cue instead of inventing motion.
 *
 * [LoadStage.Preparing] is 0%: a model swap saves and releases the outgoing model first, which
 * is real work with no measure.
 *
 * [LoadStage.Preparing] is 0%: a model swap saves and releases the outgoing model first, which
 * is real work with no measure.
 *
 * The split is reasoned from the load path (`common_init_from_params`), not measured on a device:
 * - Weights 0..80%: reading and mapping the file, the only step whose cost scales with the model
 *   (seconds to tens of seconds for a multi-GB GGUF).
 * - Context 80..92%: KV cache allocation and the compute-graph reservation; scales with n_ctx and
 *   the KV type, and on GPU includes creating the compute pipelines.
 * - Warmup 92..100%: one decode of BOS/EOS; also where mmap'd weights are first touched on CPU.
 * A device measurement can change the three constants without touching anything else.
 */
object LoadProgressMap {
    const val WEIGHTS_SHARE = 0.80f
    const val CONTEXT_START = 0.80f
    const val WARMUP_START = 0.92f

    /** Overall load fraction in 0..1. [weightsProgress] is only read while in [LoadStage.Weights]. */
    fun overall(stage: LoadStage, weightsProgress: Float): Float = when (stage) {
        LoadStage.Preparing -> 0f
        LoadStage.Weights -> weightsProgress.coerceIn(0f, 1f) * WEIGHTS_SHARE
        LoadStage.Context -> CONTEXT_START
        LoadStage.Warmup -> WARMUP_START
        LoadStage.Ready -> 1f
    }

    /** [overall] as the whole percent shown to the user. */
    fun percent(stage: LoadStage, weightsProgress: Float): Int =
        (overall(stage, weightsProgress) * 100f).roundToInt()

    /**
     * True while the engine has no inner progress to report, so the bar shows an indeterminate
     * cue. Includes [LoadStage.Weights] before its first progress value: the file is being opened
     * and nothing is honestly "0% read" yet.
     */
    fun isIndeterminate(stage: LoadStage, weightsProgress: Float = 1f): Boolean = when (stage) {
        LoadStage.Preparing, LoadStage.Context, LoadStage.Warmup -> true
        LoadStage.Weights -> weightsProgress <= 0f
        LoadStage.Ready -> false
    }

    /**
     * The backend name for the "Warming up ..." label. Predicted from the same inputs the engine
     * resolves its offload with (hint, then what the device exposes), since the engine only names
     * the backend it ended up on once the load is over.
     */
    fun backendLabel(hint: BackendHint, hasVulkan: Boolean, hasOpenCl: Boolean): String = when {
        hint == BackendHint.Cpu -> "CPU"
        hasVulkan -> "Vulkan"
        hasOpenCl -> "OpenCL"
        else -> "CPU"
    }
}
