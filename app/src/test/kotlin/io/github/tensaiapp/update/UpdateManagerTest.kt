package io.github.tensaiapp.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The update check end to end over a MockWebServer standing in for the GitHub API and its release assets: the real
 * [ReleaseClient], a real DataStore over a temp file, the real [ApkVerifier] with the signers faked.
 */
class UpdateManagerTest {

    private lateinit var dir: File
    private lateinit var server: MockWebServer
    private val storeJob = Job()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var preferences: UpdatePreferences

    private val apkBytes = ByteArray(200_000) { (it % 253).toByte() }
    private var tag = "v0.3.7"
    private var publishedSha = sha256(apkBytes)
    private val own = ApkSigners("io.github.tensaiapp", setOf("aa".repeat(32)))
    private var downloadedSigners: ApkSigners? = own
    private var signersThrow = false
    private var slowApk = false
    private val apkRequests = java.util.concurrent.atomic.AtomicInteger()
    private var now = 1_000_000_000L
    private val installed = mutableListOf<ByteArray>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-update-test").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val apkName = "tensai-${tag.removePrefix("v")}.apk"
                return when (request.requestUrl!!.encodedPath) {
                    "/latest" -> MockResponse().setBody(
                        """
                        {"tag_name":"$tag","draft":false,"prerelease":false,"body":"## What's Changed\r\n* feat: updates",
                         "assets":[
                          {"name":"$apkName","size":${apkBytes.size},"browser_download_url":"${server.url("/$apkName")}"},
                          {"name":"$apkName.sha256","size":90,"browser_download_url":"${server.url("/$apkName.sha256")}"}
                         ]}
                        """.trimIndent(),
                    )
                    "/$apkName" -> {
                        apkRequests.incrementAndGet()
                        MockResponse().setBody(Buffer().write(apkBytes)).apply { if (slowApk) throttleBody(16_384, 100, TimeUnit.MILLISECONDS) }
                    }
                    "/$apkName.sha256" -> MockResponse().setBody("$publishedSha  $apkName\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        preferences = UpdatePreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO)) { File(dir, "update.preferences_pb") })
    }

    @After
    fun tearDown() {
        runBlocking {
            server.shutdown()
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            storeJob.cancelAndJoin()
        }
        dir.deleteRecursively()
    }

    private fun manager(installedVersion: String = "0.3.6", isDebug: Boolean = false, canInstall: Boolean = true) = UpdateManager(
        preferences = preferences,
        source = ReleaseClient(OkHttpClient(), OkHttpClient(), httpsOnly = false),
        verifier = ApkVerifier(archiveSigners = { if (signersThrow) throw IOException("unreadable") else downloadedSigners }, installed = { own }),
        installedVersion = installedVersion,
        isDebug = isDebug,
        defaultUrl = server.url("/latest").toString(),
        workDir = File(dir, "update"),
        scope = jobs,
        canInstall = { canInstall },
        install = { installed += it.readBytes() },
        now = { now },
    )

    /** Waits for what [UpdateManager] launched on its scope: the preference writes and the download. */
    private suspend fun settle() {
        jobs.coroutineContext[Job]!!.children.toList().joinAll()
    }

    private val apiCalls get() = server.requestCount

    @Test
    fun `nothing is requested before the user opted in, nor after a no`() = runBlocking {
        val manager = manager()
        manager.checkIfDue()
        manager.checkIfDue(force = true)
        assertEquals(0, apiCalls)

        manager.setAllowed(false)
        settle()
        manager.checkIfDue(force = true)
        assertEquals(0, apiCalls)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun `a debug build without a source never checks, even allowed`() = runBlocking {
        val manager = manager(isDebug = true)
        manager.setAllowed(true)
        settle()
        manager.checkIfDue(force = true)
        assertEquals(0, apiCalls)
        assertFalse(manager.asksConsent.first())
    }

    @Test
    fun `allowed, a newer release is offered with its notes trimmed`() = runBlocking {
        val manager = manager()
        assertTrue(manager.asksConsent.first())
        manager.setAllowed(true)
        settle()
        assertFalse(manager.asksConsent.first())
        val offered = (manager.state.value as UpdateState.Available).release
        assertEquals("v0.3.7", offered.tag)
        assertEquals("## What's Changed\n* feat: updates", offered.notes)
        assertEquals(1, apiCalls)
    }

    @Test
    fun `at most one check a day, a failed one counts`() = runBlocking {
        // Same version as installed: nothing offered, so the state stays Idle and only the daily rule decides.
        val manager = manager(installedVersion = "0.3.7")
        preferences.setAllowed(true)
        manager.checkIfDue()
        assertEquals(1, apiCalls)

        now += UpdateManager.CHECK_INTERVAL_MS - 1
        manager.checkIfDue()
        assertEquals(1, apiCalls)

        now += 1
        server.shutdown()
        manager.checkIfDue()
        now += 60_000
        manager.checkIfDue()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(now - 60_000, preferences.lastCheckMs())
    }

    @Test
    fun `a skipped version is never offered again, a newer one is`() = runBlocking {
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        assertTrue(manager.state.value is UpdateState.Available)
        manager.skip()
        settle()

        now += UpdateManager.CHECK_INTERVAL_MS
        manager.checkIfDue()
        assertEquals(2, apiCalls)
        assertEquals(UpdateState.Idle, manager.state.value)

        tag = "v0.3.8"
        now += UpdateManager.CHECK_INTERVAL_MS
        manager.checkIfDue()
        assertEquals("v0.3.8", (manager.state.value as UpdateState.Available).release.tag)
    }

    @Test
    fun `later hides the offer until the next daily check`() = runBlocking {
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.later()
        assertEquals(UpdateState.Idle, manager.state.value)
        manager.checkIfDue()
        assertEquals(UpdateState.Idle, manager.state.value)
        now += UpdateManager.CHECK_INTERVAL_MS
        manager.checkIfDue()
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun `a verified APK goes to the installer`() = runBlocking {
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        settle()
        assertTrue(manager.state.value is UpdateState.Installing)
        assertEquals(1, installed.size)
        assertTrue(installed.single().contentEquals(apkBytes))
        assertFalse(File(dir, "update/update.apk").exists())
    }

    @Test
    fun `an APK signed by someone else is refused and deleted`() = runBlocking {
        downloadedSigners = own.copy(certSha256 = setOf("bb".repeat(32)))
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        settle()
        assertEquals(UpdateFailure.Signature, (manager.state.value as UpdateState.Failed).reason)
        assertTrue(installed.isEmpty())
        assertFalse(File(dir, "update/update.apk").exists())
        // No retry for a wrong signer.
        manager.update()
        settle()
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `an APK whose hash is not the published one is refused and deleted`() = runBlocking {
        publishedSha = "0".repeat(64)
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        settle()
        assertEquals(UpdateFailure.Hash, (manager.state.value as UpdateState.Failed).reason)
        assertTrue(installed.isEmpty())
        assertFalse(File(dir, "update/update.apk").exists())
    }

    @Test
    fun `without the install permission, Update asks for it and nothing is downloaded`() = runBlocking {
        val manager = manager(canInstall = false)
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        settle()
        assertTrue(manager.state.value is UpdateState.NeedsPermission)
        assertEquals(1, apiCalls)
    }

    @Test
    fun `a cancelled install offers the release again, a failed one says so`() = runBlocking {
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        settle()
        manager.onInstallResult(aborted = true, message = null)
        assertTrue(manager.state.value is UpdateState.Available)
        manager.update()
        settle()
        manager.onInstallResult(aborted = false, message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertEquals(UpdateFailure.Install, (manager.state.value as UpdateState.Failed).reason)
    }

    @Test
    fun `two concurrent checks make one request`() = runBlocking {
        val manager = manager(installedVersion = "0.3.7")
        preferences.setAllowed(true)
        (1..2).map { async(Dispatchers.IO) { manager.checkIfDue() } }.awaitAll()
        assertEquals(1, apiCalls)
    }

    @Test
    fun `a double Update downloads and installs once`() = runBlocking {
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        manager.update()
        settle()
        assertEquals(1, apkRequests.get())
        assertEquals(1, installed.size)
    }

    @Test
    fun `a verify that throws is refused like a wrong hash and the file deleted`() = runBlocking {
        signersThrow = true
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        settle()
        assertEquals(UpdateFailure.Hash, (manager.state.value as UpdateState.Failed).reason)
        assertTrue(installed.isEmpty())
        assertFalse(File(dir, "update/update.apk").exists())
    }

    @Test
    fun `turning the check off mid-download cancels it and deletes the file`() = runBlocking {
        slowApk = true
        val manager = manager()
        preferences.setAllowed(true)
        manager.checkIfDue()
        manager.update()
        val apk = File(dir, "update/update.apk")
        withTimeout(10_000) {
            while ((manager.state.value as? UpdateState.Downloading)?.fraction.let { it == null || it == 0f }) delay(10)
        }
        assertTrue(apk.exists())
        manager.setAllowed(false)
        settle()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertTrue(installed.isEmpty())
        assertFalse(apk.exists())
    }

    @Test
    fun `a debug build only checks an https source of its own, a release build tensai's`() {
        assertEquals(ReleaseClient.DEFAULT_URL, UpdateManager.sourceUrl(isDebug = false, debugUrl = "https://example.com/x"))
        assertEquals(null, UpdateManager.sourceUrl(isDebug = true, debugUrl = null))
        assertEquals(null, UpdateManager.sourceUrl(isDebug = true, debugUrl = "http://example.com/x"))
        assertEquals("https://example.com/x", UpdateManager.sourceUrl(isDebug = true, debugUrl = "https://example.com/x"))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
