package io.github.tensaiapp.ui.screens.bench

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.bench.ctxLabel
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.tensaiTints
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One line of a [DecodeChart]: (context tokens, tok/s) points, smallest context first. B's is dashed with square markers. */
internal data class ChartSeries(val points: List<Pair<Int, Double>>, val color: Color, val dashed: Boolean = false, val squareMarkers: Boolean = false)

/**
 * A line chart of speed against context, drawn on a Canvas: x linear in context tokens from 0 to the largest point, a
 * y axis from 0 to a round value over the highest, four gridlines with mono labels, mono context labels at the points.
 * [valueLabels] writes each value over its dot (a single run only, two lines would collide).
 */
@Composable
internal fun DecodeChart(series: List<ChartSeries>, valueLabels: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.tensaiTints.outline
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = TextStyle(fontFamily = TensaiMono, fontSize = 10.sp, color = labelColor)
    val valueStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val xs = series.flatMap { s -> s.points.map { it.first } }.distinct().sorted()
    val maxX = xs.maxOrNull()?.coerceAtLeast(1) ?: 1
    val axis = yAxis(series.flatMap { s -> s.points.map { it.second } }.maxOrNull() ?: 0.0)
    val maxY = axis.max

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val left = 34.dp.toPx()
        val right = 12.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = 22.dp.toPx()
        val plot = Size(size.width - left - right, size.height - top - bottom)
        fun x(ctx: Int) = left + plot.width * ctx / maxX
        fun y(v: Double) = top + plot.height * (1 - (v / maxY).toFloat())

        for (i in 0..axis.lines) {
            val v = maxY * i / axis.lines
            val gy = y(v)
            drawLine(grid, Offset(left, gy), Offset(left + plot.width, gy), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(axisText(v), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), gy - text.size.height / 2f))
        }
        xs.forEach { ctx ->
            val text = measurer.measure(ctxLabel(ctx), labelStyle)
            val tx = (x(ctx) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(tx, top + plot.height + 6.dp.toPx()))
        }
        series.forEach { s ->
            if (s.points.size > 1) {
                val path = Path().apply {
                    s.points.forEachIndexed { i, (ctx, v) -> if (i == 0) moveTo(x(ctx), y(v)) else lineTo(x(ctx), y(v)) }
                }
                drawPath(
                    path,
                    s.color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
                    ),
                )
            }
            s.points.forEach { (ctx, v) ->
                marker(Offset(x(ctx), y(v)), s.color, surface, s.squareMarkers)
                if (valueLabels) {
                    val text = measurer.measure(String.format(Locale.ROOT, "%.1f", v), valueStyle)
                    val tx = (x(ctx) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                    drawText(text, topLeft = Offset(tx, (y(v) - text.size.height - 6.dp.toPx()).coerceAtLeast(0f)))
                }
            }
        }
    }
}


/** A dot (or square) with a ring of the card's color, so it reads over the line. */
private fun DrawScope.marker(at: Offset, color: Color, ring: Color, square: Boolean) {
    val r = 4.dp.toPx()
    if (square) {
        drawRect(ring, Offset(at.x - r - 1.5f, at.y - r - 1.5f), Size(2 * r + 3f, 2 * r + 3f))
        drawRect(color, Offset(at.x - r, at.y - r), Size(2 * r, 2 * r))
    } else {
        drawCircle(ring, r + 1.5f, at)
        drawCircle(color, r, at)
    }
}

/** The y axis: its top and how many gridlines divide it, each on a round value. */
internal data class YAxis(val max: Double, val lines: Int)

/**
 * The y axis for data topping at [value]: 10% over it for the labels above the dots, rounded up to a step of 1, 2, 2.5
 * or 5 times a power of ten about a fifth of that (9.8 → 12 in steps of 2, 40.3 → 45 in steps of 5), so the curve fills
 * the chart. Gridlines on a divisor of the steps, 2 to 6 of them, or every step.
 */
internal fun yAxis(value: Double): YAxis {
    if (value <= 0) return YAxis(1.0, 4)
    val top = value * 1.1
    val raw = top / 5
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(5.0, 2.5, 2.0, 1.0).map { it * magnitude }.first { it <= raw }
    val steps = ceil(top / step - 1e-9).toInt()
    val lines = (2..6).filter { steps % it == 0 }.maxOrNull() ?: steps
    return YAxis(steps * step, lines)
}

private fun axisText(v: Double): String = if (v == floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
