package io.github.tensaiapp.agent

import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import org.json.JSONException
import org.json.JSONObject

/**
 * The phone actions (TEN-177, v0.3.0 T0): tools that open another app with the request ready, and the app
 * confirms. Nothing is sent from here, so none of them goes through the confirmation gate.
 */

/**
 * An intent as plain data, so the construction of each tool is tested without Android. [AndroidPhoneActions]
 * turns it into an `Intent`.
 *
 * @param extras values are String, Int, Long, Boolean, or a List of String or Int (an array extra).
 * @param scheme with [schemeSpecificPart], a URI built by Android (`Uri.fromParts`) instead of parsed from [data]:
 *   a recipient is never re-read as URI syntax.
 * @param categories extra intent categories, as constant strings.
 * @param subject what no app handles when none does, for the model: "timers".
 */
data class IntentSpec(
    val action: String,
    val data: String? = null,
    val mimeType: String? = null,
    val scheme: String? = null,
    val schemeSpecificPart: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, Any> = emptyMap(),
    val subject: String,
)

/** A launchable app: its label and its package. */
data class InstalledApp(val label: String, val packageName: String)

/** The transport keys `media_control` sends. */
enum class MediaKey(val argName: String) {
    Play("play"),
    Pause("pause"),
    PlayPause("play_pause"),
    Next("next"),
    Previous("previous"),
}

/** No installed app handles the intent. Thrown by [PhoneActions.start] and [PhoneActions.launchApp]. */
class NoHandlerException : Exception()

/** The Android side of the phone actions, so the tools test without Android. */
interface PhoneActions {
    /** Whether tensai is on screen: Android blocks an activity start from the background. */
    val inForeground: Boolean

    /** The label of the app that would handle [spec] (the system's default pick), `null` when none does. */
    fun handlerLabel(spec: IntentSpec): String?

    /** @throws NoHandlerException when no app handles it. */
    fun start(spec: IntentSpec)

    fun launchableApps(): List<InstalledApp>

    /** @throws NoHandlerException when the app has no launch intent any more. */
    fun launchApp(packageName: String)

    /** Whether audio is playing: `pause` has nothing to pause otherwise. */
    val isMusicActive: Boolean

    fun sendMediaKey(key: MediaKey)
}

internal const val BACKGROUND_ERROR =
    "tensai is not on screen and Android blocks opening apps from the background. Tell the user to come back to tensai and ask again."

/** What an opened app answers: the app card reads `opened`, the model reads `status`. */
private fun opened(app: String, set: Boolean, chose: List<String> = emptyList()) = ToolResult.ok(
    JSONObject().put("opened", app).put("status", if (set) "set, shown in $app" else "waiting for the user")
        .apply { if (chose.isNotEmpty()) put("to", chose.joinToString("; ")) }.toString(),
)

private fun refusedNoApp(what: String) =
    ToolResult.refused("no app on this phone handles $what. Tell the user, do not retry.")

private class ArgException(message: String) : Exception(message)

private fun parseArgs(argsJson: String): JSONObject = try {
    JSONObject(argsJson)
} catch (e: JSONException) {
    throw ArgException("arguments are not a JSON object")
}

private fun JSONObject.requiredString(key: String): String =
    optionalString(key) ?: throw ArgException("$key is required")

private fun JSONObject.optionalString(key: String): String? = stringArg(key)?.trim()?.takeIf { it.isNotEmpty() }

/** An integer argument: a JSON number or a numeric string (small models quote numbers), `null` when absent. */
private fun JSONObject.intArg(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = get(key)
    val number = (if (value is Number) value.toDouble() else value.toString().trim().toDoubleOrNull())
        ?: throw ArgException("$key must be a number")
    if (number != Math.floor(number) || number.isInfinite() || number.isNaN()) throw ArgException("$key must be a whole number")
    return number.toInt()
}

private fun JSONObject.boolArg(key: String): Boolean = when (val v = stringArg(key)?.lowercase()) {
    null, "false", "no" -> false
    "true", "yes" -> true
    else -> throw ArgException("$key must be true or false, got '$v'")
}

/** The flow shared by the tools that start an intent: parse, build the spec, check the screen and a handler, start. */
abstract class IntentTool internal constructor(private val phone: PhoneActions) : Tool {
    override val tier = ToolTier.PhoneAction

    /** The app starts the timer or saves the alarm by itself: the result says "set", not "waiting for the user". */
    protected open val setsDirectly = false

    /** @throws ArgException with a message for the model. */
    internal abstract fun build(args: JSONObject): IntentSpec

    /**
     * Turns what the model wrote into what [build] reads, before it: a draft tool swaps a contact name for the
     * number. [chose] collects what was picked for the model ("Jade Martin, mobile").
     * @throws ContactFailure when the lookup ends the call.
     */
    protected open suspend fun resolve(args: JSONObject, chose: MutableList<String>): JSONObject = args

    /** The spec [argsJson] builds, or the message for the model; the part of [execute] that needs no phone. */
    internal fun spec(argsJson: String): Result<IntentSpec> = try {
        Result.success(build(parseArgs(argsJson)))
    } catch (e: ArgException) {
        Result.failure(e)
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val chose = mutableListOf<String>()
        val spec = try {
            val args = parseArgs(argsJson)
            // Before resolve: a contacts lookup may wait on a permission dialog nobody sees from the background.
            if (!phone.inForeground) return ToolResult.error(BACKGROUND_ERROR)
            build(resolve(args, chose))
        } catch (e: ArgException) {
            return ToolResult.error(e.message ?: "invalid arguments")
        } catch (e: ContactFailure) {
            return e.result
        }
        val label = phone.handlerLabel(spec) ?: return refusedNoApp(spec.subject)
        return try {
            phone.start(spec)
            opened(label, setsDirectly, chose)
        } catch (e: NoHandlerException) {
            refusedNoApp(spec.subject)
        }
    }
}

const val MaxTimerSeconds = 24 * 60 * 60

/** Opens Clock with a timer ready, the user starts it. */
class SetTimerTool(phone: PhoneActions) : IntentTool(phone) {
    override val name = "set_timer"
    override val description = "Sets a timer in the Clock app (1 s to 24 h)."
    override val setsDirectly = true
    override val parametersSchema =
        """{"type":"object","properties":{"seconds":{"type":"integer"},"message":{"type":"string"}},"required":["seconds"]}"""

    override fun build(args: JSONObject): IntentSpec {
        val seconds = args.intArg("seconds") ?: throw ArgException("seconds is required")
        if (seconds !in 1..MaxTimerSeconds) throw ArgException("seconds must be between 1 and $MaxTimerSeconds (24 h), got $seconds")
        val extras = mutableMapOf<String, Any>(
            "android.intent.extra.alarm.LENGTH" to seconds,
            "android.intent.extra.alarm.SKIP_UI" to false,
        )
        args.optionalString("message")?.let { extras["android.intent.extra.alarm.MESSAGE"] = it }
        return IntentSpec("android.intent.action.SET_TIMER", extras = extras, subject = "timers")
    }
}

/** `java.util.Calendar` day numbers (Sunday is 1) by full English name or 3-letter abbreviation. */
private val dayNumbers = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    .withIndex().flatMap { (i, day) -> listOf(day to i + 1, day.take(3) to i + 1) }.toMap()

/** Opens Clock with an alarm ready, the user saves it. */
class SetAlarmTool(phone: PhoneActions) : IntentTool(phone) {
    override val name = "set_alarm"
    override val description = "Sets an alarm in the Clock app (24 h clock). days: optional, mon..sun."
    override val setsDirectly = true
    override val parametersSchema =
        """{"type":"object","properties":{"hour":{"type":"integer"},"minutes":{"type":"integer"},"message":{"type":"string"},"days":{"type":"array","items":{"type":"string"}}},"required":["hour","minutes"]}"""

    override fun build(args: JSONObject): IntentSpec {
        val hour = args.intArg("hour") ?: throw ArgException("hour is required")
        val minutes = args.intArg("minutes") ?: throw ArgException("minutes is required")
        if (hour !in 0..23) throw ArgException("hour must be 0 to 23, got $hour")
        if (minutes !in 0..59) throw ArgException("minutes must be 0 to 59, got $minutes")
        val extras = mutableMapOf<String, Any>(
            "android.intent.extra.alarm.HOUR" to hour,
            "android.intent.extra.alarm.MINUTES" to minutes,
            "android.intent.extra.alarm.SKIP_UI" to false,
        )
        args.optionalString("message")?.let { extras["android.intent.extra.alarm.MESSAGE"] = it }
        days(args)?.let { extras["android.intent.extra.alarm.DAYS"] = it }
        return IntentSpec("android.intent.action.SET_ALARM", extras = extras, subject = "alarms")
    }

    /** The day numbers of `mon`..`sun`, without repeats; `null` when none given. */
    private fun days(args: JSONObject): List<Int>? {
        if (!args.has("days") || args.isNull("days")) return null
        val array = args.optJSONArray("days") ?: throw ArgException("days must be a list like [\"mon\",\"fri\"]")
        val numbers = (0 until array.length()).map {
            dayNumbers[array.optString(it).trim().lowercase()] ?: throw ArgException("unknown day '${array.optString(it)}', use mon, tue, wed, thu, fri, sat, sun")
        }.distinct()
        return numbers.ifEmpty { null }
    }
}

/** Opens Calendar with a new event ready, the user saves it. [zone] reads the local times of the arguments. */
class CreateEventTool(phone: PhoneActions, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : IntentTool(phone) {
    override val name = "create_event"
    override val description =
        "Opens Calendar with a new event, the user saves it. start/end: ISO like 2026-10-17T14:00 (local unless offset, end defaults to +1 h). all_day: start is a date, end its last day."
    override val parametersSchema =
        """{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string"},"end":{"type":"string"},"location":{"type":"string"},"description":{"type":"string"},"all_day":{"type":"boolean"}},"required":["title","start"]}"""

    override fun build(args: JSONObject): IntentSpec {
        val title = args.requiredString("title")
        val allDay = args.boolArg("all_day")
        val begin = parseMoment(args.requiredString("start"), "start", zone())
        val end = args.optionalString("end")?.let { parseMoment(it, "end", zone()) }
        val beginMs: Long
        val endMs: Long
        if (allDay) {
            // Calendar wants whole days as midnight to midnight UTC.
            beginMs = begin.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val last = end?.toLocalDate() ?: begin.toLocalDate()
            if (last < begin.toLocalDate()) throw ArgException("end must not be before start")
            endMs = last.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } else {
            val zone = zone()
            beginMs = begin.atZone(zone).toInstant().toEpochMilli()
            endMs = end?.atZone(zone)?.toInstant()?.toEpochMilli() ?: (beginMs + HourMs)
            if (endMs <= beginMs) throw ArgException("end must be after start")
        }
        val extras = mutableMapOf<String, Any>("title" to title, "beginTime" to beginMs, "endTime" to endMs, "allDay" to allDay)
        args.optionalString("location")?.let { extras["eventLocation"] = it }
        args.optionalString("description")?.let { extras["description"] = it }
        return IntentSpec(
            "android.intent.action.INSERT",
            data = "content://com.android.calendar/events",
            mimeType = "vnd.android.cursor.dir/event",
            extras = extras,
            subject = "calendar events",
        )
    }

    /** A local date-time, a bare date (midnight), or a date-time with an offset or zone, read in [zone]. */
    private fun parseMoment(text: String, key: String, zone: ZoneId): LocalDateTime = try {
        val iso = text.replace(' ', 'T')
        when {
            'T' !in iso -> LocalDate.parse(iso).atStartOfDay()
            else -> try {
                LocalDateTime.parse(iso)
            } catch (e: DateTimeParseException) {
                ZonedDateTime.parse(iso).withZoneSameInstant(zone).toLocalDateTime()
            }
        }
    } catch (e: DateTimeException) {
        throw ArgException("$key must be a local ISO date-time like 2026-10-17T14:00, got '$text'")
    }

    private companion object {
        const val HourMs = 3_600_000L
    }
}

/**
 * Opens the messaging app with a text ready, the user presses send. The recipient is a number or a contact name
 * (TEN-186): a name is looked up in the contacts here, in the same call. The result says who was picked (`to`).
 */
class DraftSmsTool(phone: PhoneActions, private val contacts: ContactsSource) : IntentTool(phone) {
    override val name = "draft_sms"
    override val description =
        "Opens the messaging app with a text drafted. The user sends it. to: a contact name or a phone number, optional."
    override val parametersSchema =
        """{"type":"object","properties":{"body":{"type":"string"},"to":{"type":"string"}},"required":["body"]}"""
    override val waitsForUser = true

    override suspend fun resolve(args: JSONObject, chose: MutableList<String>): JSONObject {
        val recipient = args.optionalString("to") ?: args.optionalString("number") ?: return args
        if (looksLikePhoneNumber(recipient)) return args
        val resolved = resolvePhone(recipient, contacts.loadContacts())
        chose += resolved.chose
        return JSONObject(args.toString()).put("to", resolved.value)
    }

    override fun build(args: JSONObject): IntentSpec {
        val body = args.requiredString("body")
        val number = (args.optionalString("to") ?: args.optionalString("number"))?.let { raw ->
            raw.filter { it.isDigit() || it in "+,;" }.takeIf { kept -> kept.any { it.isDigit() } }
                ?: throw ArgException("number '$raw' is not a phone number")
        }
        return IntentSpec(
            "android.intent.action.SENDTO",
            scheme = "sms",
            schemeSpecificPart = number.orEmpty(),
            extras = mapOf("sms_body" to body),
            subject = "text messages",
        )
    }
}

/**
 * Opens the mail app with an email ready, the user presses send. Each recipient is an address or a contact name
 * (TEN-186), looked up like [DraftSmsTool] does.
 */
class DraftEmailTool(phone: PhoneActions, private val contacts: ContactsSource) : IntentTool(phone) {
    override val name = "draft_email"
    override val description =
        "Opens the mail app with an email drafted. The user sends it. to: a contact name, an address or a comma-separated list."
    override val parametersSchema =
        """{"type":"object","properties":{"to":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"}}}"""
    override val waitsForUser = true

    /** An address, not a name with a tag ("Jade Martin @corp.example"). */
    private fun isAddress(entry: String) = '@' in entry && ' ' !in entry

    private fun recipients(args: JSONObject) =
        args.optionalString("to")?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    override suspend fun resolve(args: JSONObject, chose: MutableList<String>): JSONObject {
        val to = recipients(args)
        if (to.all(::isAddress)) return args
        // One load for all the names.
        var all: List<Contact>? = null
        val addresses = to.map { entry ->
            if (isAddress(entry)) {
                entry
            } else {
                val book = all ?: contacts.loadContacts().also { all = it }
                resolveEmail(entry, book).also { chose += it.chose }.value
            }
        }
        return JSONObject(args.toString()).put("to", addresses.joinToString(","))
    }

    override fun build(args: JSONObject): IntentSpec {
        val to = recipients(args)
        to.firstOrNull { '@' !in it || ' ' in it }?.let { throw ArgException("'$it' is not an email address") }
        val subject = args.optionalString("subject")
        val body = args.optionalString("body")
        if (to.isEmpty() && subject == null && body == null) throw ArgException("give at least one of to, subject, body")
        val extras = mutableMapOf<String, Any>()
        if (to.isNotEmpty()) extras["android.intent.extra.EMAIL"] = to
        subject?.let { extras["android.intent.extra.SUBJECT"] = it }
        body?.let { extras["android.intent.extra.TEXT"] = it }
        return IntentSpec("android.intent.action.SENDTO", data = "mailto:", extras = extras, subject = "email")
    }
}

/**
 * Opens a web page in the browser. http and https only. Unlike the other phone actions it asks first: the
 * browser sends the GET as it opens, so a link can carry data out (a prompt injection could put the clipboard in it).
 */
class OpenUrlTool(phone: PhoneActions) : IntentTool(phone) {
    override val name = "open_url"
    override val sendsOrDeletes = true
    override val description = "Opens an http(s) link in the browser."
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""

    override fun build(args: JSONObject): IntentSpec {
        val url = args.requiredString("url")
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw ArgException("'$url' is not a valid URL")
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) throw ArgException("only http and https links can be opened, got '$url'")
        if (uri.host.isNullOrEmpty()) throw ArgException("'$url' has no host")
        return IntentSpec(
            "android.intent.action.VIEW",
            data = url,
            categories = listOf("android.intent.category.BROWSABLE"),
            subject = "web links",
        )
    }
}

/** The outcome of matching an `open_app` name against the installed apps. */
sealed interface AppMatch {
    data class Found(val app: InstalledApp) : AppMatch

    /** At most [MaxCandidates] of them. */
    data class Ambiguous(val candidates: List<InstalledApp>) : AppMatch

    data object None : AppMatch

    companion object {
        const val MaxCandidates = 5
    }
}

/**
 * [query] against [apps] by label: an exact match (case-insensitive) wins, then labels that start with the
 * query, then labels that contain it. A step with several matches is [AppMatch.Ambiguous], never a guess.
 */
fun resolveApp(query: String, apps: List<InstalledApp>): AppMatch {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return AppMatch.None
    val steps = listOf<(String) -> Boolean>({ it == q }, { it.startsWith(q) }, { q in it })
    for (matches in steps) {
        val hits = apps.filter { matches(it.label.lowercase()) }.distinctBy { it.packageName }
        when {
            hits.size == 1 -> return AppMatch.Found(hits.first())
            hits.size > 1 -> return AppMatch.Ambiguous(hits.sortedBy { it.label.lowercase() }.take(AppMatch.MaxCandidates))
        }
    }
    return AppMatch.None
}

/** Launches an installed app by its name. */
class OpenAppTool(private val phone: PhoneActions) : Tool {
    override val name = "open_app"
    override val description = "Opens an installed app by its name (as on the home screen)."
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
    override val tier = ToolTier.PhoneAction

    override suspend fun execute(argsJson: String): ToolResult {
        val query = try {
            parseArgs(argsJson).requiredString("name")
        } catch (e: ArgException) {
            return ToolResult.error(e.message ?: "invalid arguments")
        }
        if (!phone.inForeground) return ToolResult.error(BACKGROUND_ERROR)
        return when (val match = resolveApp(query, phone.launchableApps())) {
            is AppMatch.None -> ToolResult.error("no installed app matches '$query'")
            is AppMatch.Ambiguous -> ToolResult.error(
                "'$query' matches several apps: ${match.candidates.joinToString { it.label }}. Ask the user which one, or retry with the exact name.",
            )
            is AppMatch.Found -> try {
                phone.launchApp(match.app.packageName)
                opened(match.app.label, set = false)
            } catch (e: NoHandlerException) {
                ToolResult.refused("${match.app.label} has no screen to open. Tell the user.")
            }
        }
    }
}

/** Play, pause and skip in the active media app through the media key. No app opens, no permission. */
class MediaControlTool(private val phone: PhoneActions) : Tool {
    override val name = "media_control"
    override val description = "Controls the active media player."
    override val parametersSchema =
        """{"type":"object","properties":{"action":{"type":"string","enum":["play","pause","play_pause","next","previous"]}},"required":["action"]}"""
    override val tier = ToolTier.PhoneAction

    override suspend fun execute(argsJson: String): ToolResult {
        val raw = try {
            parseArgs(argsJson).requiredString("action")
        } catch (e: ArgException) {
            return ToolResult.error(e.message ?: "invalid arguments")
        }
        val key = MediaKey.entries.firstOrNull { it.argName == raw.lowercase() }
            ?: return ToolResult.error("action must be one of ${MediaKey.entries.joinToString { it.argName }}, got '$raw'")
        if (key == MediaKey.Pause && !phone.isMusicActive) return ToolResult.error("nothing is playing")
        phone.sendMediaKey(key)
        return ToolResult.ok("sent ${key.argName} to the active player")
    }
}
