package io.nekohasekai.sagernet.fmt.tailscale

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TailscaleStateLease
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// Per-profile node identity. Relative to the core working directory (no_backup),
// keyed by profile id so renames keep the node and "reset identity" can delete it.
fun tailscaleStateDirectory(profileId: Long) = "tailscale/$profileId"

// The core runs with no_backup as its working directory (libcore InitCore).
fun tailscaleStateFile(profileId: Long) = File(SagerNet.application.noBackupFilesDir, tailscaleStateDirectory(profileId))

internal fun acquireTailscaleState(ids: Collection<Long>): Closeable {
    val lease = TailscaleStateLease.acquire(File(SagerNet.application.noBackupFilesDir, "tailscale-locks"), ids)
    try {
        ids.distinct().forEach { recoverTailscaleRestore(it) }
        return lease
    } catch (e: Throwable) {
        lease.close()
        throw e
    }
}

internal fun tailscaleRestoreDirectory(profileId: Long) = File(SagerNet.application.noBackupFilesDir, "tailscale-restore/$profileId")

internal fun tailscaleStateIds(): Set<Long> = listOf("tailscale", "tailscale-restore").flatMap { name ->
    File(SagerNet.application.noBackupFilesDir, name).listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }
}.toSet()

// Called only under the node lease. Metadata is complete before the atomic directory rename;
// recovery uses Room's committed marker, not a separate transaction-completion flag.
internal fun stageTailscaleRestore(profileId: Long, originalMarker: String?, replacementMarker: String?) {
    check(originalMarker != replacementMarker) { "Tailscale restore requires distinct provenance" }
    val state = tailscaleStateFile(profileId)
    if (!state.exists()) return
    val staging = tailscaleRestoreDirectory(profileId)
    staging.parentFile!!.mkdirs()
    check(staging.mkdir()) { "Cannot stage Tailscale identity at $staging" }
    val metadata = JSONObject().apply {
        put("version", 1)
        put("originalMarker", originalMarker ?: JSONObject.NULL)
        put("replacementMarker", replacementMarker ?: JSONObject.NULL)
    }
    FileOutputStream(File(staging, "metadata.json")).use {
        it.write(metadata.toString().toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    check(state.renameTo(File(staging, "state"))) { "Cannot stage Tailscale identity at $staging" }
}

// Never overwrite an unexpected active directory, or discard state whose provenance is unclear.
// A failed rollback rename leaves both the metadata and original bytes available for retry.
internal fun recoverTailscaleRestore(profileId: Long) {
    val staging = tailscaleRestoreDirectory(profileId)
    if (!staging.exists()) return
    val failure = "Tailscale identity recovery required at $staging; preserved state must not be removed."
    check(staging.isDirectory && staging.listFiles()?.all { it.name == "metadata.json" || it.name == "state" } == true) { failure }
    val metadata = try {
        JSONObject(File(staging, "metadata.json").readText())
    } catch (e: Exception) {
        throw IllegalStateException(failure, e)
    }
    check(metadata.opt("version") == 1) { failure }
    fun marker(key: String): String? {
        val value = metadata.opt(key)
        check(value is String || value === JSONObject.NULL) { failure }
        return value as? String
    }
    val original = marker("originalMarker")
    val replacement = marker("replacementMarker")
    check(original != replacement) { failure }
    val current = SagerDatabase.proxyDao.getById(profileId)?.takeIf { it.type == ProxyEntity.TYPE_TAILSCALE }?.uuid
    val state = tailscaleStateFile(profileId)
    val retired = File(staging, "state")
    check(!state.exists() || !retired.exists()) { failure }
    when {
        current == replacement -> {
            // A committed foreign profile must never see the retired node's credentials.
            check(!state.exists()) { failure }
            check(retired.deleteRecursively()) { failure }
        }

        current == original -> {
            if (retired.exists()) {
                state.parentFile!!.mkdirs()
                check(retired.renameTo(state)) { failure }
            } else {
                check(state.exists()) { failure }
            }
        }

        else -> error(failure)
    }
    check(staging.deleteRecursively()) { failure }
}

internal fun retainsTailscaleIdentity(previous: ProxyEntity?, restored: ProxyEntity): Boolean = previous?.type == ProxyEntity.TYPE_TAILSCALE && restored.type == ProxyEntity.TYPE_TAILSCALE &&
    previous.uuid.isNotBlank() && previous.uuid == restored.uuid && previous.requireBean() == restored.requireBean()

// Adding provenance to a legacy local node does not change or remove its credentials.
internal fun profilesForBackup(): List<ProxyEntity> {
    var profiles = emptyList<ProxyEntity>()
    SagerDatabase.instance.runInTransaction {
        profiles = SagerDatabase.proxyDao.getAll()
        profiles.filter { it.type == ProxyEntity.TYPE_TAILSCALE && it.uuid.isBlank() }.forEach {
            // Do not change legacy provenance while an interrupted restore still refers to it.
            if (tailscaleRestoreDirectory(it.id).exists()) acquireTailscaleState(listOf(it.id)).close()
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
fun pruneTailscaleState(keep: Set<Long>? = null, profileIds: Collection<Long>? = null) {
    if (DataStore.serviceState.ownsTailscaleState) return
    val candidates = profileIds?.toSet() ?: run {
        val live = keep ?: SagerDatabase.proxyDao.getIdsByType(ProxyEntity.TYPE_TAILSCALE).toSet()
        File(SagerNet.application.noBackupFilesDir, "tailscale").listFiles().orEmpty()
            .filter { it.name.toLongOrNull() == null }.forEach { it.deleteRecursively() }
        tailscaleStateIds().filter { it !in live || tailscaleRestoreDirectory(it).exists() }
    }
    for (id in candidates) {
        val directory = tailscaleStateFile(id)
        if (!directory.exists() && !tailscaleRestoreDirectory(id).exists()) continue
        val lease = try {
            acquireTailscaleState(listOf(id))
        } catch (_: IllegalStateException) {
            continue // Busy or unrecoverable nodes must keep their state.
        }
        lease.use {
            // A profile can be created/restored after the candidate scan; recheck under its lock.
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
