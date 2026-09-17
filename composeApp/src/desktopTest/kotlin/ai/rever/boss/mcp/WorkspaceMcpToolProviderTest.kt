package ai.rever.boss.mcp

import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalOpenEvent
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.flow.first
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

class WorkspaceMcpToolProviderTest {
    private val tempDirs = mutableListOf<File>()
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
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.terminalTabOpener = null
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
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
    fun `list_workspaces returns predefined templates and active state`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

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
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

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
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

            val args = """{"workspaceId":"nonexistent-workspace-id"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("not found"))
        }

    @Test
    fun `open_workspace with createIfAbsent creates new workspace when missing`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

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
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

            val badPath = File(workspaceDir, "does-not-exist.json").absolutePath
            val args = """{"workspacePath":"${badPath.replace('\\', '/')}"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("Workspace file not found"))
        }

    @Test
    fun `create_workspace creates disposable workspace with unique ID`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

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
    fun `open_terminal rejects invalid working directory`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

            val badDir = File(workspaceDir, "non_existent_folder_abc").absolutePath
            val args = """{"workingDirectory":"${badDir.replace('\\', '/')}"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("Working directory does not exist or is not a directory"))
        }

    @Test
    fun `open_terminal succeeds and emits TerminalOpenEvent`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

            var openedTabInfo: TerminalTabInfo? = null
            WorkspaceMcpToolProvider.terminalTabOpener = { _, tab ->
                openedTabInfo = tab
                true
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
            assertTrue(tabId.startsWith("terminal-"))
            assertEquals(tabId.removePrefix("terminal-"), terminalId)

            val opened = assertNotNull(openedTabInfo)
            assertEquals(tabId, opened.id)
            assertEquals("echo hello", opened.initialCommand)
        }

    @Test
    fun `close_workspace deletes disposable workspace`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(WorkspaceMcpToolProvider)

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
}
