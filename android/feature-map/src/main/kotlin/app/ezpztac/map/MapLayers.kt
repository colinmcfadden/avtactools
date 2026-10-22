package app.ezpztac.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

/** Draws the device's position on [host]'s map while the GPS is tracking, and takes it off when it is not. */
@Composable
fun GpsLayer(host: MapHost, gps: GpsState) {
    val overlay = remember(host) { GpsOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, gps) { overlay.show((gps as? GpsState.Tracking)?.fix) }
}

/**
 * Draws the open diagram on [host]'s map: its boundary, target and slope raster, the raster at [slopeOpacity] (0 to 1, of its own alpha). An empty scene
 * takes the diagram off. The opacity is told apart from the scene, so moving its slider changes one property of the raster and nothing else.
 */
@Composable
fun DiagramLayer(host: MapHost, scene: LzScene, slopeOpacity: Float) {
    val assets = LocalContext.current.assets
    val screenDensity = LocalDensity.current.density
    val overlay = remember(host) { DiagramOverlay(assets, screenDensity, slopeOpacity).also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, slopeOpacity) { overlay.setSlopeOpacity(slopeOpacity) }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}

/**
 * Draws the open LZ/PZs not being worked on, faded ([OtherLzOverlay]). Composed before [DiagramLayer], so its layers are put under the open one's; an empty scene
 * takes them off.
 */
@Composable
fun OtherLzLayer(host: MapHost, scene: OtherLzScene) {
    val assets = LocalContext.current.assets
    val screenDensity = LocalDensity.current.density
    val overlay = remember(host) { OtherLzOverlay(assets, screenDensity).also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}

/** Draws the open set of routes on [host]'s map: each route's line and named points, and a route being drawn. An empty scene takes them off. */
@Composable
fun RouteLayer(host: MapHost, scene: RouteScene) {
    val overlay = remember(host) { RouteOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}

/** Draws the local points that are shown on [host]'s map, gathered into rings where they crowd. An empty scene takes them off. */
@Composable
fun PointLayer(host: MapHost, scene: PointScene) {
    val overlay = remember(host) { PointOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}

/** Draws the visible threats' detection and engagement range rings. Their MIL-STD symbols are [ThreatLabelsLayer]. */
@Composable
fun ThreatLayer(host: MapHost, scene: ThreatScene) {
    val overlay = remember(host) { ThreatOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}
