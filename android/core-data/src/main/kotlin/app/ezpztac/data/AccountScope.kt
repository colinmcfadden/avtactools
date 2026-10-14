package app.ezpztac.data

import androidx.room.withTransaction

/** Whose saved plans are on this device. */
public sealed interface Ownership {
    /** Nobody has signed in here yet, or the data was wiped. */
    public data object Unclaimed : Ownership

    public data object Yours : Ownership

    /**
     * Another account's. Signing in as someone else must not show them that account's plans, and must not upload them under the new
     * one; [unsyncedChanges] says how much would be lost by clearing them, so the person can be asked first.
     */
    public data class SomeoneElses(val unsyncedChanges: Int) : Ownership
}

/**
 * Keeps one account's plans on a device from leaking into another's. The plan has sign-out wipe tokens, threats and caches, and
 * the plans stay, so a person who signs out and back in finds their work; this is what stops a *different* person who signs in on the
 * same device from finding it.
 */
public interface AccountScope {
    public suspend fun ownership(userId: Int): Ownership

    /** Records [userId] as the owner. Only for data that is unclaimed or already theirs; wipe first otherwise. */
    public suspend fun claim(userId: Int)

    /**
     * Removes every saved plan, every queued change and the sync cursor, and the claim; and every mission pack held here, with every edit
     * to one not yet taken or kept. Irreversible.
     */
    public suspend fun wipe()
}

internal class RoomAccountScope(private val database: EzpzDatabase) : AccountScope {
    private val dao get() = database.syncDao()
    private val packs get() = database.packDao()

    override suspend fun ownership(userId: Int): Ownership = when (dao.state(OWNER)) {
        null -> Ownership.Unclaimed
        userId -> Ownership.Yours
        // Edits to mission packs count too: those not taken by their pack, and those a pack refused and not yet kept in the library.
        else -> Ownership.SomeoneElses(unsyncedChanges = dao.outboxSize() + packs.unsentOrDropped())
    }

    override suspend fun claim(userId: Int) {
        val owner = dao.state(OWNER)
        check(owner == null || owner == userId) { "this device's data belongs to another account; wipe it first" }
        dao.setState(SyncStateEntity(OWNER, userId))
    }

    override suspend fun wipe() {
        database.withTransaction {
            dao.wipeRecords()
            dao.wipeOutbox()
            dao.wipeBlobs()
            dao.wipeState()
            packs.wipePacks()
            packs.wipeItems()
            packs.wipeOps()
            packs.wipeOwn()
        }
    }

    private companion object {
        const val OWNER = "owner"
    }
}
