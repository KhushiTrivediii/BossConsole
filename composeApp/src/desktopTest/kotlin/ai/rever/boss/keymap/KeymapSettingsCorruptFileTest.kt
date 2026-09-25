package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.keymap.presets.KeymapPresets
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corrupt-file recovery for keymap-settings.json (BossConsole#925). #937 made the writes atomic and
 * owner-only; a file that is already corrupt - a torn write from before it, or a hand-edit - was
 * still re-read and re-failed on every launch, and the next save overwrote it with no copy kept.
 * It is now moved aside and replaced with a fresh default.
 *
 * Hermetic through [KeymapSettingsManager.resetForTesting], so nothing here touches the operator's ~/.boss.
 */
class KeymapSettingsCorruptFileTest {
    private lateinit var tempDir: File
    private lateinit var settingsFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("keymap-corrupt-test-").toFile()
        settingsFile = File(tempDir, "keymap-settings.json")
    }

    @AfterTest
    fun tearDown() {
        KeymapSettingsManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a corrupt keymap settings file is moved aside and replaced with a fresh default`() {
        assertSelfHeals("{ not valid json at all")
    }

    // The classic artifact of a crash mid-write under the old truncate-on-open writeText.
    @Test
    fun `an empty keymap settings file is moved aside and replaced with a fresh default`() {
        assertSelfHeals("")
    }

    // Valid JSON of the wrong shape, as a hand-edit can leave behind.
    @Test
    fun `a keymap settings file of the wrong shape is set aside and replaced with a fresh default`() {
        assertSelfHeals("[]")
    }

    // A newer build can write an enum member this build does not know. That file is readable, not
    // corrupt: coercion falls back to the declared default and the user's keymap must stay put.
    @Test
    fun `a keymap file with an unknown enum value is coerced, not renamed aside`() {
        val fixture = """{"presetName":"Mine","customized":true,"tabSwitchMode":"A_FUTURE_MODE","version":1}"""
        settingsFile.writeText(fixture)

        KeymapSettingsManager.resetForTesting(settingsFile)

        val loaded = KeymapSettingsManager.currentSettings.value
        assertEquals("Mine", loaded.presetName)
        assertEquals(TabSwitchMode.MRU, loaded.tabSwitchMode)
        assertEquals(emptyList(), corruptSiblings(), "a readable file must not be treated as corrupt")
    }

    // Only a decode failure may rename a file away. A read error (here: the path is a directory, so
    // readText throws an IOException) says nothing about the bytes, so nothing is moved.
    @Test
    fun `a read failure that is not a decode failure leaves the file in place`() {
        assertTrue(settingsFile.mkdirs())

        KeymapSettingsManager.resetForTesting(settingsFile)

        assertTrue(settingsFile.isDirectory, "the original path must be untouched")
        assertEquals(emptyList(), corruptSiblings(), "no aside may be created for a read error")
    }

    private fun corruptSiblings(): List<File> =
        tempDir.listFiles { f -> f.name.startsWith("${settingsFile.name}.corrupt-") }.orEmpty().toList()

    private fun assertSelfHeals(fixture: String) {
        settingsFile.writeText(fixture)

        KeymapSettingsManager.resetForTesting(settingsFile)

        assertEquals(KeymapPresets.getBOSSDefault(), KeymapSettingsManager.currentSettings.value)
        assertTrue(settingsFile.isFile, "a fresh file must be written back")
        assertTrue(settingsFile.readText() != fixture, "the corrupt content must not still be at the original path")
        val corrupt = tempDir.listFiles { f -> f.name.startsWith("${settingsFile.name}.corrupt-") }.orEmpty()
        assertEquals(1, corrupt.size, "the corrupt bytes must be kept, renamed aside")
        assertEquals(fixture, corrupt.single().readText())
    }
}
