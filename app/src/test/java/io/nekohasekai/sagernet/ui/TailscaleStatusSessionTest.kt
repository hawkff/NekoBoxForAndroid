package io.nekohasekai.sagernet.ui

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TailscaleStatusSessionTest {
    private class Transport : TailscaleStatusTransport {
        lateinit var listener: TailscaleStatusTransport.Listener
        val calls = mutableListOf<String>()
        var session = 0L
        var request = 0L
        var expectedExit = ""
        override fun connect(listener: TailscaleStatusTransport.Listener) { this.listener = listener; calls += "connect" }
        override fun disconnect() { calls += "disconnect" }
        override fun observe(sessionId: Long, profileId: Long, identity: String) { session = sessionId; calls += "observe" }
        override fun start(sessionId: Long, profileId: Long, identity: String) { calls += "start" }
        override fun close(sessionId: Long) { calls += "close" }
        override fun ping(sessionId: Long, requestId: Long, peerId: String) { request = requestId; calls += "ping:$peerId" }
        override fun selectExit(sessionId: Long, requestId: Long, peerId: String, expectedExit: String) {
            request = requestId; this.expectedExit = expectedExit; calls += "exit:$peerId"
        }
        override fun cancel(sessionId: Long, requestId: Long) { calls += "cancel" }
    }

    private fun setup(): Pair<TailscaleStatusSession, Transport> {
        val transport = Transport()
        val session = TailscaleStatusSession(transport, 42, "identity")
        session.foreground()
        transport.listener.connected(true)
        return session to transport
    }

    @Test fun passiveOpenAndRepeatedResumeOnlyObserve() {
        val (session, transport) = setup()
        session.foreground()
        assertEquals(listOf("connect", "observe"), transport.calls)
        assertTrue(session.state.value.canCheck)
    }

    @Test fun checkIsExplicitStoppedOnlyAndNeverRepeatedOnResume() {
        val (session, transport) = setup()
        transport.listener.serviceState(false)
        session.check()
        assertFalse(transport.calls.contains("start"))
        transport.listener.serviceState(true)
        session.check()
        session.check()
        session.foreground()
        assertEquals(1, transport.calls.count { it == "start" })
    }

    @Test fun rotationKeepsSessionButOrdinaryBackgroundClosesIt() {
        val (session, transport) = setup()
        session.check()
        val id = transport.session
        session.background(true)
        session.foreground()
        assertEquals(id, transport.session)
        assertFalse(transport.calls.contains("close"))
        session.background(false)
        assertEquals(listOf("close", "disconnect"), transport.calls.takeLast(2))
    }

    @Test fun explicitBrowserHandoffKeepsSessionUntilServerClosesIt() {
        val (session, transport) = setup()
        session.check()
        transport.listener.status(transport.session, 1, status(source = "temporary"))
        assertNotNull(session.openLogin())
        session.background(false)
        assertFalse(transport.calls.contains("close"))
        transport.listener.status(transport.session, 2, status(stage = "closed", source = "temporary"))
        assertFalse(session.state.value.temporary)
        assertNull(session.openLogin())
        session.foreground()
        assertEquals(1, transport.calls.count { it == "start" })
        session.background(false)
        assertTrue(transport.calls.contains("close"))
    }

    @Test fun browserReturnDoesNotGrantUnrelatedBackgroundPermission() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status(source = "temporary"))
        session.openLogin()
        session.background(false)
        session.foreground()
        session.background(false)
        assertTrue(transport.calls.contains("close"))
    }

    @Test fun reconnectOnlyObservesAndRejectsOldSessionResults() {
        val (session, transport) = setup()
        session.check()
        val old = transport.session
        transport.listener.disconnected()
        transport.listener.connected(true)
        transport.listener.status(old, 50, status())
        assertNull(session.state.value.status)
        assertEquals(1, transport.calls.count { it == "start" })
        assertEquals(2, transport.calls.count { it == "observe" })
    }

    @Test fun staleSequenceGenerationIdentityAndProfileAreIgnored() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 5, status(generation = 2))
        for ((sequence, json) in listOf(
            4L to status(savedExit = "stale"),
            6L to status(generation = 1, savedExit = "stale"),
            7L to JSONObject(status()).put("identity", "other").toString(),
            8L to JSONObject(status()).put("profileId", 99).toString(),
        )) transport.listener.status(transport.session, sequence, json)
        assertEquals("100.64.0.2", session.state.value.status?.savedExit)
        assertEquals(2L, session.state.value.status?.generation)
    }

    @Test fun exitUsesStableIdAndExpectedSavedSelectorAndWaitsForAcknowledgement() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        session.selectExit("peer-stable", "stale")
        assertEquals(0L, transport.request)
        session.selectExit("peer-stable", "100.64.0.2")
        assertEquals("exit:peer-stable", transport.calls.last())
        assertEquals("100.64.0.2", transport.expectedExit)
        assertEquals("exit", session.state.value.pending)
        assertNull(session.state.value.exitOutcome)
        transport.listener.result(transport.session, transport.request + 1, exitResult("applied-and-saved"))
        assertNull(session.state.value.exitOutcome)
        transport.listener.result(transport.session, transport.request, exitResult("conflict"))
        assertEquals("conflict", session.state.value.exitOutcome)
        assertNull(session.state.value.pending)
    }

    @Test fun newNodeGenerationRejectsOldMutationAcknowledgement() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status(generation = 1))
        session.selectExit("peer-stable", "100.64.0.2")
        val oldRequest = transport.request
        transport.listener.status(transport.session, 2, status(generation = 2))
        transport.listener.result(transport.session, oldRequest, exitResult("applied-and-saved"))
        assertNull(session.state.value.exitOutcome)
        assertNull(session.state.value.pending)
    }

    @Test fun explicitCancelClosesAndReobservesWithoutRestarting() {
        val (session, transport) = setup()
        session.check()
        session.cancelSession()
        transport.listener.connected(true)
        assertEquals(1, transport.calls.count { it == "start" })
        assertEquals(2, transport.calls.count { it == "observe" })
        assertFalse(session.state.value.temporary)
    }

    @Test fun cancelExitWaitsForFinalizerAndNoneIsExplicit() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        session.selectExit("", "100.64.0.2")
        assertEquals("exit:", transport.calls.last())
        session.cancelRequest()
        assertEquals("exit", session.state.value.pending)
        transport.listener.result(transport.session, transport.request, exitResult("cancelled-before-apply"))
        assertNull(session.state.value.pending)
    }

    @Test fun pingOnlyRunsOnRequestIsBoundedAndRejectsOtherPeers() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        assertFalse(transport.calls.any { it.startsWith("ping") })
        session.ping("peer-stable")
        transport.listener.result(transport.session, transport.request, pingResult(1, "other"))
        assertTrue(session.state.value.samples.isEmpty())
        for (i in 1..8) transport.listener.result(transport.session, transport.request, pingResult(i))
        assertEquals(5, session.state.value.samples.size)
        assertEquals("peer-relay", session.state.value.samples.last().path)
        transport.listener.result(transport.session, transport.request, pingResult(9, done = true))
        assertNull(session.state.value.pending)
    }

    @Test fun processRecreationDoesNotRestoreConsentOrBrowserHandoff() {
        val (_, transport) = setup()
        val newSession = TailscaleStatusSession(transport, 42, "identity")
        newSession.foreground()
        transport.listener.connected(true)
        assertFalse(newSession.state.value.temporary)
        assertNull(newSession.openLogin())
        assertFalse(transport.calls.contains("start"))
    }

    companion object {
        internal fun status(stage: String = "observing", source: String = "running", generation: Long = 1, savedExit: String = "100.64.0.2"): String = """
            {"version":1,"profileId":42,"identity":"identity","generation":$generation,
             "source":"$source","stage":"$stage","savedExit":"$savedExit","errorCode":"","message":"",
             "node":{"backendState":"Running","needsLogin":true,"needsApproval":true,
               "authUrl":"https://login.example.test/a?token=secret","keyAuth":false,"self":null,
               "currentExit":{"id":"peer-stable","ip":"100.64.0.2","live":true},
               "peers":[{"id":"peer-stable","name":"Example peer","dnsName":"example.test","ips":["100.64.0.2"],
                 "online":false,"expired":false,"keyExpiry":0,"exitNodeOption":true,"exitNodeSelected":true}],
               "totalPeers":300,"peersTruncated":true}}
        """.trimIndent()
        private fun exitResult(outcome: String) = """{"kind":"exit","outcome":"$outcome","savedExit":"100.64.0.2","errorCode":"","message":""}"""
        private fun pingResult(sequence: Int, peerId: String = "peer-stable", done: Boolean = false) = """
            {"kind":"ping","done":$done,"errorCode":"","message":"","sample":{
            "peerId":"$peerId","peerIp":"100.64.0.2","sequence":$sequence,"latencyMs":12.5,
            "path":"peer-relay","derpRegionId":0,"derpRegionCode":"","error":""}}
        """.trimIndent()
    }
}
