package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.PROFILE_NOT_RUNNING
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.chainHops
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean

class UrlTest(private val service: ISagerNetService? = null) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int {
        // A Tailscale node has one identity, so a profile the running service already drives is
        // measured through the service instead of starting a second node next to it.
        if (service != null && DataStore.serviceState.connected && profile.usesTailscale()) {
            try {
                return service.urlTestProfile(profile.id)
            } catch (e: IllegalStateException) {
                if (e.message?.contains(PROFILE_NOT_RUNNING) != true) throw e
            }
        }
        return TestInstance(profile, link, timeout).doTest()
    }

    private fun ProxyEntity.usesTailscale() = try {
        chainHops(this).any { it.requireBean() is TailscaleBean }
    } catch (_: IllegalArgumentException) {
        false
    }
}
