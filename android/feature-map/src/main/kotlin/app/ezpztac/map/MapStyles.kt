package app.ezpztac.map

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One base map. The same three the web offers (`frontend/src/feature/mapStyles/mapStyles.js`), under the same ids, because a diagram saves its id. */
data class MapBaseStyle(
    val id: String,
    val label: String,
    /** An XYZ tile template. The tokens may come in any order (ArcGIS puts `{y}` before `{x}`). */
    val tiles: String,
    val attribution: String,
    /** The size the tiles are *drawn* at, in points. Mapbox's `@2x` 512-point tiles are 1024-pixel images. */
    val tileSize: Int,
    /** The zoom range the server actually has tiles for; outside it the map stretches what it has instead of going blank. */
    val minZoom: Int,
    val maxZoom: Int,
    /** Mapbox's styles need the public token the server hands out (`/api/config`); the FAA chart does not. */
    val needsMapboxToken: Boolean,
)

object MapStyles {
    /** What a diagram that saved nothing, or a name this version has never heard of, is drawn on. */
    const val DEFAULT_ID = "satellite"

    private const val MAPBOX_ATTRIBUTION = "&copy; <a href=\"https://www.mapbox.com/about/maps/\">Mapbox</a>"

    private fun mapbox(id: String, label: String, styleId: String, token: String) = MapBaseStyle(
        id = id, label = label,
        tiles = "https://api.mapbox.com/styles/v1/mapbox/$styleId/tiles/512/{z}/{x}/{y}@2x?access_token=$token",
        attribution = MAPBOX_ATTRIBUTION, tileSize = 512, minZoom = 0, maxZoom = 20, needsMapboxToken = true,
    )

    private val VFR = MapBaseStyle(
        id = "vfr-sectional", label = "VFR",
        tiles = "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/{z}/{y}/{x}",
        attribution = "FAA Aeronautical Information Services", tileSize = 256,
        // FAA only publishes levels 8 to 12.
        minZoom = 8, maxZoom = 12, needsMapboxToken = false,
    )

    /**
     * The base maps that can be drawn right now. Without a Mapbox token (the server was never reached and none was remembered) only
     * the FAA chart can be, and the switcher shows just that rather than a style that would draw nothing.
     */
    fun available(mapboxToken: String?): List<MapBaseStyle> {
        val token = mapboxToken?.takeIf { it.startsWith("pk.") }
        return buildList {
            if (token != null) {
                add(mapbox("satellite", "Satellite", "satellite-v9", token))
                add(mapbox("topo", "Topo", "outdoors-v12", token))
            }
            add(VFR)
        }
    }

    /** The style a saved id names, or the first one that can be drawn if it names none (a stale or unknown id must not blank the map). */
    fun byId(id: String?, mapboxToken: String?): MapBaseStyle = available(mapboxToken).let { all -> all.firstOrNull { it.id == id } ?: all.first() }

    /**
     * A MapLibre style document for [style]: its tiles over a background in the app's dark navy (so the first frame, and a tile that
     * has not arrived, are not white). Overlays are added to the loaded style afterwards.
     */
    fun styleJson(style: MapBaseStyle): String = buildJsonObject {
        put("version", 8)
        put("name", style.id)
        putJsonObject("sources") {
            putJsonObject("base") {
                put("type", "raster")
                putJsonArray("tiles") { add(JsonPrimitive(style.tiles)) }
                put("tileSize", style.tileSize)
                put("minzoom", style.minZoom)
                put("maxzoom", style.maxZoom)
                put("attribution", style.attribution)
            }
        }
        putJsonArray("layers") {
            addJsonObject {
                put("id", "background")
                put("type", "background")
                putJsonObject("paint") { put("background-color", BACKGROUND) }
            }
            addJsonObject {
                put("id", "base")
                put("type", "raster")
                put("source", "base")
            }
        }
    }.toString()

    private const val BACKGROUND = "#092137"
}
