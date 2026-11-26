package app.ezpztac.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Draws a [MeasuresScene]'s lines on the map, in the web's look: a backstop as red dashes outlined in white (`backstopStyle.js`), a phase line black on a white
 * casing (`phaseLineStyle.js`), a line being drawn as the web's draft (dashed in its colour, a dot at each point) and an amber halo under what is held. A FLOT's
 * scallops, a backstop's arrow and the labels are [MeasuresLabelsLayer]'s. Thin on purpose, like [RouteOverlay]: *what* is drawn is the scene, which is tested;
 * this only hands it to MapLibre, which needs a GPU.
 */
class MeasuresOverlay {
    private var vector: GeoJsonSource? = null
    private var latest: MeasuresScene = MeasuresScene.EMPTY

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        val geoJson = GeoJsonSource(SOURCE)
        vector = geoJson
        style.addSource(geoJson)
        val role = { value: String -> Expression.eq(Expression.get("role"), Expression.literal(value)) }
        style.addLayer(
            LineLayer(HALO_LAYER, SOURCE).withFilter(role("halo")).withProperties(
                PropertyFactory.lineColor(HELD), PropertyFactory.lineOpacity(0.55f), PropertyFactory.lineWidth(14f),
                PropertyFactory.lineJoin("round"), PropertyFactory.lineCap("round"),
            ),
        )
        // MapLibre's dashes are in widths of their line: 7 and 10 points for both, so the white goes round each red dash, as the web's do.
        style.addLayer(
            LineLayer(BACKSTOP_CASING_LAYER, SOURCE).withFilter(role("backstop")).withProperties(
                PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(5f), PropertyFactory.lineCap("square"),
                PropertyFactory.lineDasharray(arrayOf(7f / 5f, 10f / 5f)),
            ),
        )
        style.addLayer(
            LineLayer(BACKSTOP_LAYER, SOURCE).withFilter(role("backstop")).withProperties(
                PropertyFactory.lineColor(BACKSTOP_RED), PropertyFactory.lineWidth(3f), PropertyFactory.lineCap("square"),
                PropertyFactory.lineDasharray(arrayOf(7f / 3f, 10f / 3f)),
            ),
        )
        style.addLayer(
            LineLayer(PHASE_CASING_LAYER, SOURCE).withFilter(role("phase-line")).withProperties(
                PropertyFactory.lineColor(PHASE_CASING), PropertyFactory.lineWidth(8f), PropertyFactory.lineOpacity(0.92f),
                PropertyFactory.lineJoin("round"), PropertyFactory.lineCap("round"),
            ),
        )
        style.addLayer(
            LineLayer(PHASE_LAYER, SOURCE).withFilter(role("phase-line")).withProperties(
                PropertyFactory.lineColor(PHASE_COLOR), PropertyFactory.lineWidth(3.5f), PropertyFactory.lineJoin("round"), PropertyFactory.lineCap("round"),
            ),
        )
        // A line being drawn, as MapView.jsx draws each: the backstop as it will be, a FLOT dashed blue, a phase line dashed black on its casing.
        style.addLayer(
            LineLayer(DRAFT_BACKSTOP_CASING_LAYER, SOURCE).withFilter(role("draft-backstop")).withProperties(
                PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(5f), PropertyFactory.lineCap("square"),
                PropertyFactory.lineDasharray(arrayOf(7f / 5f, 10f / 5f)),
            ),
        )
        style.addLayer(
            LineLayer(DRAFT_BACKSTOP_LAYER, SOURCE).withFilter(role("draft-backstop")).withProperties(
                PropertyFactory.lineColor(BACKSTOP_RED), PropertyFactory.lineWidth(3f), PropertyFactory.lineCap("square"),
                PropertyFactory.lineDasharray(arrayOf(7f / 3f, 10f / 3f)),
            ),
        )
        style.addLayer(
            LineLayer(DRAFT_FLOT_LAYER, SOURCE).withFilter(role("draft-flot")).withProperties(
                PropertyFactory.lineColor(FLOT_COLOR), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(8f / 3f, 6f / 3f)),
            ),
        )
        style.addLayer(
            LineLayer(DRAFT_PHASE_CASING_LAYER, SOURCE).withFilter(role("draft-phase-line")).withProperties(
                PropertyFactory.lineColor(PHASE_CASING), PropertyFactory.lineWidth(6f), PropertyFactory.lineOpacity(0.85f),
            ),
        )
        style.addLayer(
            LineLayer(DRAFT_PHASE_LAYER, SOURCE).withFilter(role("draft-phase-line")).withProperties(
                PropertyFactory.lineColor(PHASE_COLOR), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(8f / 3f, 6f / 3f)),
            ),
        )
        val kind = { value: String -> Expression.eq(Expression.get("kind"), Expression.literal(value)) }
        style.addLayer(
            CircleLayer(DRAFT_VERTEX_LAYER, SOURCE).withFilter(role("draft-vertex")).withProperties(
                PropertyFactory.circleRadius(4f), PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleColor(
                    Expression.switchCase(kind("backstop"), Expression.literal(BACKSTOP_RED), kind("flot"), Expression.literal(FLOT_COLOR), Expression.literal(PHASE_COLOR)),
                ),
                PropertyFactory.circleStrokeColor(Expression.switchCase(kind("phase-line"), Expression.literal(PHASE_CASING), Expression.literal("#ffffff"))),
            ),
        )
        show(latest)
    }

    /** Draws [scene]; an empty one takes the lines off the map. */
    fun show(scene: MeasuresScene) {
        latest = scene
        vector?.setGeoJson(scene.geoJson())
    }

    companion object {
        const val BACKSTOP_RED = "#e60000"
        const val FLOT_COLOR = "#2f8fff"
        const val PHASE_COLOR = "#0b0f14"
        const val PHASE_CASING = "#ffffff"
        const val HELD = "#FFC107"

        private const val SOURCE = "measures"
        private const val HALO_LAYER = "measures-halo"
        private const val BACKSTOP_CASING_LAYER = "measures-backstop-casing"
        private const val BACKSTOP_LAYER = "measures-backstop"
        private const val PHASE_CASING_LAYER = "measures-phase-casing"
        private const val PHASE_LAYER = "measures-phase"
        private const val DRAFT_BACKSTOP_CASING_LAYER = "measures-draft-backstop-casing"
        private const val DRAFT_BACKSTOP_LAYER = "measures-draft-backstop"
        private const val DRAFT_FLOT_LAYER = "measures-draft-flot"
        private const val DRAFT_PHASE_CASING_LAYER = "measures-draft-phase-casing"
        private const val DRAFT_PHASE_LAYER = "measures-draft-phase"
        private const val DRAFT_VERTEX_LAYER = "measures-draft-vertex"
    }
}
