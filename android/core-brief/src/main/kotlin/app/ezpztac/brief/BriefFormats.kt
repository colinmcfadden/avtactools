package app.ezpztac.brief

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The brief's formats, as data (contracts/briefs, its README is the format): a template per format, the bindings a template may use,
 * the mission constants and the standard procedures. Bundled unchanged from contracts/briefs (the build copies them; a test holds the
 * bundled bytes to the files), as the web bundles its copies (`feature/brief/templates.js`). Read here with types: the files are this
 * repository's own, held to the format's rules by BriefTemplatesTest (the web's briefTemplates.test.js, ported).
 */

/** A format's template: its paragraphs and their sections in order, who briefs each, where each kind of stop is briefed. */
@Serializable
public data class BriefTemplate(
    val format: String,
    val id: String,
    val version: Int,
    val title: String,
    val shortTitle: String,
    val source: String,
    val defaultWho: String,
    val roles: Map<String, List<String>>,
    val paragraphs: List<BriefParagraph>,
)

@Serializable
public data class BriefParagraph(
    val number: String,
    val title: String,
    val who: String? = null,
    val sections: List<BriefSection>,
)

/** One section of a format: its items, the facts in its page's table, its figure and its script. */
@Serializable
public data class BriefSection(
    val id: String,
    val title: String,
    val who: String? = null,
    /** `route`, or a role (`LZ`, `PZ`...): one step per route, or per stop of that role, in the flow. Null: briefed once. */
    val repeat: String? = null,
    /** Side-by-side sections that share a step (and a repeat) are one page and one sandtable step. */
    val step: String? = null,
    val stepTitle: String? = null,
    /** `overview`, `departure`, `route`, `stop`, `rally` or `none`. */
    val figure: String? = null,
    val facts: List<String>? = null,
    val items: List<BriefItem>,
    val script: List<String>,
)

/** One item a section asks for: filled from the plan (`bind`), a standard procedure (`standard`), or typed. */
@Serializable
public data class BriefItem(
    val id: String,
    val label: String,
    /** `text`, `note`, `time`, `choice`, `procedure`, `list` or `info`. */
    val kind: String,
    val bind: String? = null,
    val standard: String? = null,
    val options: List<String>? = null,
)

/** A value the compiler can find in the plan, and where it may be used (`brief`, `stop` or `route`). */
@Serializable
public data class BriefBinding(
    val scope: String,
    val type: String,
    val label: String? = null,
    val description: String = "",
)

/** A mission constant a brief offers (the band on every page, the box on the sandtable). */
@Serializable
public data class BriefConstant(
    val id: String,
    val label: String,
    val short: String,
    val unit: String? = null,
    val bind: String? = null,
    val standard: String? = null,
    val offered: Boolean,
)

/** A standard number a procedure or a constant uses: a number or text, as the file gives it. */
@Serializable
public data class StandardValue(val value: JsonElement, val unit: String? = null, val label: String)

@Serializable
public data class ProcedureField(val id: String, val label: String)

/** What a contingency's *Standard* applies: its script, with the person's own fields where it has any. */
@Serializable
public data class StandardProcedure(
    val title: String,
    val summary: String,
    val script: List<String>,
    val fields: List<ProcedureField>? = null,
)

@Serializable
public data class BriefStandards(
    val values: Map<String, StandardValue>,
    val procedures: Map<String, StandardProcedure>,
)

/** Everything [CompileBrief] needs to know about a format: the template, the bindings it may use, the constants and the standards. */
public data class BriefFormat(
    val template: BriefTemplate,
    val bindings: Map<String, BriefBinding>,
    val constants: List<BriefConstant>,
    val standards: BriefStandards,
)

/** The bundled formats (`briefFormat(id)` and `BRIEF_FORMATS` in the web's `templates.js`). */
public object BriefFormats {
    /** Where the build puts contracts/briefs on the classpath. */
    public const val RESOURCE_DIR: String = "/app/ezpztac/brief/formats/"

    /** The files bundled, contracts/briefs's `*.json`. */
    public val FILES: List<String> = listOf("amb.json", "hasty.json", "bindings.json", "constants.json", "standards.json")

    /** The stop roles, in the order offered. */
    public val ROLES: List<String> = listOf("PZ", "LZ", "FARP", "HA", "LAAGER", "FALSE_LZ")

    /** What a role is called in a sentence. */
    public val ROLE_NAMES: Map<String, String> = linkedMapOf(
        "PZ" to "PZ", "LZ" to "LZ", "FARP" to "FARP", "HA" to "holding area", "LAAGER" to "laager", "FALSE_LZ" to "false insertion LZ",
    )

    /** A format a person may choose when making a brief: its id and title. */
    public data class Choice(val id: String, val title: String)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BindingsFile(val bindings: Map<String, BriefBinding>)

    @Serializable
    private data class ConstantsFile(val constants: List<BriefConstant>)

    /** A bundled file's bytes, as contracts/briefs has them. */
    public fun bytes(name: String): ByteArray {
        val stream = BriefFormats::class.java.getResourceAsStream(RESOURCE_DIR + name)
            ?: error("The brief format $name is not bundled")
        return stream.use { it.readBytes() }
    }

    private fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    private val templates: Map<String, BriefTemplate> by lazy {
        listOf("amb", "hasty").associateWith { json.decodeFromString(BriefTemplate.serializer(), text("$it.json")) }
    }
    private val bindings: Map<String, BriefBinding> by lazy { json.decodeFromString(BindingsFile.serializer(), text("bindings.json")).bindings }
    private val constants: List<BriefConstant> by lazy { json.decodeFromString(ConstantsFile.serializer(), text("constants.json")).constants }
    private val standards: BriefStandards by lazy { json.decodeFromString(BriefStandards.serializer(), text("standards.json")) }

    /** The formats a person may choose, in the order offered. */
    public val CHOICES: List<Choice> by lazy { listOf("amb", "hasty").map { Choice(it, templates.getValue(it).shortTitle) } }

    /** The format [id] (`amb` or `hasty`), or null for one this version does not have. */
    public fun format(id: String): BriefFormat? {
        val template = templates[id] ?: return null
        return BriefFormat(template, bindings, constants, standards)
    }
}
