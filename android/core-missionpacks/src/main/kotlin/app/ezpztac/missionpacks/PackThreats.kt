package app.ezpztac.missionpacks

import app.ezpztac.model.ThreatSetDocument
import app.ezpztac.model.ThreatSets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A set of threats as a mission pack item: the pure part of the web's `packThreats.js`, on the JSON a pack holds and held to `contracts/fixtures/packs/threats.json`. Everyone
 * shares where a threat is and what it is. Whether it is shown, and its terrain mask, are each person's own, and are never sent: a mask is hundreds of kilobytes of picture, and every
 * operation stays in the pack's log for good. A mask that came with a copy from the Library is drawn while it still fits.
 */
public object PackThreats {
    private val OWN = setOf("visible", "mask", "maskLoading", "maskError")
    private val RADAR_FIELDS = listOf("type", "rangeNmi", "antennaHeightFt", "aglNotMsl", "showMask", "showRangeRings")
    private val BAND_FIELDS = listOf("altFt", "color", "alpha", "colorIndex", "viewable")

    /** A set as the web's `threatSetFromItem` reads one: each threat with this person's own fields beside it, what could not be read, and the document's own fields. */
    public data class Read(val threats: List<JsonObject>, val unreadable: List<JsonElement>, val version: JsonElement, val extras: JsonObject)

    /** What everyone shares of one threat (anything that is not an object as it is). */
    public fun sharedThreat(threat: JsonElement): JsonElement = if (threat is JsonObject) JsonObject(threat.filterKeys { it !in OWN }) else threat

    /** What everyone shares of a set's data: the data, with each of its threats' own fields left out. */
    public fun sharedThreatData(data: JsonElement?): JsonElement? {
        val threats = (data as? JsonObject)?.get("threats") as? JsonArray ?: return data
        return JsonObject(LinkedHashMap(data).apply { put("threats", JsonArray(threats.map(::sharedThreat))) })
    }

    /** What a terrain mask depends on (the web's `maskSubject`): where the threat is and what its radars are, each field that is there. */
    private fun subjectOf(threat: JsonElement?): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        Js.prop(threat, "lat")?.let { fields["lat"] = it }
        Js.prop(threat, "lon")?.let { fields["lon"] = it }
        fields["radars"] = JsonArray(
            (Js.prop(threat, "radars") as? JsonArray).orEmpty().map { radar ->
                val kept = LinkedHashMap<String, JsonElement>()
                RADAR_FIELDS.forEach { key -> Js.prop(radar, key)?.let { kept[key] = it } }
                kept["bands"] = JsonArray(
                    (Js.prop(radar, "bands") as? JsonArray).orEmpty().map { band ->
                        JsonObject(BAND_FIELDS.mapNotNull { key -> Js.prop(band, key)?.let { key to it } }.toMap(LinkedHashMap()))
                    },
                )
                JsonObject(kept)
            },
        )
        return JsonObject(fields)
    }

    private fun maskFits(threat: JsonElement?, mask: JsonElement?): Boolean =
        Js.truthy(Js.prop(mask, "for")) && ThreatSets.sameValue(Js.prop(mask, "for"), subjectOf(threat))

    private fun isNumber(value: JsonElement?): Boolean = value is JsonPrimitive && !value.isString && Js.numberOf(value) != null

    /** Whether a threat can be read at all (the web's `readableThreat`): an object with a place and radars, each with its bands. */
    private fun readable(threat: JsonElement): Boolean {
        if (threat !is JsonObject || !isNumber(threat["lat"]) || !isNumber(threat["lon"])) return false
        val radars = threat["radars"] as? JsonArray ?: return false
        return radars.all { Js.truthy(it) && Js.prop(it, "bands") is JsonArray }
    }

    /**
     * An item's data as this person's set: `threatSetFromItem`. Each threat with an id of its own is read, with what [previous] (the set as it stood here, or null) held for it: whether
     * it was shown, a mask that still fits where the item has none that does, and a mask being worked out for the same place. A threat that cannot be read, or whose id another has, is
     * kept to be written back rather than lost.
     */
    public fun threatSetFromItem(data: JsonElement?, previous: JsonElement?): Read {
        val fields = data as? JsonObject ?: JsonObject(emptyMap())
        val before = HashMap<String, JsonElement>()
        (Js.prop(previous, "threats") as? JsonArray).orEmpty().forEach { threat -> Js.prop(threat, "id")?.takeIf(Js::isId)?.let { before[Js.idKey(it)] = threat } }
        val seen = HashSet<String>()
        val threats = ArrayList<JsonObject>()
        val unreadable = ArrayList<JsonElement>()
        (fields["threats"] as? JsonArray).orEmpty().forEach { threat ->
            val id = Js.prop(threat, "id")?.takeIf(Js::isId)
            if (!readable(threat) || id == null || !seen.add(Js.idKey(id))) {
                unreadable.add(sharedThreat(threat))
                return@forEach
            }
            val had = before[Js.idKey(id)]
            val read = LinkedHashMap((sharedThreat(threat) as JsonObject))
            read["visible"] = Js.prop(had, "visible")?.takeUnless { it is JsonNull } ?: JsonPrimitive(true)
            val sameSubject = had != null && ThreatSets.sameValue(subjectOf(had), subjectOf(JsonObject(read)))
            read["mask"] = when {
                maskFits(JsonObject(read), Js.prop(threat, "mask")) -> Js.prop(threat, "mask")
                had != null && maskFits(JsonObject(read), Js.prop(had, "mask")) -> Js.prop(had, "mask")
                else -> null
            } ?: JsonNull
            read["maskLoading"] = JsonPrimitive(sameSubject && Js.truthy(Js.prop(had, "maskLoading")))
            read["maskError"] = (if (sameSubject) Js.prop(had, "maskError") else null) ?: JsonNull
            threats.add(JsonObject(read))
        }
        val version = fields["version"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(ThreatSets.DOCUMENT_VERSION)
        return Read(threats, unreadable, version, JsonObject(fields.filterKeys { it != "threats" && it != "version" }))
    }

    /** A set as its item's data (`threatSetDoc`): the threats as everyone shares them, then what could not be read. */
    public fun threatSetDoc(set: Read): JsonObject = JsonObject(
        LinkedHashMap(set.extras).apply {
            put("version", set.version)
            put("threats", JsonArray(set.threats.map(::sharedThreat) + set.unreadable))
        },
    )

    /**
     * One sentence for the pack's history about a change from [before] to [after] (`describeThreatChange`): the first threat added, moved or changed, else the first removed. Cut to the
     * length of a sentence, never through a character.
     */
    public fun describeThreatChange(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String {
        val set = name?.takeIf { it.isNotEmpty() } ?: "the threats"
        val phrases = ArrayList<String>()
        val was = byId(Js.prop(before, "threats"))
        val now = byId(Js.prop(after, "threats"))
        now.forEach { (id, threat) ->
            val old = was[id]
            when {
                old == null -> phrases += "added ${called(threat)} to $set"
                !Js.strictEquals(Js.prop(old, "lat"), Js.prop(threat, "lat")) || !Js.strictEquals(Js.prop(old, "lon"), Js.prop(threat, "lon")) -> phrases += "moved ${called(threat)}"
                !PackDiff.sameData(old, threat) -> phrases += "changed ${called(threat)}"
            }
        }
        was.forEach { (id, threat) -> if (id !in now) phrases += "removed ${called(threat)} from $set" }
        return Js.cut("${actor?.takeIf { it.isNotEmpty() } ?: "Someone"} ${phrases.firstOrNull() ?: "edited $set"}.", PackLz.MAX_SENTENCE)
    }

    private fun byId(list: JsonElement?): LinkedHashMap<String, JsonElement> {
        val map = LinkedHashMap<String, JsonElement>()
        (list as? JsonArray).orEmpty().forEach { threat -> if (threat is JsonObject) threat["id"]?.takeIf(Js::isId)?.let { map[Js.idKey(it)] = threat } }
        return map
    }

    private fun called(threat: JsonElement): String = Js.prop(threat, "name").takeIf(Js::truthy)?.let { "the threat ${Js.text(it)}" } ?: "a threat"

    /** The `item.create` that puts [threats] in the pack as a new set, its uuid `ts-` and [newId]; [count] is how many sets the pack has. */
    public fun newThreatSetOp(name: String?, threats: List<JsonElement>, actor: String?, count: Int, newId: () -> String): JsonObject {
        val data = JsonObject(linkedMapOf("version" to JsonPrimitive(ThreatSets.DOCUMENT_VERSION), "threats" to JsonArray(threats.map(::sharedThreat))))
        return PackSentences.newItemOp("threatset", PackSentences.packItemId("threatset", newId()), name, count, data, actor)
    }
}

/** A mission pack's set of threats as the threat store holds it: under the editor's id for the item ([PackRef]). */
public data class PackThreatSet(val id: String, val name: String, val document: ThreatSetDocument)

/**
 * A threat set item as a [PackThreatSet] (`usePackThreats`). Each person's own fields are which threats they have hidden, by id, and their terrain masks, which stay in memory. A set's shape
 * is the shape of the document made from it, so a set opened and not changed sends nothing; and a threat the types here cannot hold (or the web's rules would not read) is written back as
 * it came, after the others.
 */
public object ThreatPackKind : PackKind<PackThreatSet> {
    override val kind: String = "threatset"

    private const val OWN_HIDDEN = "hidden"

    // Where a threat is, is one value: a move north here and a typed grid there would otherwise leave it at half of each.
    private val PLACE = WholeValue(listOf("lat", "lon")) { path -> path.size == 2 && isField(path[0], "threats") && path[1] is JsonObject }

    override fun shared(data: JsonElement?): JsonElement = PackThreats.sharedThreatData(data) ?: JsonNull

    override fun fromItem(localId: String, item: PackItemView, own: JsonObject?): PackThreatSet {
        val hidden = (own?.get(OWN_HIDDEN) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
        val document = typed(PackThreats.threatSetFromItem(item.data, null))
        return PackThreatSet(localId, item.name, document.copy(threats = document.threats.map { t -> if (t.entry.id in hidden) t.copy(entry = t.entry.copy(visible = false)) else t }))
    }

    override fun docOf(document: PackThreatSet, carryFrom: JsonElement?): JsonElement =
        PackThreats.sharedThreatData(ThreatSets.write(document.document, maskBudget = 0)) ?: JsonNull

    override fun nameOf(document: PackThreatSet): String = document.name

    override fun ownOf(document: PackThreatSet): JsonObject =
        JsonObject(mapOf(OWN_HIDDEN to JsonArray(document.document.threats.filterNot { it.entry.visible }.map { JsonPrimitive(it.entry.id) })))

    override fun withShared(document: PackThreatSet, shared: JsonElement, name: String): PackThreatSet =
        document.copy(name = name, document = typed(PackThreats.threatSetFromItem(shared, ThreatSets.write(document.document, maskBudget = Int.MAX_VALUE))))

    override fun describe(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String = PackThreats.describeThreatChange(before, after, name, actor)

    override fun keepWhole(ops: List<JsonObject>, after: JsonElement?): List<JsonObject> = keptWhole(ops, after, listOf(PLACE))

    // A read set as the types hold it. A threat with an id that is not text, or is empty, could not be found again by id here, and stays as it came.
    private fun typed(read: PackThreats.Read): ThreatSetDocument {
        val (named, odd) = read.threats.partition { Js.isString(it["id"]) && (it["id"] as JsonPrimitive).content.isNotEmpty() }
        val document = ThreatSets.read(JsonObject(mapOf("threats" to JsonArray(named))))
        return ThreatSetDocument(document.threats, (document.unreadable + odd + read.unreadable).map(PackThreats::sharedThreat), read.version, read.extras)
    }
}
