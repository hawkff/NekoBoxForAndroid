package xyz.nekobyte.nekobox.database

import android.content.Context
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File

// Names older installs wrote the same databases under. Each is moved once, on the first open.
private val legacyDatabaseNames = mapOf(
    Key.DB_PROFILE to "sager_net.db",
    Key.DB_PUBLIC to "configuration.db",
)

/** Resolves [name] under the databases directory, moving the file an older install left under
 *  its previous name first so existing data survives the rename. */
internal fun Context.databaseFile(name: String): File {
    val target = getDatabasePath(name)
    target.parentFile?.mkdirs()
    legacyDatabaseNames[name]?.let { moveDatabaseFiles(getDatabasePath(it), target) }
    return target
}

/** Moves [from] and its journal files to [to]. Does nothing once [to] exists, so a second
 *  process racing the first open sees the finished move. */
internal fun moveDatabaseFiles(from: File, to: File) {
    if (to.exists() || !from.exists()) return
    // The journal goes first: a rollback journal is only useful next to the file it belongs to.
    for (suffix in listOf("-journal", "-wal", "-shm", "")) {
        val source = File(from.path + suffix)
        if (source.exists() && !source.renameTo(File(to.path + suffix))) {
            Logs.w("database file ${source.name} could not be moved")
        }
    }
}
