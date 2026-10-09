package app.ezpztac.missionpacks

import app.ezpztac.network.LiveMessage
import app.ezpztac.network.PackLiveConnection
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.EnumMap
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps one mission pack in step with the server: the web's packClient.js (docs/MISSION_PACKS.md §5), with the device's own copy
 * behind it. It loads the pack, sends the edits made here in order, one batch at a time, and takes everyone else's from the live
 * stream, or by asking every few seconds where there is none or while it is down. The state lives in [PackSessions]; this is the
 * plumbing, with the network ([PackApi]), the device's copy ([PackStore]), the library ([PackKeeper]) and the clock (the
 * dispatcher's) passed in, so tests can drive them.
 *
 * Open ([open]), it loads the pack and follows it. Stopped ([stop]), or started not open (a drain: the app was ended with edits
 * waiting), it only sends what waits, retried until it is taken or refused, then keeps in the library whatever the pack would not
 * take and halts. [halt] stops at once and gives nothing up: what waits stays on the device, for this account only.
 *
 * Where it is not like the web, by the owner's decisions (2026-10-08):
 *  - Nothing made here lives only in memory. Every change to the session is written to [store] before anyone sees it: [edit]
 *    returns once the edits are on the device, and a batch is written as sent before it goes. A pack read back after the app was
 *    ended is shown at once ([PackStatus.OFFLINE]) and can be edited; a batch that was out then first waits for a catch-up from the
 *    stored `seq`, whose events confirm whatever of it the server took, and only the rest goes again.
 *  - A failure about the account (signed out, someone else signed in, not through the gate, Mission Packs turned off) pauses: the
 *    edits wait, whole, and nothing is retried until the engine starts a client again. The web drops them, or reads such a 403 as
 *    the pack gone. It reads the live service's 4403 as the pack gone too; here the API is asked, since the service sends 4403 only
 *    when the API's access check refuses the account, and the API's own answer decides.
 *  - Edits the pack would not take are kept in the library whenever the pack is not open: when a drain halts, and when the pack is
 *    gone (a 403 or 404 on load or catch-up drops the edits waiting as gone, where the web would lose them). While it is open they
 *    wait on the device, and [PackNotice.Dropped] says so.
 *  - Sign-out is the engine's: a client sends only as [me] (core-network refuses any other session before sending), and [halt]
 *    keeps the queue for that account's next sign-in.
 *  - A 429's Retry-After holds every send until it is over. After any other failure, as on the web, a new edit or a catch-up sends
 *    at once, without waiting for the retry.
 *
 * Every handler runs on one thread of [dispatcher], so its flags read as the web's do; a change to the session also takes [lock],
 * because writing it to the store suspends and another handler may run meanwhile. The network is never asked under the lock.
 */
internal class PackClient(
    val uuid: String,
    private val me: PackUser,
    private val api: PackApi,
    private val store: PackStore,
    private val keeper: PackKeeper,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val timing: PackTiming = PackTiming(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val notify: (PackNotice) -> Unit = {},
    private val requestBackgroundDrain: () -> Unit = {},
    open: Boolean = true,
    foreground: Boolean = true,
) {
    private enum class Mode { OPEN, DRAINING }

    private enum class Timer { POLL, RECONNECT, SEND, PRESENCE, LOAD }

    private val confined = dispatcher.limitedParallelism(1)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job + confined)
    private val lock = Mutex()

    private var current = PackState(uuid, PackStatus.LOADING, null, emptyList(), emptyList(), null)
    private val mutableState = MutableStateFlow(current)

    /** The pack as the person sees it. Every session in it is on the device already. */
    val state: StateFlow<PackState> = mutableState.asStateFlow()

    private var mode = if (open) Mode.OPEN else Mode.DRAINING

    // Nothing more is written: the client halted, drained, or the pack is gone. Set before the last write (the kept edits).
    private var ended = false
    private var paused = false
    private var visible = foreground

    // Loading: the device's copy has been read; a batch it had out waits for a catch-up first; in step with the server since.
    private var storeRead = false
    private var needsCatchUp = false
    private var requeueStored = false
    private var ready = false

    private var loading = false
    private var sending = false

    // The server asked for a pause (a 429 with Retry-After): nothing is sent until the send timer fires. After any other failure,
    // as on the web, a new edit or a catch-up sends at once.
    private var askedToWait = false
    private var catchingUp = false
    private var catchUpAgain = false
    private var catchUpAgainInBackground = true
    private var askedForBackground = false
    private var loadRetry = 0
    private var sendRetry = 0
    private var socketRetry = 0

    private var socket: PackLiveConnection? = null
    private var socketJob: CompletableJob? = null
    private var welcomed = false
    private var focus: JsonObject? = null
    private var focusSent = true
    private val timers = EnumMap<Timer, Job>(Timer::class.java)

    /** Whether it has stopped for good: halted, drained, paused while draining, or gone. */
    val halted: Boolean get() = job.isCompleted || job.isCancelled

    // -- What the engine and the screens call ------------------------------------------------------------------------------

    /** Reads the device's copy and, open, the server's; then sends what waits. */
    fun start() {
        work.launch { prepare() }
    }

    /**
     * Edits made here: shown at once, sent in order. Returns why they were refused (`closed`, `gone`, `loading`, `paused`, or the
     * session's own refusals: `read_only`, an operation's validation reason), or null once they are on the device.
     */
    suspend fun edit(ops: List<JsonObject>): String? = withContext(confined) {
        when {
            current.status == PackStatus.GONE -> "gone"
            ended || mode != Mode.OPEN -> "closed"
            current.session == null -> "loading"
            paused -> "paused"
            else -> {
                var refused: String? = null
                val taken = transition { session -> PackSessions.edit(session, ops, newId).also { refused = it.refused }.session }
                when {
                    taken == null -> "closed"
                    refused != null -> refused
                    else -> {
                        flush()
                        null
                    }
                }
            }
        }
    }

    /** Closes the stream and asks nothing more; what is still waiting is sent first, then what the pack would not take is kept. */
    fun stop() {
        work.launch { stopNow() }
    }

    /** Stops at once, giving nothing up: what waits stays on the device. Once this returns, nothing more is written. */
    suspend fun halt() {
        withContext(confined) {
            if (!ended) {
                ended = true
                clearTimers()
                closeSocket()
            }
        }
        // A change being written finishes first; every later one sees `ended`.
        lock.withLock { }
        job.cancelAndJoin()
    }

    /** Waits until the client has stopped for good. */
    suspend fun join() {
        job.join()
    }

    /** The app came to the front ([visible]) or went to the back, where it neither follows the pack nor asks for anything new. */
    fun foreground(visible: Boolean) {
        work.launch {
            this@PackClient.visible = visible
            if (ended || paused || mode != Mode.OPEN) return@launch
            if (!visible) {
                listOf(Timer.POLL, Timer.PRESENCE, Timer.LOAD, Timer.RECONNECT).forEach(::clear)
                closeSocket()
                publish { copy(status = if (status == PackStatus.LIVE) PackStatus.POLLING else status, people = emptyList()) }
            } else if (ready) {
                connect()
                if (current.status != PackStatus.LIVE) startPolling()
                catchUp()
            } else if (!loading) {
                clear(Timer.LOAD)
                prepare()
            }
        }
    }

    /**
     * The connection is back, or the app came to the front: a pack that could not be loaded is tried now, and a send waiting to be
     * tried again goes (unless the server asked for the wait).
     */
    fun wake() {
        work.launch {
            if (ended || paused) return@launch
            if (!ready) {
                if (!loading) {
                    clear(Timer.LOAD)
                    prepare()
                }
            } else if (timers.containsKey(Timer.SEND) && !askedToWait) {
                clear(Timer.SEND)
                flushNow()
            }
        }
    }

    /** What this person has open, for everyone else's presence (null for nothing). Small: a few fields. */
    fun setFocus(next: JsonObject?) {
        work.launch {
            focus = next
            sendPresence()
        }
    }

    /** Asks for whatever is new now: after a change made outside the operation stream (a copy from the library). */
    fun refresh() {
        work.launch { catchUpNow(background = false) }
    }

    /** Keeps in the library the person's version of whatever the pack would not take, and lets those edits go. */
    suspend fun keepDropped() {
        withContext(confined) { if (!ended) keepNow() }
    }

    /** Lets go of the edits the pack would not take, without keeping them. */
    suspend fun discardDropped() {
        withContext(confined) {
            lock.withLock {
                val session = current.session ?: return@withLock
                if (ended || session.dropped.isEmpty()) return@withLock
                commit(session, PackSessions.withoutDropped(session, session.dropped.mapNotNull { PackSessions.text(it.op["client_op_id"]) }.toSet()))
            }
        }
    }

    // -- Loading ------------------------------------------------------------------------------------------------------------

    // The web's load, with the device's copy first: read it and show it; when it had a batch out or edits the server took, catch up
    // from its seq (whose events confirm them, ahead of any finish or role change); then, open, take the pack again, keeping what is
    // pending on top. A drain does not take the pack: it only sends.
    private suspend fun prepare() {
        if (ended || paused || loading || ready) return
        loading = true
        var failure: PackFailure? = null
        try {
            if (!storeRead) {
                val stored = store.load(uuid, me.id)
                storeRead = true
                if (stored != null) {
                    needsCatchUp = PackSessions.hasSentOrAcked(stored)
                    requeueStored = stored.pending.any { it.state == PendingState.SENT }
                    publish { copy(status = PackStatus.OFFLINE, session = stored, items = itemsOf(stored)) }
                }
            }
            if (needsCatchUp && current.session != null) {
                catchingUp = true
                try {
                    failure = fetch(background = mode != Mode.OPEN)
                } finally {
                    catchingUp = false
                }
                if (failure == null) needsCatchUp = false
            }
            if (failure == null && mode == Mode.OPEN && !ended) {
                val pack = api.getPack(uuid, me.id)
                change { before -> if (before == null) PackSessions.open(pack, me.id) else PackSessions.reload(before, pack) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = PackFailure.of(e)
        } finally {
            loading = false
        }
        if (ended || paused) return
        if (failure != null) {
            loadFailed(failure)
            return
        }
        ready = true
        loadRetry = 0
        publish { copy(error = null) }
        // What waits goes first (a batch the app had out when it was ended, then what was queued): its send is the next thing to run
        // on this thread, and marks the batch out before anything that arrives can settle it. A drain with nothing on the device has
        // nothing to send, and halts.
        flush()
        if (mode == Mode.OPEN) {
            startPolling()
            connect()
        }
        drained()
    }

    private suspend fun loadFailed(failure: PackFailure) {
        when {
            failure.pauses -> pause(failure)
            failure.status == 403 || failure.status == 404 -> lost(failure)
            else -> {
                publish { copy(status = if (session != null) PackStatus.OFFLINE else PackStatus.ERROR, error = failure) }
                // A server down or no connection: tried again, as sends are. While open and in the back, not until the app is shown
                // again ([wake]); a drain carries on, as sending does.
                later(Timer.LOAD, timing.retry(loadRetry++)) {
                    if (mode == Mode.DRAINING || visible) work.launch { prepare() }
                }
                if (mode == Mode.DRAINING) askForBackgroundDrain()
            }
        }
    }

    // -- Catching up -------------------------------------------------------------------------------------------------------

    // Pages of the log from the session's seq until there are no more. Null when in step, else why not. A copy that disagrees with
    // the server (it never should) takes the server's again, keeping what is pending.
    private suspend fun fetch(background: Boolean): PackFailure? {
        var more = true
        while (more && !ended && !paused) {
            val since = current.session?.seq ?: return null
            val after = try {
                val page = api.events(uuid, since, timing.eventsPage, background, me.id)
                val events = objects(page["events"])
                more = Js.truthy(page["has_more"])
                receive(events) ?: return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return PackFailure.of(e)
            }
            if (after.diverged) {
                reload()
                more = false
            }
        }
        return null
    }

    private suspend fun receive(events: List<JsonObject>): PackSession? {
        var skipped = 0
        var gap = false
        val after = transition { session ->
            skipped = ownSkipped(session, events, UNTAKEN)
            PackSessions.receive(session, events).also { gap = it.gap }.session
        } ?: return null
        if (skipped > 0) notify(PackNotice.OwnSkipped(uuid, skipped))
        if (gap) catchUp()
        return after
    }

    private suspend fun reload() {
        val pack = try {
            api.getPack(uuid, me.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return                                                  // the next poll, event or reconnect tries again
        }
        transitionOrNull { PackSessions.reload(it, pack) }
    }

    private fun catchUp(background: Boolean = false) {
        work.launch { catchUpNow(background) }
    }

    // One at a time; one asked for meanwhile runs once more after it. While open only: a drain only sends.
    private suspend fun catchUpNow(background: Boolean) {
        if (ended || paused || !ready || mode != Mode.OPEN) return
        if (catchingUp) {
            catchUpAgain = true
            if (!background) catchUpAgainInBackground = false
            return
        }
        catchingUp = true
        var failure: PackFailure? = null
        try {
            var inBackground = background
            do {
                catchUpAgain = false
                catchUpAgainInBackground = true
                failure = fetch(inBackground)
                inBackground = catchUpAgainInBackground
            } while (failure == null && catchUpAgain && !ended && !paused)
        } finally {
            catchingUp = false
        }
        when {
            failure == null -> Unit
            failure.pauses -> pause(failure)
            failure.status == 403 || failure.status == 404 -> lost(failure)
            else -> Unit                                            // the next poll, event or reconnect tries again
        }
        flush()
    }

    private fun startPolling() {
        if (ended || paused || mode != Mode.OPEN) return
        if (current.status != PackStatus.POLLING) publish { copy(status = PackStatus.POLLING) }
        if (!timers.containsKey(Timer.POLL)) poll()
    }

    private fun poll() {
        if (ended || paused || mode != Mode.OPEN || !visible || current.status != PackStatus.POLLING) return
        // Going to the back clears this timer, so it never fires there.
        later(Timer.POLL, timing.pollMs) {
            catchUp(background = true)
            poll()
        }
    }

    // -- The live stream ---------------------------------------------------------------------------------------------------

    private fun connect() {
        if (ended || paused || mode != Mode.OPEN || !visible || socketJob != null) return
        val url = current.session?.liveUrl ?: return
        welcomed = false
        val connection = Job(job)
        socketJob = connection
        val scope = CoroutineScope(work.coroutineContext + connection)
        scope.launch {
            val live = try {
                api.openLive(url, uuid, scope, me.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (socketJob === connection) {
                    socketJob = null
                    scheduleReconnect()
                }
                connection.complete()
                return@launch
            }
            if (socketJob !== connection || live == null) {
                // Closed meanwhile; or no stream for this account now (signed out, someone else signed in) or at that address. The
                // polls carry on, and their own answer pauses the client when the account is why.
                if (socketJob === connection) socketJob = null
                live?.close(1000)
                connection.complete()
                return@launch
            }
            socket = live
            live.messages.collect { message -> if (socket === live) onMessage(message) }
            connection.complete()
        }
    }

    private suspend fun onMessage(message: LiveMessage) {
        if (ended || paused) return
        val seq = current.session?.seq ?: return
        when (message) {
            is LiveMessage.Welcome -> {
                welcomed = true
                socketRetry = 0
                clear(Timer.POLL)
                publish { copy(status = PackStatus.LIVE) }
                if (message.headSeq > seq) catchUp()
                if (focus != null) sendPresence()
            }
            is LiveMessage.Event -> if (message.seq == seq + 1 && !catchingUp) {
                val after = try {
                    receive(listOf(message.event))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null                                            // not on the device: the next event is a gap, and fetches it
                } ?: return
                if (after.diverged) work.launch { reload() }
                flush()
            } else if (message.seq > seq) {
                catchUp()
            }
            is LiveMessage.Head -> if (message.seq > seq) catchUp()
            LiveMessage.Resync -> catchUp()
            is LiveMessage.Presence -> publish { copy(people = message.people) }
            is LiveMessage.Closed -> closed(message)
        }
    }

    private suspend fun closed(message: LiveMessage.Closed) {
        socket = null
        socketJob = null
        welcomed = false
        if (mode != Mode.OPEN) return
        publish { copy(people = emptyList()) }
        when {
            message.packGone -> lost(PackFailure(404, "pack_not_found"))
            // The API refused the account, not the pack: ask it, and its own answer decides (a pause, never a drop).
            message.code == 4403 -> {
                startPolling()
                catchUp()
            }
            else -> {
                startPolling()
                if (message.reconnect) scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        later(Timer.RECONNECT, timing.retry(socketRetry++)) { connect() }
    }

    private fun closeSocket() {
        val live = socket
        val connection = socketJob
        socket = null
        socketJob = null
        welcomed = false
        live?.close(1000)
        connection?.cancel()
    }

    private fun sendPresence() {
        val live = socket ?: return
        if (!welcomed) return
        if (timers.containsKey(Timer.PRESENCE)) {
            focusSent = false
            return
        }
        live.presence(focus)                                        // a focus over 1 KB is not sent: the service would drop it
        focusSent = true
        later(Timer.PRESENCE, timing.presenceMs) { if (!focusSent) sendPresence() }
    }

    // -- Sending -----------------------------------------------------------------------------------------------------------

    private fun flush() {
        work.launch { flushNow() }
    }

    // One batch in flight. It is written as sent before it goes, so a batch out when the app is ended is known to have gone.
    private suspend fun flushNow() {
        if (ended || sending || paused || !ready || askedToWait) return
        sending = true
        var batch: JsonObject? = null
        // The batch the app had out when it was ended, or whose answer could not be written, got no answer here: it goes again,
        // unchanged, with what is queued. The flag is let go only once that is on the device: a write that fails leaves the batch
        // sent in the session, where nothing else would ever send it.
        val requeue = requeueStored
        try {
            transition { session ->
                val base = if (requeue) PackSessions.restored(session) else session
                val next = PackSessions.nextBatch(base)
                batch = next?.batch
                next?.session ?: base
            }
            if (requeue) requeueStored = false
        } catch (e: CancellationException) {
            sending = false
            throw e
        } catch (_: Exception) {
            sending = false
            retrySend(PackFailure(0))
            return
        }
        val out = batch
        if (out == null) {
            sending = false
            drained()
            return
        }
        val answer = try {
            api.sendOps(uuid, out, me.id)
        } catch (e: CancellationException) {
            sending = false
            throw e
        } catch (e: Exception) {
            sending = false
            sendFailed(PackFailure.of(e))
            return
        }
        sending = false
        sendRetry = 0
        if (ended) return
        var skipped = 0
        var catchUpNeeded = false
        val applied = try {
            transition { session ->
                skipped = ownSkipped(session, objects(answer["results"]), SENT)
                PackSessions.batchAnswered(session, answer).also { catchUpNeeded = it.catchUp }.session
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not on the device: as if the answer were lost. The batch goes again and the server answers it from its log.
            requeueStored = true
            retrySend(PackFailure(0))
            return
        }
        if (applied == null) return
        if (skipped > 0) notify(PackNotice.OwnSkipped(uuid, skipped))
        if (catchUpNeeded && mode == Mode.OPEN) catchUp()
        flush()
        drained()
    }

    private suspend fun sendFailed(failure: PackFailure) {
        if (ended) return
        when {
            // About the account: the batch waits with the rest, whole.
            failure.pauses -> {
                if (transitionOrNull { PackSessions.batchFailed(it, PackFailure(0)) } == null) requeueStored = true
                pause(failure)
            }
            failure.retryable -> {
                if (transitionOrNull { PackSessions.batchFailed(it, failure) } == null) requeueStored = true
                retrySend(failure)
            }
            else -> {
                var skipped = 0
                val after = transitionOrNull { session ->
                    skipped = ownSkipped(session, objects(failure.taken), SENT)
                    PackSessions.batchFailed(session, failure)
                }
                if (after == null) {
                    if (!ended) {
                        requeueStored = true
                        retrySend(PackFailure(0))
                    }
                    return
                }
                if (skipped > 0) notify(PackNotice.OwnSkipped(uuid, skipped))
                // What the refusal says the pack took the first time waits for its events, as an answer's results do.
                if (mode == Mode.OPEN && after.pending.any { it.state == PendingState.ACKED }) catchUp()
                flush()
            }
        }
        drained()
    }

    private fun retrySend(failure: PackFailure) {
        if (ended || paused) return
        val asked = (failure.retryAfterSeconds ?: 0) * 1_000
        later(Timer.SEND, maxOf(timing.retry(sendRetry++), asked)) { flush() }
        askedToWait = failure.status == 429
        // Draining, the app may be ended before the next try: the background sync takes over then.
        if (mode == Mode.DRAINING) askForBackgroundDrain()
    }

    private fun askForBackgroundDrain() {
        if (askedForBackground) return
        askedForBackground = true
        requestBackgroundDrain()
    }

    // -- Stopping ----------------------------------------------------------------------------------------------------------

    private fun stopNow() {
        if (ended || mode != Mode.OPEN) return
        mode = Mode.DRAINING
        listOf(Timer.POLL, Timer.RECONNECT, Timer.PRESENCE, Timer.LOAD).forEach(::clear)
        closeSocket()
        if (paused) {
            // The next enable sends what waits.
            end()
            return
        }
        if (!ready && !loading) work.launch { prepare() }
        drained()
    }

    // Draining, and nothing is left to send or waiting to be sent again: keep what the pack would not take, and halt.
    private fun drained() {
        if (ended || mode != Mode.DRAINING || !storeRead || loading || sending || timers.containsKey(Timer.SEND)) return
        if (current.session?.pending?.any { it.state in UNTAKEN } == true) return
        ended = true
        clearTimers()
        closeSocket()
        work.launch {
            try {
                keepNow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Still on the device: kept by the next client of this pack, or at the next enable.
            } finally {
                job.cancel()
            }
        }
    }

    private fun pause(failure: PackFailure) {
        if (ended || paused) return
        paused = true
        clearTimers()
        closeSocket()
        publish { copy(status = PackStatus.PAUSED, error = failure, people = emptyList()) }
        notify(PackNotice.Paused(uuid, failure.code ?: "http_${failure.status}"))
        if (mode == Mode.DRAINING) end()
    }

    // A 403 or 404 about the pack (deleted, or the person is not in it any more), on load, catch-up or from the stream: what waits
    // is dropped as gone, then kept. A copy that was gone already (its edits could not be kept last time) is gone again.
    private suspend fun lost(failure: PackFailure) {
        publish { copy(error = failure) }
        if (current.session?.gone == null) transitionOrNull { PackSessions.batchFailed(it, failure) }
        goneNow(failure)
    }

    // The pack was deleted, or is not this person's any more: what it had not taken is kept, and the device forgets it.
    private suspend fun goneNow(failure: PackFailure?) {
        if (ended || current.status == PackStatus.GONE) return
        ended = true
        clearTimers()
        closeSocket()
        val why = current.session?.gone ?: if (failure?.status == 403) "forbidden" else "not_found"
        publish { copy(status = PackStatus.GONE, error = failure ?: error, people = emptyList()) }
        notify(PackNotice.Gone(uuid, why))
        try {
            keepNow()
            // Only once nothing of the person's is left in it: a drop that could not be written leaves the edits waiting.
            val left = current.session
            if (left == null || (left.dropped.isEmpty() && left.pending.none { it.state in UNTAKEN })) store.forget(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Still on the device: the engine keeps it at the next enable.
        } finally {
            job.cancel()
        }
    }

    private fun end() {
        ended = true
        clearTimers()
        closeSocket()
        job.cancel()
    }

    // What the pack would not take, as the person's own version of each item, saved to the library; then let go. One whose version
    // is not kept (the library failed) stays, to be kept later. Under the lock, so no edit lands between reading and letting go.
    private suspend fun keepNow() {
        var notices = emptyList<PackNotice>()
        lock.withLock {
            val session = current.session ?: return
            if (session.dropped.isEmpty()) return
            // Each item's version is named for good by its first dropped edit, so keeping it again makes no second record.
            val firstDropped = LinkedHashMap<String, String>()
            session.dropped.forEach { dropped ->
                val item = PackSessions.text(dropped.op["item"])
                val id = PackSessions.text(dropped.op["client_op_id"])
                if (item != null && id != null) firstDropped.putIfAbsent(item, id)
            }
            val versions = PackSessions.droppedVersions(session).map { version ->
                val key = "$uuid:${version.uuid}:${firstDropped[version.uuid]}"
                version.uuid to KeptVersion(key, version.kind, PackSentences.myEditsName(version.name), version.data)
            }
            val outcomes = if (versions.isEmpty()) {
                emptyList()
            } else {
                try {
                    keeper.keep(uuid, versions.map { it.second })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return
                }
            }
            val answered = outcomes.map { it.key }.toSet()
            val waiting = versions.filter { it.second.key !in answered }.map { it.first }.toSet()
            val letGo = session.dropped.filter { PackSessions.text(it.op["item"]) !in waiting }
            val ids = letGo.mapNotNull { PackSessions.text(it.op["client_op_id"]) }.toSet()
            commit(session, PackSessions.withoutDropped(session, ids))
            val saved = outcomes.filterIsInstance<KeptOutcome.Saved>()
            notices = buildList {
                if (saved.isNotEmpty()) add(PackNotice.Kept(uuid, PackSessions.text(session.pack["name"]), saved, letGo.map { it.reason }.toSet()))
                outcomes.filterIsInstance<KeptOutcome.NothingToKeep>().forEach { add(PackNotice.NothingToKeep(uuid, it.key, it.reason)) }
            }
        }
        notices.forEach(notify)
    }

    // -- The session -------------------------------------------------------------------------------------------------------

    // Every change to the session: computed on the latest one, written to the device, then shown. Null when nothing more may be
    // written, or there is no session.
    private suspend fun change(step: (PackSession?) -> PackSession?): PackSession? {
        var before: PackSession? = null
        val after = lock.withLock {
            if (ended) return null
            before = current.session
            val next = step(before) ?: return null
            commit(before, next)
            next
        }
        reacted(before, after)
        return after
    }

    private suspend fun transition(step: (PackSession) -> PackSession): PackSession? = change { it?.let(step) }

    // A change whose failure to reach the device is handled by trying again later.
    private suspend fun transitionOrNull(step: (PackSession) -> PackSession): PackSession? = try {
        transition(step)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // Under the lock.
    private suspend fun commit(before: PackSession?, after: PackSession) {
        if (after === before) return
        store.write(before, after, me.id)
        publish { copy(session = after, items = itemsOf(after)) }
    }

    // What a change means beyond the session: edits the pack would not take while it is open, and the pack gone.
    private fun reacted(before: PackSession?, after: PackSession) {
        if (after.gone != null) {
            if (before?.gone == null) work.launch { goneNow(null) }
            return
        }
        val dropped = after.dropped.size - (before?.dropped?.size ?: 0)
        if (dropped > 0 && mode == Mode.OPEN) {
            notify(PackNotice.Dropped(uuid, dropped, after.dropped.takeLast(dropped).map { it.reason }.toSet()))
        }
    }

    private fun publish(change: PackState.() -> PackState) {
        current = current.change()
        mutableState.value = current
    }

    private fun itemsOf(session: PackSession): List<PackItemView> = PackSessions.visibleItems(session).map { item ->
        val uuid = PackSessions.text(item["uuid"]).orEmpty()
        val info = session.info[uuid]
        PackItemView(
            uuid = uuid,
            kind = PackSessions.text(item["kind"]).orEmpty(),
            name = PackSessions.text(item["name"]).orEmpty(),
            data = item.getValue("data"),
            info = info,
            pendingCreate = info == null,
        )
    }

    // -- Timers ------------------------------------------------------------------------------------------------------------

    private fun later(timer: Timer, ms: Long, fire: () -> Unit) {
        clear(timer)
        val waiting = work.launch(start = CoroutineStart.LAZY) {
            delay(ms)
            timers.remove(timer)
            if (timer == Timer.SEND) askedToWait = false
            fire()
        }
        timers[timer] = waiting
        waiting.start()
    }

    private fun clear(timer: Timer) {
        timers.remove(timer)?.cancel()
        if (timer == Timer.SEND) askedToWait = false
    }

    private fun clearTimers() {
        Timer.entries.forEach(::clear)
    }

    private companion object {
        val UNTAKEN = setOf(PendingState.QUEUED, PendingState.SENT)
        val SENT = setOf(PendingState.SENT)

        fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

        // How many of [entries] (an answer's results, a refusal's `taken`, events) say one of this person's edits in [states] was
        // skipped: it reached the pack after what it changed was gone, and changed nothing.
        fun ownSkipped(session: PackSession, entries: List<JsonObject>, states: Set<PendingState>): Int {
            val ours = session.pending.filter { it.state in states }.mapNotNull { PackSessions.text(it.op["client_op_id"]) }.toSet()
            if (ours.isEmpty()) return 0
            return entries.count { PackSessions.text(it["status"]) == "skipped" && PackSessions.text(it["client_op_id"]) in ours }
        }
    }
}
