package app.ezpztac.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Draws a [RouteScene] on the map: each route as a line in its colour over a dark casing (so it reads on satellite and on the pale charts), its
 * named points as dots, the shaping points of the route being worked on as small dots, and a route being drawn as a dashed white line. Thin on
 * purpose, like [DiagramOverlay]: *what* is drawn is the scene, which is tested; this only hands it to MapLibre, which needs a GPU.
 */
class RouteOverlay {
    private var vector: GeoJsonSource? = null
    private var latest: RouteScene = RouteScene.EMPTY

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        val geoJson = GeoJsonSource(SOURCE)
        vector = geoJson
        style.addSource(geoJson)
        val role = { value: String -> Expression.eq(Expression.get("role"), Expression.literal(value)) }
        val held = Expression.eq(Expression.get("selected"), Expression.literal(true))
        style.addLayer(
            LineLayer(CASING_LAYER, SOURCE).withFilter(role("route")).withProperties(
                PropertyFactory.lineColor("#000000"), PropertyFactory.lineOpacity(0.55f), PropertyFactory.lineJoin("round"), PropertyFactory.lineCap("round"),
                PropertyFactory.lineWidth(Expression.switchCase(held, Expression.literal(9f), Expression.literal(7f))),
            ),
        )
        style.addLayer(
            LineLayer(LINE_LAYER, SOURCE).withFilter(role("route")).withProperties(
                PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineJoin("round"), PropertyFactory.lineCap("round"),
                PropertyFactory.lineWidth(Expression.switchCase(held, Expression.literal(5f), Expression.literal(3.5f))),
            ),
        )
        style.addLayer(
            LineLayer(DRAFT_LAYER, SOURCE).withFilter(role("draft")).withProperties(
                PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(2f, 2f)), PropertyFactory.lineJoin("round"),
            ),
        )
        style.addLayer(
            CircleLayer(SHAPE_LAYER, SOURCE).withFilter(role("shape")).withProperties(
                PropertyFactory.circleRadius(Expression.switchCase(held, Expression.literal(7f), Expression.literal(4f))),
                PropertyFactory.circleColor(Expression.get("color")), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        style.addLayer(
            CircleLayer(PIN_LAYER, SOURCE).withFilter(role("pin")).withProperties(
                PropertyFactory.circleRadius(Expression.switchCase(held, Expression.literal(10f), Expression.literal(7f))),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor(Expression.switchCase(held, Expression.literal(HELD_COLOR), Expression.literal("#FFFFFF"))),
                PropertyFactory.circleStrokeWidth(Expression.switchCase(held, Expression.literal(4f), Expression.literal(2f))),
            ),
        )
        style.addLayer(
            CircleLayer(DRAFT_VERTEX_LAYER, SOURCE).withFilter(role("draft-vertex")).withProperties(
                PropertyFactory.circleRadius(5f), PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor("#000000"), PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        show(latest)
    }

    /** Draws [scene]; an empty one clears the routes off the map. */
    fun show(scene: RouteScene) {
        latest = scene
        vector?.setGeoJson(scene.geoJson())
    }

    private companion object {
        const val SOURCE = "routes"
        const val CASING_LAYER = "routes-casing"
        const val LINE_LAYER = "routes-line"
        const val DRAFT_LAYER = "routes-draft"
        const val SHAPE_LAYER = "routes-shape"
        const val PIN_LAYER = "routes-pin"
        const val DRAFT_VERTEX_LAYER = "routes-draft-vertex"
        const val HELD_COLOR = "#FFC107"
    }
}
