package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONArray

data class TailscalePeer(val name: String, val dnsName: String, val ips: List<String>, val online: Boolean, val exitNode: Boolean)

fun parseTailscalePeers(json: String): List<TailscalePeer> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val peer = array.getJSONObject(index)
        val ips = peer.getJSONArray("ips")
        TailscalePeer(
            name = peer.getString("name"),
            dnsName = peer.getString("dnsName"),
            ips = (0 until ips.length()).map { ips.getString(it) },
            online = peer.getBoolean("online"),
            exitNode = peer.getBoolean("exitNode"),
        )
    }
}

// Time a user gets to finish an interactive login in the browser while the probe node waits.
const val TAILSCALE_LOGIN_TIMEOUT_MS = 180_000

// A short-lived node for a Tailscale profile the service is not running, used to list its
// peers. It reuses the profile's saved identity, which is free while the service does not.
class TailscalePeersInstance(profile: ProxyEntity) : BoxInstance(profile) {

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    // [onLoginRequired] gets the interactive login URL of a node without an auth key; returning
    // true keeps the node up while the user signs in, until the login completes or
    // TAILSCALE_LOGIN_TIMEOUT_MS passes.
    suspend fun listPeers(onLoginRequired: suspend (url: String) -> Boolean = { false }): List<TailscalePeer> = use {
        init()
        launch()
        // The config may also carry a group's Tailscale front or landing node; pick this profile's.
        val endpoint = config.tailscaleEndpoints.getValue(profile.id)
        try {
            // Only the login matters here; the configured exit node may be the one being replaced.
            Libcore.tailscaleWaitReady(box, endpoint.tag, false, TAILSCALE_READY_TIMEOUT_MS)
        } catch (e: Exception) {
            val url = Libcore.tailscaleAuthURL(box, endpoint.tag)
            if (url.isEmpty() || !onLoginRequired(url)) throw e
            Libcore.tailscaleWaitReady(box, endpoint.tag, false, TAILSCALE_LOGIN_TIMEOUT_MS)
        }
        parseTailscalePeers(Libcore.tailscalePeers(box, endpoint.tag))
    }
}
