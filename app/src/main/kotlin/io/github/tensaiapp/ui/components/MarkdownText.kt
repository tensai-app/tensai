package io.github.tensaiapp.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Shared, stateless — commonmark's [Parser] is safe to reuse across calls, so one instance serves every [MarkdownText] in the app. */
private val markdownParser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

/**
 * Which typographic scale block headings are drawn at (TEN-89).
 *
 * The rest of the rendering already follows [MarkdownText]'s `style`; headings are the one
 * construct that used to reach past it into the theme, which is fine for a reply drawn at
 * `bodyLarge` and wrong inside a card of secondary copy.
 */
enum class MarkdownHeadings {
    /** The theme's own display scale — what an assistant reply's headings look like. */
    Display,

    /** Derived from the caller's `style`, so a `##` stays inside the block's own scale. */
    Compact,
}

/**
 * Renders [markdown] as Compose UI (TEN-33): headings, paragraphs with bold/italic/inline code,
 * fenced/indented code blocks (mono, copy button), bullet/ordered lists, links (opened via
 * [androidx.compose.ui.platform.LocalUriHandler], the default behavior of a [LinkAnnotation.Url]
 * span) and GFM tables (horizontally scrollable). Inline `$…$` maths are resolved on the way —
 * see [mathSpans], which decides what is a formula and what is a dollar sign.
 *
 * Backed by [commonmark](https://github.com/commonmark/commonmark-java) rather than a ready-made
 * Compose markdown-renderer library: commonmark is a small, pure-Java parser with no UI opinions
 * of its own, so every rendering decision below stays under this file's (and the DS's) control
 * instead of fighting a third-party renderer's theming API to match code blocks/tables/links
 * pixel-for-pixel — and it's one well-audited dependency rather than a whole Compose rendering
 * framework layered on top of ours. It also happens to be exactly what streaming needs: the
 * CommonMark spec defines well-formed behavior for *unterminated* constructs (an open fence, an
 * unclosed `*`), so re-parsing the whole (short, chat-message-length) string on every growing chunk
 * never crashes or throws on a still-incomplete document — see `MarkdownText`'s
 * `MarkdownStreamingTest` for the concrete "renders every prefix of a full response" check.
 *
 * V1 covers exactly the constructs TEN-33 asked for — no blockquotes, images, footnotes,
 * strikethrough, or nested lists beyond one level. Extend [MarkdownBlock]/[appendInlineNodes] if a
 * future ticket needs them.
 *
 * @param style the body copy every paragraph, list and table cell is drawn at — and, when
 *   [headings] is [MarkdownHeadings.Compact], what headings are sized from.
 * @param headings see [MarkdownHeadings]. [ThinkingBlock] is the one caller that compacts them.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    headings: MarkdownHeadings = MarkdownHeadings.Display,
) {
    val document = remember(markdown) { markdownParser.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        document.children().forEach { block -> MarkdownBlock(node = block, color = color, style = style, headings = headings) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, color: Color, style: TextStyle, headings: MarkdownHeadings, modifier: Modifier = Modifier) {
    when (node) {
        is Heading -> Text(
            text = node.inlineAnnotatedString(),
            style = headingStyle(level = node.level, base = style, headings = headings),
            color = color,
            modifier = modifier,
        )

        is Paragraph -> MarkdownParagraph(node = node, color = color, style = style, modifier = modifier)

        is FencedCodeBlock -> MarkdownCodeBlock(code = node.literal.trimEnd('\n'), modifier = modifier)
        is IndentedCodeBlock -> MarkdownCodeBlock(code = node.literal.trimEnd('\n'), modifier = modifier)

        is BulletList -> MarkdownList(node = node, ordered = false, color = color, style = style, headings = headings, modifier = modifier)
        is OrderedList -> MarkdownList(node = node, ordered = true, color = color, style = style, headings = headings, modifier = modifier)

        is TableBlock -> MarkdownTable(node = node, color = color, style = style, modifier = modifier)

        is ThematicBreak -> HorizontalDivider(modifier = modifier)

        // Any other container (e.g. a block quote) — not one of TEN-33's required constructs, so
        // just fall through to its children rather than dropping the text on the floor.
        else -> node.children().forEach { MarkdownBlock(it, color, style, headings, modifier) }
    }
}

/**
 * A paragraph, drawn around its display formula when it has one and as ordinary inline copy
 * otherwise.
 *
 * [displayMathScope] answers `null` for everything that is not a `$$…$$` run spread over several
 * inline nodes, so the common path is unchanged. When it does answer, the copy before the opening
 * delimiter and after the closing one is still drawn the normal way — that is what keeps a bold
 * label above the formula, `**Formule quadratique :**`, from costing the formula its rendering.
 *
 * Shared with [MarkdownList] so a formula inside a list item reads the same as one at the top.
 */
@Composable
private fun MarkdownParagraph(node: Node, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val scope = displayMathScope(node)
    val text = if (scope == null) {
        node.inlineAnnotatedString()
    } else {
        paragraphAroundMaths(
            node = node,
            scope = scope,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            linkColor = MaterialTheme.colorScheme.primary,
        )
    }
    if (text.isNotBlank()) Text(text = text, style = style, color = color, modifier = modifier)
}

/**
 * The joint: [node]'s copy up to the opening delimiter, then [scope]'s formula, then the copy
 * after the closing one — one string, because they are one paragraph.
 *
 * Split out of [MarkdownParagraph] and free of any composable so a JVM test can assert on the
 * string this actually builds rather than on the spans that go into it. What that buys is the one
 * thing spans cannot show: the `'\n'` [appendInlineNodes] draws for the hard break between a bold
 * label and its formula. See `InlineMathTest`.
 */
internal fun paragraphAroundMaths(
    node: Node,
    scope: DisplayMathScope,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    appendInlineNodes(node.firstChild, codeBackground, linkColor, stopAt = scope.open)
    appendMathSpans(scope.spans)
    appendInlineNodes(scope.close.next, codeBackground, linkColor)
}

/**
 * A heading's style. [MarkdownHeadings.Display] is the theme's scale, unchanged since TEN-33.
 * [MarkdownHeadings.Compact] scales [base] instead — the multipliers are small on purpose: inside
 * [ThinkingBlock] a `##` is a marker in a wall of reasoning, not a title competing with the reply
 * underneath it.
 */
@Composable
private fun headingStyle(level: Int, base: TextStyle, headings: MarkdownHeadings): TextStyle = when (headings) {
    MarkdownHeadings.Display -> when (level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }.copy(fontWeight = FontWeight.Bold)

    MarkdownHeadings.Compact -> {
        val scale = when (level) {
            1 -> 1.15f
            2 -> 1.08f
            else -> 1f
        }
        base.copy(
            fontWeight = FontWeight.Bold,
            fontSize = if (base.fontSize.isSpecified) base.fontSize * scale else base.fontSize,
            lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * scale else base.lineHeight,
        )
    }
}

@Composable
private fun MarkdownList(
    node: ListBlock,
    ordered: Boolean,
    color: Color,
    style: TextStyle,
    headings: MarkdownHeadings,
    modifier: Modifier = Modifier,
) {
    val startNumber = (node as? OrderedList)?.markerStartNumber ?: 1
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        node.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${startNumber + index}." else "•",
                    style = style,
                    color = color,
                    modifier = Modifier.widthIn(min = 20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    item.children().forEach { child ->
                        when (child) {
                            is Paragraph -> MarkdownParagraph(node = child, color = color, style = style)
                            is BulletList -> MarkdownList(child, ordered = false, color = color, style = style, headings = headings, modifier = Modifier.padding(top = 4.dp))
                            is OrderedList -> MarkdownList(child, ordered = true, color = color, style = style, headings = headings, modifier = Modifier.padding(top = 4.dp))
                            else -> MarkdownBlock(child, color, style, headings)
                        }
                    }
                }
            }
        }
    }
}

/** Fenced/indented code block per the DS: surface-variant background, JetBrains Mono, top-right copy button, horizontal scroll for long lines. */
@Composable
private fun MarkdownCodeBlock(code: String, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Surface(shape = TensaiShapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp)) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                        scope.launch {
                            delay(1400)
                            copied = false
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = stringResource(if (copied) R.string.markdown_code_copied else R.string.markdown_copy_code),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = code,
                fontFamily = TensaiMono,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * A GFM table, laid out with a small custom [Layout]: every cell is measured unconstrained, the
 * widest cell in each column and the tallest cell in each row set that column's width/row's
 * height, and cells are placed on that grid — [Row.horizontalScroll] around it is what makes wide
 * tables scroll rather than squeeze, per the DS.
 */
@Composable
private fun MarkdownTable(node: TableBlock, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val rows = node.children().flatMap { section -> section.children() }.filterIsInstance<TableRow>()
    val headerColumnCount = rows.firstOrNull()?.children()?.count() ?: return
    if (headerColumnCount == 0) return

    Row(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEachIndexed { rowIndex, row ->
                    val cells = row.children().filterIsInstance<TableCell>()
                    for (columnIndex in 0 until headerColumnCount) {
                        val cell = cells.getOrNull(columnIndex)
                        Text(
                            text = cell?.inlineAnnotatedString() ?: AnnotatedString(""),
                            style = if (rowIndex == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                            color = color,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            },
        ) { measurables, _ ->
            val placeables = measurables.map { it.measure(Constraints()) }
            val colWidths = IntArray(headerColumnCount)
            val rowHeights = IntArray(rows.size)
            placeables.forEachIndexed { index, placeable ->
                val r = index / headerColumnCount
                val c = index % headerColumnCount
                colWidths[c] = maxOf(colWidths[c], placeable.width)
                rowHeights[r] = maxOf(rowHeights[r], placeable.height)
            }
            layout(colWidths.sum(), rowHeights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until headerColumnCount) {
                        placeables[r * headerColumnCount + c].placeRelative(x, y)
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

@Composable
private fun Node.inlineAnnotatedString(): AnnotatedString {
    val inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString { appendInlineNodes(firstChild, inlineCodeBackground, linkColor) }
}

/**
 * Appends the inline nodes from [start] up to, but not including, [stopAt] — `null` meaning "to
 * the end of the run". [stopAt] is what lets a paragraph's copy be drawn around a display formula
 * rather than instead of it, see [MarkdownParagraph].
 */
private fun AnnotatedString.Builder.appendInlineNodes(
    start: Node?,
    codeBackground: Color,
    linkColor: Color,
    stopAt: Node? = null,
) {
    var node = start
    while (node != null && node !== stopAt) {
        when (node) {
            is MarkdownTextNode -> appendMaths(node.literal)
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is Code -> {
                withStyle(SpanStyle(fontFamily = TensaiMono, fontSize = 13.sp, background = codeBackground)) {
                    append(" ${node.literal} ")
                }
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlineNodes(node.firstChild, codeBackground, linkColor) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlineNodes(node.firstChild, codeBackground, linkColor) }
            is Link -> {
                // Only web links are live: `javascript:`, `intent:`, `file:` and the rest render as plain text.
                if (!isWebLink(node.destination)) {
                    appendInlineNodes(node.firstChild, codeBackground, linkColor)
                } else {
                    val linkStyle = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold))
                    withLink(LinkAnnotation.Url(node.destination, linkStyle)) { appendInlineNodes(node.firstChild, codeBackground, linkColor) }
                }
            }
            // Any other inline node (e.g. an image) — fall through to its children, if any.
            else -> appendInlineNodes(node.firstChild, codeBackground, linkColor)
        }
        node = node.next
    }
}

/**
 * Appends one commonmark text run, with its `$…$` maths drawn rather than spelled (TEN-90). This
 * is the only place [mathSpans] is called from, which is what keeps `$PATH` in a `bash` block —
 * a literal on the code node, never a text run — out of its reach.
 *
 * Sub/superscripts are a baseline shift at `0.75.em` rather than Unicode's ᵢ/² code points: those
 * cover barely any of the alphabet (there is no subscript `B`, and `$H_B$` is exactly what the
 * model writes), and mixing real code points with shifted spans in one line looks like a bug. `em`
 * keeps them proportional to whatever [MarkdownText]'s `style` is, so they shrink with the rest
 * inside [ThinkingBlock].
 */
private fun AnnotatedString.Builder.appendMaths(literal: String) = appendMathSpans(mathSpans(literal))

/** Draws already-read [spans] — see [appendMaths] for the sub/superscript rationale. */
private fun AnnotatedString.Builder.appendMathSpans(spans: List<MathSpan>) {
    for (span in spans) {
        when (span) {
            is MathSpan.Plain -> append(span.text)
            is MathSpan.Subscript ->
                withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Subscript)) { append(span.text) }
            is MathSpan.Superscript ->
                withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Superscript)) { append(span.text) }
        }
    }
}

private fun Node.children(): List<Node> {
    val result = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        result.add(child)
        child = child.next
    }
    return result
}

/** True for an `http://` or `https://` destination (any case), the only schemes a Markdown link may open. */
internal fun isWebLink(destination: String?): Boolean {
    val d = destination?.trim() ?: return false
    return d.startsWith("http://", ignoreCase = true) || d.startsWith("https://", ignoreCase = true)
}
