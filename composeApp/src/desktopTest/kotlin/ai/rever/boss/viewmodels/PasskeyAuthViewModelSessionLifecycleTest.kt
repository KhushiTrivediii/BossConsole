package ai.rever.boss.viewmodels

import ai.rever.boss.services.supabase.CrossDeviceAuthenticationRequired
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial lifecycle tests for the PasskeyAuthViewModel auth state machine.
 * The passkey authentication seam is a non-cancellation-cooperative gate, matching
 * a downstream service that converts CancellationException into Result.failure.
 * Every failure mode is therefore driven deterministically instead of by sleeps:
 * a superseded attempt completing late, local cross-device identity retirement,
 * and an explicit cancel killing the in-flight completion path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyAuthViewModelSessionLifecycleTest {
    private val pendingAttempts = ArrayDeque<CompletableDeferred<Result<Unit>>>()

    private fun newViewModel() =
        PasskeyAuthViewModel { _, _ ->
            val gate = CompletableDeferred<Result<Unit>>()
            pendingAttempts.addLast(gate)
            withContext(NonCancellable) { gate.await() }
        }

    private fun crossDeviceRequirement() =
        CrossDeviceAuthenticationRequired(
            qrCodeUrl = "https://auth.example/qr",
            challenge = "challenge-superseded",
            sessionId = "session-superseded",
        )

    @Test
    fun `a superseded attempt cannot fire onSuccess or clear the loading state`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                var firstSuccess = 0
                var secondSuccess = 0
                viewModel.authenticateWithEmailAndPasskey("user@example.com") { firstSuccess++ }
                advanceUntilIdle()
                val superseded = pendingAttempts.removeFirst()
                assertTrue(viewModel.isLoading.value)

                viewModel.authenticateWithSpecificPasskey("user@example.com", "cred-1") { secondSuccess++ }
                advanceUntilIdle()
                assertEquals(1, pendingAttempts.size)

                // The abandoned attempt completes after being superseded.
                superseded.complete(Result.success(Unit))
                advanceUntilIdle()

                assertEquals(0, firstSuccess)
                assertEquals(0, secondSuccess)
                assertTrue(viewModel.isLoading.value)
            } finally {
                pendingAttempts.forEach { it.complete(Result.failure(Exception("test cleanup"))) }
                advanceUntilIdle()
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a superseded attempt cannot re-arm the cross-device QR dialog`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                viewModel.authenticateWithEmailAndPasskey("user@example.com") {}
                advanceUntilIdle()
                val superseded = pendingAttempts.removeFirst()

                viewModel.authenticateWithSpecificPasskey("user@example.com", "cred-1") {}
                advanceUntilIdle()

                // The abandoned attempt surfaces a cross-device requirement late.
                superseded.complete(Result.failure(crossDeviceRequirement()))
                advanceUntilIdle()

                assertFalse(viewModel.showCrossDeviceQR.value)
                assertNull(viewModel.crossDeviceQRUrl.value)
                assertNull(viewModel.crossDeviceChallenge.value)
                assertNull(viewModel.crossDeviceSessionId.value)
            } finally {
                pendingAttempts.forEach { it.complete(Result.failure(Exception("test cleanup"))) }
                advanceUntilIdle()
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `dismissing the QR dialog retires the cross-device session identity`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                viewModel.authenticateWithEmailAndPasskey("user@example.com") {}
                advanceUntilIdle()
                pendingAttempts.removeFirst().complete(Result.failure(crossDeviceRequirement()))
                advanceUntilIdle()
                assertTrue(viewModel.showCrossDeviceQR.value)
                assertEquals("session-superseded", viewModel.crossDeviceSessionId.value)

                viewModel.dismissCrossDeviceQR()

                assertFalse(viewModel.showCrossDeviceQR.value)
                assertNull(viewModel.crossDeviceQRUrl.value)
                assertNull(viewModel.crossDeviceChallenge.value)
                assertNull(viewModel.crossDeviceSessionId.value)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cancelling authentication retires the local cross-device identity`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                viewModel.authenticateWithEmailAndPasskey("user@example.com") {}
                advanceUntilIdle()
                pendingAttempts.removeFirst().complete(Result.failure(crossDeviceRequirement()))
                advanceUntilIdle()
                assertTrue(viewModel.showCrossDeviceQR.value)

                viewModel.cancelAuthentication()

                assertFalse(viewModel.showCrossDeviceQR.value)
                assertNull(viewModel.crossDeviceQRUrl.value)
                assertNull(viewModel.crossDeviceChallenge.value)
                assertNull(viewModel.crossDeviceSessionId.value)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `an explicitly cancelled attempt cannot complete the login`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                var success = 0
                viewModel.authenticateWithEmailAndPasskey("user@example.com") { success++ }
                advanceUntilIdle()
                val gate = pendingAttempts.removeFirst()

                viewModel.cancelAuthentication()
                assertFalse(viewModel.isLoading.value)

                // The cancelled attempt completes after the user backed out.
                gate.complete(Result.success(Unit))
                advanceUntilIdle()
                assertEquals(0, success)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
}
