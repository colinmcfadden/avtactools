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
