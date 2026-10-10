package app.ezpztac.missionpacks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Where the device keeps every mission pack this account has open or owes edits to, so a pack can be read and edited with no
 * signal and nothing made here is lost when the system ends the app: what the server has confirmed, the edits it has not, and
 * those it refused (until they are kept in the library or let go). Room's in the app (core-data), [InMemoryPackStore] in tests.
 *
 * Every session is stored for one account ([write]'s `me`), and is never read back for another: one person's queued edits must
 * never be sent as someone else's.
 */
public interface PackStore {
    /**
     * The session last written for [pack], as it was written: a batch that was out is still sent, and edits the server took are
     * still acked, each with the seq its result named or none (the client works out what became of them first:
     * [PackSessions.hasSentOrAcked]); what the pack refused is still dropped, in its order; read-only, gone and diverged as they
     * were; numbers as written and every object's keys in their order (but for the order of the items in the maps keyed by item,
     * which nothing reads). A store that keeps the view out of its rows gives it back as [PackSessions.withView] works it out, and
     * does nothing else to the session: not [PackSessions.restored], which would put the batch out back in the queue and hide it
     * from that catch-up, and nothing that settles, which would drop the edits a read-only pack holds for the server's answer.
     * Null when there is none, or it is another account's. `PackStoreContract` (this module's test fixtures) holds a store to this.
     */
    public suspend fun load(pack: String, me: Int): PackSession?

    /**
     * Stores [after], which the client made from [before] (null: nothing was stored, or it is a fresh copy of the pack, which
     * replaces whatever is stored for it, another account's included), in one transaction, as [me]'s. A session keeps the instance
     * of everything that did not change, so a store need only rewrite what is not the same instance as in [before]: an event
     * someone else made rewrites one item, not the pack.
     */
    public suspend fun write(before: PackSession?, after: PackSession, me: Int)

    /** Forgets [pack] entirely, its own fields included. */
    public suspend fun forget(pack: String)

    /** The packs with edits of [me]'s not yet sent, or sent with no answer yet: what a drain has to deliver. */
    public suspend fun owed(me: Int): List<String>

    /** The packs holding edits of [me]'s that the pack refused and that have not been kept or let go. */
    public suspend fun withDropped(me: Int): List<String>

    /** How many of [me]'s edits have not reached a pack (queued or sent) or were refused and are not kept yet. Taken ones are the pack's. */
    public suspend fun unsentCount(me: Int): Int

    /** Notes that [pack] was opened at [at] (milliseconds), which [prune] keeps the latest of. */
    public suspend fun touch(pack: String, at: Long)

    /** Forgets [me]'s packs beyond the [keep] most recently opened that have no edit of any kind left in them. */
    public suspend fun prune(me: Int, keep: Int = 10)

    /** This person's own fields for an item of a pack (an LZ/PZ's view, the routes they hid), which are never sent; null if none. */
    public suspend fun own(pack: String, item: String): JsonObject?

    /** Stores this person's own fields for an item of a pack. */
    public suspend fun putOwn(pack: String, item: String, own: JsonObject)
}

/** A [PackStore] in memory, for tests. Its lock is not re-entrant, as a Room transaction is not. */
public class InMemoryPackStore : PackStore {
    private class Stored(val me: Int, val session: PackSession, var openedAt: Long)

    private val lock = Mutex()
    private val packs = LinkedHashMap<String, Stored>()
    private val owns = LinkedHashMap<Pair<String, String>, JsonObject>()

    /** How many times anything was written: a test of what is durable when looks at it. */
    public var writes: Int = 0
        private set

    override suspend fun load(pack: String, me: Int): PackSession? = lock.withLock { packs[pack]?.takeIf { it.me == me }?.session }

    override suspend fun write(before: PackSession?, after: PackSession, me: Int): Unit = lock.withLock {
        val uuid = requireNotNull(after.uuid) { "A pack's session names its pack" }
        packs[uuid] = Stored(me, after, packs[uuid]?.openedAt ?: 0)
        writes += 1
    }

    override suspend fun forget(pack: String): Unit = lock.withLock {
        packs.remove(pack)
        owns.keys.removeAll { it.first == pack }
    }

    override suspend fun owed(me: Int): List<String> = mine(me) { stored ->
        stored.session.pending.any { it.state == PendingState.QUEUED || it.state == PendingState.SENT }
    }

    override suspend fun withDropped(me: Int): List<String> = mine(me) { it.session.dropped.isNotEmpty() }

    override suspend fun unsentCount(me: Int): Int = lock.withLock {
        packs.values.filter { it.me == me }.sumOf { stored ->
            stored.session.pending.count { it.state != PendingState.ACKED } + stored.session.dropped.size
        }
    }

    override suspend fun touch(pack: String, at: Long): Unit = lock.withLock { packs[pack]?.openedAt = at }

    override suspend fun prune(me: Int, keep: Int): Unit = lock.withLock {
        val forget = packs.entries.filter { it.value.me == me }
            .sortedByDescending { it.value.openedAt }
            .drop(keep)
            .filter { it.value.session.pending.isEmpty() && it.value.session.dropped.isEmpty() }
            .map { it.key }
        forget.forEach { pack ->
            packs.remove(pack)
            owns.keys.removeAll { it.first == pack }
        }
    }

    override suspend fun own(pack: String, item: String): JsonObject? = lock.withLock { owns[pack to item] }

    override suspend fun putOwn(pack: String, item: String, own: JsonObject): Unit = lock.withLock { owns[pack to item] = own }

    private suspend fun mine(me: Int, keep: (Stored) -> Boolean): List<String> =
        lock.withLock { packs.filter { it.value.me == me && keep(it.value) }.keys.toList() }
}
