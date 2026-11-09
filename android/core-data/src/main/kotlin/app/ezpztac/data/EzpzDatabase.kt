package app.ezpztac.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 5,
    exportSchema = true,
    // 2: a record can carry a file (a mission's `.msnx`): two columns on the record, two on a send in progress, and the table the bytes live in.
    // 3: mission packs: four new tables, nothing existing altered.
    // 4: a record notes when it last changed (the Library sorts by it): one column, null for every record kept before.
    // 5: nothing altered. The sync cursor is forgotten, once, so a device past saved threat sets it could not read before picks them up ([ForgetCursor]).
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = EzpzDatabase.ForgetCursor::class),
    ],
)
internal abstract class EzpzDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    abstract fun packDao(): PackDao

    /** The next pull starts from the beginning. Safe: a change a record already has is applied as no change, and a pull never overwrites a record with changes of its own. */
    class ForgetCursor : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM sync_state WHERE `key` = 'cursor'")
        }
    }

    companion object {
        const val FILE_NAME = "ezpz.db"

        fun open(context: Context): EzpzDatabase =
            Room.databaseBuilder(context.applicationContext, EzpzDatabase::class.java, FILE_NAME).build()
    }
}
