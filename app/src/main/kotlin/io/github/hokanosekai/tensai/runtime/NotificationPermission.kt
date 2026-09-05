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
 * The `POST_NOTIFICATIONS` runtime permission request that
 * [InferenceService]'s persistent notification needs on API 33+.
 *
 * Per the TEN-27 spec, this must be requested at the moment the user first
 * triggers a model load from the UI — not at app startup, where a
 * permission prompt is much more likely to get a hard "deny, don't ask
 * again". That call site is
 * [io.github.hokanosekai.tensai.ui.screens.loading.LoadingScreen], which
 * invokes the returned lambda in the same `LaunchedEffect` that starts the
 * load, immediately before it. A refusal is not treated as a failure: the
 * load proceeds, only without a persistent notification behind it.
 *
 * **This is not once per session, and the gap matters.** The guard below is
 * `checkSelfPermission != GRANTED`, which stops re-asking someone who has
 * *accepted*; someone who *refused* falls straight through it. And
 * `LoadingScreen`'s `LaunchedEffect(modelId)` re-fires on every fresh entry
 * to the screen, which is re-entrable (its own back action is "continue in
 * the background"). So a user who declines once is asked again on the next
 * load, and Android turns a second decline into a permanent
 * "don't ask again" — the exact outcome the paragraph above exists to
 * avoid. Fixing it needs a once-per-process latch, not a KDoc: TEN-75.
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
