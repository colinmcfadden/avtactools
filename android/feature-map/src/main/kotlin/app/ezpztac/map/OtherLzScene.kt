package app.ezpztac.map

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One open LZ/PZ that is not the one being worked on, as the map draws it: dimmed, with its name at its target. */
data class OtherLz(
    val id: String,
    val name: String,
    val target: LatLon?,
    /** Its target's grid, as it was given or found. */
    val grid: String? = null,
    /** Its analysis boundary, when its own view draws it (`view.showLZOutline`, the web's *LZ Box*), as the open one's is. */
    val boundary: List<LatLon>,
    /** A boundary drawn by hand and not analysed yet: drawn whatever the view says, as the open one's is. */
    val drawn: List<LatLon>,
    val graphics: GraphicsScene,
)

/**
 * The open LZ/PZs other than the one being worked on, as plain data (the owner's request of 2026-10-21; the web draws the open one alone, AGENTS.md §15): each
 * dimmed under it, so the picture of the operation stays on the map while the next LZ/PZ is planned. What is drawn of each: its target and name, its boundary,
 * and the graphics that say where things are (aircraft, PZ arrows, sectors, go-arounds). Not its slope map, its separation lines or keep-out rings (alerts are
 * for the one being worked on), nor its doghouses and units, which are drawn over the map one by one and would crowd it.
 */
data class OtherLzScene(val lzs: List<OtherLz> = emptyList()) {
    val isEmpty: Boolean get() = lzs.isEmpty()

    /** The vector part as GeoJSON for the map's source, `[longitude, latitude]`: each feature carries a `role` the layers style it by, and the `lz` it belongs to. */
    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            for (lz in lzs) {
                val of = "lz" to JsonPrimitive(lz.id)
                if (lz.boundary.size >= 3) add(feature("boundary", polygon(lz.boundary), of))
                when {
                    lz.drawn.size >= 3 -> add(feature("drawn", polygon(lz.drawn), of))
                    lz.drawn.size == 2 -> add(feature("drawn", line(lz.drawn), of))
                }
                lz.graphics.sectors.forEach { add(feature("sector", polygon(it.ring), of)) }
                lz.graphics.pzMarkers.forEach { pz ->
                    add(feature("pz", line(listOf(pz.anchor, pz.tip)), of))
                    add(feature("pz-anchor", point(pz.anchor), of))
                    add(feature("pz-tip", point(pz.tip), of, "bearing" to JsonPrimitive(GraphicsScene.bearing(pz.anchor, pz.tip))))
                }
                lz.graphics.goArounds.forEach { ga ->
                    add(feature("ga", point(ga.at), of, "icon" to JsonPrimitive(if (ga.direction == "right") "ga-right" else "ga-left"), "rotation" to JsonPrimitive(ga.rotationDeg)))
                }
                // Never red: how close they stand is said for the one being worked on.
                lz.graphics.aircraft.forEach { a ->
                    add(
                        feature(
                            "heli", point(a.at), of, "icon" to JsonPrimitive(LzScene.aircraftImage(a.iconKey, violating = false)),
                            "rotation" to JsonPrimitive(a.rotationDeg), "k0" to JsonPrimitive(LzScene.aircraftK0(a)),
                        ),
                    )
                }
                lz.target?.let { add(feature("target", point(it), of)) }
            }
        }
    }.toString()

    private fun feature(role: String, geometry: JsonObject, vararg properties: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") {
            put("role", role)
            properties.forEach { (k, v) -> put(k, v) }
        }
        put("geometry", geometry)
    }

    private fun position(p: LatLon): JsonArray = buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }

    private fun point(p: LatLon) = buildJsonObject { put("type", "Point"); put("coordinates", position(p)) }

    private fun line(points: List<LatLon>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { add(position(it)) } }
    }

    private fun polygon(points: List<LatLon>) = buildJsonObject {
        put("type", "Polygon")
        putJsonArray("coordinates") { add(buildJsonArray { (points + points.first()).forEach { add(position(it)) } }) }
    }

    companion object {
        val EMPTY = OtherLzScene()

        /** The scene of [diagrams], in their order; [profiles] and [active] say what each placed aircraft is, as for the open one ([GraphicsScene.of]). */
        fun of(diagrams: List<Diagram>, profiles: List<AircraftProfile> = emptyList(), active: AircraftProfile = AircraftProfile.FALLBACK): OtherLzScene =
            OtherLzScene(
                diagrams.map { d ->
                    OtherLz(
                        id = d.id, name = d.name, target = d.target?.let { LatLon(it.lat, it.lon) }, grid = d.target?.mgrs?.takeIf(String::isNotBlank),
                        boundary = if (d.view.showLZOutline) DiagramGeometry.boundary(d) else emptyList(), drawn = DiagramGeometry.drawn(d),
                        graphics = GraphicsScene.of(d, profiles, active).let { it.copy(separations = emptyList(), doghouses = emptyList(), units = emptyList()) },
                    )
                },
            )
    }
}

/**
 * Which open LZ/PZ not being worked on a tap was on, worked out on the screen as [GraphicHitTest] does, so it is tried without a GPU: its target, its name beside it
 * (where [PinLabels.others] puts it), one of its aircraft (anywhere on the rotor disc), a PZ arrow or a go-around, within a finger's reach; the nearest wins. Not
 * anywhere inside its boundary or a sector: those are large, and a tap there on a phone hides the frame, as one inside the open LZ/PZ does.
 */
object OtherLzHitTest {
    fun pick(scene: OtherLzScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): String? {
        val finger = view.toScreen(tap)
        var best: String? = null
        var bestDistance = Double.MAX_VALUE
        fun consider(id: String, distance: Double) {
            if (distance < bestDistance) {
                best = id
                bestDistance = distance
            }
        }
        for (lz in scene.lzs) {
            lz.target?.let { target ->
                val at = view.toScreen(target)
                val distance = GraphicHitTest.distance(finger, at)
                if (distance <= touchRadiusPx || PinLabels.chipContains(at, lz.name, view.density, finger)) consider(lz.id, distance)
            }
            for (aircraft in lz.graphics.aircraft) {
                val distance = GraphicHitTest.distance(finger, view.toScreen(aircraft.at))
                if (distance <= maxOf(touchRadiusPx, view.metersToPixels(aircraft.diameterM / 2, aircraft.at.lat))) consider(lz.id, distance)
            }
            for (pz in lz.graphics.pzMarkers) {
                val distance = GraphicHitTest.distanceToSegment(finger, view.toScreen(pz.anchor), view.toScreen(pz.tip))
                if (distance <= touchRadiusPx) consider(lz.id, distance)
            }
            for (goAround in lz.graphics.goArounds) {
                val distance = GraphicHitTest.distance(finger, view.toScreen(goAround.at))
                if (distance <= touchRadiusPx) consider(lz.id, distance)
            }
        }
        return best
    }
}
