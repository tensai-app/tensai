package io.github.tensaiapp.ui.components

import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownTextNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which asterisks [parseMarkdown] reads as emphasis (TEN-168).
 *
 * The samples come from the phone: asked whether 391 is prime, Qwen3-1.7B reasons with `7*55` and
 * `17*23`, and plain CommonMark paired those asterisks into italics, in the reasoning and the reply.
 */
class MarkdownEmphasisTest {

    @Test
    fun arithmeticInAReasoningLineKeepsItsAsterisks() {
        val document = parseMarkdown("7*55 is 385, and 391-385=6, so try 11*35=385")

        assertEquals(0, document.count<Emphasis>())
        assertEquals("7*55 is 385, and 391-385=6, so try 11*35=385", document.plainText())
    }

    @Test
    fun aProductBetweenDigitsIsNotEmphasis() {
        val document = parseMarkdown("17*23=391 and 7*55=385")

        assertEquals(0, document.count<Emphasis>())
        assertEquals("17*23=391 and 7*55=385", document.plainText())
    }

    @Test
    fun aDoubleAsteriskBetweenDigitsIsNotBold() {
        val document = parseMarkdown("2**10 and 2**5")

        assertEquals(0, document.count<StrongEmphasis>())
        assertEquals("2**10 and 2**5", document.plainText())
    }

    @Test
    fun anAsteriskInInlineCodeIsNotEscaped() {
        val document = parseMarkdown("`2*3` then 17*23 then `c*4`")

        assertEquals(listOf("2*3", "c*4"), document.all<Code>().map { it.literal })
        assertEquals(0, document.count<Emphasis>())
    }

    @Test
    fun anAsteriskInAFencedBlockIsNotEscaped() {
        val document = parseMarkdown("17*23=391\n\n```\nx = 2*3\n```\n")

        assertEquals("x = 2*3\n", document.all<FencedCodeBlock>().single().literal)
    }

    @Test
    fun anAsteriskInAnIndentedBlockIsNotEscaped() {
        val document = parseMarkdown("17*23=391\n\n    x = 2*3\n")

        assertEquals("x = 2*3\n", document.all<IndentedCodeBlock>().single().literal)
    }

    @Test
    fun anAsteriskInRawHtmlIsNotEscaped() {
        val document = parseMarkdown("""17*23 and <span title="2*3">x</span>""")

        assertEquals("""<span title="2*3">""", document.all<HtmlInline>().first().literal)
    }

    @Test
    fun anAsteriskInAnAutolinkIsNotEscaped() {
        val document = parseMarkdown("17*23, see <https://example.com/2*3>")

        assertEquals("https://example.com/2*3", document.all<Link>().single().destination)
    }

    @Test
    fun anAsteriskInCodeInATableCellIsNotEscaped() {
        val document = parseMarkdown("| op | value |\n| --- | --- |\n| `2*3` | 17*23 |\n")

        assertEquals(listOf("2*3"), document.all<Code>().map { it.literal })
        assertEquals("17*23", document.all<TableCell>().last().plainText())
    }

    @Test
    fun boldGluedToAWordStaysBold() {
        assertEquals("API", parseMarkdown("the **API**s it calls").all<StrongEmphasis>().single().plainText())
        assertEquals("H", parseMarkdown("**H**ello").all<StrongEmphasis>().single().plainText())
    }

    @Test
    fun ordinaryEmphasisStillRenders() {
        assertEquals(1, parseMarkdown("*a*").count<Emphasis>())
        assertEquals(1, parseMarkdown("**b**").count<StrongEmphasis>())

        val inSentence = parseMarkdown("x *y* z")
        assertEquals(1, inSentence.count<Emphasis>())
        assertEquals("y", inSentence.all<Emphasis>().single().plainText())
    }

    @Test
    fun emphasisNextToArithmeticStillRenders() {
        val document = parseMarkdown("since 17*23=391, *391 is not prime*")

        assertEquals("391 is not prime", document.all<Emphasis>().single().plainText())
        assertTrue(document.plainText().startsWith("since 17*23=391, "))
    }

    @Test
    fun aBulletIsStillAList() {
        val document = parseMarkdown("* item\n* other")

        assertEquals(1, document.count<BulletList>())
        assertEquals(0, document.count<Emphasis>())
    }

    @Test
    fun aSpacedProductIsLeftAsIs() {
        val document = parseMarkdown("2 * 3 = 6 and 4 * 5 = 20")

        assertEquals(0, document.count<Emphasis>())
        assertEquals("2 * 3 = 6 and 4 * 5 = 20", document.plainText())
    }

    private inline fun <reified T : Node> Node.all(): List<T> = descendants().filterIsInstance<T>()

    private fun Node.descendants(): List<Node> {
        val found = mutableListOf(this)
        var child = firstChild
        while (child != null) {
            found += child.descendants()
            child = child.next
        }
        return found
    }

    private inline fun <reified T : Node> Node.count(): Int = all<T>().size

    private fun Node.plainText(): String = all<MarkdownTextNode>().joinToString("") { it.literal }
}
