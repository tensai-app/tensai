package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.download.DownloadError
import io.github.tensaiapp.download.DownloadItem
import io.github.tensaiapp.download.DownloadStatus
import io.github.tensaiapp.hf.HfError
import io.github.tensaiapp.hf.HfQuantFile
import io.github.tensaiapp.hf.HfRecommendation
import io.github.tensaiapp.hf.RatedQuant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Button state, file matching, README cleaning and speed of the repo screen (TEN-140). */
class RepoLogicTest {

    private val mib = 1024L * 1024

    private fun quant(label: String, sizeMib: Long, name: String = "m-$label.gguf") =
        HfQuantFile(name, label, sizeMib * mib, null)

    private fun item(
        status: DownloadStatus,
        id: String = "d1",
        repo: String = "a/b",
        file: String = "m-Q4_K_M.gguf",
        size: Long = 1000,
        done: Long = 0,
        error: DownloadError? = null,
    ) = DownloadItem(id, repo, file, "Q4_K_M", "b", size, null, status, done, error, null)

    private val q4 = quant("Q4_K_M", 1200)

    private fun action(
        item: DownloadItem? = null,
        onDevice: String? = null,
        gated: Boolean = false,
        hasToken: Boolean = false,
        ahead: Int = 0,
        bps: Long = 0,
        quant: HfQuantFile? = q4,
    ) = RepoLogic.action(quant, gated, hasToken, onDevice, item, ahead, bps)

    // ---- button per download status

    @Test
    fun noQuantMeansNoButton() {
        assertEquals(RepoAction.None, action(quant = null))
    }

    @Test
    fun aFreshFileOffersDownloadWithItsSize() {
        assertEquals(RepoAction.Download(q4.sizeBytes), action())
    }

    @Test
    fun aFileInTheLibraryOffersLoadWhateverTheQueueSays() {
        assertEquals(RepoAction.Load("m1"), action(onDevice = "m1"))
        assertEquals(RepoAction.Load("m1"), action(onDevice = "m1", item = item(DownloadStatus.Failed)))
        assertEquals(RepoAction.Load("m1"), action(onDevice = "m1", gated = true))
    }

    @Test
    fun aDownloadingFileShowsBytesSpeedAndTimeLeft() {
        val a = action(item(DownloadStatus.Downloading, size = 1000, done = 400), bps = 100)

        assertEquals(RepoAction.Downloading("d1", 400, 1000, 100, 6), a)
    }

    @Test
    fun theTimeLeftRoundsUpAndIsUnknownWithoutASpeed() {
        val running = item(DownloadStatus.Downloading, size = 1000, done = 0)

        assertEquals(4L, (action(running, bps = 300) as RepoAction.Downloading).etaSeconds)
        assertNull((action(running, bps = 0) as RepoAction.Downloading).etaSeconds)
    }

    @Test
    fun aPausedFileOffersResume() {
        assertEquals(RepoAction.Paused("d1", 250, 1000), action(item(DownloadStatus.Paused, done = 250)))
    }

    @Test
    fun aQueuedFileShowsHowManyAreAhead() {
        assertEquals(RepoAction.Queued("d1", 2), action(item(DownloadStatus.Queued), ahead = 2))
    }

    @Test
    fun aFailedFileShowsItsReasonAndRetry() {
        assertEquals(
            RepoAction.Failed("d1", DownloadError.DiskFull),
            action(item(DownloadStatus.Failed, error = DownloadError.DiskFull)),
        )
        assertEquals(RepoAction.Failed("d1", DownloadError.Http(503)), action(item(DownloadStatus.Failed, error = DownloadError.Http(503))))
    }

    @Test
    fun aDownloadThatFailedGatedOrWithAnInvalidTokenAsksForAToken() {
        assertEquals(RepoAction.NeedsToken(false), action(item(DownloadStatus.Failed, error = DownloadError.Gated)))
        assertEquals(RepoAction.NeedsToken(true), action(item(DownloadStatus.Failed, error = DownloadError.InvalidToken)))
    }

    @Test
    fun aGatedFailureWithATokenIsALicenseProblemNotATokenProblem() {
        val gated = item(DownloadStatus.Failed, error = DownloadError.Gated)
        val license = item(DownloadStatus.Failed, error = DownloadError.LicenseNotAccepted)
        assertEquals(RepoAction.NeedsLicense("d1"), action(license, hasToken = true))
        assertEquals(RepoAction.NeedsToken(false), action(gated, hasToken = false))
        // A "needs a token" failure from before the token was set is just a failure to retry.
        assertEquals(RepoAction.Failed("d1", DownloadError.Gated), action(gated, hasToken = true))
        // An invalid token stays a token problem either way.
        assertEquals(RepoAction.NeedsToken(true), action(item(DownloadStatus.Failed, error = DownloadError.InvalidToken), hasToken = true))
    }

    @Test
    fun aFailedRepoLoadPicksItsGateCardFromTheErrorAndTheToken() {
        assertEquals(GateCard.NeedsToken, RepoLogic.gateFor(HfError.Gated(401), hasToken = false))
        assertEquals(GateCard.NeedsLicense, RepoLogic.gateFor(HfError.Gated(403), hasToken = true))
        // 401 with a token cannot be the licence (the client maps it to InvalidToken): never that card.
        assertEquals(GateCard.NeedsToken, RepoLogic.gateFor(HfError.Gated(401), hasToken = true))
        assertEquals(GateCard.NeedsToken, RepoLogic.gateFor(HfError.Gated(403), hasToken = false))
        assertEquals(GateCard.InvalidToken, RepoLogic.gateFor(HfError.InvalidToken, hasToken = true))
        assertEquals(GateCard.InvalidToken, RepoLogic.gateFor(HfError.InvalidToken, hasToken = false))
        assertNull(RepoLogic.gateFor(HfError.NotFound, hasToken = true))
        assertNull(RepoLogic.gateFor(HfError.Offline, hasToken = false))
    }

    @Test
    fun aGatedRepoWithoutATokenAsksForOneUpFront() {
        assertEquals(RepoAction.NeedsToken(false), action(gated = true, hasToken = false))
        assertEquals(RepoAction.Download(q4.sizeBytes), action(gated = true, hasToken = true))
    }

    @Test
    fun aFinishedDownloadIsNotAnActionOfItsOwn() {
        assertEquals(RepoAction.Download(q4.sizeBytes), action(item(DownloadStatus.Done)))
    }

    // ---- matching the queue and the library

    @Test
    fun theLatestUnfinishedDownloadOfTheFileIsTheOneShown() {
        val queue = listOf(
            item(DownloadStatus.Done, id = "old"),
            item(DownloadStatus.Failed, id = "mid"),
            item(DownloadStatus.Paused, id = "new"),
            item(DownloadStatus.Queued, id = "other", file = "other.gguf"),
            item(DownloadStatus.Queued, id = "elsewhere", repo = "c/d"),
        )

        assertEquals("new", RepoLogic.itemFor("A/B", "m-Q4_K_M.gguf", queue)?.id)
        assertNull(RepoLogic.itemFor("a/b", "nope.gguf", queue))
    }

    @Test
    fun onlyRunningAndQueuedDownloadsAreAheadOfAFile() {
        val queue = listOf(
            item(DownloadStatus.Downloading, id = "1"),
            item(DownloadStatus.Paused, id = "2"),
            item(DownloadStatus.Queued, id = "3"),
            item(DownloadStatus.Failed, id = "4"),
            item(DownloadStatus.Queued, id = "5"),
        )

        assertEquals(0, RepoLogic.queueAhead(queue[0], queue))
        assertEquals(2, RepoLogic.queueAhead(queue[4], queue))
        assertEquals(1, RepoLogic.queueAhead(queue[2], queue))
    }

    @Test
    fun aLibraryModelIsTheFileWhenNameAndSizeMatchFolderIgnored() {
        val library = listOf(LibraryFile("m1", "m-Q4_K_M.gguf", q4.sizeBytes), LibraryFile("m2", "m-Q8_0.gguf", 1))

        assertEquals("m1", RepoLogic.onDeviceModelId(q4, library))
        assertEquals("m1", RepoLogic.onDeviceModelId(q4.copy(fileName = "sub/m-Q4_K_M.gguf"), library))
        // Same name, other size: another build of the file.
        assertNull(RepoLogic.onDeviceModelId(q4.copy(sizeBytes = q4.sizeBytes + 1), library))
        assertNull(RepoLogic.onDeviceModelId(quant("Q2_K", 500), library))
    }

    // ---- preselection

    private fun compat(q: HfQuantFile) =
        Compatibility(Verdict.RunsWell, q.sizeBytes, 8L * 1024 * mib, BackendHint.Cpu)

    private fun rated(vararg q: HfQuantFile) = q.sortedBy { it.sizeBytes }.map {
        RatedQuant(it, compat(it))
    }

    private val q2 = quant("Q2_K", 700)
    private val q8 = quant("Q8_0", 2100)

    private fun recommendation(q: HfQuantFile, isRecommended: Boolean = true) =
        HfRecommendation(q, compat(q), isRecommended)

    @Test
    fun theLinkedFileWinsThenTheRecommendationThenTheSmallest() {
        val all = rated(q2, q4, q8)

        assertEquals(q8.fileName, RepoLogic.preselect(all, recommendation(q4), q8.fileName))
        assertEquals(q4.fileName, RepoLogic.preselect(all, recommendation(q4), null))
        assertEquals(q2.fileName, RepoLogic.preselect(all, null, null))
        assertNull(RepoLogic.preselect(emptyList(), null, null))
    }

    @Test
    fun aLinkedFileTheRepoDoesNotHaveFallsBackAndAFolderIsForgiven() {
        val inFolder = quant("Q4_K_M", 1200, name = "gguf/m-Q4_K_M.gguf")
        val all = rated(q2, inFolder)

        assertEquals(q2.fileName, RepoLogic.preselect(all, recommendation(q2), "missing.gguf"))
        assertEquals(inFolder.fileName, RepoLogic.preselect(all, recommendation(q2), "m-q4_k_m.gguf"))
    }

    @Test
    fun aLinkedFileInARemovedQuantNeverSelectsAnythingHidden() {
        val all = rated(q2, q4)

        assertEquals(q4.fileName, RepoLogic.preselect(all, recommendation(q4), "m-Q4_0_4_4.gguf"))
        assertEquals(q2.fileName, RepoLogic.preselect(all, null, "dir/m-Q4_0_8_8.gguf"))
    }

    // ---- labels

    @Test
    fun aRowIsLabelledWithItsQuantUnlessThatDoesNotTheFilesApart() {
        val dup = listOf(quant("Q4_K_M", 1, name = "a/m-Q4_K_M.gguf"), quant("Q4_K_M", 2, name = "b/m-Q4_K_M.gguf"))
        val unknown = HfQuantFile("weights.gguf", "GGUF", 5, null)

        assertEquals("Q4_K_M", RepoLogic.rowLabel(q4, listOf(q4, q8)))
        assertEquals("a/m-Q4_K_M.gguf", RepoLogic.rowLabel(dup[0], dup))
        assertEquals("weights.gguf", RepoLogic.rowLabel(unknown, listOf(unknown, q4)))
    }

    @Test
    fun theContextIsInKilotokens() {
        assertEquals("32K", RepoLogic.formatContext(32_768))
        assertEquals("128K", RepoLogic.formatContext(131_072))
        assertEquals("40K", RepoLogic.formatContext(40_960))
        assertEquals("1M", RepoLogic.formatContext(1_048_576))
        assertEquals("512", RepoLogic.formatContext(512))
    }

    // ---- README

    @Test
    fun theFrontMatterAndCommentsAreCutOff() {
        val raw = "---\nlicense: apache-2.0\ntags:\n- gguf\n---\n<!-- hidden -->\n# Title\n\nBody text.\n"

        assertEquals("# Title\n\nBody text.", RepoLogic.cleanReadme(raw))
    }

    @Test
    fun aReadmeWithoutFrontMatterIsKept() {
        assertEquals("# Title\n\ntext", RepoLogic.cleanReadme("# Title\n\ntext\n"))
        // A horizontal rule is not a front matter block.
        assertEquals("text\n\n---\n\nmore", RepoLogic.cleanReadme("text\n\n---\n\nmore"))
    }

    @Test
    fun linesThatAreOnlyHtmlTagsGoButCodeBlocksStay() {
        val raw = "<p align=\"center\">\n<img src=\"x.png\">\n</p>\n\nHello <b>world</b>\n\n```html\n<div>\n</div>\n```\n"

        assertEquals("Hello <b>world</b>\n\n```html\n<div>\n</div>\n```", RepoLogic.cleanReadme(raw))
    }

    @Test
    fun anEmptyOrFrontMatterOnlyReadmeCleansToNothing() {
        assertEquals("", RepoLogic.cleanReadme(""))
        assertEquals("", RepoLogic.cleanReadme("---\nlicense: mit\n---\n"))
    }

    @Test
    fun theReadmeIsCutAtAParagraphBreak() {
        val text = "a".repeat(60) + "\n\n" + "b".repeat(60) + "\n\n" + "c".repeat(60)

        assertEquals(text, RepoLogic.slice(text, 1000))
        assertEquals("a".repeat(60) + "\n\n" + "b".repeat(60), RepoLogic.slice(text, 150))
        // No break in reach: a hard cut.
        assertEquals(100, RepoLogic.slice("x".repeat(500), 100).length)
        assertTrue(RepoLogic.slice("x".repeat(500), 100).all { it == 'x' })
    }

    // ---- speed

    @Test
    fun speedNeedsTwoReadingsThenSmoothsThem() {
        val meter = SpeedMeter(weight = 0.5)

        assertEquals(0L, meter.sample("d1", 0, 0))
        // The first jump only re-bases: a first reading can be a stale snapshot.
        assertEquals(0L, meter.sample("d1", 90_000, 500))
        assertEquals(1000L, meter.sample("d1", 91_000, 1500))
        // 3000 B/s now, half of it blended with the 1000 before.
        assertEquals(2000L, meter.sample("d1", 94_000, 2500))
    }

    @Test
    fun speedStartsOverForAnotherDownloadAndAfterNoneRuns() {
        val meter = SpeedMeter()
        meter.sample("d1", 0, 0)
        meter.sample("d1", 1000, 500)
        assertTrue(meter.sample("d1", 5000, 1000) > 0)

        assertEquals(0L, meter.sample("d2", 100, 2000))
        assertEquals(0L, meter.sample(null, 0, 3000))
        assertEquals(0L, meter.sample("d2", 200, 4000))
    }

    @Test
    fun aThrottledGapIsNotAStopUntilItIsLong() {
        val meter = SpeedMeter()
        meter.sample("d1", 0, 0)
        val speed = meter.sample("d1", 1000, 1000)

        assertEquals(speed, meter.sample("d1", 1000, 5000))
        assertEquals(0L, meter.sample("d1", 1000, 20_000))
    }

    // ---- README, adversarial inputs (linear time, never throws)

    private fun timed(limitMs: Long = 2_000, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < limitMs)
    }

    @Test
    fun manyUnterminatedCommentOpenersAreLinear() = timed {
        val raw = "<!--".repeat(200_000)

        assertEquals("", RepoLogic.cleanReadme(raw))
    }

    @Test
    fun anUnterminatedCommentRunsToTheEndAndATerminatedOneIsCut() {
        assertEquals("before", RepoLogic.cleanReadme("before\n<!-- never closed\nmore"))
        assertEquals("a b", RepoLogic.cleanReadme("a <!-- x --><!-- y -->b").replace("a b", "a b"))
        assertEquals("ab", RepoLogic.stripComments("a<!-- x -->b"))
    }

    @Test
    fun aHugeTagOnlyLineAndAHugeProseLineAreLinear() = timed {
        val tags = "<a>".repeat(300_000)
        val prose = "x".repeat(300_000) + "<"
        val spaced = "<b ".repeat(100_000)

        assertEquals(tags, RepoLogic.cleanReadme(tags))
        assertEquals(prose, RepoLogic.cleanReadme(prose))
        assertEquals(spaced.trim(), RepoLogic.cleanReadme(spaced))
    }

    @Test
    fun tagsOnlyIsDecidedPerLineWithAShortCap() {
        assertTrue(RepoLogic.isTagsOnly("  <p align=\"center\"> <img src=\"x\"/> </p>  "))
        assertTrue(!RepoLogic.isTagsOnly("<b>bold</b> text"))
        assertTrue(!RepoLogic.isTagsOnly("<3 hearts"))
        assertTrue(!RepoLogic.isTagsOnly("<unclosed"))
        assertTrue(!RepoLogic.isTagsOnly("   "))
        assertTrue(!RepoLogic.isTagsOnly("<a>".repeat(500)))
    }

    @Test
    fun manyBlankLinesCollapseInOnePass() = timed {
        val raw = "a" + "\n".repeat(1_000_000) + "b"

        assertEquals("a\n\nb", RepoLogic.cleanReadme(raw))
    }

    @Test
    fun theExpandedReadmeIsCappedLow() {
        assertTrue(RepoLogic.README_MAX_CHARS <= 12_000)
        val text = (1..5_000).joinToString("\n\n") { "paragraph number $it" }

        val cut = RepoLogic.slice(text, RepoLogic.README_MAX_CHARS)

        assertTrue(cut.length <= RepoLogic.README_MAX_CHARS)
        assertTrue(text.startsWith(cut))
    }

    // ---- blocked verdicts (TEN-167)

    @Test
    fun anUnsupportedFileOffersNoDownload() {
        val blocked = io.github.tensaiapp.compat.Compatibility(
            verdict = io.github.tensaiapp.compat.Verdict.Unsupported,
            estimatedBytes = 0,
            deviceRamBytes = 0,
            recommendedBackend = io.github.tensaiapp.engine.BackendHint.Auto,
            architecture = "spark2_5",
        )
        assertEquals(
            RepoAction.Blocked(io.github.tensaiapp.compat.Verdict.Unsupported, "spark2_5"),
            RepoLogic.action(q4, false, false, null, null, 0, 0, blocked = blocked),
        )
        // Already in the library: still blocked, consistent with the card.
        assertEquals(RepoAction.Blocked(io.github.tensaiapp.compat.Verdict.Unsupported, "spark2_5"), RepoLogic.action(q4, false, false, "m1", null, 0, 0, blocked = blocked))
    }
}
