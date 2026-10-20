package app.ezpztac.viewer3d

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.LidarBuilds
import app.ezpztac.data.LidarLookup
import app.ezpztac.data.LidarLookupRun
import app.ezpztac.data.LidarPhase
import app.ezpztac.data.LidarProblem
import app.ezpztac.data.TerrainProbe
import app.ezpztac.data.TilesetAddress
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.LatLon
import app.ezpztac.scene3d.SceneLook
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the page shows: its title, how the point cloud stands ([phase]; while it builds, the service's [stage], the builds [ahead] of it and the
 * seconds it has run; why it failed, [problem]), whether the server has no ground there ([noGround]), and where the tileset is once it is
 * built ([tileset], [context], relative to the API's root).
 */
data class Viewer3DUi(
    val name: String,
    val phase: LidarPhase,
    val stage: String? = null,
    val ahead: Int? = null,
    val elapsedS: Int? = null,
    val saved: Boolean = false,
    /** A mission pack's LZ/PZ, which is never saved to the Library. */
    val inPack: Boolean = false,
    val problem: LidarProblem? = null,
    val buildFailed: Boolean = false,
    val noGround: Boolean = false,
    val tileset: String? = null,
    val context: String? = null,
) {
    val title: String get() = Viewer3DWords.title(name)

    companion object {
        fun of(target: Viewer3DTarget, lookup: LidarLookup, ground: Boolean?): Viewer3DUi = Viewer3DUi(
            name = target.name,
            phase = lookup.phase,
            stage = lookup.stage,
            ahead = lookup.position?.takeIf { it > 0 },
            elapsedS = lookup.elapsedS,
            saved = target.saved,
            inPack = PackRef.parse(target.uuid) != null,
            problem = lookup.problem,
            buildFailed = lookup.buildFailed,
            // Said only with the cloud to see, as the web does: until then there is nothing to be under.
            noGround = lookup.phase == LidarPhase.AVAILABLE && ground == false,
            tileset = lookup.url,
            context = lookup.contextUrl,
        )
    }
}

/**
 * The 3D view's state: one lookup (core-data's [LidarBuilds]) for each LZ/PZ it is shown on, the web's probe for ground there, and once the point
 * cloud is in hand its scene ([SceneSession]: the tiles, the camera). The view model outlives the page (it is the activity's), so [show] starts
 * what an opening needs and [close] lets go of it; a new target, or the same one moved, starts again, and only its being saved changes on the run.
 * The point size is the person's, kept on the device ([PointSizeMemory]).
 */
@HiltViewModel
class Viewer3DViewModel @Inject constructor(
    private val lidar: LidarBuilds,
    private val probe: TerrainProbe,
    private val scenes: SceneMaker,
    private val pointSizes: PointSizeMemory,
) : ViewModel() {
    private val target = MutableStateFlow<Viewer3DTarget?>(null)
    private val lookup = MutableStateFlow(LidarLookup())
    private val ground = MutableStateFlow<Boolean?>(null)
    private var run: LidarLookupRun? = null
    private var jobs: List<Job> = emptyList()
    private val _session = MutableStateFlow<SceneSession?>(null)
    private var sceneOf: Pair<String?, String?>? = null
    private val _pointSize = MutableStateFlow(SceneLook.pointSizeOrDefault(pointSizes.get()))

    /** The scene of the point cloud being shown; null until it is in hand. */
    val session: StateFlow<SceneSession?> = _session.asStateFlow()

    /** The point size the person chose, 2 to 16. */
    val pointSize: StateFlow<Int> = _pointSize.asStateFlow()

    /** What the page shows; null while it is closed. */
    val state: StateFlow<Viewer3DUi?> = combine(target, lookup, ground) { t, l, g -> t?.let { Viewer3DUi.of(it, l, g) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The page is shown on [shown]: the same place goes on as it was (saving it marks its build kept); another starts afresh. */
    fun show(shown: Viewer3DTarget) {
        val current = target.value
        if (current != null && current.uuid == shown.uuid && current.lat == shown.lat && current.lon == shown.lon) {
            if (current.saved != shown.saved) run?.setKeep(shown.saved)
            target.value = shown
            return
        }
        close()
        target.value = shown
        val at = LatLon(shown.lat, shown.lon)
        val started = lidar.start(viewModelScope, at, keep = shown.saved)
        run = started
        jobs = listOf(
            viewModelScope.launch {
                started.state.collect {
                    lookup.value = it
                    if (it.phase == LidarPhase.AVAILABLE) scene(shown, it)
                }
            },
            viewModelScope.launch { ground.value = probe.hasGround(at) },
        )
    }

    // The cloud is in hand: its scene, made once for where it is (the core and the ring the lookup named).
    private fun scene(target: Viewer3DTarget, found: LidarLookup) {
        val at = found.url to found.contextUrl
        if (_session.value != null && sceneOf == at) return
        _session.value?.close()
        sceneOf = at
        _session.value = scenes.make(LatLon(target.lat, target.lon), TilesetAddress.of(found.url), TilesetAddress.of(found.contextUrl), viewModelScope, _pointSize.value)
    }

    /** The point size, 2 to 16, kept for next time. */
    fun setPointSize(size: Int) {
        val chosen = SceneLook.pointSizeOrDefault(size)
        if (chosen == _pointSize.value) return
        _pointSize.value = chosen
        pointSizes.set(chosen)
        _session.value?.setPointSize(chosen)
    }

    /** The scene's *Retry*, when its tileset could not be had. */
    fun retryScene() {
        _session.value?.retry()
    }

    /** *Retry* and *Check again*. */
    fun retry() {
        run?.retry()
    }

    /** The page closed: the lookup lets go of its build (unless the LZ/PZ is saved, which the service keeps building). */
    fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        run?.close()
        run = null
        _session.value?.close()
        _session.value = null
        sceneOf = null
        target.value = null
        lookup.value = LidarLookup()
        ground.value = null
    }

    override fun onCleared() {
        run?.close()
        _session.value?.close()
    }
}
