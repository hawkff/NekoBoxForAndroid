package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.PROFILE_NOT_RUNNING
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray

// A Tailscale node has one saved identity, so at most one core may run it. The service is the
// authority on which nodes it runs; everything else goes through here before starting a probe.
object TailscaleAccess {

    // Probes (URL tests, peer listings) run one at a time: two tests of chains sharing a node
    // would otherwise start it twice.
    val probeLock = Mutex()

    // The service handle while the service is starting or running, null when it is stopped.
    // Binding is asynchronous, so a started service without a binder yet is waited for briefly
    // rather than mistaken for a stopped one.
    suspend fun service(binder: () -> ISagerNetService?): ISagerNetService? {
        if (!DataStore.serviceState.started) return null
        repeat(30) {
            binder()?.let { return it }
            delay(100)
        }
        error(app.getString(R.string.tailscale_service_starting))
    }

    // Runs [viaService] when the service is up; PROFILE_NOT_RUNNING means the profile is not
    // part of its configuration, and the probe may go ahead as long as none of [nodes] is.
    suspend fun <T> run(binder: () -> ISagerNetService?, nodes: Collection<Long>, viaService: (ISagerNetService) -> T, probe: suspend () -> T): T {
        val service = service(binder)
        if (service != null) {
            try {
                return viaService(service)
            } catch (e: IllegalStateException) {
                if (e.message?.contains(PROFILE_NOT_RUNNING) != true) throw e
            }
            val running = JSONArray(service.runningTailscaleProfiles())
            val busy = (0 until running.length()).map { running.getLong(it) }
            if (nodes.any { it in busy }) error(app.getString(R.string.tailscale_node_in_use))
        }
        return probe()
    }
}
