package app.ezpztac.map

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.Doghouses
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.planning.AircraftGeometry
import app.ezpztac.planning.AircraftLookup
import app.ezpztac.planning.PlanningGraphics
import app.ezpztac.planning.RouteCalc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A placed aircraft, as drawn: where, turned how, as big as its rotor, and red if it is too close to another. */
data class SceneAircraft(
    val ref: GraphicRef,
    val at: LatLon,
    val rotationDeg: Double,
    val diameterM: Double,
    /** The tip clearance this aircraft needs from another's rotor, metres: the keep-out ring round it is its rotor radius plus this. */
    val clearanceM: Double,
    val iconKey: String,
    val designation: String,
    val violating: Boolean,
    val selected: Boolean,
)

/** The line between two aircraft's rotor tips and what it says, in feet. The web draws one for every pair, red when too close. */
data class SceneSeparation(val from: LatLon, val to: LatLon, val gapFt: Double, val violating: Boolean) {
    val label: String get() = "${max(0.0, RouteCalc.jsRound(gapFt)).toLong()} ft"
    val middle: LatLon get() = LatLon((from.lat + to.lat) / 2, (from.lon + to.lon) / 2)
}

/** A PZ marker: an arrow from its anchor to its tip. */
data class ScenePz(val ref: GraphicRef, val anchor: LatLon, val tip: LatLon, val selected: Boolean)

data class SceneSector(val ref: GraphicRef, val ring: List<LatLon>, val selected: Boolean)

/** A go-around arrow. [direction] is the web's "left" or "right". */
data class SceneGoAround(val ref: GraphicRef, val at: LatLon, val rotationDeg: Double, val direction: String?, val selected: Boolean)

/**
 * A doghouse: the label box beside the landing zone, turned by its heading. It says what the web's box says, in the web's words:
 * [label] (`[SP1]`), the [heading] in three digits, the time as [minutes] and [seconds], and the distance and airspeed as written.
 */
data class SceneDoghouse(
    val ref: GraphicRef,
    val at: LatLon,
    val rotationDeg: Double,
    val label: String,
    val heading: String,
    val minutes: String,
    val seconds: String,
    val distanceKm: String,
    val airspeedKts: String,
    val selected: Boolean,
)

/**
 * A unit: a MIL-STD-2525C symbol with the labels round it, standing at [at]. [sidc] is null for an older unit that was an image rather than a
 * symbol, which is drawn as a plain marker.
 */
data class SceneUnit(
    val ref: GraphicRef,
    val at: LatLon,
    val sidc: String?,
    val uniqueDesignation: String,
    val higherFormation: String,
    val selected: Boolean,
)

/**
 * What is drawn for a diagram's planning graphics, as plain data (see [LzScene]). Aircraft are drawn to scale, separation lines run between
 * rotor tips, and an aircraft that is too close to another is red. The aircraft are measured as the web measures them (each by its own
 * profile), so what is drawn is what the alerts say.
 */
data class GraphicsScene(
    val aircraft: List<SceneAircraft> = emptyList(),
    val separations: List<SceneSeparation> = emptyList(),
    val pzMarkers: List<ScenePz> = emptyList(),
    val sectors: List<SceneSector> = emptyList(),
    val goArounds: List<SceneGoAround> = emptyList(),
    val doghouses: List<SceneDoghouse> = emptyList(),
    val units: List<SceneUnit> = emptyList(),
) {
    val isEmpty: Boolean get() = aircraft.isEmpty() && pzMarkers.isEmpty() && sectors.isEmpty() && goArounds.isEmpty() && doghouses.isEmpty() && units.isEmpty()

    /** The graphic that is selected, as a position the selection halo sits on. Null when nothing is selected. */
    val selectedAt: LatLon?
        get() = aircraft.firstOrNull { it.selected }?.at ?: pzMarkers.firstOrNull { it.selected }?.anchor
            ?: goArounds.firstOrNull { it.selected }?.at ?: doghouses.firstOrNull { it.selected }?.at ?: units.firstOrNull { it.selected }?.at
            ?: sectors.firstOrNull { it.selected }?.ring?.let(::centroid)

    private fun centroid(ring: List<LatLon>) = LatLon(ring.sumOf { it.lat } / ring.size, ring.sumOf { it.lon } / ring.size)

    companion object {
        val EMPTY = GraphicsScene()

        /** Metres in a degree of latitude on the Web Mercator sphere the map draws on (its radius is 6,378,137 m). */
        private const val EARTH_RADIUS_M = 6_378_137.0

        /**
         * The graphics of [diagram]. [profiles] and [active] say what each aircraft is (by the profile slug it was placed with); [selected]
         * is the graphic the person is holding, if any.
         */
        fun of(diagram: Diagram?, profiles: List<AircraftProfile>, active: AircraftProfile, selected: GraphicRef? = null): GraphicsScene {
            if (diagram == null) return EMPTY
            val g = diagram.graphics
            val placed = g.helicopters.mapNotNull { saved -> PlanningGraphics.Aircraft.of(saved)?.let { saved as JsonObject to it } }
            val resolve = { a: PlanningGraphics.Aircraft -> AircraftLookup.profileForAsset(a.profileRef, profiles, active) }

            val separations = mutableListOf<SceneSeparation>()
            val violating = mutableSetOf<String>()
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                val (_, a) = placed[i]
                val (_, b) = placed[j]
                val profileA = resolve(a)
                val profileB = resolve(b)
                val edge = PlanningGraphics.rotorEdge(a.at, b.at, AircraftGeometry.rotorRadiusFt(profileA), AircraftGeometry.rotorRadiusFt(profileB))
                val required = AircraftGeometry.pairSeparation(profileA, profileB).requiredClearanceFt
                val tooClose = edge.edgeDistFt < required
                separations += SceneSeparation(edge.start, edge.end, edge.edgeDistFt, tooClose)
                if (tooClose) { violating += DiagramOps.idText(a.id); violating += DiagramOps.idText(b.id) }
            }

            val aircraft = placed.map { (saved, a) ->
                val profile = resolve(a)
                val key = DiagramOps.idText(a.id)
                val ref = GraphicRef("helicopters", key)
                SceneAircraft(
                    ref = ref, at = a.at, rotationDeg = number(saved["rotation"]) ?: 0.0, diameterM = profile.rotorDiameterM, clearanceM = profile.rotorTipClearanceM,
                    iconKey = AircraftIcons.keyFor(profile.iconKey), designation = profile.designation, violating = key in violating, selected = ref == selected,
                )
            }

            val pz = g.pzMarkers.mapNotNull { saved ->
                val o = saved as? JsonObject ?: return@mapNotNull null
                val lat = number(o["lat"]) ?: return@mapNotNull null
                val lon = number(o["lon"]) ?: return@mapNotNull null
                // The web draws a marker with no tip 0.001 degrees west of its anchor (it makes new ones 0.002 west).
                val tip = LatLon(number(o["tipLat"]) ?: lat, number(o["tipLon"]) ?: (lon - 0.001))
                val ref = GraphicRef("pzMarkers", DiagramOps.idText(o["id"]))
                ScenePz(ref, LatLon(lat, lon), tip, ref == selected)
            }

            val sectors = g.sectorsOfFire.mapNotNull { saved ->
                val points = ((saved as? JsonObject)?.get("points") as? JsonArray)?.mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    LatLon(number(po["lat"]) ?: return@mapNotNull null, number(po["lng"]) ?: return@mapNotNull null)
                }.orEmpty()
                if (points.size < 3) return@mapNotNull null
                val ref = GraphicRef("sectorsOfFire", DiagramOps.idText((saved as JsonObject)["id"]))
                SceneSector(ref, points, ref == selected)
            }

            val goArounds = g.goArounds.mapNotNull { saved ->
                val o = saved as? JsonObject ?: return@mapNotNull null
                val at = LatLon(number(o["lat"]) ?: return@mapNotNull null, number(o["lon"]) ?: return@mapNotNull null)
                val ref = GraphicRef("goArounds", DiagramOps.idText(o["id"]))
                SceneGoAround(ref, at, number(o["rotation"]) ?: 0.0, (o["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.content, ref == selected)
            }
            val doghouses = g.doghouses.mapNotNull { saved ->
                val o = saved as? JsonObject ?: return@mapNotNull null
                val at = LatLon(number(o["lat"]) ?: return@mapNotNull null, number(o["lon"]) ?: return@mapNotNull null)
                val ref = GraphicRef("doghouses", DiagramOps.idText(o["id"]))
                val rotation = Doghouses.rotation(o)
                val shown = Doghouses.display(o, rotation)
                SceneDoghouse(
                    ref = ref, at = at, rotationDeg = rotation, label = shown.id.orEmpty(), heading = shown.heading, minutes = shown.minutes, seconds = shown.seconds,
                    distanceKm = shown.distanceText, airspeedKts = shown.airspeedText, selected = ref == selected,
                )
            }
            val units = g.units.mapNotNull { saved ->
                val o = saved as? JsonObject ?: return@mapNotNull null
                val at = LatLon(number(o["lat"]) ?: return@mapNotNull null, number(o["lon"]) ?: return@mapNotNull null)
                val ref = GraphicRef("units", DiagramOps.idText(o["id"]))
                SceneUnit(
                    ref = ref, at = at, sidc = (o["sidc"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content,
                    uniqueDesignation = text(o["uniqueDesignation"]), higherFormation = text(o["higherFormation"]), selected = ref == selected,
                )
            }
            return GraphicsScene(aircraft, separations, pz, sectors, goArounds, doghouses, units)
        }

        /** A label as written, as text (a designation of 5 is "5"); nothing for null or absent. */
        private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

        private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

        /** A circle of [radiusM] metres round [center], as a closed ring of [steps] points, on the map's own sphere so it matches the sprite drawn to the same size. */
        fun circle(center: LatLon, radiusM: Double, steps: Int = 48): List<LatLon> {
            val dLat = radiusM / EARTH_RADIUS_M
            val dLon = radiusM / (EARTH_RADIUS_M * cos(Math.toRadians(center.lat)))
            val ring = (0 until steps).map { i ->
                val angle = 2 * Math.PI * i / steps
                LatLon(center.lat + Math.toDegrees(dLat * cos(angle)), center.lon + Math.toDegrees(dLon * sin(angle)))
            }
            return ring + ring.first()
        }

        /** The compass bearing from one point to another, degrees clockwise from north, for a line's arrow head. */
        fun bearing(from: LatLon, to: LatLon): Double {
            val dLon = Math.toRadians(to.lon - from.lon)
            val lat1 = Math.toRadians(from.lat)
            val lat2 = Math.toRadians(to.lat)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}
