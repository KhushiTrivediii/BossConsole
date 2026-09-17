package ai.rever.boss.mcp

import ai.rever.boss.cli.CLISecurityValidator
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.WorkspaceEventBus
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.random.Random

/**
 * Host MCP tool provider exposing workspace and terminal lifecycle operations.
 *
 * Enables AI agents and automation clients to bootstrap workspaces and terminals
 * from a cold start (zero active workspaces or terminals) without manual UI actions.
 *
 * Tools exposed:
 * - list_workspaces / workspace_list
 * - open_workspace / workspace_open
 * - create_workspace / workspace_create
 * - open_terminal / terminal_open
 * - close_workspace / workspace_close
 */
@Suppress("TooManyFunctions")
object WorkspaceMcpToolProvider : McpToolProvider {
    private val logger = BossLogger.forComponent("WorkspaceMcpToolProvider")

    override val providerId: String = "boss-workspace"

    /** Test hook / platform hook to create a window when no windows exist. */
    internal var windowCreator: (() -> String)? = null

    /** Test hook to override workspace file manager. */
    internal var fileManagerProvider: (() -> WorkspaceFileManager)? = null

    /** Test hook to resolve split view state for a window. */
    internal var splitViewStateResolver: ((String) -> SplitViewState?)? = null

    /** Test hook / UI hook to directly open a terminal tab and return authoritative tab info. */
    internal var terminalTabOpener: ((windowId: String, command: String?, cwd: String?) -> TerminalTabInfo?)? = null

    /** Timeout in milliseconds when awaiting compose readiness on cold start. */
    internal var splitViewWaitTimeoutMs: Long = 5000L

    private fun getFileManager(): WorkspaceFileManager = fileManagerProvider?.invoke() ?: WorkspaceFileManager()

    @Suppress("ReturnCount")
    internal suspend fun awaitSplitViewState(
        windowId: String,
        timeoutMillis: Long = splitViewWaitTimeoutMs,
    ): SplitViewState? {
        splitViewStateResolver?.invoke(windowId)?.let { return it }
        SplitViewStateRegistry.getState(windowId)?.let { return it }
        return withTimeoutOrNull(timeoutMillis) {
            SplitViewStateRegistry.states
                .filter { it.containsKey(windowId) }
                .first()[windowId]
        }
    }

    sealed class TargetWindowResolution {
        data class Success(
            val windowId: String,
            val isColdStart: Boolean = false,
        ) : TargetWindowResolution()

        data class Failure(
            val errorMessage: String,
        ) : TargetWindowResolution()
    }

    @Suppress("ReturnCount")
    internal fun resolveTargetWindow(requestedWindowId: String?): TargetWindowResolution {
        // 1. Explicit windowId
        if (!requestedWindowId.isNullOrBlank()) {
            val isRegistered =
                splitViewStateResolver?.invoke(requestedWindowId) != null ||
                    SplitViewStateRegistry.isRegistered(requestedWindowId)
            return if (isRegistered) {
                TargetWindowResolution.Success(requestedWindowId)
            } else {
                TargetWindowResolution.Failure(
                    "Target window '$requestedWindowId' is not registered or has been closed",
                )
            }
        }

        // 2. Check registered window states
        val registeredStates = SplitViewStateRegistry.getAllStates()
        if (registeredStates.isEmpty()) {
            // True cold start: zero windows exist. Window creation is permitted.
            val creator =
                windowCreator
                    ?: return TargetWindowResolution.Failure(
                        "No active windows exist and no window creator is configured",
                    )
            val newId = creator.invoke()
            return TargetWindowResolution.Success(newId, isColdStart = true)
        }

        if (registeredStates.size == 1) {
            return TargetWindowResolution.Success(registeredStates.keys.first())
        }

        val openIds = registeredStates.keys.joinToString(", ")
        return TargetWindowResolution.Failure(
            "Multiple windows are open ($openIds). Explicit 'windowId' is required to prevent focus interference.",
        )
    }

    internal fun resolveTargetWindowId(requestedWindowId: String?): String? =
        (resolveTargetWindow(requestedWindowId) as? TargetWindowResolution.Success)?.windowId

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListWorkspacesTool("list_workspaces"),
            createListWorkspacesTool("workspace_list"),
            createOpenWorkspaceTool("open_workspace"),
            createOpenWorkspaceTool("workspace_open"),
            createCreateWorkspaceTool("create_workspace"),
            createCreateWorkspaceTool("workspace_create"),
            createOpenTerminalTool("open_terminal"),
            createOpenTerminalTool("terminal_open"),
            createCloseWorkspaceTool("close_workspace"),
            createCloseWorkspaceTool("workspace_close"),
        )

    private fun createListWorkspacesTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "List all existing workspaces, their project paths, and whether they are active or running.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "windowId": { "type": "string", "description": "Optional window ID to query active status against" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleListWorkspaces(args) },
        )

    private fun createOpenWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Open an existing workspace or create one if absent, targeting a window.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "workspaceId": { "type": "string", "description": "ID of the workspace to open" },
                        "workspacePath": { "type": "string", "description": "Path to workspace JSON file" },
                        "name": { "type": "string", "description": "Name if creating workspace" },
                        "projectPath": { "type": "string", "description": "Project root directory" },
                        "windowId": { "type": "string", "description": "Target window ID" },
                        "createIfAbsent": { "type": "boolean", "description": "Create workspace if not found" },
                        "openTerminal": { "type": "boolean", "description": "Automatically open a terminal tab" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleOpenWorkspace(args) },
        )

    private fun createCreateWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Create and persist a new workspace configuration (optionally disposable) without activating it.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "name": { "type": "string", "description": "Name for the workspace" },
                        "projectPath": { "type": "string", "description": "Project root directory" },
                        "isDisposable": {
                            "type": "boolean",
                            "description": "If true, creates a unique disposable workspace"
                        },
                        "openTerminal": {
                            "type": "boolean",
                            "description": "Configure an initial terminal tab in layout"
                        }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleCreateWorkspace(args) },
        )

    private fun createOpenTerminalTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Open a terminal tab in a workspace/window without requiring an existing terminal tab.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "windowId": { "type": "string", "description": "Target window ID" },
                        "workspaceId": { "type": "string", "description": "Target workspace ID" },
                        "workingDirectory": { "type": "string", "description": "Working directory for the terminal" },
                        "command": { "type": "string", "description": "Initial command to run in the terminal" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleOpenTerminal(args) },
        )

    private fun createCloseWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Close a workspace or release its layout in the target window.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "workspaceId": { "type": "string", "description": "ID of workspace to close" },
                        "windowId": { "type": "string", "description": "Target window ID" }
                    },
                    "required": ["workspaceId"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleCloseWorkspace(args) },
        )

    // =========================================================================
    // Handlers
    // =========================================================================

    @Suppress("LongMethod")
    private suspend fun handleListWorkspaces(args: McpToolArgs): McpToolResult {
        val windowId = args.string("windowId")
        val targetWindowId =
            if (!windowId.isNullOrBlank()) {
                windowId
            } else {
                val registered = SplitViewStateRegistry.getAllStates()
                if (registered.size == 1) {
                    registered.keys.first()
                } else if (registered.isEmpty()) {
                    windowCreator?.invoke()
                } else {
                    null
                }
            }
        val splitViewState =
            targetWindowId?.let {
                splitViewStateResolver?.invoke(it) ?: SplitViewStateRegistry.getState(it)
            }
        val activeWorkspaceId = splitViewState?.currentWorkspaceId

        val allWorkspaces = mutableMapOf<String, LayoutWorkspace>()

        // 1. Predefined templates
        PredefinedWorkspaces.allWorkspaces.forEach { ws ->
            allWorkspaces[ws.id] = ws
        }

        // 2. Saved workspaces on disk
        val fileManager = getFileManager()
        try {
            val files = fileManager.listWorkspaces()
            for (fileInfo in files) {
                val loaded = fileManager.loadWorkspace(fileInfo.fileName)
                if (loaded != null) {
                    allWorkspaces[loaded.id] = loaded
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.WORKSPACE, "Error listing workspace files", error = e)
        }

        // 3. Live workspaces across windows
        val allStates = SplitViewStateRegistry.getAllStates()
        val runningWorkspaceIds = allStates.values.mapNotNull { it.currentWorkspaceId }.toSet()

        val jsonArray =
            buildJsonArray {
                allWorkspaces.values.forEach { ws ->
                    val isActive = ws.id == activeWorkspaceId
                    val isRunning = runningWorkspaceIds.contains(ws.id)
                    val isTemplate = ws.id in PredefinedWorkspaces.allIds

                    add(
                        buildJsonObject {
                            put("id", ws.id)
                            put("name", ws.name)
                            if (ws.projectPath != null) {
                                put("projectPath", ws.projectPath)
                            }
                            put("description", ws.description)
                            put("isActive", isActive)
                            put("isRunning", isRunning)
                            put("isTemplate", isTemplate)
                        },
                    )
                }
            }

        val response =
            buildJsonObject {
                put("success", true)
                put("activeWindowId", targetWindowId)
                put("activeWorkspaceId", activeWorkspaceId)
                put("workspaces", jsonArray)
            }

        return McpToolResult(response.toString())
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun handleOpenWorkspace(args: McpToolArgs): McpToolResult {
        val workspaceId = args.string("workspaceId")
        val workspacePath = args.string("workspacePath")
        val name = args.string("name")
        val projectPath = args.string("projectPath")
        val requestedWindowId = args.string("windowId")
        val createIfAbsent = args.boolean("createIfAbsent") ?: false
        val openTerminal = args.boolean("openTerminal") ?: false

        if (!workspacePath.isNullOrBlank() && !CLISecurityValidator.isValidPath(workspacePath)) {
            return McpToolResult("Invalid workspace path (security check failed)", isError = true)
        }
        if (!projectPath.isNullOrBlank() && !CLISecurityValidator.isValidPath(projectPath)) {
            return McpToolResult("Invalid project path (security check failed)", isError = true)
        }

        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> targetResolution.windowId
                is TargetWindowResolution.Failure -> return McpToolResult(targetResolution.errorMessage, isError = true)
            }

        val splitViewState = awaitSplitViewState(targetWindowId)

        // Locate or create workspace
        var workspace: LayoutWorkspace? = null

        if (!workspacePath.isNullOrBlank()) {
            val file = File(workspacePath)
            if (file.exists() && file.canRead()) {
                workspace = runCatching { WorkspaceSerializer.deserialize(file.readText()) }.getOrNull()
            } else if (!createIfAbsent) {
                return McpToolResult("Workspace file not found: $workspacePath", isError = true)
            }
        }

        if (workspace == null && !workspaceId.isNullOrBlank()) {
            // Check predefined templates
            workspace = PredefinedWorkspaces.allWorkspaces.firstOrNull { it.id == workspaceId }

            // Check saved workspaces
            if (workspace == null) {
                val fileManager = getFileManager()
                val fileName =
                    if (workspaceId.endsWith(".json")) {
                        workspaceId
                    } else {
                        WorkspaceFileManagerCommon.fileNameForId(workspaceId)
                    }
                workspace = fileManager.loadWorkspace(fileName) ?: fileManager.loadWorkspace(workspaceId)
            }
        }

        if (workspace == null) {
            if (createIfAbsent || !name.isNullOrBlank()) {
                val newId = workspaceId?.takeIf { it.isNotBlank() } ?: LayoutWorkspace.generateId()
                val wsName = name?.takeIf { it.isNotBlank() } ?: "Workspace $newId"
                val rootPath = projectPath ?: DefaultWorkingDirectory.nominalPath()
                workspace = createDefaultWorkspace(newId, wsName, rootPath, openTerminal = openTerminal)
                // Persist
                getFileManager().saveWorkspace(workspace)
            } else {
                return McpToolResult(
                    "Workspace '$workspaceId' not found. Specify createIfAbsent=true to create it.",
                    isError = true,
                )
            }
        }

        // Idempotency: Reopening an existing workspace does not duplicate it or disturb unrelated windows
        if (splitViewState != null && splitViewState.currentWorkspaceId == workspace.id) {
            logger.debug(
                LogCategory.WORKSPACE,
                "Workspace already active in target window",
                mapOf("workspaceId" to workspace.id, "windowId" to targetWindowId),
            )
            return McpToolResult(
                buildJsonObject {
                    put("success", true)
                    put("workspaceId", workspace.id)
                    put("workspaceName", workspace.name)
                    put("projectPath", workspace.projectPath)
                    put("windowId", targetWindowId)
                    put("alreadyActive", true)
                }.toString(),
            )
        }

        // Apply workspace
        if (splitViewState != null) {
            withContext(Dispatchers.Main) {
                applyWorkspace(
                    workspace = workspace,
                    splitViewState = splitViewState,
                    windowProjectState = WindowProjectStateRegistry.get(targetWindowId),
                    restoreProject = true,
                )
            }
        }

        // Broadcast load event for external observers
        val savedPath = getFileManager().getWorkspaceFilePath(workspace.id)
        WorkspaceEventBus.loadWorkspace(savedPath, targetWindowId)

        var terminalInfo: JsonObject? = null
        if (openTerminal) {
            terminalInfo = doOpenTerminal(targetWindowId, workspace.id, workspace.projectPath, command = null)
        }

        val resultObj =
            buildJsonObject {
                put("success", true)
                put("workspaceId", workspace.id)
                put("workspaceName", workspace.name)
                put("projectPath", workspace.projectPath)
                put("windowId", targetWindowId)
                if (terminalInfo != null) {
                    put("terminal", terminalInfo)
                }
            }

        return McpToolResult(resultObj.toString())
    }

    private suspend fun handleCreateWorkspace(args: McpToolArgs): McpToolResult {
        val name = args.string("name")
        val projectPath = args.string("projectPath")
        val isDisposable = args.boolean("isDisposable") ?: false
        val openTerminal = args.boolean("openTerminal") ?: false

        if (!projectPath.isNullOrBlank() && !CLISecurityValidator.isValidPath(projectPath)) {
            return McpToolResult("Invalid project path (security check failed)", isError = true)
        }

        val id =
            if (isDisposable) {
                "workspace-disposable-${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}"
            } else {
                LayoutWorkspace.generateId()
            }

        val wsName =
            name?.takeIf { it.isNotBlank() }
                ?: if (isDisposable) "Disposable Workspace" else "New Workspace"

        val rootPath = projectPath ?: DefaultWorkingDirectory.nominalPath()
        val workspace = createDefaultWorkspace(id, wsName, rootPath, openTerminal = openTerminal)

        // Save to file manager
        val fileManager = getFileManager()
        val filePath = fileManager.saveWorkspace(workspace)

        val resultObj =
            buildJsonObject {
                put("success", true)
                put("workspaceId", id)
                put("workspaceName", wsName)
                put("projectPath", rootPath)
                if (filePath != null) {
                    put("filePath", filePath)
                }
                put("isDisposable", isDisposable)
            }

        return McpToolResult(resultObj.toString())
    }

    @Suppress("ReturnCount")
    private suspend fun handleOpenTerminal(args: McpToolArgs): McpToolResult {
        val requestedWindowId = args.string("windowId")
        val workspaceId = args.string("workspaceId")
        val workingDirectory = args.string("workingDirectory")
        val command = args.string("command")

        if (!command.isNullOrBlank() && !CLISecurityValidator.isValidCommand(command)) {
            return McpToolResult("Invalid command format (security check failed)", isError = true)
        }

        // Validate working directory if specified
        if (!workingDirectory.isNullOrBlank()) {
            if (!CLISecurityValidator.isValidPath(workingDirectory)) {
                return McpToolResult(
                    "Invalid working directory path (security check failed)",
                    isError = true,
                )
            }
            val dir = File(workingDirectory)
            if (!dir.exists() || !dir.isDirectory) {
                return McpToolResult(
                    "Working directory does not exist or is not a directory: $workingDirectory",
                    isError = true,
                )
            }
        }

        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> {
                    targetResolution.windowId
                }

                is TargetWindowResolution.Failure -> {
                    return McpToolResult(targetResolution.errorMessage, isError = true)
                }
            }

        val terminalInfo =
            doOpenTerminal(targetWindowId, workspaceId, workingDirectory, command)
                ?: return McpToolResult(
                    "Failed to open terminal in window $targetWindowId",
                    isError = true,
                )

        return McpToolResult(terminalInfo.toString())
    }

    @Suppress("ReturnCount")
    private suspend fun doOpenTerminal(
        windowId: String,
        workspaceId: String?,
        workingDirectory: String?,
        command: String?,
    ): JsonObject? {
        val effectiveCwd = workingDirectory ?: DefaultWorkingDirectory.nominalPath()

        val mountedTab =
            terminalTabOpener?.invoke(windowId, command, effectiveCwd) ?: run {
                val splitViewState = awaitSplitViewState(windowId) ?: return null
                withContext(Dispatchers.Main) {
                    splitViewState.openTerminalInActivePanelNow(command, effectiveCwd)
                }
            } ?: return null

        val tabId = mountedTab.id
        val terminalId = tabId.removePrefix("terminal-")

        // Always broadcast event for listeners/runners
        TerminalEventBus.openTerminal(
            command = command,
            sourceWindowId = windowId,
            workingDirectory = effectiveCwd,
            requiresConfirmation = false,
        )

        return buildJsonObject {
            put("success", true)
            put("tabId", tabId)
            put("terminalId", terminalId)
            put("windowId", windowId)
            if (workspaceId != null) {
                put("workspaceId", workspaceId)
            }
            put("workingDirectory", effectiveCwd)
            if (command != null) {
                put("command", command)
            }
            put("openedDirectly", true)
        }
    }

    private suspend fun handleCloseWorkspace(args: McpToolArgs): McpToolResult {
        val workspaceId = args.string("workspaceId")
        if (workspaceId.isNullOrBlank()) {
            return McpToolResult("workspaceId is required", isError = true)
        }

        val requestedWindowId = args.string("windowId")
        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> targetResolution.windowId
                is TargetWindowResolution.Failure -> null
            }

        // If disposable, delete file
        if (workspaceId.contains("disposable")) {
            val fileName =
                if (workspaceId.endsWith(".json")) {
                    workspaceId
                } else {
                    WorkspaceFileManagerCommon.fileNameForId(workspaceId)
                }
            getFileManager().deleteWorkspace(fileName)
        }

        val response =
            buildJsonObject {
                put("success", true)
                put("workspaceId", workspaceId)
                if (targetWindowId != null) {
                    put("windowId", targetWindowId)
                }
            }

        return McpToolResult(response.toString())
    }

    private fun createDefaultWorkspace(
        id: String,
        name: String,
        projectPath: String,
        openTerminal: Boolean,
    ): LayoutWorkspace {
        val tabs =
            if (openTerminal) {
                listOf(
                    TabConfig(
                        type = "terminal",
                        title = "Terminal",
                        workingDirectory = projectPath,
                    ),
                )
            } else {
                emptyList()
            }

        return LayoutWorkspace(
            id = id,
            name = name,
            description = "Workspace $name",
            layout =
                SinglePanel(
                    PanelConfig(
                        id = "panel-$id-1",
                        tabs = tabs,
                    ),
                ),
            projectPath = projectPath,
        )
    }
}
