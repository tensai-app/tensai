package io.github.hokanosekai.tensai.ui.components

import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text as MarkdownTextNode

/**
 * One drawn piece of an inline text run, once its `$…$` maths have been read (TEN-90).
 *
 * [MarkdownText] turns these into `AnnotatedString` spans; everything the reader is not confident
 * about arrives here as [Plain] holding the original LaTeX, delimiters included.
 */
internal sealed interface MathSpan {
    val text: String

    /** Ordinary copy at the caller's baseline — including any `$` that was *not* read as maths. */
    data class Plain(override val text: String) : MathSpan

    /** A subscript, as in `H_B`. */
    data class Subscript(override val text: String) : MathSpan

    /** A superscript, as in `x^2`. */
    data class Superscript(override val text: String) : MathSpan
}

/**
 * Reads the `$…$` and `$$…$$` maths out of one inline text run and returns what to draw (TEN-90).
 *
 * Small models write LaTeX whether or not anything asked them to, so a reply that reasons about
 * `$17 \times 24 = 408$` otherwise shows the reader the source. Rather than embed a typesetting
 * engine (KaTeX in a WebView, MathJax) for what a 2B model on a phone actually emits, this maps a
 * deliberately small vocabulary onto Unicode and two baseline shifts, and **hands back anything
 * else untouched**: a wrong rendering is worse than a missing one, so `$\int_0^1 f(x)dx$` stays
 * exactly as the model wrote it.
 *
 * What is read:
 * - the operators `\times \cdot \div \pm \mp \le \leq \ge \geq \ne \neq \approx \infty`,
 * - the Greek letters, lower and upper case, including the `\var…` shapes,
 * - the spacing commands `\, \; \: \ `, plus `\quad` and `\qquad` as em spaces,
 * - single subscripts and superscripts, `H_B`, `x^2`, `x_{10}`,
 * - `\frac{a}{b}`, flattened to `a/b` and parenthesised when a side is not a single atom,
 * - `\text{…}`, whose body is taken verbatim — it is what the models here reach for to label a
 *   term, `\text{index} = …`, and dropping the wrapper is the whole of rendering it.
 *
 * Anything outside that — matrices, integrals, sums with bounds, `\begin`/`\end`, `&` alignments,
 * `\sqrt`, `\mathrm`, an unknown command, a stray `{` — makes the *whole* formula fall back to its
 * literal source.
 *
 * Three conservative rules keep ordinary prose out of this, and none of them may be traded for
 * another: each catches a class the other two miss.
 *
 * A single `$` only opens a formula when the run between it and its closing `$` holds a `\`, `_`
 * or `^`. A price ("costs $5 and $7") carries no such marker and stays literal.
 *
 * The padding must match at both ends — see [paddingIsSymmetric]. Models write `$ x_1 $` far more
 * often than `$x_1$`, so padding cannot disqualify a run; what it can do is tell maths from prose
 * naming two shell variables, which is padded on one side only.
 *
 * A `$` with no partner at all stays literal, which is what every generation looks like while the
 * token after `$H_B` has not arrived yet, and a formula never spans a line break.
 *
 * `$$…$$` is unambiguous enough to skip the marker rule, but not the other two. What is left of a
 * run once its padding is trimmed is what gets read, so `$ x_1 $` draws no wider than `$x_1$`.
 *
 * Code is excluded structurally rather than by a rule here: [MarkdownText] only ever calls this
 * for commonmark `Text` nodes, and a fenced block or an inline `` `…` `` carries its content as a
 * literal with no `Text` child, so `$PATH` inside a `bash` block cannot reach this function.
 *
 * See [displayMathScope] for the other shape maths arrive in, `$$` alone on its own lines.
 */
internal fun mathSpans(literal: String): List<MathSpan> {
    if (literal.isEmpty()) return emptyList()
    if ('$' !in literal) return listOf(MathSpan.Plain(literal))

    val spans = mutableListOf<MathSpan>()
    val plain = StringBuilder()
    var index = 0
    while (index < literal.length) {
        val char = literal[index]
        if (char != '$') {
            plain.append(char)
            index++
            continue
        }

        val display = literal.startsWith("$$", index)
        val delimiter = if (display) "$$" else "$"
        val contentStart = index + delimiter.length
        val close = findClosingDelimiter(literal, contentStart, delimiter)
        val raw = close?.let { literal.substring(contentStart, it) }
        val formula = raw?.takeIf(::paddingIsSymmetric)?.trim()
        val read = formula?.takeIf { it.isNotEmpty() && looksLikeMaths(it, display) }?.let(::readFormula)

        if (close == null || read == null) {
            // Unclosed, unconvincing, or not in the vocabulary: the source stands as written.
            plain.append(delimiter)
            index = contentStart
        } else {
            flush(plain, spans)
            spans += read
            index = close + delimiter.length
        }
    }
    flush(plain, spans)
    return spans.mergeAdjacentPlain()
}

/**
 * A `$$…$$` run that spans several of one paragraph's inline nodes (TEN-90) — what [MarkdownText]
 * draws as maths, and where in the paragraph to draw it.
 *
 * [open] and [close] are the text nodes holding the delimiters. Everything before [open] and
 * everything after [close] is the paragraph's ordinary inline content and is rendered as such.
 */
internal class DisplayMathScope(val open: Node, val close: Node, val spans: List<MathSpan>)

/**
 * Finds the one display formula in [paragraph], or returns `null` (TEN-90).
 *
 * The standard way to write block maths puts the delimiters on their own lines:
 *
 * ```
 * $$
 * \text{index} = (\text{hash}(key) + k^2) \% \text{size}
 * $$
 * ```
 *
 * commonmark makes that `Text("$$")`, a `SoftLineBreak`, the body, another break and `Text("$$")`
 * — separate runs. [mathSpans] works one run at a time and so can never see an opening and its
 * closing together; the characters simply never meet. Hence this second entry point.
 *
 * The all-or-nothing rule applies to the **span between the delimiters**, not to the paragraph
 * (device QA). Models label their formulas — `**Formule quadratique :**` then a hard break then
 * the block — and commonmark puts that label's `StrongEmphasis` in the same paragraph. Refusing
 * the whole paragraph over a bold node that has nothing to do with the formula threw away the
 * commonest shape there is. So: only the nodes strictly between [open] and [close] must be text
 * and line breaks. A `Code` node among them still declines, which is how the structural exclusion
 * survives — `$PATH` in an inline code span between two `$$` is not a formula and never becomes
 * one.
 *
 * The delimiters must each be the whole of their own text node. `$$ a = b $$` written inline
 * inside a run is not this shape; [mathSpans] already reads that one.
 *
 * Declining means the paragraph renders run by run through [mathSpans], which is the pre-existing
 * behaviour: the reader sees the LaTeX. Nothing here can make a paragraph render *wrong*, only
 * unrendered. A formula still streaming has no closing `$$` yet and so declines too.
 */
internal fun displayMathScope(paragraph: Node): DisplayMathScope? {
    val children = inlineChildren(paragraph)
    val openIndex = children.indexOfFirst { it.isDisplayDelimiter() }
    val closeIndex = children.indexOfLast { it.isDisplayDelimiter() }
    if (openIndex < 0 || closeIndex <= openIndex) return null

    val body = StringBuilder()
    for (index in openIndex + 1 until closeIndex) {
        when (val child = children[index]) {
            is MarkdownTextNode -> body.append(child.literal)
            // The breaks are layout, not content: one formula wrapped over several lines. Anything
            // that genuinely needs its own line (a `\\` row, an `&` alignment) is outside the
            // vocabulary and falls out of readFormula as null anyway.
            is SoftLineBreak, is HardLineBreak -> body.append(' ')
            else -> return null
        }
    }

    val formula = body.toString().trim()
    if (formula.isEmpty() || '$' in formula) return null
    val spans = readFormula(formula) ?: return null
    return DisplayMathScope(children[openIndex], children[closeIndex], spans)
}

private fun Node.isDisplayDelimiter(): Boolean = this is MarkdownTextNode && literal.trim() == "$$"

private fun inlineChildren(node: Node): List<Node> {
    val children = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        children += child
        child = child.next
    }
    return children
}

/** The index of the `$`/`$$` that closes a formula opened at [contentStart], or `null`. */
private fun findClosingDelimiter(source: String, contentStart: Int, delimiter: String): Int? {
    // A formula never spans a line break.
    if (contentStart >= source.length) return null
    var index = contentStart
    while (index < source.length) {
        val char = source[index]
        if (char == '\n' || char == '\r') return null
        if (source.startsWith(delimiter, index)) {
            if (index == contentStart) return null // ...and never holds nothing at all.
            return index
        }
        if (char == '$') return null
        index++
    }
    return null
}

/**
 * Whether a `$…$` run is padded the same way at both ends (TEN-90, device QA).
 *
 * Models pad their delimiters — `$ x_1 $` is the commonest way they write inline maths — so the
 * padding cannot simply disqualify a run. But it is exactly what separates maths from prose that
 * happens to name two shell variables: `$ x_1 $` and `$x_1$` are padded on both sides or neither,
 * while `export $HOME_DIR before $PATH` opens tight against `H` and closes after a space. That
 * asymmetry is the tell, and it costs nothing to read.
 */
private fun paddingIsSymmetric(raw: String): Boolean =
    raw.firstOrNull()?.isWhitespace() == raw.lastOrNull()?.isWhitespace()

/** Whether a `$…$` run carries enough LaTeX to be worth reading as one — see [mathSpans]. */
private fun looksLikeMaths(formula: String, display: Boolean): Boolean =
    display || formula.any { it == '\\' || it == '_' || it == '^' }

/** Reads a formula's body, or `null` when any part of it is outside the vocabulary. */
private fun readFormula(formula: String): List<MathSpan>? {
    val spans = mutableListOf<MathSpan>()
    val plain = StringBuilder()
    var index = 0
    while (index < formula.length) {
        val char = formula[index]
        when {
            char == '\\' -> {
                val command = readCommand(formula, index) ?: return null
                when (command.name) {
                    "frac" -> {
                        val numerator = readGroup(formula, command.end) ?: return null
                        val denominator = readGroup(formula, numerator.end) ?: return null
                        plain.append(fraction(numerator.body, denominator.body) ?: return null)
                        index = denominator.end
                    }

                    "text" -> {
                        val body = readGroup(formula, command.end) ?: return null
                        if (body.body.isEmpty() || !body.body.all(::isOrdinary)) return null
                        plain.append(body.body)
                        index = body.end
                    }

                    else -> {
                        plain.append(Symbols[command.name] ?: return null)
                        index = command.end
                    }
                }
            }

            char == '_' || char == '^' -> {
                val argument = readArgument(formula, index + 1) ?: return null
                val atom = readAtom(argument.body)?.takeIf { it.isNotEmpty() } ?: return null
                flush(plain, spans)
                spans += if (char == '_') MathSpan.Subscript(atom) else MathSpan.Superscript(atom)
                index = argument.end
            }

            isOrdinary(char) -> {
                plain.append(char)
                index++
            }

            else -> return null
        }
    }
    flush(plain, spans)
    return spans
}

/**
 * A formula fragment that has to come out as flat text — a subscript's body, a fraction's side.
 * `null` when it needs a shifted baseline of its own, which is a nesting this doesn't draw.
 */
private fun readAtom(fragment: String): String? =
    readFormula(fragment)?.singleOrNull()?.let { it as? MathSpan.Plain }?.text

/** `\frac{a}{b}` flattened — parenthesised when a side is more than one atom, so `(a+b)/2` reads right. */
private fun fraction(numerator: String, denominator: String): String? {
    val top = readAtom(numerator)?.takeIf { it.isNotEmpty() } ?: return null
    val bottom = readAtom(denominator)?.takeIf { it.isNotEmpty() } ?: return null
    return "${parenthesise(top)}/${parenthesise(bottom)}"
}

private fun parenthesise(side: String): String =
    if (side.any { it == ' ' || it in "+-×÷·/±" }) "($side)" else side

private class Command(val name: String, val end: Int)

/** Reads `\name`, or a one-character control such as `\,`. */
private fun readCommand(source: String, backslash: Int): Command? {
    var index = backslash + 1
    if (index >= source.length) return null
    if (!source[index].isLetter()) return Command(source[index].toString(), index + 1)
    while (index < source.length && source[index].isLetter()) index++
    return Command(source.substring(backslash + 1, index), index)
}

private class Fragment(val body: String, val end: Int)

/** Reads `{…}`, including nested braces. */
private fun readGroup(source: String, open: Int): Fragment? {
    if (open >= source.length || source[open] != '{') return null
    var depth = 0
    var index = open
    while (index < source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return Fragment(source.substring(open + 1, index), index + 1)
            }
        }
        index++
    }
    return null
}

/** The body a `_` or `^` applies to: a braced group, or the single character/command after it. */
private fun readArgument(source: String, start: Int): Fragment? {
    if (start >= source.length) return null
    if (source[start] == '{') return readGroup(source, start)
    if (source[start] == '\\') {
        val command = readCommand(source, start) ?: return null
        return Fragment(source.substring(start, command.end), command.end)
    }
    if (!isOrdinary(source[start])) return null
    return Fragment(source[start].toString(), start + 1)
}

/**
 * Characters a formula may carry as themselves. `& # { } $ ^ _ \` are all left out on purpose.
 *
 * `%` is in, and has to be: it is a LaTeX comment character, so models escape it as `\%` — and
 * CommonMark counts `%` among the punctuation a backslash escapes, which means the parser eats
 * that backslash and hands us a bare `%` long before any of this runs.
 */
private fun isOrdinary(char: Char): Boolean =
    char.isLetterOrDigit() || char == ' ' || char in "+-=()[].,:;/<>!?'|*\"%" || char.code > 127

private fun flush(plain: StringBuilder, spans: MutableList<MathSpan>) {
    if (plain.isEmpty()) return
    spans += MathSpan.Plain(plain.toString())
    plain.clear()
}

private fun List<MathSpan>.mergeAdjacentPlain(): List<MathSpan> {
    val merged = mutableListOf<MathSpan>()
    forEach { span ->
        val previous = merged.lastOrNull()
        if (span is MathSpan.Plain && previous is MathSpan.Plain) {
            merged[merged.lastIndex] = MathSpan.Plain(previous.text + span.text)
        } else {
            merged += span
        }
    }
    return merged
}

/** The one-for-one commands, the whole vocabulary of them — see [mathSpans] for why it is this short. */
private val Symbols: Map<String, String> = mapOf(
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "approx" to "≈", "infty" to "∞",
    "," to " ", ";" to " ", ":" to " ", " " to " ", "%" to "%",
    // \quad is a wide space, and models use it to separate two equations on one line — a single
    // space would run them together, so it gets the em space it means.
    "quad" to "\u2003", "qquad" to "\u2003\u2003",
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
    "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
    "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)
