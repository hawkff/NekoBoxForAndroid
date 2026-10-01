package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService

/**
 * Decides how a change made on the share screen reaches the service. A running service reloads
 * at once. A stopped one is launched when sharing is switched on; the launch reads the store by
 * itself, so only a change made after it needs a reload once the service connects. A launch
 * requested while the service is still stopping waits for Stopped and happens once. A launch
 * that ends in Stopped failed and is not retried; the next toggle may launch again. Pure logic
 * so the interleavings are testable on the JVM; the activity calls it on the main thread.
 */
class ShareServiceSync {
    enum class Action { None, Reload, Launch }

    private var version = 0
    private var launchedVersion = -1
    private var deferred = false

    /** A setting changed; [startIfStopped] when the change needs a running service. */
    fun onChange(state: BaseService.State, startIfStopped: Boolean, allowAccess: Boolean): Action {
        version++
        return when {
            // The reload reads the whole store, so a launch in flight needs no follow-up.
            state.canStop -> {
                launchedVersion = -1
                Action.Reload
            }

            !startIfStopped || !allowAccess -> Action.None

            state == BaseService.State.Stopping -> {
                deferred = true
                Action.None
            }

            launchedVersion < 0 -> launch()

            else -> Action.None
        }
    }

    /** The service reported [state]. */
    fun onState(state: BaseService.State, allowAccess: Boolean): Action = when (state) {
        BaseService.State.Connected -> {
            val changedSinceLaunch = launchedVersion in 0 until version
            launchedVersion = -1
            if (changedSinceLaunch) Action.Reload else Action.None
        }

        BaseService.State.Stopped -> {
            launchedVersion = -1
            val launch = deferred && allowAccess
            deferred = false
            if (launch) launch() else Action.None
        }

        else -> Action.None
    }

    /** The launch never reached the service, for instance because the VPN permission was denied. */
    fun onLaunchFailed() {
        launchedVersion = -1
    }

    private fun launch(): Action {
        launchedVersion = version
        return Action.Launch
    }
}
