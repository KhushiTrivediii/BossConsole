package ai.rever.boss.mcp

import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalOpenEvent
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class WorkspaceMcpToolProviderTest {
    private val tempDirs = mutableListOf<File>()
    private val createdSplitViewStates = mutableListOf<SplitViewState>()
    private lateinit var workspaceDir: File
    private lateinit var fileManager: WorkspaceFileManager

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("workspace-mcp-test").toFile()
        tempDirs.add(dir)
        workspaceDir = dir
        fileManager = WorkspaceFileManager(directoryOverride = dir.absolutePath)
        WorkspaceMcpToolProvider.fileManagerProvider = { fileManager }
        WorkspaceMcpToolProvider.windowCreator = { "test-window-window-1" }
        WorkspaceMcpToolProvider.splitViewStateResolver = { null }
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 50L
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 5000L
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        createdSplitViewStates.forEach { it.dispose() }
        createdSplitViewStates.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun createTestCore(): McpToolRegistryCore {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine)
        core.registerProvider(WorkspaceMcpToolProvider)
        return core
    }

    @Test
    fun `tools exposes workspace and terminal lifecycle operations and aliases`() {
        val tools = WorkspaceMcpToolProvider.tools().map { it.name }.toSet()
        assertTrue(tools.contains("list_workspaces"))
        assertTrue(tools.contains("workspace_list"))
        assertTrue(tools.contains("open_workspace"))
        assertTrue(tools.contains("workspace_open"))
        assertTrue(tools.contains("create_workspace"))
        assertTrue(tools.contains("workspace_create"))
        assertTrue(tools.contains("open_terminal"))
        assertTrue(tools.contains("terminal_open"))
        assertTrue(tools.contains("close_workspace"))
        assertTrue(tools.contains("workspace_close"))
    }

    @Test
    fun `registered in McpToolRegistryImpl by default`() {
        val registeredNames =
            McpToolRegistryImpl.allTools.value
                .map { it.definition.name }
                .toSet()
        assertTrue(registeredNames.contains("open_workspace"))
        assertTrue(registeredNames.contains("open_terminal"))
        assertTrue(registeredNames.contains("list_workspaces"))
        assertTrue(registeredNames.contains("create_workspace"))
    }

    @Test
    fun `mutating tool catalog registers all workspace and terminal mutating operations and aliases`() {
        val mutatingTools =
            listOf(
                "open_workspace",
                "workspace_open",
                "create_workspace",
                "workspace_create",
                "open_terminal",
                "terminal_open",
                "close_workspace",
                "workspace_close",
            )
        for (tool in mutatingTools) {
            assertTrue(
                McpMutatingToolCatalog.isMutating(tool),
                "Expected $tool to be registered as mutating",
            )
        }
        assertFalse(
            McpMutatingToolCatalog.isMutating("list_workspaces"),
            "list_workspaces should not be mutating",
        )
        assertFalse(
            McpMutatingToolCatalog.isMutating("workspace_list"),
            "workspace_list should not be mutating",
        )
    }

    @Test
    fun `list_workspaces returns predefined templates and active state`() =
        runBlocking {
            val core = createTestCore()

            val result = core.invoke("list_workspaces", "{}")
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("test-window-window-1", json["activeWindowId"]?.jsonPrimitive?.content)

            val workspaces = json["workspaces"]?.jsonArray
            assertNotNull(workspaces)
            assertTrue(workspaces.isNotEmpty(), "Predefined workspaces must be listed")

            val templateIds = workspaces.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(templateIds.contains(PredefinedWorkspaces.DUAL_TERMINAL_ID))
            assertTrue(templateIds.contains(PredefinedWorkspaces.BROWSER_ONLY_ID))
        }

    @Test
    fun `open_workspace opens predefined workspace from cold start`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}"""
            val result = core.invoke("open_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals(PredefinedWorkspaces.DUAL_TERMINAL_ID, json["workspaceId"]?.jsonPrimitive?.content)
            assertEquals("Dual Terminal", json["workspaceName"]?.jsonPrimitive?.content)
            assertEquals("test-window-window-1", json["windowId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `open_workspace fails with clear error when workspace is not found`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"workspaceId":"nonexistent-workspace-id"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("not found"))
        }

    @Test
    fun `open_workspace with createIfAbsent creates new workspace when missing`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"workspaceId":"custom-auto-ws","name":"Auto Created","createIfAbsent":true}"""
            val result = core.invoke("open_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("custom-auto-ws", json["workspaceId"]?.jsonPrimitive?.content)
            assertEquals("Auto Created", json["workspaceName"]?.jsonPrimitive?.content)

            // Saved to disk
            val loaded = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("custom-auto-ws"))
            assertNotNull(loaded)
            assertEquals("Auto Created", loaded.name)
        }

    @Test
    fun `open_workspace with missing file returns clear error`() =
        runBlocking {
            val core = createTestCore()

            val badPath = File(workspaceDir, "does-not-exist.json").absolutePath
            val args = """{"workspacePath":"${badPath.replace('\\', '/')}"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("Workspace file not found"))
        }

    @Test
    fun `create_workspace creates disposable workspace with unique ID`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"isDisposable":true,"projectPath":"${workspaceDir.absolutePath.replace('\\', '/')}"}"""
            val result = core.invoke("create_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertTrue(json["isDisposable"]?.jsonPrimitive?.booleanOrNull == true)

            val wsId = json["workspaceId"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(wsId.startsWith("workspace-disposable-"), "ID must have disposable prefix: $wsId")

            // And file is created
            val loaded = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId))
            assertNotNull(loaded)
        }

    @Test
    fun `create_workspace persists workspace layout without activating window or applying layout`() =
        runBlocking {
            val core = createTestCore()

            var windowActivated = false
            WorkspaceMcpToolProvider.splitViewStateResolver = {
                windowActivated = true
                null
            }

            val validPath = workspaceDir.absolutePath.replace('\\', '/')
            val args = """{"name":"Decoupled Workspace","projectPath":"$validPath"}"""
            val result = core.invoke("create_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("Decoupled Workspace", json["workspaceName"]?.jsonPrimitive?.content)
            assertNotNull(json["filePath"]?.jsonPrimitive?.content)

            val wsId = json["workspaceId"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(wsId.isNotBlank())

            // Persisted on disk
            val loaded = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId))
            assertNotNull(loaded)
            assertEquals("Decoupled Workspace", loaded.name)

            // SplitViewStateResolver was not invoked (no activation)
            assertFalse(windowActivated, "create_workspace must not activate window")
        }

    @Test
    fun `open_terminal rejects invalid working directory`() =
        runBlocking {
            val core = createTestCore()

            val badDir = File(workspaceDir, "non_existent_folder_abc").absolutePath
            val args = """{"workingDirectory":"${badDir.replace('\\', '/')}"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("Working directory does not exist or is not a directory"))
        }

    @Test
    fun `open_terminal succeeds and emits TerminalOpenEvent with authoritative tab id`() =
        runBlocking {
            val core = createTestCore()

            var openedCmd: String? = null
            var openedCwd: String? = null
            val expectedTab =
                TerminalTabInfo(
                    id = "terminal-authoritative-999",
                    title = "Terminal",
                    workingDirectory = workspaceDir.absolutePath.replace('\\', '/'),
                    initialCommand = "echo hello",
                )
            WorkspaceMcpToolProvider.terminalTabOpener = { _, cmd, cwd ->
                openedCmd = cmd
                openedCwd = cwd
                expectedTab
            }

            val validDir = workspaceDir.absolutePath.replace('\\', '/')
            val args = """{"workingDirectory":"$validDir","command":"echo hello"}"""
            val result = core.invoke("open_terminal", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("test-window-window-1", json["windowId"]?.jsonPrimitive?.content)
            assertEquals("echo hello", json["command"]?.jsonPrimitive?.content)

            val tabId = json["tabId"]?.jsonPrimitive?.content.orEmpty()
            val terminalId = json["terminalId"]?.jsonPrimitive?.content.orEmpty()
            assertEquals("terminal-authoritative-999", tabId)
            assertEquals("authoritative-999", terminalId)

            assertEquals("echo hello", openedCmd)
            assertEquals(validDir, openedCwd)
        }

    @Test
    fun `resolveTargetWindow refuses targeting when multiple windows are open and windowId omitted`() =
        runBlocking {
            val tabReg1 = TabRegistry()
            val tabReg2 = TabRegistry()
            val state1 = SplitViewState(tabReg1, "window-multi-1")
            val state2 = SplitViewState(tabReg2, "window-multi-2")
            createdSplitViewStates.add(state1)
            createdSplitViewStates.add(state2)

            SplitViewStateRegistry.register("window-multi-1", state1)
            SplitViewStateRegistry.register("window-multi-2", state2)

            val core = createTestCore()

            // When windowId is omitted with multiple windows open, open_workspace should fail
            val args = """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError, "Should fail when windowId is omitted with multiple windows open")
            assertTrue(
                result.text.contains("Multiple windows are open"),
                "Expected error message regarding multiple active windows: ${result.text}",
            )

            // With explicit valid windowId, it succeeds
            val explicitArgs =
                """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}","windowId":"window-multi-1"}"""
            val explicitResult = core.invoke("open_workspace", explicitArgs)
            assertFalse(explicitResult.isError, "Expected success with explicit windowId: ${explicitResult.text}")
        }

    @Test
    fun `awaitSplitViewState waits for window registration on cold start`() =
        runBlocking {
            val tabReg = TabRegistry()
            val state = SplitViewState(tabReg, "window-async-ready")
            createdSplitViewStates.add(state)

            // Register asynchronously after 20ms
            launch {
                delay(20L)
                SplitViewStateRegistry.register("window-async-ready", state)
            }

            val resolved = WorkspaceMcpToolProvider.awaitSplitViewState("window-async-ready", timeoutMillis = 500L)
            assertNotNull(resolved, "Should successfully resolve window state once registered")
        }

    @Test
    fun `awaitSplitViewState returns null when registration times out`() =
        runBlocking {
            val resolved = WorkspaceMcpToolProvider.awaitSplitViewState("window-nonexistent", timeoutMillis = 50L)
            assertTrue(resolved == null, "Should return null if window state never registers within timeout")
        }

    @Test
    fun `close_workspace deletes disposable workspace`() =
        runBlocking {
            val core = createTestCore()

            // Create disposable
            val createResult = core.invoke("create_workspace", """{"isDisposable":true}""")
            val json = Json.parseToJsonElement(createResult.text).jsonObject
            val wsId = json["workspaceId"]!!.jsonPrimitive.content

            assertNotNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId)))

            // Close disposable
            val closeResult = core.invoke("close_workspace", """{"workspaceId":"$wsId"}""")
            assertFalse(closeResult.isError)

            // Verify file was cleaned up
            val loadedAfterClose = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId))
            assertTrue(loadedAfterClose == null)
        }

    @Test
    fun `open_terminal rejects command with newlines or control characters`() =
        runBlocking {
            val core = createTestCore()
            val args = """{"command":"echo hello\nrm -rf /"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("security check failed"))
        }

    @Test
    fun `open_terminal rejects dangerous working directory paths`() =
        runBlocking {
            val core = createTestCore()
            val args = """{"workingDirectory":"/tmp/../etc/passwd"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("security check failed"))
        }

    @Test
    fun `open_workspace rejects dangerous project or workspace paths`() =
        runBlocking {
            val core = createTestCore()
            val badProjectArgs = """{"workspaceId":"test-ws","projectPath":"/tmp;rm -rf /"}"""
            val projectResult = core.invoke("open_workspace", badProjectArgs)
            assertTrue(projectResult.isError)
            assertTrue(projectResult.text.contains("security check failed"))

            val badFileArgs = """{"workspacePath":"/tmp/../etc/shadow"}"""
            val fileResult = core.invoke("open_workspace", badFileArgs)
            assertTrue(fileResult.isError)
            assertTrue(fileResult.text.contains("security check failed"))
        }
}
