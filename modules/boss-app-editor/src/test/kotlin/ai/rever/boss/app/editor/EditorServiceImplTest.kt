package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.plugin.language.LanguageIds
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#75: this used to be a hand-maintained table independent of
 * `composeApp`'s `EditorLanguages`, and the two disagreed on `.sh`/`.bash`/`.zsh`
 * (`shell` here, `bash` there). Both now read the shared `LanguageIds` table -
 * `detectLanguage("sh")` returning `bash` rather than `shell` is the actual bug this
 * consolidation fixes, not just a refactor with no observable effect.
 */
class EditorServiceImplTest {
    private val service = EditorServiceImpl()

    @Test
    fun `shell extensions now agree with the shared table, not the old local one`() {
        assertEquals("bash", service.detectLanguage("sh"))
        assertEquals("bash", service.detectLanguage("bash"))
        assertEquals("bash", service.detectLanguage("zsh"))
    }

    @Test
    fun `previously working mappings are unchanged`() {
        assertEquals("kotlin", service.detectLanguage("kt"))
        assertEquals("kotlin", service.detectLanguage("kts"))
        assertEquals("java", service.detectLanguage("java"))
        assertEquals("python", service.detectLanguage("py"))
        assertEquals("javascript", service.detectLanguage("js"))
        assertEquals("go", service.detectLanguage("go"))
        assertEquals("rust", service.detectLanguage("rs"))
        assertEquals("yaml", service.detectLanguage("yaml"))
        assertEquals("json", service.detectLanguage("json"))
    }

    @Test
    fun `proto keeps its local mapping - the shared table has no id for it`() {
        // LanguageIds is shared with boss-file-types.json's default-app extension
        // list; adding "proto" there is a separate change, so it stays a local
        // addition on top of the shared table rather than migrated into it.
        assertEquals("protobuf", service.detectLanguage("proto"))
    }

    @Test
    fun `an unrecognised extension is plaintext, not the shared table's text`() {
        // EditorServiceImpl's own default was always "plaintext", distinct from
        // EditorLanguages' "text" - preserved deliberately, since this is this
        // service's own gRPC contract, not a value composeApp reads.
        assertEquals("plaintext", service.detectLanguage("notarealextension"))
    }

    @Test
    fun `newly available ids the old local table never had`() {
        // Gained for free by reading the shared table instead of a copy that only
        // ever knew ~24 extensions.
        assertEquals("fortran", service.detectLanguage("f90"))
        assertEquals("clojure", service.detectLanguage("clj"))
        assertEquals("batch", service.detectLanguage("bat"))
        assertEquals("diff", service.detectLanguage("diff"))
    }

    @Test
    fun `every shared extension agrees with the service`() {
        LanguageIds.extensions().forEach { (extension, language) ->
            assertEquals(language, service.detectLanguage(extension), extension)
        }
    }

    @Test
    fun `opening a shell file returns the shared language through the RPC response`() =
        runBlocking {
            val file = Files.createTempFile("boss-language-", ".sh").toFile()
            try {
                file.writeText("echo hello\n")
                val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                assertTrue(response.success)
                assertEquals("bash", response.language)
                assertEquals("echo hello\n", response.content)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `opening named files uses shared filename precedence and keeps service defaults`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-language-names-").toFile()
            val cases =
                mapOf(
                    "Dockerfile" to "dockerfile",
                    "Containerfile" to "dockerfile",
                    "Makefile" to "makefile",
                    "GNUmakefile" to "makefile",
                    "Gemfile" to "ruby",
                    "Rakefile" to "ruby",
                    "Dockerfile.dev" to "dockerfile",
                    "Dockerfile.sh" to "dockerfile",
                    ".env.local" to "properties",
                    "service.proto" to "protobuf",
                    "notes.unknown" to "plaintext",
                    "Gemfile.lock" to "plaintext",
                )
            try {
                cases.forEach { (name, expected) ->
                    val file = directory.resolve(name).apply { writeText("content") }
                    val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                    assertTrue(response.success, name)
                    assertEquals(expected, response.language, name)
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `extension lookup is case-insensitive`() {
        assertEquals("kotlin", service.detectLanguage("KT"))
    }

    @Test
    fun `saveFile writes content atomically and updates openFiles dirty tracking`() =
        runBlocking<Unit> {
            val file = Files.createTempFile("boss-editor-atomic-", ".txt").toFile()
            try {
                file.writeText("initial content")
                service.saveFile(
                    ai.rever.boss.ipc.proto.services.SaveFileRequest
                        .newBuilder()
                        .setPath(file.absolutePath)
                        .setContent("updated atomic content")
                        .build(),
                )
                assertEquals("updated atomic content", file.readText())

                val openFiles =
                    service.listOpenFiles(
                        ai.rever.boss.ipc.proto.Empty
                            .getDefaultInstance(),
                    )
                val info = openFiles.filesList.find { it.path == file.canonicalFile.absolutePath }
                assertTrue(info != null)
                assertFalse(info.isModified)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `path traversal and system paths are refused`() =
        runBlocking<Unit> {
            // The gate is proven with an explicit policy and REAL paths so the refusal is
            // deterministic on every OS: an allowed root with a blocked system subtree inside
            // it, mirroring the shipped home+blocked-prefix shape.
            val allowedRoot = Files.createTempDirectory("editor-gate-").toFile()
            val systemSubtree = File(allowedRoot, "fake-system")
            systemSubtree.mkdirs()
            val inSystem = File(systemSubtree, "cmd.exe")
            inSystem.writeText("system file")
            val inAllowed = File(allowedRoot, "ok.txt")
            inAllowed.writeText("fine")
            try {
                val gated =
                    EditorServiceImpl(
                        allowedRoots = listOf(allowedRoot.absolutePath),
                        blockedPrefixes = listOf(systemSubtree.absolutePath),
                    )

                // Traversal is a refusal (the gate fires), never a read.
                val traversalPath = allowedRoot.absolutePath + "/../fake-system/cmd.exe"
                val openDotDot = gated.openFile(OpenFileRequest.newBuilder().setPath(traversalPath).build())
                assertFalse(openDotDot.success)
                assertTrue(openDotDot.errorMessage.contains("not allowed"), openDotDot.errorMessage)

                // A path under a blocked system prefix is a GATE refusal ("system path"),
                // even though the file exists and is inside the allowed root - not a
                // "File not found".
                val openSystem = gated.openFile(OpenFileRequest.newBuilder().setPath(inSystem.absolutePath).build())
                assertFalse(openSystem.success)
                assertTrue(openSystem.errorMessage.contains("system path"), openSystem.errorMessage)

                // A path outside the allowed root is a GATE refusal ("outside allowed roots").
                val openOutside = gated.openFile(OpenFileRequest.newBuilder().setPath("/etc/passwd").build())
                assertFalse(openOutside.success)
                assertTrue(openOutside.errorMessage.contains("outside allowed roots"), openOutside.errorMessage)

                // And a path inside the allowed root, outside the blocked subtree, still opens.
                val openOk = gated.openFile(OpenFileRequest.newBuilder().setPath(inAllowed.absolutePath).build())
                assertTrue(openOk.success, openOk.errorMessage)
            } finally {
                allowedRoot.deleteRecursively()
            }
        }

    @Test
    fun `a file name with two dots but no traversal component is allowed`() =
        runBlocking<Unit> {
            // "notes..bak" is a legitimate name: only a `..` PATH COMPONENT is traversal.
            val file = Files.createTempFile("notes..", ".bak").toFile()
            try {
                file.writeText("backup")
                val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                assertTrue(response.success, response.errorMessage)
                assertEquals("backup", response.content)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `saveFile surfaces a refused path as a gRPC INVALID_ARGUMENT, not a silent success`() =
        runBlocking<Unit> {
            // The response carries no error field, so a refused save must throw: a silent
            // Empty would let the caller (and its autosave retry logic) believe the write held.
            // Over the wire it must be INVALID_ARGUMENT with a description, not the
            // message-less UNKNOWN that a raw IllegalArgumentException becomes.
            val root = Files.createTempDirectory("editor-allow-").toFile()
            val outside = Files.createTempDirectory("editor-outside-").toFile()
            val strictService = EditorServiceImpl(allowedRoots = listOf(root.absolutePath))
            try {
                val target = File(outside, "outside.txt")
                target.writeText("untouched")

                // Traversal is refused before any I/O.
                val refusedTraversal =
                    assertFailsWith<StatusRuntimeException> {
                        strictService.saveFile(
                            ai.rever.boss.ipc.proto.services.SaveFileRequest
                                .newBuilder()
                                .setPath("${root.absolutePath}/../${outside.name}/outside.txt")
                                .setContent("must not be written")
                                .build(),
                        )
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, refusedTraversal.status.code)

                // A save whose target is an EXISTING file outside the allowed root is
                // refused, and that file must be byte-for-byte untouched - the gate is the
                // only thing between the write and the user's document.
                val refused =
                    assertFailsWith<StatusRuntimeException> {
                        strictService.saveFile(
                            ai.rever.boss.ipc.proto.services.SaveFileRequest
                                .newBuilder()
                                .setPath(target.absolutePath)
                                .setContent("must not be written")
                                .build(),
                        )
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, refused.status.code)
                assertEquals("untouched", target.readText())

                // A refused save leaves no temp sibling behind in the target's directory.
                val strays = outside.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
                assertTrue(strays.isEmpty(), "temp leftovers: ${strays.map { it.name }}")
            } finally {
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }

    @Test
    fun `saveFile preserves the existing file's permissions on POSIX`() =
        runBlocking<Unit> {
            val file = Files.createTempFile("boss-editor-perms-", ".txt").toFile()
            try {
                file.writeText("initial")
                val posixView = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
                if (posixView != null) {
                    val ownerOnly =
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        )
                    Files.setPosixFilePermissions(file.toPath(), ownerOnly)
                    service.saveFile(
                        ai.rever.boss.ipc.proto.services.SaveFileRequest
                            .newBuilder()
                            .setPath(file.absolutePath)
                            .setContent("updated, still private")
                            .build(),
                    )
                    assertEquals("updated, still private", file.readText())
                    // The atomic move must not widen a 0600 document to the umask default.
                    assertEquals(ownerOnly, Files.getPosixFilePermissions(file.toPath()))
                } else {
                    // Non-POSIX filesystem (Windows CI): the save still succeeds.
                    service.saveFile(
                        ai.rever.boss.ipc.proto.services.SaveFileRequest
                            .newBuilder()
                            .setPath(file.absolutePath)
                            .setContent("updated")
                            .build(),
                    )
                    assertEquals("updated", file.readText())
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun `saveFile atomic write handles a one-character file name`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("boss-editor-onename").toFile()
            try {
                val oneChar = java.io.File(dir, "x")
                service.saveFile(
                    ai.rever.boss.ipc.proto.services.SaveFileRequest
                        .newBuilder()
                        .setPath(oneChar.absolutePath)
                        .setContent("one-char ok")
                        .build(),
                )
                assertEquals("one-char ok", oneChar.readText())
                val strays = dir.listFiles()?.filter { it.name != "x" }.orEmpty()
                assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
            } finally {
                dir.deleteRecursively()
            }
        }
}
