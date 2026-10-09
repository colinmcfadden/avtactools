package app.ezpztac.data

import android.content.Context
import androidx.room.AutoMigration
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
    entities = [
        RecordEntity::class, OutboxEntity::class, SyncStateEntity::class, BlobEntity::class,
        PackEntity::class, PackItemEntity::class, PackOpEntity::class, PackOwnEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // 2: a record can carry a file (a mission's `.msnx`): two columns on the record, two on a send in progress, and the table the bytes live in.
    // 3: mission packs: four new tables, nothing existing altered.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
internal abstract class EzpzDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    abstract fun packDao(): PackDao

    companion object {
        const val FILE_NAME = "ezpz.db"

        fun open(context: Context): EzpzDatabase =
            Room.databaseBuilder(context.applicationContext, EzpzDatabase::class.java, FILE_NAME).build()
    }
}
