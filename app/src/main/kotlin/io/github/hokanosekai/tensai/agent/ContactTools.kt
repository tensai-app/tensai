package io.github.hokanosekai.tensai.agent

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Contacts for the phone actions (TEN-186): `find_contact`, and the name lookup `draft_sms` and `draft_email` do
 * themselves, so a small model never has to chain two calls. Numbers only go to the local model and the intent.
 */

enum class PhoneType(val label: String) {
    Mobile("mobile"),
    Home("home"),
    Work("work"),
    Other("other"),
}

/** @param normalized the E.164 form Android computed, when it did. */
data class ContactPhone(val number: String, val type: PhoneType, val normalized: String? = null)

/**
 * One address book entry, [emails] in the order the address book prefers (primary first). [id] is the provider's
 * contact id: two entries with the same name are two people until the user says otherwise.
 */
data class Contact(val id: Long, val name: String, val phones: List<ContactPhone>, val emails: List<String>)

/** The Android side of the contacts, so the tools test without Android. */
interface ContactsSource {
    /** Whether READ_CONTACTS is granted: asking would show no dialog. */
    fun isGranted(): Boolean

    /** Asks for READ_CONTACTS when it is not granted yet: suspends while Android shows the dialog. */
    suspend fun access(): PermissionResult

    /** Every contact with a number or an address. Needs [access] granted. */
    fun load(): List<Contact>
}

/** What the model reads when the user said no to the contacts permission. */
const val ContactsRefusedText = "the user refused access to the contacts. Tell the user, do not retry."

const val ContactsBlockedText =
    "contacts access is blocked in Android settings. Tell the user to allow Contacts for tensai in the app settings, do not retry."

/**
 * The key of a refusal the app settings can undo: the result is `{"error": "...", "blocked_permission": "contacts"}`,
 * and the call detail offers "Open app settings" when it finds the key (see [blockedPermission]). Reusable by any
 * tool that needs an Android permission.
 */
const val BlockedPermissionKey = "blocked_permission"

internal fun blockedResult(permission: String, message: String) =
    ToolResult.refused(JSONObject().put("error", message).put(BlockedPermissionKey, permission).toString())

/** The permission a stored result says is blocked for good, `null` for any other output. */
fun blockedPermission(output: String?): String? = try {
    output?.removePrefix("Error: ")?.trim()?.takeIf { it.startsWith("{") }?.let(::JSONObject)
        ?.optString(BlockedPermissionKey)?.takeIf { it.isNotEmpty() }
} catch (_: JSONException) {
    null
}

/**
 * The refusal for a permission that is not granted, `null` when it is. [permission] is the key of the settings link
 * (see [BlockedPermissionKey]), [refused] and [blocked] what the model reads for each kind of refusal.
 */
internal fun refusalFor(
    result: PermissionResult,
    permission: String = "contacts",
    refused: String = ContactsRefusedText,
    blocked: String = ContactsBlockedText,
): ToolResult? = when (result) {
    PermissionResult.Granted -> null
    PermissionResult.Denied -> ToolResult.refused(refused)
    PermissionResult.PermanentlyDenied -> blockedResult(permission, blocked)
}

private val combiningMarks = Regex("\\p{M}+")
private val spaces = Regex("\\s+")

/** Lowercase, without accents, single spaces: "  Jadé  MARTIN" is "jade martin". */
internal fun normalizeName(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase().trim().replace(spaces, " ")

/** Whether [text] reads as a phone number (digits and dialing punctuation, no letter), as opposed to a name. */
internal fun looksLikePhoneNumber(text: String): Boolean =
    text.any { it.isDigit() } && text.all { it.isDigit() || it in "+-().,; *#/" }

/** The last 9 digits of a number, its E.164 form when known: "+33 6 12 34 56 78" and "06 12 34 56 78" are one. */
private fun numberKey(phone: ContactPhone): String = (phone.normalized ?: phone.number).filter(Char::isDigit).takeLast(9)

/**
 * Every contact that answers to [query]: a name that contains it (so the full name, the first name alone and a part
 * of a name all count), accents and case ignored. The best come first (full name, then first name, then the rest),
 * each by name. Each contact keeps its own numbers (no merge by name) minus duplicates. Never a pick: see [pickContacts].
 */
internal fun matchContacts(query: String, contacts: List<Contact>): List<Contact> {
    val q = normalizeName(query)
    if (q.isEmpty()) return emptyList()
    val people = contacts.map(::deduped)
    fun rank(c: Contact): Int = normalizeName(c.name).let { if (it == q) 0 else if (it.substringBefore(' ') == q) 1 else 2 }
    return people.filter { q in normalizeName(it.name) }.sortedWith(compareBy({ rank(it) }, { normalizeName(it.name) }))
}

private fun deduped(c: Contact) = c.copy(phones = c.phones.distinctBy(::numberKey), emails = c.emails.distinctBy { it.lowercase() })

/**
 * The contacts whose whole display name is [name] (case and accents ignored, symbols and emoji kept: "Jade" and
 * "Jade 💜" are two contacts). What a label copied from an ambiguity list resolves with.
 */
private fun exactContacts(name: String, contacts: List<Contact>): List<Contact> {
    val q = normalizeName(name)
    return if (q.isEmpty()) emptyList() else contacts.filter { normalizeName(it.name) == q }.map(::deduped)
}

/** A label as the ambiguity list prints it, "Jade 💜 · mobile" or "Jade …42 · mobile/home": the name part and the types. */
private val listedLabel = Regex("^(.+?)\\s+\u00B7\\s+((?:mobile|home|work|other|no email)(?:/(?:mobile|home|work|other))*)$")

/**
 * What the model hands back from the list: the name (with its tag) and the phone types listed after it. Null for a plain
 * name. [types] holds one entry when the label shows a single type, which then picks the number.
 */
private class Label(val name: String, val types: List<String>)

private fun parseLabel(text: String): Label? =
    listedLabel.find(text.trim())?.let { Label(it.groupValues[1], it.groupValues[2].split('/')) }

/**
 * Hits for [query] with its [tag] parsed out by [tagRegex]. A tagged name or a label from the list is an exact name
 * ([exactContacts]), falling back to the plain lookup of the whole text when no contact has that name.
 */
private fun lookup(query: String, label: Label?, tag: MatchResult?, all: List<Contact>): List<Contact> {
    val base = tag?.groupValues?.get(1) ?: label?.name ?: query
    if (label == null && tag == null) return pickContacts(base, all)
    return exactContacts(base, all).ifEmpty { pickContacts(query, all) }
}

/**
 * The contacts [query] designates for a draft for a draft: all the [matchContacts] candidates, except that a query of several words
 * equal to exactly one contact's full name picks that one ("Jade Martin"). A single word never picks: with "Jade" and
 * a contact named just "Jade" among others, the user may well mean another, so the model must ask.
 */
internal fun pickContacts(query: String, contacts: List<Contact>): List<Contact> {
    val hits = matchContacts(query, contacts)
    val q = normalizeName(query)
    if (' ' in q) hits.filter { normalizeName(it.name) == q }.singleOrNull()?.let { return listOf(it) }
    return hits
}

/** A lookup that did not end on one address or number: [result] is what the model reads. */
internal class ContactFailure(val result: ToolResult) : Exception()

private const val MaxCandidates = 5

private fun last2(number: String) = number.filter(Char::isDigit).takeLast(2)

/**
 * "Name · mobile/home; Other · work", at most [MaxCandidates], with how many were left out. No full numbers. Names
 * that appear twice carry a short [tag] ("Jade Martin …42") that [withoutTag] reads back on the retry.
 */
private fun candidateList(hits: List<Contact>, detail: (Contact) -> String, tag: (Contact) -> String): String {
    val twice = hits.groupingBy { normalizeName(it.name) }.eachCount().filterValues { it > 1 }.keys
    val shown = hits.take(MaxCandidates).joinToString("; ") { c ->
        val label = if (normalizeName(c.name) in twice) "${c.name} ${tag(c)}".trim() else c.name
        detail(c).let { if (it.isEmpty()) label else "$label · $it" }
    }
    val more = hits.size - MaxCandidates
    return if (more > 0) "$shown; and $more more" else shown
}

private val phoneTag = Regex("^(.+?)\\s*\u2026(\\d{2})$")
private val emailTag = Regex("^(.+?)\\s+@(\\S+)$")

/** What a name resolved to: the [value] for the intent and what the model is told it picked ("Jade Martin, mobile"). */
internal class Resolved(val value: String, val chose: String)

/** Loads the contacts, asking the permission first. @throws ContactFailure when it is refused, or revoked meanwhile. */
internal suspend fun ContactsSource.loadContacts(): List<Contact> {
    refusalFor(access())?.let { throw ContactFailure(it) }
    return try {
        load()
    } catch (e: SecurityException) {
        // Revoked after the check: only the app settings can grant it again.
        throw ContactFailure(blockedResult("contacts", ContactsBlockedText))
    }
}

private fun noContact(name: String) = ContactFailure(ToolResult.error("no contact named $name"))

private fun severalContacts(name: String, hits: List<Contact>, detail: (Contact) -> String, tag: (Contact) -> String) = ContactFailure(
    ToolResult.error(
        "'$name' matches several contacts: ${candidateList(hits, detail, tag)}. " +
            "Ask the user which one with ask_user, then retry with the name exactly as listed.",
    ),
)

/**
 * The number of the contact called [name] among [all]. One contact with one number, or with one mobile among
 * several numbers, resolves. Otherwise [ContactFailure] tells the model to ask the user (`ask_user`) and retry with
 * the name (as listed) or number chosen.
 */
internal fun resolvePhone(name: String, all: List<Contact>): Resolved {
    val label = parseLabel(name)
    val tagged = phoneTag.find(label?.name ?: name.trim())
    var hits = lookup(name, label, tagged, all)
    tagged?.let { t -> hits = hits.filter { c -> c.phones.any { last2(it.number) == t.groupValues[2] } } }
    if (hits.isEmpty()) throw noContact(name)
    if (hits.size > 1) {
        val types = { c: Contact -> c.phones.map { it.type.label }.distinct().joinToString("/") }
        throw severalContacts(name, hits, types) { c -> c.phones.firstOrNull()?.let { "\u2026${last2(it.number)}" }.orEmpty() }
    }
    val contact = hits.single()
    // A label with one type ("Jade · mobile") names the number: the one of that type, when there is one.
    val phones = label?.types?.singleOrNull()
        ?.let { type -> contact.phones.filter { it.type.label == type }.takeIf { it.isNotEmpty() } } ?: contact.phones
    val phone = when {
        phones.isEmpty() -> throw ContactFailure(ToolResult.error("${contact.name} has no phone number"))
        phones.size == 1 -> phones.single()
        // Several numbers: the numbers themselves are listed, the model has to hand one back.
        else -> phones.singleOrNull { it.type == PhoneType.Mobile } ?: throw ContactFailure(
            ToolResult.error(
                "${contact.name} has several numbers: ${phones.joinToString("; ") { "${it.type.label} ${it.number}" }}. " +
                    "Ask the user which one with ask_user, then retry with that number.",
            ),
        )
    }
    return Resolved(phone.number, "${contact.name}, ${phone.type.label}")
}

/** The primary address of the contact called [name] among [all], failing like [resolvePhone]. */
internal fun resolveEmail(name: String, all: List<Contact>): Resolved {
    val label = parseLabel(name)
    val tagged = emailTag.find(label?.name ?: name.trim())
    var hits = lookup(name, label, tagged, all)
    tagged?.let { t -> hits = hits.filter { c -> c.emails.any { it.substringAfter('@').equals(t.groupValues[2], ignoreCase = true) } } }
    if (hits.isEmpty()) throw noContact(name)
    if (hits.size > 1) {
        throw severalContacts(name, hits, { c -> if (c.emails.isEmpty()) "no email" else "" }) { c ->
            c.emails.firstOrNull()?.let { "@${it.substringAfter('@')}" }.orEmpty()
        }
    }
    val contact = hits.single()
    val address = contact.emails.firstOrNull() ?: throw ContactFailure(ToolResult.error("${contact.name} has no email address"))
    return Resolved(address, contact.name)
}

/**
 * Looks a contact up by name for questions like "what is Jade's number" (TEN-186). Asks the Android contacts
 * permission at first use ([waitsForUser]: the runtime's timeout does not apply while the dialog is up).
 *
 * The result the model reads: `{"contacts": [{"name", "phones": [{"type", "number"}], "emails": [...]}], "more": 2}`,
 * at most [MaxCandidates] contacts, `more` when others matched. No match is an empty list with a `note`.
 */
class FindContactTool(private val contacts: ContactsSource, private val inForeground: () -> Boolean) : Tool {
    override val name = "find_contact"
    override val description = "Finds contacts by name and returns their phone numbers and emails. name: a first name, a full name or part of one."
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
    override val tier = ToolTier.AndroidPermission
    override val waitsForUser = true

    override suspend fun execute(argsJson: String): ToolResult {
        val query = try {
            JSONObject(argsJson).stringArg("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.error("name is required")
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        // The permission dialog needs the screen: from the background the call would wait on a dialog nobody sees.
        if (!contacts.isGranted() && !inForeground()) return ToolResult.error(BACKGROUND_ERROR)
        val hits = try {
            matchContacts(query, contacts.loadContacts())
        } catch (e: ContactFailure) {
            return e.result
        }
        val json = JSONObject().put(
            "contacts",
            JSONArray(
                hits.take(MaxCandidates).map { c ->
                    JSONObject()
                        .put("name", c.name)
                        .put("phones", JSONArray(c.phones.map { JSONObject().put("type", it.type.label).put("number", it.number) }))
                        .put("emails", JSONArray(c.emails))
                },
            ),
        )
        if (hits.size > MaxCandidates) json.put("more", hits.size - MaxCandidates)
        if (hits.isEmpty()) json.put("note", "no contact named $query")
        return ToolResult.ok(json.toString())
    }
}
