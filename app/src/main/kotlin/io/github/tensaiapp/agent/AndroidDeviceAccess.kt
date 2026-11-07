package io.github.tensaiapp.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentUris
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.StatFs
import android.provider.CalendarContract
import android.util.Log
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.view.KeyEvent
import io.github.tensaiapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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

/**
 * The contacts on a real device (TEN-186): READ_CONTACTS asked through [gate] at first use, then two queries (numbers,
 * addresses) grouped by contact. Nothing is cached or logged.
 */
class AndroidContactsSource(context: Context, private val gate: PermissionGate) : ContactsSource {
    private val app = context.applicationContext

    override fun isGranted(): Boolean = app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override suspend fun access(): PermissionResult {
        if (isGranted()) return PermissionResult.Granted
        return gate.request(Manifest.permission.READ_CONTACTS)
    }

    override fun load(): List<Contact> {
        val names = LinkedHashMap<Long, String>()
        val phones = HashMap<Long, MutableList<ContactPhone>>()
        val emails = HashMap<Long, MutableList<String>>()
        app.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.NORMALIZED_NUMBER),
            // Visible contacts only: the hidden and auto-collected ones are not in the user's address book.
            "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1", null, "${Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(2)?.trim().orEmpty()
                val name = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                names.putIfAbsent(id, name)
                val type = when (c.getInt(3)) {
                    Phone.TYPE_MOBILE -> PhoneType.Mobile
                    Phone.TYPE_HOME -> PhoneType.Home
                    Phone.TYPE_WORK -> PhoneType.Work
                    else -> PhoneType.Other
                }
                phones.getOrPut(id) { mutableListOf() } += ContactPhone(number, type, c.getString(4)?.takeIf { it.isNotBlank() })
            }
        }
        app.contentResolver.query(
            Email.CONTENT_URI,
            arrayOf(Email.CONTACT_ID, Email.DISPLAY_NAME, Email.ADDRESS),
            "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1", null, "${Email.IS_SUPER_PRIMARY} DESC, ${Email.IS_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val address = c.getString(2)?.trim().orEmpty()
                val name = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty() || address.isEmpty()) continue
                names.putIfAbsent(id, name)
                emails.getOrPut(id) { mutableListOf() } += address
            }
        }
        return names.map { (id, name) -> Contact(id, name, phones[id].orEmpty(), emails[id].orEmpty()) }
    }
}

/**
 * The SMS on a real device (TEN-212): READ_SMS asked through [gate] at first use, then one query on the inbox and sent
 * box (number, date, body, type), see [smsSelection]. Nothing is cached or logged.
 */
class AndroidSmsSource(context: Context, private val gate: PermissionGate) : SmsSource {
    private val app = context.applicationContext

    override fun isGranted(): Boolean = app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override suspend fun access(): PermissionResult {
        if (isGranted()) return PermissionResult.Granted
        return gate.request(Manifest.permission.READ_SMS)
    }

    override fun query(sinceMillis: Long, box: SmsBox, maxRows: Int): List<SmsMessage>? {
        val where = smsSelection(sinceMillis, box)
        val messages = mutableListOf<SmsMessage>()
        val cursor = try {
            app.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.TYPE),
                where.selection, where.args.toTypedArray(), "${Telephony.Sms.DATE} DESC",
            )
        } catch (e: IllegalArgumentException) {
            // No provider for the URI: a device without telephony.
            null
        } ?: return null
        cursor.use { c ->
            while (messages.size < maxRows && c.moveToNext()) {
                val address = c.getString(0)?.trim().orEmpty()
                if (address.isEmpty()) continue
                messages += SmsMessage(address, c.getLong(1), c.getString(2).orEmpty(), c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT)
            }
        }
        return messages
    }
}

/**
 * The calendar on a real device (TEN-179): READ_CALENDAR asked through [gate] at first use, then one query on the
 * instances table, which expands recurring events. Visible calendars only, nothing but title, time, all-day flag and
 * place is read. Nothing is cached or logged.
 */
class AndroidCalendarSource(context: Context, private val gate: PermissionGate) : CalendarSource {
    private val app = context.applicationContext

    override fun isGranted(): Boolean = app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun access(): PermissionResult {
        if (isGranted()) return PermissionResult.Granted
        return gate.request(Manifest.permission.READ_CALENDAR)
    }

    override fun events(startMillis: Long, endMillis: Long, limit: Int): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMillis)
            ContentUris.appendId(it, endMillis)
        }.build()
        val events = mutableListOf<CalendarEvent>()
        app.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (events.size < limit && c.moveToNext()) {
                events += CalendarEvent(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4))
            }
        }
        return events
    }
}

/**
 * The approximate location on a real device (TEN-179): ACCESS_COARSE_LOCATION asked through [gate] at first use, then
 * the network provider (the only one a coarse grant allows, no Play Services). A fresh fix on Android 11+ within
 * [FIX_TIMEOUT_MS], else the newest last known position of the network or passive provider. Nothing is cached and no
 * position is logged. A SecurityException (the permission was revoked meanwhile) is left to [LocationTool], which
 * turns it into the settings refusal.
 */
class AndroidLocationSource(context: Context, private val gate: PermissionGate) : LocationSource {
    private val app = context.applicationContext

    override fun isGranted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun access(): PermissionResult {
        if (isGranted()) return PermissionResult.Granted
        return gate.request(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // The grant is checked by the gate before this runs. A revoke in between throws SecurityException on purpose: the tool
    // turns it into the settings refusal.
    @SuppressLint("MissingPermission")
    override suspend fun current(): LocationOutcome {
        val manager = app.getSystemService(LocationManager::class.java) ?: return LocationOutcome.Failed(LocationFailure.Unavailable)
        if (!manager.isLocationEnabled) return LocationOutcome.Failed(LocationFailure.Off)
        return try {
            val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                withTimeoutOrNull(FIX_TIMEOUT_MS) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        val cancel = CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        // Runnable::run: the callback runs on the location thread, resuming there is cheap, and
                        // resuming after the cancel above is a no-op.
                        manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel, Runnable::run) { cont.resume(it) }
                    }
                }
            } else {
                null
            }
            val location = fresh ?: listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (location == null) {
                LocationOutcome.Failed(LocationFailure.Unavailable)
            } else {
                LocationOutcome.Found(
                    LocationFix(location.latitude, location.longitude, location.takeIf { it.hasAccuracy() }?.accuracy, location.time),
                )
            }
        } catch (e: IllegalArgumentException) {
            // A provider this phone does not have. The class only: a message could carry a position.
            Log.w("tensai.location", "provider rejected: ${e.javaClass.simpleName}")
            LocationOutcome.Failed(LocationFailure.Unavailable)
        }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 8_000L
    }
}
