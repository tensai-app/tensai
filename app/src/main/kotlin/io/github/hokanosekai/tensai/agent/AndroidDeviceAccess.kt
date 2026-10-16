package io.github.hokanosekai.tensai.agent

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.view.KeyEvent
import io.github.hokanosekai.tensai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The Android side of the local tools (TEN-175): thin readers, the logic and the formats are in the tools. */

/** Reads [DeviceSnapshot] from the platform. Needs `ACCESS_NETWORK_STATE`, already in the manifest. */
class AndroidDeviceInfoSource(context: Context) : DeviceInfoSource {
    private val app = context.applicationContext

    override fun read(): DeviceSnapshot {
        // The battery broadcast is sticky: registering with no receiver just returns the last one.
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        // The app's own volume: where the models live, which is the figure the user acts on.
        val stat = StatFs(app.filesDir.path)
        val memory = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        return DeviceSnapshot(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            storageFreeBytes = stat.availableBytes,
            storageTotalBytes = stat.totalBytes,
            ramFreeBytes = memory.availMem,
            ramTotalBytes = memory.totalMem,
            minutesToFull = timeToFullMinutes(charging),
            network = networkType(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
    }

    /** `computeChargeTimeRemaining` answers -1 when it cannot tell, and only means something while charging. */
    private fun timeToFullMinutes(charging: Boolean): Int? {
        if (!charging) return null
        val ms = app.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        return if (ms > 0) (ms / 60_000).toInt() else null
    }

    private fun networkType(): String {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}

class AndroidClipboardAccess(context: Context) : ClipboardAccess {
    private val app = context.applicationContext
    private val manager get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun read(): String? = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()

    override fun write(text: String) = manager.setPrimaryClip(ClipData.newPlainText("tensai", text))
}

/**
 * The flashlight through [CameraManager.setTorchMode], no permission needed.
 *
 * A torch tensai turned on is turned off when the app leaves the foreground ([isForeground] goes false): the
 * user cannot see tensai's state from another app, and a light left on drains the battery. One the user lit
 * themselves is not touched.
 */
class AndroidTorchControl(
    context: Context,
    scope: CoroutineScope,
    isForeground: Flow<Boolean>,
) : TorchControl {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)

    @Volatile private var resolved = false
    @Volatile private var cachedId: String? = null

    /** Set when tensai switched it on and has not switched it off since. */
    @Volatile private var onByUs = false

    init {
        scope.launch {
            isForeground.collect { foreground ->
                if (!foreground && onByUs) runCatching { set(false) }
            }
        }
    }

    /**
     * The first camera with a flash unit, `null` when the device has none. "None" is cached, an exception
     * from the camera service is not: it may be transient, and caching it would hide the flash until restart.
     */
    private fun flashCameraId(): String? {
        if (resolved) return cachedId
        val id = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            return null
        }
        cachedId = id
        resolved = true
        return id
    }

    override val available: Boolean get() = flashCameraId() != null

    override fun set(on: Boolean) {
        val id = flashCameraId() ?: throw IllegalStateException("this device has no flashlight")
        try {
            manager.setTorchMode(id, on)
            onByUs = on
        } catch (e: CameraAccessException) {
            throw IllegalStateException("the flashlight is in use by another app, try again later")
        }
    }
}

/**
 * The phone actions on a real device (TEN-177): intents started from the application context with
 * `FLAG_ACTIVITY_NEW_TASK`, so they work from the chat screen. Needs the `<queries>` of the manifest to see
 * which app would answer.
 *
 * From the background Android 10+ drops the start without an error, so the tools check [inForeground] first.
 */
class AndroidPhoneActions(
    context: Context,
    private val isForeground: () -> Boolean,
) : PhoneActions {
    private val app = context.applicationContext

    override val inForeground: Boolean get() = isForeground()

    private fun intent(spec: IntentSpec): Intent = Intent(spec.action).apply {
        val uri = if (spec.scheme != null) Uri.fromParts(spec.scheme, spec.schemeSpecificPart.orEmpty(), null) else spec.data?.let(Uri::parse)
        when {
            uri != null && spec.mimeType != null -> setDataAndType(uri, spec.mimeType)
            uri != null -> data = uri
            spec.mimeType != null -> type = spec.mimeType
        }
        spec.extras.forEach { (key, value) ->
            when (value) {
                is String -> putExtra(key, value)
                is Int -> putExtra(key, value)
                is Long -> putExtra(key, value)
                is Boolean -> putExtra(key, value)
                is List<*> -> if (value.all { it is Int }) {
                    putIntegerArrayListExtra(key, ArrayList(value.filterIsInstance<Int>()))
                } else {
                    putExtra(key, value.filterIsInstance<String>().toTypedArray())
                }
            }
        }
        spec.categories.forEach(::addCategory)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The default handler's label; "android" is the system resolver (several apps, none chosen), named as such. */
    override fun handlerLabel(spec: IntentSpec): String? {
        val pm = app.packageManager
        val info = pm.resolveActivity(intent(spec), PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        if (info.activityInfo.packageName == "android") return app.getString(R.string.tool_app_picker)
        return info.loadLabel(pm).toString()
    }

    override fun start(spec: IntentSpec) {
        try {
            app.startActivity(intent(spec))
        } catch (e: ActivityNotFoundException) {
            throw NoHandlerException()
        }
    }

    override fun launchableApps(): List<InstalledApp> {
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map {
            InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }
    }

    override fun launchApp(packageName: String) {
        val launch = app.packageManager.getLaunchIntentForPackage(packageName) ?: throw NoHandlerException()
        try {
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw NoHandlerException()
        }
    }

    override val isMusicActive: Boolean get() = app.getSystemService(AudioManager::class.java).isMusicActive

    /** The key goes to the active media session, down then up: what a headset button does. No permission. */
    override fun sendMediaKey(key: MediaKey) {
        val code = when (key) {
            MediaKey.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.PlayPause -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaKey.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audio = app.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
