package io.github.hokanosekai.tensai.data.session

import io.github.hokanosekai.tensai.engine.KvCacheType

/**
 * The model configuration a [SessionEntity] must match to be restorable — see
 * [SessionRepository.restore]'s mismatch check.
 *
 * Deliberately mirrors [io.github.hokanosekai.tensai.engine.LoadedModelInfo] plus the model's
 * stable [io.github.hokanosekai.tensai.data.models.ModelEntity.id] (which `LoadedModelInfo` itself
 * doesn't carry — only a display name) rather than reusing that type directly, so this module has
 * no dependency on `RuntimeGraph`/`InferenceQueue` wiring to resolve one.
 *
 * @param modelId the [io.github.hokanosekai.tensai.data.models.ModelEntity.id] currently loaded.
 * @param nCtx the context window currently in effect.
 * @param kvCacheType the KV-cache precision currently in effect.
 * @param backend the compute backend currently in effect — recorded for [SessionEntity.backend],
 *   never checked by [SessionRepository.restore] (see that field's KDoc for why).
 */
data class SessionModelKey(
    val modelId: String,
    val nCtx: Int,
    val kvCacheType: KvCacheType,
    val backend: String,
)
