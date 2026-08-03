package io.github.hokanosekai.tensai.ui.screens.models

import kotlin.math.roundToInt

/** e.g. 7_000_000_000L -> "7B params", 1_100_000_000L -> "1.1B params". */
internal fun formatParamCount(paramCount: Long): String {
    val billions = paramCount / 1_000_000_000.0
    val label = if (billions >= 10 || billions == billions.roundToInt().toDouble()) {
        "${billions.roundToInt()}B"
    } else {
        "%.1fB".format(billions)
    }
    return "$label params"
}

/** e.g. 4_400_000_000L -> "4.4 GB". */
internal fun formatFileSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 0.1) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}
