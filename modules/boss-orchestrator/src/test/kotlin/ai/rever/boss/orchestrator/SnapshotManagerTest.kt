package ai.rever.boss.orchestrator

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotManagerTest {
    private lateinit var tempDir: java.io.File
    private lateinit var manager: SnapshotManager

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("boss-snap-test").toFile()
        manager = SnapshotManager(tempDir)
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save returns a non-blank snapshot ID`() {
        val id = manager.save("proc-1", "hello".toByteArray())
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `construction fails when snapshots path is occupied by a file`() {
        val occupiedDataDir = Files.createTempDirectory("boss-snap-occupied").toFile()
        try {
            java.io.File(occupiedDataDir, "snapshots").writeText("occupied")

            assertFails { SnapshotManager(occupiedDataDir) }
        } finally {
            occupiedDataDir.deleteRecursively()
        }
    }

    @Test
    fun `concurrent first saves both succeed`() {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val saves =
                listOf("first", "second").map { value ->
                    executor.submit<String> {
                        start.await()
                        manager.save("proc-concurrent", value.toByteArray())
                    }
                }
            start.countDown()

            assertEquals(2, saves.map { it.get() }.distinct().size)
            assertEquals(2, manager.listSnapshots("proc-concurrent").size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `save and load round-trips data correctly`() {
        val data = "test snapshot data".toByteArray()
        manager.save("proc-1", data, "test description")

        val loaded = manager.loadLatest("proc-1")
        assertNotNull(loaded)
        assertEquals("test snapshot data", loaded.decodeToString())
    }

    @Test
    fun `loadLatest returns null when no snapshots exist for process`() {
        val loaded = manager.loadLatest("nonexistent-proc")
        assertNull(loaded)
    }

    @Test
    fun `loadLatest returns the most recent snapshot`() {
        manager.save("proc-2", "older".toByteArray())
        Thread.sleep(10)
        manager.save("proc-2", "newer".toByteArray())

        val loaded = manager.loadLatest("proc-2")
        assertNotNull(loaded)
        assertEquals("newer", loaded.decodeToString())
    }

    @Test
    fun `listSnapshots returns empty list when no snapshots exist`() {
        val list = manager.listSnapshots("nonexistent-proc")
        assertTrue(list.isEmpty())
    }

    @Test
    fun `listSnapshots returns snapshots ordered newest first`() {
        manager.save("proc-3", "snap-1".toByteArray(), "first")
        Thread.sleep(10)
        manager.save("proc-3", "snap-2".toByteArray(), "second")

        val list = manager.listSnapshots("proc-3")
        assertEquals(2, list.size)
        assertEquals("second", list[0].description)
        assertEquals("first", list[1].description)
    }

    @Test
    fun `cleanup retains keepLast snapshots and deletes older ones`() {
        for (i in 1..5) {
            manager.save("proc-4", "data-$i".toByteArray(), "desc-$i")
            Thread.sleep(5)
        }
        assertEquals(5, manager.listSnapshots("proc-4").size)

        manager.cleanup("proc-4", keepLast = 2)
        val remaining = manager.listSnapshots("proc-4")
        assertEquals(2, remaining.size)
        assertEquals("desc-5", remaining[0].description)
        assertEquals("desc-4", remaining[1].description)
    }

    @Test
    fun `cleanup handles keepLast zero by deleting all snapshots`() {
        for (i in 1..3) {
            manager.save("proc-5", "data-$i".toByteArray())
            Thread.sleep(5)
        }
        manager.cleanup("proc-5", keepLast = 0)
        assertTrue(manager.listSnapshots("proc-5").isEmpty())
    }

    @Test
    fun `cleanup is a no-op when count is already within keepLast`() {
        for (i in 1..3) {
            manager.save("proc-6", "data-$i".toByteArray())
            Thread.sleep(5)
        }
        manager.cleanup("proc-6", keepLast = 10)

        assertEquals(3, manager.listSnapshots("proc-6").size)
    }

    @Test
    fun `cleanup on nonexistent process does not throw`() {
        manager.cleanup("no-such-proc", keepLast = 5)
    }

    @Test
    fun `SnapshotInfo has correct processId and positive sizeBytes`() {
        manager.save("proc-7", ByteArray(42) { it.toByte() })
        val info = manager.listSnapshots("proc-7").first()

        assertEquals("proc-7", info.processId)
        assertTrue(info.sizeBytes > 0)
        assertTrue(info.timestamp > 0)
    }

    @Test
    fun `save rejects path traversal process IDs`() {
        assertFailsWith<IllegalArgumentException> {
            manager.save("../../escape", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("../parent", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("sub/dir", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("invalid.", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("CON", "payload".toByteArray())
        }
    }

    @Test
    fun `loadLatest and listSnapshots reject traversal process IDs`() {
        assertFailsWith<IllegalArgumentException> {
            manager.loadLatest("../../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            manager.listSnapshots("../../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            manager.cleanup("../../escape")
        }
    }

    @Test
    fun `save restricts permissions to owner on posix filesystems`() {
        manager.save("proc-perms", "secret-payload".toByteArray(), "secret description")
        val snapshotsDir = java.io.File(tempDir, "snapshots/proc-perms")
        val files = snapshotsDir.listFiles() ?: emptyArray()
        assertTrue(files.isNotEmpty())

        for (file in files) {
            val path = file.toPath()
            val hasPosix = path.fileSystem.supportedFileAttributeViews().contains("posix")
            if (hasPosix) {
                val perms = Files.getPosixFilePermissions(path)
                val ownerOnly =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )
                assertEquals(ownerOnly, perms, "Expected owner-only permissions for ${file.name}")
            }
        }
        assertTrue(files.none { it.extension == "tmp" })
    }

    @Test
    fun `read list and cleanup reject a symlinked process directory`() {
        val outside = Files.createTempDirectory("snapshot-outside").toFile()
        try {
            java.io.File(outside, "1-external.snapshot").writeText("external")
            val link = java.io.File(tempDir, "snapshots/proc-link").toPath()
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }

            assertFailsWith<IllegalArgumentException> {
                manager.save("proc-link", "replacement".toByteArray())
            }
            assertFailsWith<IllegalArgumentException> { manager.loadLatest("proc-link") }
            assertFailsWith<IllegalArgumentException> { manager.listSnapshots("proc-link") }
            assertFailsWith<IllegalArgumentException> { manager.cleanup("proc-link") }
            assertTrue(java.io.File(outside, "1-external.snapshot").exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `snapshot and description symlinks are not followed`() {
        manager.save("proc-files", "safe".toByteArray())
        val processDir = java.io.File(tempDir, "snapshots/proc-files")
        val outside = java.io.File(tempDir, "outside-secret").apply { writeText("secret") }
        try {
            Files.createSymbolicLink(
                java.io.File(processDir, "9999999999999-external.snapshot").toPath(),
                outside.toPath(),
            )
            val realSnapshot =
                processDir
                    .listFiles { file -> file.extension == "snapshot" }
                    .orEmpty()
                    .single { !Files.isSymbolicLink(it.toPath()) }
            Files.createSymbolicLink(
                java.io.File(processDir, "${realSnapshot.nameWithoutExtension}.desc").toPath(),
                outside.toPath(),
            )
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }

        assertEquals("safe", manager.loadLatest("proc-files")?.decodeToString())
        assertEquals("", manager.listSnapshots("proc-files").single().description)
    }
}
