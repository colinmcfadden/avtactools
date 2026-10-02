package app.ezpztac.map

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    internal fun attach(map: MapLibreMap) {
        this.map = map
        waiting.toList().also { waiting.clear() }.forEach { it(map) }
        publishCamera(map)
    }

    internal fun detach() {
        map = null
    }

    internal fun publishCamera(map: MapLibreMap) {
        val position = map.cameraPosition
        val target = position.target ?: return
        camera = CameraState(LatLon(target.latitude, target.longitude), position.zoom, position.bearing)
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val ready = map
        if (ready != null) action(ready) else waiting += action
    }

    /** Moves the camera with an animation, to [at] and, if given, [zoom]. */
    fun flyTo(at: LatLon, zoom: Double? = null) = whenReady { map ->
        val target = LatLng(at.lat, at.lon)
        val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(target, zoom) else CameraUpdateFactory.newLatLng(target)
        map.animateCamera(update, 600)
    }

    /** Moves the camera at once, for the first frame. */
    fun jumpTo(state: CameraState) = whenReady { map ->
        map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(state.center.lat, state.center.lon)).zoom(state.zoom).bearing(state.bearingDegrees).build()))
    }

    /** Turns the map back to north. */
    fun faceNorth() = whenReady { map ->
        val position = map.cameraPosition
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(position).bearing(0.0).build()), 300)
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
    onLongPress: (LatLon) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context) { createMapView(context) }
    val longPress by rememberUpdatedState(onLongPress)

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
        view.getMapAsync { map ->
            map.uiSettings.apply {
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                isCompassEnabled = false                       // the app draws its own, in the design system
            }
            map.addOnCameraMoveListener { host.publishCamera(map) }
            map.addOnCameraIdleListener { host.publishCamera(map) }
            map.addOnMapLongClickListener { at -> longPress(LatLon(at.latitude, at.longitude)); true }
            host.attach(map)
            host.jumpTo(initial)
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            host.detach()
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(style.id, style.tiles) { host.applyStyle(style) }

    AndroidView(factory = { view }, modifier = modifier)
}

private var libraryReady = false

private fun createMapView(context: Context): MapView {
    if (!libraryReady) {
        MapLibre.getInstance(context.applicationContext)
        libraryReady = true
    }
    return MapView(context)
}
