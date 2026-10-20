package app.ezpztac.viewer3d

import app.ezpztac.data.TerrainHeights
import app.ezpztac.data.TilesetAddress
import app.ezpztac.data.TilesetFiles
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.scene3d.CameraFlight
import app.ezpztac.scene3d.CameraGesture
import app.ezpztac.scene3d.CameraMoves
import app.ezpztac.scene3d.CameraPose
import app.ezpztac.scene3d.CompassPose
import app.ezpztac.scene3d.DrawnTile
import app.ezpztac.scene3d.ImageryDraw
import app.ezpztac.scene3d.ImageryScene
import app.ezpztac.scene3d.ImageryTileId
import app.ezpztac.scene3d.LocalFrame
import app.ezpztac.scene3d.PntsReader
import app.ezpztac.scene3d.PointCloudScene
import app.ezpztac.scene3d.SceneDepth
import app.ezpztac.scene3d.SceneGround
import app.ezpztac.scene3d.SceneLook
import app.ezpztac.scene3d.Sphere
import app.ezpztac.scene3d.TerrainDraw
import app.ezpztac.scene3d.TerrainScene
import app.ezpztac.scene3d.TerrainTileId
import app.ezpztac.scene3d.Tile
import app.ezpztac.scene3d.TileFetch
import app.ezpztac.scene3d.TilesetDocument
import app.ezpztac.scene3d.TilesetReader
import app.ezpztac.scene3d.ViewCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs

/** Why the scene is not drawn, for the page to say in the app's own words, and whether trying again may help. */
enum class SceneProblem(val retryable: Boolean) {
    /** The point cloud's tileset could not be fetched: no connection, or the server busy. */
    UNREACHABLE(true),

    /** Its tileset came and could not be read, or the server refused it. */
    UNREADABLE(true),

    /** The server named the point cloud in a form this version cannot open: trying again cannot help. */
    NOT_A_TILESET(false),

    /** The device has no OpenGL ES 3.0. */
    NO_GL(false),

    /** OpenGL would not set the scene up (a shader or a target refused). */
    GL_FAILED(false),
}

/** Where the scene stands: its tileset on its way, drawn (its tiles still filling in), or not drawn, and why. */
sealed interface SceneStatus {
    data object Loading : SceneStatus

    data object Drawing : SceneStatus

    data class Failed(val problem: SceneProblem) : SceneStatus
}

/**
 * How many points the scene draws in a frame and holds at most: a quarter of the app's heap for the points in hand, 15 bytes each (they are held on
 * the GPU too, which is not the heap), and half that drawn, never more than [PointCloudScene]'s three and six million.
 */
data class SceneBudgets(val draw: Long, val hold: Long) {
    companion object {
        val DEFAULT: SceneBudgets = SceneBudgets(PointCloudScene.DRAW_BUDGET, PointCloudScene.HOLD_BUDGET)

        private const val BYTES_PER_POINT = 15L
        private const val MIN_HOLD = 1_000_000L

        /** For a heap of [memoryClassMb] megabytes (`ActivityManager.memoryClass`). */
        fun forHeap(memoryClassMb: Int): SceneBudgets {
            val hold = (memoryClassMb.toLong() * 1024 * 1024 / 4 / BYTES_PER_POINT).coerceIn(MIN_HOLD, PointCloudScene.HOLD_BUDGET)
            return SceneBudgets(minOf(PointCloudScene.DRAW_BUDGET, hold / 2), hold)
        }
    }
}

/**
 * Where a scene's parts come from: the point cloud's [files]; the ground's [heights] (none: no ground is drawn); and the satellite [imagery]
 * laid on it, read by [decoder] (none: the ground is bare).
 */
class SceneSources(
    val files: TilesetFiles,
    val heights: TerrainHeights? = null,
    val imagery: ImageryTiles? = null,
    val decoder: ImageryDecoder? = null,
)

/** Makes the scene of a point cloud: a [SceneSession] over the server's files in the app; tests give theirs their own dispatchers and clock. */
fun interface SceneMaker {
    fun make(target: LatLon, core: TilesetAddress?, ring: TilesetAddress?, parent: CoroutineScope, pointSize: Int): SceneSession
}

/**
 * What the renderer draws one frame from: the camera, each tileset's tiles (lit apart), what was let go of, the point size, and whether a
 * flight goes on; and the ground: its tiles, nearest first ([ground]), the imagery to lay on each ([imagery], coarse first; a tile not in
 * [imageryWhole] is drawn in the ground's own colour first, part of it having none yet), the imagery decoded and waiting for the GPU
 * ([uploads], which the renderer puts there and says so, [SceneSession.uploaded]), and what of each was let go of.
 */
class SceneWork(
    val camera: ViewCamera,
    val clouds: List<List<DrawnTile>>,
    val letGo: List<Tile>,
    val pointSize: Int,
    val animating: Boolean,
    val ground: List<TerrainDraw> = emptyList(),
    val groundLetGo: List<TerrainTileId> = emptyList(),
    val imagery: Map<TerrainTileId, List<ImageryDraw>> = emptyMap(),
    val imageryWhole: Set<TerrainTileId> = emptySet(),
    val uploads: List<Pair<ImageryTileId, ImageryImage>> = emptyList(),
    val imageryLetGo: List<ImageryTileId> = emptyList(),
)

/**
 * One LZ/PZ's scene while its 3D view is open (docs/ANDROID_3D_PLAN.md, V4): the point cloud's [core] and the [ring] round it, their tiles fetched
 * as the frames ask ([PointCloudScene]) and read off the GL thread, the camera and its flights, and the point size. It lives in the view model,
 * so the GL view (and its context) can come and go and find everything in hand. Safe from any thread: frames come from the GL thread, the fingers
 * and the buttons from the main one, the tiles from the fetches'.
 *
 * As the web's: the view opens framing the core's sphere; the ring is asked for once the core is drawing, and one that cannot be had is no ring
 * (it is optional, and losing it must not lose the LZ). **Not like the web**, a tile that met no connection is asked for again ([PointCloudScene]).
 */
class SceneSession(
    target: LatLon,
    private val core: TilesetAddress?,
    private val ring: TilesetAddress?,
    sources: SceneSources,
    budgets: SceneBudgets,
    parent: CoroutineScope,
    pointSize: Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val decode: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The scene's frame: east, north and up at the LZ, on the ellipsoid. */
    val frame: LocalFrame = LocalFrame(target.lat, target.lon)

    private val files = sources.files
    private val heights = sources.heights
    private val imageryTiles = sources.imagery
    private val decoder = sources.decoder
    private val scene = PointCloudScene(budgets.draw, budgets.hold)
    private val terrain = TerrainScene()
    private val imagery = ImageryScene()

    // Imagery decoded and waiting for the GPU, in the order it came; let go of at close.
    private val pending = LinkedHashMap<ImageryTileId, ImageryImage>()

    @Volatile private var closed = false
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Any()

    private val _status = MutableStateFlow<SceneStatus>(if (core == null) SceneStatus.Failed(SceneProblem.NOT_A_TILESET) else SceneStatus.Loading)
    val status: StateFlow<SceneStatus> = _status.asStateFlow()

    private val _compass = MutableStateFlow<CompassPose?>(null)

    /** The compass for the camera as last drawn; null until the first frame. */
    val compass: StateFlow<CompassPose?> = _compass.asStateFlow()

    /** Asks for a frame (the GL view's `requestRender`); set while a view draws this scene. */
    @Volatile var listener: (() -> Unit)? = null

    @Volatile var pointSize: Int = SceneLook.pointSizeOrDefault(pointSize)
        private set

    /**
     * The level ground the camera's moves hold to: the terrain's own height at the LZ, from the finest of its tiles in hand; until there is
     * some, the first tile's points round the LZ; until then, the cloud's middle.
     */
    internal val groundZ: Double? get() = synchronized(lock) { moves?.groundZ }

    // Under [lock].
    private var pose: CameraPose? = null
    private var flight: CameraFlight? = null
    private var flightStart = 0L
    private var lz: Sphere? = null
    private var moves: CameraMoves? = null
    // Where the moves' ground came from: nothing yet (the cloud's middle), the first tile's points, or the terrain at a level (finer is better).
    private var groundFrom = GROUND_FROM_NOTHING
    private var viewport: Viewport? = null
    private var coreRoot: Tile? = null
    private var ringRoot: Tile? = null

    private class Viewport(val width: Int, val height: Int, val pixelRatio: Double)

    init {
        if (core != null) scope.launch { loadCore(core) }
    }

    /** *Retry* after the core could not be had. */
    fun retry() {
        val address = core ?: return
        val now = _status.value
        if (now !is SceneStatus.Failed || !now.problem.retryable) return
        _status.value = SceneStatus.Loading
        scope.launch { loadCore(address) }
    }

    /** The GL view cannot draw it: said once, and kept. */
    fun cannotDraw(problem: SceneProblem) {
        _status.value = SceneStatus.Failed(problem)
    }

    /** The view closed: every fetch stops and every point, tile of ground and image is let go of. */
    fun close() {
        listener = null
        scope.cancel()
        scene.clear()
        terrain.clear()
        imagery.clear()
        synchronized(pending) {
            closed = true
            pending.values.forEach { it.release() }
            pending.clear()
        }
    }

    /** The renderer put [id] on the GPU, where it takes [bytes]. */
    fun uploaded(id: ImageryTileId, bytes: Long) {
        imagery.arrived(id, bytes)
    }

    /** The renderer could not put [id] on the GPU (it had no room): not tried again this session. */
    fun notUploaded(id: ImageryTileId) {
        imagery.failed(id, again = false)
    }

    /** The GL context went, and the imagery's textures with it: they are fetched again (the HTTP cache has them). */
    fun texturesLost() {
        imagery.lostTextures()
    }

    fun setPointSize(size: Int) {
        pointSize = SceneLook.pointSizeOrDefault(size)
        invalidate()
    }

    /**
     * The frame to draw now on a view of [widthPx] by [heightPx] at [pixelRatio] (the GL thread's): the camera where its flight has got to, the
     * tiles to draw and to let go of; the fetches it wants are started here. Null before the core's tileset is in hand.
     */
    fun frame(widthPx: Int, heightPx: Int, pixelRatio: Double): SceneWork? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val now = clock()
        val camera: ViewCamera
        val animating: Boolean
        synchronized(lock) {
            viewport = Viewport(widthPx, heightPx, pixelRatio)
            val at = poseAt(now) ?: return null
            camera = at.camera(widthPx, heightPx, pixelRatio, SceneDepth.NEAR, SceneDepth.FAR)
            animating = flight != null
        }
        updateCompass(camera)
        val work = scene.frame(camera, now)
        for (fetch in work.fetch) fetch(fetch)
        val ground = if (heights != null) terrain.frame(camera, frame, now) else null
        ground?.fetch?.forEach(::fetchGround)
        val laid = if (ground != null && imageryTiles != null && decoder != null) imagery.frame(ground.drawn.map { it.tile }, now) else null
        laid?.fetch?.forEach(::fetchImagery)
        val uploads = synchronized(pending) {
            pending.entries.take(MAX_UPLOADS).map { it.key to it.value }.also { taken -> taken.forEach { pending.remove(it.first) } }
        }
        return SceneWork(
            camera, work.byCloud, work.letGo, pointSize, animating,
            ground = ground?.drawn.orEmpty(), groundLetGo = ground?.letGo.orEmpty(),
            imagery = laid?.byTerrain.orEmpty(), imageryWhole = laid?.complete.orEmpty(), uploads = uploads, imageryLetGo = laid?.letGo.orEmpty(),
        )
    }

    /** The fingers moved the camera; one on it ends a flight where it has got to. */
    fun gesture(gestures: List<CameraGesture>) {
        if (gestures.isEmpty()) return
        synchronized(lock) {
            val view = viewport ?: return
            val m = moves ?: return
            var at = poseAt(clock()) ?: return
            flight = null
            for (g in gestures) {
                val camera = at.camera(view.width, view.height, view.pixelRatio, SceneDepth.NEAR, SceneDepth.FAR)
                at = when (g) {
                    is CameraGesture.Pan -> m.pan(camera, g.from, g.to)
                    is CameraGesture.Zoom -> m.zoom(camera, g.at, g.factor)
                    is CameraGesture.Turn -> m.turn(camera, g.angle)
                    is CameraGesture.Tilt -> m.tilt(camera, g.by)
                }
            }
            pose = at
        }
        invalidate()
    }

    /** *LZ*: flies back to the view the scene opened on, the core's sphere framed, in [SceneLook.FRAME_MS]. */
    fun frameLz() {
        synchronized(lock) {
            val sphere = lz ?: return
            fly(CameraPose.framing(frame, sphere), SceneLook.FRAME_MS)
        }
        invalidate()
    }

    /** The compass: turns to face north about the ground in the middle of the view, in [SceneLook.FACE_NORTH_MS]. */
    fun faceNorth() {
        synchronized(lock) {
            val view = viewport ?: return
            val m = moves ?: return
            val at = poseAt(clock()) ?: return
            fly(m.facingNorth(at.camera(view.width, view.height, view.pixelRatio, SceneDepth.NEAR, SceneDepth.FAR)), SceneLook.FACE_NORTH_MS)
        }
        invalidate()
    }

    // Under [lock].
    private fun fly(to: CameraPose, durationMs: Long) {
        val now = clock()
        val from = poseAt(now) ?: return
        flight = CameraFlight(from, to, durationMs)
        flightStart = now
    }

    // Under [lock]: the pose now, a flight that has ended landed.
    private fun poseAt(now: Long): CameraPose? {
        val f = flight ?: return pose
        val elapsed = now - flightStart
        val at = f.at(elapsed)
        if (f.done(elapsed)) flight = null
        pose = at
        return at
    }

    // Below a tenth of a degree the change is invisible, as the web's compass leaves the page alone.
    private fun updateCompass(camera: ViewCamera) {
        val next = CompassPose.of(camera.heading, camera.pitch)
        val last = _compass.value
        if (last != null && abs(next.rotateDeg - last.rotateDeg) < COMPASS_CHANGE_DEG && abs(next.tiltDeg - last.tiltDeg) < COMPASS_CHANGE_DEG) return
        _compass.value = next
    }

    private fun invalidate() {
        listener?.invoke()
    }

    private suspend fun loadCore(address: TilesetAddress) {
        try {
            val doc = read(address)
            val sphere = Sphere(doc.root.volume.center, doc.root.volume.radius)
            synchronized(lock) {
                coreRoot = doc.root
                lz = sphere
                if (pose == null) pose = CameraPose.framing(frame, sphere)
                // Until a tile's points say where the ground is, the middle of the cloud.
                if (moves == null) moves = CameraMoves(sphere.center.z)
            }
            scene.add(doc, SceneLook.CORE_SCREEN_SPACE_ERROR)
            _status.value = SceneStatus.Drawing
            invalidate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _status.value = SceneStatus.Failed(if (retryable(e)) SceneProblem.UNREACHABLE else SceneProblem.UNREADABLE)
            return
        }
        ring?.let { loadRing(it) }
    }

    private suspend fun loadRing(address: TilesetAddress) {
        try {
            val doc = read(address)
            synchronized(lock) { ringRoot = doc.root }
            scene.add(doc, SceneLook.CONTEXT_SCREEN_SPACE_ERROR)
            invalidate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The ring is optional: the LZ is drawn without it.
        }
    }

    private suspend fun read(address: TilesetAddress): TilesetDocument {
        val bytes = withContext(io) { files.file(address.key, address.path) }
        return withContext(decode) { TilesetReader.read(bytes, address.path, frame) }
    }

    // Started from a frame: fetched, read off the GL thread, and handed to the scene, which then wants a frame.
    private fun fetch(fetch: TileFetch) {
        val key = keyOf(fetch.tile)
        if (key == null) {
            scene.failed(fetch.tile, again = false)
            return
        }
        scope.launch {
            try {
                val bytes = withContext(io) { files.file(key, fetch.path) }
                if (fetch.isTileset) {
                    withContext(decode) { scene.joined(fetch.tile, bytes, frame) }
                } else {
                    val points = withContext(decode) { PntsReader.read(bytes, fetch.tile.world, frame) }
                    scene.arrived(fetch.tile, points)
                    if (synchronized(lock) { groundFrom } < GROUND_FROM_POINTS) {
                        withContext(decode) { SceneGround.of(points) }?.let { holdTo(it, GROUND_FROM_POINTS) }
                    }
                }
                invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                scene.failed(fetch.tile, retryable(e))?.let(::wake)
            }
        }
    }

    // The moves hold to [z] if it comes from somewhere better than what they hold to now.
    private fun holdTo(z: Double, from: Int) {
        synchronized(lock) {
            if (from <= groundFrom) return
            moves = CameraMoves(z)
            groundFrom = from
        }
    }

    // A frame must be drawn when a failed tile's pause is over, or nothing asks again until a finger moves.
    private fun wake(atMs: Long) {
        scope.launch {
            delay((atMs - clock()).coerceAtLeast(0) + 1)
            invalidate()
        }
    }

    // A tile of ground: its heights fetched (background work, held while heavy work runs) and read off the GL thread; none where the server has none.
    private fun fetchGround(tile: TerrainTileId) {
        val source = heights ?: return
        scope.launch {
            try {
                val answer = withContext(io) { source.tile(tile) }
                withContext(decode) { terrain.arrived(tile, answer, frame) }
                terrain.heightAt(frame.lat, frame.lon)?.let { (level, height) -> holdTo(frame.fromGeodetic(frame.lat, frame.lon, height).z, GROUND_FROM_TERRAIN + level) }
                invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                terrain.failed(tile, retryable(e))?.let(::wake)
            }
        }
    }

    // A tile of imagery: fetched, decoded off the GL thread, and left for the renderer to put on the GPU.
    private fun fetchImagery(id: ImageryTileId) {
        val source = imageryTiles ?: return
        val reader = decoder ?: return
        scope.launch {
            try {
                val bytes = withContext(io) { source.tile(id) }
                val image = withContext(decode) { reader.decode(bytes) }
                if (image == null) {
                    imagery.failed(id, again = false)
                    return@launch
                }
                val kept = synchronized(pending) { if (closed) false else true.also { pending[id] = image } }
                if (!kept) image.release()
                invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                imagery.failed(id, again = e !is ImageryRefused && (e is IOException || retryable(e)))?.let(::wake)
            }
        }
    }

    // A tile is the core's or the ring's by the root it hangs from (a tileset joined into a tile hangs from that tile).
    private fun keyOf(tile: Tile): String? {
        val root = generateSequence(tile) { it.parent }.last()
        return synchronized(lock) {
            when {
                root === coreRoot -> core?.key
                root === ringRoot -> ring?.key
                else -> null
            }
        }
    }

    private companion object {
        const val COMPASS_CHANGE_DEG = 0.1

        /** Imagery put on the GPU in a frame at most: each is a few milliseconds of the GL thread. */
        const val MAX_UPLOADS = 3

        const val GROUND_FROM_NOTHING = 0
        const val GROUND_FROM_POINTS = 1
        const val GROUND_FROM_TERRAIN = 2

        // No connection, the server busy or down: worth another try. A refusal (a 404, a file too large) or a file that cannot be read is not.
        fun retryable(e: Exception): Boolean = when (e) {
            is NetworkException, is RateLimitedException -> true
            is ApiException -> e.status >= 500 || e.status == 408
            else -> false
        }
    }
}
