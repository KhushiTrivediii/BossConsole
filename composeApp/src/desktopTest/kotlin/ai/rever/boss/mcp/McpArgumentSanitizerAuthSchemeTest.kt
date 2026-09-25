package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An `Authorization: <scheme> <credential>` header must be masked whole.
 *
 * Before the auth-scheme alternative, `sensitiveAssignment`'s `[^\s&,;}]+` stopped at the first
 * space, so the value it captured was the scheme word itself and the credential survived as
 * `[REDACTED] <token>`. Sanitized arguments reach the approval dialog AND `McpOperationLedger`,
 * which appends them to disk, so a surviving credential is written out in plaintext.
 */
class McpArgumentSanitizerAuthSchemeTest {
    @Suppress("MaxLineLength")
    private fun command(command: String): String = assertNotNull(McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"])

    @Test
    fun `bearer credential in an Authorization header is masked, not just its label`() {
        val out = command("curl -H 'Authorization: Bearer abc123def456' https://api.internal")
        assertFalse(out.contains("abc123def456"), "credential survived sanitisation: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("https://api.internal"), out)
    }

    @Test
    fun `basic token and negotiate schemes are masked too`() {
        assertFalse(command("curl -H 'Authorization: Basic YWRtaW46aHVudGVyMg=='").contains("YWRtaW46aHVudGVyMg=="))
        assertFalse(command("curl -H 'Authorization: Token TokenSchemeSecret123'").contains("TokenSchemeSecret123"))
        assertFalse(command("curl -H 'Authorization: Negotiate YIIZk3YGKw'").contains("YIIZk3YGKw"))
        assertFalse(command("curl -H 'Authorization: Digest DigestSecret123'").contains("DigestSecret123"))
        assertFalse(command("curl -H 'Authorization: NTLM NtlmSecret123'").contains("NtlmSecret123"))
    }

    @Test
    fun `a standalone bearer header without a sensitive key still uses the bearer rule`() {
        // "X-Auth" is not in the sensitive-keyword set, so this must still be caught by [bearer].
        val out = command("curl -H 'X-Auth: Bearer standalone987'")
        assertFalse(out.contains("standalone987"), "standalone bearer survived: $out")
        assertTrue(out.contains("Bearer [REDACTED]"), "expected the bearer rule to fire: $out")
    }

    @Test
    fun `existing assignment redaction is unchanged`() {
        assertEquals("psql --[REDACTED]", command("psql --password=hunter2"))
        val githubToken = "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123"
        assertFalse(command("export GH=$githubToken").contains(githubToken))
        assertFalse(command("curl -d token=xoxb-2444-55667788-aBcDeFgHiJkLmNoP https://x").contains("xoxb-2444"))
        assertFalse(command("curl -H 'X: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc'").contains("eyJzdWIiOiIxIn0"))
    }

    @Test
    fun `ordinary sensitive assignments still use their original value boundary`() {
        assertEquals("note [REDACTED] settings remain", command("note secret: Basic settings remain"))
    }

    @Test
    fun `authorization label does not consume command text on the next line`() {
        val raw = "authorization:\nrm -rf /important/data"
        assertEquals(raw, command(raw))
    }

    @Test
    fun `sensitive assignment nested in authorization is fully masked`() {
        assertEquals("[REDACTED]", command("authorization: invalid token: abc123"))
        assertEquals("[REDACTED]", command("authorization: none password : hunter2"))
    }

    @Test
    fun `authorization value without a scheme is masked`() {
        assertEquals("curl -H '[REDACTED] https://x", command("curl -H 'Authorization: abc123' https://x"))
    }

    @Test
    fun `a compound scheme chain is masked whole`() {
        // The generic scheme group iterates once per scheme word before consuming the credential.
        val out = command("curl -H 'Authorization: Bearer Token abc123secret'")
        assertFalse(out.contains("abc123secret"), "compound scheme leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    @Test
    fun `a quoted credential is masked for both quoting styles`() {
        // Assert on each HALF, not the whole string: the bare class used to take `'abc` and leave
        // ` def'`, which a contains("abc def") check passes while the credential is still readable.
        val single = command("""curl -H "Authorization: Bearer 'abc def'" """)
        assertFalse(single.contains("abc"), "single-quoted credential leaked: $single")
        assertFalse(single.contains("def"), "single-quoted credential tail leaked: $single")
        assertTrue(single.contains("[REDACTED]"), single)

        val double = command("""curl -H 'Authorization: Bearer "abc def"' """)
        assertFalse(double.contains("abc"), "double-quoted credential leaked: $double")
        assertFalse(double.contains("def"), "double-quoted credential tail leaked: $double")
        assertTrue(double.contains("[REDACTED]"), double)
    }

    @Test
    fun `URI userinfo credentials are redacted with the shared helper`() {
        // Postgres URI authority: the whole userinfo goes (shared #640 convention), host stays
        // readable.
        val pg = McpArgumentSanitizer.sanitizeMessage("psql postgres://admin:hunter2@prod-db.example.invalid/app")
        assertEquals("psql postgres://[REDACTED]@prod-db.example.invalid/app", pg)

        // MongoDB URI authority password, percent-encoded @ included
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
    }

    @Test
    fun `curl user flag credentials are redacted in every flag spelling`() {
        // Space form: the password is masked whole (it may contain colons)
        val curl = McpArgumentSanitizer.sanitizeMessage("curl -u admin:hunter2 https://api.example.invalid/health")
        assertEquals("curl -u admin:[REDACTED] https://api.example.invalid/health", curl)

        val curlLong = McpArgumentSanitizer.sanitizeMessage("curl --user admin:pa:ss word")
        assertEquals("curl --user admin:[REDACTED] word", curlLong)

        // `=` form (long options take --opt=value)
        val curlEq = McpArgumentSanitizer.sanitizeMessage("curl --user=admin:hunter2 https://x")
        assertEquals("curl --user=admin:[REDACTED] https://x", curlEq)

        // Attached short form (-u takes an attached argument)
        val curlAttached = McpArgumentSanitizer.sanitizeMessage("curl -uadmin:hunter2 https://x")
        assertEquals("curl -uadmin:[REDACTED] https://x", curlAttached)

        // Email usernames (the ordinary SaaS basic-auth shape) are masked too, @ in password too
        val email = McpArgumentSanitizer.sanitizeMessage("curl -u alice@example.com:hunter2 https://x")
        assertEquals("curl -u alice@example.com:[REDACTED] https://x", email)

        val emailEq = McpArgumentSanitizer.sanitizeMessage("curl --user=alice@example.com:hunter2 https://x")
        assertEquals("curl --user=alice@example.com:[REDACTED] https://x", emailEq)

        val atInPassword = McpArgumentSanitizer.sanitizeMessage("curl -u alice@example.com:p@ss https://x")
        assertEquals("curl -u alice@example.com:[REDACTED] https://x", atInPassword)

        // A bare URL after the flag is not user:pass
        val curlUrl = McpArgumentSanitizer.sanitizeMessage("redis-cli -u http://cache.example.invalid:6379")
        assertEquals("redis-cli -u http://cache.example.invalid:6379", curlUrl)

        // An rsync remote spec (rsync -u is --update) is a file-copy destination, not a
        // credential: a single-slash path must stay readable for the operator.
        val rsync = McpArgumentSanitizer.sanitizeMessage("rsync -u host:/srv/data /tmp")
        assertEquals("rsync -u host:/srv/data /tmp", rsync)

        // A bare newline is NOT a separator (same [ \t] convention the authorization rule pins).
        // Known leak, kept on purpose: this pins that the rule cannot wander into an unrelated
        // next line, and the unmasked admin:hunter2 is written to the ledger in plaintext - the
        // price of not crossing newlines. The common multi-line curl shape (line continuation)
        // IS masked; see the continuation test below.
        val newline = McpArgumentSanitizer.sanitizeMessage("curl -u\nadmin:hunter2 https://x")
        assertEquals("curl -u\nadmin:hunter2 https://x", newline)

        // A quoted password with a space is masked whole: the bare [^\s]* used to stop at the
        // space and leave the tail readable (the same bug the quoted-credential test above pins
        // for the authorization rule).
        val quotedPass = McpArgumentSanitizer.sanitizeMessage("curl -u 'admin:hun ter' https://x")
        assertFalse(quotedPass.contains("hun"), "single-quoted password leaked: $quotedPass")
        assertFalse(quotedPass.contains("ter"), "single-quoted password tail leaked: $quotedPass")
        assertTrue(quotedPass.contains("[REDACTED]"), quotedPass)

        val quotedPassDouble = McpArgumentSanitizer.sanitizeMessage("curl -u \"admin:hun ter\" https://x")
        assertFalse(quotedPassDouble.contains("hun"), "double-quoted password leaked: $quotedPassDouble")
        assertFalse(quotedPassDouble.contains("ter"), "double-quoted password tail leaked: $quotedPassDouble")
        assertTrue(quotedPassDouble.contains("[REDACTED]"), quotedPassDouble)

        // --proxy-user: one flag, two spellings, both masked (the short -U form was already
        // matched by the case-insensitive -u branch; the long spelling needs its own entry).
        val proxyLong = McpArgumentSanitizer.sanitizeMessage("curl --proxy-user admin:hunter2 https://x")
        assertEquals("curl --proxy-user admin:[REDACTED] https://x", proxyLong)

        val proxyLongEq = McpArgumentSanitizer.sanitizeMessage("curl --proxy-user=admin:hunter2 https://x")
        assertEquals("curl --proxy-user=admin:[REDACTED] https://x", proxyLongEq)

        val proxyShort = McpArgumentSanitizer.sanitizeMessage("curl -U admin:hunter2 https://x")
        assertEquals("curl -U admin:[REDACTED] https://x", proxyShort)
    }

    @Test
    fun `a line continuation between the user flag and its credential is redacted too`() {
        // The ordinary multi-line curl shape: a trailing backslash + newline is an unambiguous
        // continuation, so the separator alternative may cross exactly that - and nothing else.
        val continued = McpArgumentSanitizer.sanitizeMessage("curl -u \\\n  admin:hunter2 https://x")
        assertEquals("curl -u \\\n  admin:[REDACTED] https://x", continued)

        val continuedAttached = McpArgumentSanitizer.sanitizeMessage("curl -u\\\nadmin:hunter2 https://x")
        assertEquals("curl -u\\\nadmin:[REDACTED] https://x", continuedAttached)

        val continuedCrlf = McpArgumentSanitizer.sanitizeMessage("curl -u \\\n  admin:hunter2 https://x")
        assertEquals("curl -u \\\n  admin:[REDACTED] https://x", continuedCrlf)

        // But a bare newline (no backslash) still does not cross - the rule cannot wander
        // into an unrelated next line.
        val bareNewline = McpArgumentSanitizer.sanitizeMessage("curl -u\nadmin:hunter2 https://x")
        assertEquals("curl -u\nadmin:hunter2 https://x", bareNewline)
    }

    @Test
    fun `AWS access key IDs are redacted`() {
        val aws = McpArgumentSanitizer.sanitizeMessage("aws configure set key AKIAIOSFODNN7EXAMPLE")
        assertEquals("aws configure set key [REDACTED]", aws)

        // Temporary credentials (ASIA) are masked too
        val asia = McpArgumentSanitizer.sanitizeMessage("aws sts assume-role token ASIAQ78Y75EXAMPLEKEY")
        assertEquals("aws sts assume-role token [REDACTED]", asia)

        // A run of MORE than 16 trailing chars is not a real key ID and is left alone
        // (fail-open on malformed tokens is documented in the pattern's KDoc)
        val long = McpArgumentSanitizer.sanitizeMessage("id AKIAABCDEFGHIJKLMNOPQ extra")
        assertEquals("id AKIAABCDEFGHIJKLMNOPQ extra", long)
    }

    @Test
    fun `PEM private key blocks are masked whole, including GPG armor`() {
        // A full PEM private key block is masked, not just its header
        val pemBlock =
            McpArgumentSanitizer.sanitizeMessage(
                "-----BEGIN RSA PRIVATE KEY-----MIIEowIBAAKCAQEA0body-----END RSA PRIVATE KEY-----",
            )
        assertEquals("[REDACTED]", pemBlock)

        // GPG armor: the trailing BLOCK in both markers used to defeat the rule entirely
        val pgp =
            McpArgumentSanitizer.sanitizeMessage(
                "-----BEGIN PGP PRIVATE KEY BLOCK-----\nMIIEvAIBADANBQ\n-----END PGP PRIVATE KEY BLOCK-----",
            )
        assertEquals("[REDACTED]", pgp)

        // A truncated fragment still masks the header to end of line (the over-masked
        // closing quote is safe: a fragment that cannot be re-parsed is still secret)
        val pemFragment = McpArgumentSanitizer.sanitizeMessage("echo '-----BEGIN RSA PRIVATE KEY-----MIIEowIBAAKCAQEA'")
        assertEquals("echo '[REDACTED]", pemFragment)

        // Public keys are not secret material and stay readable
        val pub =
            McpArgumentSanitizer.sanitizeMessage(
                "-----BEGIN PGP PUBLIC KEY BLOCK-----abc-----END PGP PUBLIC KEY BLOCK-----",
            )
        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----abc-----END PGP PUBLIC KEY BLOCK-----", pub)
    }
}
