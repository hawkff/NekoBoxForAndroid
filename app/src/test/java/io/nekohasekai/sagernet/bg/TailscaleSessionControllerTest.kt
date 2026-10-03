package io.nekohasekai.sagernet.bg

import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.TailscaleProfileStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleSessionControllerTest {
    private lateinit var data: BaseService.Data
    private lateinit var profile: ProxyEntity

    private class Client(val binder: IBinder = Binder()) {
        val statuses = LinkedBlockingQueue<JSONObject>()
        val results = LinkedBlockingQueue<JSONObject>()
        val callback = Proxy.newProxyInstance(ISagerNetServiceCallback::class.java.classLoader,
            arrayOf(ISagerNetServiceCallback::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "cbTailscaleStatus" -> { statuses.add(JSONObject(args!![2] as String)); null }
                "cbTailscaleResult" -> { results.add(JSONObject(args!![2] as String)); null }
                else -> null
            }
        } as ISagerNetServiceCallback

        fun status(): JSONObject = checkNotNull(statuses.poll(5, TimeUnit.SECONDS))
        fun result(): JSONObject = checkNotNull(results.poll(5, TimeUnit.SECONDS))
    }

    @Before
    fun setup() {
        ConfigBuilderTestEnv.reset()
        data = newData()
        profile = ProxyEntity(groupId = 1).putBean(TailscaleBean().apply { initializeDefaultValues() })
        profile.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(profile) }
    }

    private fun newData(): BaseService.Data {
        lateinit var result: BaseService.Data
        val service = Proxy.newProxyInstance(BaseService.Interface::class.java.classLoader,
            arrayOf(BaseService.Interface::class.java)) { _, method, _ ->
            if (method.name == "getData") result else error("Unexpected service operation: ${method.name}")
        } as BaseService.Interface
        result = BaseService.Data(service)
        return result
    }

    @After
    fun cleanup() {
        runBlocking { data.tailscale.drain() }
        data.binder.close()
    }

    private fun open(client: Client) {
        data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        data.binder.observeTailscale(client.callback, 1, profile.id, profile.uuid)
    }

    @Test
    fun passiveOpenAndPingNeverStartANode() {
        val client = Client()
        open(client)
        val status = client.status()
        assertEquals("not-running", status.getString("stage"))
        assertEquals("none", status.getString("source"))
        assertTrue(status.isNull("node"))
        assertNull(data.proxy)
        data.binder.pingTailscalePeer(client.callback, 1, 1, "peer", 100)
        assertEquals("tailscale:not-running", client.result().getString("errorCode"))
        assertEquals(BaseService.State.Stopped, data.state)
    }

    @Test
    fun sameSessionAndConnectionIdsBelongToDifferentBinders() {
        val first = Client()
        val second = Client()
        open(first)
        open(second)
        first.status()
        second.status()
        data.binder.closeTailscaleSession(first.callback, 1)
        data.binder.pingTailscalePeer(second.callback, 1, 1, "peer", 100)
        assertEquals("ping", second.result().getString("kind"))
        assertNull(first.results.poll())
        assertEquals(2, data.binder.callbackIdMap.size)
    }

    @Test
    fun registrationAndUnregisterUseBinderIdentityNotWrapperIdentity() {
        val original = Client()
        val alias = Client(original.binder)
        open(original)
        original.status()
        data.binder.registerCallback(alias.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        assertEquals(1, data.binder.callbackIdMap.size)
        data.binder.unregisterCallback(alias.callback)
        assertTrue(data.binder.callbackIdMap.isEmpty())
        data.binder.pingTailscalePeer(original.callback, 1, 1, "peer", 100)
        assertNull(original.results.poll(100, TimeUnit.MILLISECONDS))
        data.binder.unregisterCallback(original.callback)
    }

    @Test
    fun callbackDeathUsesTheSameCleanupAndDoesNotAffectAnotherOwner() {
        val first = Client()
        val second = Client()
        open(first)
        open(second)
        first.status()
        second.status()
        val field = BaseService.Binder::class.java.getDeclaredField("callbacks").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val callbacks = field.get(data.binder) as RemoteCallbackList<ISagerNetServiceCallback>
        callbacks.onCallbackDied(first.callback)
        assertFalse(data.binder.callbackIdMap.containsKey(first.binder))
        data.binder.pingTailscalePeer(second.callback, 1, 1, "peer", 100)
        assertEquals("ping", second.result().getString("kind"))
        data.binder.pingTailscalePeer(first.callback, 1, 1, "peer", 100)
        assertNull(first.results.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun drainClosesAdmissionBeforeJoiningSessions() {
        val client = Client()
        open(client)
        client.status()
        runBlocking { data.tailscale.drain() }
        data.binder.startTailscaleCheck(client.callback, 2, profile.id, profile.uuid)
        assertEquals("tailscale:not-stopped", client.status().getString("errorCode"))
        assertNull(data.proxy)
    }

    @Test
    fun stoppedBoundModeCannotStartTemporaryNodeWhileOtherModeStarts() {
        val otherMode = newData()
        otherMode.state = BaseService.State.Connecting
        try {
            val client = Client()
            data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
            data.binder.startTailscaleCheck(client.callback, 1, profile.id, profile.uuid)
            assertEquals("tailscale:not-stopped", client.status().getString("errorCode"))
            assertNull(data.proxy)
        } finally {
            otherMode.state = BaseService.State.Stopped
            otherMode.binder.close()
        }
    }

    @Test
    fun modeHandoffDrainsAcceptedSaveBeforeStartupReadsProfile() = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val otherMode = newData()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = TailscaleSessionController::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        val session = (field.get(data.tailscale) as Map<*, *>).values.single()!!
        val owner = session.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(session) as Job
        // Simulate an accepted temporary-node finalizer under the real session parent, without JNI.
        val finalizer = CoroutineScope(owner + Dispatchers.IO).launch {
            finalizeTailscaleExit("", {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                object : TailscaleExitChange {
                    override fun savedValue() = "100.64.0.2"
                    override fun commit() = Unit
                    override fun rollback() = Unit
                }
            }, { TailscaleProfileStore.compareAndSetExit(profile.id, profile.uuid, "", it) }, {}, {})
        }
        var startup: Deferred<String>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            otherMode.state = BaseService.State.Connecting
            startup = async(Dispatchers.IO) {
                TailscaleSessionController.drainAll()
                TailscaleProfileStore.read(profile.id).tailscaleBean!!.exitNode.orEmpty()
            }
            delay(100)
            assertFalse(startup.isCompleted)
            release.countDown()
            assertEquals("100.64.0.2", startup.await())
            assertTrue(finalizer.isCompleted)
        } finally {
            release.countDown()
            startup?.join()
            finalizer.join()
            otherMode.state = BaseService.State.Stopped
            otherMode.binder.close()
        }
    }

    @Test
    fun staleIdentityReturnsPrivateErrorWithoutStarting() {
        val client = Client()
        data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        data.binder.observeTailscale(client.callback, 1, profile.id, "old-identity")
        assertEquals("error", client.status().getString("stage"))
        assertNull(data.proxy)
    }
}
