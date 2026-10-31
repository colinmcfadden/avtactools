package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * One point of a saved set of local points, as the web keeps it after importing an AMPS `.LPS` (`{ id, name, description, group, icon, elevationFt, lat,
 * lon }`). [extras] are the fields a newer release adds, kept so an edit never drops them; they are not part of the point's identity.
 */
public data class SetPoint(
    /** The point's own id within its set (the web makes `lps-<n>-<random>`): how a map tap or a list row names it. */
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val icon: String,
    /** The feet AMPS recorded, or null if the file gave no number. */
    val elevationFt: Double?,
    val lat: Double,
    val lon: Double,
    val extras: JsonObject = JsonObject(emptyMap()),
) {
    val at: LatLon get() = LatLon(lat, lon)
}

/**
 * A saved set of local points: what one `.LPS` import made, under a name. The server keeps only the name and the list of points (`/api/pointsets`), so
 * the colour a set is drawn in and whether it is shown are each device's own and are not here.
 *
 * Nothing is dropped on the way through. A field a newer release adds to a point is kept in its `extras`; an entry that cannot be read as a point at all
 * (no position) is kept in [unreadable] and written back after the others.
 */
public data class PointSet(
    /** The record's identity (its `client_uuid`), which is how the set is found whatever else changes. */
    val id: String,
    /** The server's id once it has one; null for a set made here that has not synced. */
    val savedId: Int? = null,
    val name: String,
    val points: List<SetPoint>,
    /** Entries of the list that could not be read as a point, as they were. */
    val unreadable: List<JsonElement> = emptyList(),
) {
    /** How many points the set holds, the ones this version cannot read included (they are kept, so they count). */
    val pointCount: Int get() = points.size + unreadable.size

    /** The point named [pointId], if it is in the set. */
    public fun point(pointId: String): SetPoint? = points.firstOrNull { it.id == pointId }
}

/** Reading a [PointSet] from the server's list of points and writing it back, and making one from an `.LPS` import. */
public object PointSets {
    private val KNOWN = setOf("id", "name", "description", "group", "icon", "elevationFt", "lat", "lon")

    /**
     * The set a record holds: its document is `{"points": [...]}`. Tolerant: a document with no `points` is an empty set, and an entry that has no position
     * is kept as it was ([PointSet.unreadable]) instead of being thrown away or failing the whole set. A text field that is missing or not text is empty,
     * and an elevation that is not a number is none.
     */
    public fun parse(id: String, savedId: Int?, name: String, data: JsonObject): PointSet {
        val raw = (data["points"] as? JsonArray) ?: JsonArray(emptyList())
        val points = ArrayList<SetPoint>()
        val unreadable = ArrayList<JsonElement>()
        raw.forEachIndexed { index, element ->
            val point = readPoint(element, index)
            if (point != null) points += point else unreadable += element
        }
        return PointSet(id = id, savedId = savedId, name = name, points = points, unreadable = unreadable)
    }

    /** The document for [set]: the points as the web writes them, then what could not be read. */
    public fun serialize(set: PointSet): JsonObject =
        JsonObject(mapOf("points" to JsonArray(set.points.map(::writePoint) + set.unreadable)))

    /**
     * A set from the points of an `.LPS` file. [newId] makes each point's id from its place in the file; the web's are `lps-<n>-<random>`, which a caller
     * can reproduce, and nothing else depends on their shape.
     */
    public fun fromLps(id: String, parsed: LocalPointSet, newId: (index: Int) -> String): PointSet = PointSet(
        id = id,
        name = parsed.name,
        points = parsed.points.mapIndexed { i, p ->
            SetPoint(id = newId(i), name = p.name, description = p.description, group = p.group, icon = p.icon, elevationFt = p.elevationFt, lat = p.lat, lon = p.lon)
        },
    )

    private fun readPoint(element: JsonElement, index: Int): SetPoint? {
        val obj = element as? JsonObject ?: return null
        val lat = number(obj["lat"]) ?: return null
        val lon = number(obj["lon"]) ?: return null
        return SetPoint(
            // A point the web saved with no id still has to be named: by its place, which is stable for as long as the set is not reordered.
            id = text(obj["id"]).ifEmpty { "pt-$index" },
            name = text(obj["name"]),
            description = text(obj["description"]),
            group = text(obj["group"]),
            icon = text(obj["icon"]),
            elevationFt = number(obj["elevationFt"]),
            lat = lat,
            lon = lon,
            extras = JsonObject(obj.filterKeys { it !in KNOWN }),
        )
    }

    private fun writePoint(p: SetPoint): JsonElement {
        val typed = mapOf(
            "id" to JsonPrimitive(p.id),
            "name" to JsonPrimitive(p.name),
            "description" to JsonPrimitive(p.description),
            "group" to JsonPrimitive(p.group),
            "icon" to JsonPrimitive(p.icon),
            "elevationFt" to (p.elevationFt?.let(::JsonPrimitive) ?: JsonNull),
            "lat" to JsonPrimitive(p.lat),
            "lon" to JsonPrimitive(p.lon),
        )
        // A field this version knows is never overwritten by a stale extra.
        return JsonObject(typed + p.extras.filterKeys { it !in typed })
    }

    private fun number(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
}

/** What typing [typed] as a route point's name does: the name in capitals, and the local point it names, if any. */
public data class LocalPointMatch(
    val name: String,
    /** The position of the local point the name refers to, or null when it names none. */
    val at: LatLon?,
    /** That point's charted elevation in feet, or null when it has none. */
    val chartElevationFt: Double?,
)

/**
 * The loaded local points by name, the way the web's `RoutePlanSection` finds them: a name is a local point's (capitals, one leading dot of what is typed
 * ignored). A point with no name cannot be found, and of two with one name the later wins. Held to `contracts/fixtures/localpoints/match.json`.
 */
public class LocalPointNames(points: Iterable<SetPoint>) {
    private val byName = HashMap<String, SetPoint>()

    init {
        for (p in points) if (p.name.isNotEmpty()) byName[p.name.uppercase(Locale.ROOT)] = p
    }

    public fun match(typed: String): LocalPointMatch {
        val name = typed.uppercase(Locale.ROOT)
        val found = byName[name.removePrefix(".")]
        return LocalPointMatch(name, found?.at, found?.elevationFt)
    }
}
