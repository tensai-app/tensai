package io.github.tensaiapp.agent

import org.json.JSONObject

/** What the phone says about itself. Nothing here identifies the user or the device: no SSID, IMEI, serial or account. */
data class DeviceSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val ramFreeBytes: Long,
    val ramTotalBytes: Long,
    /** Minutes until the battery is full, `null` when not charging or the system cannot tell. */
    val minutesToFull: Int?,
    val network: String,
    val androidVersion: String,
    val model: String,
)

/** Where [DeviceInfoTool] reads the phone, so a JVM test can answer with a fake. */
fun interface DeviceInfoSource {
    fun read(): DeviceSnapshot
}

/** Battery, storage, RAM, network type, Android version and model. */
class DeviceInfoTool(private val source: DeviceInfoSource) : Tool {
    override val name = "device_info"
    override val description = "Battery, charging, free storage and RAM, network type, Android version and phone model."
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult = ToolResult.ok(format(source.read()))

    companion object {
        private const val GB = 1_000_000_000.0

        fun format(s: DeviceSnapshot): String {
            val json = JSONObject()
            if (s.batteryPercent != null) {
                json.put("batteryPercent", s.batteryPercent)
                s.charging?.let { json.put("charging", it) }
                if (s.charging == true && s.minutesToFull != null) json.put("timeToFull", duration(s.minutesToFull))
            }
            return json
                .put("storageFreeGB", gb(s.storageFreeBytes))
                .put("storageTotalGB", gb(s.storageTotalBytes))
                .put("ramFreeGB", gb(s.ramFreeBytes))
                .put("ramTotalGB", gb(s.ramTotalBytes))
                .put("network", s.network)
                .put("android", s.androidVersion)
                .put("model", s.model)
                .toString()
        }

        private fun duration(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "${minutes}min"

        private fun gb(bytes: Long) = Math.round(bytes / GB * 10) / 10.0
    }
}
