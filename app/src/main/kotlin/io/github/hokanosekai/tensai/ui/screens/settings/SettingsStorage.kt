package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.session.SessionEntity
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
 */
data class StorageFigures(
    val modelBytes: Long,
    val modelCount: Int,
    val sessionBytes: Long,
    val sessionCount: Int,
    val volumeTotalBytes: Long,
) {
    /**
     * Fraction of [volumeTotalBytes] the models occupy, in `0f..1f` — what the usage bar fills to.
     * `0f` when the volume size is unknown or nonsensical, so an unreadable `StatFs` renders an
     * empty bar rather than a full one.
     */
    val modelFraction: Float
        get() = if (volumeTotalBytes <= 0) 0f else (modelBytes.toDouble() / volumeTotalBytes).toFloat().coerceIn(0f, 1f)
}

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
 * [io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate.entry] and
 * [io.github.hokanosekai.tensai.ui.screens.HomePill.state] already apply to the model library, and
 * the [fileSize] parameter is the same injectable-file-access shape as their `fileExists` — a JVM
 * test can state every disk arrangement it wants without one.
 */
object SettingsStorage {

    /**
     * @param models every imported model, as persisted.
     * @param sessions every persisted session (see [io.github.hokanosekai.tensai.data.session.SessionDao.observeAll]).
     * @param volumeTotalBytes see [StorageFigures.volumeTotalBytes].
     * @param fileSize size of the file at a path in bytes, or `null` if there is no file there —
     *   injectable for tests. `null` and `0` are deliberately different answers: a missing file is
     *   not a model, an empty one is a (useless) model that really is taking up a directory entry,
     *   and conflating them would silently drop rows the user can still see on the Models screen.
     */
    fun figures(
        models: List<ModelEntity>,
        sessions: List<SessionEntity>,
        volumeTotalBytes: Long,
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
        )
    }
}
