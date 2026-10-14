package io.github.hokanosekai.tensai.hf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reversible stand-in for the Keystore: XOR plus a marker, so the sealed form is never the plain text. */
internal class FakeTokenCipher : TokenCipher {
    var failSeal = false

    /** What [open] throws, null for none. */
    var openFailure: Exception? = null

    override fun seal(plain: ByteArray): ByteArray {
        if (failSeal) throw GeneralSecurityException("seal refused")
        return byteArrayOf(0x7f) + plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    override fun open(sealed: ByteArray): ByteArray {
        openFailure?.let { throw it }
        if (sealed.firstOrNull() != 0x7f.toByte()) throw AEADBadTagException("tag mismatch")
        return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}

/** [TokenStore]'s logic over a real DataStore on a temp file and a fake cipher (the Keystore needs a device). */
class TokenStoreTest {

    private lateinit var dir: File
    private lateinit var job: Job
    private lateinit var dataStore: DataStore<Preferences>
    private val cipher = FakeTokenCipher()

    private val token = "hf_x8Tq2LmN3kQzAbCd"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-token-test").toFile()
        job = Job()
        dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { File(dir, "t.preferences_pb") }
    }

    @After
    fun tearDown() = runBlocking {
        job.cancelAndJoin()
        dir.deleteRecursively()
        Unit
    }

    private fun store(onMain: () -> Boolean = { false }) = TokenStore(dataStore, cipher, Dispatchers.IO, onMain)

    private suspend fun rawStored(): String? = dataStore.data.first().asMap().values.firstOrNull()?.toString()

    @Test
    fun anUntouchedStoreHasNoToken() = runBlocking {
        val s = store()
        assertNull(s.get())
        assertFalse(s.hasToken.first())
    }

    @Test
    fun setThenGetReturnsTheTokenAndHasTokenFollows() = runBlocking {
        val s = store()
        assertTrue(s.set(token))
        assertEquals(token, s.get())
        assertTrue(s.hasToken.first())
    }

    @Test
    fun theTokenIsTrimmedAndABlankOneClears() = runBlocking {
        val s = store()
        s.set("  $token \n")
        assertEquals(token, s.get())

        assertTrue(s.set("   "))
        assertNull(s.get())
        assertFalse(s.hasToken.first())
    }

    @Test
    fun clearRemovesIt() = runBlocking {
        val s = store()
        s.set(token)
        s.clear()
        assertNull(s.get())
        assertFalse(s.hasToken.first())
        assertNull(rawStored())
    }

    @Test
    fun whatIsOnDiskIsTheSealedFormAndNeverTheToken() = runBlocking {
        store().set(token)
        val raw = rawStored()!!
        assertFalse(raw.contains(token))
        assertFalse(raw.contains(token.takeLast(8)))
    }

    @Test
    fun aNewInstanceOverTheSameFileReadsTheTokenBack() = runBlocking {
        store().set(token)
        assertEquals(token, store().get())
    }

    @Test
    fun aPermanentlyUnreadableValueIsDroppedAndReadsAsUnset() = runBlocking {
        val permanent = listOf(
            AEADBadTagException("tampered"),
            TokenKeyMissingException(),
            java.security.UnrecoverableKeyException("gone"),
        )
        for (failure in permanent) {
            store().set(token)
            cipher.openFailure = failure

            val s = store()
            assertNull(failure.toString(), s.get())
            assertFalse(s.hasToken.first())
            assertNull(rawStored())
            cipher.openFailure = null
        }
    }

    @Test
    fun aMalformedBlobIsDropped() = runBlocking {
        dataStore.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("hf_token_sealed")] = "%%% not base64 %%%" }
        val s = store()
        assertNull(s.get())
        assertNull(rawStored())
    }

    @Test
    fun aTransientKeystoreFailureNeverWipesTheToken() = runBlocking {
        store().set(token)
        val before = rawStored()
        cipher.openFailure = GeneralSecurityException("keystore daemon busy")

        val s = store()
        assertNull(s.get())
        // Not usable now, so it does not show as a token that works, but it is still there.
        assertFalse(s.hasToken.first())
        assertEquals(before, rawStored())

        // The fault passes: the same store reads it on the next call.
        cipher.openFailure = null
        assertEquals(token, s.get())
        assertTrue(s.hasToken.first())
    }

    @Test
    fun aCorruptDataStoreReadsAsUnsetWithoutThrowing() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data = kotlinx.coroutines.flow.flow<Preferences> { throw androidx.datastore.core.CorruptionException("bad file") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("must not write")
        }
        val s = TokenStore(broken, cipher, Dispatchers.IO)
        assertNull(s.get())
        assertNull(s.peek())
    }

    @Test
    fun hasTokenIsFalseForABlobThatCannotBeDecryptedAfterRestore() = runBlocking {
        store().set(token)
        cipher.openFailure = TokenKeyMissingException()
        assertFalse(store().hasToken.first())
    }

    @Test
    fun aMalformedTokenIsNotStored() = runBlocking {
        val s = store()
        for (bad in listOf("short", "has space inside", "hf_\u00e9\u00e9\u00e9\u00e9\u00e9", "hf_line\nbreak1", "x".repeat(257))) {
            assertFalse(bad, s.set(bad))
        }
        assertNull(rawStored())
        assertTrue(s.set("x".repeat(256)))
    }

    @Test
    fun aRefusedEncryptionChangesNothing() = runBlocking {
        val s = store()
        s.set(token)
        cipher.failSeal = true

        assertFalse(s.set("hf_anothertoken"))
        assertEquals(token, s.get())
    }

    @Test
    fun peekAnswersFromTheCacheAndFollowsSetAndClear() = runBlocking {
        val s = store()
        s.set(token)
        // A second instance has a cold cache: peek fills it with one blocking read, off Main.
        val cold = store()
        assertEquals(token, cold.peek())

        cold.clear()
        assertNull(cold.peek())
        cold.set("hf_nextone")
        assertEquals("hf_nextone", cold.peek())
    }

    @Test
    fun peekOnTheMainThreadWithAColdCacheAnswersNullInsteadOfBlocking() = runBlocking {
        store().set(token)
        val cold = store(onMain = { true })
        assertNull(cold.peek())
        // Once warmed (by get, on IO), the cache answers even on Main.
        cold.get()
        assertEquals(token, cold.peek())
    }

    @Test
    fun theMaskKeepsThePrefixAndTheLastFourOnly() {
        assertEquals("hf_••••AbCd", TokenStore.mask(token))
        assertEquals("••••WXYZ", TokenStore.mask("abcdefghijklWXYZ"))
        // Too short to hide anything: bullets only.
        assertEquals("hf_••••", TokenStore.mask("hf_abcd"))
        assertEquals("••••", TokenStore.mask("abc"))
    }
}
