package io.github.hokanosekai.tensai.hf

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.hfTokenDataStore by preferencesDataStore(name = "tensai_hf_token")

/**
 * The user's Hugging Face token, encrypted at rest (TEN-144).
 *
 * **Storage.** One DataStore value: base64 of [TokenCipher.seal]'s output (IV + AES/GCM ciphertext).
 * The key never leaves the Android Keystore. The token is never logged and never put in an exception
 * message, here or by callers: a failure is reported as a boolean or as "no token". The DataStore
 * file is excluded from backup and device transfer (see the manifest): the key would not follow it.
 *
 * **Reads.** [get] is a suspend that decrypts on [io]. [peek] is the synchronous accessor for the
 * HTTP clients' `tokenProvider`: it answers from a cache that [set] and [clear] keep current, so a
 * Settings change applies to the next request. A cold cache is filled by a blocking read on the
 * caller's thread, except on the main thread where it answers null instead (the clients call their
 * provider on IO, so that case only guards against a mistake).
 *
 * **Unreadable values.** Only a permanent failure deletes the stored value: the key is gone or
 * invalidated, the blob is malformed or fails its GCM tag. Anything else (a Keystore hiccup, a
 * DataStore I/O or corruption error) reads as "no token right now" and keeps the value, so a
 * transient fault cannot wipe the user's token; it is read again next time.
 *
 * @param onMain whether the calling thread is the main one.
 */
class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onMain: () -> Boolean = { false },
) {
    constructor(context: Context) : this(
        dataStore = context.hfTokenDataStore,
        cipher = KeystoreCipher(),
        onMain = { Looper.myLooper() == Looper.getMainLooper() },
    )

    private val key = stringPreferencesKey("hf_token_sealed")
    private val lock = Mutex()

    @Volatile private var cached: String? = null

    /** True once the stored value was read for good (a token, or definitely none). False after a transient failure. */
    @Volatile private var loaded = false

    /**
     * Whether there is a token that can actually be used: something is stored and it decrypts. A
     * blob that cannot be opened (restored without its key) is dropped by the read and shows as unset.
     */
    val hasToken: Flow<Boolean> = dataStore.data
        .map { it[key] }
        .distinctUntilChanged()
        .map { sealed -> sealed != null && get() != null }
        .distinctUntilChanged()

    /** The token, or null when unset or unreadable. */
    suspend fun get(): String? = withContext(io) {
        lock.withLock {
            if (!loaded) {
                when (val read = readStored()) {
                    is Read.Found -> {
                        cached = read.token
                        loaded = true
                    }
                    Read.Absent -> {
                        cached = null
                        loaded = true
                    }
                    Read.Unreadable -> cached = null
                }
            }
            cached
        }
    }

    /** For a `tokenProvider`: never suspends on the caller's dispatcher. See the class KDoc. */
    fun peek(): String? {
        if (!loaded) {
            if (onMain()) return null
            return runBlocking { get() }
        }
        return cached
    }

    /**
     * Stores [token] (trimmed). Blank clears. Returns false when it is not [well formed][TokenFormat]
     * or could not be encrypted or written: nothing changed then.
     */
    suspend fun set(token: String): Boolean {
        val clean = token.trim()
        if (clean.isEmpty()) {
            clear()
            return true
        }
        if (!TokenFormat.isValid(clean)) return false
        return withContext(io) {
            lock.withLock {
                try {
                    val sealed = Base64.getEncoder().encodeToString(cipher.seal(clean.toByteArray(Charsets.UTF_8)))
                    dataStore.edit { it[key] = sealed }
                    cached = clean
                    loaded = true
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    suspend fun clear() {
        withContext(io) {
            lock.withLock {
                dataStore.edit { it.remove(key) }
                cached = null
                loaded = true
            }
        }
    }

    private sealed interface Read {
        data class Found(val token: String) : Read
        data object Absent : Read
        data object Unreadable : Read
    }

    /** Caller holds [lock]. */
    private suspend fun readStored(): Read {
        val sealed = try {
            dataStore.data.first()[key]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CorruptionException, IOException: nothing is known, so nothing is deleted.
            return Read.Unreadable
        } ?: return Read.Absent
        val blob = try {
            Base64.getDecoder().decode(sealed)
        } catch (e: IllegalArgumentException) {
            return drop()
        }
        return try {
            val token = String(cipher.open(blob), Charsets.UTF_8)
            if (token.isBlank()) drop() else Read.Found(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isPermanent()) drop() else Read.Unreadable
        }
    }

    /** The value will never open: forget it rather than fail on every call. */
    private suspend fun drop(): Read {
        try {
            dataStore.edit { it.remove(key) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Could not delete now; it reads as absent anyway and the next read tries again.
        }
        return Read.Absent
    }

    private fun Throwable.isPermanent(): Boolean = generateSequence(this) { it.cause }.take(4).any {
        it is KeyPermanentlyInvalidatedException ||
            it is UnrecoverableKeyException ||
            it is TokenKeyMissingException ||
            it is AEADBadTagException ||
            it is IllegalArgumentException
    }

    companion object {
        /**
         * `hf_••••abcd`: the `hf_` prefix when there is one, a bullet run, the last four characters.
         * A token too short for that to hide anything shows bullets only.
         */
        fun mask(token: String): String {
            val prefix = if (token.startsWith("hf_")) "hf_" else ""
            val tail = if (token.length >= 12) token.takeLast(4) else ""
            return "$prefix••••$tail"
        }
    }
}
