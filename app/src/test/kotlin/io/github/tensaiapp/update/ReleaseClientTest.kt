package io.github.tensaiapp.update

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ReleaseClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(vararg assets: String) =
        """{"tag_name":"v0.3.7","body":"notes","assets":[${assets.joinToString(",")}]}"""

    private fun asset(name: String, url: String = "https://github.com/tensai-app/tensai/releases/download/v0.3.7/$name") =
        """{"name":"$name","size":1000,"browser_download_url":"$url"}"""

    private fun latest(body: String, httpsOnly: Boolean = true): Release? = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        ReleaseClient(OkHttpClient(), OkHttpClient(), httpsOnly).latest(server.url("/latest").toString())
    }

    @Test
    fun `the APK and its sha256 make a release`() {
        val release = latest(release(asset("tensai-0.3.7.apk"), asset("tensai-0.3.7.apk.sha256")))!!
        assertEquals("0.3.7", release.version)
        assertEquals("tensai-0.3.7.apk", release.apkName)
        assertTrue(release.sha256Url.endsWith("tensai-0.3.7.apk.sha256"))
        assertEquals("application/vnd.github+json", server.takeRequest().getHeader("Accept"))
    }

    @Test
    fun `a release without its sha256, or with two APKs, is not one`() {
        assertNull(latest(release(asset("tensai-0.3.7.apk"))))
        assertNull(latest(release(asset("a.apk"), asset("b.apk"), asset("a.apk.sha256"))))
        assertNull(latest("""{"tag_name":"v0.3.7","draft":true,"assets":[${asset("t.apk")},${asset("t.apk.sha256")}]}"""))
    }

    @Test
    fun `an asset off https is refused`() {
        assertNull(latest(release(asset("t.apk", "http://example.com/t.apk"), asset("t.apk.sha256"))))
    }

    /** Downloads a body of [served] bytes for a release that announces [announced]; true if it was accepted. */
    private fun download(served: Int, announced: Long): Boolean {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(served))))
        val dest = Files.createTempFile("tensai-release", ".apk").toFile()
        val release = Release("v0.3.7", "", "t.apk", server.url("/t.apk").toString(), announced, server.url("/t.apk.sha256").toString())
        return try {
            runBlocking { ReleaseClient(OkHttpClient(), OkHttpClient(), httpsOnly = false).download(release, dest) { _, _ -> } }
            true
        } catch (e: IOException) {
            false
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `a download of the announced size is accepted, shorter or longer is refused`() {
        assertTrue(download(served = 5_000, announced = 5_000))
        if (download(served = 4_999, announced = 5_000)) fail("a short body was accepted")
        if (download(served = 5_001, announced = 5_000)) fail("a long body was accepted")
    }

    @Test
    fun `the sha256 file is read like sha256sum writes it`() {
        val hex = "AB".repeat(32)
        assertEquals(hex.lowercase(), ReleaseClient.parseSha256("$hex  tensai-0.3.7.apk\n"))
        assertEquals(hex.lowercase(), ReleaseClient.parseSha256(hex))
        assertNull(ReleaseClient.parseSha256("not a hash"))
        assertNull(ReleaseClient.parseSha256(""))
    }

    @Test
    fun `long notes are cut on a line end`() {
        val body = (1..400).joinToString("\r\n") { "* change number $it" }
        val notes = ReleaseClient.trimNotes(body)
        assertTrue(notes.length <= ReleaseClient.NOTES_MAX_CHARS + 3)
        assertTrue(notes.endsWith("\n\n…"))
        assertTrue(notes.lines().dropLast(2).all { it.startsWith("* change number ") })
    }
}
