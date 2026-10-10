package xyz.nekobyte.nekobox

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import xyz.nekobyte.nekobox.database.DbExecutors
import xyz.nekobyte.nekobox.database.preference.KeyValuePair

@Database(entities = [KeyValuePair::class], version = 1)
abstract class TempDatabase : RoomDatabase() {

    companion object {
        @Suppress("EXPERIMENTAL_API_USAGE")
        private val instance by lazy {
            Room.inMemoryDatabaseBuilder(NekoBox.application, TempDatabase::class.java)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .setQueryExecutor(DbExecutors.query)
                .build()
        }

        val profileCacheDao get() = instance.profileCacheDao()
    }

    abstract fun profileCacheDao(): KeyValuePair.Dao
}
