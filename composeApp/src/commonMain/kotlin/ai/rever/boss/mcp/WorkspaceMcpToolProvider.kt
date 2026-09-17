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
import ai.rever.boss.components.workspaces.extractPanels
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectState
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock

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
// One cohesive MCP tool provider; handlers stay beside their tool definitions.
@Suppress("TooManyFunctions", "LargeClass")
object WorkspaceMcpToolProvider : McpToolProvider {
    private val logger = BossLogger.forComponent("WorkspaceMcpToolProvider")

    /** Panel id of the terminal panel the bootstrap Space builds. */
    const val BOOTSTRAP_PANEL_ID = "panel-open-workspace"

    /** Id prefix of disposable workspaces minted by this tool; only these may be file-deleted. */
    internal const val DISPOSABLE_ID_PREFIX = "workspace-disposable-"

    /**
     * Bootstrap Spaces this tool created, by window then by canonical project path, so a second
     * open of the same project re-enters the Space instead of minting a second one for the same
     * directory. The manager's windowWorkspaces map covers saved Spaces, but an unsaved bootstrap
     * Space is not in the manager's list, so the tool remembers its own (matching rules
     * consolidated from #799).
     */
    internal val createdSpaces = ConcurrentHashMap<String, ConcurrentHashMap<String, LayoutWorkspace>>()

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
                val openIds =
                    SplitViewStateRegistry
                        .getAllStates()
                        .keys
                        .joinToString(", ")
                        .ifEmpty { "(none)" }
                TargetWindowResolution.Failure(
                    "Target window '$requestedWindowId' is not registered or has been closed. " +
                        "Open windows: $openIds",
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
        McpToolDefinition.withRbac(
            name = name,
            description =
                "Open a workspace in a window: either 'path' to open a project directory as a new " +
                    "Space with its first terminal (bootstrap; re-opening a running path re-enters " +
                    "the Space instead of duplicating it), or an existing workspace by " +
                    "'workspaceId' / 'workspacePath', optionally created via 'name' / 'projectPath' " +
                    "with 'createIfAbsent'.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "Absolute path of an existing project directory to open as a new Space with its first terminal. A leading ~ is expanded; relative paths are refused." },
                        "workspaceId": { "type": "string", "description": "ID of the workspace to open" },
                        "workspacePath": { "type": "string", "description": "Path to workspace JSON file" },
                        "name": { "type": "string", "description": "Name if creating workspace" },
                        "projectPath": { "type": "string", "description": "Project root directory" },
                        "windowId": { "type": "string", "description": "Target window ID" },
                        "createIfAbsent": { "type": "boolean", "description": "Create workspace if not found" },
                        "openTerminal": { "type": "boolean", "description": "Automatically open a terminal tab (workspace-id modes; the path bootstrap already includes its first terminal)" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleOpenWorkspace(args) },
            readOnly = false,
        )

    private fun createCreateWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition.withRbac(
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
            readOnly = false,
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
            readOnly = false,
        )

    private fun createCloseWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Stop a workspace running in a window (clearing its tabs), and delete the file of a " +
                    "disposable workspace this tool created.",
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
            readOnly = false,
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
                // Read-only: a listing must not create a window. With exactly one registered
                // window it is the only possible target; otherwise report none.
                SplitViewStateRegistry.getAllStates().keys.singleOrNull()
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
                if (targetWindowId != null) {
                    put("activeWindowId", targetWindowId)
                }
                if (activeWorkspaceId != null) {
                    put("activeWorkspaceId", activeWorkspaceId)
                }
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

        val path = args.string("path")
        if (!path.isNullOrBlank()) {
            val otherSelectors =
                listOfNotNull(
                    workspaceId?.takeIf { it.isNotBlank() },
                    workspacePath?.takeIf { it.isNotBlank() },
                    name?.takeIf { it.isNotBlank() },
                )
            if (otherSelectors.isNotEmpty()) {
                return McpToolResult(
                    "Specify 'path' alone (bootstrap a project directory) or one of 'workspaceId' / " +
                        "'workspacePath' / 'name' (open or create a saved workspace), not both.",
                    isError = true,
                )
            }
            return openWorkspaceByPath(path, requestedWindowId)
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

    /**
     * Path-based bootstrap mode of open_workspace (consolidated from #799): open [rawPath] as a
     * Space in a window, creating its first terminal panel for the panel-scoped terminal tools
     * (`run_in_panel` and friends). Re-opening a path that is already running re-enters the
     * Space instead of minting a duplicate (see [matchExistingSpace]).
     */
    @Suppress("ReturnCount")
    private suspend fun openWorkspaceByPath(
        rawPath: String,
        requestedWindowId: String?,
    ): McpToolResult {
        val pathCheck = checkProjectPath(rawPath)
        val projectPath =
            pathCheck.canonicalPath
                ?: return McpToolResult(pathCheck.error ?: "Invalid path: $rawPath", isError = true)

        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> targetResolution.windowId
                is TargetWindowResolution.Failure -> return McpToolResult(targetResolution.errorMessage, isError = true)
            }
        val splitViewState =
            awaitSplitViewState(targetWindowId)
                ?: return McpToolResult(
                    "Window '$targetWindowId' did not register its UI state in time; retry.",
                    isError = true,
                )
        val runningIds = workspaceManager.windowWorkspaces.value[targetWindowId].orEmpty()
        val (space, reused) = resolveBootstrapSpace(targetWindowId, projectPath, runningIds)

        // Fast path: the window already shows this Space, so the live terminal is left alone.
        if (splitViewState.currentWorkspaceId == space.id) {
            return McpToolResult(
                buildPathResult(
                    reused = true,
                    windowId = targetWindowId,
                    space = space,
                    projectPath = projectPath,
                ),
            )
        }

        switchWindowToSpace(splitViewState, WindowProjectStateRegistry.getOrCreate(targetWindowId), space)

        if (!splitViewState.tabRegistry.isRegistered(TerminalTabType.typeId)) {
            return McpToolResult(
                "The Space is open, but the terminal tab type is not registered, so terminal tools have " +
                    "no panel to attach to. Check that the terminal plugin is installed and enabled, then retry.",
                isError = true,
            )
        }

        return McpToolResult(
            buildPathResult(
                reused = reused,
                windowId = targetWindowId,
                space = space,
                projectPath = projectPath,
            ),
        )
    }

    /**
     * The Space [projectPath] should run in [windowId]: an existing one worth re-entering (see
     * [matchExistingSpace]), or a fresh bootstrap Space remembered for future re-entry. Returns
     * the Space and whether it was re-entered rather than created.
     */
    private fun resolveBootstrapSpace(
        windowId: String,
        projectPath: String,
        runningIds: Set<String>,
    ): Pair<LayoutWorkspace, Boolean> {
        val existing =
            matchExistingSpace(
                remembered = createdSpaces[windowId]?.get(projectPath),
                savedSpaces = workspaceManager.workspaces.value,
                runningIdsInWindow = runningIds,
                projectPath = projectPath,
            )
        return Pair(
            existing ?: buildBootstrapSpace(projectPath).also { fresh ->
                createdSpaces.getOrPut(windowId) { ConcurrentHashMap() }[projectPath] = fresh
            },
            existing != null,
        )
    }

    /**
     * Preserve, load, apply: the same three steps the Space switcher takes, so re-entering a
     * previously running Space restores its preserved tree when the window holds one.
     */
    private suspend fun switchWindowToSpace(
        splitViewState: SplitViewState,
        windowProjectState: WindowProjectState,
        space: LayoutWorkspace,
    ) {
        withContext(Dispatchers.Main) {
            val currentWorkspace = workspaceManager.currentWorkspace.value
            if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
            }
            workspaceManager.loadWorkspace(space)
            applyWorkspace(space, splitViewState, windowProjectState, restoreProject = true)
        }
    }

    /** The JSON reply for path mode: status plus the ids a caller needs to aim tools at what was opened. */
    private fun buildPathResult(
        reused: Boolean,
        windowId: String,
        space: LayoutWorkspace,
        projectPath: String,
    ): String {
        val panelId =
            space.layout
                .extractPanels()
                .firstOrNull()
                ?.first
                ?: BOOTSTRAP_PANEL_ID
        return buildJsonObject {
            put("success", true)
            put("status", if (reused) "reused" else "opened")
            put("workspaceId", space.id)
            put("workspaceName", space.name)
            put("projectPath", projectPath)
            put("windowId", windowId)
            put("panelId", panelId)
        }.toString()
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
                "$DISPOSABLE_ID_PREFIX${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}"
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

        // Stop the Space where it is running: clears its tabs and drops any preserved copy,
        // the same effect closing it in the Space list has.
        var releasedHere = false
        if (targetWindowId != null) {
            val splitViewState = awaitSplitViewState(targetWindowId)
            if (splitViewState != null) {
                releasedHere = withContext(Dispatchers.Main) { splitViewState.closeWorkspace(workspaceId) }
            }
        }

        // Only delete the file of a disposable workspace this tool minted (prefix, not
        // substring): a user's saved Space whose name merely mentions "disposable" is not ours.
        var fileDeleted = false
        if (workspaceId.startsWith(DISPOSABLE_ID_PREFIX)) {
            val fileName =
                if (workspaceId.endsWith(".json")) {
                    workspaceId
                } else {
                    WorkspaceFileManagerCommon.fileNameForId(workspaceId)
                }
            fileDeleted = getFileManager().deleteWorkspace(fileName)
        }

        val response =
            buildJsonObject {
                put("success", true)
                put("workspaceId", workspaceId)
                if (targetWindowId != null) {
                    put("windowId", targetWindowId)
                }
                put("releasedHere", releasedHere)
                put("fileDeleted", fileDeleted)
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

/**
 * Expands a leading `~` to the user's home directory, the way a shell would, so a path an
 * agent copy-pasted from a terminal works unchanged. Anything else passes through as-is.
 */
internal fun expandTilde(
    path: String,
    home: String? = System.getProperty("user.home"),
): String =
    when {
        path == "~" -> home ?: path
        path.startsWith("~/") -> home?.let { it + path.substring(1) } ?: path
        else -> path
    }

private val pathLog = BossLogger.forComponent("WorkspaceMcpToolProvider")

/**
 * The canonical absolute form of [path], or null when it is not an existing directory.
 * Every failure (missing, a file, unreadable, security-restricted) means the same thing
 * to the caller: report a clear error.
 */
@Suppress("TooGenericExceptionCaught")
internal fun canonicalizeOrNull(path: String): String? =
    try {
        val dir = File(path)
        if (dir.isDirectory) dir.canonicalPath else null
    } catch (t: Throwable) {
        pathLog.warn(LogCategory.WORKSPACE, "Cannot canonicalize project path: $path", error = t)
        null
    }

/**
 * The outcome of validating the caller's project path for open_workspace path mode: the
 * canonical directory to open ([canonicalPath]), or the user-facing reason it was refused
 * ([error]); at most one of the two is set.
 */
private data class ProjectPathCheck(
    val canonicalPath: String?,
    val error: String?,
)

private suspend fun checkProjectPath(rawPath: String): ProjectPathCheck {
    val expandedPath = expandTilde(rawPath)
    // Same gate the boss://folder deep link runs before opening a project folder: a connected
    // MCP client is no more trusted than a web page, so both surfaces share one definition of
    // an acceptable project path, failing closed.
    val rejection =
        when {
            !File(expandedPath).isAbsolute -> {
                "Path must be absolute (got '$rawPath'): a relative path would resolve against the " +
                    "BOSS process's working directory, not the caller's."
            }

            !CLISecurityValidator.isValidPath(expandedPath) -> {
                "Refusing to open '$rawPath': the path contains characters the boss:// folder deep " +
                    "link rejects for the same operation (`..`, or shell metacharacters like `;`, `&`, " +
                    "`|`, `$` and a backtick). Pass a plain absolute path to the project directory instead."
            }

            else -> {
                null
            }
        }
    if (rejection != null) {
        return ProjectPathCheck(null, rejection)
    }
    val canonical = withContext(Dispatchers.IO) { canonicalizeOrNull(expandedPath) }
    return ProjectPathCheck(canonical, "Path is not an existing directory: $rawPath".takeIf { canonical == null })
}

/**
 * The bootstrap Space open_workspace opens for a project: one panel, one terminal tab pointed
 * at the project, named for it, so it reads naturally in the Space picker if the user saves it
 * (consolidated from #799).
 */
internal fun buildBootstrapSpace(canonicalPath: String): LayoutWorkspace {
    val projectName = canonicalPath.trimEnd('/').extractFileName().ifEmpty { "Project" }
    return LayoutWorkspace(
        id = LayoutWorkspace.generateId(),
        name = projectName,
        description = "Bootstrap Space opened by the open_workspace MCP tool.",
        layout =
            SinglePanel(
                PanelConfig(
                    id = WorkspaceMcpToolProvider.BOOTSTRAP_PANEL_ID,
                    tabs =
                        listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Terminal",
                                workingDirectory = canonicalPath,
                            ),
                        ),
                ),
            ),
        timestamp = Clock.System.now().toEpochMilliseconds(),
        projectPath = canonicalPath,
    )
}

/**
 * The Space to re-enter for [projectPath], if there is one worth reusing rather than building a
 * fresh bootstrap Space (matching rules consolidated from #799):
 *
 * 1. a Space this tool already created for the path, when it is running in the window;
 * 2. a saved Space for the path that is running in the window, the same thing from the user's
 *    own list;
 * 3. any saved Space for the path, which applies to this window the way picking it in the Space
 *    switcher would;
 * 4. a Space this tool created earlier even though it is no longer running - reusing the object
 *    keeps its id stable instead of minting a second Space for the same directory.
 */
internal fun matchExistingSpace(
    remembered: LayoutWorkspace?,
    savedSpaces: List<LayoutWorkspace>,
    runningIdsInWindow: Set<String>,
    projectPath: String,
): LayoutWorkspace? =
    remembered?.takeIf { it.id in runningIdsInWindow }
        ?: savedSpaces.firstOrNull { it.id in runningIdsInWindow && it.projectPath == projectPath }
        ?: savedSpaces.firstOrNull { it.projectPath == projectPath }
        ?: remembered
