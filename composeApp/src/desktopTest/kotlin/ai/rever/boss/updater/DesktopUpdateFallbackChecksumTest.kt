package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DesktopUpdateFallbackChecksumTest {
    @Test
    fun `UpdateInfo retains sha256 field for fallback verification`() {
        val info =
            UpdateInfo(
                available = true,
                currentVersion = Version.parse("9.0.0")!!,
                latestVersion = Version.parse("9.1.0")!!,
                releaseNotes = "Fixes",
                downloadUrl = "https://invalid-storage.example.com/BOSS-9.1.0.dmg",
                assetName = "BOSS-9.1.0.dmg",
                assetSize = 1000L,
                sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            )

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", info.sha256)
    }
}
