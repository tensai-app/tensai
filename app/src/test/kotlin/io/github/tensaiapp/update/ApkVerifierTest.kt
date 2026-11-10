package io.github.tensaiapp.update

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ApkVerifierTest {

    private lateinit var dir: File
    private lateinit var apk: File
    private val own = ApkSigners("io.github.tensaiapp", setOf("aa".repeat(32)))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-apk-verifier").toFile()
        apk = File(dir, "update.apk").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun verifier(theirs: ApkSigners?, installed: ApkSigners? = own) = ApkVerifier(archiveSigners = { theirs }, installed = { installed })

    @Test
    fun `same hash and same signer pass, the hash compared case-insensitively`() {
        assertEquals(ApkCheck.Ok, verifier(own).verify(apk, ApkVerifier.sha256(apk).uppercase()))
    }

    @Test
    fun `a hash other than the published one is refused`() {
        assertEquals(ApkCheck.HashMismatch, verifier(own).verify(apk, "0".repeat(64)))
    }

    @Test
    fun `another signer is refused`() {
        val sha = ApkVerifier.sha256(apk)
        assertEquals(ApkCheck.SignatureMismatch, verifier(own.copy(certSha256 = setOf("bb".repeat(32)))).verify(apk, sha))
        // A second signer next to ours is not ours either.
        assertEquals(ApkCheck.SignatureMismatch, verifier(own.copy(certSha256 = own.certSha256 + "bb".repeat(32))).verify(apk, sha))
    }

    @Test
    fun `our signer on another package is refused`() {
        assertEquals(ApkCheck.SignatureMismatch, verifier(own.copy(packageName = "com.example.other")).verify(apk, ApkVerifier.sha256(apk)))
    }

    @Test
    fun `signers that cannot be read are refused`() {
        val sha = ApkVerifier.sha256(apk)
        assertEquals(ApkCheck.SignatureMismatch, verifier(null).verify(apk, sha))
        assertEquals(ApkCheck.SignatureMismatch, verifier(own, installed = null).verify(apk, sha))
        assertEquals(ApkCheck.SignatureMismatch, verifier(own.copy(certSha256 = emptySet()), installed = own.copy(certSha256 = emptySet())).verify(apk, sha))
    }
}
