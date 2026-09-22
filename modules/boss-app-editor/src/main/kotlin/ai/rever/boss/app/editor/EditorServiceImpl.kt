package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.language.LanguageIds
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of EditorService.
 *
 * Provides real file I/O using the host filesystem:
 * - OpenFile: reads file from disk, detects language by extension
 * - SaveFile: writes content back to disk atomically (sibling temp + move)
 * - DetectMainFunctions: regex-based scan for entry points across multiple languages
 * - GetTokens / NavigateToDefinition: require PSI (in composeApp) — return empty
 *
 * Every path crosses the confinement gate in [validatePath] before any file I/O. The
 * shipped roots are [user.home] and the system temp dir (#885); they are constructor
 * parameters so tests (and an operator who wants a wider root) can override them.
 * That policy is a decision, not an accident: widening it is a deliberate edit to
 * [defaultAllowedRoots], not a silent relaxation.
 *
 * Failure conventions: [openFile] carries its error in the response (the proto has an
 * error field); [saveFile] and [detectMainFunctions] throw — their responses have no
 * error field, so a refused or failed call must be the exception. Gate refusals are
 * gRPC [Status.INVALID_ARGUMENT], genuine I/O failures [Status.INTERNAL], so the
 * client can tell "path refused" from "disk full" over the wire.
 *
 * [openFiles] is keyed by the canonical absolute path (not the request path), so
 * [listOpenFiles] reports the real location behind symlinks; a client must look up by
 * canonical path.
 */
@Suppress("TooManyFunctions")
class EditorServiceImpl(
    allowedRoots: List<String> = defaultAllowedRoots(),
    blockedPrefixes: List<String> = defaultBlockedPrefixes(),
) : EditorServiceGrpcKt.EditorServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(EditorServiceImpl::class.java)

    /** canonical path → isDirty: tracks files opened in this session */
    private val openFiles = ConcurrentHashMap<String, Boolean>()

    private val allowedRoots: List<String> =
        allowedRoots.map { runCatching { File(it).canonicalPath }.getOrDefault(it) }

    /**
     * Blocked prefixes are defense in depth under the allowlist. A prefix that is an
     * allowed root itself is dropped, because denying it would refuse every file the
     * policy explicitly allows (the running-as-root case where [user.home] is /root);
     * a system subtree inside an allowed root still denies (fail closed).
     */
    private val blockedPrefixes: List<String> =
        blockedPrefixes
            .map { runCatching { File(it).canonicalPath }.getOrDefault(it) }
            .filterNot { blocked ->
                allowedRoots.any { it.equals(blocked, ignoreCase = IS_CASE_INSENSITIVE) }
            }

    private fun isSubpathOfCanonical(
        path: String,
        root: String,
    ): Boolean {
        // Both arguments are already canonical (resolve-once at the call site). Windows
        // and the default macOS volumes are case-insensitive; Linux case-sensitivity is
        // preserved so /HOME/x does not read as /home/x.
        val ignoreCase = IS_CASE_INSENSITIVE
        return path.equals(root, ignoreCase = ignoreCase) ||
            path.lowercase().startsWith(root.lowercase() + File.separator)
    }

    private fun refuse(message: String): Nothing =
        throw
        Status.INVALID_ARGUMENT
            .withDescription(message)
            .asRuntimeException()

    private fun validatePath(rawPath: String): File {
        // A `..` PATH COMPONENT is traversal; `..` inside a file name (notes..bak) is not.
        val hasTraversal =
            rawPath
                .replace('\\', '/')
                .split('/')
                .any { it == ".." }
        if (hasTraversal) {
            refuse("Path traversal sequences ('..') are not allowed: $rawPath")
        }

        val canonicalFile =
            try {
                File(rawPath).canonicalFile
            } catch (e: Exception) {
                refuse("Invalid or unresolvable path: $rawPath (${e.message})")
            }

        val canonicalPath = canonicalFile.absolutePath

        val underAnAllowedRoot = allowedRoots.any { isSubpathOfCanonical(canonicalPath, it) }
        if (!underAnAllowedRoot) {
            refuse("Access denied: path '$rawPath' (canonical: '$canonicalPath') is outside allowed roots")
        }

        blockedPrefixes.firstOrNull { isSubpathOfCanonical(canonicalPath, it) }?.let { prefix ->
            refuse("Access to system path '$prefix' is not allowed: $rawPath")
        }

        return canonicalFile
    }

    private fun ioFailure(
        message: String,
        cause: Throwable,
    ): Nothing =
        throw
        Status.INTERNAL
            .withDescription(message)
            .withCause(cause)
            .asRuntimeException()

    private fun posixPermissions(path: java.nio.file.Path): Set<PosixFilePermission>? =
        Files
            .getFileAttributeView(path, PosixFileAttributeView::class.java)
            ?.readAttributes()
            ?.permissions()

    private fun atomicWriteText(
        file: File,
        content: String,
    ) {
        runCatching { file.parentFile?.mkdirs() }
            .onFailure { ioFailure("Could not create parent directory for ${file.absolutePath}", it) }
        // createTempFile rejects a prefix under 3 characters, so a 1-character file name
        // (prefix "x.") would throw before anything is written. Pad to the minimum.
        val prefix = "${file.name}.".padEnd(3, '_')
        val tempFile = File.createTempFile(prefix, ".tmp", file.parentFile)
        try {
            // Preserve the target's existing permissions (a 0600 source file must stay
            // 0600); the temp file otherwise inherits the process umask, which on POSIX
            // could widen a private file. New files keep the umask default.
            if (file.exists()) {
                val existing =
                    runCatching { posixPermissions(file.canonicalFile.toPath()) }
                        .onFailure { ioFailure("Could not read permissions for ${file.absolutePath}", it) }
                        .getOrNull()
                if (existing != null) {
                    runCatching { Files.setPosixFilePermissions(tempFile.toPath(), existing) }
                        .onFailure { ioFailure("Could not preserve permissions for ${file.absolutePath}", it) }
                }
            }
            tempFile.writeText(content, Charsets.UTF_8)
            atomicMoveFrom(file, tempFile)
        } catch (e: StatusRuntimeException) {
            throw e
        } catch (e: IOException) {
            ioFailure("Failed to write ${file.absolutePath}", e)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Move [temp] onto [target], replacing it. Same contract as the house
     * `File.atomicMoveFrom` (composeApp `ai.rever.boss.utils.AtomicFileWrite` — kept in
     * sync with it by hand; `composeApp` is not reachable from `modules/`):
     * `renameTo` is wrong here because Win32 `MoveFile` refuses to overwrite an
     * existing target, so `Files.move` + `REPLACE_EXISTING` is the portable form and
     * `ATOMIC_MOVE` rules out a torn destination when the volume supports it.
     */
    @Suppress("SwallowedException")
    private fun atomicMoveFrom(
        target: File,
        temp: File,
    ) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Language-specific main/entry-point patterns
    private val mainPatterns =
        listOf(
            Regex("""^\s*(?:suspend\s+)?fun\s+main\s*\("""), // Kotlin
            Regex("""^\s*public\s+static\s+void\s+main\s*\(\s*String"""), // Java
            Regex("""^\s*if\s+__name__\s*==\s*['"]__main__['"]\s*:"""), // Python
            Regex("""^\s*func\s+main\s*\(\s*\)"""), // Go / Swift
            Regex("""^\s*fn\s+main\s*\(\s*\)"""), // Rust
            Regex("""^\s*int\s+main\s*\("""), // C / C++
        )

    override suspend fun openFile(request: OpenFileRequest): OpenFileResponse =
        withContext(Dispatchers.IO) {
            logger.info("openFile: path={}", request.path)
            val file =
                try {
                    validatePath(request.path)
                } catch (e: Exception) {
                    return@withContext OpenFileResponse
                        .newBuilder()
                        .setSuccess(false)
                        .setErrorMessage(e.message ?: "Invalid path")
                        .build()
                }
            if (!file.exists() || !file.isFile) {
                return@withContext OpenFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("File not found: ${request.path}")
                    .build()
            }
            try {
                val content = file.readText(Charsets.UTF_8)
                openFiles[file.absolutePath] = false
                OpenFileResponse
                    .newBuilder()
                    .setSuccess(true)
                    .setContent(content)
                    .setLanguage(languageForFile(file))
                    .build()
            } catch (e: Exception) {
                logger.warn("openFile read failed: {}", e.message)
                OpenFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.message ?: "Read failed")
                    .build()
            }
        }

    override suspend fun saveFile(request: SaveFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("saveFile: path={}", request.path)
            val file = validatePath(request.path)
            // A refused or failed save must reach the caller, not masquerade as success:
            // the response carries no error field, so the failure is the exception
            // (INVALID_ARGUMENT for a refused path, INTERNAL for a failed write).
            try {
                atomicWriteText(file, request.content)
            } catch (e: StatusRuntimeException) {
                logger.error("saveFile refused/failed for {}: {}", request.path, e.message)
                throw e
            }
            openFiles[file.absolutePath] = false
            Empty.getDefaultInstance()
        }

    override suspend fun getTokens(request: GetTokensRequest): GetTokensResponse {
        // PSI-based tokenization lives in composeApp (kotlin-compiler-embeddable).
        // Return empty — the kernel-side editor proxy uses composeApp's PSI directly.
        logger.debug("getTokens: path={} (PSI not in this process)", request.path)
        return GetTokensResponse.newBuilder().build()
    }

    override suspend fun navigateToDefinition(request: NavigateRequest): NavigateResponse {
        logger.debug("navigateToDefinition: path={} (PSI not in this process)", request.path)
        return NavigateResponse.newBuilder().setFound(false).build()
    }

    override suspend fun detectMainFunctions(request: DetectMainRequest): DetectMainResponse =
        withContext(Dispatchers.IO) {
            logger.info("detectMainFunctions: path={}", request.path)
            // The response has no error field: a refused path throws (INVALID_ARGUMENT)
            // rather than masquerading as "this file has no entry points".
            val file = validatePath(request.path)
            if (!file.exists() || !file.isFile) return@withContext DetectMainResponse.newBuilder().build()

            val functions = mutableListOf<MainFunctionInfo>()
            try {
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (mainPatterns.any { it.containsMatchIn(line) }) {
                        functions +=
                            MainFunctionInfo
                                .newBuilder()
                                .setName("main")
                                .setLine(idx + 1)
                                .setDisplayName("main (line ${idx + 1})")
                                .setQualifiedName("${file.nameWithoutExtension}.main")
                                .build()
                    }
                }
            } catch (e: Exception) {
                logger.warn("detectMainFunctions scan error: {}", e.message)
            }

            DetectMainResponse.newBuilder().addAllFunctions(functions).build()
        }

    override suspend fun listOpenFiles(request: Empty): ListOpenFilesResponse {
        val infos =
            openFiles.entries.map { (path, dirty) ->
                OpenFileInfo
                    .newBuilder()
                    .setPath(path)
                    .setIsModified(dirty)
                    .build()
            }
        return ListOpenFilesResponse.newBuilder().addAllFiles(infos).build()
    }

    // Filename rules take precedence even when a suffix is a known extension
    // (Dockerfile.sh is a Dockerfile). Preserve this service's proto and unknown defaults.
    private fun languageForFile(file: File): String =
        LanguageIds.detect(file.name).takeUnless { it == LanguageIds.TEXT }
            ?: detectLanguage(file.extension)

    /**
     * BossConsole#75: this used to be its own hand-maintained table, independent of
     * (and disagreeing with) `composeApp`'s `EditorLanguages` - most visibly, `.sh`/
     * `.bash`/`.zsh` were `shell` here and `bash` there. Both now read
     * [LanguageIds], the module the two were consolidated into. `proto` stays a local
     * addition: `LanguageIds` is the table shared with `boss-file-types.json`'s
     * default-file-type-association list, and adding an id there means adding the
     * extension to that JSON too - out of scope for a language-id fix.
     */
    internal fun detectLanguage(ext: String): String =
        when (ext.lowercase()) {
            "proto" -> "protobuf"
            else -> LanguageIds.forExtension(ext) ?: "plaintext"
        }

    companion object {
        /** Windows and the default macOS volumes are case-insensitive; Linux is not. */
        private val IS_CASE_INSENSITIVE: Boolean =
            System
                .getProperty("os.name", "")
                .startsWith("windows", ignoreCase = true) ||
                System.getProperty("os.name", "").startsWith("mac", ignoreCase = true)

        /**
         * The shipped confinement policy (#885): the user's home and the system temp dir.
         * Deliberately narrow — a project outside both (an external volume, /srv, a WSL
         * mount) is refused, which is the point of the gate. Override via the constructor
         * when a wider root is intended.
         */
        private fun defaultAllowedRoots(): List<String> =
            listOfNotNull(
                System.getProperty("user.home"),
                System.getProperty("java.io.tmpdir"),
            )

        /**
         * Defense in depth under the allowlist (mirrors `FileSystemPathPolicy`'s blocked
         * roots in `modules/boss-service-filesystem` — kept in sync by hand). The Windows
         * roots and `SystemRoot`/`WINDIR` are read only on Windows, like that policy.
         */
        private fun defaultBlockedPrefixes(): List<String> {
            val list =
                mutableListOf(
                    "/etc",
                    "/sys",
                    "/proc",
                    "/dev",
                    "/boot",
                    "/root",
                )
            if (System.getProperty("os.name", "").startsWith("windows", ignoreCase = true)) {
                list += listOf("C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)")
                System.getenv("SystemRoot")?.let { list.add(it) }
                System.getenv("WINDIR")?.let { list.add(it) }
            }
            return list
        }
    }
}
