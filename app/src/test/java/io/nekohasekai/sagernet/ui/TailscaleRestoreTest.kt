package io.nekohasekai.sagernet.ui

import android.app.Application
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.acquireTailscaleState
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TailscaleRestoreTest {
    @Test
    fun matchingNonemptyMarkerAndCompatibleContentPreserveLocalState() {
        val local = setup()
        val restored = local.copy(tailscaleBean = local.tailscaleBean!!.clone().apply { name = "renamed" })
        restore(restored)
        assertTrue(tailscaleStateFile(local.id).exists())
        assertEquals(local.uuid, stored(local.id).uuid)
    }

    @Test
    fun foreignBlankOrIncompatibleContentRequireNewIdentityAndFreshLocalMarker() {
        for (kind in listOf("foreign", "blank", "incompatible", "legacy-local")) {
            val local = setup()
            val restored = local.copy(tailscaleBean = local.tailscaleBean!!.clone())
            when (kind) {
                "foreign" -> restored.uuid = "foreign-marker"
                "blank" -> restored.uuid = ""
                "incompatible" -> restored.tailscaleBean!!.hostname = "different-host"
                "legacy-local" -> {
                    ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.setTailscaleMarker(local.id, "") }
                    restored.uuid = ""
                }
            }
            val importedMarker = restored.uuid
            restore(restored)
            assertFalse("$kind must remove local credentials", tailscaleStateFile(local.id).exists())
            val marker = stored(local.id).uuid
            assertTrue(marker.isNotBlank())
            assertNotEquals(importedMarker, marker)
            assertNotEquals(local.uuid, marker)
        }
    }

    @Test
    fun cachedIdleCannotRestoreOverActiveProbeAndFailureMakesNoDatabaseChanges() {
        val local = setup()
        acquireTailscaleState(listOf(local.id)).use {
            assertNotNull(runCatching { restore(local.copy(uuid = "foreign")) }.exceptionOrNull())
            assertEquals(local.uuid, stored(local.id).uuid)
            assertEquals("original", ConfigBuilderTestEnv.io { SagerDatabase.groupDao.allGroups().single().name })
            assertTrue(tailscaleStateFile(local.id).exists())
        }
    }

    @Test
    fun stoppingOrConnectingTimeoutAbortsBeforeAnyMutation() = runTest {
        for (state in listOf(BaseService.State.Stopping, BaseService.State.Connecting)) {
            val local = setup()
            DataStore.serviceState = state
            try {
                val failure = runCatching {
                    DatabaseBackupRestoreOperations.replaceProfiles(listOf(local.copy(uuid = "foreign")), emptyList())
                }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(local.uuid, stored(local.id).uuid)
                assertEquals("original", ConfigBuilderTestEnv.io { SagerDatabase.groupDao.allGroups().single().name })
                assertTrue(tailscaleStateFile(local.id).exists())
            } finally {
                DataStore.serviceState = BaseService.State.Idle
            }
        }
    }

    private fun setup(): ProxyEntity {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        val node = ProxyEntity(id = 42, groupId = 1).putBean(TailscaleBean().apply { initializeDefaultValues() })
        ConfigBuilderTestEnv.io {
            SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "original")))
            SagerDatabase.proxyDao.addProxy(node)
        }
        tailscaleStateFile(node.id).apply { mkdirs() }.resolve("tailscaled.state").writeText("local-state")
        return node
    }

    private fun stored(id: Long) = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.getById(id)!! }

    private fun restore(node: ProxyEntity) = ConfigBuilderTestEnv.io {
        runBlocking { DatabaseBackupRestoreOperations.replaceProfiles(listOf(node), listOf(ProxyGroup(id = 1, name = "restored"))) }
    }
}
