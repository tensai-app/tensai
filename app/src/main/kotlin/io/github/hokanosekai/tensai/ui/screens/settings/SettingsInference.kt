package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.runtime.LoadPlan
import io.github.hokanosekai.tensai.runtime.LoadPolicy
import java.io.File

/**
 * The context window and KV-cache precision Settings > Inference shows — see
 * [SettingsInference.facts].
 */
sealed interface InferenceFacts {

    /** Nothing loaded and nothing loadable: the two rows have no honest value to show. */
    data object Unknown : InferenceFacts

    /**
     * What the resident model was actually loaded with, straight off
     * [LoadedModelInfo] — the engine's answer, not a prediction of it.
     */
    data class InUse(val modelName: String, val nCtx: Int, val kvCacheType: KvCacheType) : InferenceFacts

    /**
     * What [LoadPolicy] would request for [modelName] on this device. Shown when no model is
     * resident, so the screen still says something true instead of a dash — still labelled as a
     * plan rather than a fact, because a load can still fail for reasons no estimate sees. Since
     * TEN-77 it is at least a *stable* plan: [LoadPolicy] budgets against
     * [DeviceProfile.usableRamBytes], which doesn't drift, so this row no longer changes value
     * every time the screen is opened.
     */
    data class Planned(val modelName: String, val nCtx: Int, val kvCacheType: KvCacheType) : InferenceFacts
}

/**
 * Settings > Inference's two read-only rows.
 *
 * Neither value is a setting in V1: `n_ctx` and KV-cache precision are derived per load by
 * [LoadPolicy] from the model's own trained context and the device's usable RAM (see its KDoc —
 * the two are chosen *together*, trading precision for context rather than shrinking the context
 * first). Exposing either as a picker would mean letting the user request a load the device cannot
 * hold, so the screen reports them instead, and this is where that reporting is decided.
 *
 * Same "a row whose file is gone is not a model" rule as
 * [io.github.hokanosekai.tensai.ui.screens.HomePill.state], and the same injectable [fileExists]:
 * planning a load for a model that isn't on disk would put numbers on screen for something that
 * can never be loaded.
 */
object SettingsInference {

    /**
     * @param resident the model currently in memory, `null` if none — callers pass
     *   [io.github.hokanosekai.tensai.runtime.residentModel], so a model stays "in use" for the
     *   whole of a generation, not just between them.
     * @param models every imported model, most recently imported first — the first one whose file
     *   survives is the one [InferenceFacts.Planned] plans for, matching what Home offers to load.
     * @param profile the device as it is now, or `null` if it can't be read — without it there is
     *   no RAM figure to plan against, so an empty queue resolves to [InferenceFacts.Unknown]
     *   rather than to a plan computed from a made-up number.
     * @param fileExists existence check for a [ModelEntity.filePath] — injectable for tests.
     */
    fun facts(
        resident: LoadedModelInfo?,
        models: List<ModelEntity>,
        profile: DeviceProfile?,
        fileExists: (String) -> Boolean = { File(it).exists() },
    ): InferenceFacts {
        if (resident != null) {
            return InferenceFacts.InUse(
                modelName = resident.name,
                nCtx = resident.nCtxEffective,
                kvCacheType = resident.kvCacheType,
            )
        }
        if (profile == null) return InferenceFacts.Unknown
        val next = models.firstOrNull { fileExists(it.filePath) } ?: return InferenceFacts.Unknown
        // No feasible plan is "nothing loadable", which is exactly what Unknown means. Printing
        // LoadPlan.Infeasible's floor here would put a context on screen for a load this device
        // can't perform — the row would read as a promise (TEN-77).
        val plan = LoadPolicy.compute(next, profile) as? LoadPlan.Feasible ?: return InferenceFacts.Unknown
        return InferenceFacts.Planned(
            modelName = next.displayName,
            nCtx = plan.nCtx,
            kvCacheType = plan.kvCacheType,
        )
    }
}
