package io.github.tensaiapp

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Debug builds only: logs the UI frame timings every [WINDOW_MS] (TEN-197), so dropped frames
 * while the GPU computes can be read from logcat instead of judged by eye.
 *
 * One line per window with frames: `frames: N, janky M (>33 ms), frozen F (>700 ms), max X ms`.
 *
 * The callback thread is shared by the whole process; each activity adds its own listener and
 * removes it in `onDestroy`, so a recreation leaks neither a listener nor a thread.
 */
object FrameStatsLogger {
    private const val TAG = "tensai.frames"
    private const val WINDOW_MS = 5_000L
    private const val JANKY_NS = 33_000_000L
    private const val FROZEN_NS = 700_000_000L

    private val handler: Handler by lazy {
        Handler(HandlerThread("frame-stats").apply { start() }.looper)
    }

    fun attach(activity: ComponentActivity) {
        if (!BuildConfig.DEBUG) return
        var windowStart = SystemClock.uptimeMillis()
        var frames = 0
        var janky = 0
        var frozen = 0
        var maxNs = 0L
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            frames++
            if (total > JANKY_NS) janky++
            if (total > FROZEN_NS) frozen++
            if (total > maxNs) maxNs = total
            val now = SystemClock.uptimeMillis()
            if (now - windowStart >= WINDOW_MS) {
                Log.i(TAG, "frames: $frames, janky $janky (>33 ms), frozen $frozen (>700 ms), max ${maxNs / 1_000_000} ms")
                windowStart = now
                frames = 0
                janky = 0
                frozen = 0
                maxNs = 0L
            }
        }
        val window = activity.window
        window.addOnFrameMetricsAvailableListener(listener, handler)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                window.removeOnFrameMetricsAvailableListener(listener)
                owner.lifecycle.removeObserver(this)
            }
        })
    }
}
