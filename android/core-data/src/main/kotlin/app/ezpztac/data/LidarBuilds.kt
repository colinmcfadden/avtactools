package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.LidarBuildDto
import app.ezpztac.network.LidarFrameDto
import app.ezpztac.network.LidarResolveDto
import app.ezpztac.network.LidarTargetDto
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.lidarBuild
import app.ezpztac.network.lidarBuildStatus
import app.ezpztac.network.lidarRelease
import app.ezpztac.network.lidarResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** The calls finding or building a point cloud makes (contracts/openapi.yaml, *The 3D LZ view*). [ApiClient] in the app; a stand-in in tests. */
public interface LidarApi {
    /** [stream]: asks for the place's survey streamed from USGS's octree where nothing is built (`resolve`'s `stream`). */
    public suspend fun resolve(at: LatLon, radiusM: Double?, stream: Boolean): LidarResolveDto

    public suspend fun build(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double?): LidarBuildDto

    public suspend fun status(key: String, watcher: String?, keep: Boolean): LidarBuildDto

    public suspend fun release(key: String, watcher: String?)
}

/** [LidarApi] over the real client. */
public class ApiClientLidarApi(private val client: ApiClient) : LidarApi {
    override suspend fun resolve(at: LatLon, radiusM: Double?, stream: Boolean): LidarResolveDto = client.lidarResolve(at, radiusM, stream)

    override suspend fun build(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double?): LidarBuildDto = client.lidarBuild(at, keep, watcher, radiusM)

    override suspend fun status(key: String, watcher: String?, keep: Boolean): LidarBuildDto = client.lidarBuildStatus(key, watcher, keep)

    override suspend fun release(key: String, watcher: String?): Unit = client.lidarRelease(key, watcher)
}

/**
 * Who waits for point-cloud builds from this device, and which builds it was waiting on, kept between launches: the web keeps both for its tab
 * (`sessionStorage`), so a reloaded page can tell the build service the last one's builds are no longer wanted. An app ended without a word is
 * the reload here. Nothing in it names a place: the watcher is a random id, the builds their opaque keys.
 */
public interface LidarWaiting {
    /** This device's opaque id (`^[A-Za-z0-9_-]{8,64}$`), made once and kept. */
    public fun watcher(): String

    public fun inFlight(): Set<String>

    public fun setInFlight(keys: Set<String>)

    public companion object
}

/** [LidarWaiting] in memory, for tests (and a process with nowhere to keep it). */
public class InMemoryLidarWaiting(private val id: String = LidarWaiting.newWatcher()) : LidarWaiting {
    @Volatile private var keys: Set<String> = emptySet()

    override fun watcher(): String = id

    override fun inFlight(): Set<String> = keys

    override fun setInFlight(keys: Set<String>) {
        this.keys = keys
    }
}

/** A new watcher id: `android-` and 24 random letters of the URL-safe alphabet. */
public fun LidarWaiting.Companion.newWatcher(random: SecureRandom = SecureRandom()): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    return "android-" + String(CharArray(24) { alphabet[random.nextInt(alphabet.length)] })
}

/** What a lookup has come to (the web's `useLidarTileset` states). */
public enum class LidarPhase { IDLE, RESOLVING, BUILDING, AVAILABLE, MISSING, ERROR }

/**
 * Why a lookup or a build ended in [LidarPhase.ERROR], for the screen to say in the app's own words. **Not like the web**, which shows the server's
 * `error` text: a failed build's can carry up to 2,000 characters of a tool's output and the target's coordinates (backend/lidar/build.py), which
 * are not a crew's to read.
 */
public enum class LidarProblem {
    /** The lookup was refused, or failed on the server. */
    LOOKUP_FAILED,

    /** No answer to the lookup. */
    LOOKUP_UNREACHABLE,

    /** The build could not start: the service is down, or this server cannot build after all. */
    BUILD_REFUSED,

    /** The build service's queue is full. */
    QUEUE_FULL,

    /** No answer to the request for a build. */
    BUILD_UNREACHABLE,

    /** The build service tried and could not build it. */
    BUILD_FAILED,

    /** Five asks in a row about the build went unanswered (or the service kept forgetting it). */
    LOST_CONTACT,
}

/**
 * What the 3D view shows of the point cloud it was opened on: where it is ([url], [contextUrl], relative to the API's root), how its build is going
 * ([stage] in the service's own word, [position] in the queue, [elapsedS] since it started), the place the server would build ([target], for a
 * server that cannot), or why it failed ([problem]; [buildFailed] when it was the build, not the lookup). [streamed] says the tileset is a whole
 * survey streamed from USGS's octree, which the view opens on [frame] (where to look), never on the tileset's own sphere (a state).
 */
public data class LidarLookup(
    val phase: LidarPhase = LidarPhase.IDLE,
    val key: String? = null,
    val url: String? = null,
    val contextUrl: String? = null,
    val target: LidarTargetDto? = null,
    val problem: LidarProblem? = null,
    val stage: String? = null,
    val position: Int? = null,
    val elapsedS: Int? = null,
    val buildFailed: Boolean = false,
    val streamed: Boolean = false,
    val frame: LidarFrameDto? = null,
)

/**
 * Finding an LZ/PZ's point cloud, and having it built while someone waits: `useLidarTileset.js` as a plain machine, held to
 * `contracts/fixtures/viewer3d/lidarFlow.json` request by request, and, asked with `stream` (the place's USGS survey streamed where nothing is
 * built, docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, Part C), to `lidarFlowStream.json`.
 *
 * - **Coordinates only in POST bodies**: a build's progress is asked for by its opaque key.
 * - **A build runs only while someone waits.** Every ask about it says who waits ([LidarWaiting.watcher]); closing the view lets it go (a DELETE,
 *   not waited for), and a build nobody asks after is dropped by the service. A saved LZ/PZ's build is kept ([LidarLookupRun.setKeep]): it finishes
 *   regardless. The keys waited on are kept on the device, so the next run's first lookup lets go of what an app ended without a word was waiting
 *   for ([releaseAbandoned]).
 * - **Asked every [POLL_MS]**, a failed ask tried again up to [MAX_POLL_FAILURES] in a row, and a build the service forgot (404) or dropped
 *   (`cancelled`) asked for again, [MAX_RESUBMITS] times at most.
 *
 * The releases and a save's build ([requestKept]) run in [scope], which outlives any one view.
 */
public class LidarBuilds(
    private val api: LidarApi,
    private val waiting: LidarWaiting,
    private val scope: CoroutineScope,
) {
    /**
     * Starts finding the point cloud at [at], in [runScope] (a view's): its state from [LidarPhase.RESOLVING] on. [stream] asks for a streamed
     * survey where nothing is built; only a view that opens on [LidarLookup.frame] may ask.
     */
    public fun start(runScope: CoroutineScope, at: LatLon, keep: Boolean, radiusM: Double? = null, stream: Boolean = false): LidarLookupRun =
        LidarLookupRun(this, runScope, at, radiusM, keep, stream)

    /**
     * Asks for the point cloud at [at] to be built in the background and kept: an LZ/PZ just saved. Built already, streamed there (a first view
     * is seconds away already, as the web's `requestKeptBuild` sees it), or a server that cannot: nothing to say.
     */
    public fun requestKept(at: LatLon) {
        if (!at.lat.isFinite() || !at.lon.isFinite()) return
        scope.launch {
            try {
                if (api.resolve(at, radiusM = null, stream = true).available) return@launch
                api.build(at, keep = true, watcher = null, radiusM = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nobody waits on it; the next 3D view of this LZ/PZ asks again.
            }
        }
    }

    /**
     * Letting go of the builds the last run of the app was waiting on and never saw end (the web's `releaseAbandonedBuilds`, which a page runs as it
     * loads): started as soon as this is made, once a process (what this one waits on afterwards is its own), in [scope], so a view closing does
     * not cut it short. Each key is forgotten only once its release has been tried, so an app ended in the middle leaves the rest to the next.
     */
    private val abandoned: Job = scope.launch {
        for (key in waiting.inFlight()) {
            try {
                api.release(key, waiting.watcher())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lease is the backstop.
            }
            clearInFlight(key)
        }
    }

    /**
     * A lookup waits for that before it asks for anything, [RELEASE_WAIT_MS] at most: the same place opened again at once has the same key, and a
     * release arriving after its new build would drop it (the build is then asked for again). The wait gives up on its own; the releases go on.
     */
    internal suspend fun afterAbandonedReleased() {
        withTimeoutOrNull(RELEASE_WAIT_MS) { abandoned.join() }
    }

    internal fun watcher(): String = waiting.watcher()

    internal fun markInFlight(key: String) = synchronized(this) {
        waiting.setInFlight(waiting.inFlight() + key)
    }

    internal fun clearInFlight(key: String) = synchronized(this) {
        waiting.setInFlight(waiting.inFlight() - key)
    }

    /** Stops waiting for [key]. Not waited for: the service's lease drops the build anyway if the request is lost. */
    internal fun release(key: String) {
        clearInFlight(key)
        scope.launch {
            try {
                api.release(key, waiting.watcher())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lease is the backstop.
            }
        }
    }

    internal suspend fun resolve(at: LatLon, radiusM: Double?, stream: Boolean) = api.resolve(at, radiusM, stream)

    internal suspend fun build(at: LatLon, keep: Boolean, radiusM: Double?) = api.build(at, keep, waiting.watcher(), radiusM)

    internal suspend fun status(key: String, keep: Boolean) = api.status(key, waiting.watcher(), keep)

    public companion object {
        /** The service reports a stage change every few seconds at most, and a build takes minutes. */
        public const val POLL_MS: Long = 3_000

        /** Asks in a row that may fail before giving up: a redeploy briefly drops requests, which should not lose a build. */
        public const val MAX_POLL_FAILURES: Int = 5

        /** Times a build the service forgot or dropped is asked for again, so a service that keeps losing work cannot loop for ever. */
        public const val MAX_RESUBMITS: Int = 2

        /** How long the first lookup waits for the last run's builds to be let go before it asks for its own. */
        public const val RELEASE_WAIT_MS: Long = 3_000
    }
}

// JavaScript's `value || null` for the web's text fields: an empty one is none.
private fun String?.orNull(): String? = this?.takeIf { it.isNotEmpty() }

/**
 * One lookup, from opening the 3D view until it closes ([close]) or is tried again ([retry]). Its scope, and every call to it, are one thread's
 * (the main one): a view model's.
 */
public class LidarLookupRun internal constructor(
    private val builds: LidarBuilds,
    private val runScope: CoroutineScope,
    private val at: LatLon,
    private val radiusM: Double?,
    keep: Boolean,
    private val stream: Boolean,
) {
    private val _state = MutableStateFlow(LidarLookup())

    /** What the view shows. */
    public val state: StateFlow<LidarLookup> = _state.asStateFlow()

    // Read on every request rather than starting again: saving the LZ/PZ mid-build marks its build kept, it does not begin it again.
    @Volatile private var keep: Boolean = keep

    private var job: Job? = null

    init {
        launchRun()
    }

    /** The LZ/PZ was saved (or not) since the view opened. */
    public fun setKeep(keep: Boolean) {
        this.keep = keep
    }

    /** *Retry* and *Check again*: the whole lookup from the start, letting go of anything waited on. */
    public fun retry() {
        stop()
        launchRun()
    }

    /** The view closed: stops asking, and lets go of the build it was waiting for. */
    public fun close() {
        stop()
    }

    // A pass stopped lets go of the build it waits on as it ends (below), so a pass ended by its scope (a view model cleared without a close)
    // lets go too, and a pass tried again never lets go of the next one's.
    private fun stop() {
        job?.cancel()
        job = null
    }

    private fun launchRun() {
        if (!at.lat.isFinite() || !at.lon.isFinite()) {
            _state.value = LidarLookup()
            return
        }
        job = runScope.launch {
            val lookup = Lookup()
            try {
                lookup.run()
            } finally {
                lookup.letGo()
            }
        }
    }

    /** One pass: the web's effect body, with its counters and the build it waits on. */
    private inner class Lookup {
        private var failures = 0
        private var resubmits = 0
        private var resolvedTarget: LidarTargetDto? = null
        private var waitingOn: String? = null

        /**
         * A failure that came in just as the pass was stopped is not acted on (the web's `if (cancelled) return`): an answer already on its way wins
         * over a cancellation in a coroutine, and acting on it would settle the build as failed and let go of nothing.
         */
        suspend fun stillWanted() = currentCoroutineContext().ensureActive()

        /** The pass ended unfinished (stopped, or its scope ended): this device no longer waits for the build. */
        fun letGo() {
            waitingOn?.let(builds::release)
            waitingOn = null
        }

        suspend fun run() {
            _state.value = LidarLookup(LidarPhase.RESOLVING)
            builds.afterAbandonedReleased()
            val data = try {
                builds.resolve(at, radiusM, stream)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stillWanted()
                return fail(if (e is NetworkException) LidarProblem.LOOKUP_UNREACHABLE else LidarProblem.LOOKUP_FAILED)
            }
            if (data.available) return finish(data.key, data.url, data.contextUrl, data.streamed == true, data.frame)
            // The place the server resolved, so a server that cannot build can say what an operator would build.
            resolvedTarget = data.target
            if (data.canBuild == true) {
                showJob(data.key, stage = "starting", position = null, elapsedS = null)
                startBuild()
                return
            }
            _state.value = LidarLookup(LidarPhase.MISSING, key = data.key.orNull(), target = resolvedTarget)
        }

        private suspend fun startBuild() {
            val job = try {
                builds.build(at, keep, radiusM)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stillWanted()
                return fail(
                    when (e) {
                        is RateLimitedException -> LidarProblem.QUEUE_FULL
                        is NetworkException -> LidarProblem.BUILD_UNREACHABLE
                        else -> LidarProblem.BUILD_REFUSED
                    },
                    buildFailed = true,
                )
            }
            if (job.state == "done" && !job.url.isNullOrEmpty()) return finish(job.key, job.url, job.contextUrl)
            showJob(job.key, job.stage, job.position, job.elapsedS)
            poll(job.key)
        }

        private suspend fun poll(key: String) {
            while (true) {
                delay(LidarBuilds.POLL_MS)
                val job = try {
                    builds.status(key, keep)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stillWanted()
                    if (e is ApiException && e.status == 404 && resubmits < LidarBuilds.MAX_RESUBMITS) {
                        // A restarted service forgets its jobs: asking again queues the same place.
                        resubmits += 1
                        return startBuild()
                    }
                    failures += 1
                    if (failures >= LidarBuilds.MAX_POLL_FAILURES) return fail(LidarProblem.LOST_CONTACT, buildFailed = true)
                    continue
                }
                failures = 0
                if (job.state == "done" && !job.url.isNullOrEmpty()) return finish(job.key, job.url, job.contextUrl)
                if (job.state == "failed") return fail(LidarProblem.BUILD_FAILED, buildFailed = true)
                // Dropped as unwatched while this view still waited (the app was in the back longer than the service's lease): ask again.
                if (job.state == "cancelled" && resubmits < LidarBuilds.MAX_RESUBMITS) {
                    resubmits += 1
                    return startBuild()
                }
                showJob(job.key, job.stage, job.position, job.elapsedS)
            }
        }

        private fun showJob(key: String?, stage: String?, position: Int?, elapsedS: Int?) {
            val known = key.orNull()
            if (known != null && known != waitingOn) {
                waitingOn = known
                builds.markInFlight(known)
            }
            _state.value = LidarLookup(
                LidarPhase.BUILDING, key = known, stage = stage.orNull() ?: "waiting", position = position, elapsedS = elapsedS, target = resolvedTarget,
            )
        }

        private fun settled() {
            waitingOn?.let(builds::clearInFlight)
            waitingOn = null
        }

        private fun finish(key: String?, url: String?, contextUrl: String?, streamed: Boolean = false, frame: LidarFrameDto? = null) {
            settled()
            _state.value = LidarLookup(
                LidarPhase.AVAILABLE, key = key.orNull(), url = url, contextUrl = contextUrl.orNull(), streamed = streamed, frame = frame,
            )
        }

        private fun fail(problem: LidarProblem, buildFailed: Boolean = false) {
            settled()
            _state.value = LidarLookup(LidarPhase.ERROR, problem = problem, buildFailed = buildFailed, target = resolvedTarget)
        }
    }
}
