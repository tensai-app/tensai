package io.github.hokanosekai.tensai.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Stub for the `POST_NOTIFICATIONS` runtime permission request that
 * [InferenceService]'s persistent notification needs on API 33+.
 *
 * Per the TEN-27 spec, this must be requested at the moment the user first
 * triggers a model load from the UI — not at app startup, where a
 * permission prompt is much more likely to get a hard "deny, don't ask
 * again". No screen currently calls [InferenceQueue.loadModel] (model
 * picking UI is TEN-30/36); wire this in at that call site once it exists,
 * by calling the returned lambda immediately before the first `loadModel`
 * call in a session.
 *
 * No-op on API < 33, where this permission doesn't exist and notifications
 * are granted implicitly.
 */
@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
