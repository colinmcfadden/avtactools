package app.ezpztac.missionpacks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.fail
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Someone with an account that has Mission Packs: who they are to the packs, and the address they are invited at. */
public data class PackPerson(val user: PackUser, val email: String)

/**
 * One device's connection, as a scenario sets it: no signal at all ([offline]), the answer to a batch lost after the server took it
 * ([loseNextAnswers]), or held until the scenario lets it go ([holdNextAnswer]). Each environment's [PackApi] for the device reads it,
 * the real server's from OkHttp's threads.
 */
public class Network {
    /** Nothing leaves the device: every call fails before it is sent. */
    @Volatile public var offline: Boolean = false

    private val losses = AtomicInteger()
    private val held = AtomicReference<CompletableDeferred<Unit>?>()

    /** The answers to the next [count] batches are lost once the server has taken them. */
    public fun loseNextAnswers(count: Int = 1) {
        losses.addAndGet(count)
    }

    /** The answer to the next batch waits, once the server has taken it, until the scenario completes what this returns; then it is lost. */
    public fun holdNextAnswer(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { held.set(it) }

    /** Whether the answer to the batch the server just took is lost, counting it. */
    public fun losesAnswer(): Boolean = losses.getAndUpdate { if (it > 0) it - 1 else 0 } > 0

    /** The hold on the answer to the batch the server just took, when one was asked for (once). */
    public fun takeHold(): CompletableDeferred<Unit>? = held.getAndSet(null)
}

/**
 * One device of one person: its own copy of their packs ([store]), its own library ([keeper]), and the engine the app runs, which
 * [restart] replaces as an app that was ended and started again would ([drainInBackground]: a background sync in a process with no
 * screens). Everything the device sends goes through [api], which reads [network]. What the engine told the screens is in [notices],
 * and every `client_op_id` it made, in order, in [opIds].
 */
public class PackDevice(
    public val label: String,
    public val person: PackPerson,
    private val api: PackApi,
    public val network: Network,
    public val store: PackStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val timing: PackTiming,
) {
    /** The device's library: what it kept of edits a pack refused. */
    public val keeper: RecordingKeeper = RecordingKeeper()

    /**
     * Every notice the device's engines gave, in order. One arrives a moment after it is given, which is after what it tells of is on
     * the device: a scenario waits for it ([eventually]) rather than look once.
     */
    public val notices: MutableList<PackNotice> = mutableListOf()

    /** Every `client_op_id` the device made, in order. */
    public val opIds: MutableList<String> = mutableListOf()

    /** How many times an engine asked for a sync in the background. */
    public var backgroundSyncs: Int = 0
        private set

    private var opened: String? = null
    private var listening: Job? = null

    /** The engine the app runs now. */
    public var engine: PackEngine = newEngine()
        private set

    /** The open pack as the screens see it. */
    public val state: PackState? get() = engine.open.value

    /** Item [uuid] of the open pack, as the person sees it. */
    public fun item(uuid: String): PackItemView? = engine.open.value?.item(uuid)

    /** What the device holds of [pack] for its person. */
    public suspend fun stored(pack: String): PackSession? = store.load(pack, person.user.id)

    /** Signed in, with Mission Packs on: the engine goes. */
    public suspend fun enable() {
        engine.enable(person.user)
    }

    public suspend fun open(pack: String) {
        opened = pack
        engine.openPack(pack)
    }

    public suspend fun close() {
        engine.closePack()
        opened = null
    }

    /** Edits to the open pack; why they were refused, or null. */
    public suspend fun edit(vararg ops: JsonObject): String? = engine.edit(checkNotNull(opened) { "$label has no pack open" }, ops.toList())

    /** The connection is back: the engine hears so at once, as the app's network watcher tells it. */
    public fun online() {
        network.offline = false
        engine.wake()
    }

    /** The app was ended and started again: the engine stopped where it was, a new one over the same copy, the same pack open. */
    public suspend fun restart() {
        engine.disable()
        engine = newEngine()
        engine.enable(person.user)
        opened?.let { engine.openPack(it) }
    }

    /** The app was ended, or signed out: the engine stops where it is, giving nothing up. */
    public suspend fun quit() {
        engine.disable()
        opened = null
    }

    /** The app was ended, and a background sync ran in a process with no screens: a new engine, nobody signed in for it, drains. */
    public suspend fun drainInBackground(timeoutMs: Long): DrainOutcome {
        quit()
        engine = newEngine()
        return engine.drainAll(person.user, timeoutMs)
    }

    private fun newEngine(): PackEngine {
        listening?.cancel()
        val made = PackEngine(api, store, keeper, scope, dispatcher, { backgroundSyncs++ }, timing, ::nextId)
        // Listening before anything can be said.
        listening = scope.launch(start = CoroutineStart.UNDISPATCHED) { made.notices.collect { notices += it } }
        return made
    }

    // Unique across devices and runs, since the server answers an id it has seen from its log whoever sent it.
    private fun nextId(): String = "$label-${opIds.size + 1}-${UUID.randomUUID().toString().take(8)}".also { opIds += it }
}

/** Waits, in real time, until [check] holds, for at most [timeoutMs]; fails saying [what] did not happen. */
public suspend fun eventually(what: String, timeoutMs: Long = 20_000, check: suspend () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (!check()) {
        if (System.nanoTime() - deadline > 0) fail<Unit>("Never happened, in $timeoutMs ms: $what")
        delay(20)
    }
}
