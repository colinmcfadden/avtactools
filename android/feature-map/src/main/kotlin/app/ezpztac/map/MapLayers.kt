package app.ezpztac.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** Draws the device's position on [host]'s map while the GPS is tracking, and takes it off when it is not. */
@Composable
fun GpsLayer(host: MapHost, gps: GpsState) {
    val overlay = remember(host) { GpsOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, gps) { overlay.show((gps as? GpsState.Tracking)?.fix) }
}

/** Draws the open diagram on [host]'s map: its boundary, target and slope raster. An empty scene takes the diagram off. */
@Composable
fun DiagramLayer(host: MapHost, scene: LzScene) {
    val overlay = remember(host) { DiagramOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}

/** Draws the open set of routes on [host]'s map: each route's line and named points, and a route being drawn. An empty scene takes them off. */
@Composable
fun RouteLayer(host: MapHost, scene: RouteScene) {
    val overlay = remember(host) { RouteOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}
