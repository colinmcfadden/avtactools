package app.ezpztac.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** Hands a threat scene's range rings to MapLibre. The symbols themselves are Compose overlays, where the existing MIL-STD renderer can draw them. */
class ThreatOverlay {
    private var source: GeoJsonSource? = null
    private var latest = ThreatScene.EMPTY

    fun install(style: Style) {
        val geoJson = GeoJsonSource(SOURCE)
        source = geoJson
        style.addSource(geoJson)
        val ring = Expression.eq(Expression.get("role"), Expression.literal("ring"))
        val dashed = Expression.eq(Expression.get("dashed"), Expression.literal(true))
        style.addLayer(
            LineLayer(SOLID_LAYER, SOURCE).withFilter(Expression.all(ring, Expression.not(dashed))).withProperties(
                PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.9f),
            ),
        )
        style.addLayer(
            LineLayer(DASHED_LAYER, SOURCE).withFilter(Expression.all(ring, dashed)).withProperties(
                PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineDasharray(arrayOf(4f, 4f)),
            ),
        )
        show(latest)
    }

    fun show(scene: ThreatScene) {
        latest = scene
        source?.setGeoJson(scene.geoJson())
    }

    private companion object {
        const val SOURCE = "threats"
        const val SOLID_LAYER = "threat-rings-solid"
        const val DASHED_LAYER = "threat-rings-dashed"
    }
}
