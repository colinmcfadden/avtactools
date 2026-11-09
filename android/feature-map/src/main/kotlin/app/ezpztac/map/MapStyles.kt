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
    /** How far it reaches, so that no tile is asked for past it; null: the whole world. */
    val bounds: ChartBox? = null,
    /** Drawn under it, each within its own bounds: the ArcGIS sectional where a build of the FAA's charts has none (docs/VFR_CHARTS.md §4.6). */
    val under: List<MapBaseStyle> = emptyList(),
    /** The size they are drawn at on a dense screen ([levelsFiner]). Half for a build's tiles, which hold only their size in pixels; the same for the rest. */
    val denseTileSize: Int = tileSize,
)

object MapStyles {
    /** What a diagram that saved nothing, or a name this version has never heard of, is drawn on. */
    const val DEFAULT_ID = "satellite"

    /** The VFR map's id, however it is drawn. */
    const val VFR_ID = "vfr-sectional"

    private const val MAPBOX_ATTRIBUTION = "&copy; <a href=\"https://www.mapbox.com/about/maps/\">Mapbox</a>"

    private fun mapbox(id: String, label: String, styleId: String, token: String) = MapBaseStyle(
        id = id, label = label,
        tiles = "https://api.mapbox.com/styles/v1/mapbox/$styleId/tiles/512/{z}/{x}/{y}@2x?access_token=$token",
        attribution = MAPBOX_ATTRIBUTION, tileSize = 512, minZoom = 0, maxZoom = 20, needsMapboxToken = true,
    )

    private val ARCGIS_VFR = MapBaseStyle(
        id = VFR_ID, label = "VFR",
        tiles = "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/{z}/{y}/{x}",
        attribution = "FAA Aeronautical Information Services", tileSize = 256,
        // FAA only publishes levels 8 to 12.
        minZoom = 8, maxZoom = 12, needsMapboxToken = false,
    )

    // The live build where it reaches, over ArcGIS in each region it has no chart of (Hawaii, Alaska); with no build, ArcGIS everywhere (§4.6).
    private fun vfr(live: VfrLive?): MapBaseStyle = if (live == null) ARCGIS_VFR else ARCGIS_VFR.copy(
        tiles = live.tiles, tileSize = live.tileSize, minZoom = live.minZoom, maxZoom = live.maxZoom, bounds = live.bounds,
        under = live.fallback.map { ARCGIS_VFR.copy(bounds = it) }, denseTileSize = live.tileSize / 2,
    )

    /**
     * The base maps that can be drawn right now. Without a Mapbox token (the server was never reached and none was remembered) only
     * the FAA chart can be, and the switcher shows just that rather than a style that would draw nothing.
     */
    fun available(mapboxToken: String?, charts: VfrLive? = null): List<MapBaseStyle> {
        val token = mapboxToken?.takeIf { it.startsWith("pk.") }
        return buildList {
            if (token != null) {
                add(mapbox("satellite", "Satellite", "satellite-v9", token))
                add(mapbox("topo", "Topo", "outdoors-v12", token))
            }
            add(vfr(charts))
        }
    }

    /** The style a saved id names, or the first one that can be drawn if it names none (a stale or unknown id must not blank the map). */
    fun byId(id: String?, mapboxToken: String?, charts: VfrLive? = null): MapBaseStyle =
        available(mapboxToken, charts).let { all -> all.firstOrNull { it.id == id } ?: all.first() }

    /**
     * A MapLibre style document for [style]: its tiles over a background in the app's dark navy (so the first frame, and a tile that
     * has not arrived, are not white). The layer on top is always `base`, which the overlays are put above; those it stands over go under it.
     * Overlays are added to the loaded style afterwards. [density] is the screen's pixels to the point, which says how fine its charts are drawn.
     */
    fun styleJson(style: MapBaseStyle, density: Double = 1.0): String = buildJsonObject {
        put("version", 8)
        put("name", style.id)
        putJsonObject("sources") {
            style.under.forEachIndexed { index, below -> put("$UNDER$index", sourceOf(below, density)) }
            put("base", sourceOf(style, density))
        }
        putJsonArray("layers") {
            addJsonObject {
                put("id", "background")
                put("type", "background")
                putJsonObject("paint") { put("background-color", BACKGROUND) }
            }
            style.under.indices.forEach { index ->
                addJsonObject {
                    put("id", "$UNDER$index")
                    put("type", "raster")
                    put("source", "$UNDER$index")
                }
            }
            addJsonObject {
                put("id", "base")
                put("type", "raster")
                put("source", "base")
            }
        }
    }.toString()

    private fun sourceOf(style: MapBaseStyle, density: Double) = buildJsonObject {
        put("type", "raster")
        putJsonArray("tiles") { add(JsonPrimitive(style.tiles)) }
        put("tileSize", if (levelsFiner(density) > 0) style.denseTileSize else style.tileSize)
        put("minzoom", style.minZoom)
        put("maxzoom", style.maxZoom)
        put("attribution", style.attribution)
        style.bounds?.let { box -> putJsonArray("bounds") { add(JsonPrimitive(box.west)); add(JsonPrimitive(box.south)); add(JsonPrimitive(box.east)); add(JsonPrimitive(box.north)) } }
    }

    private const val UNDER = "fallback-"
    private const val BACKGROUND = "#092137"
}
