package app.ezpztac.brief

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The brief's formats are data (contracts/briefs, its README is the format). The web's briefTemplates.test.js holds every rule the compiler
 * leans on; these are the same checks against the same files, so a template that breaks one fails here too, and the files the app bundles
 * are those files byte for byte.
 */
class BriefTemplatesTest {
    private val directory = File(System.getProperty("ezpz.briefs") ?: error("ezpz.briefs is not set; run the tests through Gradle"))
    private fun read(name: String): JsonObject = Json.parseToJsonElement(File(directory, name).readText()).jsonObject

    private val templateIds = listOf("amb", "hasty")
    private val roles = listOf("PZ", "LZ", "FARP", "HA", "LAAGER", "FALSE_LZ")
    private val kinds = listOf("text", "note", "time", "choice", "procedure", "list", "info")
    private val figures = listOf("overview", "departure", "route", "stop", "rally", "none")
    private val scopes = listOf("brief", "stop", "route")
    private val types = listOf("text", "time", "heading", "feet", "knots", "grid", "count", "list")
    private val itemId = Regex("^[a-z]+(\\.[a-z]+)*$")

    private val bindings = read("bindings.json")["bindings"]!!.jsonObject
    private val constants = read("constants.json")["constants"]!!.jsonArray.map { it.jsonObject }
    private val standards = read("standards.json")
    private val procedures = standards["procedures"]!!.jsonObject
    private val values = standards["values"]!!.jsonObject

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun blank(value: JsonElement?) = value.str()?.trim().isNullOrEmpty()
    private fun list(value: JsonElement?): List<JsonElement> = (value as? JsonArray).orEmpty()

    private fun scopeOf(section: JsonObject): String = when (section["repeat"].str()) {
        null -> if (section["repeat"] == null) "brief" else "stop"
        "route" -> "route"
        else -> "stop"
    }

    private fun scopeAllows(sectionScope: String, bindingScope: String?) = bindingScope == "brief" || bindingScope == sectionScope

    private val placeholder = Regex("\\{([^{}]*)\\}")
    private fun placeholders(text: String) = placeholder.findAll(text).map { it.groupValues[1] }.toList()
    private fun strayBraces(text: String) = placeholder.replace(text, "").any { it == '{' || it == '}' }
    private fun splitKind(name: String): Pair<String, String?> = name.indexOf(':').let { if (it < 0) name to null else name.substring(0, it) to name.substring(it + 1) }

    private fun problemsOfBinding(where: String, name: String?, sectionScope: String): List<String> {
        val binding = name?.let { bindings[it] } as? JsonObject ?: return listOf("$where: no binding $name")
        val scope = binding["scope"].str()
        return if (!scopeAllows(sectionScope, scope)) listOf("$where: $name is $scope-scoped, used in a $sectionScope section") else emptyList()
    }

    private fun problemsOfProcedureText(where: String, procedure: JsonObject, text: String, sectionScope: String): List<String> {
        val problems = ArrayList<String>()
        if (strayBraces(text)) problems += "$where: a brace outside a placeholder"
        placeholders(text).forEach { p ->
            val (kind, name) = splitKind(p)
            when (kind) {
                "value" -> if (values[name ?: ""] == null) problems += "$where: no standard value $name"
                "field" -> if (list(procedure["fields"]).none { it.jsonObject["id"].str() == name }) problems += "$where: no field $name"
                "bind" -> problems += problemsOfBinding(where, name, sectionScope)
                else -> problems += "$where: placeholder {$p} is not value:, field: or bind:"
            }
        }
        return problems
    }

    /** Every problem with a template, so one run lists them all. */
    private fun templateProblems(file: String, t: JsonObject): List<String> {
        val problems = ArrayList<String>()
        fun say(message: String) {
            problems += "$file: $message"
        }

        if (t["format"].str() != "ezpz-brief-template") say("format is not ezpz-brief-template")
        if (t["id"].str() != file) say("id ${t["id"]} is not its file's name")
        val version = (t["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (version == null || version < 1) say("version is not a whole number from 1")
        listOf("title", "shortTitle", "source", "defaultWho").forEach { if (blank(t[it])) say("$it is missing") }
        val paragraphs = list(t["paragraphs"]).map { it.jsonObject }
        if (paragraphs.isEmpty()) {
            say("no paragraphs")
            return problems
        }

        val sections = ArrayList<JsonObject>()
        paragraphs.forEachIndexed { i, p ->
            if (p["number"].str() == null || p["title"].str() == null) say("paragraph ${i + 1} has no number or title")
            if (p["who"] != null && blank(p["who"])) say("paragraph ${p["number"].str()}: who is empty")
            if (list(p["sections"]).isEmpty()) say("paragraph ${p["number"].str()} has no sections")
            list(p["sections"]).forEach { sections += it.jsonObject }
        }
        val byId = LinkedHashMap<String, JsonObject>()
        sections.forEach { s ->
            val id = s["id"].str() ?: ""
            if (byId.containsKey(id)) say("section $id twice")
            byId[id] = s
        }

        // Roles: every one named, each listing the sections repeated for it, and nothing repeated for a role it does not list.
        val roleMap = t["roles"] as? JsonObject ?: JsonObject(emptyMap())
        if (roleMap.keys.sorted() != roles.sorted()) say("roles are ${roleMap.keys.joinToString(", ")}, not ${roles.joinToString(", ")}")
        roles.forEach { role ->
            list(roleMap[role]).forEach { idElement ->
                val id = idElement.str()
                val s = byId[id]
                if (s == null) say("role $role lists $id, which is no section")
                else if (s["repeat"].str() != role) say("role $role lists $id, which repeats for ${s["repeat"].str() ?: "nothing"}")
            }
        }
        sections.forEach { s ->
            val repeat = s["repeat"].str() ?: return@forEach
            if (repeat == "route") return@forEach
            if (repeat !in roles) say("section ${s["id"].str()} repeats for $repeat, no role")
            else if (list(roleMap[repeat]).none { it.str() == s["id"].str() }) say("section ${s["id"].str()} repeats for $repeat but the role does not list it")
        }

        // Steps: a step's sections are side by side and repeat alike, so one page and one sandtable step hold them.
        val seenSteps = HashMap<String, Pair<Int, String?>>()
        sections.forEachIndexed { i, s ->
            val step = s["step"]
            val next = sections.getOrNull(i + 1)
            val startsGroup = i == 0 || sections[i - 1]["step"] != step
            if (startsGroup && next != null && next["step"] == step && blank(s["stepTitle"])) say("step ${step.str()} has several sections and no stepTitle on its first")
            if (!startsGroup && s["stepTitle"] != null) say("section ${s["id"].str()}: stepTitle only on a step's first section")
            val stepText = step.str()
            if (stepText.isNullOrEmpty()) {
                say("section ${s["id"].str()} has no step")
                return@forEachIndexed
            }
            seenSteps[stepText]?.let { (index, repeat) ->
                if (index != i - 1) say("step $stepText is not side by side (section ${s["id"].str()})")
                if (repeat != s["repeat"].str()) say("step $stepText mixes repeats (section ${s["id"].str()})")
            }
            seenSteps[stepText] = i to s["repeat"].str()
        }

        sections.forEach { s ->
            val where = "section ${s["id"].str()}"
            val scope = scopeOf(s)
            if (blank(s["title"])) say("$where has no title")
            val figure = s["figure"].str()
            if (figure !in figures) say("$where: figure $figure is unknown")
            if (figure == "route" && scope != "route") say("$where: a route figure in a section not repeated per route")
            if (figure == "stop" && scope != "stop") say("$where: a stop figure in a section not repeated per stop")
            list(s["facts"]).forEach { problemsOfBinding(where, it.str(), scope).forEach(::say) }

            val items = LinkedHashMap<String, JsonObject>()
            list(s["items"]).map { it.jsonObject }.forEach { item ->
                val id = item["id"].str() ?: ""
                val at = "$where.$id"
                if (!itemId.matches(id)) say("$at: id is not lower-case letters and dots")
                if (items.containsKey(id)) say("$at twice")
                items[id] = item
                if (blank(item["label"])) say("$at has no label")
                val kind = item["kind"].str()
                if (kind !in kinds) say("$at: kind $kind is unknown")
                if (kind == "choice" && list(item["options"]).size <= 1) say("$at: a choice needs options")
                if (kind != "choice" && item["options"] != null) say("$at: options on a $kind")
                item["standard"]?.let { standard ->
                    if (kind != "procedure") say("$at: a standard on a $kind")
                    val procedure = procedures[standard.str() ?: ""] as? JsonObject
                    if (procedure == null) {
                        say("$at: no standard procedure ${standard.str()}")
                    } else {
                        (listOf(procedure["summary"]) + list(procedure["script"])).forEach { text ->
                            problemsOfProcedureText("$at (${standard.str()})", procedure, text.str() ?: "", scope).forEach(::say)
                        }
                    }
                }
                item["bind"]?.let { bind ->
                    if (kind == "procedure" || kind == "info") say("$at: a $kind cannot be bound")
                    problemsOfBinding(at, bind.str(), scope).forEach(::say)
                }
            }
            if (items.isEmpty()) say("$where has no items")

            val script = list(s["script"])
            if (script.isEmpty()) say("$where has no script")
            script.forEach { element ->
                val sentence = element.str()
                if (sentence == null || sentence.isBlank()) {
                    say("$where: an empty sentence")
                    return@forEach
                }
                if (strayBraces(sentence)) say("$where: a brace outside a placeholder in \"$sentence\"")
                placeholders(sentence).forEach placeholder@{ p ->
                    if (itemId.matches(p)) {
                        if (!items.containsKey(p)) say("$where: {$p} is no item of the section")
                        return@placeholder
                    }
                    val (kind, name) = splitKind(p)
                    when (kind) {
                        "bind" -> problemsOfBinding(where, name, scope).forEach(::say)
                        "const" -> if (constants.none { it["id"].str() == name }) say("$where: no constant $name")
                        "item" -> {
                            val key = name ?: ""
                            val dot = key.lastIndexOf('.')
                            val other = byId[Js.slice(key, 0, dot)]
                            // An item of a repeated section has one value per stop or route, so only its own section may name it.
                            if (other == null || list(other["items"]).none { it.jsonObject["id"].str() == Js.slice(key, dot + 1) }) {
                                say("$where: {$p} names no item")
                            } else if (other["repeat"] != null && other !== s) {
                                say("$where: {$p} names an item of a repeated section")
                            }
                        }
                        else -> say("$where: placeholder {$p} is not an item, bind:, const: or item:")
                    }
                }
            }
            // Every item is said somewhere in its section's script, so nothing filled in is left out of the brief.
            items.forEach { (id, item) ->
                if (item["kind"].str() == "info") return@forEach
                if (script.none { placeholders(it.str() ?: "").contains(id) }) say("$where.$id is in no sentence of the script")
            }
        }
        return problems
    }

    @TestFactory
    fun `each template keeps every rule of the format`(): List<DynamicTest> = templateIds.map { id ->
        DynamicTest.dynamicTest(id) { assertEquals(emptyList<String>(), templateProblems(id, read("$id.json"))) }
    }

    @Test
    fun `every file in contracts-briefs is accounted for, and bundled byte for byte`() {
        val files = directory.listFiles()!!.map { it.name }.filter { it.endsWith(".json") }.sorted()
        assertEquals(BriefFormats.FILES.sorted(), files)
        files.forEach { assertArrayEquals(File(directory, it).readBytes(), BriefFormats.bytes(it), it) }
    }

    @Test
    fun `reads every format it bundles`() {
        BriefFormats.CHOICES.forEach { choice ->
            val format = BriefFormats.format(choice.id)
            assertNotNull(format, choice.id)
            assertEquals(choice.id, format!!.template.id)
            assertEquals(read("${choice.id}.json")["shortTitle"].str(), choice.title)
        }
        assertEquals(null, BriefFormats.format("aopord"))
        assertEquals(BriefFormats.ROLES.toSet(), BriefFormats.ROLE_NAMES.keys)
    }

    @Test
    fun `names every binding with a scope, a type and a description`() {
        val problems = ArrayList<String>()
        bindings.forEach { (name, element) ->
            val b = element.jsonObject
            val scope = b["scope"].str()
            if (!Regex("^[a-z]+(\\.[a-zA-Z]+)?$").matches(name)) problems += "$name: not a dotted lower camel name"
            if (scope !in scopes) problems += "$name: scope $scope"
            if (b["type"].str() !in types) problems += "$name: type ${b["type"].str()}"
            if ((b["description"].str()?.length ?: 0) < 10) problems += "$name: no description"
            if (scope != "brief" && !name.startsWith("$scope.")) problems += "$name: a $scope binding is named $scope.…"
            if (scope == "brief" && Regex("^(stop|route)\\.").containsMatchIn(name)) problems += "$name: named like a ${name.substringBefore('.')} binding"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `gives every constant a label, a band name, and a source that exists`() {
        val problems = ArrayList<String>()
        val ids = HashSet<String>()
        constants.forEach { c ->
            val id = c["id"].str() ?: ""
            if (!ids.add(id)) problems += "$id twice"
            if (blank(c["label"])) problems += "$id: no label"
            val short = c["short"].str()
            if (short == null || short != short.uppercase() || short.length > 12) problems += "$id: short is not upper case, 12 at most"
            val offered = c["offered"] as? JsonPrimitive
            if (offered == null || offered.isString || offered.content !in listOf("true", "false")) problems += "$id: offered is not true or false"
            c["bind"]?.let { bind -> if ((bindings[bind.str() ?: ""] as? JsonObject)?.get("scope").str() != "brief") problems += "$id: bind ${bind.str()} is no brief binding" }
            c["standard"]?.let { standard -> if (values[standard.str() ?: ""] == null) problems += "$id: no standard value ${standard.str()}" }
        }
        assertEquals(emptyList<String>(), problems)
        assertTrue(constants.any { (it["offered"] as JsonPrimitive).content == "true" })
    }

    @Test
    fun `gives every standard a label and every procedure a title, a summary and a script`() {
        assertEquals("ezpz-brief-standards", standards["format"].str())
        val problems = ArrayList<String>()
        values.forEach { (id, element) ->
            val v = element.jsonObject
            val value = v["value"]
            if (value == null || value is JsonNull || value.str() == "") problems += "$id: no value"
            if (blank(v["label"])) problems += "$id: no label"
        }
        procedures.forEach { (id, element) ->
            val p = element.jsonObject
            if (p["title"].str() == null || p["summary"].str() == null) problems += "$id: no title or summary"
            if (list(p["script"]).isEmpty()) problems += "$id: no script"
            val fieldIds = list(p["fields"]).map { it.jsonObject["id"].str() }
            if (fieldIds.toSet().size != fieldIds.size) problems += "$id: a field twice"
            // Brief-scoped text here is checked in full; stop and route bindings are checked where a template uses the procedure.
            (listOf(p["summary"]) + list(p["script"])).forEach { text ->
                (problemsOfProcedureText(id, p, text.str() ?: "", "stop") + problemsOfProcedureText(id, p, text.str() ?: "", "route"))
                    .filter { !Regex("-scoped, used in a").containsMatchIn(it) }
                    .forEach { if (it !in problems) problems += it }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    // -- The checks themselves: a template broken on purpose must be caught, or the test above proves nothing. ------------------------

    /** The amb template as a tree that can be edited. */
    @Suppress("UNCHECKED_CAST")
    private fun ambTree(): MutableMap<String, Any?> = toTree(read("amb.json")) as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun toTree(e: JsonElement): Any? = when (e) {
        is JsonObject -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, toTree(v)) } }
        is JsonArray -> e.map(::toTree).toMutableList()
        else -> e
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJson(value: Any?): JsonElement = when (value) {
        is Map<*, *> -> JsonObject((value as Map<String, Any?>).mapValues { toJson(it.value) })
        is List<*> -> JsonArray(value.map(::toJson))
        is JsonElement -> value
        null -> JsonNull
        else -> error("not JSON: $value")
    }

    @Suppress("UNCHECKED_CAST")
    private fun sectionOf(t: MutableMap<String, Any?>, id: String): MutableMap<String, Any?> =
        (t["paragraphs"] as List<MutableMap<String, Any?>>).flatMap { it["sections"] as List<MutableMap<String, Any?>> }
            .first { (it["id"] as JsonPrimitive).content == id }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.items() = this["items"] as MutableList<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.script() = this["script"] as MutableList<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap() = this as MutableMap<String, Any?>

    private fun broken(edit: (MutableMap<String, Any?>) -> Unit): String {
        val t = ambTree()
        edit(t)
        return templateProblems("amb", toJson(t).jsonObject).joinToString("\n")
    }

    private fun text(s: String) = JsonPrimitive(s)

    @TestFactory
    fun `catches a broken template`(): List<DynamicTest> = listOf<Triple<String, (MutableMap<String, Any?>) -> Unit, Regex>>(
        Triple("a placeholder naming no item", { t -> sectionOf(t, "1").script().add(text("Oops {z}.")) }, Regex("\\{z\\} is no item")),
        Triple("an unknown binding", { t -> sectionOf(t, "1").items()[0].asMap()["bind"] = text("packet.nothing") }, Regex("no binding packet\\.nothing")),
        Triple("a stop binding outside a stop section", { t -> sectionOf(t, "1").script().add(text("{bind:stop.grid}")) }, Regex("stop-scoped, used in a brief section")),
        Triple("a route binding in a stop section", { t -> sectionOf(t, "4.e").script().add(text("{bind:route.name}")) }, Regex("route-scoped, used in a stop section")),
        Triple(
            "an item no sentence says",
            { t -> sectionOf(t, "1").items().add(linkedMapOf<String, Any?>("id" to text("c"), "label" to text("Extra"), "kind" to text("text"))) },
            Regex("1\\.c is in no sentence"),
        ),
        Triple("a role without its section", { t -> t["roles"].asMap()["LZ"] = mutableListOf<Any?>() }, Regex("repeats for LZ but the role does not list it")),
        Triple("a role listing the wrong section", { t -> t["roles"].asMap()["PZ"] = mutableListOf<Any?>(text("4.e")) }, Regex("role PZ lists 4\\.e, which repeats for LZ")),
        Triple("a missing role", { t -> t["roles"].asMap().remove("LAAGER") }, Regex("roles are")),
        Triple("a step split in two", { t -> sectionOf(t, "3")["step"] = text("1") }, Regex("step 1 is not side by side")),
        Triple("an unknown standard", { t -> sectionOf(t, "5.a").items()[0].asMap()["standard"] = text("nothing") }, Regex("no standard procedure nothing")),
        Triple("a stray brace", { t -> sectionOf(t, "1").script().add(text("Half {open")) }, Regex("a brace outside a placeholder")),
        Triple("an item of a repeated section named elsewhere", { t -> sectionOf(t, "7").script().add(text("{item:4.e.ii}")) }, Regex("names an item of a repeated section")),
        Triple("a stop figure in a brief section", { t -> sectionOf(t, "2")["figure"] = text("stop") }, Regex("a stop figure in a section not repeated per stop")),
        Triple("a choice with no options", { t -> sectionOf(t, "1").items()[1].asMap().remove("options") }, Regex("a choice needs options")),
        Triple(
            "a shared step with no title",
            { t ->
                sectionOf(t, "8")["step"] = text("7")
                sectionOf(t, "8")["figure"] = text("overview")
            },
            Regex("step 7 has several sections and no stepTitle"),
        ),
    ).map { (name, edit, expected) ->
        DynamicTest.dynamicTest(name) {
            val problems = broken(edit)
            assertTrue(expected.containsMatchIn(problems), "expected /$expected/ in:\n$problems")
        }
    }
}
