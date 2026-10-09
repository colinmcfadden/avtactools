package app.ezpztac.missionpacks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** What a drain in the background came to ([PackEngine.drainAll]). */
public enum class DrainOutcome {
    /** Everything the account made has reached its pack, and whatever a pack refused is kept in the library. */
    DONE,

    /** Something is still waiting that a later try may send (no connection, the server busy or down): try again later. */
    RETRY,

    /**
     * Something is still waiting that only the account can let go (signed out, someone else signed in, not through the `.mil` gate,
     * Mission Packs turned off): nothing was dropped, and trying again changes nothing until then.
     */
    PAUSED,
}

/**
 * Every mission pack this device is working on, for the account signed in ([enable]): the one open, as on the web one at a time
 * ([openPack]); those closed with edits still on their way ([closePack]), which send them and then keep whatever the pack refused;
 * and those [enable] or a background sync ([drainAll]) finds with edits waiting on the device. One client per pack, ever, so there is
 * only ever one batch in flight for a pack, whichever of these holds it: a pack opened again while it is still sending is the same
 * client, opened again ([PackClient.reopen]).
 *
 * Where it is not like the web, by the owner's decisions (2026-10-08):
 *  - Signing out ([disable]) stops everything at once and gives nothing up: what waits stays on the device, for that account only, and
 *    goes at its next [enable]. Another account never sends it, sees it, or keeps it (each client reads and writes only its own
 *    account's copy, and core-network refuses a call made for anyone but the account signed in).
 *  - What a pack refused is kept in the library ([PackKeeper], as "NAME (my edits)") whenever the pack is not open: when it is closed,
 *    at [enable], and in a background drain. While it is open it waits on the device for the screens ([PackNotice.Dropped],
 *    [keepDropped], [discardDropped]); whatever is left is kept once it closes.
 *  - A failure about the account pauses a pack's client with every edit kept ([PackStatus.PAUSED]); the next [enable], or opening the
 *    pack again, asks again.
 *
 * The screens see the open pack ([open]) and what they should hear about ([notices]); the app tells it who is signed in ([enable],
 * [disable]), whether it is in front ([foreground]) and when the connection is back ([wake]); the background sync calls [drainAll].
 * Every client runs on one thread of [dispatcher] at a time; the engine's own bookkeeping takes [lock], and never holds it over a
 * call to the server. [open] is set as the open client sets its own state, never after: an editor that reads it once an edit has
 * returned sees that edit.
 */
public class PackEngine(
    private val api: PackApi,
    private val store: PackStore,
    private val keeper: PackKeeper,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val requestBackgroundDrain: () -> Unit = {},
    private val timing: PackTiming = PackTiming(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    // Which client [open] shows, and its state: changed together, so a client let go of never shows over the one after it.
    private val openLock = Any()

    private val mutableMe = MutableStateFlow<PackUser?>(null)

    /** Who the packs are for: the account [enable]d, or null. */
    public val me: StateFlow<PackUser?> = mutableMe.asStateFlow()

    private val mutableOpen = MutableStateFlow<PackState?>(null)

    /** The open pack as the person sees it, or null when none is. It stays on a pack that is gone until another is opened, or it is closed. */
    public val open: StateFlow<PackState?> = mutableOpen.asStateFlow()

    private val mutableNotices = MutableSharedFlow<PackNotice>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What happened to a pack that the person should hear about, from every client: the open one's and those closing or draining. */
    public val notices: SharedFlow<PackNotice> = mutableNotices.asSharedFlow()

    // Every client there is, one per pack at most, written under [lock]. A client that has stopped for good is taken out by its watcher.
    @Volatile private var clients: Map<String, PackClient> = emptyMap()

    // The open pack's client, written under [lock] and [openLock]; it stays on a client that stopped (a pack gone) until it is let go.
    @Volatile private var openClient: PackClient? = null

    @Volatile private var visible = true

    // The packs whose last client paused for the account's sake, until a new one starts: what a drain says PAUSED for.
    private val paused = ConcurrentHashMap.newKeySet<String>()

    // Moves on whenever a client starts, stops for good, opens, closes, or stalls or gets through again: what a drain in the background
    // waits on.
    private val changes = MutableStateFlow(0L)

    // -- Who it is for -------------------------------------------------------------------------------------------------------

    /**
     * Packs for [user]: whatever the device holds of theirs starts going again. Each pack with edits waiting is drained (sent, then what
     * it refused is kept); a pack holding only what it refused is kept; and a client paused for the account's sake is tried again.
     * Another account's clients stop first, and everything of theirs stays on the device for them.
     */
    public suspend fun enable(user: PackUser) {
        lock.withLock {
            haltLocked(clients.values.filter { it.me.id != user.id })
            if (openClient?.me?.id?.let { it != user.id } == true) follow(null)
            mutableMe.value = user
            // Only with nothing running: a client still writing a pack would bring back half of what pruning forgot.
            if (clients.isEmpty()) store.prune(user.id)
            // Paused for the account's sake (signed out, the feature off): the account is back, so each is asked again, by a new
            // client, as the next enable is what the pause waits for.
            clients.values.filter { it.state.value.status == PackStatus.PAUSED }.forEach { paused ->
                val wasOpen = paused === openClient
                haltLocked(listOf(paused))
                if (wasOpen) follow(startClient(paused.uuid, user, open = true))
            }
            val due = LinkedHashSet(store.owed(user.id) + store.withDropped(user.id))
            due.forEach { pack -> if (clients[pack] == null) startClient(pack, user, open = false) }
        }
    }

    /**
     * Nobody is signed in for packs any more (signed out, Mission Packs off, someone else signing in): every client stops at once,
     * the open pack is let go, and nothing is given up. What waits stays on the device for this account's next [enable].
     */
    public suspend fun disable() {
        lock.withLock {
            follow(null)
            haltLocked(clients.values.toList())
            mutableMe.value = null
        }
    }

    // -- The open pack -----------------------------------------------------------------------------------------------------

    /**
     * Opens [uuid], closing the pack open before it (which goes on sending what waits). A pack still sending since it was closed is
     * opened again, the same client; one paused, or stopping, is replaced. Nothing opens while nobody is signed in for packs.
     */
    public suspend fun openPack(uuid: String) {
        lock.withLock {
            val user = mutableMe.value ?: return
            val current = openClient
            if (current != null && current.uuid == uuid && current.usable) {
                noteOpened(current, now())
                return
            }
            current?.takeIf { it.uuid != uuid }?.stop()
            var client = clients[uuid]
            if (client != null && (!client.usable || !client.reopen())) {
                // Paused (opening it again is asking again) or stopping (keeping what the pack refused): let it finish, then a new one.
                haltLocked(listOf(client))
                client = null
            }
            val opened = client ?: startClient(uuid, user, open = true)
            follow(opened)
            noteOpened(opened, now())
        }
    }

    /** Closes the open pack: it stops following everyone else's edits, sends what waits, then keeps whatever the pack refused. */
    public suspend fun closePack() {
        lock.withLock {
            val current = openClient ?: return
            follow(null)
            current.stop()
        }
    }

    /**
     * Edits made here to [pack], which must be the open one: shown at once, sent in order. Returns why they were refused (`closed`:
     * it is not open; `loading`, `paused`, `gone`, `read_only`, or a malformed operation's reason), or null once they are on the device.
     */
    public suspend fun edit(pack: String, ops: List<JsonObject>): String? {
        val client = openClient?.takeIf { it.uuid == pack && mutableMe.value != null } ?: return "closed"
        return client.edit(ops)
    }

    /** What this person has open in the open pack (`{item}`), for everyone else's presence; null for nothing. */
    public fun setFocus(focus: JsonObject?) {
        openClient?.setFocus(focus)
    }

    /** Asks the open pack for whatever is new now: after a change made outside the operation stream (a copy from the library). */
    public fun refresh() {
        openClient?.refresh()
    }

    /** Keeps in the library the person's version of what the open pack [pack] refused, and lets those edits go. */
    public suspend fun keepDropped(pack: String) {
        openClient?.takeIf { it.uuid == pack }?.keepDropped()
    }

    /** Lets go of what the open pack [pack] refused, without keeping it. */
    public suspend fun discardDropped(pack: String) {
        openClient?.takeIf { it.uuid == pack }?.discardDropped()
    }

    // -- The app ---------------------------------------------------------------------------------------------------------------

    /** The app came to the front ([visible]) or went to the back, where the open pack is not followed but edits still go. */
    public fun foreground(visible: Boolean) {
        this.visible = visible
        clients.values.forEach { it.foreground(visible) }
    }

    /** The connection is back, or the app is: a pack that could not be loaded is tried now, and a send waiting to be tried again goes. */
    public fun wake() {
        clients.values.forEach { it.wake() }
    }

    /** How many of the account's edits have not reached a pack, or were refused and are not kept yet. */
    public suspend fun unsentCount(): Int = mutableMe.value?.let { store.unsentCount(it.id) } ?: 0

    // -- The background sync ---------------------------------------------------------------------------------------------------

    /**
     * Sends everything [user] made that waits on the device, in every pack but the open one (whose client sends by itself), and keeps
     * whatever a pack refused: the background sync's part, in a process the app may not have started (nobody [enable]d) as well as in
     * one where it has. A pack with a client already sending (closed a moment ago, or found at [enable]) is waited for; any other gets a
     * client of its own. It goes on while there is something to wait for (a pack closed meanwhile has its edits sent too), for at most
     * [timeoutMs]. A pack whose client waits to try again (its last try got no answer, or met the server busy or down) is not waited for:
     * the run says RETRY at once, rather than hold the sync that follows it through the client's pauses, and the next run tries again.
     *
     * It says [DrainOutcome.RETRY] when anything is still waiting that a later try may send, [DrainOutcome.PAUSED] when what waits can
     * only go once the account can send it, [DrainOutcome.DONE] otherwise. A client it started that nobody else will follow (nobody is
     * signed in for packs here, so no screen) stops when it returns, leaving what it had not sent on the device for the next try.
     */
    public suspend fun drainAll(user: PackUser, timeoutMs: Long = 60_000): DrainOutcome {
        val started = ArrayList<PackClient>()
        var someoneElse = false
        withTimeoutOrNull(timeoutMs) {
            // Each pack is drained once here: one whose client paused, or could not get through, waits for the next try.
            val handled = HashSet<String>()
            while (true) {
                val seen = changes.value
                val waiting = lock.withLock {
                    val signedIn = mutableMe.value
                    if (signedIn != null && signedIn.id != user.id) {
                        someoneElse = true
                        return@withLock emptyList()
                    }
                    // The open pack has its client already, so it is never given another, nor waited for.
                    val due = LinkedHashSet(store.owed(user.id) + store.withDropped(user.id))
                    due.removeAll(handled)
                    due.forEach { pack ->
                        handled += pack
                        if (clients[pack] == null) started += startClient(pack, user, open = false)
                    }
                    clients.values.filter { it !== openClient && it.me.id == user.id && !it.halted && !it.stalled }
                }
                if (waiting.isEmpty()) break
                changes.first { it != seen }
            }
        }
        val signedIn = mutableMe.value
        if (signedIn?.id != user.id) {
            lock.withLock { haltLocked(started.filter { !it.halted }) }
        }
        if (someoneElse || (signedIn != null && signedIn.id != user.id)) return DrainOutcome.PAUSED
        val opened = openClient?.takeIf { !it.halted }?.uuid
        val left = LinkedHashSet(store.owed(user.id) + store.withDropped(user.id)).apply { remove(opened) }
        return when {
            left.any { it !in paused } -> DrainOutcome.RETRY
            left.isNotEmpty() -> DrainOutcome.PAUSED
            else -> DrainOutcome.DONE
        }
    }

    // -- How it is done -------------------------------------------------------------------------------------------------------

    // A client that can be opened as it is: not stopped for good, and not paused for the account's sake.
    private val PackClient.usable: Boolean get() = !halted && state.value.status != PackStatus.PAUSED

    // Under [lock].
    private fun startClient(pack: String, user: PackUser, open: Boolean): PackClient {
        lateinit var client: PackClient
        client = PackClient(
            pack, user, api, store, keeper, scope, dispatcher, timing, newId, ::heard, requestBackgroundDrain,
            open = open, foreground = visible, onState = { state -> shown(client, state) }, onStalled = { changes.update { it + 1 } },
        )
        clients = clients + (pack to client)
        paused.remove(pack)
        scope.launch {
            client.join()
            lock.withLock { if (clients[pack] === client) clients = clients - pack }
            changes.update { it + 1 }
        }
        client.start()
        changes.update { it + 1 }
        return client
    }

    // Under [lock]. Each stops at once, keeping everything; once this returns, none of them writes anything more.
    private suspend fun haltLocked(stopping: List<PackClient>) {
        if (stopping.isEmpty()) return
        stopping.forEach { client -> if (clients[client.uuid] === client) clients = clients - client.uuid }
        stopping.forEach { it.halt() }
        changes.update { it + 1 }
    }

    // Under [lock]. [client]'s pack was opened at [at], which [PackStore.prune] keeps the latest of: noted once the device holds it,
    // since a pack opened for the first time is stored only when its first load is written, and a note for a pack the device does not
    // hold changes nothing.
    private fun noteOpened(client: PackClient, at: Long) {
        scope.launch {
            try {
                if (client.onDevice()) store.touch(client.uuid, at)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not noted: the pack is pruned sooner, that is all.
            }
        }
    }

    // Under [lock]. [client]'s state is what [open] shows from now on (none for null).
    private fun follow(client: PackClient?) {
        synchronized(openLock) {
            openClient = client
            mutableOpen.value = client?.state?.value
        }
        changes.update { it + 1 }
    }

    // From a client, on its own thread, as it sets a new state: shown if it is the open one.
    private fun shown(client: PackClient, state: PackState) {
        synchronized(openLock) { if (openClient === client) mutableOpen.value = state }
    }

    // From a client, on its own thread: passed on to the screens. Something kept in the library is a new record there, which the
    // library's own sync sends up.
    private fun heard(notice: PackNotice) {
        if (notice is PackNotice.Paused) paused += notice.pack
        mutableNotices.tryEmit(notice)
        if (notice is PackNotice.Kept) requestBackgroundDrain()
    }
}
