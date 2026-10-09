package io.nekohasekai.sagernet.api

import android.app.Application
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.TailscaleSessionController
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ApiRuntimeTest {
    @Test
    fun apiDisconnectDoesNotOverwriteUiStateButItsOwnStateBecomesUnknown() {
        ConfigBuilderTestEnv.reset()
        var state = BaseService.State.Stopped
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication()) { state }
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            DataStore.serviceState = BaseService.State.Connected
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
            state = BaseService.State.Stopping
            assertEquals(BaseService.State.Stopping, runtime.serviceState())
            runtime.onServiceDisconnected()
            assertEquals(BaseService.State.Connected, DataStore.serviceState)
            assertEquals(BaseService.State.Idle, runtime.serviceState())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun oversizedTailscaleInputsAreRejectedBeforeIdsOrRequestSlotsAreConsumed() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication())
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            val profile = ProxyEntity(id = 42, uuid = "fixture").putBean(TailscaleBean().apply { initializeDefaultValues() })
            val session = runtime.openTailscale(profile, false).getLong("sessionId")
            for ((peer, timeout, exit) in listOf(
                Triple("p".repeat(257), 1000, null),
                Triple("p".repeat(257), null, ""),
                Triple("peer", null, "e".repeat(4097)),
            )) {
                val failure = runCatching { runtime.tailscaleRequest(session, peer, timeout, exit) }.exceptionOrNull()
                assertTrue(failure is ApiFailure)
                assertEquals("invalid_parameters", (failure as ApiFailure).code)
            }
            assertFalse(fixture.calls.any { it.first == "pingTailscalePeer" || it.first == "setTailscaleExitNode" })
            val accepted = runtime.tailscaleRequest(session, "p".repeat(256), 1000, null).getLong("requestId")
            assertEquals(session + 1, accepted)
            runtime.tailscaleRequest(session, "p".repeat(256), null, "e".repeat(4096))
            assertEquals(1, fixture.calls.count { it.first == "setTailscaleExitNode" })
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun admissionMatchesControllerSessionAndInFlightRequestLimits() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication())
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            val profile = ProxyEntity(id = 42, uuid = "fixture").putBean(TailscaleBean().apply { initializeDefaultValues() })
            val session = runtime.openTailscale(profile, false).getLong("sessionId")
            runtime.openTailscale(profile, false)
            assertEquals(TailscaleSessionController.MAX_OWNER_SESSIONS, fixture.calls.count { it.first == "observeTailscale" })
            assertEquals("busy", (runCatching { runtime.openTailscale(profile, false) }.exceptionOrNull() as ApiFailure).code)
            val requests = List(TailscaleSessionController.MAX_SESSION_REQUESTS) { runtime.tailscaleRequest(session, "peer", 1000, null).getLong("requestId") }
            assertEquals("busy", (runCatching { runtime.tailscaleRequest(session, "peer", 1000, null) }.exceptionOrNull() as ApiFailure).code)
            fixture.callback.cbTailscaleResult(session, requests.first(), """{"kind":"ping","done":true}""")
            runCurrent()
            runtime.tailscaleRequest(session, "peer", 1000, null)
            assertEquals(TailscaleSessionController.MAX_SESSION_REQUESTS + 1, fixture.calls.count { it.first == "pingTailscalePeer" })
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }
}
