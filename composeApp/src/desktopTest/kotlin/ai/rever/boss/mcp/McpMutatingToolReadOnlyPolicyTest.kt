package ai.rever.boss.mcp

import ai.rever.boss.components.dialogs.McpToolIdentity
import ai.rever.boss.components.dialogs.isViewTool
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpMutatingToolReadOnlyPolicyTest {
    @Test
    fun `tool with readOnly false and benign name is classified as mutating`() {
        assertTrue(
            McpMutatingToolCatalog.isMutating("data_fetch", readOnly = false),
            "A tool declaring readOnly = false must be classified as mutating regardless of name",
        )
    }

    @Test
    fun `tool with readOnly true and mutating suffix is classified as mutating`() {
        assertTrue(
            McpMutatingToolCatalog.isMutating("data_delete", readOnly = true),
            "A tool with a mutating suffix must remain mutating even if readOnly = true (name backstop)",
        )
    }

    @Test
    fun `tool with readOnly true and benign name is classified as read-only`() {
        assertFalse(
            McpMutatingToolCatalog.isMutating("data_fetch", readOnly = true),
            "A tool with readOnly = true and benign name must be classified as read-only",
        )
    }

    @Test
    fun `resolveAction uses defaultMutatingAction when readOnly is false`() {
        val config =
            McpToolPolicyConfig(
                defaultMutatingAction = McpPolicyAction.ASK,
                defaultReadOnlyAction = McpPolicyAction.ALLOW,
            )
        assertEquals(
            McpPolicyAction.ASK,
            McpMutatingToolCatalog.resolveAction("data_fetch", config, readOnly = false),
        )
    }

    @Test
    fun `policyEngine policyFor respects readOnly declaration`() {
        val engine = McpPolicyEngine()
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("data_fetch", readOnly = false),
            "policyFor must resolve defaultMutatingAction (ASK) when readOnly = false",
        )
    }

    @Test
    fun `isViewTool returns false when readOnly is false`() {
        val identity =
            McpToolIdentity(
                toolName = "data_fetch",
                providerId = "test-plugin",
                expectedRevocation = 0L,
                description = "Fetch data",
                readOnly = false,
            )
        assertFalse(
            identity.isViewTool(),
            "isViewTool must return false for a tool declaring readOnly = false",
        )
    }
}
