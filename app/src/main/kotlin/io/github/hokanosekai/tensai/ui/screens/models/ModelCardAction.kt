package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.runtime.InferenceQueue

/** What a [ModelCard] whose file is gone offers as its one action (TEN-107). */
internal enum class MissingFileAction { Remove, Unload }

/**
 * A resident model whose file vanished keeps working (the mmap holds the inode), but
 * `ModelRepository.deleteModel` refuses to touch it, so "Remove" would be a dead end. Offer
 * "Unload" first; once the queue is idle the card reads not-loaded + missing and gets "Remove".
 */
internal fun missingFileAction(isLoaded: Boolean): MissingFileAction =
    if (isLoaded) MissingFileAction.Unload else MissingFileAction.Remove

/** Same call as the foreground notification's Unload action (`InferenceService.ACTION_UNLOAD`). */
internal suspend fun unloadResidentModel(queue: InferenceQueue) = queue.unloadModel()
