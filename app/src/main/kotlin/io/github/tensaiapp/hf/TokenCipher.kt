package io.github.tensaiapp.hf

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals and opens the Hugging Face token. A seam so [TokenStore]'s logic runs on the JVM with a fake;
 * the real one is [KeystoreCipher], which needs a device.
 *
 * The sealed form is opaque to callers: the IV travels inside it.
 */
interface TokenCipher {
    /** @throws java.security.GeneralSecurityException when the key is unusable. */
    fun seal(plain: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException on a wrong key, a corrupt or tampered blob. */
    fun open(sealed: ByteArray): ByteArray
}

/** [TokenCipher.open] found no key: the sealed blob can never be opened (restored on another device, key wiped). */
class TokenKeyMissingException : GeneralSecurityException("token key missing")

/**
 * AES-256/GCM with a key that lives in the Android Keystore (hardware-backed where the device has it,
 * never exportable). Output is `IV (12 bytes) + ciphertext + tag`. A fresh IV per [seal], as GCM requires.
 *
 * Deliberately thin and not unit-tested: it is the platform's [Cipher] and nothing else. The key is
 * created on first use. If it disappears (restore on another device, key invalidation) [open] throws
 * [TokenKeyMissingException] or a Keystore exception, which [TokenStore] reads as permanent.
 */
class KeystoreCipher(private val alias: String = "tensai_hf_token") : TokenCipher {

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, existingKey() ?: throw TokenKeyMissingException(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    @Synchronized
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
