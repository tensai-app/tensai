package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.data.session.SessionEntity
import java.io.File

/** Saved sessions attributable to one model: how many, and how many bytes they hold on disk. */
internal data class ModelSessions(val sessions: List<SessionEntity>, val bytes: Long) {
    val count: Int get() = sessions.size
}

/**
 * The sessions of [modelId], read off [SessionEntity.modelId] (every saved state records the model
 * that produced it). Sizes come from disk and a row whose file is gone counts for nothing, the
 * same rule as `SettingsStorage.figures`. `null` when there is nothing to show.
 */
internal fun sessionsOfModel(
    modelId: String,
    sessions: List<SessionEntity>,
    fileSize: (String) -> Long? = { File(it).takeIf(File::isFile)?.length() },
): ModelSessions? {
    val present = sessions.filter { it.modelId == modelId }.mapNotNull { s -> fileSize(s.filePath)?.let { s to it } }
    if (present.isEmpty()) return null
    return ModelSessions(present.map { it.first }, present.sumOf { it.second })
}
