package io.nekohasekai.sagernet.fmt.tailscale

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TailscaleStateLease
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import moe.matsuri.nb4a.SingBoxOptions
import java.io.File
import java.util.UUID

// Per-profile node identity. Relative to the core working directory (no_backup),
// keyed by profile id so renames keep the node and "reset identity" can delete it.
fun tailscaleStateDirectory(profileId: Long) = "tailscale/$profileId"

// The core runs with no_backup as its working directory (libcore InitCore).
fun tailscaleStateFile(profileId: Long) = File(SagerNet.application.noBackupFilesDir, tailscaleStateDirectory(profileId))

internal fun acquireTailscaleState(ids: Collection<Long>) = TailscaleStateLease.acquire(
    File(SagerNet.application.noBackupFilesDir, "tailscale-locks"),
    ids,
)

internal fun tailscaleStateIds() = File(SagerNet.application.noBackupFilesDir, "tailscale")
    .listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }

internal fun retainsTailscaleIdentity(previous: ProxyEntity?, restored: ProxyEntity): Boolean =
    previous?.type == ProxyEntity.TYPE_TAILSCALE && restored.type == ProxyEntity.TYPE_TAILSCALE &&
        previous.uuid.isNotBlank() && previous.uuid == restored.uuid && previous.requireBean() == restored.requireBean()

// Adding provenance to a legacy local node does not change or remove its credentials.
internal fun profilesForBackup(): List<ProxyEntity> {
    var profiles = emptyList<ProxyEntity>()
    SagerDatabase.instance.runInTransaction {
        profiles = SagerDatabase.proxyDao.getAll()
        profiles.filter { it.type == ProxyEntity.TYPE_TAILSCALE && it.uuid.isBlank() }.forEach {
            it.uuid = UUID.randomUUID().toString()
            SagerDatabase.proxyDao.setTailscaleMarker(it.id, it.uuid)
        }
    }
    return profiles
}

internal fun resetTailscaleIdentity(profileId: Long): String {
    check(!DataStore.serviceState.ownsTailscaleState) { "Stop the service before resetting the Tailscale identity." }
    return acquireTailscaleState(listOf(profileId)).use {
        val marker = UUID.randomUUID().toString()
        SagerDatabase.instance.runInTransaction {
            check(SagerDatabase.proxyDao.getById(profileId)?.type == ProxyEntity.TYPE_TAILSCALE) { "Tailscale profile no longer exists" }
            check(tailscaleStateFile(profileId).deleteRecursively()) { "Cannot remove Tailscale identity" }
            SagerDatabase.proxyDao.setTailscaleMarker(profileId, marker)
        }
        marker
    }
}

// A cached service state is only a fast refusal; the per-node OS lock is authoritative.
fun pruneTailscaleState(keep: Set<Long>? = null) {
    if (DataStore.serviceState.ownsTailscaleState) return
    val directories = File(SagerNet.application.noBackupFilesDir, "tailscale").listFiles() ?: return
    for (directory in directories) {
        val id = directory.name.toLongOrNull()
        if (id == null) {
            directory.deleteRecursively()
            continue
        }
        val lease = try {
            acquireTailscaleState(listOf(id))
        } catch (_: IllegalStateException) {
            continue // An active probe/service will prune again after closing.
        }
        lease.use {
            val live = keep?.contains(id) ?: (SagerDatabase.proxyDao.getById(id)?.type == ProxyEntity.TYPE_TAILSCALE)
            if (!live) directory.deleteRecursively()
        }
    }
}

fun buildSingBoxEndpointTailscaleBean(bean: TailscaleBean, profileId: Long): SingBoxOptions.Endpoint_TailscaleOptions = SingBoxOptions.Endpoint_TailscaleOptions().apply {
    type = "tailscale"
    state_directory = tailscaleStateDirectory(profileId)
    if (bean.authKey!!.isNotBlank()) auth_key = bean.authKey
    if (bean.controlUrl!!.isNotBlank()) control_url = bean.controlUrl
    if (bean.hostname!!.isNotBlank()) hostname = bean.hostname
    if (bean.exitNode!!.isNotBlank()) {
        exit_node = bean.exitNode
        if (bean.exitNodeAllowLanAccess == true) exit_node_allow_lan_access = true
    }
    if (bean.acceptRoutes == true) accept_routes = true
    if (bean.onlyTcp443 == true) only_tcp_443 = true
}
