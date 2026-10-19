package io.github.tensaiapp.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contacts for the phone actions (TEN-186): name or number, the lookup rules, the permission outcomes. */
class ContactToolsTest {

    private var ids = 0L

    /** A contact with the next id: the tests that care about identity build their own. */
    private fun Contact(name: String, phones: List<ContactPhone>, emails: List<String>) = Contact(++ids, name, phones, emails)

    private class FakeContacts(
        private val contacts: List<Contact>,
        private val access: PermissionResult = PermissionResult.Granted,
    ) : ContactsSource {
        var asked = 0
        var loaded = 0

        override fun isGranted() = access == PermissionResult.Granted

        override suspend fun access(): PermissionResult {
            asked++
            return access
        }

        override fun load(): List<Contact> {
            loaded++
            return contacts
        }
    }

    private class FakePhone(override val inForeground: Boolean = true) : PhoneActions {
        val started = mutableListOf<IntentSpec>()

        override fun handlerLabel(spec: IntentSpec) = "Messages"

        override fun start(spec: IntentSpec) {
            started += spec
        }

        override fun launchableApps() = emptyList<InstalledApp>()

        override fun launchApp(packageName: String) = Unit

        override val isMusicActive = false

        override fun sendMediaKey(key: MediaKey) = Unit
    }

    private fun phone(number: String, type: PhoneType = PhoneType.Mobile) = ContactPhone(number, type)

    private val jade = Contact("Jade Martin", listOf(phone("06 12 34 56 78"), phone("02 99 00 11 22", PhoneType.Home)), listOf("jade@example.com", "jm@work.example"))
    private val jadeP = Contact("Jade Petit", listOf(phone("06 99 99 99 99")), emptyList())
    private val zoe = Contact("Zoé Lefèvre", listOf(phone("07 11 22 33 44")), listOf("zoe@example.com"))
    private val sam = Contact("Sam", listOf(phone("06 00 00 00 01"), phone("06 00 00 00 02")), emptyList())
    private val book = listOf(jade, jadeP, zoe, sam, Contact("Samuel Roy", listOf(phone("06 55 55 55 55")), emptyList()))

    // name or number

    @Test
    fun numbersAreNotNames() {
        assertTrue(looksLikePhoneNumber("0612345678"))
        assertTrue(looksLikePhoneNumber("+33 6 12-34-56-78"))
        assertTrue(looksLikePhoneNumber("(02) 99.00.11.22"))
        assertTrue(looksLikePhoneNumber("0612345678, 0699999999"))
        assertFalse(looksLikePhoneNumber("Jade"))
        assertFalse(looksLikePhoneNumber("Jade 2"))
        assertFalse(looksLikePhoneNumber("1-800-FLOWERS"))
        assertFalse(looksLikePhoneNumber(""))
    }

    // matching

    @Test
    fun matchingIgnoresCaseAndAccents() {
        assertEquals(listOf("Zoé Lefèvre"), matchContacts("zoe lefevre", book).map { it.name })
        assertEquals(listOf("Zoé Lefèvre"), matchContacts("ZOÉ", book).map { it.name })
        assertEquals(listOf("Zoé Lefèvre"), matchContacts("  lefevre ", book).map { it.name })
    }

    @Test
    fun firstNameAloneAndContains() {
        assertEquals(listOf("Jade Martin", "Jade Petit"), matchContacts("jade", book).map { it.name })
        assertEquals(listOf("Jade Martin"), matchContacts("martin", book).map { it.name })
        assertEquals(listOf("Jade Petit"), matchContacts("ade pe", book).map { it.name })
    }

    @Test
    fun candidatesListTheExactNameFirstButNeverPickIt() {
        // "Sam" is also the start of "Samuel Roy".
        assertEquals(listOf("Sam", "Samuel Roy"), matchContacts("sam", book).map { it.name })
        assertEquals(listOf("Sam", "Samuel Roy"), pickContacts("sam", book).map { it.name })
    }

    @Test
    fun aBareFirstNameIsAmbiguousEvenWithAnExactContact() = runBlocking {
        val two = listOf(Contact("Jade", listOf(phone("06 00 00 00 99", PhoneType.Other)), listOf("j@example.com")), jade)
        val phone = FakePhone()
        val sms = DraftSmsTool(phone, FakeContacts(two)).execute("""{"body":"hi","to":"Jade"}""")
        val mail = DraftEmailTool(phone, FakeContacts(two)).execute("""{"to":"jade"}""")

        assertTrue(sms.isError)
        assertTrue(sms.text, sms.text.startsWith("'Jade' matches several contacts: Jade · other; Jade Martin · mobile/home."))
        assertTrue(mail.isError)
        assertTrue(mail.text, mail.text.contains("Jade; Jade Martin"))
        assertTrue(phone.started.isEmpty())
        // find_contact lists the exact one first
        val found = JSONObject(FindContactTool(FakeContacts(two)) { true }.execute("""{"name":"jade"}""").text).getJSONArray("contacts")
        assertEquals("Jade", found.getJSONObject(0).getString("name"))
        assertEquals(2, found.length())
    }

    @Test
    fun aLabelFromTheListResolvesTheContactAndItsType() = runBlocking {
        val bare = Contact("Jade", listOf(phone("06 00 00 00 01", PhoneType.Other)), listOf("bare@example.com"))
        val heart = Contact("Jade \uD83D\uDC9C", listOf(phone("06 00 00 00 02")), listOf("heart@example.com"))
        val x = Contact("Jade X", listOf(phone("06 00 00 00 03", PhoneType.Other)), emptyList())
        val book = listOf(bare, heart, x)
        val phone = FakePhone()
        val sms = DraftSmsTool(phone, FakeContacts(book))

        // the three labels the ambiguity listed
        val listed = sms.execute("""{"body":"hi","to":"Jade"}""").text
        assertTrue(listed, listed.contains("Jade \u00B7 other; Jade X \u00B7 other; Jade \uD83D\uDC9C \u00B7 mobile"))

        sms.execute("""{"body":"hi","to":"Jade \uD83D\uDC9C \u00B7 mobile"}""")
        sms.execute("""{"body":"hi","to":"Jade \u00B7 other"}""")
        sms.execute("""{"body":"hi","to":"Jade X \u00B7 other"}""")

        assertEquals(listOf("0600000002", "0600000001", "0600000003"), phone.started.map { it.schemeSpecificPart })
        // a different contact, not a symbol-blind match
        assertFalse(normalizeName("Jade \uD83D\uDC9C") == normalizeName("Jade"))

        val mail = DraftEmailTool(phone, FakeContacts(book))
        mail.execute("""{"to":"Jade \u00B7 no email"}""")
        mail.execute("""{"to":"Jade \uD83D\uDC9C"}""")
        assertEquals(listOf("bare@example.com"), phone.started[3].extras["android.intent.extra.EMAIL"])
        assertEquals(listOf("heart@example.com"), phone.started[4].extras["android.intent.extra.EMAIL"])
    }

    @Test
    fun aLabelTypePicksTheNumberAmongSeveral() = runBlocking {
        val phone = FakePhone()
        DraftSmsTool(phone, FakeContacts(listOf(jade))).execute("""{"body":"hi","to":"Jade Martin \u00B7 home"}""")
        DraftSmsTool(phone, FakeContacts(listOf(jade))).execute("""{"body":"hi","to":"Jade Martin \u00B7 mobile/home"}""")

        assertEquals("0299001122", phone.started[0].schemeSpecificPart)
        // two types listed: no pick by type, the mobile rule applies
        assertEquals("0612345678", phone.started[1].schemeSpecificPart)
    }

    @Test
    fun aTaggedLabelStillWorks() = runBlocking {
        val a = Contact(1, "Jade Martin", listOf(phone("06 12 34 56 42")), emptyList())
        val b = Contact(2, "Jade Martin", listOf(phone("06 98 76 54 31")), emptyList())
        val phone = FakePhone()
        DraftSmsTool(phone, FakeContacts(listOf(a, b))).execute("""{"body":"hi","to":"Jade Martin \u202631 \u00B7 mobile"}""")

        assertEquals("0698765431", phone.started.single().schemeSpecificPart)
    }

    @Test
    fun aFullNameQueryPicksItsExactContact() = runBlocking {
        val two = listOf(Contact("Jade", listOf(phone("06 00 00 00 99", PhoneType.Other)), emptyList()), jade)
        val phone = FakePhone()
        DraftSmsTool(phone, FakeContacts(two)).execute("""{"body":"hi","to":"Jade Martin"}""")

        assertEquals("0612345678", phone.started.single().schemeSpecificPart)
    }

    @Test
    fun aFullNameSharedByTwoContactsStaysAmbiguous() {
        val twins = listOf(Contact("Jade Martin", listOf(phone("06 12 34 56 42")), emptyList()), Contact("Jade Martin", listOf(phone("06 98 76 54 31")), emptyList()))

        assertEquals(2, pickContacts("jade martin", twins).size)
    }

    @Test
    fun aFirstNameWithASingleCandidateResolves() = runBlocking {
        val phone = FakePhone()
        DraftSmsTool(phone, FakeContacts(listOf(jade))).execute("""{"body":"hi","to":"jade"}""")

        assertEquals("0612345678", phone.started.single().schemeSpecificPart)
    }

    @Test
    fun noMatchAndBlankQuery() {
        assertTrue(matchContacts("Paul", book).isEmpty())
        assertTrue(matchContacts("  ", book).isEmpty())
    }

    @Test
    fun twoContactsOfTheSameNameStayTwo() {
        val twice = listOf(
            Contact(1, "Zoé Lefèvre", listOf(phone("07 11 22 33 44")), emptyList()),
            Contact(2, "zoe lefevre", listOf(phone("07 99 88 77 66")), emptyList()),
        )

        assertEquals(2, matchContacts("zoe", twice).size)
    }

    @Test
    fun theSameNumberWrittenTwoWaysIsOne() {
        val both = Contact(
            1, "Zoé",
            listOf(phone("+33 6 12 34 56 78"), phone("06 12 34 56 78"), ContactPhone("0612345678", PhoneType.Mobile, "+33612345678"), phone("06 00 00 00 00", PhoneType.Work)),
            listOf("Zoe@Example.com", "zoe@example.com"),
        )
        val hit = matchContacts("zoe", listOf(both)).single()

        assertEquals(2, hit.phones.size)
        assertEquals(1, hit.emails.size)
    }

    @Test
    fun collidingNamesGetATagAndTheTagWorksOnTheRetry() = runBlocking {
        val a = Contact(1, "Jade Martin", listOf(phone("06 12 34 56 42")), listOf("jade@home.example"))
        val b = Contact(2, "Jade Martin", listOf(phone("06 98 76 54 31")), listOf("jade@corp.example"))
        val c = Contact(3, "Jade Petit", listOf(phone("06 11 11 11 11")), emptyList())
        val phone = FakePhone()
        val sms = DraftSmsTool(phone, FakeContacts(listOf(a, b, c)))
        val ambiguous = sms.execute("""{"body":"hi","to":"jade"}""")

        assertEquals(
            "'jade' matches several contacts: Jade Martin \u202642 · mobile; Jade Martin \u202631 · mobile; Jade Petit · mobile. " +
                "Ask the user which one with ask_user, then retry with the name exactly as listed.",
            ambiguous.text,
        )
        assertFalse(ambiguous.text.contains("06 12 34 56 42"))

        sms.execute("""{"body":"hi","to":"Jade Martin \u202631"}""")
        assertEquals("0698765431", phone.started.single().schemeSpecificPart)

        val mail = DraftEmailTool(phone, FakeContacts(listOf(a, b, c)))
        assertTrue(mail.execute("""{"to":"jade martin"}""").text.contains("Jade Martin @home.example; Jade Martin @corp.example"))
        mail.execute("""{"to":"Jade Martin @corp.example"}""")
        assertEquals(listOf("jade@corp.example"), phone.started.last().extras["android.intent.extra.EMAIL"])
    }

    // draft_sms

    @Test
    fun smsGivenANumberNeverTouchesTheContacts() = runBlocking {
        val contacts = FakeContacts(book)
        val phone = FakePhone()
        val result = DraftSmsTool(phone, contacts).execute("""{"body":"hi","to":"06 12 34 56 78"}""")

        assertFalse(result.text, result.isError)
        assertEquals("0612345678", phone.started.single().schemeSpecificPart)
        assertEquals(0, contacts.asked)
    }

    @Test
    fun smsStillReadsTheOldNumberKey() = runBlocking {
        val phone = FakePhone()
        DraftSmsTool(phone, FakeContacts(book)).execute("""{"body":"hi","number":"0612345678"}""")

        assertEquals("0612345678", phone.started.single().schemeSpecificPart)
    }

    @Test
    fun smsResolvesANameInTheSameCallAndPrefersMobile() = runBlocking {
        val phone = FakePhone()
        val result = DraftSmsTool(phone, FakeContacts(book)).execute("""{"body":"salut","to":"zoe"}""")
        val mobile = DraftSmsTool(phone, FakeContacts(listOf(jade))).execute("""{"body":"salut","number":"Jade"}""")

        assertFalse(result.text, result.isError)
        assertEquals("0711223344", phone.started[0].schemeSpecificPart)
        assertEquals("salut", phone.started[0].extras["sms_body"])
        // two numbers, one mobile: the mobile
        assertEquals("0612345678", phone.started[1].schemeSpecificPart)
        // the model reads who was picked, not the number
        val told = JSONObject(mobile.text)
        assertEquals("Jade Martin, mobile", told.getString("to"))
        assertFalse(mobile.text.contains("0612345678"))
    }

    @Test
    fun smsWithSeveralContactsListsThemWithoutNumbers() = runBlocking {
        val phone = FakePhone()
        val result = DraftSmsTool(phone, FakeContacts(book)).execute("""{"body":"hi","to":"Jade"}""")

        assertTrue(result.isError)
        assertFalse(result.refused)
        assertEquals(
            "'Jade' matches several contacts: Jade Martin · mobile/home; Jade Petit · mobile. " +
                "Ask the user which one with ask_user, then retry with the name exactly as listed.",
            result.text,
        )
        assertTrue(phone.started.isEmpty())
    }

    @Test
    fun ambiguityListIsCappedAtFive() = runBlocking {
        val many = (1..8).map { Contact("Alex $it", listOf(phone("06 00 00 00 0$it")), emptyList()) }
        val result = DraftSmsTool(FakePhone(), FakeContacts(many)).execute("""{"body":"hi","to":"alex"}""")

        assertTrue(result.text, result.text.contains("Alex 5 · mobile; and 3 more."))
        assertFalse(result.text.contains("Alex 6"))
    }

    @Test
    fun smsWithSeveralNumbersAndNoSingleMobileListsTheNumbers() = runBlocking {
        val result = DraftSmsTool(FakePhone(), FakeContacts(listOf(sam))).execute("""{"body":"hi","to":"sam"}""")

        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Sam has several numbers: mobile 06 00 00 00 01; mobile 06 00 00 00 02."))
        assertTrue(result.text.contains("ask_user"))
    }

    @Test
    fun smsWithNoContactIsAClearError() = runBlocking {
        val result = DraftSmsTool(FakePhone(), FakeContacts(book)).execute("""{"body":"hi","to":"Paul"}""")

        assertTrue(result.isError)
        assertFalse(result.refused)
        assertEquals("no contact named Paul", result.text)
    }

    @Test
    fun smsForAContactWithoutANumber() = runBlocking {
        val mailOnly = Contact("Léa", emptyList(), listOf("lea@example.com"))
        val result = DraftSmsTool(FakePhone(), FakeContacts(listOf(mailOnly))).execute("""{"body":"hi","to":"lea"}""")

        assertEquals("Léa has no phone number", result.text)
    }

    // draft_email

    @Test
    fun emailResolvesTheFirstAddressAndKeepsWrittenOnes() = runBlocking {
        val phone = FakePhone()
        val result = DraftEmailTool(phone, FakeContacts(book)).execute("""{"to":"jade martin, bob@example.org","subject":"Hi"}""")

        assertFalse(result.text, result.isError)
        assertEquals(listOf("jade@example.com", "bob@example.org"), phone.started.single().extras["android.intent.extra.EMAIL"])
        assertEquals("Jade Martin", JSONObject(result.text).getString("to"))
    }

    @Test
    fun emailGivenAddressesNeverTouchesTheContacts() = runBlocking {
        val contacts = FakeContacts(book)
        DraftEmailTool(FakePhone(), contacts).execute("""{"to":"a@b.fr","subject":"x"}""")
        DraftEmailTool(FakePhone(), contacts).execute("""{"subject":"x"}""")

        assertEquals(0, contacts.asked)
    }

    @Test
    fun emailErrors() = runBlocking {
        val tool = DraftEmailTool(FakePhone(), FakeContacts(book))

        assertEquals("no contact named Paul", tool.execute("""{"to":"Paul"}""").text)
        assertEquals("Jade Petit has no email address", tool.execute("""{"to":"jade petit"}""").text)
        assertEquals(
            "'jade' matches several contacts: Jade Martin; Jade Petit · no email. " +
                "Ask the user which one with ask_user, then retry with the name exactly as listed.",
            tool.execute("""{"to":"jade"}""").text,
        )
    }

    // permission

    @Test
    fun deniedPermissionIsRefused() = runBlocking {
        val contacts = FakeContacts(book, PermissionResult.Denied)
        val sms = DraftSmsTool(FakePhone(), contacts).execute("""{"body":"hi","to":"jade"}""")
        val find = FindContactTool(contacts) { true }.execute("""{"name":"jade"}""")

        for (result in listOf(sms, find)) {
            assertTrue(result.refused)
            assertTrue(result.isError)
            assertEquals(ContactsRefusedText, result.text)
        }
        assertEquals(null, blockedPermission(find.text))
        assertEquals(null, blockedPermission("Error: no contact named Paul"))
        assertEquals(0, contacts.loaded)
    }

    @Test
    fun permanentlyDeniedPermissionIsRefusedAndPointsToTheSettings() = runBlocking {
        val find = FindContactTool(FakeContacts(book, PermissionResult.PermanentlyDenied)) { true }.execute("""{"name":"jade"}""")
        val email = DraftEmailTool(FakePhone(), FakeContacts(book, PermissionResult.PermanentlyDenied)).execute("""{"to":"jade"}""")

        for (result in listOf(find, email)) {
            assertTrue(result.refused)
            assertEquals("contacts", blockedPermission(result.text))
            assertEquals("contacts", blockedPermission("Error: ${result.text}"))
            assertEquals(ContactsBlockedText, JSONObject(result.text).getString("error"))
        }
    }

    @Test
    fun permissionWaitsForTheUser() {
        // The dialog may stay up as long as the user wants: no 10 s tool timeout.
        assertTrue(FindContactTool(FakeContacts(book)) { true }.waitsForUser)
        assertTrue(DraftSmsTool(FakePhone(), FakeContacts(book)).waitsForUser)
        assertTrue(DraftEmailTool(FakePhone(), FakeContacts(book)).waitsForUser)
        assertEquals(ToolTier.AndroidPermission, FindContactTool(FakeContacts(book)) { true }.tier)
    }

    // find_contact

    @Test
    fun findContactAnswersNamePhonesAndEmails() = runBlocking {
        val result = FindContactTool(FakeContacts(book)) { true }.execute("""{"name":"jade martin"}""")
        val contact = JSONObject(result.text).getJSONArray("contacts").getJSONObject(0)

        assertFalse(result.isError)
        assertEquals("Jade Martin", contact.getString("name"))
        assertEquals("mobile", contact.getJSONArray("phones").getJSONObject(0).getString("type"))
        assertEquals("06 12 34 56 78", contact.getJSONArray("phones").getJSONObject(0).getString("number"))
        assertEquals("home", contact.getJSONArray("phones").getJSONObject(1).getString("type"))
        assertEquals("jade@example.com", contact.getJSONArray("emails").getString(0))
        assertFalse(JSONObject(result.text).has("more"))
    }

    @Test
    fun findContactCapsAtFiveAndSaysHowManyMore() = runBlocking {
        val many = (1..7).map { Contact("Alex $it", listOf(phone("06 00 00 00 0$it")), emptyList()) }
        val json = JSONObject(FindContactTool(FakeContacts(many)) { true }.execute("""{"name":"alex"}""").text)

        assertEquals(5, json.getJSONArray("contacts").length())
        assertEquals(2, json.getInt("more"))
    }

    @Test
    fun findContactWithNoMatchIsAnEmptyListWithANote() = runBlocking {
        val result = FindContactTool(FakeContacts(book)) { true }.execute("""{"name":"Paul"}""")
        val json = JSONObject(result.text)

        assertFalse(result.isError)
        assertEquals(0, json.getJSONArray("contacts").length())
        assertEquals("no contact named Paul", json.getString("note"))
        assertEquals("name is required", FindContactTool(FakeContacts(book)) { true }.execute("{}").text)
    }

    // background

    @Test
    fun draftsFromTheBackgroundFailBeforeAnyLookup() = runBlocking {
        val contacts = FakeContacts(book, PermissionResult.Denied)
        val sms = DraftSmsTool(FakePhone(inForeground = false), contacts).execute("""{"body":"hi","to":"jade"}""")
        val mail = DraftEmailTool(FakePhone(inForeground = false), contacts).execute("""{"to":"jade"}""")

        for (result in listOf(sms, mail)) {
            assertTrue(result.isError)
            assertFalse(result.refused)
            assertEquals(BACKGROUND_ERROR, result.text)
        }
        assertEquals(0, contacts.asked)
    }

    @Test
    fun findContactFailsFastInTheBackgroundOnlyWhenItWouldAsk() = runBlocking {
        val notGranted = FakeContacts(book, PermissionResult.Denied)
        val result = FindContactTool(notGranted) { false }.execute("""{"name":"jade"}""")

        assertEquals(BACKGROUND_ERROR, result.text)
        assertEquals(0, notGranted.asked)
        // granted: no dialog, it answers
        assertFalse(FindContactTool(FakeContacts(book)) { false }.execute("""{"name":"jade"}""").isError)
    }

    @Test
    fun emailLoadsTheContactsOnceForSeveralNames() = runBlocking {
        val contacts = FakeContacts(book)
        DraftEmailTool(FakePhone(), contacts).execute("""{"to":"jade martin, zoe, bob@example.org"}""")

        assertEquals(1, contacts.loaded)
        assertEquals(1, contacts.asked)
    }

    // the permission bridge

    @Test
    fun gateSuspendsUntilTheActivityAnswers() = runBlocking {
        val gate = PermissionGate()
        val result = async { gate.request("android.permission.READ_CONTACTS") }
        val request = withTimeout(5_000) { gate.pending.first { it != null } }!!

        assertEquals("android.permission.READ_CONTACTS", request.permission)
        assertFalse(result.isCompleted)

        gate.complete(request.id, PermissionResult.Denied)

        assertEquals(PermissionResult.Denied, result.await())
        assertEquals(null, gate.pending.value)
    }

    @Test
    fun gateDropsTheRequestWhenTheCallIsCancelled() = runBlocking {
        val gate = PermissionGate()
        val result = async { gate.request("android.permission.READ_CONTACTS") }
        withTimeout(5_000) { gate.pending.first { it != null } }

        result.cancel()
        result.join()

        assertEquals(null, gate.pending.value)
        gate.complete(1, PermissionResult.Granted)
    }

    @Test
    fun aCancelledRequestNeverAnswersTheNextOne() = runBlocking {
        val gate = PermissionGate()
        val first = async { gate.request("android.permission.READ_CONTACTS") }
        val a = withTimeout(5_000) { gate.pending.first { it != null } }!!
        first.cancel()
        first.join()
        val second = async { gate.request("android.permission.READ_CONTACTS") }
        val b = withTimeout(5_000) { gate.pending.first { it != null } }!!

        // the dialog of the first one answers late
        gate.complete(a.id, PermissionResult.Denied)
        assertFalse(second.isCompleted)
        assertEquals(b.id, gate.pending.value?.id)

        gate.complete(b.id, PermissionResult.Granted)
        assertEquals(PermissionResult.Granted, second.await())
    }

    @Test
    fun aRequestIsLaunchedOnceEvenWhenTheCollectorRestarts() = runBlocking {
        val gate = PermissionGate()
        val result = async { gate.request("android.permission.READ_CONTACTS") }
        val request = withTimeout(5_000) { gate.pending.first { it != null } }!!
        val state = PermissionLaunchState()
        var launches = 0
        // Home, then back: the collector starts again and sees the same pending request.
        repeat(3) {
            gate.pending.value?.let { if (state.begin(it, rationale = false)) launches++ }
        }

        assertEquals(1, launches)
        val done = state.finish()!!
        assertEquals(request.id, done.id)
        gate.complete(done.id, PermissionResult.Denied)
        assertEquals(PermissionResult.Denied, result.await())
        assertEquals(null, state.finish())
    }

    @Test
    fun aRecreatedActivityKeepsTheLaunchInFlight() {
        val gate = PermissionGate()
        val request = PermissionRequest(7, "android.permission.READ_CONTACTS", kotlinx.coroutines.CompletableDeferred())
        val before = PermissionLaunchState().apply { begin(request, rationale = true) }
        val after = PermissionLaunchState().apply { restore(before.launched) }

        assertFalse(after.begin(request, rationale = true))
        assertEquals(true, after.launched?.rationaleBefore)
        assertEquals(null, gate.pending.value)
    }

    @Test
    fun dialogOutcomeMapsToAResult() {
        assertEquals(PermissionResult.Granted, PermissionResult.of(granted = true, rationaleBefore = false, rationaleAfter = false, askedBefore = false))
        // refused once: Android shows a rationale next time
        assertEquals(PermissionResult.Denied, PermissionResult.of(granted = false, rationaleBefore = false, rationaleAfter = true, askedBefore = false))
        // refused for good: refused before, no rationale any more
        assertEquals(PermissionResult.PermanentlyDenied, PermissionResult.of(granted = false, rationaleBefore = true, rationaleAfter = false, askedBefore = true))
        // refused before and asked before, rationale gone (Android 11+ "asked twice")
        assertEquals(PermissionResult.PermanentlyDenied, PermissionResult.of(granted = false, rationaleBefore = false, rationaleAfter = false, askedBefore = true))
    }

    @Test
    fun aDismissedFirstDialogIsOnlyDenied() {
        // Android 11+: tap outside or back on the first dialog answers granted=false with no rationale, like a permanent refusal.
        assertEquals(PermissionResult.Denied, PermissionResult.of(granted = false, rationaleBefore = false, rationaleAfter = false, askedBefore = false))
    }
}
