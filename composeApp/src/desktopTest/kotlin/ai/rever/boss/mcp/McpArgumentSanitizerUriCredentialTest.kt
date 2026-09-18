package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for Issue #886: a credential inside a URI authority
 * (scheme://user:password@host) must be redacted before reaching the approval
 * dialog or the MCP operation ledger.
 */
class McpArgumentSanitizerUriCredentialTest {
    private fun command(cmd: String): String {
        return assertNotNull(McpArgumentSanitizer.sanitize(mapOf("command" to cmd))["command"])
    }

    // -- URI userinfo redaction -----------------------------------

    @Test
    fun `postgres URI password is redacted`() {
        val out = command("psql postgres://admin:***@prod-db.example.invalid/app")
        assertFalse(out.contains("hunter2"), "postgres password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), "expected redaction marker: $out")
        assertTrue(out.contains("prod-db.example.invalid"), "host should be preserved: $out")
        assertTrue(out.contains("/app"), "path should be preserved: $out")
    }

    @Test
    fun `mongodb URI password is redacted`() {
        val out = command("mongosh mongodb+srv://svc:***@cluster.example.invalid/db")
        assertFalse(out.contains("secretpass"), "mongodb password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("cluster.example.invalid"), "host should be preserved: $out")
    }

    @Test
    fun `redis URI password is redacted`() {
        val out = command("redis-cli -u redis://default:***@cache.example.invalid:6379")
        assertFalse(out.contains("redispass"), "redis password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("cache.example.invalid"), "host and port should be preserved: $out")
    }

    @Test
    fun `https URI with credentials is redacted`() {
        val out = command("git clone https://nitin:hunter2@git.example.invalid/org/private.git")
        assertFalse(out.contains("hunter2"), "https password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("git.example.invalid"), "host should be preserved: $out")
        assertTrue(out.contains("/org/private.git"), "path should be preserved: $out")
    }

    @Test
    fun `URI with at-sign in password is fully redacted`() {
        val out = command("psql postgres://user:***@ss@db.example.invalid/app")
        assertFalse(out.contains("p@ss"), "password with at-sign leaked: $out")
        assertFalse(out.contains("ss@"), "password tail leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("db.example.invalid"), "host should be preserved: $out")
    }

    @Test
    fun `URI without credentials is unchanged`() {
        val out = command("curl https://api.example.invalid/health")
        assertEquals("curl https://api.example.invalid/health", out)
    }

    @Test
    fun `multiple URIs in one command are all redacted`() {
        val out = command("psql postgres://u1:***@db1.internal/app && mongosh mongodb://u2:***@db2.internal/db")
        assertFalse(out.contains("p1"), "first password leaked: $out")
        assertFalse(out.contains("p2"), "second password leaked: $out")
        assertTrue(out.contains("db1.internal"), out)
        assertTrue(out.contains("db2.internal"), out)
    }

    @Test
    fun `URI credential inside a quoted argument is redacted`() {
        val out = command("psql \"postgres://admin:***@prod-db.internal/app\"")
        assertFalse(out.contains("hunter2"), "quoted URI password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    // -- curl -u / --user redaction -------------------------------

    @Test
    fun `curl dash u user colon pass is redacted`() {
        val out = command("curl -u admin:hunter2 https://api.example.invalid/health")
        assertFalse(out.contains("hunter2"), "curl -u password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("admin"), "username should be preserved: $out")
        assertTrue(out.contains("https://api.example.invalid/health"), "URL should be preserved: $out")
    }

    @Test
    fun `curl --user user colon pass is redacted`() {
        val out = command("curl --user admin:hunter2 https://api.example.invalid/health")
        assertFalse(out.contains("hunter2"), "curl --user password leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("admin"), "username should be preserved: $out")
    }

    @Test
    fun `curl dash u without colon is unchanged`() {
        val out = command("curl -u admin https://api.example.invalid/health")
        assertFalse(out.contains("[REDACTED]"), "should not redact without colon: $out")
    }

    // -- Non-regression: existing behaviour preserved -------------

    @Test
    fun `existing bearer header redaction still works`() {
        val out = command("curl -H 'Authorization: Bearer *** https://api.internal")
        assertFalse(out.contains("abc123def456"), "bearer token leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    @Test
    fun `existing password assignment redaction still works`() {
        val out = command("psql --password=hunter2")
        assertFalse(out.contains("hunter2"), "password assignment leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    @Test
    fun `existing JWT shape redaction still works`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.afdsafdsafds"
        val out = command("curl -H 'X: $jwt'")
        assertFalse(out.contains(jwt), "JWT leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    @Test
    fun `plain URL without credentials in command text is unchanged`() {
        val out = command("curl https://example.com/path?query=value")
        assertEquals("curl https://example.com/path?query=value", out)
    }
}
