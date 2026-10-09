package io.nekohasekai.sagernet.api

import android.content.Context
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.TailscaleSessionController
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.*
import moe.matsuri.nb4a.utils.JavaUtil
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class ApiRuntime(private val context: Context) : SagerConnection.Callback {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_LOCAL_API, true)

    @Volatile private var connected: ISagerNetService? = null

    @Volatile var speed = JSONObject()
        private set

    @Volatile var lastError: String? = null
        private set
    private val sequence = AtomicLong()
    private val sessions = ConcurrentHashMap<Long, JSONObject>()
    private val results = ConcurrentHashMap<Long, JSONObject>()
    private val requests = ConcurrentHashMap<Long, Long>()
    private val jobs = LinkedHashMap<String, JSONObject>()

    @Volatile var busy = false
        private set

    fun connect() = connection.connect(context, this)

    fun close() {
        scope.cancel()
        connection.disconnect(context)
        connected = null
        sessions.clear()
        results.clear()
        requests.clear()
    }

    suspend fun rebind() = withContext(Dispatchers.Main) {
        connection.disconnect(context)
        onServiceDisconnected()
        connect()
    }

    fun binder() = connected ?: reject("service_unavailable", "The service connection is not ready")

    fun serviceState() = connected?.let { service ->
        runCatching { BaseService.State.entries.getOrNull(service.state) }.getOrNull()
    } ?: BaseService.State.Idle

    override fun onServiceConnected(service: ISagerNetService) {
        connected = service
    }

    override fun onServiceDisconnected() {
        connected = null
        sessions.clear()
        results.clear()
        requests.clear()
    }

    override fun onBinderDied() {
        onServiceDisconnected()
        connection.disconnect(context)
        connect()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        lastError = if (msg == null) null else "service_failed"
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        speed = JSONObject(JavaUtil.gson.toJson(stats))
    }

    override fun cbSelectorUpdate(id: Long) {
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
    }

    override fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) {
        if (sessions.containsKey(sessionId)) {
            sessions[sessionId] = JSONObject().put("sessionId", sessionId).put("sequence", sequence).put("status", JSONObject(json))
        }
    }

    override fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) {
        if (requests[requestId] == sessionId) results[requestId] = JSONObject(json)
    }

    @Synchronized
    fun job(action: suspend () -> Any): JSONObject {
        if (busy) reject("busy", "A background operation is running")
        busy = true
        val id = UUID.randomUUID().toString()
        synchronized(jobs) {
            while (jobs.size >= 16) jobs.remove(jobs.keys.first())
            jobs[id] = JSONObject().put("id", id).put("state", "running")
        }
        scope.launch {
            val outcome = JSONObject().put("id", id)
            try {
                outcome.put("result", withTimeout(600_000) { action() }).put("state", "succeeded")
            } catch (_: CancellationException) {
                outcome.put("state", "cancelled")
            } catch (e: ApiFailure) {
                outcome.put("state", "failed").put("error", JSONObject().put("code", e.code).put("message", e.message))
            } catch (_: Exception) {
                outcome.put("state", "failed").put("error", JSONObject().put("code", "operation_failed"))
            } finally {
                synchronized(jobs) { jobs[id] = outcome }
                busy = false
            }
        }
        return JSONObject().put("jobId", id)
    }

    fun jobStatus(id: String) = synchronized(jobs) {
        jobs[id]?.let { JSONObject(it.toString()) } ?: reject("not_found", "Job is unknown or expired")
    }

    suspend fun openTailscale(profile: ProxyEntity, check: Boolean): JSONObject = withContext(Dispatchers.Main) {
        binder()
        requireApi(profile.type == ProxyEntity.TYPE_TAILSCALE, "A Tailscale profile is required")
        if (sessions.size >= TailscaleSessionController.MAX_OWNER_SESSIONS) reject("busy", "Close a Tailscale session before opening another")
        val id = sequence.incrementAndGet()
        sessions[id] = JSONObject().put("sessionId", id).put("state", "pending")
        if (check) connection.startTailscaleCheck(id, profile.id, profile.uuid) else connection.observeTailscale(id, profile.id, profile.uuid)
        JSONObject().put("sessionId", id)
    }

    fun tailscaleStatus(id: Long) = sessions[id] ?: reject("not_found", "Tailscale session is unknown")

    suspend fun closeTailscale(id: Long) = withContext(Dispatchers.Main) {
        tailscaleStatus(id)
        connection.closeTailscaleSession(id)
        sessions.remove(id)
        requests.entries.filter { it.value == id }.forEach { (requestId, _) ->
            requests.remove(requestId)
            results.remove(requestId)
        }
        JSONObject().put("closed", true)
    }

    suspend fun tailscaleRequest(sessionId: Long, peer: String, timeout: Int?, savedExit: String?): JSONObject {
        requireApi(peer.length <= TailscaleSessionController.MAX_PEER_ID_LENGTH, "Peer ID is too long")
        requireApi(timeout != null || (savedExit != null && savedExit.length <= TailscaleSessionController.MAX_EXIT_SELECTION_LENGTH), "Invalid exit selection")
        return withContext(Dispatchers.Main) {
            binder()
            tailscaleStatus(sessionId)
            if (requests.size >= 64) reject("busy", "Close the session to discard completed requests")
            val active = requests.count { (id, owner) ->
                val result = results[id]
                owner == sessionId && result?.optBoolean("done") != true && result?.optString("kind") != "exit"
            }
            if (active >= TailscaleSessionController.MAX_SESSION_REQUESTS) reject("busy", "Wait for a Tailscale request to finish")
            val id = sequence.incrementAndGet()
            requests[id] = sessionId
            if (timeout != null) {
                connection.pingTailscalePeer(sessionId, id, peer, timeout)
            } else {
                connection.setTailscaleExitNode(sessionId, id, peer, savedExit!!)
            }
            JSONObject().put("requestId", id)
        }
    }

    fun tailscaleResult(id: Long): JSONObject {
        if (!requests.containsKey(id)) reject("not_found", "Tailscale request is unknown")
        return results[id] ?: JSONObject().put("state", "pending")
    }

    suspend fun cancelTailscale(id: Long) = withContext(Dispatchers.Main) {
        val session = requests[id] ?: reject("not_found", "Tailscale request is unknown")
        connection.cancelTailscaleRequest(session, id)
        JSONObject().put("accepted", true)
    }
}
