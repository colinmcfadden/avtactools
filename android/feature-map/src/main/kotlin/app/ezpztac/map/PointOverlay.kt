package app.ezpztac.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Draws a [PointScene] on the map: each local point a dot in its set's colour with a white ring, the held one larger with an amber ring, and a crowd of
 * them (a set can be thousands) gathered into one larger ring that breaks up as the map is zoomed in. Thin on purpose, like [RouteOverlay]: *what* is
 * drawn is the scene, which is tested; this only hands it to MapLibre, which needs a GPU.
 */
class PointOverlay {
    private var source: GeoJsonSource? = null
    private var latest: PointScene = PointScene.EMPTY

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        val geoJson = GeoJsonSource(SOURCE, GeoJsonOptions().withCluster(true).withClusterMaxZoom(CLUSTER_MAX_ZOOM).withClusterRadius(CLUSTER_RADIUS))
        source = geoJson
        style.addSource(geoJson)
        val crowd = Expression.has("point_count")
        val held = Expression.eq(Expression.get("selected"), Expression.literal(true))
        style.addLayer(
            CircleLayer(CLUSTER_LAYER, SOURCE).withFilter(crowd).withProperties(
                PropertyFactory.circleRadius(Expression.step(Expression.get("point_count"), Expression.literal(13f), Expression.stop(10, 17f), Expression.stop(100, 22f))),
                PropertyFactory.circleColor("#263238"), PropertyFactory.circleOpacity(0.85f),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        style.addLayer(
            CircleLayer(POINT_LAYER, SOURCE).withFilter(Expression.not(crowd)).withProperties(
                PropertyFactory.circleRadius(Expression.switchCase(held, Expression.literal(9f), Expression.literal(5.5f))),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor(Expression.switchCase(held, Expression.literal(HELD_COLOR), Expression.literal("#FFFFFF"))),
                PropertyFactory.circleStrokeWidth(Expression.switchCase(held, Expression.literal(4f), Expression.literal(2f))),
            ),
        )
        show(latest)
    }

    /** Draws [scene]; an empty one clears the points off the map. */
    fun show(scene: PointScene) {
        latest = scene
        source?.setGeoJson(scene.geoJson())
    }

    private companion object {
        const val SOURCE = "local-points"
        const val CLUSTER_LAYER = "local-points-cluster"
        const val POINT_LAYER = "local-points-point"
        const val HELD_COLOR = "#FFC107"

        /** Above this zoom nothing is gathered: points that far in are tens of metres apart on the ground. */
        const val CLUSTER_MAX_ZOOM = 12
        const val CLUSTER_RADIUS = 36
    }
}
