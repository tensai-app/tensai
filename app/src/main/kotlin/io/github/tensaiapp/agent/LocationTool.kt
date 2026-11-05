package io.github.tensaiapp.agent

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.json.JSONObject

/**
 * Where the phone is, for `location` (TEN-179): approximate only (ACCESS_COARSE_LOCATION), rounded, and never turned
 * into a place name here: Android's Geocoder sends the coordinates to Google, which "nothing leaves the device unless
 * the Network tier" forbids. The model chains the coordinates into `weather`.
 */

/** A position as the platform gave it. [accuracyMeters] is `null` when the provider does not say. */
data class LocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?, val timeMillis: Long)

/** Why there is no position to give. */
enum class LocationFailure(val message: String) {
    /** The location switch of the phone is off. */
    Off("location is turned off on the phone. Tell the user to turn it on in the quick settings, do not retry."),

    /** Location is on but the phone could not produce a position. */
    Unavailable("could not get a position from the phone right now. Tell the user, do not retry."),

    /** The only position the phone had is too old to say where the user is now. */
    Stale("no recent position, location may be off or the phone has not had a fix lately. Tell the user, do not retry."),
}

/** The Android side of the location, so the tool tests without Android. */
interface LocationSource {
    /** Whether ACCESS_COARSE_LOCATION is granted: asking would show no dialog. */
    fun isGranted(): Boolean

    /** Asks for ACCESS_COARSE_LOCATION when it is not granted yet: suspends while Android shows the dialog. */
    suspend fun access(): PermissionResult

    /** A position, fresh when the platform can get one in time, else the last known. Needs [access] granted. */
    suspend fun current(): LocationOutcome
}

/** What [LocationSource.current] found. */
sealed interface LocationOutcome {
    data class Found(val fix: LocationFix) : LocationOutcome
    data class Failed(val reason: LocationFailure) : LocationOutcome
}

const val LocationRefusedText = "the user refused access to the location. Tell the user, do not retry."

/** A last-known fix older than this says where the phone was, not where it is. */
internal const val LOCATION_MAX_AGE_MS = 60 * 60 * 1000L

const val LocationBlockedText =
    "location access is blocked in Android settings. Tell the user to allow Location for tensai in the app settings, do not retry."

/** Two decimals of a degree: about 1 km, what a coarse fix is worth anyway and no more than the user agreed to share. */
internal fun roundCoordinate(degrees: Double): Double = Math.round(degrees * 100.0) / 100.0

/**
 * The answer: `{"latitude": 38.72, "longitude": -9.13, "accuracy_m": 1500, "fix_time": "2026-10-17 14:03", "note": ...}`
 * with the time in [zone]. `accuracy_m` is left out when unknown. The note says the position is approximate and
 * not for web searches.
 */
internal fun formatLocation(fix: LocationFix, zone: ZoneId): String {
    val latitude = roundCoordinate(fix.latitude)
    val longitude = roundCoordinate(fix.longitude)
    val json = JSONObject().put("latitude", latitude).put("longitude", longitude)
    fix.accuracyMeters?.takeIf { it > 0f }?.let { json.put("accuracy_m", Math.round(it)) }
    json.put("fix_time", Instant.ofEpochMilli(fix.timeMillis).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString().replace('T', ' '))
    json.put("note", "approximate, about 1 km. Do not put coordinates in web searches or URLs")
    return json.toString()
}

/** What [obtainFix] ends on: a position to use, or the result the tool answers with. */
internal sealed interface FixResult {
    data class Got(val fix: LocationFix) : FixResult
    data class Failed(val result: ToolResult) : FixResult
}

/**
 * The position for a tool, asking the permission at first use: shared by `location` and `weather` so both refuse,
 * block, fail and age the same way. A background call fails before any dialog, a revoked grant points to the settings,
 * and a fix older than [LOCATION_MAX_AGE_MS] is [LocationFailure.Stale].
 */
internal suspend fun obtainFix(location: LocationSource, inForeground: () -> Boolean, clock: Clock): FixResult {
    // The permission dialog needs the screen: from the background the call would wait on a dialog nobody sees.
    if (!location.isGranted() && !inForeground()) return FixResult.Failed(ToolResult.error(BACKGROUND_ERROR))
    refusalFor(location.access(), "location", LocationRefusedText, LocationBlockedText)?.let { return FixResult.Failed(it) }
    val outcome = try {
        location.current()
    } catch (e: SecurityException) {
        // Revoked after the check: only the app settings can grant it again.
        return FixResult.Failed(blockedResult("location", LocationBlockedText))
    }
    return when (outcome) {
        is LocationOutcome.Found ->
            if (clock.millis() - outcome.fix.timeMillis > LOCATION_MAX_AGE_MS) FixResult.Failed(ToolResult.error(LocationFailure.Stale.message))
            else FixResult.Got(outcome.fix)
        is LocationOutcome.Failed -> FixResult.Failed(ToolResult.error(outcome.reason.message))
    }
}

/** The approximate position of the phone (TEN-179). Asks the permission at first use, like [FindContactTool]. */
class LocationTool(
    private val location: LocationSource,
    private val inForeground: () -> Boolean,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Tool {
    override val name = "location"
    override val description = "Approximate position of the phone, only when the user asks where they are. Not for weather: call weather with place \"here\"."
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override val tier = ToolTier.AndroidPermission
    override val waitsForUser = true

    override suspend fun execute(argsJson: String): ToolResult = when (val got = obtainFix(location, inForeground, clock)) {
        is FixResult.Got -> ToolResult.ok(formatLocation(got.fix, clock.zone))
        is FixResult.Failed -> got.result
    }
}
