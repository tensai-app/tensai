package io.github.tensaiapp.agent

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `weather` and `wikipedia` (TEN-181) against recorded-shape Open-Meteo and Wikimedia JSON. */
class WeatherWikipediaToolTest {

    private val geocoding = """{"results":[{"id":3031582,"name":"Bordeaux","latitude":44.84044,"longitude":-0.5805,"country":"France","admin1":"Nouvelle-Aquitaine"}]}"""

    private val forecast = """
        {"current":{"temperature_2m":14.3,"apparent_temperature":12.0,"relative_humidity_2m":81,"precipitation":0.0,"weather_code":61,"wind_speed_10m":18.4},
         "daily":{"time":["2026-10-17","2026-10-18"],"weather_code":[63,3],"temperature_2m_max":[15.2,13.0],"temperature_2m_min":[9.1,8.0],
                  "precipitation_sum":[2.14,0.0],"precipitation_probability_max":[60,10]}}
    """.trimIndent()

    private fun run(tool: Tool, args: String) = runBlocking { tool.execute(args) }

    // weather

    @Test fun weatherGeocodesThenReadsTheForecast() {
        val http = FakeHttp(json(geocoding), json(forecast))
        val result = run(WeatherTool(http), """{"place":"Bordeaux"}""")

        assertFalse(result.text, result.isError)
        assertEquals(
            "Bordeaux, Nouvelle-Aquitaine, France (Open-Meteo) \u00b7 forecast for today, Sat 2026-10-17:\n" +
                "now: 14.3C, feels 12, light rain, wind 18.4 km/h, humidity 81%\n" +
                "rain, 9.1 to 15.2C, rain 2.1 mm (60%)",
            result.text,
        )
        assertEquals("Open-Meteo", result.destination)
        assertTrue(http.urls[0].startsWith("https://geocoding-api.open-meteo.com/v1/search?name=Bordeaux&count=1"))
        assertTrue(http.urls[1].contains("latitude=44.84044&longitude=-0.5805"))
        assertTrue(http.urls[1].endsWith("forecast_days=16"))
    }

    @Test fun weatherRetriesOnceOn503ThenGivesUp() {
        val recovered = FakeHttp(status(503), json(forecast))
        assertFalse(run(WeatherTool(recovered), """{"place":"38.72,-9.13"}""").isError)
        assertEquals(2, recovered.urls.size)

        val down = FakeHttp(status(503), status(503))
        assertTrue(run(WeatherTool(down), """{"place":"38.72,-9.13"}""").text.contains("HTTP 503"))
        assertEquals(2, down.urls.size)
    }

    @Test fun weatherTakesCoordinatesWithoutGeocoding() {
        val http = FakeHttp(json(forecast))
        val result = run(WeatherTool(http), """{"place":"38.72, -9.13"}""")

        assertFalse(result.isError)
        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().contains("latitude=38.72&longitude=-9.13"))
        assertTrue(result.text.startsWith("38.72, -9.13 (Open-Meteo) \u00b7 forecast for today"))
    }

    @Test fun weatherCoordinatesAtTheBoundsAndTextThatIsNotCoordinates() {
        val http = FakeHttp(json(forecast))
        assertFalse(run(WeatherTool(http), """{"place":"-90,180"}""").isError)
        assertEquals(1, http.urls.size)
        // a name with a number in it still goes through geocoding
        val named = FakeHttp(json("""{"generationtime_ms":0.3}"""))
        assertTrue(run(WeatherTool(named), """{"place":"Paris 75"}""").text.contains("no place found"))
        assertTrue(named.urls.single().startsWith("https://geocoding-api.open-meteo.com"))
    }

    // `day`: names the model can get right, resolved against the place's own date

    private fun dayRun(day: String?, forecastJson: String = forecast): ToolResult {
        val args = if (day == null) """{"place":"1,1"}""" else """{"place":"1,1","day":"$day"}"""
        return run(WeatherTool(FakeHttp(json(forecastJson))), args)
    }

    @Test fun tomorrowIsLabeledWithItsDateAndHasNoCurrentConditions() {
        val result = dayRun("tomorrow")

        assertEquals(
            "1, 1 (Open-Meteo) · forecast for tomorrow, Sun 2026-10-18:\novercast, 8 to 13C, rain 0 mm (10%)",
            result.text,
        )
        assertFalse(result.text.contains("now:"))
    }

    @Test fun todayIsTheDefaultAndKeepsTheCurrentConditions() {
        val byDefault = dayRun(null)
        assertEquals(dayRun("today").text, byDefault.text)
        assertTrue(byDefault.text.startsWith("1, 1 (Open-Meteo) · forecast for today, Sat 2026-10-17:\nnow: 14.3C"))
        assertEquals(dayRun("TODAY").text, byDefault.text)
    }

    @Test fun aDateAndAWeekdayResolveToTheirDay() {
        assertTrue(dayRun("2026-10-18").text.contains("forecast for Sun 2026-10-18:"))
        // 2026-10-17 is a Saturday: "saturday" is today, "sunday" and "mon" the next ones
        assertTrue(dayRun("saturday").text.contains("forecast for Sat 2026-10-17:\nnow:"))
        assertTrue(dayRun("Sunday").text.contains("forecast for Sun 2026-10-18:"))
    }

    @Test fun theDayIsResolvedAgainstThePlacesOwnDate() {
        // It is already the 18th at the place: "tomorrow" is the 19th, and the 17th is no longer in the forecast.
        val ahead = """{"current":{"time":"2026-10-18T01:30","temperature_2m":9.0,"weather_code":3},"daily":{"time":["2026-10-18","2026-10-19"],"weather_code":[3,61],"temperature_2m_max":[12,14],"temperature_2m_min":[7,9]}}"""
        assertTrue(dayRun("tomorrow", ahead).text.contains("forecast for tomorrow, Mon 2026-10-19:\nlight rain, 9 to 14C"))
        assertTrue(dayRun("today", ahead).text.contains("forecast for today, Sun 2026-10-18:\nnow: 9C"))
    }

    @Test fun aDayOutsideTheForecastSaysWhatItCovers() {
        val result = dayRun("2027-01-01")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("no forecast for 2027-01-01 (it covers 2026-10-17 to 2026-10-18)"))
        assertEquals("Open-Meteo", result.destination)
    }

    @Test fun anUnknownDayIsRefusedBeforeAnyRequest() {
        val http = FakeHttp()
        for (bad in listOf("next week", "2026-13-40", "yesterday", "3")) {
            val result = run(WeatherTool(http), """{"place":"Bordeaux","day":"$bad"}""")
            assertTrue(bad, result.isError)
            assertTrue(result.text.contains("day must be today, tomorrow"))
        }
        assertTrue(http.urls.isEmpty())
    }

    @Test fun theForecastRequestCoversSixteenDays() {
        val http = FakeHttp(json(forecast))
        run(WeatherTool(http), """{"place":"1,1","day":"tomorrow"}""")
        assertTrue(http.urls.single().endsWith("forecast_days=16"))
    }

    @Test fun theSchemaTakesADayNotACount() {
        val schema = org.json.JSONObject(WeatherTool(FakeHttp()).parametersSchema).getJSONObject("properties")
        assertTrue(schema.has("day"))
        assertFalse(schema.has("days"))
    }


    @Test fun weatherUnknownPlaceAndBadCoordinates() {
        val none = run(WeatherTool(FakeHttp(json("""{"generationtime_ms":0.3}"""))), """{"place":"Nowhereville"}""")
        assertTrue(none.isError)
        assertTrue(none.text.contains("no place found"))
        assertTrue(run(WeatherTool(FakeHttp()), """{"place":"95,10"}""").isError)
        assertTrue(run(WeatherTool(FakeHttp()), """{"place":"10,181"}""").isError)
        assertTrue(run(WeatherTool(FakeHttp()), """{"place":"-91.5,10"}""").isError)
    }

    @Test fun weatherFailuresAreTypedMessages() {
        assertTrue(run(WeatherTool(FakeHttp(IOException("down"))), """{"place":"Bordeaux"}""").text.contains("network error"))
        assertTrue(run(WeatherTool(FakeHttp(status(429))), """{"place":"Bordeaux"}""").text.contains("rate limiting"))
        assertTrue(run(WeatherTool(FakeHttp(json("<html>"))), """{"place":"Bordeaux"}""").text.contains("format"))
        val result = run(WeatherTool(FakeHttp(json(geocoding), json("""{"current":{"temperature_2m":"x"}}"""))), """{"place":"Bordeaux"}""")
        assertFalse(result.text.isEmpty())
    }

    @Test fun weatherCodesAreDescribed() {
        assertEquals("clear", WeatherTool.describe(0))
        assertEquals("thunderstorm", WeatherTool.describe(95))
        assertEquals("unknown conditions", WeatherTool.describe(1234))
    }

    // wikipedia

    private val search = """{"pages":[{"id":1,"key":"Kotlin_(programming_language)","title":"Kotlin (programming language)","excerpt":"<span class=\"searchmatch\">Kotlin</span> is a language","description":"Programming language"}]}"""

    private val summary = """
        {"title":"Kotlin (programming language)","description":"Programming language","extract":"Kotlin is a cross-platform, statically typed language.",
         "content_urls":{"desktop":{"page":"https://fr.wikipedia.org/wiki/Kotlin_(langage)"}}}
    """.trimIndent()

    @Test fun wikipediaSearchesThenReadsTheSummaryOfTheTopHit() {
        val http = FakeHttp(json(search), json(summary))
        val result = run(WikipediaTool(http, "tensai/test"), """{"query":"kotlin","lang":"fr"}""")

        assertFalse(result.text, result.isError)
        assertEquals(
            "Kotlin (programming language) (Programming language)\n" +
                "Kotlin is a cross-platform, statically typed language.\n" +
                "https://fr.wikipedia.org/wiki/Kotlin_(langage)\n" +
                "source: Wikipedia, CC BY-SA",
            result.text,
        )
        assertEquals("Wikipedia (fr)", result.destination)
        assertEquals("https://fr.wikipedia.org/w/rest.php/v1/search/page?q=kotlin&limit=1", http.urls[0])
        assertEquals("https://fr.wikipedia.org/api/rest_v1/page/summary/Kotlin_%28programming_language%29", http.urls[1])
    }

    @Test fun wikipediaFallsBackToTheSearchHitWhenTheSummaryFails() {
        val result = run(WikipediaTool(FakeHttp(json(search), status(404)), "ua"), """{"query":"kotlin"}""")

        assertFalse(result.isError)
        assertTrue(result.text.contains("Kotlin is a language"))
        assertTrue(result.text.contains("https://en.wikipedia.org/wiki/Kotlin_%28programming_language%29"))
    }

    @Test fun wikipediaNoArticleAndBadLanguage() {
        val none = run(WikipediaTool(FakeHttp(json("""{"pages":[]}""")), "ua"), """{"query":"zzzz"}""")
        assertFalse(none.isError)
        assertEquals("no article found", none.text)
        assertTrue(run(WikipediaTool(FakeHttp(), "ua"), """{"query":"x","lang":"en.evil.com"}""").isError)
    }

    @Test fun wikipediaRefusedUserAgentIsReportedAsBlocked() {
        val result = run(WikipediaTool(FakeHttp(status(403)), "ua"), """{"query":"kotlin"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("blocked"))
        assertEquals("Wikipedia (en)", result.destination)
    }

    @Test fun wikipediaAnyFailureOfTheSummaryFallsBackToTheSearchHit() {
        for (failure in listOf(status(429), status(500), json("<html>not json</html>"), json("""{"extract":"""))) {
            val result = run(WikipediaTool(FakeHttp(json(search), failure), "ua"), """{"query":"kotlin"}""")
            assertFalse(result.text, result.isError)
            assertTrue(result.text.contains("a language"))
        }
    }

    @Test fun wikipediaLanguageCodes() {
        for (lang in listOf("en", "fr", "zh-min-nan", "be-tarask")) {
            val http = FakeHttp(json("""{"pages":[]}"""))
            assertFalse(lang, run(WikipediaTool(http, "ua"), """{"query":"x","lang":"$lang"}""").isError)
        }
        for (lang in listOf("e", "en.evil.com", "en/x", "english-language-code", "en-")) {
            assertTrue(lang, run(WikipediaTool(FakeHttp(), "ua"), """{"query":"x","lang":"$lang"}""").isError)
        }
    }

    @Test fun weatherMissingValuesPrintAQuestionMark() {
        val sparse = """{"current":{"weather_code":0},"daily":{"time":["2026-10-17"],"weather_code":[0],"temperature_2m_max":[null],"temperature_2m_min":[]}}"""
        val result = run(WeatherTool(FakeHttp(json(sparse))), """{"place":"1,1"}""")

        assertFalse(result.isError)
        assertFalse(result.text, result.text.contains("NaN"))
        assertTrue(result.text, result.text.contains("now: ?C, feels ?, clear, wind ? km/h"))
        assertTrue(result.text, result.text.contains("clear, ? to ?C"))
    }

    // no place: the phone's own position

    private val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-17T10:00:00Z"), java.time.ZoneId.of("Europe/Paris"))

    private class FakeLocation(
        private val access: PermissionResult = PermissionResult.Granted,
        private val fixAgeMs: Long = 0L,
        private val nowMs: Long,
    ) : LocationSource {
        var asked = 0
        var fetched = 0

        override fun isGranted() = access == PermissionResult.Granted

        override suspend fun access(): PermissionResult {
            asked++
            return access
        }

        override suspend fun current(): LocationOutcome {
            fetched++
            return LocationOutcome.Found(LocationFix(38.716671, -9.133329, 1200f, nowMs - fixAgeMs))
        }
    }

    private fun hereTool(http: FakeHttp, location: FakeLocation, inForeground: Boolean = true) =
        WeatherTool(http, location = location, inForeground = { inForeground }, clock = clock)

    @Test fun noPlaceForecastsTheLocationWithoutGeocoding() {
        for (args in listOf("{}", """{"place":""}""", """{"place":null}""", """{"day":"today"}""")) {
            val http = FakeHttp(json(forecast))
            val location = FakeLocation(nowMs = clock.millis())
            val result = run(hereTool(http, location), args)

            assertFalse(result.text, result.isError)
            assertEquals(1, http.urls.size)
            assertTrue(http.urls.single().startsWith("https://api.open-meteo.com/v1/forecast?latitude=38.72&longitude=-9.13"))
            assertTrue(result.text, result.text.startsWith("your location (approx. 38.72,-9.13) (Open-Meteo)"))
            assertEquals(1, location.fetched)
        }
    }

    @Test fun hereWordsAreTheLocationToo() {
        for (word in listOf("here", "Here", "current location", "My Location", "near me")) {
            val http = FakeHttp(json(forecast))
            val result = run(hereTool(http, FakeLocation(nowMs = clock.millis())), """{"place":""}""")

            assertFalse(result.text, result.isError)
            assertEquals(word, 1, http.urls.size)
            assertFalse(http.urls.single().contains("geocoding"))
            assertTrue(result.text.startsWith("your location"))
        }
    }

    @Test fun aDeniedLocationPermissionIsRefused() {
        val http = FakeHttp()
        val location = FakeLocation(PermissionResult.Denied, nowMs = clock.millis())
        val result = run(hereTool(http, location), "{}")

        assertTrue(result.refused)
        assertEquals(LocationRefusedText, result.text)
        assertEquals(0, location.fetched)
        assertTrue(http.urls.isEmpty())
    }

    @Test fun aPermanentlyDeniedLocationPermissionIsBlocked() {
        val http = FakeHttp()
        val result = run(hereTool(http, FakeLocation(PermissionResult.PermanentlyDenied, nowMs = clock.millis())), "{}")

        assertTrue(result.refused)
        assertEquals("location", blockedPermission(result.text))
        assertTrue(http.urls.isEmpty())
    }

    @Test fun aStaleFixIsNotForecast() {
        val http = FakeHttp()
        val result = run(hereTool(http, FakeLocation(fixAgeMs = LOCATION_MAX_AGE_MS + 1, nowMs = clock.millis())), "{}")

        assertTrue(result.isError)
        assertEquals(LocationFailure.Stale.message, result.text)
        assertTrue(http.urls.isEmpty())
    }

    @Test fun fromTheBackgroundItFailsBeforeTheDialog() {
        val location = FakeLocation(PermissionResult.Denied, nowMs = clock.millis())
        val result = run(hereTool(FakeHttp(), location, inForeground = false), "{}")

        assertEquals(BACKGROUND_ERROR, result.text)
        assertEquals(0, location.asked)
    }

    @Test fun aNamedCityNeverTouchesTheLocation() {
        val http = FakeHttp(json(geocoding), json(forecast))
        val location = FakeLocation(nowMs = clock.millis())
        val result = run(hereTool(http, location), """{"place":"Bordeaux"}""")

        assertFalse(result.isError)
        assertEquals(2, http.urls.size)
        assertEquals(0, location.asked)
        assertEquals(0, location.fetched)
    }

    @Test fun withoutALocationSourceAnEmptyPlaceIsStillAnError() {
        assertEquals("place is empty", run(WeatherTool(FakeHttp()), "{}").text)
        assertTrue(WeatherTool(FakeHttp()).waitsForUser)
    }
}
