package algofeed.desktop

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyDataTest {
    @Test fun movesOldDataAndKeyOnce() {
        val root = createTempDirectory().toFile()
        val data = File(root, "share")
        val config = File(root, "config")
        File(data, "sift").mkdirs()
        File(data, "sift/sift.db").writeText("db")
        File(data, "sift/sift.db-wal").writeText("wal")
        File(config, "sift").mkdirs()
        File(config, "sift/openrouter.key").writeText("key")

        migrateLegacyData(data, config)

        assertEquals("db", File(data, "algofeed/algofeed.db").readText())
        assertEquals("wal", File(data, "algofeed/algofeed.db-wal").readText())
        assertEquals("key", File(config, "algofeed/openrouter.key").readText())
        assertFalse(File(data, "sift").exists())

        // A later stray old folder is left alone once the new one exists.
        File(data, "sift").mkdirs()
        migrateLegacyData(data, config)
        assertTrue(File(data, "sift").exists())
        assertEquals("db", File(data, "algofeed/algofeed.db").readText())
        root.deleteRecursively()
    }
}
