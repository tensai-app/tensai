package io.github.hokanosekai.tensai.data.models

import io.github.hokanosekai.tensai.engine.GgufInfo

/**
 * Re-derives the KV-cache shape of every [ModelEntity] whose stored
 * [ModelEntity.kvShapeVersion] is not the one the native library currently reports, by re-reading
 * that model's GGUF header. Runs once per app start, from [ModelRepository.backfillKvCacheShape].
 *
 * TEN-18 introduced the shape columns and backfilled them by looking for zeroes. That finds a row
 * that never had a shape; it cannot find a row whose shape is *wrong*. The formula was revised
 * once already — the first version charged the V half per layer, the corrected one charges it at
 * the global maximum, which is what llama.cpp allocates whenever the V cache is transposed — and a
 * model imported between the two carries a non-zero, entirely plausible-looking under-count that
 * the zero test skipped forever. An under-counted KV cache tells the app it can afford more
 * context than it can, which is the direction [KvCacheSize] exists to never go.
 *
 * So the row records which formula produced it, and this class re-reads whatever doesn't match.
 * Nothing here interprets the number.
 *
 * A stale row is re-read for its KV shape *and* its capabilities (`supportsThinking`, `supportsTools`,
 * `hasChatTemplate`, TEN-154, TEN-159). A migration that adds a column derived from the header can therefore reset
 * `kvShapeVersion` to 0 to get every row re-read once, as [TensaiDbMigrations.MIGRATION_12_13] and [TensaiDbMigrations.MIGRATION_14_15] do.
 *
 * **Why the version is not a constant in this file.** The derivation lives in `tensai_jni.cpp`, in
 * a pinned submodule; the C++ is what reads the header and applies the formula, and Kotlin only
 * copies the result out of a JSON field. A constant here would be a number somebody has to
 * remember to bump while editing C++ in another repository. Forgetting once is silent: the
 * backfill simply never fires again, which is the exact failure this class was written to fix. So
 * [currentShapeVersion] asks the library, and the library answers with the same number it stamps
 * into [GgufInfo.kvShapeVersion].
 *
 * A consequence worth stating: the app can be handed a version it has never heard of, because the
 * submodule moves independently of it. That is a normal outcome, not an error — the comparison is
 * equality, and anything unequal means "re-read".
 *
 * Split out of [ModelRepository] the same way [ModelImporter] was, and for the same reason: what
 * this class has to get right (relist exactly the stale rows, and leave nothing behind that would
 * make the next start redo the work) is pure logic, while [ModelRepository] holds a `Context` and
 * cannot be constructed on the JVM. Both effects it needs are parameters, so `KvCacheShapeBackfill`
 * runs under a fake DAO and an injected version with no device and no real GGUF.
 *
 * @param readMetadata reads a GGUF header without loading weights — header-only, milliseconds even
 *   on a multi-GB file, which is what makes a whole-library pass affordable at start.
 * @param currentShapeVersion the derivation revision the native library reports right now. A
 *   function, not a value: on device it crosses JNI, and nothing should pay for that when there is
 *   nothing to backfill.
 */
internal class KvCacheShapeBackfill(
    private val modelDao: ModelDao,
    private val readMetadata: suspend (path: String) -> GgufInfo,
    private val currentShapeVersion: () -> Long,
) {

    /**
     * One pass. Idempotent by construction: every row it touches comes back carrying the version
     * it just compared against, so a second call — the next app start — selects nothing.
     *
     * That mattering is not hypothetical. Changing a row's shape can change the `n_ctx` the load
     * policy picks, and
     * [io.github.hokanosekai.tensai.data.session.SessionRepository] drops every persisted session
     * of a model whose `nCtx` moved. A pass that re-fired on each start would be re-prefilling
     * conversations from scratch, tens of seconds each, forever. Note the other half of that
     * guarantee is in [ModelImporter], which stamps the version at import time — a freshly
     * imported model is already current and is never picked up here at all.
     *
     * Failures are swallowed per model rather than batched: one file that has been moved, deleted
     * or replaced by something unreadable leaves that row exactly as it was — on the previous
     * estimate, and still marked stale, so a later start retries it — while every other row is
     * still corrected.
     */
    suspend fun run() {
        val version = currentShapeVersion()

        modelDao.withStaleKvCacheShape(version).forEach { model ->
            // Only a *thrown* read is treated as "come back later". A read that succeeds and
            // reports no usable shape (`kvFullElementsPerToken == 0`, a header this formula can't
            // get an attention shape out of) is a real answer, and gets stamped like any other:
            // re-asking the same formula the same question every start would only produce the
            // same nothing. The row then falls to KvCacheSize's embeddingLength over-estimate,
            // which is the safe direction — and the identical state a fresh import of that file
            // would land in, rather than one that depends on what the row happened to hold before.
            val info = runCatching { readMetadata(model.filePath) }.getOrNull() ?: return@forEach

            modelDao.updateKvCacheShape(
                id = model.id,
                kvFullElementsPerToken = info.kvFullElementsPerToken,
                kvSwaElementsPerToken = info.kvSwaElementsPerToken,
                slidingWindow = info.slidingWindow,
                // What the read itself reported, not `version`. Same number in any single process,
                // but this is the one that actually produced the three figures above it.
                kvShapeVersion = info.kvShapeVersion,
                // The same re-read refreshes the capabilities (TEN-154), from the chat template.
                supportsThinking = ChatTemplateCapabilities.supportsThinking(info),
                supportsTools = ChatTemplateCapabilities.supportsTools(info),
                hasChatTemplate = ChatTemplateCapabilities.hasChatTemplate(info),
            )
        }
    }
}
