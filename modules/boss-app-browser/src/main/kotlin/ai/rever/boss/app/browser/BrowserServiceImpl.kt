package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.logging.LogSanitizer
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The only schemes a navigation may start with. `UrlOpenValidation` in the host
 * process enforces the same allowlist on OS deep links, so the two readers agree:
 * a denylist here would leave `file:`, `blob:`, `view-source:` and the like open
 * in exactly the surface a compromised tool could reach.
 */
private val ALLOWED_SCHEME_PREFIXES = listOf("http://", "https://")

/** Space, backslash and the like: the printable characters [UrlOpenValidation] bars from an authority. */
private val FORBIDDEN_IN_AUTHORITY = charArrayOf('\u0020', '\u00A0', '\\', '"', '<', '>')

/**
 * gRPC implementation of BrowserService.
 *
 * Tracks navigation state per window using an in-memory map and streams
 * events via a SharedFlow. JxBrowser runs in the composeApp process —
 * this service handles routing and state management for multi-window
 * browser coordination over IPC.
 */
class BrowserServiceImpl : BrowserServiceGrpcKt.BrowserServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(BrowserServiceImpl::class.java)

    /** Per-window page state snapshot. */
    private data class PageState(
        val windowId: String,
        val url: String,
        val title: String,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val isLoading: Boolean = false,
    )

    private val windowStates = ConcurrentHashMap<String, PageState>()
    private val navigationEvents = MutableSharedFlow<BrowserNavigationEvent>(extraBufferCapacity = 128)

    /**
     * Refuses every scheme the host's [UrlOpenValidation] rule refuses, and the URL
     * shapes that scheme bypasses: browsers strip ASCII tab and newline anywhere in
     * a URL, so "java\tscript:" reaches the page as `javascript:` while a prefix
     * test sees neither.
     */
    private fun validateUrl(
        windowId: String,
        url: String,
        safeUrlForLogging: String,
    ): String? {
        val scheme = ALLOWED_SCHEME_PREFIXES.firstOrNull { url.startsWith(it, ignoreCase = true) }
        val error =
            when {
                url.isBlank() -> {
                    "URL must not be blank"
                }

                scheme == null -> {
                    logger.warn(
                        "Refusing navigation with a non-http(s) scheme: windowId={}, url={}",
                        windowId,
                        safeUrlForLogging,
                    )
                    "Only http:// and https:// URLs are allowed"
                }

                else -> {
                    authorityError(windowId, url, safeUrlForLogging, scheme.length, logger)
                }
            }
        return error
    }

    private fun emitNavEvent(
        windowId: String,
        url: String,
        title: String,
        type: NavigationEventType,
        timestamp: Long,
    ) {
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(windowId)
                .setUrl(url)
                .setTitle(title)
                .setEventType(type)
                .setTimestamp(timestamp)
                .build(),
        )
    }

    override suspend fun navigate(request: NavigateBrowserRequest): NavigateBrowserResponse {
        val url = request.url.trim()
        val safeUrlForLogging = LogSanitizer.redactUrlUserInfo(url)
        logger.info("navigate: windowId={}, url={}", request.windowId, safeUrlForLogging)

        val error = validateUrl(request.windowId, url, safeUrlForLogging)
        if (error != null) {
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage(error)
                .build()
        }

        val prev = windowStates[request.windowId]
        val newState =
            PageState(
                windowId = request.windowId,
                url = url,
                title = url,
                canGoBack = prev != null,
                isLoading = false,
            )
        windowStates[request.windowId] = newState

        val ts = System.currentTimeMillis()
        emitNavEvent(request.windowId, url, url, NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED, ts)
        emitNavEvent(request.windowId, url, url, NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED, ts + 1)

        return NavigateBrowserResponse
            .newBuilder()
            .setSuccess(true)
            .setFinalUrl(url)
            .setTitle(url)
            .build()
    }

    override suspend fun executeJS(request: ExecuteJSRequest): ExecuteJSResponse {
        logger.debug("executeJS: windowId={}, scriptLen={}", request.windowId, request.script.length)
        // JS execution requires JxBrowser which runs in the composeApp process.
        return ExecuteJSResponse
            .newBuilder()
            .setSuccess(false)
            .setErrorMessage("JS execution requires JxBrowser (composeApp process)")
            .build()
    }

    override fun onNavigationEvent(request: Empty): Flow<BrowserNavigationEvent> =
        flow {
            navigationEvents.collect { event -> emit(event) }
        }

    override suspend fun getFavicon(request: GetFaviconRequest): GetFaviconResponse {
        logger.debug("getFavicon: url={}", LogSanitizer.redactUrlUserInfo(request.url))
        return GetFaviconResponse
            .newBuilder()
            .setFaviconBytes(ByteString.EMPTY)
            .setContentType("")
            .build()
    }

    override suspend fun getPageInfo(request: Empty): PageInfoResponse {
        if (windowStates.size > 1) {
            logger.warn(
                "getPageInfo called with {} windows tracked - returning first window only; use a window-specific RPC for multi-window support",
                windowStates.size,
            )
        }
        val state = windowStates.values.firstOrNull()
        return PageInfoResponse
            .newBuilder()
            .setUrl(state?.url ?: "")
            .setTitle(state?.title ?: "")
            .setCanGoBack(state?.canGoBack ?: false)
            .setCanGoForward(state?.canGoForward ?: false)
            .setIsLoading(state?.isLoading ?: false)
            .build()
    }

    override suspend fun goBack(request: Empty): Empty {
        logger.debug("goBack")
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(System.currentTimeMillis())
                .build(),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun goForward(request: Empty): Empty {
        logger.debug("goForward")
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(System.currentTimeMillis())
                .build(),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun reload(request: Empty): Empty {
        logger.debug("reload")
        val state = windowStates.values.firstOrNull()
        if (state != null) {
            windowStates[state.windowId] = state.copy(isLoading = false)
            val ts = System.currentTimeMillis()
            emitNavEvent(
                state.windowId,
                state.url,
                state.title,
                NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED,
                ts,
            )
            emitNavEvent(
                state.windowId,
                state.url,
                state.title,
                NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED,
                ts + 1,
            )
        }
        return Empty.getDefaultInstance()
    }
}

/**
 * Checks the authority of an http(s) URL whose scheme ends at [schemeLength].
 *
 * @return a refusal message, or null when the authority is well formed.
 */
private fun authorityError(
    windowId: String,
    url: String,
    safeUrlForLogging: String,
    schemeLength: Int,
    logger: Logger,
): String? {
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), schemeLength)
    val authority = url.substring(schemeLength, if (authorityEnd >= 0) authorityEnd else url.length)
    return when {
        authority.isEmpty() || authority.any { it in FORBIDDEN_IN_AUTHORITY || it.isISOControl() } -> {
            logger.warn(
                "Refusing navigation with a malformed authority: windowId={}, url={}",
                windowId,
                safeUrlForLogging,
            )
            "The URL authority is malformed"
        }

        // Credentials in the authority are how a link disguises its real
        // destination ("https://apple.com@evil.example"); the host deep-link
        // gate refuses them too.
        authority.contains('@') -> {
            logger.warn(
                "Refusing navigation with credentials in the authority: windowId={}, url={}",
                windowId,
                safeUrlForLogging,
            )
            "Credentials are not allowed in the URL authority"
        }

        else -> {
            null
        }
    }
}
