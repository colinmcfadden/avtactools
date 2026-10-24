package app.ezpztac.briefs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The compiled brief (core-brief's `CompileBrief`, docs/MISSION_BRIEF.md §2.3) read as the screens show it. Only reading: what a brief says is decided by the compiler,
 * and the screens lay it out (the web's setup reads the same fields).
 */
internal object BriefReading {
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun truth(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

    /** An entry of the mission flow: a route, or a stop with its role; [gone] for an item no longer in the plan. */
    data class FlowEntry(
        val ref: String,
        val kind: String,
        val name: String?,
        val role: String?,
        val grid: String?,
        val start: String?,
        val end: String?,
        val arrive: String?,
        val gone: Boolean,
    )

    fun flow(model: JsonObject): List<FlowEntry> = objects(model["flow"]).map { f ->
        FlowEntry(
            ref = text(f["ref"]).orEmpty(), kind = text(f["kind"]).orEmpty(), name = text(f["name"]), role = text(f["role"]), grid = text(f["grid"]),
            start = text(f["start"]), end = text(f["end"]), arrive = text(f["arrive"]), gone = truth(f["gone"]),
        )
    }

    /** A mission constant as the brief says it: its [value] (null: a write-in) and where it came from (`typed`, `plan`, `standard`, `blank`). */
    data class Constant(val id: String, val label: String, val short: String, val value: String?, val source: String)

    fun constants(model: JsonObject): List<Constant> = objects(model["constants"]).map { c ->
        Constant(text(c["id"]).orEmpty(), text(c["label"]).orEmpty(), text(c["short"]).orEmpty(), text(c["value"]), text(c["source"]).orEmpty())
    }

    /** One part of a sentence: words, or a write-in line ([writeIn]) with what it is for ([label]). */
    data class Part(val text: String, val writeIn: Boolean, val label: String?)

    /** An item of a step, as the brief briefs it: its answer key, kind, where its words came from, the words (null: a write-in). */
    data class Item(val id: String, val key: String, val label: String, val kind: String, val source: String, val text: String?, val writeIn: Boolean)

    /** A fact the step's figure carries (`RALLY: ACP2 (inbound)`). */
    data class Fact(val key: String, val label: String, val value: String?)

    /** A step of the brief: a paragraph's section, or one instance of it per LZ/PZ or route it repeats for. */
    data class Step(
        val id: String,
        val number: String,
        val title: String,
        val who: String,
        val sections: List<String>,
        val items: List<Item>,
        val facts: List<Fact>,
        val script: List<List<Part>>,
        val scriptEdited: Boolean,
    )

    private fun parts(value: JsonElement?): List<Part> = objects(value).map { p ->
        if (p.containsKey("writeIn")) Part("", writeIn = true, label = text(p["label"])) else Part(text(p["text"]).orEmpty(), writeIn = false, label = null)
    }

    fun steps(model: JsonObject): List<Step> = objects(model["steps"]).map { s ->
        Step(
            id = text(s["id"]).orEmpty(), number = text(s["number"]).orEmpty(), title = text(s["title"]).orEmpty(), who = text(s["who"]).orEmpty(),
            sections = (s["sections"] as? JsonArray).orEmpty().mapNotNull { text(it) },
            items = objects(s["items"]).map { i ->
                Item(
                    text(i["id"]).orEmpty(), text(i["key"]).orEmpty(), text(i["label"]).orEmpty(), text(i["kind"]).orEmpty(), text(i["source"]).orEmpty(),
                    text(i["text"]), truth(i["writeIn"]),
                )
            },
            facts = objects(s["facts"]).map { f -> Fact(text(f["key"]).orEmpty(), text(f["label"]).orEmpty(), text(f["value"])) },
            script = (s["script"] as? JsonArray).orEmpty().map { parts(it) },
            scriptEdited = truth(s["scriptEdited"]),
        )
    }

    /** The raw steps, for the edits that read them (core-brief's `BriefEdits.statusOf`, `useStandardForAll`). */
    fun stepObjects(model: JsonObject): List<JsonObject> = objects(model["steps"])

    /** A page of the packet as compiled: its place in the list ([n], never a page the PDF prints it on), its kind and its title or step. */
    data class Page(val n: Int, val kind: String, val stepId: String?, val title: String?)

    fun pages(model: JsonObject): List<Page> = objects(model["pages"]).map { p ->
        Page((p["n"] as? JsonPrimitive)?.intOrNull ?: 0, text(p["kind"]).orEmpty(), text(p["stepId"]), text(p["title"]))
    }

    /** What could not be briefed, in the brief's words: said before briefing. */
    fun problems(model: JsonObject): List<String> = objects(model["problems"]).mapNotNull { text(it["message"]) }

    /** The model's options as compiled (each switch's state). */
    fun option(model: JsonObject, key: String): Boolean = truth((model["options"] as? JsonObject)?.get(key))

    /** The strip charts' count and their first's ground size, in kilometres, for the review's line about them. */
    fun strips(model: JsonObject): List<JsonObject> = objects(model["strips"])

    /** A sentence's parts as plain words, a write-in as a line with what it is for (the web's `Sentence`). */
    fun sentence(parts: List<Part>): String = parts.joinToString("") { p ->
        if (p.writeIn) "${p.label?.let { "$it " }.orEmpty()}$WRITE_IN" else p.text
    }

    const val WRITE_IN = "________"
}
