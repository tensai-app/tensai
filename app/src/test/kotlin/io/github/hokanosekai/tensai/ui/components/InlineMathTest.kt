package io.github.hokanosekai.tensai.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [mathSpans] reads out of a model's LaTeX, and — more to the point — what it refuses to
 * read (TEN-90).
 *
 * The samples come from a real conversation on device: asked for a logic puzzle, a 2B model
 * answers with `$17 \times 24 = 408$` and `($H_B$)` whether or not anything asked it for maths.
 * The reading is kept out of the composable and free of any Compose type, same as `SamplingText`:
 * it is the part that can be quietly wrong, and the only part a JVM unit test can reach.
 */
class InlineMathTest {

    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

    @Test
    fun anInlineProductIsReadAsOperatorsAndNumbers() {
        assertEquals(
            listOf(MathSpan.Plain("17 × 24 = 408")),
            mathSpans("\$17 \\times 24 = 408\$"),
        )
    }

    @Test
    fun aSubscriptedVariableKeepsItsSurroundingProse() {
        assertEquals(
            listOf(MathSpan.Plain("(H"), MathSpan.Subscript("B"), MathSpan.Plain(")")),
            mathSpans("(\$H_B\$)"),
        )
    }

    @Test
    fun aPriceIsNotAFormula() {
        // Two dollars in one sentence and no LaTeX between them: prose, not maths.
        val prose = "The plan costs \$5 a month and \$50 a year."

        assertEquals(listOf(MathSpan.Plain(prose)), mathSpans(prose))
    }

    @Test
    fun aShellVariableInACodeBlockIsNeverReachedAtAll() {
        // The exclusion is structural, not a rule inside mathSpans: a fenced block carries its
        // content as a literal with no Text child, so walking the tree the way MarkdownText does
        // never offers `$PATH` to the reader at all — while the paragraph above it, same `$`, is
        // read.
        val markdown = "Set \$x_1\$ first.\n\n```bash\necho \$PATH \$HOME\n```\n"

        assertEquals(
            listOf(MathSpan.Plain("Set x"), MathSpan.Subscript("1"), MathSpan.Plain(" first.")),
            drawnSpans(markdown),
        )
        assertEquals(
            "the block's own text is handed to the code renderer untouched",
            "echo \$PATH \$HOME\n",
            (parser.parse(markdown).lastChild as FencedCodeBlock).literal,
        )
    }

    @Test
    fun anInlineCodeSpanIsNeverReachedEither() {
        assertEquals(
            listOf(MathSpan.Plain("Run "), MathSpan.Plain(" to see it.")),
            drawnSpans("Run `echo \$PATH_HOME` to see it."),
        )
    }

    @Test
    fun anOpenDelimiterWithNoCloseStaysLiteralAndSwallowsNothing() {
        // What every generation looks like between the token that wrote `$H_B` and the one that
        // will close it — if this ate the rest of the run, the reply would flicker as it streams.
        assertEquals(
            listOf(MathSpan.Plain("A sees \$H_B and C sees nothing yet")),
            mathSpans("A sees \$H_B and C sees nothing yet"),
        )
    }

    @Test
    fun everyPrefixOfAStreamingReplyIsReadableAndNeverGrows() {
        val full = "A sees \$H_B\$ and \$H_C\$, so \$17 \\times 24 = 408\$ holds."

        for (length in 0..full.length) {
            val prefix = full.take(length)
            val spans = mathSpans(prefix)
            assertTrue(
                "reading `$prefix` may only shorten it, never invent text",
                spans.sumOf { it.text.length } <= prefix.length,
            )
        }
    }

    @Test
    fun aFormulaNeverSpansALineBreak() {
        val text = "costs \$5\nor \$6"

        assertEquals(listOf(MathSpan.Plain(text)), mathSpans(text))
    }

    @Test
    fun latexOutsideTheVocabularyIsLeftExactlyAsTheModelWroteIt() {
        for (source in listOf(
            "\$\\int_0^1 f(x)dx\$",
            "\$\\sum_{i=0}^{n} a_i\$",
            "\$\$\\begin{matrix} a & b \\end{matrix}\$\$",
            "\$\\sqrt{2}\$",
        )) {
            assertEquals(
                "a wrong rendering is worse than none, so this stays source",
                listOf(MathSpan.Plain(source)),
                mathSpans(source),
            )
        }
    }

    @Test
    fun theVocabularyItDoesReadCoversWhatASmallModelActuallyWrites() {
        assertEquals(
            listOf(MathSpan.Plain("α ≤ β ≠ γ ± ∞")),
            mathSpans("\$\\alpha \\le \\beta \\ne \\gamma \\pm \\infty\$"),
        )
        assertEquals(listOf(MathSpan.Plain("1/2")), mathSpans("\$\\frac{1}{2}\$"))
        assertEquals(listOf(MathSpan.Plain("(a + b)/2")), mathSpans("\$\\frac{a + b}{2}\$"))
        assertEquals(listOf(MathSpan.Plain("x"), MathSpan.Superscript("2")), mathSpans("\$x^2\$"))
        assertEquals(listOf(MathSpan.Plain("x"), MathSpan.Subscript("10")), mathSpans("\$x_{10}\$"))
    }

    @Test
    fun displayMathNeedsNoMarkerBecauseNobodyWritesAPriceWithTwoDollars() {
        assertEquals(listOf(MathSpan.Plain("408")), mathSpans("\$\$408\$\$"))
        assertEquals(listOf(MathSpan.Plain("17 × 24")), mathSpans("\$\$17 \\times 24\$\$"))
    }

    @Test
    fun textWithNoDollarAtAllComesBackWhole() {
        assertEquals(listOf(MathSpan.Plain("Nothing to read here.")), mathSpans("Nothing to read here."))
        assertEquals(emptyList<MathSpan>(), mathSpans(""))
    }

    @Test
    fun theSentenceThatPromptedTheTicketReadsEndToEnd() {
        // Verbatim from the reported conversation, bold label and all.
        val markdown = "**A voit :** Le chapeau de B (\$H_B\$) et le chapeau de C (\$H_C\$)."

        assertEquals(
            listOf(
                MathSpan.Plain("A voit :"),
                MathSpan.Plain(" Le chapeau de B (H"),
                MathSpan.Subscript("B"),
                MathSpan.Plain(") et le chapeau de C (H"),
                MathSpan.Subscript("C"),
                MathSpan.Plain(")."),
            ),
            drawnSpans(markdown),
        )
    }

    @Test
    fun aDisplayBlockWithItsDelimitersOnTheirOwnLinesIsReadAcrossTheBreaks() {
        // Verbatim from the device. commonmark splits this into Text("$$"), SoftLineBreak, the
        // body, SoftLineBreak, Text("$$") — three runs mathSpans can never see together, which is
        // why displayMathSpans flattens the paragraph first.
        val markdown = "$$\n\\text{index} = (\\text{hash}(key) + k^2) \\% \\text{size}\n$$\n"

        assertEquals(
            listOf(
                MathSpan.Plain("index = (hash(key) + k"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(") % size"),
            ),
            paragraphSpans(markdown),
        )
    }

    @Test
    fun aDisplayBlockStillStreamingIsDeclinedRatherThanGuessed() {
        // No closing "$$" has been generated yet. Declining leaves the source on screen, which is
        // what the reader saw a token ago: no flicker, no half-read formula.
        assertNull(displayFormula("$$\n\\text{index} = (\\text{hash}(key)"))
    }

    @Test
    fun aNodeThatIsNotTextBetweenTheDelimitersDeclines() {
        // The all-or-nothing rule covers the span between the delimiters, not the paragraph — but
        // inside that span it is as strict as ever.
        assertNull(
            "an inline code span keeps its structural exclusion, display block or not",
            displayFormula("\$\$\n`echo \$PATH`\n\$\$"),
        )
        assertNull(
            // Dropping the code node instead of declining would leave `x =  + 1`, which reads as a
            // perfectly plausible formula and is not what the model wrote. Decline is the only
            // honest answer.
            "a code span among real content must decline the whole formula, not be skipped",
            displayFormula("\$\$\nx = `\$PATH` + 1\n\$\$"),
        )
        assertNull(
            "emphasis inside the formula is not something this draws",
            displayFormula("\$\$\n**a** = b\n\$\$"),
        )
        assertNull(
            "a second formula in the same paragraph is not one display block",
            displayFormula("\$\$\na = b\n\$\$ and \$\$c = d\$\$"),
        )
    }

    @Test
    fun aBoldLabelAboveTheBlockNoLongerCostsTheFormulaItsRendering() {
        // Device QA. Two trailing spaces after the label make a hard break, so commonmark puts the
        // label's StrongEmphasis and the whole block in one paragraph. Scoping the rule to what
        // lies between the delimiters is what lets the label be a label and the formula a formula.
        val markdown = "**Formule quadratique :**  \n\$\$\nax_1^2 + bx_1 + c = 0\n\$\$\n"

        assertEquals(
            listOf(
                MathSpan.Plain("Formule quadratique :"),
                MathSpan.Plain("ax"),
                MathSpan.Subscript("1"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" + bx"),
                MathSpan.Subscript("1"),
                MathSpan.Plain(" + c = 0"),
            ),
            paragraphSpans(markdown),
        )
    }

    @Test
    fun theLabelAndItsFormulaAreOneStringWithTheLineBreakStillBetweenThem() {
        // MathSpan says what is read; it cannot say what is *drawn* between the label and the
        // formula, and the joint is the whole of what this diff adds. So this asserts on the
        // string paragraphAroundMaths actually builds — the same call the composable makes, with
        // colours it does not otherwise care about.
        val paragraph = paragraphOf("**Formule quadratique :**  \n\$\$\nax_1^2 + bx_1 + c = 0\n\$\$\n")
        val scope = displayMathScope(paragraph)
        assertNotNull(scope)

        val drawn = paragraphAroundMaths(paragraph, scope!!, Color.Unspecified, Color.Unspecified)

        assertEquals(
            "the hard break survives as a real newline, and nothing on either side is swallowed",
            "Formule quadratique :\nax12 + bx1 + c = 0",
            drawn.text,
        )
        assertEquals(
            "the label is still drawn bold, not flattened into the formula",
            listOf("Formule quadratique :"),
            drawn.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { drawn.text.substring(it.start, it.end) },
        )
        assertEquals(
            "the subscripts and the superscript keep their own shifted spans inside that string",
            listOf("1", "2", "1"),
            drawn.spanStyles.filter { it.item.baselineShift != null }.map { drawn.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun aMultiLineAlignmentIsStillLeftAsSourceEvenAsADisplayBlock() {
        assertNull(displayFormula("$$\n\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}\n$$"))
    }

    @Test
    fun textWrappersAreUnwrappedBecauseThatIsHowTheseModelsLabelATerm() {
        assertEquals(listOf(MathSpan.Plain("cost = 17 × 24")), mathSpans("$\\text{cost} = 17 \\times 24$"))
        // `\text{…}` is verbatim, spaces inside the braces included, so this is written the way
        // LaTeX is actually written: no space before the wrapper, the space inside it.
        assertEquals(listOf(MathSpan.Plain("50% of the total")), mathSpans("\$50\\% \\text{of the total}\$"))
    }

    @Test
    fun aBarePercentIsOrdinaryBecauseCommonMarkAlreadyAteTheEscapingBackslash() {
        // Models write `\%`; CommonMark counts `%` as escapable punctuation and hands the renderer
        // a bare one. Both spellings have to read, or the device's own formula falls back to source.
        val rendered = listOf(MathSpan.Plain("x"), MathSpan.Superscript("2"), MathSpan.Plain(" % y"))

        assertEquals(rendered, mathSpans("\$x^2 % y\$"))
        assertEquals(rendered, mathSpans("\$x^2 \\% y\$"))
    }

    @Test
    fun delimitersPaddedWithSpacesStillOpenAFormula() {
        // The hole that let 19 green tests ship a screen where nothing rendered. `$ x_1 $` is how
        // these models write inline maths most of the time, and refusing a run that opens or
        // closes on a space refused all of it.
        assertEquals(
            listOf(MathSpan.Plain("x"), MathSpan.Subscript("1")),
            mathSpans("\$ x_1 \$"),
        )
        assertEquals(
            listOf(MathSpan.Plain("ax"), MathSpan.Superscript("2"), MathSpan.Plain(" + bx + c = 0")),
            mathSpans("\$ ax^2 + bx + c = 0 \$"),
        )
    }

    @Test
    fun paddingIsTrimmedSoAPaddedFormulaDrawsNoWiderThanATightOne() {
        assertEquals(mathSpans("\$x_1\$"), mathSpans("\$ x_1 \$"))
        assertEquals(mathSpans("\$\$a^2 = b\$\$"), mathSpans("\$\$  a^2 = b  \$\$"))
    }

    @Test
    fun aDisplayRunPaddedInsideItsDoubleDollarsIsRead() {
        assertEquals(
            listOf(
                MathSpan.Plain("a"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" + b"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" = c"),
                MathSpan.Superscript("2"),
            ),
            mathSpans("\$\$ a^2 + b^2 = c^2 \$\$"),
        )
    }

    @Test
    fun proseNamingTwoShellVariablesIsPaddedOnOneSideOnlyAndStaysLiteral() {
        // What the padding rule buys once it stops being "no padding at all". Each of these holds
        // a `_` or `^`, so the marker rule alone would let every one of them through and mangle it.
        for (prose in listOf(
            "export \$HOME_DIR before \$PATH",
            "il a payé \$5_000 puis \$10",
            "set \$x_1 and \$y then run",
        )) {
            assertEquals(listOf(MathSpan.Plain(prose)), mathSpans(prose))
        }
    }

    @Test
    fun aDisplayBlockPaddedAndSeparatedByQuadsReadsEndToEnd() {
        // Verbatim from the QA reply (Qwen3-1.7B): padded delimiters on their own lines, two
        // equations separated by \quad \text{et} \quad.
        val markdown = "\$\$\nax_1^2 + bx_1 + c = 0 \\quad \\text{et} \\quad ax_2^2 + bx_2 + c = 0\n\$\$\n"

        assertEquals(
            listOf(
                MathSpan.Plain("ax"),
                MathSpan.Subscript("1"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" + bx"),
                MathSpan.Subscript("1"),
                // \u2003 is the em space \quad maps to — spelled out, because an em space sitting
                // invisibly in a string literal is a trap for whoever reads this next.
                MathSpan.Plain(" + c = 0 \u2003 et \u2003 ax"),
                MathSpan.Subscript("2"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" + bx"),
                MathSpan.Subscript("2"),
                MathSpan.Plain(" + c = 0"),
            ),
            paragraphSpans(markdown),
        )
    }

    @Test
    fun theModulusBlockFromTheDeviceIsReadInsteadOfLeftAsSource() {
        // Verbatim from the reported conversation. `\mod` was the only command in the whole
        // corpus outside the vocabulary, and one missing word made readFormula decline, which made
        // displayMathScope decline, which put the `$$` delimiters themselves on the reader's
        // screen.
        val markdown = "\$\$\n\\text{index} = (\\text{hash}(key) + k^2) \\mod \\text{tableSize}\n\$\$\n"

        assertEquals(
            listOf(
                MathSpan.Plain("index = (hash(key) + k"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(") mod tableSize"),
            ),
            paragraphSpans(markdown),
        )
    }

    @Test
    fun operatorNamesComeOutAsWordsAndTakeTheirSpacingFromTheSource() {
        // `\mod` is a word, not a glyph, and like `\times` it leans on the spaces already in the
        // source — which is exactly why it needs no layout and can be in the vocabulary at all.
        assertEquals(listOf(MathSpan.Plain("17 mod 5 = 2")), mathSpans("\$17 \\bmod 5 = 2\$"))
        assertEquals(
            listOf(MathSpan.Plain("log"), MathSpan.Subscript("2"), MathSpan.Plain("(n) = max(a, b)")),
            mathSpans("\$\\log_2(n) = \\max(a, b)\$"),
        )
        assertEquals(
            listOf(
                MathSpan.Plain("sin"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" x + cos"),
                MathSpan.Superscript("2"),
                MathSpan.Plain(" x = 1"),
            ),
            mathSpans("\$\\sin^2 x + \\cos^2 x = 1\$"),
        )
    }

    @Test
    fun theSingleGlyphSymbolsCoverSetsLogicAndArrows() {
        assertEquals(
            listOf(MathSpan.Plain("∀ x ∈ A, x ∉ B ⇒ A ∩ B = ∅")),
            mathSpans("\$\\forall x \\in A, x \\notin B \\Rightarrow A \\cap B = \\emptyset\$"),
        )
        // The spacing around ⟨ ⟩ is the source's own, same as everywhere else here: this maps
        // commands to glyphs and never inserts a space of its own.
        assertEquals(
            listOf(MathSpan.Plain("f : A → B ⇔ ⟨ x, y ⟩ ≠ ∂")),
            mathSpans("\$f : A \\to B \\Leftrightarrow \\langle x, y \\rangle \\ne \\partial\$"),
        )
    }

    @Test
    fun mathrmIsUnwrappedLikeTextButOnlyWhereMathModeWouldNotEatASpace() {
        assertEquals(listOf(MathSpan.Plain("d/dx")), mathSpans("\$\\frac{\\mathrm{d}}{\\mathrm{d}x}\$"))
        assertEquals(
            listOf(MathSpan.Plain("E = mc"), MathSpan.Superscript("2")),
            mathSpans("\$E = \\mathrm{mc}^2\$"),
        )
        // LaTeX sets `\mathrm{km h}` as "kmh" — math mode drops the space. Drawing "km h" would be
        // a formula the source does not have, so the wrapper is refused instead of diverging.
        val spaced = "\$\\mathrm{km h}^{-1}\$"
        assertEquals(listOf(MathSpan.Plain(spaced)), mathSpans(spaced))
    }

    @Test
    fun aWiderVocabularyStillRefusesEverythingThatNeedsThePageInTwoDimensions() {
        for (source in listOf(
            "\$\\sqrt{2}\$",
            "\$\\sqrt{a^2 + b^2}\$",
            "\$\\int_0^1 f(x)dx\$",
            "\$\\sum_{i=0}^{n} a_i\$",
            "\$\\prod_{k=1}^{n} k\$",
            // \lim is the reason \max and \min are in the vocabulary and it is not: its bound sits
            // *under* it, and there is no baseline shift that draws that.
            "\$\\lim_{x \\to 0} \\frac{\\sin x}{x}\$",
            // The congruence spelling, which parenthesises its argument rather than naming an
            // operator — a different mechanism, so it is out until something asks for it.
            "\$a \\equiv b \\pmod{n}\$",
            "\$\\mathbb{R}^n\$",
            "\$\\vec{v} \\cdot \\vec{w}\$",
        )) {
            assertEquals(
                "a longer vocabulary must not become a licence to render approximately",
                listOf(MathSpan.Plain(source)),
                mathSpans(source),
            )
        }
        assertNull(displayFormula("\$\$\n\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}\n\$\$"))
        assertNull(displayFormula("\$\$\n\\begin{pmatrix} a & b \\end{pmatrix}\n\$\$"))
    }

    /**
     * The spans `MarkdownText` would draw for [markdown]: it calls [mathSpans] for commonmark
     * `Text` nodes and for nothing else, so collecting those literals in document order is the
     * same projection the renderer builds — without needing a device to build it.
     */
    private fun drawnSpans(markdown: String): List<MathSpan> =
        literalsBetween(parser.parse(markdown).firstChild, stopAt = null).flatMap { mathSpans(it) }

    /**
     * What `MarkdownParagraph` would draw for [markdown]'s last block: the copy before the opening
     * delimiter, the formula, then the copy after the closing one — and the plain run-by-run
     * reading when there is no display scope at all. Same shape as the composable.
     */
    private fun paragraphSpans(markdown: String): List<MathSpan> {
        val paragraph = parser.parse(markdown).lastChild
        val scope = displayMathScope(paragraph)
            ?: return literalsBetween(paragraph.firstChild, stopAt = null).flatMap { mathSpans(it) }
        return literalsBetween(paragraph.firstChild, stopAt = scope.open).flatMap { mathSpans(it) } +
            scope.spans +
            literalsBetween(scope.close.next, stopAt = null).flatMap { mathSpans(it) }
    }

    /** [markdown]'s last block, which every display-block sample here is. */
    private fun paragraphOf(markdown: String): Node = parser.parse(markdown).lastChild

    private fun displayFormula(markdown: String): List<MathSpan>? =
        displayMathScope(parser.parse(markdown).lastChild)?.spans

    /** Every text literal from [start] up to, but not including, [stopAt] — mirrors the renderer's walk. */
    private fun literalsBetween(start: Node?, stopAt: Node?): List<String> {
        val literals = mutableListOf<String>()
        var child = start
        while (child != null && child !== stopAt) {
            if (child is MarkdownTextNode) literals += child.literal else literals += literalsBetween(child.firstChild, null)
            child = child.next
        }
        return literals
    }
}
