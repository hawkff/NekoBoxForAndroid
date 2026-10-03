package io.nekohasekai.sagernet.bg

import android.os.IBinder
import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.BoxInstance
import io.nekohasekai.sagernet.bg.proto.TAILSCALE_LOGIN_TIMEOUT_MS
import io.nekohasekai.sagernet.bg.proto.TailscaleAccess
import io.nekohasekai.sagernet.bg.proto.TailscaleReadinessIntent
import io.nekohasekai.sagernet.bg.proto.TailscaleSessionInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.TailscaleProfileStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import org.json.JSONObject

/** Owns only Tailscale management resources, never the running service's box. */
internal class TailscaleSessionController(
    private val data: BaseService.Data,
    private val registered: (IBinder) -> Boolean,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<Pair<IBinder, Long>, Session>()
    // Retain closing jobs until their finalizers complete so lifecycle drains cannot miss them.
    private val closing = mutableSetOf<Job>()
    private var destroyed = false
    private var draining = false

    init {
        synchronized(registryLock) {
            draining = controllers.any { it.data.state == BaseService.State.Connecting || it.data.state == BaseService.State.Stopping }
            controllers.add(this)
        }
    }

    // A service-mode handoff can leave both service objects bound in this process.
    companion object {
        private val registryLock = Any()
        private val controllers = mutableSetOf<TailscaleSessionController>()
        private val mutation = Mutex()
        private var temporaryOwner: Session? = null
        private fun snapshot() = synchronized(registryLock) { controllers.toList() }

        fun stopAllAdmission() { snapshot().forEach { it.stopAdmission() } }

        fun resumeAllAdmission() {
            val current = snapshot()
            if (current.none { it.data.state == BaseService.State.Connecting || it.data.state == BaseService.State.Stopping }) {
                current.forEach { it.resumeAdmission() }
            }
        }

        suspend fun drainAll() {
            val current = snapshot()
            current.forEach { it.stopAdmission() }
            current.forEach { it.drain() }
        }
    }

    private fun managementState(): BaseService.State {
        val states = snapshot().map { it.data.state }
        return states.firstOrNull { it == BaseService.State.Stopping || it == BaseService.State.Connecting }
            ?: states.firstOrNull { it == BaseService.State.Connected } ?: BaseService.State.Stopped
    }

    private fun runningData(): BaseService.Data? =
        DataStore.baseService?.data?.takeIf { it.state == BaseService.State.Connected }
            ?: snapshot().firstOrNull { it.data.state == BaseService.State.Connected }?.data

    private class Session(val cb: ISagerNetServiceCallback, val id: Long, val profileId: Long, val identity: String, val temporary: Boolean) {
        val key = cb.asBinder() to id
        lateinit var job: Job
        val statuses = Channel<String>(Channel.CONFLATED)
        val requests = mutableMapOf<Long, Job>()
        var lastRequest = 0L
        var accepting = true
        var sequence = 0L
        @Volatile var target: Target? = null
        @Volatile var generation = 0L
        @Volatile var source = "none"
        @Volatile var savedExit = ""
    }

    private data class Target(val owner: BaseService.Data, val instance: BoxInstance, val snapshot: ProxyEntity, val tag: String, val intent: TailscaleReadinessIntent, val temporary: Boolean) {
        val generation get() = intent.generation
    }

    fun open(cb: ISagerNetServiceCallback, sessionId: Long, profileId: Long, identity: String, temporary: Boolean) {
        synchronized(lock) {
            if (destroyed || !registered(cb.asBinder()) || sessionId <= 0 || profileId <= 0 || identity.length > 128) return
            val mayStartTemporary = temporary && !draining && managementState() == BaseService.State.Stopped
            val key = cb.asBinder() to sessionId
            val previous = sessions[key]
            if (previous != null) {
                if (previous.profileId != profileId || previous.identity != identity) return
                if (!temporary || previous.temporary || previous.target != null) return
                retire(previous)
            }
            if (sessions.size >= 8 || sessions.keys.count { it.first == cb.asBinder() } >= 2) return
            val s = Session(cb, sessionId, profileId, identity, temporary)
            val temporaryBusy = mayStartTemporary && synchronized(registryLock) {
                if (temporaryOwner != null) true else {
                    temporaryOwner = s
                    false
                }
            }
            sessions[key] = s
            s.job = scope.launch(start = CoroutineStart.LAZY) {
                supervisorScope {
                    val delivery = launch {
                        for (json in s.statuses) {
                            if (current(s)) runCatching { s.cb.cbTailscaleStatus(s.id, ++s.sequence, json) }
                        }
                    }
                    try {
                        previous?.job?.join()
                        if (previous != null) s.sequence = previous.sequence
                        val profile = read(s)
                        s.savedExit = profile.tailscaleBean!!.exitNode.orEmpty()
                        if (temporaryBusy) {
                            status(s, "error", code = "tailscale:busy")
                            return@supervisorScope
                        }
                        if (temporary && !mayStartTemporary && managementState() != BaseService.State.Connected) {
                            status(s, "error", code = "tailscale:not-stopped")
                            return@supervisorScope
                        }
                        while (!temporary && (managementState() == BaseService.State.Connecting || managementState() == BaseService.State.Stopping)) {
                            status(s, "starting")
                            delay(250)
                        }
                        val running = runningData()
                        val proxy = running?.proxy
                        if (running != null && proxy != null && proxy.isInitialized()) {
                            val endpoint = proxy.config.tailscaleEndpoints[profileId]
                            if (endpoint == null) {
                                status(s, "not-running")
                                awaitCancellation()
                            }
                            val snapshot = proxy.config.trafficMap.values.flatten().first { it.id == profileId }
                            checkRuntime(s, snapshot)
                            observe(s, Target(running, proxy, snapshot, endpoint.tag, proxy.tailscaleReadiness, false))
                        } else if (mayStartTemporary && managementState() == BaseService.State.Stopped) {
                            status(s, "starting")
                            withTimeout(TAILSCALE_LOGIN_TIMEOUT_MS) {
                                TailscaleAccess.whileStopped(::managementState) {
                                    TailscaleSessionInstance(read(s)).runSession { instance ->
                                        val endpoint = instance.config.tailscaleEndpoints.getValue(profileId)
                                        observe(s, Target(data, instance, instance.profile, endpoint.tag, TailscaleReadinessIntent(), true))
                                    }
                                }
                            }
                        } else {
                            status(s, if (managementState() == BaseService.State.Stopped) "not-running" else "starting")
                            awaitCancellation()
                        }
                    } catch (_: TimeoutCancellationException) {
                        status(s, "closed", code = "tailscale:expired")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        status(s, "error", code = "tailscale:unavailable")
                    } finally {
                        withContext(NonCancellable) {
                            synchronized(lock) { s.accepting = false }
                            drainRequests(s)
                            s.target = null
                            s.statuses.close()
                            delivery.join()
                        }
                    }
                }
            }
            s.job.invokeOnCompletion {
                synchronized(registryLock) { if (temporaryOwner === s) temporaryOwner = null }
            }
            s.job.start()
        }
    }

    private fun current(s: Session): Boolean = synchronized(lock) {
        !destroyed && sessions[s.key] === s && registered(s.key.first)
    }

    private fun read(s: Session): ProxyEntity = TailscaleProfileStore.read(s.profileId).also {
        check(it.uuid == s.identity && it.tailscaleBean!!.exitNode.orEmpty().length <= 4096) { "tailscale:conflict" }
    }

    private fun checkRuntime(s: Session, snapshot: ProxyEntity): ProxyEntity = read(s).also {
        check(snapshot.uuid == s.identity && TailscaleProfileStore.sameRuntime(it.tailscaleBean!!, snapshot.tailscaleBean!!)) { "tailscale:conflict" }
    }

    private fun valid(s: Session, t: Target): Boolean = current(s) && s.target === t &&
        if (t.temporary) managementState() == BaseService.State.Stopped
        else t.owner.state == BaseService.State.Connected && t.owner.proxy === t.instance

    private suspend fun observe(s: Session, target: Target) {
        synchronized(lock) {
            check(!draining && current(s)) { "tailscale:closing" }
            s.target = target
            s.generation = target.generation
            s.source = if (target.temporary) "temporary" else "running"
        }
        try {
            val stream = Libcore.observeTailscaleStatus(target.instance.box, target.tag)
            try {
                status(s, "observing", Libcore.tailscaleStatus(target.instance.box, target.tag))
                while (currentCoroutineContext().isActive && valid(s, target)) {
                    val json = stream.next(500)
                    if (json.isNotEmpty()) {
                        s.savedExit = checkRuntime(s, target.snapshot).tailscaleBean!!.exitNode.orEmpty()
                        status(s, "observing", json)
                    }
                }
            } finally {
                stream.close()
            }
        } finally {
            // Requests are children of the owner, not runProbe. Join them before its box closes.
            synchronized(lock) { s.accepting = false }
            withContext(NonCancellable) { drainRequests(s) }
            s.target = null
        }
    }

    private fun status(s: Session, stage: String, node: String? = null, code: String = "") {
        val json = JSONObject().put("version", 1).put("profileId", s.profileId).put("identity", s.identity)
            .put("generation", s.generation).put("source", s.source)
            .put("stage", stage).put("savedExit", s.savedExit).put("node", node?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("errorCode", code).put("message", "").toString()
        s.statuses.trySend(json)
    }

    private fun result(s: Session, requestId: Long, json: JSONObject) {
        if (current(s) && synchronized(lock) { s.requests[requestId]?.isActive == true }) {
            runCatching { s.cb.cbTailscaleResult(s.id, requestId, json.toString()) }
        }
    }

    private fun request(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, block: suspend (Session, Target?) -> Unit) {
        synchronized(lock) {
            val s = sessions[cb.asBinder() to sessionId] ?: return
            if (destroyed || draining || !s.accepting || !registered(cb.asBinder()) || !s.job.isActive || requestId <= s.lastRequest || s.requests.size >= 4) return
            val target = s.target
            if (target != null && !valid(s, target)) return
            s.lastRequest = requestId
            val job = scope.launch(s.job + Dispatchers.IO, start = CoroutineStart.LAZY) { block(s, target) }
            s.requests[requestId] = job
            job.invokeOnCompletion { synchronized(lock) { s.requests.remove(requestId) } }
            job.start()
        }
    }

    fun ping(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, peerId: String, timeoutMs: Int) {
        if (peerId.length > 256) return
        request(cb, sessionId, requestId) { s, t ->
            if (t == null) {
                result(s, requestId, pingResult(true, code = "tailscale:not-running"))
                return@request
            }
            var code = ""
            try {
                checkRuntime(s, t.snapshot)
                val timeout = timeoutMs.coerceIn(1, 10_000)
                val stream = Libcore.startTailscalePeerPing(t.instance.box, t.tag, peerId, timeout)
                try {
                    val deadline = SystemClock.elapsedRealtime() + timeout
                    var samples = 0
                    while (samples < 5 && SystemClock.elapsedRealtime() < deadline && valid(s, t)) {
                        currentCoroutineContext().ensureActive()
                        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 500).toInt()
                        val json = stream.next(remaining)
                        if (json.isNotEmpty()) {
                            samples++
                            result(s, requestId, pingResult(false, JSONObject(json)))
                        }
                    }
                    if (samples < 5 && SystemClock.elapsedRealtime() >= deadline) code = "tailscale:ping-timeout"
                } finally {
                    stream.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message != "EOF") code = "tailscale:ping-failed"
            }
            result(s, requestId, pingResult(true, code = code))
        }
    }

    private fun pingResult(done: Boolean, sample: JSONObject? = null, code: String = "") = JSONObject()
        .put("kind", "ping").put("done", done).put("sample", sample ?: JSONObject.NULL).put("errorCode", code).put("message", "")

    fun setExit(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, peerId: String, expectedExit: String) {
        if (peerId.length > 256 || expectedExit.length > 4096) return
        request(cb, sessionId, requestId) { s, t ->
            if (t == null) {
                result(s, requestId, JSONObject().put("kind", "exit").put("outcome", "failed-unchanged")
                    .put("savedExit", s.savedExit).put("errorCode", "tailscale:not-running").put("message", ""))
                return@request
            }
            val outcome = mutation.withLock {
                currentCoroutineContext().ensureActive()
                if (!valid(s, t)) return@withLock TailscaleExitResult("cancelled-before-apply", s.savedExit)
                val profile = try {
                    checkRuntime(s, t.snapshot).also {
                        check(it.tailscaleBean!!.exitNode.orEmpty() == expectedExit) { "tailscale:conflict" }
                    }
                } catch (_: Exception) {
                    return@withLock TailscaleExitResult("conflict", s.savedExit, "tailscale:conflict")
                }
                val oldIntent = t.intent.get(s.profileId, t.snapshot.tailscaleBean!!.exitNode.orEmpty().isNotEmpty())
                // This is the application admission point shared with stop and owner cleanup.
                val admitted = synchronized(lock) { !draining && valid(s, t) && s.requests[requestId]?.isActive == true }
                if (!admitted) return@withLock TailscaleExitResult("cancelled-before-apply", s.savedExit)
                finalizeTailscaleExit(
                    profile.tailscaleBean!!.exitNode.orEmpty(),
                    begin = {
                        val change = Libcore.setTailscaleExitNode(t.instance.box, t.tag, peerId)
                        object : TailscaleExitChange {
                            override fun savedValue() = change.savedValue()
                            override fun commit() { change.commit() }
                            override fun rollback() { change.rollback() }
                        }
                    },
                    save = { value ->
                        TailscaleProfileStore.compareAndSetRuntimeExit(s.profileId, s.identity, expectedExit, value, t.snapshot.tailscaleBean)
                        s.savedExit = value
                    },
                    applied = { t.intent.set(s.profileId, it) },
                    rolledBack = { t.intent.set(s.profileId, oldIntent) },
                )
            }
            // Refresh both sides after success, conflict or divergence, without restarting anything.
            runCatching {
                s.savedExit = read(s).tailscaleBean!!.exitNode.orEmpty()
                val actual = Libcore.tailscaleStatus(t.instance.box, t.tag)
                if (outcome.outcome == "diverged") {
                    t.intent.set(s.profileId, !JSONObject(actual).isNull("currentExit"))
                }
                val observers = synchronized(lock) {
                    sessions.values.filter { it.profileId == s.profileId && it.identity == s.identity && it.target?.instance === t.instance }
                }
                for (observer in observers) {
                    observer.savedExit = s.savedExit
                    status(observer, "observing", actual)
                }
            }
            val name = if (outcome.outcome == "applied-and-saved" && !valid(s, t)) "saved-for-next-start" else outcome.outcome
            result(s, requestId, JSONObject().put("kind", "exit").put("outcome", name)
                .put("savedExit", s.savedExit).put("errorCode", outcome.errorCode).put("message", ""))
        }
    }

    fun cancelRequest(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long) {
        synchronized(lock) { sessions[cb.asBinder() to sessionId]?.requests?.get(requestId)?.cancel() }
    }

    fun closeSession(cb: ISagerNetServiceCallback, sessionId: Long) {
        synchronized(lock) { sessions[cb.asBinder() to sessionId]?.let(::retire) }
    }

    fun releaseOwner(owner: IBinder) {
        synchronized(lock) { sessions.values.filter { it.key.first == owner }.forEach(::retire) }
    }

    private fun retire(s: Session) {
        s.accepting = false
        sessions.remove(s.key)
        closing.add(s.job)
        s.job.invokeOnCompletion { synchronized(lock) { closing.remove(s.job) } }
        s.job.cancel()
    }

    private suspend fun drainRequests(s: Session) {
        val jobs = synchronized(lock) { s.requests.values.toList().also { jobs -> jobs.forEach { it.cancel() } } }
        jobs.joinAll()
    }

    fun stopAdmission() {
        synchronized(lock) { draining = true }
    }

    fun resumeAdmission() {
        synchronized(lock) { if (!destroyed) draining = false }
    }

    suspend fun drain() = withContext(NonCancellable + Dispatchers.IO) {
        val jobs = synchronized(lock) {
            draining = true
            sessions.values.toList().forEach(::retire)
            closing.toList()
        }
        jobs.joinAll()
    }

    fun destroy() {
        synchronized(lock) { destroyed = true; draining = true }
        scope.launch {
            try {
                drain()
            } finally {
                synchronized(registryLock) { controllers.remove(this@TailscaleSessionController) }
                scope.cancel()
            }
        }
    }
}
