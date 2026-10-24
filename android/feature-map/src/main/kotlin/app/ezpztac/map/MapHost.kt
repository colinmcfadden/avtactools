package app.ezpztac.map

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ezpztac.designsystem.PanelSheetDefaults
import app.ezpztac.model.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The map the screens drive. It holds MapLibre's [MapLibreMap] once the view has made one and queues what it is asked in the meantime,
 * so a screen can say "fly to the target" before the map exists.
 *
 * Nothing here can be exercised without a GPU, so none of it is tested: the logic that decides *what* to ask (the styles, the readout, the
 * search, where the camera goes) lives in plain functions that are.
 */
@Stable
class MapHost {
    private var map: MapLibreMap? = null
    private val waiting = mutableListOf<(MapLibreMap) -> Unit>()
    private val styleListeners = mutableListOf<(Style) -> Unit>()

    /** The current camera, once there is a map. Compose reads this, so the readout follows the crosshair. */
    var camera: CameraState? by mutableStateOf(null)
        private set

    /** The strips at the top and bottom of the map that the app's controls cover, in pixels: the camera's centre is the middle of the rest. */
    private var padding = Padding(0, 0)

    /** Whether a padding has been set: the first is applied at once, later ones glide. */
    private var padded = false

    /** A flight under way ([flyTo], [faceNorth]): a change of padding while it lasts is folded into it rather than cutting it short. */
    private var flight: Flight? = null

    /** Whether the camera's last move began with the person's own gesture (a pan, a pinch), not the app's (a flight to a GPS fix or a search, a view put back). */
    private var byHand = false

    internal fun moveStarted(reason: Int) {
        byHand = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
    }

    private data class Padding(val top: Int, val bottom: Int) {
        fun array() = doubleArrayOf(0.0, top.toDouble(), 0.0, bottom.toDouble())
    }

    private class Flight(val target: LatLng, val zoom: Double?, val bearing: Double?, val endsAt: Long)

    /**
     * Reserves [topPx] at the top of the map and [bottomPx] at its bottom (the search bar, the sheet): MapLibre then reports the middle of the
     * rest as the camera's target, which is where the crosshair is drawn. What was under the crosshair stays under it, the map gliding to the new
     * middle as the crosshair does; the first padding is applied at once. A flight under way carries on to its target, framed by the new padding.
     */
    internal fun setPadding(topPx: Int, bottomPx: Int) = whenReady { map ->
        val next = Padding(topPx, bottomPx)
        if (padded && next == padding) return@whenReady
        val first = !padded
        padding = next
        padded = true
        val going = flight
        when {
            going != null -> fly(map, going.target, going.zoom, (going.endsAt - SystemClock.uptimeMillis()).coerceAtLeast(MIN_FLIGHT_MS), going.bearing)
            first -> map.moveCamera(CameraUpdateFactory.paddingTo(next.array()))
            else -> map.easeCamera(CameraUpdateFactory.paddingTo(next.array()), PanelSheetDefaults.SettleMillis)
        }
        publishCamera(map)
    }

    /**
     * The map is made: it starts at [initial], and what was asked of it meanwhile (a flight to an LZ/PZ opened at launch, the sandtable's flight to its step after the
     * activity was made again) is done after that, in the order asked, so the first camera never undoes it.
     */
    internal fun attach(map: MapLibreMap, initial: CameraState) {
        this.map = map
        jumpTo(initial)
        waiting.toList().also { waiting.clear() }.forEach { it(map) }
        publishCamera(map)
    }

    internal fun detach() {
        map = null
        flight = null
    }

    internal fun publishCamera(map: MapLibreMap) {
        val position = map.cameraPosition
        val target = position.target ?: return
        // The camera's own padding, which moves while it glides; what was asked for when the map has none to say.
        val now = position.padding
        camera = CameraState(
            LatLon(target.latitude, target.longitude), position.zoom, position.bearing,
            paddingTopPx = now?.getOrNull(1) ?: padding.top.toDouble(),
            paddingBottomPx = now?.getOrNull(3) ?: padding.bottom.toDouble(),
            byHand = byHand,
        )
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val ready = map
        if (ready != null) action(ready) else waiting += action
    }

    /** Moves the camera with an animation, to [at] and, if given, [zoom]. */
    fun flyTo(at: LatLon, zoom: Double? = null) = whenReady { map -> fly(map, LatLng(at.lat, at.lon), zoom, FLIGHT_MS) }

    /**
     * Moves the camera to [at] at [zoom], turned to [bearing], as a flight of [durationMs], or at once when that is 0 (animations off on the device): the live
     * sandtable's flight to a step (feature-brief), which frames its figure north up. A padding that changes meanwhile is folded into the flight, as for [flyTo].
     */
    fun flyTo(at: LatLon, zoom: Double, bearing: Double, durationMs: Long) = whenReady { map ->
        if (durationMs <= 0) {
            flight = null
            val position = CameraPosition.Builder().target(LatLng(at.lat, at.lon)).zoom(zoom).bearing(bearing).padding(padding.array())
            map.moveCamera(CameraUpdateFactory.newCameraPosition(position.build()))
        } else {
            fly(map, LatLng(at.lat, at.lon), zoom, durationMs, bearing)
        }
    }

    private fun fly(map: MapLibreMap, target: LatLng, zoom: Double?, durationMs: Long, bearing: Double? = null) {
        val going = Flight(target, zoom, bearing, SystemClock.uptimeMillis() + durationMs)
        flight = going
        val position = CameraPosition.Builder().target(target).padding(padding.array())
        if (zoom != null) position.zoom(zoom)
        if (bearing != null) position.bearing(bearing)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(position.build()), durationMs.toInt(),
            object : MapLibreMap.CancelableCallback {
                // Only this flight's own ending forgets it: one folded into a new padding is cancelled on purpose and replaced.
                override fun onFinish() { if (flight === going) flight = null }
                override fun onCancel() { if (flight === going) flight = null }
            },
        )
    }

    /**
     * Moves the camera at once: for the first frame, and to give the view back when the live sandtable ends. It ends a flight under way, so a padding that changes
     * next does not carry that flight on to where it was going.
     */
    fun jumpTo(state: CameraState) = whenReady { map ->
        flight = null
        val position = CameraPosition.Builder().target(LatLng(state.center.lat, state.center.lon)).zoom(state.zoom).bearing(state.bearingDegrees)
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position.padding(padding.array()).build()))
    }

    /**
     * Turns the map back to north, as a flight, so the sheet settling meanwhile (a new padding) carries the turn on rather than stopping it part way. A flight
     * already under way keeps going where it was going, turned north as well.
     */
    fun faceNorth() = whenReady { map ->
        val going = flight
        val target = going?.target ?: map.cameraPosition.target ?: return@whenReady
        fly(map, target, going?.zoom, FACE_NORTH_MS, bearing = 0.0)
    }

    /** Runs [install] each time a style finishes loading (and now, if one has): overlays have to be added again for every style. */
    fun onStyle(install: (Style) -> Unit) {
        styleListeners += install
        map?.style?.takeIf { it.isFullyLoaded }?.let(install)
    }

    internal fun styleLoaded(style: Style) = styleListeners.toList().forEach { it(style) }

    internal fun applyStyle(style: MapBaseStyle) = whenReady { map ->
        map.setStyle(Style.Builder().fromJson(MapStyles.styleJson(style))) { loaded -> styleLoaded(loaded) }
    }

    /** What is under a point on the screen, for taps; `null` while there is no map. */
    fun screenToLatLon(x: Float, y: Float): LatLon? = map?.projection?.fromScreenLocation(android.graphics.PointF(x, y))?.let { LatLon(it.latitude, it.longitude) }
}

@Composable
fun rememberMapHost(): MapHost = remember { MapHost() }

/**
 * The MapLibre view, with its lifecycle forwarded and the style kept in step with [style]. The first camera is [initial]; after that the
 * person moves it. Attribution stays on (Mapbox's and the FAA's terms require it), the logo is MapLibre's own and is hidden, and tilt is
 * off: this is a flat planning map.
 */
@Composable
fun EzpzMap(
    host: MapHost,
    style: MapBaseStyle,
    initial: CameraState,
    modifier: Modifier = Modifier,
    /** The strips at the top and bottom that the app's controls cover (the search bar, the sheet): the crosshair is the middle of the rest. */
    padding: MapPadding = MapPadding(),
    /** A tap on the map, where it landed. Not told of a drag or a pinch. */
    onTap: (LatLon) -> Unit = {},
    /** A long press at this place: true when something there was picked up, so the finger now drags it (the map does not pan). */
    onPickUp: (LatLon) -> Boolean = { false },
    /** The finger holding what was picked up is now here. */
    onDrag: (LatLon) -> Unit = {},
    /** The finger lifted here: what was picked up is put down. */
    onDrop: (LatLon) -> Unit = {},
    /** The touch was taken from the drag: what was picked up goes back. */
    onDragCancelled: () -> Unit = {},
    /** A long press that picked nothing up, at this place and these pixels of the map: true when it was taken (a menu opened), so the map does not pan with the finger. */
    onPress: (LatLon, Offset) -> Boolean = { _, _ -> false },
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context) { createMapView(context) }
    val tap by rememberUpdatedState(onTap)
    val pickUp by rememberUpdatedState(onPickUp)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancelled by rememberUpdatedState(onDragCancelled)
    val press by rememberUpdatedState(onPress)

    DisposableEffect(owner, view) {
        view.onCreate(Bundle())
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        // Join in at the owner's current state: the observer only hears what happens next.
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        view.listener = object : LongPressTouches.Listener {
            private fun at(x: Float, y: Float) = host.screenToLatLon(x, y)
            override fun onPickUp(x: Float, y: Float) = at(x, y)?.let(pickUp) ?: false
            override fun onDrag(x: Float, y: Float) { at(x, y)?.let(drag) }
            override fun onDrop(x: Float, y: Float) { at(x, y)?.let(drop) ?: cancelled() }
            override fun onDragCancelled() = cancelled()
            override fun onPress(x: Float, y: Float) = at(x, y)?.let { press(it, Offset(x, y)) } ?: false
        }
        view.getMapAsync { map ->
            map.uiSettings.apply {
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                isCompassEnabled = false                       // the app draws its own, in the design system
            }
            map.addOnCameraMoveStartedListener { reason -> host.moveStarted(reason) }
            map.addOnCameraMoveListener { host.publishCamera(map) }
            map.addOnCameraIdleListener { host.publishCamera(map) }
            map.addOnMapClickListener { at -> tap(LatLon(at.latitude, at.longitude)); false }   // false: the map's own handling of a tap goes on
            host.attach(map, initial)
        }
        onDispose {
            view.listener = null
            owner.lifecycle.removeObserver(observer)
            host.detach()
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(style.id, style.tiles) { host.applyStyle(style) }
    val topPx = with(LocalDensity.current) { padding.top.roundToPx() }
    val bottomPx = with(LocalDensity.current) { padding.bottom.roundToPx() }
    LaunchedEffect(host, topPx, bottomPx) { host.setPadding(topPx, bottomPx) }

    AndroidView(factory = { view }, modifier = modifier)
}

/**
 * The strips at the [top] and [bottom] of the map that the app's own controls cover: the search bar, and the sheet at the height it rests at. The
 * camera's centre, where the crosshair is drawn and what the readout names, is the middle of what is between them.
 */
@Immutable
data class MapPadding(val top: Dp = 0.dp, val bottom: Dp = 0.dp)

/** How long a fly-to takes, how long the map takes to turn back to north, and the least a flight folded into a new padding is given to finish. */
private const val FLIGHT_MS = 600L
private const val FACE_NORTH_MS = 300L
private const val MIN_FLIGHT_MS = 150L

private var libraryReady = false

private fun createMapView(context: Context): DragMapView {
    if (!libraryReady) {
        MapLibre.getInstance(context.applicationContext)
        libraryReady = true
    }
    return DragMapView(context)
}
