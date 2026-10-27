package io.github.tensaiapp.bench

/**
 * A run's totals over its trials (a trial is one repetition of one prompt), for the saved run cards and the Compare
 * summary. As in [BenchSummary], the medians leave out errors and a [BenchTrial.prefixCold] trial. [namedInText]:
 * trials with a [BenchTrial.namedInText], [toolErrors]: trials with a call whose result was an error
 * ([BenchCall.error]), whatever their verdict. [answerOk], [answerWrong], [answerCut], [answerEmpty]: trials by
 * [BenchTrial.answerOutcome], none in a run saved before answers were checked. [reasoningP50], [maxReplyReasoningP50]:
 * reasoning tokens of the turn and of its largest reply, errors left out, as in [BenchSummary].
 */
data class RunTotals(
    val trials: Int,
    val counts: Map<BenchVerdict, Int>,
    val namedInText: Int,
    val ttftP50Ms: Long?,
    val totalP50Ms: Long?,
    val toolErrors: Int = 0,
    val answerOk: Int = 0,
    val answerWrong: Int = 0,
    val answerCut: Int = 0,
    val answerEmpty: Int = 0,
    val reasoningP50: Long? = null,
    val maxReplyReasoningP50: Long? = null,
) {
    fun count(verdict: BenchVerdict): Int = counts[verdict] ?: 0

    /** Trials whose answer was checked. */
    val answersChecked: Int get() = answerOk + answerWrong + answerCut + answerEmpty

    /**
     * [metric] for this run: a share of [trials] for a count (of [answersChecked] for an answer), milliseconds or tokens
     * for a median, `null` when there is none.
     */
    fun value(metric: SummaryMetric): Double? = when (metric) {
        SummaryMetric.TtftP50 -> ttftP50Ms?.toDouble()
        SummaryMetric.TotalP50 -> totalP50Ms?.toDouble()
        SummaryMetric.ReasoningP50 -> reasoningP50?.toDouble()
        SummaryMetric.AnswerOk, SummaryMetric.AnswerWrong, SummaryMetric.AnswerCut, SummaryMetric.AnswerEmpty ->
            if (answersChecked == 0) null else countOf(metric).toDouble() / answersChecked
        else -> if (trials == 0) null else countOf(metric).toDouble() / trials
    }

    /** How many trials [metric] counts, 0 for a median. */
    fun countOf(metric: SummaryMetric): Int = when (metric) {
        SummaryMetric.NamedInText -> namedInText
        SummaryMetric.ToolErrors -> toolErrors
        SummaryMetric.AnswerOk -> answerOk
        SummaryMetric.AnswerWrong -> answerWrong
        SummaryMetric.AnswerCut -> answerCut
        SummaryMetric.AnswerEmpty -> answerEmpty
        SummaryMetric.TtftP50, SummaryMetric.TotalP50, SummaryMetric.ReasoningP50 -> 0
        else -> count(metric.verdict!!)
    }

    companion object {
        fun of(trials: List<BenchTrial>): RunTotals {
            val timed = trials.filter { it.benchVerdict != BenchVerdict.Error && !it.prefixCold }
            return RunTotals(
                trials = trials.size,
                counts = BenchVerdict.entries.associateWith { v -> trials.count { it.benchVerdict == v } },
                namedInText = trials.count { it.namedInText.isNotEmpty() },
                ttftP50Ms = BenchSummary.median(timed.mapNotNull { it.ttftMs }),
                totalP50Ms = BenchSummary.median(timed.map { it.totalMs }),
                toolErrors = trials.count { trial -> trial.calls.any { it.error != null } },
                answerOk = trials.count { it.answerOutcome == AnswerOutcome.Correct },
                answerWrong = trials.count { it.answerOutcome == AnswerOutcome.Wrong },
                answerCut = trials.count { it.answerOutcome == AnswerOutcome.Cut },
                answerEmpty = trials.count { it.answerOutcome == AnswerOutcome.Empty },
                reasoningP50 = BenchSummary.median(trials.filter { it.benchVerdict != BenchVerdict.Error }.mapNotNull { it.reasoningTokens?.toLong() }),
                maxReplyReasoningP50 = BenchSummary.median(trials.filter { it.benchVerdict != BenchVerdict.Error }.mapNotNull { it.maxReplyReasoningTokens?.toLong() }),
            )
        }
    }
}

/** The rows of the Compare summary, in order. [verdict] is the one a count row counts. */
enum class SummaryMetric(val verdict: BenchVerdict?, val higherIsBetter: Boolean = false) {
    Correct(BenchVerdict.Correct, higherIsBetter = true),
    FalseCall(BenchVerdict.FalseCall),
    Missing(BenchVerdict.Missing),
    WrongTool(BenchVerdict.WrongTool),
    Error(BenchVerdict.Error),
    NamedInText(null),
    ToolErrors(null),
    AnswerOk(null, higherIsBetter = true),
    AnswerWrong(null),
    AnswerCut(null),
    AnswerEmpty(null),
    TtftP50(null),
    TotalP50(null),
    ReasoningP50(null),
}

/**
 * The indices of [totals] best on [metric] (all of them on a tie), empty when fewer than two runs have a value or all
 * values are the same: a best needs something to be better than.
 */
fun bestRuns(totals: List<RunTotals>, metric: SummaryMetric): Set<Int> {
    val values = totals.map { it.value(metric) }
    val present = values.filterNotNull()
    if (present.size < 2 || present.distinct().size == 1) return emptySet()
    val best = if (metric.higherIsBetter) present.max() else present.min()
    return values.indices.filterTo(LinkedHashSet()) { values[it] == best }
}

/** The order failures are shown in, and break ties in: the stacked bar's. */
val FailureOrder = listOf(BenchVerdict.FalseCall, BenchVerdict.Missing, BenchVerdict.WrongTool, BenchVerdict.Error, BenchVerdict.Unavailable)

/**
 * One prompt's trials in one run, as a matrix cell: [passes] of [total] correct, and the [failure] the others mostly
 * were. One trial reads as its verdict; several as a pass rate.
 */
data class PassCell(val trials: List<BenchTrial>) {
    val total: Int get() = trials.size
    val passes: Int get() = trials.count { it.benchVerdict == BenchVerdict.Correct }

    /** The most frequent verdict that is not a pass, ties in [FailureOrder], `null` when every trial passed. */
    val failure: BenchVerdict?
        get() {
            val failed = trials.map { it.benchVerdict }.filter { it != BenchVerdict.Correct }
            if (failed.isEmpty()) return null
            val counts = failed.groupingBy { it }.eachCount()
            return FailureOrder.maxWith(compareBy<BenchVerdict> { counts[it] ?: 0 }.thenByDescending { FailureOrder.indexOf(it) })
        }

    /** Every trial was [BenchVerdict.Unavailable]: nothing to say about the model, shown as N/A rather than 0/n. */
    val unavailable: Boolean get() = trials.isNotEmpty() && trials.all { it.benchVerdict == BenchVerdict.Unavailable }

    /**
     * What makes two cells the same outcome: the pass rate and the failures seen. So 1/1 and 3/3 match, 0/3 with
     * three misses and 0/3 with a false call do not.
     */
    val signature: Pair<Double, Set<BenchVerdict>>
        get() = (if (total == 0) 0.0 else passes.toDouble() / total) to trials.map { it.benchVerdict }.filterTo(HashSet()) { it != BenchVerdict.Correct }
}

/** One prompt across the compared runs: its cell in each, `null` where a partial run never reached it. */
data class CompareRow(val promptId: String, val cells: List<PassCell?>) {
    /** The runs disagree on this prompt, see [PassCell.signature]. A missing cell is not a disagreement. */
    val changed: Boolean get() = cells.filterNotNull().map { it.signature }.distinct().size > 1
}

/** The matrix of [runs]: one row per prompt, in the order the runs first met them. */
fun compareRows(runs: List<BenchRun>): List<CompareRow> {
    val byRun = runs.map { run -> run.results.groupBy { it.id } }
    val ids = LinkedHashSet<String>()
    runs.forEach { run -> run.results.forEach { ids += it.id } }
    return ids.map { id -> CompareRow(id, byRun.map { trials -> trials[id]?.let(::PassCell) }) }
}
