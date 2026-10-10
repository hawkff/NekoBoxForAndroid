package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun movesTheDatabaseWithItsJournal() {
        val from = folder.newFile("sager_net.db").apply { writeText("rows") }
        File(from.path + "-journal").writeText("journal")
        val to = File(folder.root, "profiles.db")

        moveDatabaseFiles(from, to)

        assertEquals("rows", to.readText())
        assertEquals("journal", File(to.path + "-journal").readText())
        assertFalse(from.exists())
        assertFalse(File(from.path + "-journal").exists())
    }

    @Test
    fun keepsAnExistingTarget() {
        val from = folder.newFile("sager_net.db").apply { writeText("old") }
        val to = folder.newFile("profiles.db").apply { writeText("new") }

        moveDatabaseFiles(from, to)

        assertEquals("new", to.readText())
        assertEquals("old", from.readText())
    }

    @Test
    fun ignoresAMissingSource() {
        val to = File(folder.root, "profiles.db")

        moveDatabaseFiles(File(folder.root, "sager_net.db"), to)

        assertFalse(to.exists())
    }
}
