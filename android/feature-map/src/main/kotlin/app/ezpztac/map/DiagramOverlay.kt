package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource

/**
 * Draws an [LzScene] on the map: the analysis boundary (a pale fill and an amber outline), a boundary being drawn (dashed), the target,
 * and the slope raster under all of them. Thin on purpose: *what* is drawn is the scene, which is tested; this only hands it to MapLibre,
 * which needs a GPU and cannot be tried here.
 */
class DiagramOverlay {
    private var style: Style? = null
    private var vector: GeoJsonSource? = null
    private var latest: LzScene = LzScene.EMPTY
    private var drawnSlope: SlopeImage? = null

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        this.style = style
        drawnSlope = null
        val geoJson = GeoJsonSource(SOURCE)
        vector = geoJson
        style.addSource(geoJson)
        val role = { value: String -> Expression.eq(Expression.get("role"), Expression.literal(value)) }
        style.addLayer(
            FillLayer(FILL_LAYER, SOURCE).withFilter(role("boundary")).withProperties(
                PropertyFactory.fillColor(BOUNDARY_COLOR), PropertyFactory.fillOpacity(0.12f),
            ),
        )
        style.addLayer(
            LineLayer(OUTLINE_LAYER, SOURCE).withFilter(role("boundary")).withProperties(
                PropertyFactory.lineColor(BOUNDARY_COLOR), PropertyFactory.lineWidth(3f), PropertyFactory.lineJoin("round"),
            ),
        )
        style.addLayer(
            LineLayer(DRAWN_LAYER, SOURCE).withFilter(role("drawn")).withProperties(
                PropertyFactory.lineColor(DRAWN_COLOR), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ),
        )
        // The points of a boundary being drawn: a dot each, so the first (which has no line to it yet) is seen.
        style.addLayer(
            CircleLayer(VERTEX_LAYER, SOURCE).withFilter(role("vertex")).withProperties(
                PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(DRAWN_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        // A dot at each corner of the boundary, so a corner can be seen and held (to delete it); the held one is larger and amber.
        val held = Expression.eq(Expression.get("held"), Expression.literal(true))
        style.addLayer(
            CircleLayer(CORNER_LAYER, SOURCE).withFilter(role("corner")).withProperties(
                PropertyFactory.circleRadius(Expression.switchCase(held, Expression.literal(9f), Expression.literal(4.5f))),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor(Expression.switchCase(held, Expression.literal("#F59E0B"), Expression.literal("#374151"))),
                PropertyFactory.circleStrokeWidth(Expression.switchCase(held, Expression.literal(3f), Expression.literal(1.5f))),
                PropertyFactory.circleOpacity(0.9f),
            ),
        )
        installGraphics(style, role)
        style.addLayer(
            CircleLayer(TARGET_LAYER, SOURCE).withFilter(role("target")).withProperties(
                PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(TARGET_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        show(latest)
    }

    /** The planning graphics, under the target and over the boundary: sectors, the keep-out ring, separation lines, PZ arrows, go-arounds, aircraft and the halo. */
    private fun installGraphics(style: Style, role: (String) -> Expression) {
        val both = { a: Expression, b: Expression -> Expression.all(a, b) }
        val violating = { value: Boolean -> Expression.eq(Expression.get("violating"), Expression.literal(value)) }
        style.addLayer(
            FillLayer(SECTOR_FILL_LAYER, SOURCE).withFilter(role("sector")).withProperties(PropertyFactory.fillColor(SECTOR_COLOR), PropertyFactory.fillOpacity(0.2f)),
        )
        style.addLayer(
            LineLayer(SECTOR_LINE_LAYER, SOURCE).withFilter(role("sector")).withProperties(PropertyFactory.lineColor(SECTOR_COLOR), PropertyFactory.lineWidth(1.5f)),
        )
        style.addLayer(
            LineLayer(RING_LAYER, SOURCE).withFilter(role("ring")).withProperties(
                PropertyFactory.lineColor(BOUNDARY_COLOR), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineDasharray(arrayOf(3f, 3f)), PropertyFactory.lineOpacity(0.9f),
            ),
        )
        style.addLayer(
            LineLayer(SEPARATION_OK_LAYER, SOURCE).withFilter(both(role("sep"), violating(false))).withProperties(
                PropertyFactory.lineColor(SEPARATION_OK_COLOR), PropertyFactory.lineWidth(2f), PropertyFactory.lineDasharray(arrayOf(3f, 3f)), PropertyFactory.lineOpacity(0.8f),
            ),
        )
        style.addLayer(
            LineLayer(SEPARATION_BAD_LAYER, SOURCE).withFilter(both(role("sep"), violating(true))).withProperties(
                PropertyFactory.lineColor(SEPARATION_BAD_COLOR), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineDasharray(arrayOf(3f, 3f)), PropertyFactory.lineOpacity(0.9f),
            ),
        )
        style.addLayer(
            LineLayer(PZ_CASING_LAYER, SOURCE).withFilter(role("pz")).withProperties(PropertyFactory.lineColor("#000000"), PropertyFactory.lineWidth(8f), PropertyFactory.lineOpacity(0.7f), PropertyFactory.lineCap("round")),
        )
        style.addLayer(
            LineLayer(PZ_LINE_LAYER, SOURCE).withFilter(role("pz")).withProperties(PropertyFactory.lineColor("#2F5BFF"), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.6f), PropertyFactory.lineCap("round")),
        )
        style.addLayer(
            CircleLayer(PZ_ANCHOR_LAYER, SOURCE).withFilter(role("pz-anchor")).withProperties(
                PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#DC2626"), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            SymbolLayer(PZ_TIP_LAYER, SOURCE).withFilter(role("pz-tip")).withProperties(
                PropertyFactory.iconImage(GraphicsImages.PZ_ARROW_HEAD), PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.5f),
            ),
        )
        // The handle at the end of the arrow, over its head: a long press here drags the tip alone (how far the marker reaches and which way it points).
        style.addLayer(
            CircleLayer(PZ_TIP_HANDLE_LAYER, SOURCE).withFilter(role("pz-tip")).withProperties(
                PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#2F5BFF"), PropertyFactory.circleOpacity(0.55f),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            SymbolLayer(GO_AROUND_LAYER, SOURCE).withFilter(role("ga")).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconRotate(Expression.get("rotation")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.7f),
            ),
        )
        // An aircraft is drawn at its true size on the ground: its picture's width is its rotor diameter, which at each zoom is `k0 * 2^zoom` times the picture.
        val aircraftSize = Expression.interpolate(
            Expression.exponential(2), Expression.zoom(),
            Expression.stop(0, Expression.get("k0")),
            Expression.stop(24, Expression.product(Expression.get("k0"), Expression.literal(16_777_216.0))),
        )
        style.addLayer(
            SymbolLayer(AIRCRAFT_LAYER, SOURCE).withFilter(role("heli")).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconRotate(Expression.get("rotation")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(aircraftSize),
            ),
        )
        style.addLayer(
            CircleLayer(HALO_LAYER, SOURCE).withFilter(role("halo")).withProperties(
                PropertyFactory.circleRadius(30f), PropertyFactory.circleColor("#FFC107"), PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleStrokeColor("#FFC107"), PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    /** Gives the style the pictures the scene refers to that it does not have yet. Each is made once. */
    private fun ensureImages(style: Style, scene: LzScene) {
        fun add(name: String, make: () -> Bitmap) {
            if (style.getImage(name) == null) style.addImage(name, make())
        }
        for (a in scene.graphics.aircraft) {
            val name = LzScene.aircraftImage(a.iconKey, a.violating)
            add(name) { AircraftIconRenderer.render(a.iconKey, if (a.violating) IconState.VIOLATION else IconState.NORMAL, LzScene.AIRCRAFT_ICON_PX) }
        }
        if (scene.graphics.goArounds.isNotEmpty()) {
            add(GraphicsImages.GO_AROUND_LEFT) { GraphicsImages.goAround(right = false) }
            add(GraphicsImages.GO_AROUND_RIGHT) { GraphicsImages.goAround(right = true) }
        }
        if (scene.graphics.pzMarkers.isNotEmpty()) add(GraphicsImages.PZ_ARROW_HEAD) { GraphicsImages.pzArrowHead() }
    }

    /** Draws [scene]; an empty one clears the diagram off the map. */
    fun show(scene: LzScene) {
        latest = scene
        val target = vector ?: return
        style?.let { ensureImages(it, scene) }
        target.setGeoJson(scene.geoJson())
        showSlope(scene.slope)
    }

    /** The raster goes *under* the boundary, so the outline stays readable over it. */
    private fun showSlope(slope: SlopeImage?) {
        val current = style ?: return
        if (slope == drawnSlope) return
        current.removeLayer(SLOPE_LAYER)
        current.removeSource(SLOPE_SOURCE)
        drawnSlope = null
        if (slope == null) return
        val bitmap = decode(slope.dataUri) ?: return                   // a raster that will not decode is left off, not drawn wrong
        val corners = LatLngQuad(LatLng(slope.north, slope.west), LatLng(slope.north, slope.east), LatLng(slope.south, slope.east), LatLng(slope.south, slope.west))
        current.addSource(ImageSource(SLOPE_SOURCE, corners, bitmap))
        current.addLayerBelow(
            RasterLayer(SLOPE_LAYER, SLOPE_SOURCE).withProperties(PropertyFactory.rasterOpacity(0.85f), PropertyFactory.rasterFadeDuration(0f)),
            FILL_LAYER,
        )
        drawnSlope = slope
    }

    private fun decode(dataUri: String): Bitmap? {
        val payload = dataUri.substringAfter("base64,", missingDelimiterValue = "")
        if (payload.isEmpty()) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    private companion object {
        const val SOURCE = "lz"
        const val FILL_LAYER = "lz-fill"
        const val OUTLINE_LAYER = "lz-outline"
        const val DRAWN_LAYER = "lz-drawn"
        const val VERTEX_LAYER = "lz-vertex"
        const val CORNER_LAYER = "lz-corner"
        const val TARGET_LAYER = "lz-target"
        const val SECTOR_FILL_LAYER = "lz-sector-fill"
        const val SECTOR_LINE_LAYER = "lz-sector-line"
        const val RING_LAYER = "lz-ring"
        const val SEPARATION_OK_LAYER = "lz-separation-ok"
        const val SEPARATION_BAD_LAYER = "lz-separation-bad"
        const val PZ_CASING_LAYER = "lz-pz-casing"
        const val PZ_LINE_LAYER = "lz-pz-line"
        const val PZ_ANCHOR_LAYER = "lz-pz-anchor"
        const val PZ_TIP_LAYER = "lz-pz-tip"
        const val PZ_TIP_HANDLE_LAYER = "lz-pz-tip-handle"
        const val GO_AROUND_LAYER = "lz-go-around"
        const val AIRCRAFT_LAYER = "lz-aircraft"
        const val HALO_LAYER = "lz-halo"
        const val SECTOR_COLOR = "#9370DB"
        const val SEPARATION_OK_COLOR = "#9CA3AF"
        const val SEPARATION_BAD_COLOR = "#DC2626"
        const val SLOPE_SOURCE = "lz-slope"
        const val SLOPE_LAYER = "lz-slope-layer"
        const val BOUNDARY_COLOR = "#FFB020"
        const val DRAWN_COLOR = "#FFFFFF"
        const val TARGET_COLOR = "#EB5757"
    }
}
