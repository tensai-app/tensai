package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelOrigin
import io.github.hokanosekai.tensai.hf.HfQuantFile

/** What "Download again" enqueues: the same repo, file and commit the model came from (TEN-143). */
internal data class DownloadAgainRequest(
    val repo: String,
    val quant: HfQuantFile,
    val displayName: String,
    val commitSha: String?,
)

/** Pure rules of the "Source" row and of "Download again" on the model detail screen. */
internal object ModelSource {

    /** `hf.co/<repo> · <quant>`, just `hf.co/<repo>` when the origin has no quant label. */
    fun label(origin: ModelOrigin): String =
        if (origin.quant.isBlank()) "hf.co/${origin.repo}" else "hf.co/${origin.repo} · ${origin.quant}"

    /**
     * The download that brings [model] back, or null for a model without an origin (an import: only
     * "Re-import" is offered). The file's size is the model row's own: it is what the download
     * finished with, so the same size is expected again and the manager's checks stay exact. The
     * commit pins the URL to the very bytes it came from, null falls back to `main`.
     */
    fun again(model: ModelEntity): DownloadAgainRequest? {
        val origin = model.origin ?: return null
        return DownloadAgainRequest(
            repo = origin.repo,
            quant = HfQuantFile(
                fileName = origin.file,
                quant = origin.quant.ifBlank { model.displayQuant },
                sizeBytes = model.sizeBytes,
                sha256 = origin.sha256,
            ),
            displayName = model.displayName,
            commitSha = origin.commit,
        )
    }

    /** The card of the running download that is [origin]'s file, if there is one. */
    fun activeDownload(origin: ModelOrigin, cards: List<DownloadCardModel>): DownloadCardModel? =
        cards.firstOrNull { it.repo.equals(origin.repo, ignoreCase = true) && it.fileName == origin.file }
}
