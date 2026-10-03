package app.ezpztac.model

import app.ezpztac.model.JsValue.truthy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/**
 * What a doghouse says, how a typed value is written into it, and how the two standard doghouses give a diagram's flight data its landing
 * and takeoff headings: the web's `doghouseFields.js` and `useDoghouses.js`, held to `contracts/fixtures/workspace/doghouses.json`.
 *
 * A doghouse is the label box beside the landing zone: a heading, a time (`MM+SS`), a distance and an airspeed, kept as the web writes them
 * (`"270°"`, `"03+20"`, `"3.13km"`, `"60 kts"`), because the document is the one the web opens too.
 */
public object Doghouses {
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { JsValue.string(it) }

    /** `String(value)`, with an absent value as `"undefined"` and null as `"null"`, which is what `parseInt` and `parseFloat` are given. */
    private fun jsString(value: JsonElement?): String = when (value) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonPrimitive -> JsValue.string(value) ?: value.content
        else -> value.toString()
    }

    private fun orZero(value: Double) = if (value.isNaN() || value == 0.0) 0.0 else value

    /** How the box is turned: the leading integer of the stored heading (`"270°"` is 270), or 0. Not wrapped, so `"-90°"` is -90. */
    public fun rotation(doghouse: JsonObject): Double = orZero(JsValue.parseInt(jsString(doghouse["heading"])))

    /** What the label and the four rows of a doghouse show. [id] is null when the doghouse has no label. */
    public data class Display(val id: String?, val heading: String, val minutes: String, val seconds: String, val distance: Double, val airspeed: Double) {
        /** The distance as the box prints it: 3.13 is "3.13", 5.0 is "5". */
        val distanceText: String get() = numberText(distance)

        /** The airspeed as the box prints it. */
        val airspeedText: String get() = numberText(airspeed)
    }

    private fun numberText(value: Double): String = if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    /** What [doghouse] shows with its box turned by [rotation] degrees (the box is turned by [rotation] of the same doghouse). */
    public fun display(doghouse: JsonObject, rotation: Double): Display {
        val timeValue = doghouse["time"]
        val time = (if (truthy(timeValue)) jsString(timeValue) else "00+00").split("+")
        val speedValue = doghouse["airspeed"]
        val airspeed = if (truthy(speedValue)) jsString(speedValue).split(" ")[0] else "90"
        return Display(
            id = text(doghouse["id_val"]),
            heading = integerText(floor(rotation + 0.5)).padStart(3, '0'),
            minutes = time.getOrNull(0)?.ifEmpty { null } ?: "00",
            seconds = time.getOrNull(1)?.ifEmpty { null } ?: "00",
            distance = orZero(JsValue.parseFloat(jsString(doghouse["dist"]))),
            airspeed = JsValue.parseInt(airspeed).let { if (it.isNaN() || it == 0.0) 90.0 else it },
        )
    }

    /** A whole number as JavaScript prints it (`-0` is "0", NaN is "NaN"). */
    private fun integerText(value: Double): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
        value == 0.0 -> "0"
        Math.abs(value) < 1e21 -> java.math.BigDecimal(value).toBigInteger().toString()
        else -> value.toString()
    }

    /** A typed heading as degrees: its leading integer, wrapped **once** into 0..359 (one below -360 stays negative, as JavaScript's `%` does). */
    public fun headingDegrees(typed: String): Double = (orZero(JsValue.parseInt(typed)) + 360) % 360

    /** The stored form of a heading in degrees: three digits and a degree sign. */
    public fun headingText(degrees: Double): String = "${integerText(degrees).padStart(3, '0')}°"

    /**
     * What writing [value] into the field [type] stores on the doghouse: `"id"` the label, `"dist"` kilometres, `"airspeed"` knots, or one of
     * the `"time-m"` / `"time-s"` halves, either of which rewrites the whole time from [minutes] and [seconds] (what both halves say now).
     * The heading is written by [headingText]; any other [type] stores nothing.
     */
    public fun fieldUpdates(type: String, value: String, minutes: String = "", seconds: String = ""): JsonObject = JsonObject(
        when {
            type == "id" -> mapOf("id_val" to JsonPrimitive(value))
            type == "dist" -> mapOf("dist" to JsonPrimitive("${value}km"))
            type == "airspeed" -> mapOf("airspeed" to JsonPrimitive("$value kts"))
            type.startsWith("time") -> mapOf("time" to JsonPrimitive("$minutes+$seconds"))
            else -> emptyMap()
        },
    )

    // -- What a person types ----------------------------------------------------------------------------------
    //
    // The web accepts anything typed into a doghouse's box and stores it (`3.5` becomes "3.5km", whatever it was). A phone asks for what the
    // field is for, so these refuse what cannot be one; what they accept is stored exactly as the web would store it ([fieldUpdates]).

    /** The longest label that fits the triangle. */
    public const val MAX_LABEL: Int = 12

    private val TIME = Regex("""(\d{1,3})\s*[+:]\s*(\d{1,2})""")
    private val DISTANCE = Regex("""\d{1,4}(\.\d{1,3})?""")
    private val AIRSPEED = Regex("""\d{1,3}""")

    /** The label as typed (`[SP1]`, `IP 2`), trimmed; null if it is blank or too long for the triangle. */
    public fun labelPatch(typed: String): JsonObject? = typed.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LABEL }?.let { fieldUpdates("id", it) }

    /** A time typed as minutes and seconds, `3+20`, `03+20` or `3:20`, stored as `03+20`; null for anything else, and for 60 seconds or more. */
    public fun timePatch(typed: String): JsonObject? {
        val match = TIME.matchEntire(typed.trim()) ?: return null
        val seconds = match.groupValues[2].toInt()
        if (seconds > 59) return null
        return fieldUpdates("time-m", "", match.groupValues[1].padStart(2, '0'), seconds.toString().padStart(2, '0'))
    }

    /** A distance in kilometres, `3`, `3.13`, stored as `3.13km`; null for anything else. */
    public fun distancePatch(typed: String): JsonObject? = typed.trim().takeIf { DISTANCE.matches(it) }?.let { fieldUpdates("dist", it) }

    /** An airspeed in knots, `60`, stored as `60 kts`; null for anything else. */
    public fun airspeedPatch(typed: String): JsonObject? = typed.trim().takeIf { AIRSPEED.matches(it) }?.let { fieldUpdates("airspeed", it) }

    private fun isString(value: JsonElement?, expected: String) = value is JsonPrimitive && value.isString && value.content == expected

    /** The landing doghouse: new ones have `role` "landing"; the older ids `dh2` and label `[RP1]` keep saved diagrams working. */
    public fun isLanding(doghouse: JsonObject): Boolean = isString(doghouse["role"], "landing") || isString(doghouse["id"], "dh2") || isString(doghouse["id_val"], "[RP1]")

    /** The takeoff doghouse: `role` "takeoff", or the older id `dh1` or label `[SP1]`. */
    public fun isTakeoff(doghouse: JsonObject): Boolean = isString(doghouse["role"], "takeoff") || isString(doghouse["id"], "dh1") || isString(doghouse["id_val"], "[SP1]")

    /**
     * [flightData] with the landing and takeoff headings the doghouses give (`landing_hdg`, `takeoff_hdg`): the first doghouse that matches
     * wins, a heading it does not have takes the key away, a kind with no doghouse keeps what the flight data had, and with neither doghouse the
     * flight data is returned as it was. What is not a doghouse is skipped.
     */
    public fun flightHeadings(doghouses: List<JsonElement>, flightData: JsonObject): JsonObject {
        val list = doghouses.mapNotNull { it as? JsonObject }
        val landing = list.firstOrNull(::isLanding)
        val takeoff = list.firstOrNull(::isTakeoff)
        if (landing == null && takeoff == null) return flightData
        val out = LinkedHashMap(flightData)
        for ((key, doghouse) in listOf("landing_hdg" to landing, "takeoff_hdg" to takeoff)) {
            if (doghouse == null) continue
            val heading = doghouse["heading"]
            if (heading == null) out.remove(key) else out[key] = heading
        }
        return JsonObject(out)
    }

    /** [diagram] with its flight data brought in line with its doghouses, when they differ from [before]'s (the web does it whenever they change). */
    public fun settle(before: Diagram?, diagram: Diagram): Diagram {
        if (before != null && before.graphics.doghouses == diagram.graphics.doghouses) return diagram
        val flight = flightHeadings(diagram.graphics.doghouses, diagram.flightData)
        return if (flight == diagram.flightData) diagram else diagram.copy(flightData = flight)
    }
}
