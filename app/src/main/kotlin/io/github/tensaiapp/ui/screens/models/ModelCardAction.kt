package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.runtime.InferenceQueue

/** What a [ModelCard] whose file is gone offers as its one action (TEN-107). */
internal enum class MissingFileAction { Remove, Unload }

/**
 * A resident model whose file vanished keeps working (the mmap holds the inode), but
 * `ModelRepository.deleteModel` refuses to touch it, so "Remove" would be a dead end. Offer
 * "Unload" first; once the queue is idle the card reads not-loaded + missing and gets "Remove".
 */
internal fun missingFileAction(isLoaded: Boolean): MissingFileAction =
    if (isLoaded) MissingFileAction.Unload else MissingFileAction.Remove

/** The one full-width action at the foot of a model card or the detail screen. */
internal enum class ModelAction {
    /** Normal: tonal "Load model". */
    Load,

    /** Won't fit and not loaded: outlined "Load anyway". */
    LoadAnyway,

    /** Unsupported architecture or draft head, not loaded: the Load button, disabled (TEN-167). */
    LoadBlocked,

    /** Loaded, file on disk: outlined "Unload". */
    Unload,

    /** File gone but still resident: error-tonal "Unload" (TEN-107). */
    UnloadMissing,

    /** File gone, not loaded: error-tonal "Remove". */
    Remove,
}

/**
 * Which action a model offers, from its state. A missing file wins over everything (nothing can be
 * loaded), then being resident (the way out is Unload, whatever the verdict), then the verdict.
 */
internal fun modelAction(isLoaded: Boolean, fileExists: Boolean, wontFit: Boolean, blocked: Boolean = false): ModelAction = when {
    !fileExists && isLoaded -> ModelAction.UnloadMissing
    !fileExists -> ModelAction.Remove
    isLoaded -> ModelAction.Unload
    blocked -> ModelAction.LoadBlocked
    wontFit -> ModelAction.LoadAnyway
    else -> ModelAction.Load
}

/** Same call as the foreground notification's Unload action (`InferenceService.ACTION_UNLOAD`). */
internal suspend fun unloadResidentModel(queue: InferenceQueue) = queue.unloadModel()
