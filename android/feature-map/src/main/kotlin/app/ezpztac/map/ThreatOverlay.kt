package app.ezpztac.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource

/** Hands a threat scene's range rings to MapLibre. The symbols themselves are Compose overlays, where the existing MIL-STD renderer can draw them. */
class ThreatOverlay {
    private var source: GeoJsonSource? = null
    private var style: Style? = null
    private var latest = ThreatScene.EMPTY

    /** The masks on the map now, by id: one that is the same picture is left alone, and one that is no longer in the scene is taken off. */
    private val drawn = LinkedHashMap<String, ThreatMaskPicture>()

    fun install(style: Style) {
        this.style = style
        drawn.clear()                                               // a new style has none of the old one's sources
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
        showMasks(scene.masks)
    }

    private fun showMasks(masks: List<ThreatMaskPicture>) {
        val current = style?.takeIf { it.isFullyLoaded } ?: return
        val wanted = masks.associateBy { it.id }
        for (id in drawn.keys.toList()) {
            if (wanted[id] == drawn[id]) continue
            current.removeLayer(layerOf(id))
            current.removeSource(sourceOf(id))
            drawn.remove(id)
        }
        for ((id, picture) in wanted) {
            if (id in drawn) continue
            val bitmap = decodeDataUri(picture.dataUri) ?: continue                // a picture that will not decode is left off, not drawn wrong
            val corners = LatLngQuad(LatLng(picture.north, picture.west), LatLng(picture.north, picture.east), LatLng(picture.south, picture.east), LatLng(picture.south, picture.west))
            current.addSource(ImageSource(sourceOf(id), corners, bitmap))
            // Under the range rings, over the map: the colours are the server's, and already carry their own transparency.
            val layer = RasterLayer(layerOf(id), sourceOf(id)).withProperties(PropertyFactory.rasterOpacity(1f), PropertyFactory.rasterFadeDuration(0f))
            if (current.getLayer(SOLID_LAYER) != null) current.addLayerBelow(layer, SOLID_LAYER) else current.addLayer(layer)
            drawn[id] = picture
        }
    }

    private fun sourceOf(id: String) = "threat-mask-$id"

    private fun layerOf(id: String) = "threat-mask-layer-$id"

    private companion object {
        const val SOURCE = "threats"
        const val SOLID_LAYER = "threat-rings-solid"
        const val DASHED_LAYER = "threat-rings-dashed"
    }
}
