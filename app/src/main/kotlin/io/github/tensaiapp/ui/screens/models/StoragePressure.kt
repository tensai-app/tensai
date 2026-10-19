package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.data.models.ModelEntity
import java.io.File

/** Whether the Models screen should warn about disk space — see [StoragePressure.state]. */
sealed interface StoragePressureState {

    /** Enough room left for one more model of the size this user actually imports. */
    data object Ok : StoragePressureState

    /**
     * Less free space than an average model here takes. Both figures are what the banner prints,
     * so nothing downstream re-measures anything.
     *
     * @param freeBytes what the volume has left, never negative.
     * @param averageModelBytes mean on-disk size of the models that are really there.
     */
    data class Low(val freeBytes: Long, val averageModelBytes: Long) : StoragePressureState
}

/**
 * Is this phone about to run out of room for models?
 *
 * "Nearly full" has no absolute answer — 2 GB free is roomy for a 0.5B Q4 and hopeless for a 7B,
 * and the app has no business picking a number in gigabytes that would be wrong on both. So the
 * threshold is **relative to what this user actually imports**: the warning appears when the free
 * space is below the mean size of the models already on the device, i.e. when the next import that
 * looks like the last one would not fit. A phone with three 1 GB models and 1.2 GB free is fine; the
 * same phone with three 4 GB models and 1.2 GB free is not, and that is the distinction a fixed
 * threshold cannot make.
 *
 * Sizes come from disk, not from [ModelEntity.sizeBytes], and a row whose file is gone counts for
 * nothing — the same rule
 * [io.github.tensaiapp.ui.screens.settings.SettingsStorage.figures],
 * [io.github.tensaiapp.ui.screens.onboarding.OnboardingGate.entry]
 * already apply, for the same reason: a row is not a file. Billing the average against models that
 * are not on the phone would raise the threshold on a device that just got *emptier*, which is
 * backwards.
 */
object StoragePressure {

    /**
     * @param models every imported model, as persisted.
     * @param freeBytes free space on the volume models live on — from
     *   [io.github.tensaiapp.data.models.StorageUsage.freeBytes].
     * @param fileSize size of the file at a path, or `null` if there is no file there — injectable
     *   for tests, same shape as `SettingsStorage.figures`' own parameter.
     */
    fun state(
        models: List<ModelEntity>,
        freeBytes: Long,
        fileSize: (String) -> Long? = { File(it).takeIf(File::isFile)?.length() },
    ): StoragePressureState {
        // Zero-length files are dropped, not just missing ones. An empty file is a real directory
        // entry (SettingsStorage counts it, deliberately), but it is not a model, and letting one
        // into a *mean* would drag the threshold towards zero and quietly switch the warning off —
        // the opposite of what a storage warning is for.
        val sizes = models.mapNotNull { fileSize(it.filePath) }.filter { it > 0 }

        // Nothing imported (or nothing left on disk): there is no "size of a model" for this user
        // yet, so there is no threshold to be under. A blank install with a full phone is the
        // import flow's problem to report, not this banner's — InsufficientStorageException already
        // says it with the real number, at the moment it actually matters.
        if (sizes.isEmpty()) return StoragePressureState.Ok

        val averageModelBytes = sizes.sum() / sizes.size

        // Strictly below: exactly one model's worth of space left still fits one more model, so
        // that is not yet the moment to interrupt anyone.
        if (freeBytes >= averageModelBytes) return StoragePressureState.Ok

        return StoragePressureState.Low(
            // StatFs can report a negative figure on a volume over its reserve; the banner would
            // print "-0.2 GB left", which reads as a bug rather than as urgency.
            freeBytes = freeBytes.coerceAtLeast(0),
            averageModelBytes = averageModelBytes,
        )
    }
}
