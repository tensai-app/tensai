package io.github.hokanosekai.tensai.agent

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Unit conversions for `calculate` (TEN-175). Every unit is a factor to its family's base unit, except
 * temperature, which has an offset and is handled apart.
 */
object UnitConversion {

    private class Unit(val family: String, val factor: Double)

    private val units = HashMap<String, Unit>()

    private fun family(name: String, vararg entries: Pair<String, Double>) {
        entries.forEach { (names, factor) -> names.split(',').forEach { units[it] = Unit(name, factor) } }
    }

    init {
        family(
            "length",
            "mm,millimeter,millimeters" to 0.001, "cm,centimeter,centimeters" to 0.01,
            "m,meter,meters,metre,metres" to 1.0, "km,kilometer,kilometers,kilometre,kilometres" to 1000.0,
            "in,inch,inches" to 0.0254, "ft,foot,feet" to 0.3048, "yd,yard,yards" to 0.9144,
            "mi,mile,miles" to 1609.344, "nmi,nauticalmile,nauticalmiles" to 1852.0,
        )
        family(
            "mass",
            "mg,milligram,milligrams" to 0.000001, "g,gram,grams" to 0.001,
            "kg,kilogram,kilograms" to 1.0, "t,tonne,tonnes" to 1000.0,
            "oz,ounce,ounces" to 0.028349523125, "lb,lbs,pound,pounds" to 0.45359237, "st,stone" to 6.35029318,
        )
        family(
            "volume",
            "ml,milliliter,milliliters,millilitre,millilitres" to 0.001, "cl,centiliter,centilitre" to 0.01,
            "l,liter,liters,litre,litres" to 1.0, "m3,cubicmeter" to 1000.0,
            "tsp,teaspoon,teaspoons" to 0.00492892159375, "tbsp,tablespoon,tablespoons" to 0.01478676478125,
            "floz" to 0.0295735295625, "cup,cups" to 0.2365882365, "pt,pint,pints" to 0.473176473,
            "qt,quart,quarts" to 0.946352946, "gal,gallon,gallons" to 3.785411784,
        )
        family(
            "speed",
            "m/s,mps" to 1.0, "km/h,kmh,kph" to 1.0 / 3.6, "mph,mi/h" to 0.44704,
            "kn,knot,knots" to 1852.0 / 3600.0, "ft/s,fps" to 0.3048,
        )
        family(
            "data",
            "bit,bits" to 0.125, "b,byte,bytes" to 1.0,
            "kb,kilobyte,kilobytes" to 1e3, "mb,megabyte,megabytes" to 1e6,
            "gb,gigabyte,gigabytes" to 1e9, "tb,terabyte,terabytes" to 1e12,
            "kib" to 1024.0, "mib" to 1024.0 * 1024, "gib" to 1024.0 * 1024 * 1024, "tib" to 1024.0 * 1024 * 1024 * 1024,
        )
        family(
            "time",
            "ms,millisecond,milliseconds" to 0.001, "s,sec,second,seconds" to 1.0,
            "min,minute,minutes" to 60.0, "h,hr,hour,hours" to 3600.0,
            "d,day,days" to 86400.0, "wk,week,weeks" to 604800.0,
        )
    }

    private val temperatures = mapOf(
        "c" to "c", "celsius" to "c", "°c" to "c",
        "f" to "f", "fahrenheit" to "f", "°f" to "f",
        "k" to "k", "kelvin" to "k",
    )

    private const val SUPPORTED =
        "Supported (case-insensitive): length, mass, temperature (c, f, k), volume, speed, data (mb = megabytes, never megabits; kb = 1000 B, kib = 1024 B), " +
            "time up to weeks. pt, qt, gal, cup and floz are US units"

    private fun key(unit: String) = unit.trim().lowercase()

    /** @throws MathException on an unknown unit or two units of different families. */
    fun convert(value: Double, from: String, to: String): Double {
        val a = key(from)
        val b = key(to)
        val ta = temperatures[a]
        val tb = temperatures[b]
        if (ta != null || tb != null) {
            if (ta == null || tb == null) throw MathException("cannot convert between temperature and ${if (ta == null) a else b}")
            val celsius = when (ta) {
                "f" -> (value - 32) * 5 / 9
                "k" -> value - 273.15
                else -> value
            }
            return when (tb) {
                "f" -> celsius * 9 / 5 + 32
                "k" -> celsius + 273.15
                else -> celsius
            }
        }
        val ua = units[a] ?: throw MathException("unknown unit '$from'. $SUPPORTED")
        val ub = units[b] ?: throw MathException("unknown unit '$to'. $SUPPORTED")
        if (ua.family != ub.family) throw MathException("cannot convert ${ua.family} ($from) to ${ub.family} ($to)")
        return value * ua.factor / ub.factor
    }
}

/** A time in one IANA zone shown in another, for `calculate` (TEN-175). */
object TimeZoneConversion {

    private val shown = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // STRICT: 2026-02-30 is refused, not clamped to the 28th.
    private val dateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)
    private val timeOnly = DateTimeFormatter.ofPattern("H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)

    /** @param time `HH:mm` (today in [from]) or `yyyy-MM-dd HH:mm`, `T` accepted for the space. */
    fun convert(time: String, from: ZoneId, to: ZoneId, today: LocalDate): String {
        val source = parse(time.trim(), from, today)
        return "${show(source)} = ${show(source.withZoneSameInstant(to))}"
    }

    fun zone(id: String): ZoneId = try {
        ZoneId.of(id.trim())
    } catch (e: DateTimeException) {
        throw MathException("unknown time zone '$id', use an IANA id like Europe/Paris or America/New_York")
    }

    private fun show(t: ZonedDateTime) = "${t.format(shown)} ${t.zone.id} (UTC${t.offset.id.replace("Z", "+00:00")})"

    private fun parse(time: String, zone: ZoneId, today: LocalDate): ZonedDateTime {
        val normalized = time.replace('T', ' ')
        try {
            val local = if (normalized.contains(' ')) {
                LocalDateTime.parse(normalized, dateTime)
            } else {
                LocalDateTime.of(today, LocalTime.parse(normalized, timeOnly))
            }
            val zoned = local.atZone(zone)
            // In a clock-change gap `atZone` silently moves the time forward: say so instead.
            if (zoned.toLocalDateTime() != local) {
                throw MathException("$time does not exist in ${zone.id} (the clocks skip it), pick another time")
            }
            return zoned
        } catch (e: DateTimeParseException) {
            throw MathException("bad time '$time', use HH:mm or yyyy-MM-dd HH:mm")
        }
    }
}
