package app.ezpztac.brief

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What the brief's setup does to the brief document (docs/MISSION_BRIEF.md §2.2), the edits the web's setup makes (`FlowStep.jsx`, `DetailsStep.jsx`,
 * `ReviewStep.jsx`, `BriefSetup.jsx`) as functions of the document: each takes the document as it is and gives it back changed, so a mission pack's brief is changed
 * against its newest version (`BriefKeeper`), and each keeps the document's other fields, and their order, as the web's spreads do.
 *
 * Only what the person typed or chose is kept; what the compiler found in the plan never is (docs/MISSION_BRIEF.md §2.2): a field typed blank takes its answer away.
 */
public object BriefEdits {
    private fun with(doc: JsonObject, key: String, value: JsonElement): JsonObject = JsonObject(doc + (key to value))

    private fun without(doc: JsonObject, key: String): JsonObject = JsonObject(doc - key)

    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: JsonObject(emptyMap())

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    // Cut by characters, never through one (an emoji is two UTF-16 units): the web's `slice` counts units, and a lone half cannot be stored or sent.
    private fun cutCharacters(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }

    // -- The format ---------------------------------------------------------------------------------------------------------------

    /** The format chosen ([id], `amb` or `hasty`): answers are kept by item, so going back finds them again. A format this version does not have changes nothing. */
    public fun chooseFormat(id: String): (JsonObject) -> JsonObject = { doc ->
        val format = BriefFormats.format(id)
        val current = text(obj(doc["template"])["id"])
        if (format == null || current == id) doc else with(doc, "template", buildJsonObject { put("id", id); put("version", format.template.version) })
    }

    // -- The mission flow (FlowStep.jsx) -----------------------------------------------------------------------------------------

    /** The flow as the document keeps it, from the model's (`flowOf`): each entry's reference, and a stop's role. */
    public fun flowOf(model: JsonObject): List<JsonObject> = (model["flow"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { f ->
        buildJsonObject {
            put("ref", f["ref"] ?: JsonPrimitive(""))
            if (text(f["kind"]) == "stop") f["role"]?.let { put("role", it) }
        }
    }

    /**
     * The flow a change to it starts from: the document's own when it is the flow the [model] shows, give or take what is no longer in the plan (so a change made
     * before the brief was compiled again with the last one keeps that one), else the model's, as the web's. **Not like the web**, whose edits always start
     * from the model drawn, so two made before it caught up undo each other; the web compiles as it draws, so it hardly can.
     */
    private fun flowBase(model: JsonObject, doc: JsonObject): List<JsonObject> {
        val shown = flowOf(model)
        val own = (doc["flow"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return shown
        val entries = (model["flow"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val ownRefs = own.map { text(it["ref"]) }.toSet()
        val fits = ownRefs.all { ref -> entries.any { text(it["ref"]) == ref } } &&
            entries.all { f -> text(f["ref"]) in ownRefs || f["gone"] == JsonPrimitive(true) }
        return if (fits) own else shown
    }

    /** Moves the flow's entry [ref] by [by] places (up is -1), from where it stands; past either end changes nothing. */
    public fun move(model: JsonObject, ref: String, by: Int): (JsonObject) -> JsonObject = { doc ->
        val next = flowBase(model, doc).toMutableList()
        val from = next.indexOfFirst { text(it["ref"]) == ref }
        val to = from + by
        if (from < 0 || to !in next.indices) {
            doc
        } else {
            next[from] = next[to].also { next[to] = next[from] }
            with(doc, "flow", JsonArray(next))
        }
    }

    /** Gives the stop [ref] its [role], which decides where the format briefs it. */
    public fun setRole(model: JsonObject, ref: String, role: String): (JsonObject) -> JsonObject = { doc ->
        with(doc, "flow", JsonArray(flowBase(model, doc).map { f -> if (text(f["ref"]) == ref) JsonObject(f + ("role" to JsonPrimitive(role))) else f }))
    }

    /** Takes an item no longer in the plan ([ref]) out of the flow. */
    public fun removeGone(model: JsonObject, ref: String): (JsonObject) -> JsonObject = { doc ->
        with(doc, "flow", JsonArray(flowBase(model, doc).filter { text(it["ref"]) != ref }))
    }

    /** Back to the routes' order: the person's order and roles go. */
    public val resetFlow: (JsonObject) -> JsonObject = { doc -> without(doc, "flow") }

    /** Whether the flow follows the routes (nothing reordered or re-roled by the person). */
    public fun followsRoutes(doc: JsonObject): Boolean = (doc["flow"] as? JsonArray).isNullOrEmpty()

    // -- The constants ------------------------------------------------------------------------------------------------------------

    /** The constants as the document keeps them (`constantsOf`): the person's list, else the ones offered. */
    public fun constantsOf(doc: JsonObject, catalog: List<BriefConstant>): List<JsonObject> =
        (doc["constants"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: catalog.filter { it.offered }.map { c -> buildJsonObject { put("id", c.id) } }

    private fun withConstants(entries: List<JsonObject>): (JsonObject) -> JsonObject = { doc -> with(doc, "constants", JsonArray(entries)) }

    private fun idOf(entry: JsonObject): String? = text(entry["id"])

    /** The constant [id]'s value, as typed (blank: the plan's value, or a write-in). */
    public fun setConstant(catalog: List<BriefConstant>, id: String, value: String): (JsonObject) -> JsonObject = { doc ->
        withConstants(constantsOf(doc, catalog).map { e -> if (idOf(e) == id) JsonObject(e + ("value" to JsonPrimitive(value))) else e })(doc)
    }

    /** Takes the constant [id] away. */
    public fun removeConstant(catalog: List<BriefConstant>, id: String): (JsonObject) -> JsonObject = { doc ->
        withConstants(constantsOf(doc, catalog).filter { idOf(it) != id })(doc)
    }

    /** Adds one of the catalog's constants ([id]), its value from the plan until typed. */
    public fun addConstant(catalog: List<BriefConstant>, id: String): (JsonObject) -> JsonObject = { doc ->
        val entries = constantsOf(doc, catalog)
        if (entries.any { idOf(it) == id }) doc else withConstants(entries + buildJsonObject { put("id", id) })(doc)
    }

    /** The constants of the catalog not in the brief, as the setup offers them to add. */
    public fun suggestions(doc: JsonObject, catalog: List<BriefConstant>): List<BriefConstant> {
        val present = constantsOf(doc, catalog).mapNotNull(::idOf).toSet()
        return catalog.filter { it.id !in present }
    }

    /** Longest label of a constant of the person's own, in characters (the web's `slice(0, 40)`). */
    public const val CUSTOM_LABEL_MAX: Int = 40

    /** Adds a constant of the person's own (`custom-n`, the first n free), its label trimmed and cut to [CUSTOM_LABEL_MAX]; a blank label adds nothing. */
    public fun addCustomConstant(catalog: List<BriefConstant>, label: String, value: String): (JsonObject) -> JsonObject = { doc ->
        val name = Js.trim(label)
        if (name.isEmpty()) {
            doc
        } else {
            val entries = constantsOf(doc, catalog)
            val present = entries.mapNotNull(::idOf).toSet()
            var n = 1
            while ("custom-$n" in present) n += 1
            val entry = buildJsonObject {
                put("id", "custom-$n")
                put("label", cutCharacters(name, CUSTOM_LABEL_MAX))
                put("value", Js.trim(value))
            }
            withConstants(entries + entry)(doc)
        }
    }

    /** Whether the brief's threats are briefed (the setup's switch; on unless turned off). */
    public fun threatsOn(doc: JsonObject): Boolean = (obj(doc["include"])["threats"] as? JsonPrimitive)?.let { !(!it.isString && it.content == "false") } ?: true

    /** Turns the threats in the brief on or off. */
    public fun setThreats(on: Boolean): (JsonObject) -> JsonObject = { doc ->
        with(doc, "include", JsonObject(obj(doc["include"]) + ("threats" to JsonPrimitive(on))))
    }

    // -- The answers (DetailsStep.jsx) ----------------------------------------------------------------------------------------------

    /** The person's answer for the item [key] (null: none), as the document keeps it. */
    public fun answerOf(doc: JsonObject, key: String): JsonObject? = obj(doc["answers"])[key] as? JsonObject

    /** Sets the answer for [key] (null takes it away). */
    public fun setAnswer(key: String, value: JsonObject?): (JsonObject) -> JsonObject = { doc ->
        val answers = obj(doc["answers"])
        with(doc, "answers", JsonObject(if (value == null) answers - key else answers + (key to value)))
    }

    /** A text, time or note answer as typed: blank takes it away (the plan's value, or a write-in, stands). What is kept is what was typed, untrimmed. */
    public fun setText(key: String, typed: String): (JsonObject) -> JsonObject =
        setAnswer(key, if (Js.trim(typed).isEmpty()) null else buildJsonObject { put("value", typed) })

    /** A choice answer: one of the item's options, or none (a write-in). */
    public fun setChoice(key: String, option: String?): (JsonObject) -> JsonObject =
        setAnswer(key, if (option.isNullOrEmpty()) null else buildJsonObject { put("value", option) })

    /** A list answer, one entry a line as typed: the lines trimmed, blank ones dropped; none takes it away. */
    public fun setList(key: String, typed: String): (JsonObject) -> JsonObject {
        val lines = typed.split("\n").map(Js::trim).filter { it.isNotEmpty() }
        return setAnswer(key, if (lines.isEmpty()) null else buildJsonObject { put("value", JsonArray(lines.map(::JsonPrimitive))) })
    }

    /** A procedure's mode: Standard, Custom or Blank (`standard`, `custom`, `blank`). Blank keeps what was typed for the others, or takes the answer away if nothing was. */
    public fun setMode(key: String, mode: String): (JsonObject) -> JsonObject = { doc ->
        val answer = answerOf(doc, key)
        val next = if (mode == "blank") {
            val kept = answer?.let { Js.truthy(it["fields"]) || Js.truthy(it["value"]) } == true
            if (kept) JsonObject(answer + ("mode" to JsonPrimitive("blank"))) else null
        } else {
            JsonObject(answer.orEmpty() + ("mode" to JsonPrimitive(mode)))
        }
        setAnswer(key, next)(doc)
    }

    /** A procedure's own words (Custom). */
    public fun setProcedureText(key: String, typed: String): (JsonObject) -> JsonObject = { doc ->
        setAnswer(key, JsonObject(answerOf(doc, key).orEmpty() + ("value" to JsonPrimitive(typed))))(doc)
    }

    /** One of a standard procedure's fields ([field]), as typed. */
    public fun setProcedureField(key: String, field: String, typed: String): (JsonObject) -> JsonObject = { doc ->
        val answer = answerOf(doc, key).orEmpty()
        val fields = obj(answer["fields"])
        setAnswer(key, JsonObject(answer + ("fields" to JsonObject(fields + (field to JsonPrimitive(typed))))))(doc)
    }

    /**
     * Every procedure briefed in [steps] (the paragraph's steps, as the model compiled them) set to Standard, the setup's "Use standard for all": an item whose id is
     * `<section>.<item>` of a [procedures] entry, each keeping what was typed for it.
     */
    public fun useStandardForAll(steps: List<JsonObject>, procedures: List<Pair<String, String>>): (JsonObject) -> JsonObject = { doc ->
        val ids = procedures.map { (section, item) -> "$section.$item" }.toSet()
        var answers = obj(doc["answers"])
        steps.forEach { step ->
            (step["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { item ->
                val key = text(item["key"])
                if (key != null && text(item["id"]) in ids) answers = JsonObject(answers + (key to JsonObject(obj(answers[key]) + ("mode" to JsonPrimitive("standard")))))
            }
        }
        with(doc, "answers", answers)
    }

    // -- Rally points -----------------------------------------------------------------------------------------------------------

    /** What a route point is as a rally point: `inbound`, `outbound`, or "" for none. */
    public fun rallyLabel(doc: JsonObject, ref: String, pointId: String): String =
        (doc["rallyPoints"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { text(it["ref"]) == ref && text(it["pointId"]) == pointId }?.let { text(it["label"]) }.orEmpty()

    /** Makes the route point a rally point ([label] `inbound` or `outbound`), or not one ("" ). */
    public fun setRally(ref: String, pointId: String, label: String): (JsonObject) -> JsonObject = { doc ->
        val rest = (doc["rallyPoints"] as? JsonArray).orEmpty().filterNot { r -> (r as? JsonObject)?.let { text(it["ref"]) == ref && text(it["pointId"]) == pointId } == true }
        val next = if (label.isEmpty()) rest else rest + buildJsonObject { put("ref", ref); put("pointId", pointId); put("label", label) }
        with(doc, "rallyPoints", JsonArray(next))
    }

    // -- Scripts ------------------------------------------------------------------------------------------------------------------

    /** Whether the person rewrote step [stepId]'s script. */
    public fun scriptEdited(doc: JsonObject, stepId: String): Boolean = text(obj(doc["scripts"])[stepId]) != null

    /** The step's script in the person's words; null or blank goes back to the script written from the fields. */
    public fun setScript(stepId: String, typed: String?): (JsonObject) -> JsonObject = { doc ->
        val scripts = obj(doc["scripts"])
        val next = if (typed == null || Js.trim(typed).isEmpty()) scripts - stepId else scripts + (stepId to JsonPrimitive(typed))
        with(doc, "scripts", JsonObject(next))
    }

    // -- The PDF's options (ReviewStep.jsx) ----------------------------------------------------------------------------------------

    /** One of the PDF's options ([key]: `script`, `constants`, `lzCards`, `navLogs`, `strips`, `iimc`, `writeIns`, `figureMap`, `paper`, `stripMap`, `stripScale`). */
    public fun setOption(key: String, value: JsonElement): (JsonObject) -> JsonObject = { doc ->
        with(doc, "options", JsonObject(obj(doc["options"]) + (key to value)))
    }

    /** A document option as the person chose it, or null (the default). */
    public fun option(doc: JsonObject, key: String): JsonElement? = obj(doc["options"])[key]

    // -- Reading the model -------------------------------------------------------------------------------------------------------

    /** How many items the brief fills, and how many print as write-in lines (the setup's foot: "12 filled · 4 print as write-in lines"). */
    public fun counts(model: JsonObject): Pair<Int, Int> {
        var filled = 0
        var blanks = 0
        (model["steps"] as? JsonArray).orEmpty().forEach { step ->
            ((step as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { item ->
                val writeIn = (item["writeIn"] as? JsonPrimitive)?.content == "true"
                if (writeIn) blanks++ else if (text(item["kind"]) != "info") filled++
            }
        }
        return filled to blanks
    }

    /** How a paragraph stands in the setup's list (`statusOf`): nothing to fill, all set, or how many items are left to add. */
    public sealed interface Status {
        public data object NothingToFill : Status

        public data object AllSet : Status

        public data class ToAdd(val count: Int) : Status
    }

    /** [paragraph]'s status from the model's [steps]: the items of every step briefing one of its sections, once each. */
    public fun statusOf(paragraph: BriefParagraph, steps: List<JsonObject>): Status {
        val ids = paragraph.sections.map { it.id }.toSet()
        val items = linkedMapOf<String, JsonObject>()
        steps.filter { step -> (step["sections"] as? JsonArray).orEmpty().any { text(it) in ids } }.forEach { step ->
            (step["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { item -> text(item["key"])?.let { items[it] = item } }
        }
        val all = items.values
        if (all.isEmpty() || all.all { text(it["kind"]) == "info" }) return Status.NothingToFill
        val blank = all.count { (it["writeIn"] as? JsonPrimitive)?.content == "true" }
        return if (blank == 0) Status.AllSet else Status.ToAdd(blank)
    }

    /**
     * A standard procedure's summary with its values and the person's fields in place (`summaryOf`): `{value:key}` the standards' value, `{field:key}` what the
     * person typed, a blank one a write-in line.
     */
    public fun summaryOf(procedure: StandardProcedure, standards: BriefStandards, fields: JsonObject?): String =
        Regex("""\{([^{}]*)\}""").replace(procedure.summary) { match ->
            val name = match.groupValues[1]
            val colon = name.indexOf(':')
            val kind = if (colon < 0) name else name.substring(0, colon)
            val key = if (colon < 0) "" else name.substring(colon + 1)
            when (kind) {
                "value" -> standards.values[key]?.value?.takeUnless { it is JsonNull }?.let(Js::text) ?: CompileBrief.WRITE_IN
                "field" -> Js.trim(fields?.get(key)?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()).ifEmpty { CompileBrief.WRITE_IN }
                else -> "…"
            }
        }
}
