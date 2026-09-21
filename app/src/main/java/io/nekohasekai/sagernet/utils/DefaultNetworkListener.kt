package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    private val networkActor = GlobalScope.actor<NetworkMessage>(
        Dispatchers.Unconfined,
        capacity = Channel.UNLIMITED,
    ) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        for (message in channel) {
            when (message) {
                is NetworkMessage.Start -> {
                    if (listeners.isEmpty()) register()
                    listeners[message.key] = message.listener
                    if (network != null) {
                        message.listener(network)
                    } else if (fallback) {
                        message.listener(SagerNet.connectivity.activeNetwork)
                    }
                }

                is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                    listeners.remove(message.key) != null && listeners.isEmpty()
                ) {
                    network = null
                    unregister()
                }

                is NetworkMessage.Put -> {
                    network = message.network
                    listeners.values.forEach { it(network) }
                }

                is NetworkMessage.Update -> if (network == message.network) {
                    listeners.values.forEach {
                        it(
                            network,
                        )
                    }
                }

                is NetworkMessage.Lost -> if (network == message.network) {
                    network = null
                    listeners.values.forEach { it(null) }
                }
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) = networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread; offer events non-blocking (trySend) so we never
    // park the framework callback thread on the actor. The actor is UNLIMITED, so trySend only
    // fails if the actor channel is closed (actor coroutine died) - log that rather than
    // silently dropping, since it means network-change handling has stopped working.
    private fun offer(message: NetworkMessage) {
        val result = networkActor.trySend(message)
        if (result.isFailure) {
            Logs.w("DefaultNetworkListener: dropped ${message.javaClass.simpleName} (actor closed?)")
        }
    }

    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            offer(NetworkMessage.Put(network))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { // it's a good idea to refresh capabilities
            offer(NetworkMessage.Update(network))
        }

        override fun onLost(network: Network) {
            offer(NetworkMessage.Lost(network))
        }
    }

    private var fallback = false
    private val callbackRegistration = NetworkCallbackRegistration()
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        if (callbackRegistration.requiresFallback) {
            callbackRegistration.unregister {
                SagerNet.connectivity.unregisterNetworkCallback(Callback)
            }.onFailure {
                Logs.w("DefaultNetworkListener: retry unregister failed", it)
            }
        }
        if (callbackRegistration.requiresFallback) {
            fallback = true
            return
        }
        fallback = false
        callbackRegistration.register {
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(request, Callback, mainHandler)

                Build.VERSION.SDK_INT >= 28 ->
                    // Request the best non-VPN network instead of listening to all matches.
                    SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)

                Build.VERSION.SDK_INT >= 26 ->
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)

                Build.VERSION.SDK_INT >= 24 ->
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)

                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
        }.onFailure {
            Logs.w(it)
            fallback = true
        }
    }

    private fun unregister() {
        callbackRegistration.unregister {
            SagerNet.connectivity.unregisterNetworkCallback(Callback)
        }.onFailure {
            fallback = true
            Logs.w("DefaultNetworkListener: failed to unregister network callback", it)
        }
    }
}
