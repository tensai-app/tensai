package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.session.SessionEntity
import io.github.hokanosekai.tensai.ui.screens.models.StoragePressure
import io.github.hokanosekai.tensai.ui.screens.models.StoragePressureState
import java.io.File

/**
 * What Settings > Storage reports — see [SettingsStorage.figures].
 *
 * @param modelBytes total on-disk size of every imported model whose file is actually there.
 * @param modelCount how many of those there are.
 * @param sessionBytes same, for persisted KV-cache sessions.
 * @param sessionCount how many of those there are.
 * @param volumeTotalBytes size of the volume the models live on, as reported by
 *   [io.github.hokanosekai.tensai.data.models.ModelRepository.storageUsage] — the denominator of
 *   the usage bar, `0` when it can't be read.
 * @param freeBytes what the same volume has left, from the same reading as
 *   [io.github.hokanosekai.tensai.data.models.StorageUsage.freeBytes] (so Models and downloads agree).
 * @param lowSpace whether [freeBytes] is under the [StoragePressure] threshold, the Models banner's own.
 */
data class StorageFigures(
    val modelBytes: Long,
    val modelCount: Int,
    val sessionBytes: Long,
    val sessionCount: Int,
    val volumeTotalBytes: Long,
    val freeBytes: Long,
    val lowSpace: Boolean,
) {
    /** The stacked bar's segments, see [SettingsStorage.shares]. */
    val shares: StorageShares
        get() = SettingsStorage.shares(modelBytes, freeBytes, volumeTotalBytes, lowSpace)
}

/**
 * Fractions of the volume, each in `0f..1f`, summing to at most `1f`.
 *
 * @param models the app's models (primary).
 * @param other everything else on the volume: the rest once models and free are taken out (neutral).
 * @param free what is left (the empty part of the track, warning-tinted when [lowFree]).
 */
data class StorageShares(val models: Float, val other: Float, val free: Float, val lowFree: Boolean)

/**
 * Settings > Storage's numbers, as a function of the library, the session table and the disk.
 *
 * The disk is what makes this worth having as a pure function. Both tables record a `sizeBytes`
 * written at import/save time, and both can outlive the file they describe — storage wiped from
 * the system settings, an app-data restore that brings rows back without files, a state file
 * removed under the app. Summing the recorded column would then bill the user for bytes that are
 * not on their phone, which is the one thing a storage screen must not do. So sizes are re-read
 * from disk, and a row whose file is gone counts for nothing at all: not in the bytes, not in the
 * count.
 *
 * That "a row whose file is gone is not a thing" rule is the same one
 * [io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate.entry] already applies to the
 * model library, and the [fileSize] parameter is the same injectable-file-access shape as its `fileExists`, a JVM
 * test can state every disk arrangement it wants without one.
 */
object SettingsStorage {

    /**
     * @param models every imported model, as persisted.
     * @param sessions every persisted session (see [io.github.hokanosekai.tensai.data.session.SessionDao.observeAll]).
     * @param volumeTotalBytes see [StorageFigures.volumeTotalBytes].
     * @param freeBytes see [StorageFigures.freeBytes].
     * @param fileSize size of the file at a path in bytes, or `null` if there is no file there —
     *   injectable for tests. `null` and `0` are deliberately different answers: a missing file is
     *   not a model, an empty one is a (useless) model that really is taking up a directory entry,
     *   and conflating them would silently drop rows the user can still see on the Models screen.
     */
    fun figures(
        models: List<ModelEntity>,
        sessions: List<SessionEntity>,
        volumeTotalBytes: Long,
        freeBytes: Long,
        fileSize: (String) -> Long? = { File(it).takeIf(File::isFile)?.length() },
    ): StorageFigures {
        val modelSizes = models.mapNotNull { fileSize(it.filePath) }
        val sessionSizes = sessions.mapNotNull { fileSize(it.filePath) }
        return StorageFigures(
            modelBytes = modelSizes.sum(),
            modelCount = modelSizes.size,
            sessionBytes = sessionSizes.sum(),
            sessionCount = sessionSizes.size,
            volumeTotalBytes = volumeTotalBytes,
            freeBytes = freeBytes,
            lowSpace = volumeTotalBytes > 0 &&
                StoragePressure.state(models, freeBytes, fileSize) is StoragePressureState.Low,
        )
    }

    /**
     * Splits the volume into models / other / free. An unknown total (`<= 0`) gives all zeroes, an
     * empty bar rather than a made-up one; negative inputs count as `0`, and models then free are
     * clamped to what the volume can hold, so the three never overflow the track.
     */
    fun shares(modelBytes: Long, freeBytes: Long, totalBytes: Long, lowFree: Boolean): StorageShares {
        if (totalBytes <= 0) return StorageShares(0f, 0f, 0f, lowFree = false)
        val models = modelBytes.coerceIn(0, totalBytes)
        val free = freeBytes.coerceIn(0, totalBytes - models)
        val other = totalBytes - models - free
        fun share(bytes: Long) = (bytes.toDouble() / totalBytes).toFloat()
        return StorageShares(share(models), share(other), share(free), lowFree)
    }
}
