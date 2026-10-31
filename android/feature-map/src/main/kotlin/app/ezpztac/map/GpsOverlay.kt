package app.ezpztac.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * The device's position on the map: a dot, and a pale circle for how far the receiver says it could be out. The circle is in metres,
 * so it is drawn as a radius that doubles with each zoom level (`2^zoom`), exactly as the ground does.
 */
class GpsOverlay {
    private var source: GeoJsonSource? = null
    private var latest: UserLocation? = null

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        val geoJson = GeoJsonSource(SOURCE)
        source = geoJson
        style.addSource(geoJson)
        // accuracy / metres-per-point at zoom 0, and at zoom 24: an exponential base-2 curve between them is the ground's own scale.
        val radius = Expression.interpolate(
            Expression.exponential(2),
            Expression.zoom(),
            Expression.stop(0, Expression.division(Expression.get("accuracy"), Expression.literal(METERS_PER_POINT_AT_ZOOM_0))),
            Expression.stop(24, Expression.division(Expression.get("accuracy"), Expression.literal(METERS_PER_POINT_AT_ZOOM_0 / (1 shl 24)))),
        )
        style.addLayer(
            CircleLayer(ACCURACY_LAYER, SOURCE).withProperties(
                PropertyFactory.circleRadius(radius),
                PropertyFactory.circleColor(ACCURACY_COLOR),
                PropertyFactory.circleOpacity(0.18f),
                PropertyFactory.circleStrokeColor(DOT_COLOR),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(0.4f),
            ),
        )
        style.addLayer(
            CircleLayer(DOT_LAYER, SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(DOT_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        latest?.let(::show)
    }

    /** Moves the dot; `null` removes it. */
    fun show(fix: UserLocation?) {
        latest = fix
        val target = source ?: return
        if (fix == null) {
            target.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val feature = Feature.fromGeometry(Point.fromLngLat(fix.at.lon, fix.at.lat)).apply {
            // A metre covers more points the nearer a pole: scale the radius so the circle is true at this latitude, not only at the equator.
            addNumberProperty("accuracy", (fix.accuracyMeters ?: 0f) / Math.cos(Math.toRadians(fix.at.lat)).toFloat().coerceAtLeast(0.05f))
        }
        target.setGeoJson(feature)
    }

    private companion object {
        const val SOURCE = "gps"
        const val ACCURACY_LAYER = "gps-accuracy"
        const val DOT_LAYER = "gps-dot"
        const val DOT_COLOR = "#2F80ED"
        const val ACCURACY_COLOR = "#2F80ED"

        /** Metres on the ground that one point covers at the equator on zoom 0 of MapLibre's 512-point tiles. */
        const val METERS_PER_POINT_AT_ZOOM_0 = 78_271.516964
    }
}
