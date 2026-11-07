package io.github.tensaiapp.agent

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone actions (TEN-177): the intent each tool builds, its validation, and what a missing app does. */
class PhoneActionToolsTest {

    private class FakePhone(
        override var inForeground: Boolean = true,
        var handler: String? = "Clock",
        var apps: List<InstalledApp> = emptyList(),
        var startFails: Boolean = false,
        override var isMusicActive: Boolean = true,
    ) : PhoneActions {
        val started = mutableListOf<IntentSpec>()
        val launched = mutableListOf<String>()
        val keys = mutableListOf<MediaKey>()

        override fun handlerLabel(spec: IntentSpec) = handler

        override fun start(spec: IntentSpec) {
            if (startFails) throw NoHandlerException()
            started += spec
        }

        override fun launchableApps() = apps

        override fun launchApp(packageName: String) {
            if (startFails) throw NoHandlerException()
            launched += packageName
        }

        override fun sendMediaKey(key: MediaKey) {
            keys += key
        }
    }

    /** For the tests that give a number or an address: the contacts are never asked. */
    private object NoContacts : ContactsSource {
        override fun isGranted(): Boolean = error("contacts not expected")

        override suspend fun access(): PermissionResult = error("contacts not expected")

        override fun load(): List<Contact> = error("contacts not expected")
    }

    private fun spec(tool: IntentTool, args: String): IntentSpec = tool.spec(args).getOrThrow()

    private fun failure(tool: IntentTool, args: String): String = tool.spec(args).exceptionOrNull()!!.message!!

    // set_timer

    @Test
    fun timerBuildsTheIntentWithTheUiLeftOn() {
        val s = spec(SetTimerTool(FakePhone()), """{"seconds":300,"message":"tea"}""")

        assertEquals("android.intent.action.SET_TIMER", s.action)
        assertEquals(300, s.extras["android.intent.extra.alarm.LENGTH"])
        assertEquals("tea", s.extras["android.intent.extra.alarm.MESSAGE"])
        assertEquals(false, s.extras["android.intent.extra.alarm.SKIP_UI"])
    }

    @Test
    fun timerAcceptsAQuotedNumberAndRejectsTheBounds() {
        val tool = SetTimerTool(FakePhone())

        assertEquals(90, spec(tool, """{"seconds":"90"}""").extras["android.intent.extra.alarm.LENGTH"])
        assertEquals(1, spec(tool, """{"seconds":1}""").extras["android.intent.extra.alarm.LENGTH"])
        assertEquals(86_400, spec(tool, """{"seconds":86400}""").extras["android.intent.extra.alarm.LENGTH"])
        assertTrue(failure(tool, """{"seconds":0}""").contains("between 1 and"))
        assertTrue(failure(tool, """{"seconds":86401}""").contains("between 1 and"))
        assertTrue(failure(tool, """{"seconds":"soon"}""").contains("must be a number"))
        assertTrue(failure(tool, """{"seconds":1.5}""").contains("whole number"))
        assertTrue(failure(tool, "{}").contains("required"))
    }

    // set_alarm

    @Test
    fun alarmBuildsTheIntentWithDays() {
        val s = spec(SetAlarmTool(FakePhone()), """{"hour":7,"minutes":30,"message":"gym","days":["Mon","friday","mon"]}""")

        assertEquals("android.intent.action.SET_ALARM", s.action)
        assertEquals(7, s.extras["android.intent.extra.alarm.HOUR"])
        assertEquals(30, s.extras["android.intent.extra.alarm.MINUTES"])
        assertEquals("gym", s.extras["android.intent.extra.alarm.MESSAGE"])
        assertEquals(listOf(2, 6), s.extras["android.intent.extra.alarm.DAYS"])
        assertEquals(false, s.extras["android.intent.extra.alarm.SKIP_UI"])
    }

    @Test
    fun alarmValidatesTheClock() {
        val tool = SetAlarmTool(FakePhone())

        assertNull(spec(tool, """{"hour":0,"minutes":0}""").extras["android.intent.extra.alarm.DAYS"])
        assertTrue(failure(tool, """{"hour":24,"minutes":0}""").contains("hour"))
        assertTrue(failure(tool, """{"hour":-1,"minutes":0}""").contains("hour"))
        assertTrue(failure(tool, """{"hour":7,"minutes":60}""").contains("minutes"))
        assertTrue(failure(tool, """{"hour":7}""").contains("minutes is required"))
        assertTrue(failure(tool, """{"hour":7,"minutes":0,"days":["someday"]}""").contains("unknown day"))
        assertTrue(failure(tool, """{"hour":7,"minutes":0,"days":["monkey"]}""").contains("unknown day"))
        assertEquals(listOf(1, 7), spec(tool, """{"hour":7,"minutes":0,"days":["Sunday","sat"]}""").extras["android.intent.extra.alarm.DAYS"])
        assertTrue(failure(tool, """{"hour":7,"minutes":0,"days":"mon"}""").contains("days must be a list"))
    }

    // create_event

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun eventConvertsLocalTimesToEpochMillis() {
        val tool = CreateEventTool(FakePhone()) { paris }
        val s = spec(tool, """{"title":"Dentist","start":"2026-10-17T14:00","end":"2026-10-17T15:30","location":"Lisbon","description":"checkup"}""")

        assertEquals("android.intent.action.INSERT", s.action)
        assertEquals("content://com.android.calendar/events", s.data)
        assertEquals("vnd.android.cursor.dir/event", s.mimeType)
        assertEquals("Dentist", s.extras["title"])
        // 14:00 in Paris on 2026-10-17 is 12:00 UTC (CEST).
        assertEquals(1_792_238_400_000L, s.extras["beginTime"])
        assertEquals(1_792_238_400_000L + 90 * 60_000, s.extras["endTime"])
        assertEquals(false, s.extras["allDay"])
        assertEquals("Lisbon", s.extras["eventLocation"])
        assertEquals("checkup", s.extras["description"])
    }

    @Test
    fun eventReadsAnOffsetInTheDeviceZone() {
        val tool = CreateEventTool(FakePhone()) { paris }
        val utc = spec(tool, """{"title":"x","start":"2026-10-17T12:00:00Z"}""")
        val plus2 = spec(tool, """{"title":"x","start":"2026-10-17T14:00:00+02:00"}""")
        val local = spec(tool, """{"title":"x","start":"2026-10-17T14:00"}""")

        assertEquals(1_792_238_400_000L, utc.extras["beginTime"])
        assertEquals(utc.extras["beginTime"], plus2.extras["beginTime"])
        assertEquals(utc.extras["beginTime"], local.extras["beginTime"])
    }

    @Test
    fun eventEndsAnHourLaterByDefault() {
        val s = spec(CreateEventTool(FakePhone()) { paris }, """{"title":"x","start":"2026-10-17 09:00"}""")

        assertEquals((s.extras["beginTime"] as Long) + 3_600_000, s.extras["endTime"])
    }

    @Test
    fun allDayEventSpansWholeUtcDays() {
        val s = spec(CreateEventTool(FakePhone()) { paris }, """{"title":"Holiday","start":"2026-10-17","end":"2026-10-18","all_day":true}""")

        assertEquals(true, s.extras["allDay"])
        assertEquals(0L, (s.extras["beginTime"] as Long) % 86_400_000)
        assertEquals(2 * 86_400_000L, (s.extras["endTime"] as Long) - (s.extras["beginTime"] as Long))
    }

    @Test
    fun eventValidatesItsDates() {
        val tool = CreateEventTool(FakePhone()) { paris }

        assertTrue(failure(tool, """{"title":"x","start":"tomorrow"}""").contains("start must be"))
        assertTrue(failure(tool, """{"title":"x","start":"2026-10-17T14:00","end":"later"}""").contains("end must be"))
        assertTrue(failure(tool, """{"title":"x","start":"2026-10-17T14:00","end":"2026-10-17T13:00"}""").contains("after start"))
        assertTrue(failure(tool, """{"start":"2026-10-17T14:00"}""").contains("title is required"))
    }

    // draft_sms, draft_email

    @Test
    fun smsKeepsTheDialableCharacters() {
        val tool = DraftSmsTool(FakePhone(), NoContacts)
        val s = spec(tool, """{"body":"on my way","number":"+33 6 39-98-10-78"}""")

        assertEquals("android.intent.action.SENDTO", s.action)
        assertEquals("sms", s.scheme)
        assertEquals("+33639981078", s.schemeSpecificPart)
        assertEquals("on my way", s.extras["sms_body"])
        assertEquals("", spec(tool, """{"body":"hi"}""").schemeSpecificPart)
        assertEquals("+3361", spec(tool, """{"body":"hi","number":"+33*61#"}""").schemeSpecificPart)
        assertTrue(failure(tool, """{"body":"hi","number":"mum"}""").contains("not a phone number"))
        assertTrue(failure(tool, """{"number":"0639981078"}""").contains("body is required"))
    }

    @Test
    fun emailBuildsMailtoWithTheExtras() {
        val tool = DraftEmailTool(FakePhone(), NoContacts)
        val s = spec(tool, """{"to":"a@b.fr, c@d.com","subject":"Hello","body":"Hi"}""")

        assertEquals("mailto:", s.data)
        assertEquals(listOf("a@b.fr", "c@d.com"), s.extras["android.intent.extra.EMAIL"])
        assertEquals("Hello", s.extras["android.intent.extra.SUBJECT"])
        assertEquals("Hi", s.extras["android.intent.extra.TEXT"])
        assertTrue(failure(tool, """{"to":"not an address"}""").contains("not an email address"))
        assertTrue(failure(tool, "{}").contains("at least one"))
        assertFalse(spec(tool, """{"subject":"only"}""").extras.containsKey("android.intent.extra.EMAIL"))
    }

    // open_url

    @Test
    fun urlAcceptsOnlyHttpAndHttps() {
        val tool = OpenUrlTool(FakePhone())

        assertEquals("https://example.com/a?b=1", spec(tool, """{"url":"https://example.com/a?b=1"}""").data)
        assertEquals("android.intent.action.VIEW", spec(tool, """{"url":"HTTP://example.com"}""").action)
        for (bad in listOf(" javascript:alert(1)", "javascript:alert(1)", "http:/x", "https:host", "file:///sdcard/x", "content://x/y", "intent://x#Intent;end", "tel:123", "example.com", "https://")) {
            assertTrue(bad, tool.spec("""{"url":"$bad"}""").isFailure)
        }
    }

    // open_app

    private val apps = listOf(
        InstalledApp("Clock", "com.a.clock"),
        InstalledApp("Chrome", "com.a.chrome"),
        InstalledApp("Photos", "com.a.photos"),
        InstalledApp("Google Photos", "com.g.photos"),
        InstalledApp("Photo Editor", "com.e.photo"),
        InstalledApp("Files", "com.a.files"),
        InstalledApp("Files by Google", "com.g.files"),
    )

    @Test
    fun appResolutionPrefersExactThenPrefixThenContains() {
        assertEquals(AppMatch.Found(apps[0]), resolveApp("clock", apps))
        assertEquals(AppMatch.Found(apps[0]), resolveApp("  CLOCK ", apps))
        assertEquals(AppMatch.Found(apps[1]), resolveApp("chr", apps))
        // "photos" is exact for Photos even though Google Photos contains it.
        assertEquals(AppMatch.Found(apps[2]), resolveApp("photos", apps))
        assertEquals(AppMatch.Found(apps[3]), resolveApp("google ph", apps))
        assertEquals(AppMatch.Found(apps[5]), resolveApp("Files", apps))
    }

    @Test
    fun appResolutionIsAmbiguousOrEmpty() {
        val ambiguous = resolveApp("pho", apps) as AppMatch.Ambiguous
        assertEquals(listOf("Photo Editor", "Photos"), ambiguous.candidates.map { it.label })
        assertEquals(AppMatch.None, resolveApp("calendar", apps))
        assertEquals(AppMatch.None, resolveApp("  ", apps))

        val many = (1..8).map { InstalledApp("Game $it", "com.game$it") }
        assertEquals(5, (resolveApp("game", many) as AppMatch.Ambiguous).candidates.size)
        // The same package twice (two launcher activities) is one app.
        assertEquals(AppMatch.Found(apps[0]), resolveApp("clock", apps + apps[0]))
    }

    @Test
    fun openAppLaunchesTheMatchAndNamesIt() = runBlocking {
        val phone = FakePhone(apps = apps)
        val result = OpenAppTool(phone).execute("""{"name":"chrome"}""")

        assertFalse(result.isError)
        assertEquals(listOf("com.a.chrome"), phone.launched)
        assertEquals("Chrome", JSONObject(result.text).getString("opened"))
    }

    @Test
    fun openAppErrorsListTheCandidates() = runBlocking {
        val phone = FakePhone(apps = apps)

        val ambiguous = OpenAppTool(phone).execute("""{"name":"pho"}""")
        assertTrue(ambiguous.isError && !ambiguous.refused)
        assertTrue(ambiguous.text.contains("Photo Editor, Photos"))
        val none = OpenAppTool(phone).execute("""{"name":"calendar"}""")
        assertTrue(none.isError && none.text.contains("no installed app"))
        assertTrue(phone.launched.isEmpty())
    }

    @Test
    fun onlyOpenUrlAsksTheGate() {
        val phone = FakePhone()
        val others = listOf(
            SetTimerTool(phone), SetAlarmTool(phone), CreateEventTool(phone), DraftSmsTool(phone, NoContacts),
            DraftEmailTool(phone, NoContacts), OpenAppTool(phone), MediaControlTool(phone),
        )

        assertTrue(OpenUrlTool(phone).requiresConfirmation("""{"url":"https://example.com"}"""))
        assertTrue(OpenUrlTool(phone).sendsOrDeletes)
        for (tool in others) assertEquals(tool.name, tool.name == "open_url", tool.requiresConfirmation("{}"))
        assertTrue(OpenUrlTool(phone).spec("""{"url":"https://x.fr"}""").getOrThrow().categories.contains("android.intent.category.BROWSABLE"))
    }

    // media_control

    @Test
    fun mediaActionsMapToKeysAndOpenNothing() = runBlocking {
        val phone = FakePhone()
        val tool = MediaControlTool(phone)

        for (action in listOf("play", "pause", "play_pause", "next", "previous")) assertFalse(tool.execute("""{"action":"$action"}""").isError)
        assertEquals(listOf(MediaKey.Play, MediaKey.Pause, MediaKey.PlayPause, MediaKey.Next, MediaKey.Previous), phone.keys)
        assertEquals("sent next to the active player", tool.execute("""{"action":"next"}""").text)
        assertTrue(tool.execute("""{"action":"rewind"}""").isError)
        assertTrue(tool.execute("{}").isError)
        assertTrue(phone.started.isEmpty())
    }

    @Test
    fun pauseWithNothingPlayingIsAnError() = runBlocking {
        val phone = FakePhone(isMusicActive = false)
        val tool = MediaControlTool(phone)

        assertEquals("nothing is playing", tool.execute("""{"action":"pause"}""").text)
        assertTrue(tool.execute("""{"action":"pause"}""").isError)
        for (action in listOf("play", "play_pause", "next", "previous")) assertFalse(tool.execute("""{"action":"$action"}""").isError)
        assertEquals(listOf(MediaKey.Play, MediaKey.PlayPause, MediaKey.Next, MediaKey.Previous), phone.keys)
    }

    @Test
    fun mediaControlWorksFromTheBackground() = runBlocking {
        val phone = FakePhone(inForeground = false)

        assertFalse(MediaControlTool(phone).execute("""{"action":"pause"}""").isError)
    }

    // the shared flow

    @Test
    fun anOpenedIntentAnswersTheAppCardShape() = runBlocking {
        val phone = FakePhone(handler = "Clock")
        val result = DraftEmailTool(phone, NoContacts).execute("""{"subject":"x"}""")

        assertFalse(result.isError)
        val json = JSONObject(result.text)
        assertEquals("Clock", json.getString("opened"))
        assertEquals("draft open in Clock, not sent: the user reviews and sends it there", json.getString("status"))
        assertEquals(1, phone.started.size)
    }

    @Test
    fun aTimerAndAnAlarmAreReportedAsSet() = runBlocking {
        val timer = SetTimerTool(FakePhone()).execute("""{"seconds":60}""")
        val alarm = SetAlarmTool(FakePhone()).execute("""{"hour":7,"minutes":0}""")
        val sms = DraftSmsTool(FakePhone(handler = "Messages"), NoContacts).execute("""{"body":"hi"}""")

        assertEquals("set, shown in Clock", JSONObject(timer.text).getString("status"))
        assertEquals("set, shown in Clock", JSONObject(alarm.text).getString("status"))
        assertEquals("draft open in Messages, not sent: the user reviews and sends it there", JSONObject(sms.text).getString("status"))
    }

    @Test
    fun noHandlerIsRefusedNotAnError() = runBlocking {
        val phone = FakePhone(handler = null)
        val result = SetTimerTool(phone).execute("""{"seconds":60}""")

        assertTrue(result.isError)
        assertTrue(result.refused)
        assertTrue(result.text.contains("no app on this phone handles timers"))
        assertTrue(phone.started.isEmpty())
    }

    @Test
    fun anActivityNotFoundAtStartIsRefusedToo() = runBlocking {
        val result = DraftEmailTool(FakePhone(startFails = true), NoContacts).execute("""{"subject":"x"}""")

        assertTrue(result.refused)
        assertTrue(result.text.contains("email"))
    }

    @Test
    fun badArgumentsAreAnErrorAndStartNothing() = runBlocking {
        val phone = FakePhone()

        for (args in listOf("not json", "[]", "{}", """{"seconds":-5}""")) {
            val result = SetTimerTool(phone).execute(args)
            assertTrue(args, result.isError && !result.refused)
        }
        assertTrue(phone.started.isEmpty())
    }

    @Test
    fun theBackgroundIsAClearErrorNotARefusal() = runBlocking {
        val phone = FakePhone(inForeground = false)

        val timer = SetTimerTool(phone).execute("""{"seconds":60}""")
        val app = OpenAppTool(phone).execute("""{"name":"clock"}""")

        for (result in listOf(timer, app)) {
            assertTrue(result.isError && !result.refused)
            assertTrue(result.text.contains("not on screen"))
        }
        assertTrue(phone.started.isEmpty() && phone.launched.isEmpty())
    }

    @Test
    fun everyToolIsAPhoneAction() {
        val phone = FakePhone()
        val tools = listOf(
            SetTimerTool(phone), SetAlarmTool(phone), CreateEventTool(phone), DraftSmsTool(phone, NoContacts),
            DraftEmailTool(phone, NoContacts), OpenAppTool(phone), OpenUrlTool(phone), MediaControlTool(phone),
        )

        assertEquals(8, tools.map { it.name }.toSet().size)
        for (tool in tools) {
            assertEquals(tool.name, ToolTier.PhoneAction, tool.tier)
            assertEquals(tool.name, tool.name == "open_url", tool.requiresConfirmation("{}"))
            assertTrue(tool.name, JSONObject(tool.parametersSchema).has("properties"))
        }
    }

    /** TEN-206: an email to a name and an address, contacts refused: the model asks for the address, nothing opens. */
    @Test
    fun anEmailToANameWithoutContactsAsksForTheAddressAndOpensNothing() = runBlocking {
        val refused = object : ContactsSource {
            override fun isGranted() = false
            override suspend fun access() = PermissionResult.Denied
            override fun load(): List<Contact> = error("not loaded after a refusal")
        }
        val phone = FakePhone()

        val result = DraftEmailTool(phone, refused).execute("""{"to":"Sam, jade@example.com"}""")

        assertTrue(result.refused)
        assertEquals(nameLookupRefused("Sam", "Sam's email address"), result.text)
        assertTrue(phone.started.isEmpty())
    }
}
