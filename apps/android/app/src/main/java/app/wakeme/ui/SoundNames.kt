package app.wakeme.ui

/** What people see for a bundled sound id (same names as iOS `soundName`). */
fun soundName(id: String): String = when (id) {
    "bundled:quack.classic" -> "Quack"
    "bundled:quack.double" -> "Double quack"
    "bundled:chime.soft" -> "Soft chime"
    "bundled:bell.bright" -> "Bright bell"
    "bundled:rise.gentle" -> "Gentle rise"
    "bundled:pulse.wake" -> "Wake pulse"
    else -> if (id.startsWith("media:")) "Voice recording ${id.removePrefix("media:").take(6)}" else id.removePrefix("bundled:").replace('.', ' ')
}
