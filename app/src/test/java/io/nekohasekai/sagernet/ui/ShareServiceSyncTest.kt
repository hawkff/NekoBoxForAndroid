package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.ui.ShareServiceSync.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each test pins one interleaving of the share screen against the service lifecycle. */
class ShareServiceSyncTest {

    private val sync = ShareServiceSync()

    private fun toggleOn(state: State) = sync.onChange(state, startIfStopped = true, allowAccess = true)
    private fun toggleOff(state: State) = sync.onChange(state, startIfStopped = false, allowAccess = false)
    private fun regenerate(state: State) = sync.onChange(state, startIfStopped = false, allowAccess = true)

    @Test
    fun runningService_reloads() {
        assertEquals(Action.Reload, toggleOn(State.Connected))
        assertEquals(Action.Reload, regenerate(State.Connecting))
    }

    @Test
    fun stoppedService_launchesOnceAndReloadsOnlyWhenChangedSinceLaunch() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.None, sync.onState(State.Connecting, allowAccess = true))
        assertEquals(Action.None, sync.onState(State.Connected, allowAccess = true))

        assertEquals(Action.None, toggleOff(State.Stopped))
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.None, regenerate(State.Stopped))
        assertEquals(Action.Reload, sync.onState(State.Connected, allowAccess = true))
    }

    @Test
    fun reloadWhileConnecting_needsNoSecondReloadOnConnected() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.Reload, regenerate(State.Connecting))
        assertEquals(Action.None, sync.onState(State.Connected, allowAccess = true))
    }

    @Test
    fun failedStart_isNotRetriedAndNextToggleLaunchesAgain() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        assertEquals(Action.None, sync.onState(State.Stopped, allowAccess = true))
        assertEquals(Action.None, toggleOff(State.Stopped))
        assertEquals(Action.Launch, toggleOn(State.Stopped))
    }

    @Test
    fun deniedPermission_letsNextToggleLaunchAgain() {
        assertEquals(Action.Launch, toggleOn(State.Stopped))
        sync.onLaunchFailed()
        assertEquals(Action.None, toggleOff(State.Stopped))
        assertEquals(Action.Launch, toggleOn(State.Stopped))
    }

    @Test
    fun toggleDuringShutdown_launchesOnceAtStopped() {
        assertEquals(Action.None, toggleOn(State.Stopping))
        assertEquals(Action.Launch, sync.onState(State.Stopped, allowAccess = true))
        assertEquals(Action.None, sync.onState(State.Stopped, allowAccess = true))
    }

    @Test
    fun toggleDuringShutdownThenOff_doesNotLaunch() {
        assertEquals(Action.None, toggleOn(State.Stopping))
        assertEquals(Action.None, toggleOff(State.Stopping))
        assertEquals(Action.None, sync.onState(State.Stopped, allowAccess = false))
    }
}
