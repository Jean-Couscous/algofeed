package algofeed.desktop

import java.io.File

/** The app's name before it became Algofeed; its data and key file are moved on first launch. */
private const val LEGACY_NAME = "sift"

/**
 * Moves `<dataHome>/sift` (database) and `<configHome>/sift` (API key file) to their Algofeed
 * locations, renaming `sift.db*` files to `algofeed.db*`. Does nothing once the new folders exist.
 */
internal fun migrateLegacyData(dataHome: File, configHome: File) {
    moveFolder(File(dataHome, LEGACY_NAME), File(dataHome, "algofeed"))
    moveFolder(File(configHome, LEGACY_NAME), File(configHome, "algofeed"))
    File(dataHome, "algofeed").listFiles { f -> f.name.startsWith("$LEGACY_NAME.db") }?.forEach { old ->
        val renamed = File(old.parentFile, "algofeed.db" + old.name.removePrefix("$LEGACY_NAME.db"))
        if (!renamed.exists()) old.renameTo(renamed)
    }
}

private fun moveFolder(old: File, new: File) {
    if (!old.isDirectory || new.exists()) return
    if (!old.renameTo(new)) System.err.println("Could not move $old to $new; move it by hand to keep your data.")
}

internal fun dataHome(): File = File(
    System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share")
)

internal fun configHome(): File = File(System.getProperty("user.home"), ".config")
