package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.hf.HfCompatibility
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.HfRepoSummary
import io.github.hokanosekai.tensai.hf.HfSort
import io.github.hokanosekai.tensai.ui.screens.onboarding.CatalogModel
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingCatalog
import java.util.Locale

/**
 * What a Discover card shows (TEN-137), already rated for this device.
 *
 * @param id repo path, `author/name`.
 * @param paramCount 0 when unknown: the params part of the subtitle is dropped.
 * @param downloads / [likes] null for a catalog entry: it is shown offline, there is no count to show.
 */
internal data class RepoCardModel(
    val id: String,
    val name: String,
    val author: String,
    val paramCount: Long,
    val verdict: Verdict,
    val quant: String,
    val sizeBytes: Long,
    val tools: Boolean,
    val vision: Boolean,
    val gated: Boolean,
    val downloads: Long?,
    val likes: Long?,
)

/** Pure rules behind the Discover home: no Android, no network, so they run on the JVM. */
internal object DiscoverLogic {

    /** Below this a repo is not a chat model (audio.cpp reported 0.0B): hidden from Discover. */
    private const val MIN_PARAMS = 100_000_000L

    /**
     * The catalog entries that fit [profile], best first: those that run well before the tight ones,
     * the biggest first inside each (parameter count is the size proxy, as in the onboarding pick).
     * Judged on the device class ([OnboardingCatalog.steadyProfile]), the same basis as everywhere
     * else, so the list does not change with the apps open. Offline: nothing here touches the network.
     */
    fun recommended(catalog: List<CatalogModel>, profile: DeviceProfile): List<RepoCardModel> {
        val steady = OnboardingCatalog.steadyProfile(profile)
        return catalog
            .map { it to CompatibilityChecker.check(it.facts, steady).verdict }
            .filter { (_, verdict) -> verdict != Verdict.WontFit }
            .sortedWith(compareBy({ (_, verdict) -> verdict.ordinal }, { (model, _) -> -model.facts.paramCount }))
            .map { (model, verdict) ->
                RepoCardModel(
                    id = model.hfPath,
                    name = model.name,
                    author = model.hfPath.substringBefore('/'),
                    paramCount = model.facts.paramCount,
                    verdict = verdict,
                    quant = model.quantLabel,
                    sizeBytes = model.facts.sizeBytes,
                    tools = model.supportsTools,
                    vision = false,
                    gated = false,
                    downloads = null,
                    likes = null,
                )
            }
    }

    /**
     * The card of a Hugging Face repo, from the quant [HfCompatibility.recommendation] picks, or null
     * when the repo has no usable quant or is not a chat model. A repo whose nearest-to-fitting quant
     * does not fit still gets its card, with [Verdict.WontFit]: whether it is listed is the "Fits my
     * phone" filter's call ([matches]), not this function's.
     */
    fun cardFor(summary: HfRepoSummary, detail: HfRepoDetail, profile: DeviceProfile): RepoCardModel? {
        // Not a chat LLM llama.cpp can run: no GGUF header summary at all, or a toy-sized one.
        if (detail.paramCount < MIN_PARAMS && detail.architecture.isBlank()) return null
        if (detail.paramCount in 1 until MIN_PARAMS) return null
        val pick = HfCompatibility.recommendation(detail, profile) ?: return null
        val verdict = pick.compatibility.verdict
        return RepoCardModel(
            id = summary.id,
            name = displayName(summary.name),
            author = summary.author,
            paramCount = detail.paramCount,
            verdict = verdict,
            quant = pick.quant.quant,
            sizeBytes = pick.quant.sizeBytes,
            tools = detail.supportsTools,
            vision = detail.hasVision,
            gated = summary.isGated,
            downloads = summary.downloads,
            likes = summary.likes,
        )
    }

    /** Whether [card] passes [filters]: "Fits my phone" drops [Verdict.WontFit], Tools and Vision need the badge. */
    fun matches(card: RepoCardModel, filters: DiscoverFilters): Boolean =
        (!filters.fits || card.verdict != Verdict.WontFit) && (!filters.tools || card.tools) && (!filters.vision || card.vision)

    /**
     * The repos of a feed that are listed under [filters], in display order. A repo whose card has not
     * arrived stays in (as a skeleton); one whose detail failed or that is not a chat model ([CardSlot.Hidden]),
     * one whose card fails [matches] and the ones in [exclude] (lower case ids, already shown elsewhere) are out.
     * [DiscoverSort.Size] orders by the size of the card's quant, smallest first, repos still loading last;
     * the other sorts are the Hub's order, kept as it came.
     */
    fun shown(
        repos: List<HfRepoSummary>,
        slots: Map<String, CardSlot>,
        filters: DiscoverFilters,
        exclude: Set<String> = emptySet(),
    ): List<HfRepoSummary> {
        val listed = repos.filter { repo ->
            repo.id.lowercase() !in exclude && when (val slot = slots[repo.id]) {
                CardSlot.Hidden -> false
                is CardSlot.Ready -> matches(slot.card, filters)
                else -> true
            }
        }
        if (filters.sort != DiscoverSort.Size) return listed
        return listed.sortedBy { (slots[it.id] as? CardSlot.Ready)?.card?.sizeBytes ?: Long.MAX_VALUE }
    }

    /**
     * Lower-cased repo ids the library has a download of (TEN-153): a Discover card of one of them
     * says "On device", whatever quant is installed. Imports have no origin and are not matched.
     */
    fun onDeviceRepos(models: List<ModelEntity>): Set<String> =
        models.mapNotNullTo(HashSet()) { it.sourceRepo?.takeIf(String::isNotBlank)?.lowercase() }

    /** `Qwen3-4B-GGUF` -> `Qwen3-4B`: the suffix says what every repo here is, so it only adds noise. */
    fun displayName(repoName: String): String =
        repoName.replace(Regex("[-_.]GGUF$", RegexOption.IGNORE_CASE), "").ifBlank { repoName }

    /** 96 -> `96`, 1_500 -> `1.5k`, 182_400 -> `182k`, 2_300_000 -> `2.3M`. Fixed `.` separator, like the other mono figures. */
    fun formatCount(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 100_000 -> String.format(Locale.US, "%.1f", n / 1_000.0).removeSuffix(".0") + "k"
        n < 1_000_000 -> "${n / 1_000}k"
        else -> String.format(Locale.US, "%.1f", n / 1_000_000.0).removeSuffix(".0") + "M"
    }
}

/** The sort menu of Discover. [api] is what the Hub is asked for; [Size] has no API sort and orders the loaded cards. */
internal enum class DiscoverSort(val api: HfSort) {
    Downloads(HfSort.Downloads),
    Likes(HfSort.Likes),
    Recent(HfSort.LastModified),
    Size(HfSort.Downloads),
}

/** The chips and the sort of Discover: "Fits my phone" starts on. */
internal data class DiscoverFilters(
    val fits: Boolean = true,
    val tools: Boolean = false,
    val vision: Boolean = false,
    val sort: DiscoverSort = DiscoverSort.Downloads,
)

/**
 * [profile] read without letting a failure out: a device profile that cannot be read becomes a
 * deliberately small, conservative phone (nothing big is called "fits"). Cancellation passes through.
 */
internal fun safeProfile(profile: () -> io.github.hokanosekai.tensai.compat.DeviceProfile): io.github.hokanosekai.tensai.compat.DeviceProfile =
    try {
        profile()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        io.github.hokanosekai.tensai.compat.DeviceProfile(
            totalRamBytes = 3L * 1024 * 1024 * 1024,
            availableRamBytes = 1L * 1024 * 1024 * 1024,
            socModel = "unknown",
            bigCoreCount = 0,
            hasVulkan = false,
            hasOpenCl = false,
        )
    }
