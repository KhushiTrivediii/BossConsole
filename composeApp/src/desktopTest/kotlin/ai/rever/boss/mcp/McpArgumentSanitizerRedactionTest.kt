package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class McpArgumentSanitizerRedactionTest {
    @Test
    fun `authorization bearer header is fully redacted`() {
        val sanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'Authorization: Bearer abc123def456'")
        assertFalse(
            sanitized.contains("abc123def456"),
            "Bearer token must not leak in plaintext, got: $sanitized",
        )
        assertEquals("curl -H '[REDACTED]'", sanitized)
    }

    @Test
    fun `authorization basic and token headers are fully redacted`() {
        val basicSanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'Authorization: Basic abc123def456'")
        assertFalse(basicSanitized.contains("abc123def456"), "Basic token must be redacted")

        val tokenSanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'Authorization: Token abc123def456'")
        assertFalse(tokenSanitized.contains("abc123def456"), "Token key must be redacted")
    }

    @Test
    fun `standalone bearer token is redacted`() {
        val sanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'X-Header: Bearer abc123def456'")
        assertFalse(sanitized.contains("abc123def456"), "Standalone Bearer token must be redacted")
        assertEquals("curl -H 'X-Header: Bearer [REDACTED]'", sanitized)
    }

    @Test
    fun `uri credentials in userinfo are redacted`() {
        val postgres = McpArgumentSanitizer.sanitizeMessage("psql postgres://admin:hunter2@prod-db.internal/app")
        assertFalse(postgres.contains("hunter2"), "Postgres password must be redacted")
        assertEquals("psql postgres://admin:[REDACTED]@prod-db.internal/app", postgres)

        val mongodb =
            McpArgumentSanitizer.sanitizeMessage(
                "mongosh mongodb+srv://svc:S3cr3tP%40ss@cluster0.mongodb.net/db",
            )
        assertFalse(mongodb.contains("S3cr3tP%40ss"), "MongoDB password must be redacted")
        assertEquals("mongosh mongodb+srv://svc:[REDACTED]@cluster0.mongodb.net/db", mongodb)
    }
}
