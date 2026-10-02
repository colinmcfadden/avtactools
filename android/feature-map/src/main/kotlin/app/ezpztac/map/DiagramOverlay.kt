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
import org.maplibre.android.style.layers.RasterLayer
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
        style.addLayer(
            CircleLayer(TARGET_LAYER, SOURCE).withFilter(role("target")).withProperties(
                PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(TARGET_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        show(latest)
    }

    /** Draws [scene]; an empty one clears the diagram off the map. */
    fun show(scene: LzScene) {
        latest = scene
        val target = vector ?: return
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
        const val TARGET_LAYER = "lz-target"
        const val SLOPE_SOURCE = "lz-slope"
        const val SLOPE_LAYER = "lz-slope-layer"
        const val BOUNDARY_COLOR = "#FFB020"
        const val DRAWN_COLOR = "#FFFFFF"
        const val TARGET_COLOR = "#EB5757"
    }
}
