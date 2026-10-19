package io.github.tensaiapp.ui.components

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
 * What is read — every entry below is one Unicode glyph, or one word, or a baseline shift, and
 * nothing on the list needs the page laid out in two dimensions:
 * - the operators `\times \cdot \div \pm \mp \le \leq \ge \geq \ne \neq \approx \infty`,
 * - the relations, arrows, set and logic signs that are one glyph apiece — `\to \Rightarrow
 *   \equiv \in \subseteq \cup \forall \neg \land \partial \langle` and their neighbours; [Symbols]
 *   holds the list,
 * - the operator names, `\mod \bmod \log \ln \sin \max \gcd` and the rest, which come out as the
 *   word they are and lean on the source's own spaces exactly as `\times` does: `a \mod b` is
 *   `a mod b`,
 * - the Greek letters, lower and upper case, including the `\var…` shapes,
 * - the spacing commands `\, \; \: \ `, plus `\quad` and `\qquad` as em spaces,
 * - single subscripts and superscripts, `H_B`, `x^2`, `x_{10}`,
 * - `\frac{a}{b}`, flattened to `a/b` and parenthesised when a side is not a single atom,
 * - `\text{…}`, whose body is taken verbatim — it is what the models here reach for to label a
 *   term, `\text{index} = …`, and dropping the wrapper is the whole of rendering it — and
 *   `\mathrm{…}` the same way, but only for a body with no space in it, because math mode swallows
 *   those and `\mathrm{a b}` sets `ab`.
 *
 * Anything outside that — matrices, integrals, sums and limits with bounds, `\sqrt`, `\pmod`,
 * `\begin`/`\end`, `&` alignments, an unknown command, a stray `{` — makes the *whole* formula
 * fall back to its literal source. What decides is not the length of the list but that one
 * criterion: a radical that has to stretch over its argument, a bound sitting under its operator,
 * a matrix's rows are all two-dimensional, and Unicode with two baseline shifts cannot draw them.
 * Drawing them approximately is the single outcome worse than not drawing them at all, so the list
 * grows only along the layout-free edge and never past it.
 *
 * Three conservative rules keep ordinary prose out of this, and none of them may be traded for
 * another: each catches a class the other two miss, and the classes are named here so the claim
 * can be checked. "$5-$10" is padded symmetrically at both ends and only the first rule refuses
 * it; "export $HOME_DIR before $PATH" carries a `_` and only the second does.
 *
 * A single `$` only opens a formula when the run between it and its closing `$` reads as maths —
 * see [looksLikeMaths]. A `\`, `_` or `^` says so; so does a single atom, `$k$`; so does an
 * operator with an operand on either side of it, `$k = k+1$`. What that leaves out is a price,
 * and it leaves it out on purpose: "costs $5 and $7" holds `5 and ` between the delimiters, which
 * names no operator, and "$5-$10" holds `5-`, whose operator has nothing to its right. Prose sets
 * its `$` against the amount and its separator just before the next one, so the separator lands at
 * an end of the run, where this does not read it.
 *
 * The padding must match at both ends — see [paddingIsSymmetric]. Models write `$ x_1 $` far more
 * often than `$x_1$`, so padding cannot disqualify a run; what it can do is tell maths from prose
 * naming two shell variables, which is padded on one side only.
 *
 * A `$` with no partner at all stays literal, which is what every generation looks like while the
 * token after `$H_B` has not arrived yet, and a formula never spans a line break.
 *
 * `$$…$$` is unambiguous enough to skip that first rule, but not the other two. What is left of a
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

/**
 * Whether a `$…$` run says enough for itself to be worth reading as maths (TEN-90, device QA).
 *
 * A `\`, `_` or `^` is the loudest way it can say so, and for a while it was the only one this
 * accepted — until a phone's own history showed 8 of its 64 inline runs turned away for want of
 * one: `$k$`, `$k = k+1$`, `$340 + 68 = 408$`, `$10 + 7$`. A lone variable and a line of arithmetic
 * are maths in any hand, so two more shapes count: a run that is one atom — see [isAtom] — and a
 * run that spells an operator with an operand on either side — see [hasInfixOperator].
 *
 * The [mathSpans] header lists what stays out; the short of it is that prose leaves its separator
 * at an end of the run, never between two operands.
 */
private fun looksLikeMaths(formula: String, display: Boolean): Boolean =
    display ||
        formula.any { it == '\\' || it == '_' || it == '^' } ||
        formula.isAtom() ||
        formula.hasInfixOperator()

/**
 * Whether this run is the one alphanumeric atom a variable is written as: a lone letter or digit,
 * `$k$`, or anything carrying a digit, `$408$`, `$x1$`.
 *
 * A bare multi-letter word does not count, and that exclusion is the whole of the care here:
 * `echo $PATH$HOME` written outside a code span leaves `PATH` between two dollars, tight at both
 * ends, and nothing else in this file would refuse it.
 */
private fun String.isAtom(): Boolean =
    all(Char::isLetterOrDigit) && (length == 1 || any(Char::isDigit))

/**
 * Whether this run spells one of `= + - * / < >` with an operand on either side of it.
 *
 * `k = k+1` does. The `5-` a price range leaves between the delimiters of "$5-$10" does not, and
 * neither does the `+ 10` of "5 $ + 10 $ de taxes", the French postfix spelling: both put their
 * operator at an end, because the amount owns the `$` next to it. Any operator in the run will do,
 * so `-x + y` still reads on the strength of its `+`.
 */
private fun String.hasInfixOperator(): Boolean = indices.any { at ->
    this[at] in "=+-*/<>" &&
        take(at).any { !it.isWhitespace() } &&
        drop(at + 1).any { !it.isWhitespace() }
}

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

                    "text", "mathrm" -> {
                        val body = readGroup(formula, command.end) ?: return null
                        if (body.body.isEmpty() || !body.body.all(::isOrdinary)) return null
                        // `\text` keeps its spaces; `\mathrm` is math mode and drops them, so
                        // `\mathrm{a b}` sets "ab" and taking the body verbatim would draw a
                        // formula the source does not have. Refuse that one rather than guess.
                        if (command.name == "mathrm" && ' ' in body.body) return null
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

/**
 * The one-for-one commands, the whole vocabulary of them — a command belongs here when it maps to
 * a fixed piece of text and needs no layout of its own. See [mathSpans] for why that is the line.
 */
private val Symbols: Map<String, String> = mapOf(
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "approx" to "≈", "infty" to "∞",
    "," to " ", ";" to " ", ":" to " ", " " to " ", "%" to "%",
    // \quad is a wide space, and models use it to separate two equations on one line — a single
    // space would run them together, so it gets the em space it means.
    "quad" to "\u2003", "qquad" to "\u2003\u2003",
    // Arrows and relations.
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
    "implies" to "⇒", "iff" to "⇔", "mapsto" to "↦",
    "equiv" to "≡", "sim" to "∼", "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    // Sets and logic.
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
    "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
    "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
    "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
    // The remaining single glyphs.
    "ldots" to "…", "dots" to "…", "cdots" to "⋯",
    "partial" to "∂", "nabla" to "∇", "perp" to "⊥", "parallel" to "∥", "angle" to "∠",
    "circ" to "∘", "oplus" to "⊕", "otimes" to "⊗", "langle" to "⟨", "rangle" to "⟩",
    // The operator names — the class this ticket was opened for. A hash-table formula spells
    // `(\text{hash}(key) + k^2) \mod \text{tableSize}`, and that one missing word cost the whole
    // block its rendering. They are words rather than glyphs, and like `\times` they take their
    // spacing from the source: `a \mod b` reads `a mod b`. `\lim`, `\sup` and `\inf` are
    // deliberately absent — they are only ever written with a bound that belongs *under* them,
    // which is the layout this cannot draw.
    "mod" to "mod", "bmod" to "mod", "log" to "log", "ln" to "ln", "exp" to "exp",
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "sec" to "sec", "csc" to "csc", "cot" to "cot",
    "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
    "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh",
    "max" to "max", "min" to "min", "gcd" to "gcd", "lcm" to "lcm",
    "deg" to "deg", "det" to "det", "dim" to "dim",
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
