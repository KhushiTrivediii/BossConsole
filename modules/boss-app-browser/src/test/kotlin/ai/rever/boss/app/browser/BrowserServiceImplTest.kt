package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.NavigateBrowserRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserServiceImplTest {
    private val service = BrowserServiceImpl()

    @Test
    fun `prohibited and unlisted schemes are refused`() =
        runBlocking<Unit> {
            listOf(
                "javascript:alert(1)",
                "data:text/html,hello",
                "vbscript:msgbox(1)",
                "file:///etc/passwd",
                "blob:https://example.com/uuid",
                "view-source:https://example.com",
                "about:blank",
            ).forEach { url ->
                val response =
                    service.navigate(
                        NavigateBrowserRequest
                            .newBuilder()
                            .setWindowId("w1")
                            .setUrl(url)
                            .build(),
                    )
                assertFalse(response.success, "$url must be refused")
                assertTrue(response.errorMessage.isNotEmpty(), "$url must carry a reason")
            }
        }

    @Test
    fun `control characters embedded in the scheme cannot bypass the allowlist`() =
        runBlocking<Unit> {
            // Browsers strip ASCII tab and newline anywhere in a URL, so these reach
            // the page as javascript: even though no prefix test sees "javascript:".
            listOf(
                "java\tscript:alert(1)",
                "java\nscript:alert(1)",
                "java\u0000script:alert(1)",
            ).forEach { url ->
                val response =
                    service.navigate(
                        NavigateBrowserRequest
                            .newBuilder()
                            .setWindowId("w1")
                            .setUrl(url)
                            .build(),
                    )
                assertFalse(response.success, "$url must be refused")
            }
        }

    @Test
    fun `malformed authorities are refused like the host deep-link gate does`() =
        runBlocking<Unit> {
            listOf(
                "https://",
                "https://exa mple.com/x",
                "https://user:pass@evil.example/x",
            ).forEach { url ->
                val response =
                    service.navigate(
                        NavigateBrowserRequest
                            .newBuilder()
                            .setWindowId("w1")
                            .setUrl(url)
                            .build(),
                    )
                assertFalse(response.success, "$url must be refused")
            }
        }

    @Test
    fun `http and https navigations succeed`() =
        runBlocking<Unit> {
            val response =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("w1")
                        .setUrl("https://example.com/test")
                        .build(),
                )
            assertTrue(response.success)
            assertEquals("https://example.com/test", response.finalUrl)
        }

    @Test
    fun `reload re-broadcasts the tracked title, not the url`() =
        runBlocking<Unit> {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("https://example.com")
                    .build(),
            )
            val events = mutableListOf<ai.rever.boss.ipc.proto.services.BrowserNavigationEvent>()
            // Unconfined so the collector is subscribed before reload emits: with no
            // live subscriber the shared flow's buffered events are dropped, not replayed.
            val collector =
                launch(Dispatchers.Unconfined) {
                    service.onNavigationEvent(Empty.getDefaultInstance()).collect { events += it }
                }
            service.reload(Empty.getDefaultInstance())
            collector.cancel()
            assertTrue(events.isNotEmpty(), "reload must emit navigation events")
            events.forEach { assertEquals("https://example.com", it.title) }
        }

    @Test
    fun `reload does not leave page loading state stranded true`() =
        runBlocking<Unit> {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("https://example.com")
                    .build(),
            )
            service.reload(Empty.getDefaultInstance())
            val pageInfo = service.getPageInfo(Empty.getDefaultInstance())
            assertFalse(pageInfo.isLoading)
        }
}
