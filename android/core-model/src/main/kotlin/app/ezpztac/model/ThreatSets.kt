package app.ezpztac.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * One threat of a saved set. [mask] is its terrain mask as the web keeps it (`{bounds, radars, for}`), whole, or null for none; [extras] are the fields of the saved threat this
 * version does not know (the web's `color`, whatever a newer release adds), and [rawRadars] its radars as they were read, written back while nothing has changed them so a
 * field a newer release put on a radar survives.
 */
public data class StoredThreat(
    val entry: ThreatEntry,
    val mask: JsonObject? = null,
    val extras: JsonObject = NOTHING,
    val rawRadars: JsonArray? = null,
) {
    private companion object {
        val NOTHING = JsonObject(emptyMap())
    }
}

/**
 * A set of threats as it is read from the Library. [unreadable] holds what could not be read as a threat, to be written back after the others rather than lost by the
 * next save; [version] and [extras] are the document's own, kept as found.
 */
public data class ThreatSetDocument(
    val threats: List<StoredThreat> = emptyList(),
    val unreadable: List<JsonElement> = emptyList(),
    val version: JsonElement = JsonPrimitive(ThreatSets.DOCUMENT_VERSION),
    val extras: JsonObject = JsonObject(emptyMap()),
)

/**
 * A set of threats as the Library keeps it: the web's `{version: 1, threats: [...]}` (`threatSets.js`), read and written the same way and held to
 * `contracts/fixtures/threats/sets.json`. A terrain mask is stored with its threat and with what it was worked out for (`for`), so one that no longer matches its threat's
 * place or radars is never drawn or kept.
 *
 * **Not like the web**, each with a test: a threat the types here cannot hold (a radar with a band missing a number, a name that is not text) cannot be read and is written back
 * as it came, where the web would carry it; a threat given a new id gets the same one each time its document is read ([read]'s `freshPrefix`), where the web's come from the clock.
 */
public object ThreatSets {
    public const val DOCUMENT_VERSION: Int = 1
    public const val FIRST_SET_NAME: String = "THREATS"
    public const val MAX_NAME: Int = 100

    /** What a set's masks may come to: the server takes 5 MB a set, and past this a mask is left out and worked out again when it is wanted. */
    public const val MASK_BUDGET: Int = 4 * 1024 * 1024

    private val KNOWN = setOf(
        "id", "name", "milstdId", "lat", "lon", "information", "source", "showThreat", "visible", "radars", "mask", "maskLoading", "maskError",
    )

    /**
     * A saved document read for the map. [usedIds] are the threat ids other open sets hold (a duplicate opened beside its original has the same ones, and every edit finds its threat
     * by id). A threat with no id of its own, or one already taken, is called `<freshPrefix>-<place>`, so reading the document again gives it the same one.
     */
    public fun read(data: JsonObject, usedIds: Set<String> = emptySet(), freshPrefix: String = "threat-read"): ThreatSetDocument {
        val list = data["threats"] as? JsonArray
        val seen = HashSet(usedIds)
        val threats = ArrayList<StoredThreat>(list?.size ?: 0)
        val unreadable = ArrayList<JsonElement>()
        list?.forEachIndexed { place, element ->
            val read = readThreat(element, place, seen, freshPrefix)
            if (read == null) unreadable.add(element) else threats.add(read)
        }
        return ThreatSetDocument(threats, unreadable, data["version"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(DOCUMENT_VERSION), JsonObject(data - "threats" - "version"))
    }

    private fun readThreat(element: JsonElement, place: Int, seen: MutableSet<String>, freshPrefix: String): StoredThreat? {
        val saved = element as? JsonObject ?: return null
        val lat = saved.number("lat") ?: return null
        val lon = saved.number("lon") ?: return null
        val raw = saved["radars"] as? JsonArray ?: return null
        val radars = radarsOf(raw) ?: return null
        val threat = Threat(
            name = saved.text("name", Radars.DEFAULT_NAME) ?: return null,
            milstdId = saved.text("milstdId", Radars.DEFAULT_MILSTD_ID) ?: return null,
            lat = lat,
            lon = lon,
            information = saved.text("information", "") ?: return null,
            source = saved.text("source", Radars.DEFAULT_SOURCE) ?: return null,
            showThreat = saved.flag("showThreat", default = true) ?: return null,
            radars = radars,
        )
        val own = (saved["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it !in seen }
        val id = own ?: fresh(freshPrefix, place, seen)
        seen.add(id)
        val entry = ThreatEntry(id, threat, visible = (saved["visible"] as? JsonPrimitive)?.booleanOrNull != false)
        val mask = (saved["mask"] as? JsonObject)?.takeIf { maskFits(threat, it) }
        return StoredThreat(entry, mask, JsonObject(saved.filterKeys { it !in KNOWN }), raw)
    }

    private fun fresh(prefix: String, place: Int, seen: Set<String>): String {
        var n = place
        while (true) {
            val id = "$prefix-$n"
            if (id !in seen) return id
            n++
        }
    }

    /** The radars of a saved threat, or null when the types here cannot hold them. */
    public fun radarsOf(element: JsonElement): List<Radar>? {
        val list = element as? JsonArray ?: return null
        return list.map { radarOf(it) ?: return null }
    }

    private fun radarOf(element: JsonElement): Radar? {
        val radar = element as? JsonObject ?: return null
        val bands = (radar["bands"] as? JsonArray ?: return null).map { bandOf(it) ?: return null }
        return Radar(
            type = radar.whole("type") ?: return null,
            rangeNmi = radar.number("rangeNmi") ?: return null,
            antennaHeightFt = radar.number("antennaHeightFt") ?: return null,
            aglNotMsl = radar.flag("aglNotMsl") ?: return null,
            showMask = radar.flag("showMask") ?: return null,
            showRangeRings = radar.flag("showRangeRings") ?: return null,
            bands = bands,
        )
    }

    private fun bandOf(element: JsonElement): RadarBand? {
        val band = element as? JsonObject ?: return null
        return RadarBand(
            altFt = band.number("altFt") ?: return null,
            color = (band["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null,
            alpha = band.number("alpha") ?: return null,
            colorIndex = band.whole("colorIndex") ?: return null,
            viewable = band.flag("viewable") ?: return null,
        )
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.whole(key: String): Int? = number(key)?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) < Int.MAX_VALUE }?.toInt()

    /** A flag, or null when it is there and is not one. Absent is [default], or null with none. */
    private fun JsonObject.flag(key: String, default: Boolean? = null): Boolean? {
        val value = this[key] ?: return default
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }

    /** Text, or [default] when it is absent, or null when it is there and is not text. */
    private fun JsonObject.text(key: String, default: String): String? {
        val value = this[key] ?: return default
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** The document saved for a set: every threat, and each mask while the document stays within [maskBudget]. */
    public fun write(document: ThreatSetDocument, maskBudget: Int = MASK_BUDGET): JsonObject {
        var room = maskBudget
        val written = document.threats.map { stored ->
            val mask = stored.mask?.takeIf { maskFits(stored.entry.threat, it) }
            val size = mask?.toString()?.length ?: 0
            if (mask != null && size <= room) {
                room -= size
                threatJson(stored, mask)
            } else {
                threatJson(stored, null)
            }
        }
        return JsonObject(document.extras + ("version" to document.version) + ("threats" to JsonArray(written + document.unreadable)))
    }

    private fun threatJson(stored: StoredThreat, mask: JsonObject?): JsonObject {
        val threat = stored.entry.threat
        val raw = stored.rawRadars?.takeIf { radarsOf(it) == threat.radars }
        return buildJsonObject {
            put("id", stored.entry.id)
            put("name", threat.name)
            put("milstdId", threat.milstdId)
            put("lat", number(threat.lat))
            put("lon", number(threat.lon))
            put("information", threat.information)
            put("source", threat.source)
            put("showThreat", threat.showThreat)
            put("visible", stored.entry.visible)
            put("radars", raw ?: radarsJson(threat.radars))
            stored.extras.forEach { (key, value) -> put(key, value) }
            if (mask != null) put("mask", mask)
        }
    }

    private fun radarsJson(radars: List<Radar>): JsonArray = JsonArray(
        radars.map { radar ->
            buildJsonObject {
                put("type", number(radar.type.toDouble()))
                put("rangeNmi", number(radar.rangeNmi))
                put("antennaHeightFt", number(radar.antennaHeightFt))
                put("aglNotMsl", radar.aglNotMsl)
                put("showMask", radar.showMask)
                put("showRangeRings", radar.showRangeRings)
                put(
                    "bands",
                    JsonArray(
                        radar.bands.map { band ->
                            buildJsonObject {
                                put("altFt", number(band.altFt))
                                put("color", band.color)
                                put("alpha", number(band.alpha))
                                put("colorIndex", number(band.colorIndex.toDouble()))
                                put("viewable", band.viewable)
                            }
                        },
                    ),
                )
            }
        },
    )

    /** A number as JavaScript writes it, so `50.0` is `50` in the document as it is in the web's. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun number(value: Double): JsonElement = if (value.isFinite()) JsonUnquotedLiteral(JsNumber.toText(value)) else JsonNull

    /** What a terrain mask depends on: where the threat is and what its radars are. */
    public fun subjectOf(threat: Threat): JsonObject = buildJsonObject {
        put("lat", number(threat.lat))
        put("lon", number(threat.lon))
        put("radars", radarsJson(threat.radars))
    }

    /** Whether [mask] is still the mask of [threat]: the same place and the same radars. */
    public fun maskFits(threat: Threat, mask: JsonObject?): Boolean {
        val subject = mask?.get("for") ?: return false
        return sameValue(subject, subjectOf(threat))
    }

    /** Equal as values, whatever order their keys are in and however a number is spelt (the API answers with its keys sorted). */
    public fun sameValue(a: JsonElement?, b: JsonElement?): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> sameValue(value, b[key]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameValue(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> samePrimitive(a, b)
        else -> a == b
    }

    private fun samePrimitive(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a.isString != b.isString) return false
        if (a.isString) return a.content == b.content
        val x = a.doubleOrNull
        val y = b.doubleOrNull
        return if (x != null && y != null) x == y else a.content == b.content
    }

    /** Two versions that differ only in their masks: no conflict, since a mask is worked out, not planned. */
    public fun sameApartFromMasks(a: JsonObject, b: JsonObject): Boolean = sameValue(withoutMasks(a), withoutMasks(b))

    private fun withoutMasks(data: JsonObject): JsonObject {
        val threats = data["threats"] as? JsonArray ?: return data
        return JsonObject(data + ("threats" to JsonArray(threats.map { if (it is JsonObject) JsonObject(it - "mask") else it })))
    }

    /** Whether two versions of one threat are the same apart from their masks. */
    private fun samePlan(a: StoredThreat, b: StoredThreat): Boolean = a.entry == b.entry && a.extras == b.extras

    /**
     * Someone else's change ([theirs]) laid over this device's ([mine]), both made from [base], threat by threat. A threat only they changed takes their version, one only this device
     * changed keeps it, and one changed on both keeps this device's. One edited there and removed here stays, edited: no work is lost to a tap on another device; and a mask, being worked
     * out rather than planned, is never a reason to keep an older threat.
     */
    public fun merge(base: List<StoredThreat>, mine: List<StoredThreat>, theirs: List<StoredThreat>): List<StoredThreat> {
        val before = base.associateBy { it.entry.id }
        val here = mine.associateBy { it.entry.id }
        val there = theirs.associateBy { it.entry.id }
        val out = ArrayList<StoredThreat>(theirs.size + mine.size)
        for (t in theirs) {
            val id = t.entry.id
            val b = before[id]
            val m = here[id]
            when {
                b == null -> out.add(m ?: t)
                m == null -> if (!samePlan(t, b)) out.add(t)
                !samePlan(m, b) -> out.add(m)
                !samePlan(t, b) -> out.add(t)
                else -> out.add(if (m.mask != b.mask) m else t)
            }
        }
        for (m in mine) {
            val id = m.entry.id
            if (id in there) continue
            val b = before[id]
            if (b == null || !samePlan(m, b)) out.add(m)
        }
        return out
    }

    /** A file's name as the set it becomes is called. */
    public fun nameFromFile(fileName: String): String {
        val bare = if (fileName.endsWith(".ths", ignoreCase = true)) fileName.dropLast(".ths".length) else fileName
        return cut(JsNumber.trimJs(bare).uppercase()).ifEmpty { FIRST_SET_NAME }
    }

    /** How many threats a saved document holds, for a list that has no need to read them. */
    public fun countIn(data: JsonObject): Int = (data["threats"] as? JsonArray)?.size ?: 0

    /** A name as it is kept: trimmed, in capitals, and no longer than a name may be, counted in characters so no emoji is cut in two. */
    public fun cleanName(name: String): String = cut(JsNumber.trimJs(name).uppercase())

    private fun cut(text: String): String =
        if (text.codePointCount(0, text.length) <= MAX_NAME) text else text.substring(0, text.offsetByCodePoints(0, MAX_NAME))
}
