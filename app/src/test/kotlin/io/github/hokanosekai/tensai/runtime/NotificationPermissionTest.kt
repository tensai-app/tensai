package io.github.hokanosekai.tensai.runtime

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.hokanosekai.tensai.data.NotificationPermissionRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `POST_NOTIFICATIONS` ask, as a sequence rather than a screen (TEN-75).
 *
 * Android hands an app two dialogs per install and makes the second denial permanent, so the
 * property under test is a count over *repeated loads*, not a single decision: how many dialogs a
 * user sees. That could not be asserted while the logic lived inside a `@Composable` — the
 * behaviour that broke it is recomposition, and there is no device here to recompose on. It is
 * asserted here because [requestNotificationPermissionOnce] takes its permission check, its
 * persisted flag and its launcher as lambdas, the same shape `ModelImporter` and `OnboardingGate`
 * already use.
 */
class NotificationPermissionTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-notification-permission-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * The bug, and the whole ticket: `LoadingScreen`'s effect re-fires on every entry, the screen
     * is re-entrable, and the old guard only suppressed re-asking for someone who had *accepted*.
     * A refusal therefore bought a second dialog, which Android turns into a permanent
     * "don't ask again".
     */
    @Test
    fun aRefusalIsNotAskedAgainOnTheNextLoad() = runBlocking {
        val install = FakeInstall(granted = false)

        install.load()
        install.load()

        assertEquals(
            "a user who declined once must not be handed the second dialog Android makes permanent",
            1,
            install.dialogsShown,
        )
    }

    /** The same, over enough entries to cover a session of loading models back to back. */
    @Test
    fun noNumberOfLoadsAfterARefusalEverShowsASecondDialog() = runBlocking {
        val install = FakeInstall(granted = false)

        repeat(20) { install.load() }

        assertEquals(1, install.dialogsShown)
    }

    /**
     * The flag is written before the dialog goes up, not when its result arrives — a result that
     * never comes (app backgrounded with the dialog showing, process killed under memory pressure)
     * must still count as an ask spent.
     */
    @Test
    fun theAskIsRecordedBeforeTheDialogIsShown() = runBlocking {
        val order = mutableListOf<String>()
        var asked = false

        requestNotificationPermissionOnce(
            sdkInt = 33,
            isGranted = { false },
            wasAsked = { asked },
            markAsked = {
                asked = true
                order += "mark"
            },
            launchRequest = { order += "launch" },
        )

        assertEquals(listOf("mark", "launch"), order)
    }

    /** Granting is the other way out: nothing left to ask for, so no dialog on later loads either. */
    @Test
    fun grantingAlsoStopsTheAsking() = runBlocking {
        val install = FakeInstall(granted = false)

        install.load()
        install.granted = true
        install.load()

        assertEquals(1, install.dialogsShown)
    }

    /** Below API 33 the permission does not exist — nothing is asked and no ask is spent. */
    @Test
    fun apiBelow33NeitherAsksNorSpendsTheAsk() = runBlocking {
        val install = FakeInstall(granted = false, sdkInt = 32)

        install.load()

        assertEquals(0, install.dialogsShown)
        assertEquals(
            "an install that upgrades to 33+ must still have its one ask available",
            false,
            install.asked,
        )
    }

    @Test
    fun aFirstLoadOnAFreshInstallDoesAsk() = runBlocking {
        val install = FakeInstall(granted = false)

        val decision = install.load()

        assertEquals(NotificationAsk.Ask, decision)
        assertEquals(1, install.dialogsShown)
    }

    @Test
    fun everyDecisionSaysWhichRuleStoppedIt() {
        assertEquals(
            NotificationAsk.SkipUnsupportedApi,
            NotificationPermissionGate.decide(sdkInt = 32, granted = false, askedBefore = false),
        )
        assertEquals(
            NotificationAsk.SkipAlreadyGranted,
            NotificationPermissionGate.decide(sdkInt = 33, granted = true, askedBefore = false),
        )
        assertEquals(
            NotificationAsk.SkipAlreadyAsked,
            NotificationPermissionGate.decide(sdkInt = 33, granted = false, askedBefore = true),
        )
        assertEquals(
            NotificationAsk.Ask,
            NotificationPermissionGate.decide(sdkInt = 33, granted = false, askedBefore = false),
        )
    }

    /**
     * The reason the latch is persisted and not a field: Android counts denials per install, and
     * this process does not survive between them. A once-per-process latch passes every test above
     * and still fails this one, which is the case that actually burns the second dialog.
     *
     * "Survives a restart" is simulated as in `PreferencesPersistenceTest`: the write goes through
     * one `DataStore` over a real file, that instance is torn down, and a second instance is built
     * over the same bytes with nothing shared above the file.
     */
    @Test
    fun aRefusalSurvivesTheProcessThatMadeIt() = runBlocking {
        val file = File(dir, "notification_permission.preferences_pb")
        var dialogsShown = 0

        suspend fun coldStartAndLoad() {
            withStore(file) { store ->
                val repository = NotificationPermissionRepository(store)
                requestNotificationPermissionOnce(
                    sdkInt = 33,
                    isGranted = { false },
                    wasAsked = repository::wasRequested,
                    markAsked = repository::markRequested,
                    launchRequest = { dialogsShown++ },
                )
            }
        }

        coldStartAndLoad()
        coldStartAndLoad()

        assertEquals(
            "the second dialog Android makes permanent must not come back with the next process",
            1,
            dialogsShown,
        )
    }

    @Test
    fun anUntouchedInstallHasNotAskedYet() = runBlocking {
        val store = File(dir, "fresh.preferences_pb")

        assertEquals(false, withStore(store) { NotificationPermissionRepository(it).wasRequested() })
    }

    @Test
    fun theFlagOnlyEverGoesOneWay() = runBlocking {
        val file = File(dir, "one_way.preferences_pb")

        withStore(file) { NotificationPermissionRepository(it).markRequested() }
        withStore(file) { NotificationPermissionRepository(it).markRequested() }

        assertTrue(withStore(file) { NotificationPermissionRepository(it).wasRequested() })
    }

    /**
     * One install across repeated loads: the permission state and the persisted flag are fields,
     * exactly as they are on a device, so [load] can be called as many times as the loading screen
     * would be entered. [dialogsShown] is what the user counts.
     */
    private class FakeInstall(var granted: Boolean, private val sdkInt: Int = 33) {
        var asked = false
            private set
        var dialogsShown = 0
            private set

        suspend fun load(): NotificationAsk = requestNotificationPermissionOnce(
            sdkInt = sdkInt,
            isGranted = { granted },
            wasAsked = { asked },
            markAsked = { asked = true },
            launchRequest = { dialogsShown++ },
        )
    }

    /** See `PreferencesPersistenceTest.withStore` — DataStore refuses two live instances per file. */
    private suspend fun <T> withStore(file: File, block: suspend (DataStore<Preferences>) -> T): T {
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            block(PreferenceDataStoreFactory.create(scope = scope) { file })
        } finally {
            job.cancelAndJoin()
        }
    }
}
