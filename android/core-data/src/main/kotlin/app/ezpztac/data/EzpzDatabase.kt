package app.ezpztac.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's database: the source of truth the screens read and the sync engine works through. It lives in app-private storage
 * under the OS's file encryption, and is excluded from every backup (the manifest), so LZ locations never reach a consumer cloud
 * and the server is the backup.
 *
 * Versions are explicit and every one's schema is exported to `schemas/` and committed. Change an entity and the build fails
 * until the version is raised and a migration written.
 */
@Database(
    entities = [RecordEntity::class, OutboxEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class EzpzDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        const val FILE_NAME = "ezpz.db"

        fun open(context: Context): EzpzDatabase =
            Room.databaseBuilder(context.applicationContext, EzpzDatabase::class.java, FILE_NAME).build()
    }
}
