package io.github.hokanosekai.tensai.ui.components

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
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
    fun aParagraphHoldingAnythingButTextAndBreaksIsDeclined() {
        assertNull(
            "an inline code span keeps its structural exclusion, display block or not",
            displayFormula("\$\$\n`echo \$PATH`\n\$\$"),
        )
        assertNull(
            "emphasis means this paragraph is prose that happens to start with $$",
            displayFormula("$$\n**a** = b\n$$"),
        )
        assertNull(
            "a second formula in the same paragraph is not one display block",
            displayFormula("\$\$\na = b\n\$\$ and \$\$c = d\$\$"),
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

    /**
     * The spans `MarkdownText` would draw for [markdown]: it calls [mathSpans] for commonmark
     * `Text` nodes and for nothing else, so collecting those literals in document order is the
     * same projection the renderer builds — without needing a device to build it.
     */
    private fun drawnSpans(markdown: String): List<MathSpan> =
        textLiterals(parser.parse(markdown)).flatMap { mathSpans(it) }

    /**
     * What `MarkdownParagraph` would draw for [markdown]'s last block: the display reading first,
     * the run-by-run one when it declines. Same two lines as the composable.
     */
    private fun paragraphSpans(markdown: String): List<MathSpan> {
        val paragraph = parser.parse(markdown).lastChild
        return displayMathSpans(paragraph) ?: textLiterals(paragraph).flatMap { mathSpans(it) }
    }

    private fun displayFormula(markdown: String): List<MathSpan>? =
        displayMathSpans(parser.parse(markdown).lastChild)

    private fun textLiterals(node: Node): List<String> {
        val literals = mutableListOf<String>()
        var child = node.firstChild
        while (child != null) {
            if (child is MarkdownTextNode) literals += child.literal else literals += textLiterals(child)
            child = child.next
        }
        return literals
    }
}
