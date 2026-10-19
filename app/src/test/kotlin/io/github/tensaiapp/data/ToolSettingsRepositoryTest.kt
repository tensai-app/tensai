package io.github.tensaiapp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.agent.ToolResult
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.ui.screens.settings.ToolGroup
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The tier grants of Settings > Tools (TEN-174) survive the process, drive what [ToolRegistry] offers the
 * model, and map onto the three cards. Same method as [PreferencesPersistenceTest]: write through one
 * DataStore over a file, read back through a second.
 */
class ToolSettingsRepositoryTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-toolsettings-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class StubTool(override val name: String, override val tier: ToolTier) : Tool {
        override val description = "stub"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(argsJson: String) = ToolResult.ok("")
    }

    private val tools = listOf(
        StubTool("local", ToolTier.Local),
        StubTool("phone", ToolTier.PhoneAction),
        StubTool("perm", ToolTier.AndroidPermission),
        StubTool("net", ToolTier.Network),
    )

    @Test
    fun anUntouchedInstallGrantsEverythingButTheNetwork() = runBlocking {
        val granted = withStore("a") { ToolSettingsRepository(it).grants.first() }

        assertEquals(setOf(ToolTier.Local, ToolTier.PhoneAction, ToolTier.AndroidPermission), granted)
    }

    @Test
    fun aGrantComesBackAfterARestart() = runBlocking {
        withStore("b") {
            val settings = ToolSettingsRepository(it)
            settings.setGranted(ToolTier.Network, true)
            settings.setGranted(ToolTier.Local, false)
        }
        val granted = withStore("b") { ToolSettingsRepository(it).grants.first() }

        assertEquals(setOf(ToolTier.PhoneAction, ToolTier.AndroidPermission, ToolTier.Network), granted)
    }

    @Test
    fun theRegistryOffersOnlyWhatIsGrantedAndSeesAChangeAtTheNextRead() = runBlocking {
        withStore("c") {
            val settings = ToolSettingsRepository(it)
            val registry = ToolRegistry(tools, settings)

            // Not collected by anyone: the first read goes to the store, the next ones to the snapshot.
            assertEquals(listOf("local", "phone", "perm"), registry.active().map { t -> t.name })

            settings.setGranted(ToolTier.Network, true)
            settings.setGranted(ToolTier.PhoneAction, false)
            assertEquals(listOf("local", "perm", "net"), registry.active().map { t -> t.name })

            // Registered tools stay listed whatever the grants: Settings shows them per card.
            assertEquals(listOf("local", "phone", "perm", "net"), registry.registered().map { t -> t.name })
        }
    }

    @Test
    fun onTheMainThreadAColdReadAnswersTheDefaultsInsteadOfBlocking() = runBlocking {
        val granted = withStore("d") { ToolSettingsRepository(it, onMain = { true }).granted() }

        assertEquals(setOf(ToolTier.Local, ToolTier.PhoneAction, ToolTier.AndroidPermission), granted)
    }

    @Test
    fun theSearchProviderDefaultsToDuckDuckGoAndIsRemembered() = runBlocking {
        assertEquals(SearchProviderChoice.DuckDuckGo, withStore("e") { ToolSettingsRepository(it).searchProvider.first() })

        withStore("f") { ToolSettingsRepository(it).setSearchProvider(SearchProviderChoice.Tavily) }

        assertEquals(SearchProviderChoice.Tavily, withStore("f") { ToolSettingsRepository(it).searchProvider.first() })
    }

    @Test
    fun onlyTheKeyedProvidersNeedAKey() {
        assertEquals(listOf(SearchProviderChoice.Brave, SearchProviderChoice.Tavily), SearchProviderChoice.entries.filter { it.needsKey })
    }

    @Test
    fun theCardsCoverEveryTierOnceAndCountWhatIsOn() {
        assertEquals(ToolTier.entries.toSet(), ToolGroup.entries.flatMap { it.tiers }.toSet())
        assertEquals(ToolTier.entries.size, ToolGroup.entries.sumOf { it.tiers.size })

        val defaults = setOf(ToolTier.Local, ToolTier.PhoneAction, ToolTier.AndroidPermission)
        assertEquals(2, ToolGroup.countOn(defaults))
        assertTrue(ToolGroup.PhoneActions.isOn(defaults))
        assertEquals(3, ToolGroup.countOn(ToolTier.entries.toSet()))
        // Half of a card is not on: its switch turns both tiers together.
        assertEquals(1, ToolGroup.countOn(setOf(ToolTier.Local, ToolTier.PhoneAction)))
    }

    @Test
    fun aCardListsTheRegisteredToolsOfItsTiers() {
        assertEquals(listOf("local"), ToolGroup.OnPhone.toolsOf(tools).map { it.name })
        assertEquals(listOf("phone", "perm"), ToolGroup.PhoneActions.toolsOf(tools).map { it.name })
        assertEquals(listOf("net"), ToolGroup.Internet.toolsOf(tools).map { it.name })
        assertTrue(ToolGroup.Internet.toolsOf(tools.filter { it.tier != ToolTier.Network }).isEmpty())
    }

    private suspend fun <T> withStore(name: String, block: suspend (DataStore<Preferences>) -> T): T {
        val job = Job()
        return try {
            block(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { File(dir, "$name.preferences_pb") })
        } finally {
            job.cancelAndJoin()
        }
    }
}
