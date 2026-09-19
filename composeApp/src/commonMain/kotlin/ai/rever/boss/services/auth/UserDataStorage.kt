package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent storage for user data to survive app restarts
 *
 * WHY THIS EXISTS (Important - Not a Workaround!):
 * ================================================
 *
 * This is the CORRECT solution for custom authentication providers with Supabase.
 * It is NOT a hack or temporary workaround - it's the recommended pattern.
 *
 * Background:
 * -----------
 * Supabase Auth was designed for built-in authentication providers (OAuth, email/password, magic links).
 * When you use a built-in provider, Supabase generates JWT tokens that include full user information,
 * and the Supabase-KT client automatically populates session.user with this data.
 *
 * Custom Authentication Providers (like Passkeys):
 * ------------------------------------------------
 * For custom authentication providers (WebAuthn/passkeys), we implement the authentication
 * logic ourselves:
 *
 * 1. Client verifies passkey signature (Touch ID, Windows Hello, etc.)
 * 2. Edge Function generates Supabase-compatible JWT tokens
 * 3. Client imports session using auth.importSession()
 * 4. **Problem**: Supabase-KT intentionally does NOT populate session.user from custom JWTs
 *    - This is by design, not a bug
 *    - Custom JWTs don't include the user metadata that built-in providers include
 *    - The session.user property remains null
 *
 * 5. **Solution**: UserDataStorage persists user information separately
 *    - We store user data (id, email, createdAt) in local storage
 *    - This data persists across app restarts
 *    - SessionManager coordinates between Supabase auth (JWT tokens) and UserDataStorage (user info)
 *
 * Why Not Use Magic Links Instead?
 * --------------------------------
 * Magic links would populate session.user, but they:
 * - Break the passwordless/biometric UX flow
 * - Add unnecessary friction (email verification step)
 * - Defeat the purpose of passkey authentication
 * - Are less secure (email interception risk)
 *
 * The Correct Pattern:
 * -------------------
 * For custom authentication providers with Supabase:
 * 1. Implement authentication logic yourself (verify passkey, etc.)
 * 2. Generate Supabase-compatible JWT tokens on the backend
 * 3. Use importSession() to establish the Supabase session (for API access)
 * 4. Persist user data separately (UserDataStorage) for app state
 * 5. Use SessionManager to coordinate both
 *
 * This pattern is used by many Supabase applications that implement custom auth providers.
 *
 * Related Documentation:
 * ---------------------
 * - See SessionManager.kt for session orchestration logic
 * - See PasskeyAuthService.kt for passkey authentication implementation
 * - See CoreAuthService.kt for session initialization and restoration
 *
 * Storage Location:
 * ----------------
 * User data is stored in: ~/.boss/user_data.json
 * This file is automatically created and managed by this service.
 */
object UserDataStorage {
    private var currentStorageFile = BossDirectories.resolve("user_data.json")
    private var currentPendingWizardCompletedFile = BossDirectories.resolve("pending_wizard_completed")

    internal val storageFile: File get() = currentStorageFile
    internal val pendingWizardCompletedFile: File get() = currentPendingWizardCompletedFile

    internal fun resetForTesting(rootDir: File) {
        currentStorageFile = File(rootDir, "user_data.json")
        currentPendingWizardCompletedFile = File(rootDir, "pending_wizard_completed")
        currentStorageFile.parentFile?.mkdirs()
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val logger = BossLogger.forComponent("UserDataStorage")

    /**
     * Guards concurrent reads and writes across coroutines, serializing
     * access to [currentStorageFile] and [currentPendingWizardCompletedFile].
     */
    private val fileLock = Mutex()

    /**
     * Generation counter bumped on every [clearUserData] call.
     *
     * Fences saves against a logout that interleaved while waiting for [fileLock].
     * A save entered before logout captures the generation prior to lock acquisition.
     * If logout occurs while it waits, its captured generation differs from [clearGeneration],
     * skipping the save and preventing resurrecting the cleared user data.
     */
    private val clearGeneration = AtomicLong()

    /** Test seam: the generation a save entering now would capture. */
    internal fun generationForTest(): Long = clearGeneration.get()

    /** Test seam invoked after the production entry point captures the generation. */
    internal var afterGenerationCaptureForTest: (suspend () -> Unit)? = null

    @Serializable
    data class StoredUserData(
        val id: String,
        val email: String,
        val createdAt: String,
        val authenticatedVia: String? = null, // "passkey", "magic_link", "password", etc.
        val pluginWizardCompleted: Boolean = false, // Whether the plugin install wizard has been completed
    )

    init {
        // Ensure directory exists
        storageFile.parentFile?.mkdirs()
    }

    private fun readPendingWizardFlag(): Boolean {
        if (!pendingWizardCompletedFile.exists()) return false
        return try {
            pendingWizardCompletedFile.readText().trim().toBoolean()
        } catch (e: Exception) {
            logger.debug(
                LogCategory.AUTH,
                "Could not read pending wizard-completed marker - assuming false",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    private fun readStoredWizardFlag(): Boolean {
        if (!storageFile.exists()) return false
        return try {
            val content = storageFile.readText()
            val data = json.decodeFromString<StoredUserData>(content)
            data.pluginWizardCompleted
        } catch (e: Exception) {
            logger.debug(
                LogCategory.AUTH,
                "Could not read stored wizard status - assuming false",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * The save body with an explicit entry generation, so the fence's regression test can
     * hand a save the generation it would have captured before a logout interleaved.
     */
    private suspend fun doSaveUserData(
        user: UserInfo,
        authenticatedVia: String?,
        generationAtEntry: Long,
    ) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                if (generationAtEntry != clearGeneration.get()) {
                    logger.warn(
                        LogCategory.AUTH,
                        "Skipping user data save: logout occurred while the save waited for the lock",
                    )
                    return@withLock
                }
                try {
                    val wizardCompleted = readPendingWizardFlag() || readStoredWizardFlag()
                    val data =
                        StoredUserData(
                            id = user.id,
                            email = user.email,
                            createdAt = user.createdAt,
                            authenticatedVia = authenticatedVia,
                            pluginWizardCompleted = wizardCompleted,
                        )
                    val content = json.encodeToString(data)
                    storageFile.atomicWriteText(content)
                    logger.debug(
                        LogCategory.AUTH,
                        "Saved user data",
                        mapOf("email" to LogSanitizer.maskEmail(user.email)),
                    )
                    if (pendingWizardCompletedFile.exists()) {
                        pendingWizardCompletedFile.delete()
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error saving user data", error = e)
                }
            }
        }
    }

    /**
     * Save user data to persistent storage.
     *
     * Captures the logout generation prior to lock acquisition so a concurrent
     * logout does not get overwritten by an earlier in-flight save.
     */
    suspend fun saveUserData(
        user: UserInfo,
        authenticatedVia: String? = null,
    ) {
        val generationAtEntry = clearGeneration.get()
        afterGenerationCaptureForTest?.invoke()
        doSaveUserData(user, authenticatedVia, generationAtEntry)
    }

    /**
     * Load user data from persistent storage.
     */
    suspend fun loadUserData(): UserInfo? =
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    if (storageFile.exists()) {
                        val content = storageFile.readText()
                        val data = json.decodeFromString<StoredUserData>(content)
                        logger.debug(
                            LogCategory.AUTH,
                            "Loaded user data",
                            mapOf(
                                "email" to LogSanitizer.maskEmail(data.email),
                                "authenticatedVia" to (data.authenticatedVia ?: "unknown"),
                            ),
                        )
                        UserInfo(
                            id = data.id,
                            email = data.email,
                            createdAt = data.createdAt,
                        )
                    } else {
                        logger.debug(LogCategory.AUTH, "No stored user data found")
                        null
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error loading user data", error = e)
                    null
                }
            }
        }

    /**
     * Clear stored user data (on logout).
     */
    suspend fun clearUserData() {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    clearGeneration.incrementAndGet()
                    if (storageFile.exists()) {
                        storageFile.delete()
                        logger.debug(LogCategory.AUTH, "Cleared user data")
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error clearing user data", error = e)
                }
            }
        }
    }

    /**
     * Check if the plugin installation wizard has been completed for this user.
     *
     * Checks both the main user_data.json and the pending file (for cases where
     * the wizard was completed before user logged in).
     */
    suspend fun isPluginWizardCompleted(): Boolean =
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    if (storageFile.exists()) {
                        try {
                            val content = storageFile.readText()
                            val data = json.decodeFromString<StoredUserData>(content)
                            if (data.pluginWizardCompleted) {
                                return@withLock true
                            }
                        } catch (e: kotlinx.serialization.SerializationException) {
                            logger.error(
                                LogCategory.SYSTEM,
                                "User data file corrupted, will reset on next save",
                                error = e,
                            )
                        } catch (e: Exception) {
                            logger.error(
                                LogCategory.SYSTEM,
                                "Error reading user data file",
                                error = e,
                            )
                        }
                    }

                    if (pendingWizardCompletedFile.exists()) {
                        try {
                            val pendingValue = pendingWizardCompletedFile.readText().trim().toBoolean()
                            if (pendingValue) {
                                return@withLock true
                            }
                        } catch (e: Exception) {
                            logger.error(
                                LogCategory.SYSTEM,
                                "Error reading pending wizard file",
                                error = e,
                            )
                        }
                    }

                    false
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error checking plugin wizard status", error = e)
                    false
                }
            }
        }

    /**
     * Mark the plugin installation wizard as completed for this user.
     *
     * If user_data.json doesn't exist yet (user not logged in), stores the setting
     * in a separate file that will be merged when the user logs in. If the file exists but is
     * undecodable, the flag goes to that same pending marker.
     */
    suspend fun setPluginWizardCompleted(completed: Boolean) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    if (storageFile.exists()) {
                        val content = storageFile.readText()
                        try {
                            val data = json.decodeFromString<StoredUserData>(content)
                            val updatedData = data.copy(pluginWizardCompleted = completed)
                            storageFile.atomicWriteText(json.encodeToString(updatedData))
                            logger.debug(
                                LogCategory.AUTH,
                                "Updated plugin wizard completion status",
                                mapOf("completed" to completed),
                            )
                        } catch (e: kotlinx.serialization.SerializationException) {
                            logger.warn(
                                LogCategory.AUTH,
                                "user_data.json undecodable; persisting wizard status via pending marker",
                                error = e,
                            )
                            pendingWizardCompletedFile.atomicWriteText(completed.toString())
                        }
                    } else {
                        pendingWizardCompletedFile.atomicWriteText(completed.toString())
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error setting plugin wizard status", error = e)
                }
            }
        }
    }
}
