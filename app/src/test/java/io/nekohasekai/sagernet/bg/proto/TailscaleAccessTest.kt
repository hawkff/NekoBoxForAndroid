package io.nekohasekai.sagernet.bg.proto

import android.app.Application
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleAccessTest {
    @Test
    fun stoppingBinderRefusesProbeEvenWhenCacheIsIdle() = runTest {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        var probed = false
        val binder = binder { BaseService.State.Stopping }
        val failure = runCatching {
            TailscaleAccess.run({ binder }, listOf(1), { error("must not query stopping core") }) { probed = true }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(probed)
    }

    @Test
    fun queuedProbeRechecksServiceAfterSerialization() = runTest {
        ConfigBuilderTestEnv.reset()
        var state = BaseService.State.Stopped
        val binder = binder { state }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var probes = 0
        val first = async {
            TailscaleAccess.run({ binder }, listOf(1), { -1 }) {
                probes++
                entered.complete(Unit)
                release.await()
                1
            }
        }
        entered.await()
        val second = async {
            runCatching {
                TailscaleAccess.run({ binder }, listOf(1), { -1 }) {
                    probes++
                    2
                }
            }
        }
        runCurrent()
        state = BaseService.State.Stopping
        release.complete(Unit)
        assertEquals(1, first.await())
        assertNotNull(second.await().exceptionOrNull())
        assertEquals(1, probes)
    }

    private fun binder(state: () -> BaseService.State) = Proxy.newProxyInstance(
        ISagerNetService::class.java.classLoader,
        arrayOf(ISagerNetService::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getState" -> state().ordinal
            else -> error("Unexpected service call: ${method.name}")
        }
    } as ISagerNetService
}
