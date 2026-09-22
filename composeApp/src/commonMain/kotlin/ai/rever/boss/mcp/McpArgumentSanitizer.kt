package ai.rever.boss.mcp

import ai.rever.boss.plugin.logging.LogSanitizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sanitizes MCP tool arguments before they reach an operator (the approval dialog) or
 * disk (the operation ledger).
 *
 * Deliberately narrower than [LogSanitizer.sanitizeMap]: that function treats any string
 * of 20 or more characters as secret-shaped ([LogSanitizer.looksLikeSecret]) and masks it
 * via [LogSanitizer.maskToken] - which is exactly wrong here, since a long file path, URL,
 * or shell command is both longer than 20 characters and the thing an operator most needs
 * to read before approving a mutating tool call. A value is only masked here when its key
 * names it as sensitive, or its shape is unambiguously a credential (JWT, GitHub token,
 * sk_/pk_ vendor key) - never on length alone.
 */
object McpArgumentSanitizer {
    private val sensitiveKeyWords =
        setOf("token", "password", "secret", "api_key", "apikey", "key", "credential")

    /** Same credential shapes [LogSanitizer] recognizes: a JWT, a GitHub token, or a vendor sk_/pk_ key. */
    private val credentialShapePattern =
        Regex(
            "(?<![A-Za-z0-9_.])(?:" +
                """eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""" +
                "|(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}" +
                "|(?:sk|pk)[-_][A-Za-z0-9_-]{8,}" +
                ")",
        )

    private val awsKeyPattern = Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""")

    /**
     * A PEM private key: the full BEGIN..END block when present, otherwise the header through the
     * end of the line (a truncated fragment is still the secret). The body, not the header, is
     * what makes this material dangerous, so the whole match is replaced.
     */
    private val pemPrivateKeyPattern =
        Regex(
            """(?s)-----BEGIN (?:[A-Z0-9-]+ )?PRIVATE KEY-----""" +
                """(?:.*?-----END (?:[A-Z0-9-]+ )?PRIVATE KEY-----|[^\n]*)""",
        )

    /**
     * `curl -u user:secret` / `--user user:secret` (space or `=` form). The credential follows a
     * flag, not a key name, so the key-name rules never see it. The username part may not contain
     * `/`, `:` or `@`, which - with the `(?!//)` guard - keeps a bare URL argument
     * (`-u http://host`) untouched; everything after the first colon is the password and is
     * masked whole (a password may itself contain colons).
     */
    private val basicAuthFlagPattern =
        Regex("""(?i)(?<![A-Za-z0-9_-])(-u|--user)\s+([^:/\s@]+):(?!//)([^\s]*)""")

    /** Parse only for audit/approval; malformed input must never reach those surfaces verbatim. */
    @Suppress("TooGenericExceptionCaught") // Invalid nested JSON must not enter the audit surface verbatim.
    fun parseArguments(raw: String): Map<String, Any?> =
        try {
            if (raw.length > 16_384) {
                mapOf("arguments" to "[OMITTED: too large]")
            } else {
                (Json.parseToJsonElement(raw) as? JsonObject)?.toMap()
                    ?: mapOf("arguments" to "[OMITTED: invalid JSON object]")
            }
        } catch (_: Exception) {
            mapOf("arguments" to "[OMITTED: invalid JSON]")
        }

    fun sanitize(args: Map<String, Any?>): Map<String, String> = sanitizeMap(args, 0)

    private fun sanitizeMap(
        args: Map<String, Any?>,
        depth: Int,
    ): Map<String, String> =
        args.mapValues { (key, value) ->
            if (sensitiveKeyWords.any { key.contains(it, ignoreCase = true) } || key.contains("auth", true)) {
                "[REDACTED]"
            } else {
                sanitizeValue(value, depth).take(4096)
            }
        }

    private fun sanitizeValue(
        value: Any?,
        depth: Int,
    ): String =
        if (depth >= 8) {
            "[OMITTED: too deeply nested]"
        } else {
            when (value) {
                is JsonObject -> {
                    sanitizeMap(value.toMap(), depth + 1).toString()
                }

                is JsonArray -> {
                    value.joinToString(prefix = "[", postfix = "]") { sanitizeValue(it, depth + 1) }
                }

                is JsonPrimitive -> {
                    sanitizeMessage(value.content)
                }

                is Map<*, *> -> {
                    val nested = value.entries.associate { it.key.toString() to it.value }
                    sanitizeMap(nested, depth + 1).toString()
                }

                is Iterable<*> -> {
                    value.joinToString(prefix = "[", postfix = "]") { sanitizeValue(it, depth + 1) }
                }

                else -> {
                    sanitizeMessage(value?.toString() ?: "null")
                }
            }
        }

    /** Authorization is special: consume generic scheme words before the credential value. */
    private val authorizationHeader =
        Regex(
            """(?i)authorization[ \t]*[:=][ \t]*""" +
                """(?:[A-Za-z][A-Za-z0-9._~+/-]*[ \t]+){0,3}(?:"[^"]*"|'[^']*'|[^\s&,;}]+)""",
        )

    private val sensitiveAssignment =
        Regex(
            """(?i)(?:password|token|secret|api[_-]?key|credential)""" +
                """\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s&,;}]+)""",
        )
    private val bearer = Regex("""(?i)Bearer\s+[^\s"',;}]+""")

    fun sanitizeMessage(text: String): String =
        text
            // URI authority userinfo is redacted by the shared #640 helper: the authority ends at
            // the first `/`, `?` or `#` and the LAST `@` is the delimiter, so a password
            // containing an `@` is removed whole (a local regex left its tail in the output).
            .let(LogSanitizer::redactUrlUserInfo)
            .replace(credentialShapePattern, "[REDACTED]")
            .replace(awsKeyPattern, "[REDACTED]")
            .replace(pemPrivateKeyPattern, "[REDACTED]")
            .replace(sensitiveAssignment, "[REDACTED]")
            .replace(authorizationHeader, "[REDACTED]")
            .replace(bearer, "Bearer [REDACTED]")
            .replace(basicAuthFlagPattern, "$1 $2:[REDACTED]")
}
