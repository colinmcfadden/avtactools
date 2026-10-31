package app.ezpztac.workspace

import app.ezpztac.model.RoutePoint

/**
 * What a point of a route is to a crew, as its bar's chips offer it (the owner's requests of 2026-10-21 and -22): a checkpoint, the SP, the RP, the target, or a point
 * that only bends the line. AMPS knows three types of named point (turn, IP, target), and the SP and the RP are both IP points, told apart by their names (`.SP`
 * and `.RP`, what the web's attack profile calls them: `SketchOps.autoDesignation`). The web's own menu offers the IP type once, as "RP / IP" (AGENTS.md §15).
 */
enum class PointRole(
    /** Its word, on its chip (a shaping point's chip says *Shaping*), and to a screen reader. */
    val label: String,
    /** What it is, said with its word to a screen reader. */
    val meaning: String,
    /** Its AMPS type; none for a shaping point. */
    val ptType: String?,
    /** The name it gives a point ([PointRoles.nameFor]); none for a shaping point, which has no name. */
    val standardName: String?,
) {
    CHECKPOINT("Checkpoint", "A turn point", "turn", ".CP"),
    SP("SP", "Start point", "ip", ".SP"),
    RP("RP", "Release point", "ip", ".RP"),
    TARGET("TGT", "Target: the LZ or PZ", "target", ".TGT"),
    SHAPING("Shaping point", "Only bends the line", null, null),
}

/** What a point of a route is, and the name it has once made something else. */
object PointRoles {
    /**
     * What a point with [kind], [ptType] and [name] is: a shaping point, a target, a checkpoint (a turn point, or a type this version does not know), or an IP point
     * that is the SP or the RP by its name. An IP point named neither (`.IP`, a place's name) is neither: null.
     */
    fun roleOf(kind: String?, ptType: String?, name: String?): PointRole? = when {
        kind != RoutePoint.KIND_AMPS -> PointRole.SHAPING
        ptType == "target" -> PointRole.TARGET
        ptType == "ip" -> when (namedAs(name)) {
            "SP" -> PointRole.SP
            "RP" -> PointRole.RP
            else -> null
        }
        else -> PointRole.CHECKPOINT
    }

    /**
     * The name a point called [name] has once it is made [role]. The SP and the RP take theirs (`.SP`, `.RP`): nothing else tells the two apart. A checkpoint or the
     * target keeps a name of the person's own (a place's, a local point's) and takes its own in place of none, or of a name that only said what the point was
     * (`.SP`, `.RP`, `.TGT`, `.LZ`, `.IP`, `.CP3`...), a checkpoint keeping a checkpoint's (`.CP3` stays). A shaping point has no name.
     */
    fun nameFor(role: PointRole, name: String?): String {
        val standard = role.standardName ?: return ""
        val own = name.orEmpty()
        return when (role) {
            PointRole.SP, PointRole.RP -> standard
            PointRole.CHECKPOINT -> if (own.isBlank() || (saysWhatItIs(own) && !checkpointName(own))) standard else own
            else -> if (own.isBlank() || saysWhatItIs(own)) standard else own
        }
    }

    /** "SP" or "RP" when [name], with one leading dot aside and in capitals, is that, or starts with it and then something that is not a letter (`SP1`, `RP HAWK`). */
    private fun namedAs(name: String?): String? {
        val bare = bare(name)
        return listOf("SP", "RP").firstOrNull { bare.startsWith(it) && bare.getOrNull(it.length)?.isLetter() != true }
    }

    /** A name that only says what the point is: the names the apps give one (`.SP`, `.RP`, `.TGT`, `.LZ`, `.PZ`, `.IP`, `.CP`, `.CP3`, `.NEWPT`), or the SP's or RP's. */
    private fun saysWhatItIs(name: String): Boolean = bare(name) in ROLE_NAMES || checkpointName(name) || namedAs(name) != null

    private fun checkpointName(name: String): Boolean = Regex("CP\\d*").matches(bare(name))

    private fun bare(name: String?): String = name.orEmpty().trim().removePrefix(".").uppercase()

    private val ROLE_NAMES = setOf("SP", "RP", "TGT", "LZ", "PZ", "IP", "CP", "NEWPT")
}
