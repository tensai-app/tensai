package io.github.tensaiapp.runtime

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.engine.ModelLoadConfig
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Instrumented test for [InferenceService], driven through the real
 * [RuntimeGraph.inferenceQueue] singleton — the service and `TensaiApp`'s
 * start/stop observer both key off that exact instance, not an arbitrary
 * queue, so this test can't use a fresh [InferenceQueue] the way
 * `InferenceQueueTest` does.
 *
 * `TensaiApp`'s observer only runs because the instrumentation target's
 * real `Application` (`TensaiApp`) is what's actually instantiated for this
 * test — no `ServiceTestRule` involved. `ServiceTestRule` is built around
 * starting a service directly under test control, which doesn't fit this
 * service's design (it's never started directly — only ever indirectly, by
 * `TensaiApp` observing queue state); querying `ActivityManager` and
 * `NotificationManagerCompat` for the real, app-driven service/notification
 * lifecycle is a closer fit here and needs no extra test dependency.
 *
 * Same test-model convention as `InferenceQueueTest` — skipped, not failed,
 * when absent:
 * ```
 * adb push some-small-model.gguf \
 *   /sdcard/Android/data/io.github.tensaiapp/files/test-model.gguf
 * ```
 *
 * The notification-content assertion additionally needs `POST_NOTIFICATIONS`
 * granted (API 33+) — no UI in the app requests it yet (that's TEN-30/36's
 * job, see [rememberNotificationPermissionRequester]), so this test grants
 * it itself via [android.app.UiAutomation.grantRuntimePermission], same
 * mechanism `GrantPermissionRule` uses. On devices/ROMs that additionally
 * deny the shell UID `GRANT_RUNTIME_PERMISSIONS` (observed on the hardened
 * device this was run against — plain `adb shell pm grant` fails there with
 * the same `SecurityException`), that assertion is skipped rather than
 * failed, since it isn't testing this ticket's code at that point — just
 * confirming an environment limitation the ticket can't work around.
 */
@RunWith(AndroidJUnit4::class)
class InferenceServiceTest {

    /**
     * Backstop for a coroutine stuck inside a non-cancellable native call ([withTimeout] can't
     * catch that — see [InferenceQueueTest]'s `globalTimeout` for the full TEN-55/TEN-17 rationale
     * and why 90s). Wraps `@Before`/`@Test`/`@After` together, so a stuck [tearDown] also gets a
     * verdict, not just the test body. Runs on a separate thread and fails from the outside on
     * timeout; does not unstick the native call itself.
     */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val modelPath: String by lazy {
        File(context.getExternalFilesDir(null), "test-model.gguf").absolutePath
    }

    private fun assumeModelPresent() {
        assumeTrue(
            "No test model at $modelPath — skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )
    }

    @Suppress("DEPRECATION") // Restricted to the caller's own services on API 26+ — exactly what we want here.
    private fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == InferenceService::class.java.name }
    }

    private fun hasActiveNotification(): Boolean =
        NotificationManagerCompat.from(context).activeNotifications.isNotEmpty()

    /** @return true if `POST_NOTIFICATIONS` is granted, attempting to grant it first. */
    private fun grantNotificationPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    @After
    fun tearDown() {
        // Best-effort cleanup so a failure mid-test doesn't leak a loaded model (and its
        // service) into the next test. Bounded (TEN-55): a stuck unload must fail this cleanup
        // rather than freeze teardown for every test after it.
        runBlocking {
            runCatching { withTimeout(UNLOAD_TIMEOUT_MS) { RuntimeGraph.inferenceQueue.unloadModel() } }
        }
    }

    @Test
    fun loadModelStartsServiceAndNotification_unloadStopsBoth() = runBlocking {
        assumeModelPresent()
        val queue = RuntimeGraph.inferenceQueue

        withTimeout(LOAD_TIMEOUT_MS) {
            queue.loadModel(ModelLoadConfig(path = modelPath, nCtx = 512, backend = BackendHint.Cpu))
        }

        withTimeout(5_000) {
            while (!isServiceRunning()) delay(50)
        }
        assertTrue("expected InferenceService to be running after loadModel", isServiceRunning())

        val permissionGranted = grantNotificationPermission()
        assumeTrue(
            "POST_NOTIFICATIONS could not be granted in this environment (device/ROM denies the " +
                "shell UID GRANT_RUNTIME_PERMISSIONS) — skipping the notification-content assertion; " +
                "service start/stop above already passed",
            permissionGranted,
        )

        withTimeout(2_000) {
            while (!hasActiveNotification()) delay(50)
        }
        assertTrue("expected an active notification after loadModel", hasActiveNotification())

        withTimeout(UNLOAD_TIMEOUT_MS) { queue.unloadModel() }

        withTimeout(5_000) {
            while (isServiceRunning()) delay(50)
        }
        assertFalse("expected InferenceService to be stopped after unloadModel", isServiceRunning())
        assertFalse("expected the notification to be gone after unloadModel", hasActiveNotification())
    }

    private companion object {
        /** See [io.github.tensaiapp.runtime.InferenceQueueTest]'s `LOAD_TIMEOUT_MS` for rationale. */
        const val LOAD_TIMEOUT_MS = 60_000L

        /** See [io.github.tensaiapp.runtime.InferenceQueueTest]'s `UNLOAD_OR_CANCEL_TIMEOUT_MS`. */
        const val UNLOAD_TIMEOUT_MS = 30_000L
    }
}
