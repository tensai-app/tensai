package io.github.tensaiapp.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the update card is. [Idle] draws nothing. */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** A newer release: Update, Later or Skip this version. */
    data class Available(val release: Release) : UpdateState

    /** Update was tapped but tensai may not install apps yet: the card sends the user to Android's setting. */
    data class NeedsPermission(val release: Release) : UpdateState

    /** [fraction] of the APK received, 0 to 1. */
    data class Downloading(val release: Release, val fraction: Float) : UpdateState

    /** Verified and handed to Android, which asks the user. Success restarts the app; a cancel goes back to [Available]. */
    data class Installing(val release: Release) : UpdateState

    data class Failed(val release: Release, val reason: UpdateFailure) : UpdateState
}

enum class UpdateFailure { Download, Hash, Signature, Install }

/**
 * The update check and install (TEN-227). Opt-in: nothing is requested until the user said yes, on the card or in
 * Settings. Then at most one check a day, at app start, off the UI; a release newer than [installedVersion] (see
 * [AppVersion.isOffered]) and not skipped becomes [UpdateState.Available].
 *
 * Update downloads the APK into [workDir], checks its SHA-256 against the release's `.sha256` and its signers against
 * the installed app's ([ApkVerifier]), then hands it to [install]. A failed check deletes the file. Later only hides
 * the card until the next daily check; Skip stores the tag, and only a newer one is offered after it.
 *
 * Which releases are asked, see [sourceUrl]: a release build asks tensai's GitHub releases, a debug build only the
 * test source set in its DEBUG section, never tensai's (it is signed with another key and could not install them).
 *
 * @param isDebug `BuildConfig.DEBUG`.
 * @param canInstall `PackageManager.canRequestPackageInstalls()`.
 * @param install copies the verified APK into a `PackageInstaller` session and commits it, see [UpdateInstaller].
 */
class UpdateManager(
    private val preferences: UpdatePreferences,
    private val source: ReleaseSource,
    private val verifier: ApkVerifier,
    private val installedVersion: String,
    private val isDebug: Boolean,
    private val defaultUrl: String = ReleaseClient.DEFAULT_URL,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val canInstall: () -> Boolean,
    private val install: suspend (File) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The "May tensai check for updates?" card: unanswered, and this build has somewhere to check. */
    val asksConsent: Flow<Boolean> = combine(preferences.consent, preferences.debugSourceUrl) { consent, debugUrl ->
        consent == UpdateConsent.Unasked && sourceUrl(isDebug, debugUrl, defaultUrl) != null
    }

    val consent: Flow<UpdateConsent> = preferences.consent

    private val checkLock = Mutex()
    private var updateJob: Job? = null

    /**
     * One check if the user allowed it, this build has a source, and none went out in the last day ([force], the debug
     * section's Check now, skips only that last rule). The time is stored before the request, so a failed one counts.
     */
    suspend fun checkIfDue(force: Boolean = false) = checkLock.withLock {
        if (preferences.consentNow() != UpdateConsent.Allowed) return@withLock
        val url = sourceUrl(isDebug, preferences.debugSourceUrl.first(), defaultUrl) ?: return@withLock
        if (_state.value != UpdateState.Idle) return@withLock
        val time = now()
        val last = preferences.lastCheckMs()
        // A clock set back (last in the future) checks again rather than waiting for it.
        if (!force && last != null && time - last in 0 until CHECK_INTERVAL_MS) return@withLock
        preferences.setLastCheckMs(time)
        val release = try {
            source.latest(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("check failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            return@withLock
        }
        when {
            release == null -> log("check: no release with an APK and its .sha256")
            !AppVersion.isOffered(installedVersion, release.tag) -> log("check: ${release.tag} not offered to $installedVersion")
            release.tag == preferences.skippedTag() -> log("check: ${release.tag} skipped")
            else -> {
                log("check: ${release.tag} offered to $installedVersion")
                _state.value = UpdateState.Available(release)
            }
        }
    }

    /**
     * The consent card's answer, or Settings'. A yes checks at once (still at most once a day). A no cancels a download
     * (its file is deleted) and clears the card, except once Android has the APK: its dialog is the user's to answer.
     */
    fun setAllowed(allowed: Boolean) {
        if (!allowed) {
            if (_state.value is UpdateState.Downloading) updateJob?.cancel()
            if (_state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
        }
        // The application scope has no exception handler: nothing may escape.
        scope.launch {
            try {
                preferences.setAllowed(allowed)
                if (allowed) checkIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("consent not saved: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
        }
    }

    /** Hides the card until the next daily check. Not while the APK downloads. */
    fun later() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    /** Never offers this tag again; a newer one still is. */
    fun skip() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _state.value = UpdateState.Idle
        scope.launch {
            try {
                preferences.setSkippedTag(release.tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("skip not saved: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
        }
        log("skip ${release.tag}")
    }

    /** Update, or Try again after a failure. Without the install permission, asks for it first: see [onResume]. */
    fun update() {
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.NeedsPermission -> current.release
            is UpdateState.Failed -> current.release.takeIf { current.reason != UpdateFailure.Signature }
            else -> null
        } ?: return
        if (!canInstall()) {
            log("install permission missing")
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        if (updateJob?.isActive == true) return
        _state.value = UpdateState.Downloading(release, 0f)
        updateJob = scope.launch { downloadAndInstall(release) }
    }

    /** Back from Android's "Install unknown apps" screen: carries on if it was granted. */
    fun onResume() {
        if (_state.value is UpdateState.NeedsPermission && canInstall()) update()
    }

    /** The installer's answer, from [UpdateInstallReceiver]: a cancel offers the release again, a failure says so. */
    fun onInstallResult(aborted: Boolean, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        log(if (aborted) "install cancelled" else "install failed: ${message.orEmpty()}")
        _state.value = if (aborted) UpdateState.Available(release) else UpdateState.Failed(release, UpdateFailure.Install)
    }

    internal suspend fun downloadAndInstall(release: Release) {
        val apk = File(workDir, "update.apk")
        workDir.mkdirs()
        apk.delete()
        try {
            val sha = try {
                source.sha256(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(release, UpdateFailure.Download, apk, "sha256 fetch: ${e.message.orEmpty()}")
                return
            }
            if (sha == null) {
                fail(release, UpdateFailure.Hash, apk, "no SHA-256 in ${release.sha256Url}")
                return
            }
            log("download ${release.apkName} (${release.apkSize} bytes)")
            try {
                var shown = 0
                source.download(release, apk) { done, total ->
                    // One state per percent, not one per 64 KB read.
                    val percent = (done * 100 / total).toInt()
                    val current = _state.value
                    // Compare-and-set: a download cancelled by [setAllowed] must not put its card back.
                    if (percent != shown && current is UpdateState.Downloading) {
                        shown = percent
                        _state.compareAndSet(current, UpdateState.Downloading(release, percent / 100f))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(release, UpdateFailure.Download, apk, "download: ${e.message.orEmpty()}")
                return
            }
            // A read that fails is a file that cannot be vouched for: refused like a wrong hash, never left on Downloading.
            val check = try {
                verifier.verify(apk, sha)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(release, UpdateFailure.Hash, apk, "verify: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                return
            }
            when (check) {
                ApkCheck.HashMismatch -> {
                    fail(release, UpdateFailure.Hash, apk, "SHA-256 mismatch, refused")
                    return
                }
                ApkCheck.SignatureMismatch -> {
                    fail(release, UpdateFailure.Signature, apk, "signer mismatch, refused")
                    return
                }
                ApkCheck.Ok -> log("verified: SHA-256 and signer match")
            }
            // Consent withdrawn while verifying: stop before Android sees it.
            currentCoroutineContext().ensureActive()
            _state.value = UpdateState.Installing(release)
            try {
                install(apk)
                log("handed to the installer")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(release, UpdateFailure.Install, apk, "installer: ${e.message.orEmpty()}")
            }
        } finally {
            // The session holds its own copy once committed.
            apk.delete()
        }
    }

    private fun fail(release: Release, reason: UpdateFailure, apk: File, message: String) {
        apk.delete()
        log(message)
        _state.value = UpdateState.Failed(release, reason)
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /**
         * The releases-API URL this build checks, null for none: a release build [defaultUrl] (tensai's own,
         * [ReleaseClient.DEFAULT_URL], but for the tests), a debug build only the https [debugUrl] set in its DEBUG section.
         */
        fun sourceUrl(isDebug: Boolean, debugUrl: String?, defaultUrl: String = ReleaseClient.DEFAULT_URL): String? = when {
            !isDebug -> defaultUrl
            debugUrl != null && debugUrl.startsWith("https://") -> debugUrl
            else -> null
        }
    }
}
