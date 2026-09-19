package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemDataProviderImplTest {
    private val tempDir =
        File(
            System.getProperty("user.home"),
            "boss-fs-provider-test-${System.currentTimeMillis()}",
        )

    @BeforeTest
    fun setUp() {
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testProviderLifecycleAndPathConfinement() =
        runTest {
            val provider = FileSystemDataProviderImpl()
            assertTrue(provider.supportsHiddenEntries)
            assertEquals(System.getProperty("user.home"), provider.getHomeDirectory())

            val subDir = File(tempDir, "subdir")
            subDir.mkdirs()

            // Create file
            val createResult = provider.createFile(subDir.absolutePath, "test.txt")
            assertTrue(createResult.isSuccess, "Should create file inside directory")
            val filePath = createResult.getOrThrow()

            // Write & Read
            val writeResult = provider.writeFile(filePath, "hello boss")
            assertTrue(writeResult.isSuccess)
            val readResult = provider.readFile(filePath)
            assertTrue(readResult.isSuccess)
            assertEquals("hello boss", readResult.getOrThrow())

            // Rename
            val renameResult = provider.rename(filePath, "renamed.txt")
            assertTrue(renameResult.isSuccess)

            // Dispose provider
            provider.dispose()
        }

    @Test
    fun testDeleteUserHomeForbidden() =
        runTest {
            val provider = FileSystemDataProviderImpl()
            val userHome = System.getProperty("user.home")
            val deleteHomeResult = provider.delete(userHome)

            assertTrue(deleteHomeResult.isFailure, "Deleting user home directory must be forbidden")
            assertTrue(deleteHomeResult.exceptionOrNull() is SecurityException)

            provider.dispose()
        }

    @Test
    @Suppress("SwallowedException")
    fun testDeleteNestedSymlinkDoesNotFollowLink() =
        runTest {
            val provider = FileSystemDataProviderImpl()

            val subDir = File(tempDir, "deletable-dir")
            subDir.mkdirs()

            val targetDir = File(tempDir, "external-target-dir")
            targetDir.mkdirs()
            val canaryFile = File(targetDir, "canary.txt")
            canaryFile.writeText("sensitive content")

            val linkFile = File(subDir, "link-to-target")
            try {
                Files.createSymbolicLink(linkFile.toPath(), targetDir.toPath())
            } catch (e: FileSystemException) {
                // Windows environment without Developer Mode / admin privilege cannot create symbolic links
                provider.dispose()
                return@runTest
            }

            val deleteResult = provider.delete(subDir.absolutePath)
            assertTrue(deleteResult.isSuccess, "Should delete subDir")

            assertFalse(subDir.exists(), "Subdirectory must be deleted")
            assertTrue(targetDir.exists(), "External target directory must not be deleted")
            assertTrue(canaryFile.exists(), "File inside external directory must remain intact")

            provider.dispose()
        }
}
