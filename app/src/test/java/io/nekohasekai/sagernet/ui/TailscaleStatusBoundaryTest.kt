package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TailscaleStatusBoundaryTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/io/nekohasekai/sagernet/ui/$name.kt"),
        File("app/src/main/java/io/nekohasekai/sagernet/ui/$name.kt"),
    ).first { it.isFile }.readText()

    @Test fun statusPathHasNoVpnEditorOrLoggingSideEffects() {
        val sources = listOf("TailscaleStatusActivity", "TailscaleStatusViewModel", "TailscaleStatusSession", "TailscaleStatusClient")
            .joinToString("\n") { source(it) }
        for (forbidden in listOf("startService(", "stopService(", "reloadService(", "saveAndExit(",
            "DataStore.editingId", "VpnRequestActivity", "Logs.", "SavedStateHandle")) {
            assertFalse(forbidden, sources.contains(forbidden))
        }
        assertTrue(source("TailscaleStatusActivity").contains("ThemedActivity(R.layout.layout_tailscale_status)"))
        assertFalse(source("TailscaleStatusActivity").contains("ProfileSettingsActivity"))
    }

    @Test fun editorSavesUseExplicitMergeAndDoNotWriteCachedWholeRows() {
        val editor = source("profile/TailscaleSettingsActivity")
        assertTrue(editor.contains("TailscaleProfileStore.saveEditor("))
        assertFalse(editor.contains("ProfileManager.updateProfile("))
        assertTrue(editor.contains("refreshingExit"))
        assertTrue(editor.contains("exitEdited = true"))
        assertTrue(editor.contains("super.saveAndExit()"))
        assertTrue(editor.contains("SagerNet.stopService()"))
    }
}
