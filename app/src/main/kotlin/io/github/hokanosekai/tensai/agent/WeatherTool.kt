package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.websearch.HttpFetcher
import io.github.hokanosekai.websearch.SearchError
import java.io.IOException
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/** GET [url] and parse the body as a JSON object, every failure as a [SearchError]. */
internal suspend fun HttpFetcher.getJson(url: String, timeoutMs: Long = HttpFetcher.DEFAULT_TIMEOUT_MS): JSONObject {
    val response = try {
        get(url, mapOf("Accept" to "application/json"), timeoutMs)
    } catch (e: IOException) {
        throw SearchError.Network(e.message ?: e.javaClass.simpleName, e)
    }
    when {
        response.status == 429 -> throw SearchError.RateLimited(response.retryAfterSeconds)
        !response.isSuccess -> throw SearchError.Network("HTTP ${response.status}")
    }
    return try {
        JSONObject(response.body)
    } catch (e: JSONException) {
        throw SearchError.FormatChanged("not JSON")
    }
}

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * `weather`: the forecast of one day (default today) from Open-Meteo (no key, CC BY 4.0, free for non-commercial
 * use, which an open-source free app is). The place is geocoded first, then the forecast is read for it.
 *
 * The day is a name the model can get right ("today", "tomorrow", a weekday, a date) rather than a count from
 * today, which small models misuse. It is resolved against the place's own local date, and the answer says which
 * date it is for, so a mix-up shows in the text.
 */
class WeatherTool(
    private val http: HttpFetcher,
    private val geocodingBase: String = "https://geocoding-api.open-meteo.com",
    private val forecastBase: String = "https://api.open-meteo.com",
) : Tool {
    override val name = "weather"
    override val description =
        "Gives the weather forecast for a place on one day. place is a city name, or latitude,longitude."
    override val parametersSchema =
        """{"type":"object","properties":{"place":{"type":"string"},"day":{"type":"string","description":"today, tomorrow, a weekday like friday, or YYYY-MM-DD. Default today"}},"required":["place"]}"""
    override val tier = ToolTier.Network

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val place = args.stringArg("place").orEmpty().trim()
        if (place.isEmpty()) return ToolResult.error("place is empty")
        val dayText = args.stringArg("day")?.trim().orEmpty().ifEmpty { "today" }
        val day = Day.parse(dayText)
            ?: return ToolResult.error("day must be today, tomorrow, a weekday like friday, or a date YYYY-MM-DD, got '$dayText'")
        return try {
            val spot = locate(place) ?: return ToolResult.error("no place found for '$place'", destination = DESTINATION)
            val forecast = http.getJson(
                "$forecastBase/v1/forecast?latitude=${spot.latitude}&longitude=${spot.longitude}" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=$FORECAST_DAYS",
                FORECAST_TIMEOUT_MS,
            )
            format(spot, day, forecast)
        } catch (e: SearchError) {
            ToolResult.error(e.toModelMessage(DESTINATION), destination = DESTINATION)
        } catch (e: JSONException) {
            ToolResult.error(SearchError.FormatChanged("unexpected forecast").toModelMessage(DESTINATION), destination = DESTINATION)
        }
    }

    /** A day as the model names it, resolved to a date once the place's local today is known. */
    internal sealed interface Day {
        fun resolve(today: LocalDate): LocalDate

        /** What the answer calls it: "tomorrow", "friday", or nothing for a bare date. */
        val label: String?

        data object Today : Day {
            override fun resolve(today: LocalDate) = today
            override val label = "today"
        }

        data object Tomorrow : Day {
            override fun resolve(today: LocalDate) = today.plusDays(1)
            override val label = "tomorrow"
        }

        /** The next such weekday, today included. */
        data class Weekday(val day: DayOfWeek) : Day {
            override fun resolve(today: LocalDate) = today.with(TemporalAdjusters.nextOrSame(day))
            override val label = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
        }

        data class On(val date: LocalDate) : Day {
            override fun resolve(today: LocalDate) = date
            override val label: String? = null
        }

        companion object {
            fun parse(text: String): Day? {
                val t = text.trim().lowercase()
                when (t) {
                    "today" -> return Today
                    "tomorrow" -> return Tomorrow
                }
                WEEKDAYS[t]?.let { return Weekday(it) }
                return try {
                    On(LocalDate.parse(t))
                } catch (e: DateTimeParseException) {
                    null
                }
            }

            private val WEEKDAYS: Map<String, DayOfWeek> = DayOfWeek.entries.flatMap { day ->
                val full = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
                listOf(full to day, full.take(3) to day)
            }.toMap()
        }
    }

    private class Spot(val label: String, val latitude: Double, val longitude: Double)

    private suspend fun locate(place: String): Spot? {
        COORDINATES.matchEntire(place)?.let { match ->
            val (lat, lon) = match.destructured
            if (lat.toDouble() in -90.0..90.0 && lon.toDouble() in -180.0..180.0) {
                return Spot("$lat, $lon", lat.toDouble(), lon.toDouble())
            }
            return null
        }
        val json = http.getJson("$geocodingBase/v1/search?name=${urlEncode(place)}&count=1&language=en&format=json")
        val hit = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val label = listOf(hit.optString("name"), hit.optString("admin1"), hit.optString("country"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
        return Spot(label, hit.getDouble("latitude"), hit.getDouble("longitude"))
    }

    private fun format(spot: Spot, day: Day, forecast: JSONObject): ToolResult {
        val daily = forecast.optJSONObject("daily")
        val dates = daily?.optJSONArray("time")
        if (daily == null || dates == null || dates.length() == 0) {
            return ToolResult.error(SearchError.FormatChanged("no daily forecast").toModelMessage(DESTINATION), destination = DESTINATION)
        }
        val current = forecast.optJSONObject("current")
        // The place's own date: the API answers in its time zone ("timezone=auto"), the phone's may differ.
        val today = try {
            LocalDate.parse((current?.optString("time")?.takeIf { it.isNotEmpty() } ?: dates.getString(0)).substringBefore('T'))
        } catch (e: DateTimeParseException) {
            LocalDate.parse(dates.getString(0))
        }
        val target = day.resolve(today)
        val index = (0 until dates.length()).firstOrNull { dates.getString(it) == target.toString() }
            ?: return ToolResult.error(
                "no forecast for $target (it covers ${dates.getString(0)} to ${dates.getString(dates.length() - 1)})",
                destination = DESTINATION,
            )

        val named = target.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + target
        val text = buildString {
            append(spot.label).append(" (Open-Meteo) · forecast for ")
            if (day.label != null && day !is Day.Weekday) append(day.label).append(", ")
            append(named).append(":\n")
            // Current conditions only describe today.
            if (target == today && current != null) {
                append("now: ").append(number(current, "temperature_2m")).append("C")
                append(", feels ").append(number(current, "apparent_temperature"))
                append(", ").append(describe(current.optInt("weather_code", -1)))
                append(", wind ").append(number(current, "wind_speed_10m")).append(" km/h")
                append(", humidity ").append(current.optInt("relative_humidity_2m")).append("%\n")
            }
            append(describe(daily.optJSONArray("weather_code")?.optInt(index, -1) ?: -1))
            append(", ").append(daily.optJSONArray("temperature_2m_min")?.optDouble(index)?.let(::round) ?: "?")
            append(" to ").append(daily.optJSONArray("temperature_2m_max")?.optDouble(index)?.let(::round) ?: "?").append("C")
            val rain = daily.optJSONArray("precipitation_sum")?.optDouble(index, 0.0) ?: 0.0
            val chance = daily.optJSONArray("precipitation_probability_max")?.optInt(index, -1) ?: -1
            if (rain > 0.0 || chance > 0) {
                append(", rain ").append(round(rain)).append(" mm")
                if (chance >= 0) append(" (").append(chance).append("%)")
            }
        }
        return ToolResult.ok(text, destination = DESTINATION)
    }

    private fun number(json: JSONObject, key: String): String = round(json.optDouble(key))

    /** One decimal at most, whole numbers without one, "?" for a value the API left out (NaN). */
    private fun round(value: Double): String {
        if (value.isNaN()) return "?"
        val tenth = Math.round(value * 10) / 10.0
        return if (tenth == Math.floor(tenth)) tenth.toInt().toString() else tenth.toString()
    }

    companion object {
        private const val DESTINATION = "Open-Meteo"
        /** Open-Meteo gives up to 16 days, enough for any date a user asks about. */
        private const val FORECAST_DAYS = 16

        /** Open-Meteo forecasts can take 10 s+ under load; geocoding (10 s) + this stay within the Network tier's 30 s. */
        private const val FORECAST_TIMEOUT_MS = 20_000L
        private val COORDINATES = Regex("(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)")

        /** WMO weather interpretation codes, as Open-Meteo returns them. */
        internal fun describe(code: Int): String = when (code) {
            0 -> "clear"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71 -> "light snow"
            73 -> "snow"
            75 -> "heavy snow"
            77 -> "snow grains"
            80, 81 -> "rain showers"
            82 -> "violent rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorm"
            96, 99 -> "thunderstorm with hail"
            else -> "unknown conditions"
        }
    }
}
