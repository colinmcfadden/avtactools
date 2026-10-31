package app.ezpztac.data

import android.content.Context
import androidx.room.Room
import app.ezpztac.network.SyncChange
import app.ezpztac.sync.Device
import app.ezpztac.sync.Env
import app.ezpztac.sync.FakeServer

/** An in-memory Room database, as the app's but not kept: for a store test. */
internal fun inMemoryDatabase(context: Context): EzpzDatabase =
    Room.inMemoryDatabaseBuilder(context, EzpzDatabase::class.java).allowMainThreadQueries().build()

/**
 * The shared sync scenarios' world, with every device keeping its records in its own Room database instead of in memory:
 * the same rules, run through the real SQLite, so any difference between the two stores shows as a failing scenario.
 */
internal class RoomEnv(private val context: Context) : Env {
    val server = FakeServer()
    private val databases = mutableListOf<EzpzDatabase>()

    override fun device(label: String): Device {
        val database = inMemoryDatabase(context).also { databases += it }
        return Device(label, server, store = RoomSyncStore(database))
    }

    override suspend fun serverView(): List<SyncChange> = server.changes(0).changes

    override fun close() = databases.forEach { it.close() }
}
