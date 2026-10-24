package app.ezpztac.map

import android.content.res.AssetManager
import android.graphics.Bitmap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Draws an [OtherLzScene] on the map: the open LZ/PZs not being worked on, each as the open one is drawn ([DiagramOverlay]) but faded, so they are seen and do
 * not compete with it. Installed before the open one's layers, so all of it lies under them. Thin on purpose, like the others: *what* is drawn is the scene,
 * which is tested; this hands it to MapLibre, which needs a GPU and cannot be tried here. The aircraft pictures are the open one's, drawn for a screen of
 * [screenDensity] pixels to the point.
 */
class OtherLzOverlay(private val assets: AssetManager, private val screenDensity: Float) {
    private var style: Style? = null
    private var vector: GeoJsonSource? = null
    private var latest: OtherLzScene = OtherLzScene.EMPTY

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        this.style = style
        val source = GeoJsonSource(SOURCE)
        vector = source
        style.addSource(source)
        val role = { value: String -> Expression.eq(Expression.get("role"), Expression.literal(value)) }
        style.addLayer(FillLayer("$SOURCE-fill", SOURCE).withFilter(role("boundary")).withProperties(PropertyFactory.fillColor(BOUNDARY_COLOR), PropertyFactory.fillOpacity(0.06f)))
        style.addLayer(
            LineLayer("$SOURCE-outline", SOURCE).withFilter(role("boundary")).withProperties(
                PropertyFactory.lineColor(BOUNDARY_COLOR), PropertyFactory.lineWidth(2f), PropertyFactory.lineOpacity(FADED), PropertyFactory.lineJoin("round"),
            ),
        )
        style.addLayer(
            LineLayer("$SOURCE-drawn", SOURCE).withFilter(role("drawn")).withProperties(
                PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(2f), PropertyFactory.lineOpacity(FADED), PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ),
        )
        style.addLayer(FillLayer("$SOURCE-sector-fill", SOURCE).withFilter(role("sector")).withProperties(PropertyFactory.fillColor(SECTOR_COLOR), PropertyFactory.fillOpacity(0.08f)))
        style.addLayer(
            LineLayer("$SOURCE-sector-line", SOURCE).withFilter(role("sector")).withProperties(
                PropertyFactory.lineColor(SECTOR_COLOR), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(FADED),
            ),
        )
        style.addLayer(
            LineLayer("$SOURCE-pz-line", SOURCE).withFilter(role("pz")).withProperties(
                PropertyFactory.lineColor("#2F5BFF"), PropertyFactory.lineWidth(4f), PropertyFactory.lineOpacity(0.35f), PropertyFactory.lineCap("round"),
            ),
        )
        style.addLayer(
            CircleLayer("$SOURCE-pz-anchor", SOURCE).withFilter(role("pz-anchor")).withProperties(
                PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#DC2626"), PropertyFactory.circleOpacity(FADED),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f), PropertyFactory.circleStrokeOpacity(FADED),
            ),
        )
        style.addLayer(
            SymbolLayer("$SOURCE-pz-tip", SOURCE).withFilter(role("pz-tip")).withProperties(
                PropertyFactory.iconImage(GraphicsImages.PZ_ARROW_HEAD), PropertyFactory.iconRotate(Expression.get("bearing")), PropertyFactory.iconOpacity(FADED),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.5f),
            ),
        )
        style.addLayer(
            SymbolLayer("$SOURCE-go-around", SOURCE).withFilter(role("ga")).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconRotate(Expression.get("rotation")), PropertyFactory.iconOpacity(FADED),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.7f),
            ),
        )
        // To scale on the ground, as the open one's are (DiagramOverlay).
        val aircraftSize = Expression.interpolate(
            Expression.exponential(2), Expression.zoom(),
            Expression.stop(0, Expression.get("k0")),
            Expression.stop(24, Expression.product(Expression.get("k0"), Expression.literal(16_777_216.0))),
        )
        style.addLayer(
            SymbolLayer("$SOURCE-aircraft", SOURCE).withFilter(role("heli")).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconRotate(Expression.get("rotation")), PropertyFactory.iconOpacity(FADED),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(aircraftSize),
            ),
        )
        style.addLayer(
            CircleLayer("$SOURCE-target", SOURCE).withFilter(role("target")).withProperties(
                PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(TARGET_COLOR), PropertyFactory.circleOpacity(0.7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f), PropertyFactory.circleStrokeOpacity(0.7f),
            ),
        )
        show(latest)
    }

    /** Gives the style the pictures the scene refers to that it does not have yet (the open one's, under the same names). */
    private fun ensureImages(style: Style, scene: OtherLzScene) {
        fun add(name: String, make: () -> Bitmap) {
            if (style.getImage(name) == null) style.addImage(name, make())
        }
        for (lz in scene.lzs) {
            for (a in lz.graphics.aircraft) {
                add(LzScene.aircraftImage(a.iconKey, violating = false)) { AircraftIconRenderer.forMap(assets, a.iconKey, IconState.NORMAL, screenDensity) }
            }
            if (lz.graphics.goArounds.isNotEmpty()) {
                add(GraphicsImages.GO_AROUND_LEFT) { GraphicsImages.goAround(right = false) }
                add(GraphicsImages.GO_AROUND_RIGHT) { GraphicsImages.goAround(right = true) }
            }
            if (lz.graphics.pzMarkers.isNotEmpty()) add(GraphicsImages.PZ_ARROW_HEAD) { GraphicsImages.pzArrowHead() }
        }
    }

    /** Draws [scene]; an empty one takes them all off the map. */
    fun show(scene: OtherLzScene) {
        latest = scene
        val target = vector ?: return
        style?.let { ensureImages(it, scene) }
        target.setGeoJson(scene.geoJson())
    }

    private companion object {
        const val SOURCE = "lz-others"

        /** How strongly what is drawn shows: about half, so the open one is plainly the one being worked on. */
        const val FADED = 0.5f

        // The open one's colours (DiagramOverlay).
        const val BOUNDARY_COLOR = "#FFB020"
        const val SECTOR_COLOR = "#9370DB"
        const val TARGET_COLOR = "#EB5757"
    }
}
