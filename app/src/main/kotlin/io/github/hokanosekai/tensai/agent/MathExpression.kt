package io.github.hokanosekai.tensai.agent

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** What is wrong with an expression, worded for the model: it is sent back as the tool error. */
class MathException(message: String) : Exception(message)

/**
 * Safe arithmetic for `calculate` (TEN-175): a recursive-descent parser over doubles, no eval and no
 * scripting engine, so the only thing an expression can do is compute a number.
 *
 * Precedence, loosest first: `+ -`, `* / %`, unary minus, `^` (right-associative, so `-2^2` is -4 and
 * `2^3^2` is 512). Trig functions work in radians, `log` is base 10.
 */
object MathExpression {

    private const val MAX_LENGTH = 500
    private const val MAX_DEPTH = 50

    private val constants = mapOf("pi" to Math.PI, "e" to Math.E)

    private val unary: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x -> sqrt(x) },
        "abs" to { x -> abs(x) },
        "round" to { x ->
            if (x.isNaN() || x.isInfinite()) throw MathException("round needs a finite number")
            // Half up, as people count; `roundToLong` would clamp a large value to Long.MAX_VALUE.
            floor(x + 0.5)
        },
        "floor" to { x -> floor(x) },
        "ceil" to { x -> ceil(x) },
        "log" to { x -> log10(x) },
        "ln" to { x -> ln(x) },
        "sin" to { x -> sin(x) },
        "cos" to { x -> cos(x) },
        "tan" to { x -> tan(x) },
    )

    private val variadic: Map<String, (List<Double>) -> Double> = mapOf(
        "min" to { xs -> xs.reduce { a, b -> min(a, b) } },
        "max" to { xs -> xs.reduce { a, b -> max(a, b) } },
    )

    /** @throws MathException on a syntax error, an unknown name, or a result that is not a finite number. */
    fun evaluate(expression: String): Double {
        if (expression.isBlank()) throw MathException("empty expression")
        if (expression.length > MAX_LENGTH) throw MathException("expression too long (max $MAX_LENGTH characters)")
        val value = Parser(expression).parse()
        if (value.isNaN()) throw MathException("undefined result (for example sqrt or log of a negative number)")
        if (value.isInfinite()) throw MathException("result is too large")
        return value
    }

    /** Whole numbers without a decimal point, anything else to 10 significant digits. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        val rounded = BigDecimal(value).round(MathContext(10)).stripTrailingZeros()
        return if (abs(value) in 1e-6..1e15) rounded.toPlainString() else rounded.toString().replace("E+", "e").replace("E", "e")
    }

    private class Parser(private val src: String) {
        private var pos = 0
        private var depth = 0

        fun parse(): Double {
            val value = expression()
            if (peek() != null) throw MathException("unexpected '${src[pos]}' at position ${pos + 1}")
            return value
        }

        private fun peek(): Char? {
            while (pos < src.length && src[pos].isWhitespace()) pos++
            return src.getOrNull(pos)
        }

        private fun expression(): Double {
            var left = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; left += term() }
                    '-' -> { pos++; left -= term() }
                    else -> return left
                }
            }
        }

        private fun term(): Double {
            var left = signed()
            while (true) {
                when (peek()) {
                    '*', '×' -> { pos++; left *= signed() }
                    '/', '÷' -> { pos++; left /= nonZero(signed()) }
                    '%' -> { pos++; left %= nonZero(signed()) }
                    else -> return left
                }
            }
        }

        private fun signed(): Double {
            if (++depth > MAX_DEPTH) throw MathException("expression nested too deeply")
            try {
                return when (peek()) {
                    '-' -> { pos++; -signed() }
                    '+' -> { pos++; signed() }
                    else -> power()
                }
            } finally {
                depth--
            }
        }

        /** `^`, or `**` which is how a model often writes a power: right-associative, tighter than unary minus. */
        private fun power(): Double {
            val base = atom()
            val c = peek()
            if (c == '^') pos++ else if (c == '*' && src.getOrNull(pos + 1) == '*') pos += 2 else return base
            return base.pow(signed())
        }

        private fun nonZero(divisor: Double): Double {
            if (divisor == 0.0) throw MathException("division by zero")
            return divisor
        }

        private fun atom(): Double {
            val c = peek() ?: throw MathException("expression ends too early")
            return when {
                c == '(' -> {
                    pos++
                    val value = expression()
                    expect(')')
                    value
                }
                c.isDigit() || c == '.' -> number()
                c.isLetter() -> name()
                else -> throw MathException("unexpected '$c' at position ${pos + 1}")
            }
        }

        private fun expect(char: Char) {
            if (peek() != char) throw MathException("missing '$char' at position ${pos + 1}")
            pos++
        }

        private fun number(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // An exponent only counts when digits follow: `2e` is the number 2 and a stray `e`, which is then rejected.
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                var p = pos + 1
                if (p < src.length && (src[p] == '+' || src[p] == '-')) p++
                if (p < src.length && src[p].isDigit()) {
                    while (p < src.length && src[p].isDigit()) p++
                    pos = p
                }
            }
            val text = src.substring(start, pos)
            return text.toDoubleOrNull() ?: throw MathException("bad number '$text'")
        }

        private fun name(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val id = src.substring(start, pos).lowercase()
            if (peek() != '(') {
                return constants[id] ?: throw MathException("unknown name '$id'. Constants: pi, e")
            }
            pos++
            val args = mutableListOf<Double>()
            if (peek() != ')') {
                do {
                    args += expression()
                    val more = peek() == ','
                    if (more) pos++
                } while (more)
            }
            expect(')')
            unary[id]?.let { fn ->
                if (args.size != 1) throw MathException("$id takes 1 argument, got ${args.size}")
                return fn(args[0])
            }
            variadic[id]?.let { fn ->
                if (args.size < 2) throw MathException("$id takes at least 2 arguments, got ${args.size}")
                return fn(args)
            }
            throw MathException("unknown function '$id'. Functions: ${(unary.keys + variadic.keys).joinToString()}")
        }
    }
}
