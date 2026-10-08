package app.ezpztac.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon

/**
 * The map with what sits over it: a search field, the crosshair and its readout, the base map and GPS buttons, and a north arrow that
 * turns with the map. [map] is where the map view goes, so this layout is tried without one (a GPU is not available to a unit test).
 */
@Composable
fun MapScreen(
    state: MapUiState,
    camera: CameraState?,
    gps: GpsState,
    onSearch: (String) -> Unit,
    onClearSearchError: () -> Unit,
    onSelectStyle: (String) -> Unit,
    onToggleGps: () -> Unit,
    onGpsPermissionResult: (Boolean) -> Unit,
    onLocateMe: () -> Unit,
    onFaceNorth: () -> Unit,
    modifier: Modifier = Modifier,
    /** The slope heat map's button, present only while the open diagram has a slope measured. */
    slope: SlopeToggleUi? = null,
    onToggleSlope: () -> Unit = {},
    /** Space the bottom sheet takes, so the crosshair stays in the middle of what is still showing. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Anything that floats over the map and the readout, placed in the screen's own box (so `Modifier.align` works): a tool that is only there while it is in use. */
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    map: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        map()

        // The crosshair sits at the middle of the map the person can see, which is above the sheet.
        Box(Modifier.align(Alignment.Center).padding(bottom = bottomInset), contentAlignment = Alignment.Center) {
            Crosshair()
        }

        Column(Modifier.safeDrawingPadding().padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            SearchField(state.searchError, onSearch, onClearSearchError)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    LayersButton(state, onSelectStyle)
                    GpsButton(gps, onToggleGps, onGpsPermissionResult, onLocateMe)
                    slope?.let {
                        MapButton(
                            "Slope", if (it.shown) "Slope heat map is on. Tap to hide it" else "Slope heat map is off. Tap to show it",
                            modifier = Modifier.alpha(if (it.shown) 1f else 0.55f), onClick = onToggleSlope,
                        )
                    }
                    camera?.let { CompassButton(it.bearingDegrees, onFaceNorth) }
                }
            }
        }

        state.readout?.let { readout ->
            ReadoutPill(readout, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = bottomInset + Tokens.Spacing.lg.dp))
        }
        overlay()
    }
}

/** The slope heat map's button: whether the raster is drawn over the diagram. */
data class SlopeToggleUi(val shown: Boolean)

@Composable
private fun Crosshair() {
    val color = MaterialTheme.colorScheme.onBackground
    val halo = MaterialTheme.colorScheme.background
    Canvas(Modifier.size(40.dp).semantics { contentDescription = "Map centre" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val arm = size.width / 2 - 2.dp.toPx()
        val gap = 6.dp.toPx()
        for ((width, paint) in listOf(4.dp.toPx() to halo, 2.dp.toPx() to color)) {          // a dark halo under a light line, readable on any imagery
            val stroke = Stroke(width)
            drawLine(paint, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), stroke.width)
            drawLine(paint, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), stroke.width)
            drawLine(paint, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), stroke.width)
            drawLine(paint, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), stroke.width)
        }
    }
}

@Composable
private fun ReadoutPill(readout: Readout, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(readout.mgrs ?: "No grid here", style = EzpzText.grid)
            Text(readout.latLon, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchField(error: String?, onSearch: (String) -> Unit, onClearError: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; if (error != null) onClearError() },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search by MGRS grid or latitude and longitude" },
            singleLine = true,
            // A placeholder, not a label: a floating label reserves room above the field, which showed as a strip over the map.
            placeholder = { Text("MGRS grid or lat/long") },
            isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
            shape = RoundedCornerShape(Tokens.Radius.md.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = container, unfocusedContainerColor = container, errorContainerColor = container),
            trailingIcon = { TextButton(onClick = { onSearch(text) }) { Text("Go") } },
        )
        // On its own surface: red text straight on imagery cannot be read.
        error?.let { Banner(it, BannerKind.Error) }
    }
}

@Composable
private fun MapButton(label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(Tokens.Size.touchTarget.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun LayersButton(state: MapUiState, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        MapButton("Map", "Base map: ${state.style.label}") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (style in state.styles) {
                DropdownMenuItem(
                    text = { Text(if (style.id == state.style.id) "${style.label}  ✓" else style.label) },
                    onClick = { open = false; onSelect(style.id) },
                )
            }
        }
    }
}

@Composable
private fun GpsButton(gps: GpsState, onToggle: () -> Unit, onPermissionResult: (Boolean) -> Unit, onLocate: () -> Unit) {
    // Fine location is what a GPS fix needs; coarse is asked for beside it because Android only offers the precise choice that way.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        onPermissionResult(granted[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    LaunchedEffect(gps) {
        if (gps == GpsState.NeedsPermission) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val label = when (gps) {
        GpsState.Off, GpsState.NeedsPermission -> "GPS"
        GpsState.Searching -> "GPS…"
        is GpsState.Tracking -> "GPS ●"
        is GpsState.Unavailable -> "GPS ✕"
    }
    val description = when (gps) {
        GpsState.Off, GpsState.NeedsPermission -> "Turn the GPS on"
        GpsState.Searching -> "GPS is looking for a fix. Tap to turn off"
        is GpsState.Tracking -> "GPS on. Tap to turn off"
        is GpsState.Unavailable -> gps.message
    }
    MapButton(label, description, onClick = if (gps is GpsState.Tracking) onLocate else onToggle)
}

@Composable
private fun CompassButton(bearingDegrees: Double, onFaceNorth: () -> Unit) {
    val needle = MaterialTheme.colorScheme.error
    val tail = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onFaceNorth,
        modifier = Modifier.size(Tokens.Size.touchTarget.dp).semantics { contentDescription = "Face north. The map is turned ${bearingDegrees.toInt()} degrees" },
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        // The button stays upright; the needle turns the opposite way to the map, so it always points to north.
        Canvas(Modifier.padding(Tokens.Spacing.md.dp).graphicsLayer { rotationZ = -bearingDegrees.toFloat() }) {
            val c = Offset(size.width / 2, size.height / 2)
            val half = size.height / 2
            val width = size.width * 0.18f
            drawPath(Path().apply { moveTo(c.x, c.y - half); lineTo(c.x + width, c.y); lineTo(c.x - width, c.y); close() }, needle)
            drawPath(Path().apply { moveTo(c.x, c.y + half); lineTo(c.x + width, c.y); lineTo(c.x - width, c.y); close() }, tail)
        }
    }
}
