package io.github.hokanosekai.tensai.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is currently in the foreground, reported by `MainActivity`'s `onStart`/`onStop`.
 *
 * A process-wide flag rather than a `ProcessLifecycleOwner` for the same reason `MainActivity` can
 * already use `onStop` as "the app went to background" (see that method's KDoc): this app has
 * exactly one `Activity`, so its start/stop pair *is* the process's foreground state, and no extra
 * dependency buys anything.
 *
 * Read by [saveSessionAfterBackgroundedGeneration], which needs it to tell the two ends of a
 * generation apart: a reply finishing while the user is watching needs no session save (they are
 * still here, and the next trigger will catch it), while a reply finishing after they put the phone
 * down is TEN-78's third case and the one moment the state is worth writing.
 *
 * Starts `false` and is only ever moved by the `Activity`: a process started headlessly (a
 * notification action waking the foreground service, say) is correctly not in the foreground.
 */
object AppForegroundTracker {
    private val _isForeground = MutableStateFlow(false)

    /** `true` between `MainActivity.onStart` and its `onStop`. */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun onActivityStarted() {
        _isForeground.value = true
    }

    fun onActivityStopped() {
        _isForeground.value = false
    }
}
