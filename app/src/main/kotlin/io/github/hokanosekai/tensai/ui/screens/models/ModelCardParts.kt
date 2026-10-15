package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.runtime.EffectiveBackend

/** The chips under a card's facts line, in the order [cardChips] returns them. */
internal enum class CardChip { Loaded, OnDevice, Tools, Thinking, Vision, Gated, Imported }

private const val SEPARATOR = " · "

/** Owner part of a Hugging Face `author/name` repo, `null` for a missing or malformed one. */
internal fun repoOwner(repo: String?): String? =
    repo?.substringBefore('/', missingDelimiterValue = "")?.trim()?.takeIf { it.isNotEmpty() }

/** "author · 3.2B params", whichever of the two is there, `null` when neither is. */
internal fun cardSubtitle(author: String?, params: String?): String? =
    joinParts(author, params)

/**
 * The mono line under the subtitle: "Q4_K_M · 1.9 GB · CPU". The size is left out when
 * [sizeBytes] is not positive (no "0 MB"), [backend] when the file is missing or the backend
 * unknown (the Discover card has none).
 */
internal fun cardFacts(quant: String?, sizeBytes: Long, backend: EffectiveBackend?): String? =
    joinParts(quant, sizeBytes.takeIf { it > 0 }?.let(::formatFileSize), backend?.label)

private fun joinParts(vararg parts: String?): String? =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

/** Chips of a card, empty when none applies (the row is then not drawn). */
internal fun cardChips(
    loaded: Boolean = false,
    onDevice: Boolean = false,
    tools: Boolean = false,
    thinking: Boolean = false,
    vision: Boolean = false,
    gated: Boolean = false,
    imported: Boolean = false,
): List<CardChip> = buildList {
    if (loaded) add(CardChip.Loaded)
    if (onDevice) add(CardChip.OnDevice)
    if (tools) add(CardChip.Tools)
    if (thinking) add(CardChip.Thinking)
    if (SHOW_VISION && vision) add(CardChip.Vision)
    if (gated) add(CardChip.Gated)
    if (imported) add(CardChip.Imported)
}

/** "Vulkan · Mali-G68 MC4", "CPU · 8 cores"; just the backend name when the hardware is unknown. */
internal fun backendHardware(backend: EffectiveBackend, vulkanDevice: String?, openClDevice: String?, cpuCores: String?): String {
    val hardware = when (backend) {
        EffectiveBackend.Vulkan -> vulkanDevice?.trim()?.takeIf { it.isNotEmpty() }
        EffectiveBackend.OpenCl -> openClDevice?.trim()?.takeIf { it.isNotEmpty() }
        EffectiveBackend.Cpu -> cpuCores?.trim()?.takeIf { it.isNotEmpty() }
    }
    return joinParts(backend.label, hardware) ?: backend.label
}
