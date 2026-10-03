package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.PROFILE_NOT_RUNNING
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

// A Tailscale node has one saved identity, so at most one core may run it. The service is the
// authority on which nodes it runs; everything else goes through here before starting a probe.
object TailscaleAccess {

    // Probes (URL tests, peer listings) run one at a time: two tests of chains sharing a node
    // would otherwise start it twice.
    private val probeLock = Mutex()

    private const val CORE_NOT_STARTED = "core not started"

    // The service handle while the service is starting or running, null when it is stopped.
    // The binder's own state decides: the cached state is stale in a freshly started UI
    // process, and binding is asynchronous, so the binder is waited for briefly.
    suspend fun service(binder: () -> ISagerNetService?): ISagerNetService? {
        repeat(20) {
            binder()?.let { service ->
                return service.takeIf { BaseService.State.values()[it.state].ownsTailscaleState }
            }
            delay(100)
        }
        if (DataStore.serviceState.ownsTailscaleState) error(app.getString(R.string.tailscale_service_starting))
        // No binder/cached Idle is not proof of availability. The probe must acquire OS leases.
        return null
    }

    // Runs [viaService] when the service is up, retrying while its core is still initializing.
    // PROFILE_NOT_RUNNING means the profile is not part of its configuration, and the probe may
    // go ahead as long as none of [nodes] is.
    suspend fun <T> run(binder: () -> ISagerNetService?, nodes: Collection<Long>, viaService: (ISagerNetService) -> T, probe: suspend () -> T): T = probeLock.withLock {
        // Recheck only after serialization: a queued probe's previous decision can be stale.
        val service = service(binder)
        if (service != null && BaseService.State.values()[service.state] == BaseService.State.Stopping) {
            error(app.getString(R.string.tailscale_service_starting))
        }
        if (service != null) {
            try {
                return@withLock retryWhileStarting(service) { viaService(service) }
            } catch (e: IllegalStateException) {
                if (e.message?.contains(PROFILE_NOT_RUNNING) != true) throw e
            }
            val running = JSONArray(retryWhileStarting(service) { service.runningTailscaleProfiles() })
            val busy = (0 until running.length()).map { running.getLong(it) }
            if (nodes.any { it in busy }) error(app.getString(R.string.tailscale_node_in_use))
        }
        probe()
    }

    private suspend fun <T> retryWhileStarting(service: ISagerNetService, block: () -> T): T {
        repeat(TAILSCALE_READY_TIMEOUT_MS / 500) {
            try {
                return block()
            } catch (e: IllegalStateException) {
                if (e.message?.contains(CORE_NOT_STARTED) != true) throw e
                if (!BaseService.State.values()[service.state].ownsTailscaleState) throw e
            }
            delay(500)
        }
        error(app.getString(R.string.tailscale_service_starting))
    }
}
