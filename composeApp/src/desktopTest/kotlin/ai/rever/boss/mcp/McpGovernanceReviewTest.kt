package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpGovernanceReviewTest {
    @Test
    fun `approved execution cancellation differs from unanswered cancellation`() =
        runBlocking {
            val bus = McpApprovalBus()
            val ledger = McpOperationLedger()
            val entered = CompletableDeferred<Unit>()
            val core = McpToolRegistryCore(disabledFile = null, approvalBus = bus, ledger = ledger)
            core.registerProvider(
                object : McpToolProvider {
                    override val providerId = "p"

                    override fun tools() =
                        listOf(
                            McpToolDefinition(
                                name = "run_command",
                                description = "test",
                                handler =
                                    McpToolHandler {
                                        entered.complete(Unit)
                                        awaitCancellation()
                                    },
                            ),
                        )
                },
            )
            val call = async { core.invoke("run_command", "{}") }
            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            bus.approve(request.id)
            entered.await()
            call.cancelAndJoin()
            assertEquals(
                McpApprovalDisposition.CANCELLED_IN_FLIGHT,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `credential spans preserve surrounding command and depth is bounded`() {
        val command = "curl -H 'Bearer ghp_123456789abcdef' https://example.test/path"
        val safe = McpArgumentSanitizer.sanitizeMessage(command)
        assertFalse(safe.contains("ghp_123456789abcdef"))
        assertTrue(safe.contains("curl"))
        assertTrue(safe.contains("https://example.test/path"))
        var nested: Any? = "sentinel"
        repeat(1000) { nested = listOf(nested) }
        assertTrue(McpArgumentSanitizer.sanitize(mapOf("data" to nested)).toString().contains("too deeply nested"))
    }

    @Test
    fun `URI userinfo credentials, curl user flags, AWS keys, and PEM keys are redacted`() {
        // Postgres URI authority: the whole userinfo goes (shared #640 convention), host stays
        // readable.
        val pg = McpArgumentSanitizer.sanitizeMessage("psql postgres://admin:hunter2@prod-db.example.invalid/app")
        assertEquals("psql postgres://[REDACTED]@prod-db.example.invalid/app", pg)

        // MongoDB URI authority password
        val mongo =
            McpArgumentSanitizer.sanitizeMessage(
                "mongosh mongodb+srv://svc:S3cr3tP%40ss@cluster.example.invalid/db",
            )
        assertEquals("mongosh mongodb+srv://[REDACTED]@cluster.example.invalid/db", mongo)

        // Redis URI authority password, with port
        val redis =
            McpArgumentSanitizer.sanitizeMessage(
                "redis-cli -u redis://default:r3disPass@cache.example.invalid:6379",
            )
        assertEquals("redis-cli -u redis://[REDACTED]@cache.example.invalid:6379", redis)

        // Plain Redis URI without a credential must be left untouched: a naive pattern reads
        // `cache` as a user and `6379` as a password.
        val redisPlain =
            McpArgumentSanitizer.sanitizeMessage("redis-cli -u redis://cache.example.invalid:6379")
        assertEquals("redis-cli -u redis://cache.example.invalid:6379", redisPlain)

        // Git HTTPS userinfo password
        val git =
            McpArgumentSanitizer.sanitizeMessage(
                "git clone https://user:hunter2@git.example.invalid/org/private.git",
            )
        assertEquals("git clone https://[REDACTED]@git.example.invalid/org/private.git", git)

        // A password containing an at sign is removed whole: the LAST @ in the authority is the
        // delimiter, so stopping at the first @ would leave `ss@host` behind.
        val atInPassword =
            McpArgumentSanitizer.sanitizeMessage("psql postgres://user:p@ss@db.example.invalid/app")
        assertEquals("psql postgres://[REDACTED]@db.example.invalid/app", atInPassword)

        // A colon and @ inside a path is not userinfo
        val pathAt =
            McpArgumentSanitizer.sanitizeMessage("curl https://api.example.invalid/a:b@c/d")
        assertEquals("curl https://api.example.invalid/a:b@c/d", pathAt)

        // curl -u credentials: the password is masked whole (it may contain colons)
        val curl = McpArgumentSanitizer.sanitizeMessage("curl -u admin:hunter2 https://api.example.invalid/health")
        assertEquals("curl -u admin:[REDACTED] https://api.example.invalid/health", curl)

        val curlLongPassword = McpArgumentSanitizer.sanitizeMessage("curl --user admin:pa:ss word")
        assertEquals("curl --user admin:[REDACTED] word", curlLongPassword)

        // A bare URL after the flag is not user:pass
        val curlUrl = McpArgumentSanitizer.sanitizeMessage("redis-cli -u http://cache.example.invalid:6379")
        assertEquals("redis-cli -u http://cache.example.invalid:6379", curlUrl)

        // AWS Access Key ID
        val aws = McpArgumentSanitizer.sanitizeMessage("aws configure set key AKIAIOSFODNN7EXAMPLE")
        assertEquals("aws configure set key [REDACTED]", aws)

        // A full PEM private key block is masked, not just its header
        val pemBlock =
            McpArgumentSanitizer.sanitizeMessage(
                "-----BEGIN RSA PRIVATE KEY-----MIIEowIBAAKCAQEA0body-----END RSA PRIVATE KEY-----",
            )
        assertEquals("[REDACTED]", pemBlock)

        // A truncated fragment still masks the header to end of line (the over-masked
        // closing quote is safe: a fragment that cannot be re-parsed is still secret)
        val pemFragment = McpArgumentSanitizer.sanitizeMessage("echo '-----BEGIN RSA PRIVATE KEY-----MIIEowIBAAKCAQEA'")
        assertEquals("echo '[REDACTED]", pemFragment)
    }

    @Test
    fun `fault notifier never runs during construction or breaks enforcement`() {
        val directory = Files.createTempDirectory("mcp-policy-review").toFile()
        try {
            val file = directory.resolve("policy.json")
            file.writeText("invalid")
            var notified = false
            val engine =
                McpPolicyEngine(file) {
                    notified = true
                    error("UI unavailable")
                }
            assertFalse(notified)
            assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
            file.delete()
            file.mkdir()
            file.resolve("child").writeText("blocks replacement")
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            assertTrue(notified)
            assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `one window owns prompt and teardown releases caller`() =
        runBlocking {
            val bus = McpApprovalBus()
            val shown = mutableListOf<McpApprovalRequest>()
            val firstWindow = launch { bus.consumeApprovals { if (it != null) shown.add(it) } }
            val secondWindow = launch { bus.consumeApprovals { if (it != null) shown.add(it) } }
            val call = async { bus.requestApproval("run_command", "p", emptyMap()) }
            bus.pendingList.first { it.isNotEmpty() }
            yield()
            assertEquals(1, shown.size)
            firstWindow.cancelAndJoin()
            secondWindow.cancelAndJoin()
            assertTrue(call.await() is McpApprovalDecision.Denied)
            assertTrue(bus.pendingList.value.isEmpty())
        }
}
