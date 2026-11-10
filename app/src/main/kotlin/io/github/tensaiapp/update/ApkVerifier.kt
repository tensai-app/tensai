package io.github.tensaiapp.update

import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/** Who signed an APK: its package name and the SHA-256 of each current signing certificate, lowercase hex. */
data class ApkSigners(val packageName: String, val certSha256: Set<String>)

/** What [ApkVerifier.verify] found. */
enum class ApkCheck { Ok, HashMismatch, SignatureMismatch }

/**
 * The two checks a downloaded update passes before Android sees it (TEN-227): its bytes hash to what the release
 * published, and it is signed by the same certificates as the app installed, under the same package name. Either one
 * failing refuses it; an APK whose signers cannot be read is refused too.
 *
 * Android would refuse a different signer at install anyway: checking here means a tampered or wrong file never
 * reaches the system dialog, and the user is told why.
 *
 * @param archiveSigners the signers of an APK file, null when it cannot be read, see [AndroidSigners.archive].
 * @param installed the signers of the app running this code, see [AndroidSigners.installed].
 */
class ApkVerifier(
    private val archiveSigners: (File) -> ApkSigners?,
    private val installed: () -> ApkSigners?,
) {
    fun verify(apk: File, expectedSha256: String): ApkCheck {
        if (sha256(apk) != expectedSha256.lowercase()) return ApkCheck.HashMismatch
        val own = installed() ?: return ApkCheck.SignatureMismatch
        val theirs = archiveSigners(apk) ?: return ApkCheck.SignatureMismatch
        return if (own.certSha256.isNotEmpty() && theirs == own) ApkCheck.Ok else ApkCheck.SignatureMismatch
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/** [ApkSigners] read from the package manager, with `GET_SIGNING_CERTIFICATES`. */
object AndroidSigners {

    @Suppress("DEPRECATION") // The PackageInfoFlags overloads are API 33+.
    fun archive(pm: PackageManager, apk: File): ApkSigners? {
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        return ApkSigners(info.packageName ?: return null, current(info.signingInfo ?: return null))
    }

    @Suppress("DEPRECATION")
    fun installed(pm: PackageManager, packageName: String): ApkSigners? {
        val info = runCatching { pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return null
        return ApkSigners(packageName, current(info.signingInfo ?: return null))
    }

    /**
     * The certificates that sign now. With one signer, the last of the rotation history (the current one, past
     * signers are not accepted in its place); with several, all of them.
     */
    private fun current(signing: SigningInfo): Set<String> {
        val certs = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners.toList()
        } else {
            listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return with(ApkVerifier) { certs.map { digest.digest(it.toByteArray()).toHex() }.toSet() }
    }
}
