package app.ezpztac.missionpacks

import app.ezpztac.model.JsNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What JavaScript does that the pack ports have to do the same way. The web's `feature/missionPacks` is the reference
 * and JavaScript wrote every fixture in `contracts/fixtures/packs`, so where Kotlin's own answer differs (the order of an
 * object's keys, whether `1` is `1.0`) the port asks here.
 *
 * Kotlin's `null` is JavaScript's `undefined`, a key that is not there; JSON's null is [JsonNull].
 */
internal object Js {
    // The largest array index ECMAScript has: 2^32 - 2.
    private const val MAX_ARRAY_INDEX = 4_294_967_294L
    private const val MAX_ARRAY_INDEX_DIGITS = 10

    // A number as JSON writes one. A literal outside this (kotlinx keeps whatever unquoted text it was given) is not a number.
    private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /**
     * [keys] in the order JavaScript enumerates an object's own keys (`Object.keys`, `JSON.stringify`, a spread): the
     * array indexes first, smallest first, then the rest in the order they were added. A [JsonObject] keeps the order
     * of its text, so `{"b": 1, "2": 2}` lists `b` first where JavaScript lists `2` first; anything that walks an object
     * where the order shows (a diff's operations) walks it in this order.
     */
    fun orderedKeys(keys: Collection<String>): List<String> {
        val indexes = keys.filter(::isArrayIndex)
        if (indexes.isEmpty()) return keys.toList()
        return indexes.sortedBy { it.toLong() } + keys.filterNot(::isArrayIndex)
    }

    /** Whether [key] is an array index: the canonical text of a whole number below 2^32 - 1 (so not `01`, `-0` or `1.0`). */
    fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > MAX_ARRAY_INDEX_DIGITS || key.any { it !in '0'..'9' }) return false
        if (key.length > 1 && key[0] == '0') return false
        return key.toLong() <= MAX_ARRAY_INDEX
    }

    /** The number [value] is if JavaScript's `typeof` would say `number`, else null. */
    fun numberOf(value: JsonElement?): Double? {
        if (value !is JsonPrimitive || value is JsonNull || value.isString) return null
        val text = value.content
        return when {
            JSON_NUMBER.matches(text) -> text.toDouble()
            // What a JsonPrimitive made from a Kotlin Double holds for the numbers JSON has no text for.
            text == "NaN" -> Double.NaN
            text == "Infinity" -> Double.POSITIVE_INFINITY
            text == "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> null
        }
    }

    fun isString(value: JsonElement?): Boolean = value is JsonPrimitive && value.isString

    /**
     * Whether JavaScript takes [value] as true (`if (value)`, `value || other`): everything but a missing key, null, false,
     * 0, -0, NaN and "". An empty object or list is true.
     */
    fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            else -> numberOf(value)?.let { it != 0.0 && !it.isNaN() } ?: (value.content == "true")
        }
    }

    /** An element's id: text or a finite number. The number 1 is not the text "1". */
    fun isId(value: JsonElement?): Boolean = isString(value) || numberOf(value)?.isFinite() == true

    /**
     * JavaScript's `===` on JSON. Numbers are compared by value, so `1` is `1.0` and `-0` is `0` (and NaN is not even
     * itself); text is never a number, so `"1"` is not `1`; an object or a list is only ever itself, as JavaScript
     * compares them by reference.
     */
    fun strictEquals(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a is JsonObject || a is JsonArray || b is JsonObject || b is JsonArray) return a === b
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        val x = a as JsonPrimitive
        val y = b as JsonPrimitive
        if (x.isString || y.isString) return x.isString && y.isString && x.content == y.content
        val numberX = numberOf(x)
        val numberY = numberOf(y)
        if (numberX != null && numberY != null) return numberX == numberY
        if (numberX != null || numberY != null) return false
        return x.content == y.content                                                   // true and false
    }

    /**
     * A key that tells ids apart the way [strictEquals] does, for a map or a set of them: the web's
     * `` `${typeof id}:${id}` ``, so `"1"` and `1` differ and `1.0` and `1` do not.
     */
    fun idKey(id: JsonElement): String {
        if (isString(id)) return "string:" + (id as JsonPrimitive).content
        val number = numberOf(id)
        require(number != null && number.isFinite()) { "An id is text or a finite number, not $id" }
        return "number:" + JsNumber.toText(number)
    }

    // -- Reading a value as JavaScript reads it -------------------------------------------------------------------------

    /** JavaScript's `Number(value)` ([JsNumber.of]): `"0x5A"` and `[90]` are 90, `" "` and `[]` are 0, an object is NaN. */
    fun toNumber(value: JsonElement?): Double = JsNumber.of(value)

    /** `Math.round`: a half goes up, toward positive infinity, so 2.5 is 3 and -1.5 is -1 (Kotlin's `round` goes to even). */
    fun round(value: Double): Double {
        if (!value.isFinite()) return value
        val floor = Math.floor(value)
        return if (value - floor >= 0.5) floor + 1 else floor
    }

    /**
     * `String(value)`, as a template literal writes a value: text as it is, a number as JavaScript writes it (`-0` is `0`, 1e21 is
     * `1e+21`), `null`, `true`, a list's elements joined by commas (null as nothing), `[object Object]`, and `undefined` for a key that
     * is not there.
     */
    fun text(value: JsonElement?): String = when (value) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonObject -> "[object Object]"
        is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else text(it) }
        is JsonPrimitive -> when {
            value.isString -> value.content
            else -> numberOf(value)?.let(JsNumber::toText) ?: value.content                // true and false
        }
    }

    /** What `String.prototype.trim` takes from each end: JavaScript's white space and line terminators. */
    private fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    /**
     * `text.trim()` as JavaScript trims, which is not Kotlin's: the no-break spaces, U+3000 and the byte order mark are taken, a zero-width
     * space is not, and nor are U+001C to U+001F, which Kotlin's `trim` takes.
     */
    fun trim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isSpace(text[start])) start++
        while (end > start && isSpace(text[end - 1])) end--
        return text.substring(start, end)
    }

    /**
     * [text] cut to [max] UTF-16 units, as the web's `.slice(0, max)` counts, except that a character is never split: where the cut
     * would fall between the two halves of a character outside the basic plane (an emoji), the whole character goes. The web keeps
     * its first half, a lone surrogate the server cannot store (AGENTS.md §15); the fixtures mark those cases `webBug`.
     */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (max > 0 && Character.isHighSurrogate(text[max - 1]) && Character.isLowSurrogate(text[max])) max - 1 else max
        return text.substring(0, end)
    }

    /**
     * `value?.[key]` for JSON: an object's own field (never one from JavaScript's prototype), a list's element at an index or its
     * `length`, the character of a text at an index or its `length`; null (undefined) for anything else.
     */
    fun prop(value: JsonElement?, key: String): JsonElement? = when (value) {
        is JsonObject -> value[key]
        is JsonArray -> when {
            key == "length" -> JsonPrimitive(value.size)
            isArrayIndex(key) && key.toLong() < value.size -> value[key.toInt()]
            else -> null
        }
        is JsonPrimitive -> if (!value.isString) {
            null
        } else {
            val text = value.content
            when {
                key == "length" -> JsonPrimitive(text.length)
                isArrayIndex(key) && key.toLong() < text.length -> JsonPrimitive(text[key.toInt()].toString())
                else -> null
            }
        }
        null -> null
    }

    /** `Object.keys(value)`: an object's keys in JavaScript's order, a list's or a text's indexes; none for anything else. */
    fun keys(value: JsonElement?): List<String> = when (value) {
        is JsonObject -> orderedKeys(value.keys)
        is JsonArray -> value.indices.map(Int::toString)
        is JsonPrimitive -> if (value.isString) value.content.indices.map(Int::toString) else emptyList()
        null -> emptyList()
    }

    /** `Object.values(value)`, in [keys]' order. */
    fun values(value: JsonElement?): List<JsonElement> = keys(value).mapNotNull { prop(value, it) }

    /**
     * The fields `{...value}` makes: an object's own fields (array indexes first, as JavaScript orders them), a list's elements and a
     * text's characters keyed by position, and nothing from null, a number or a boolean. A new map, for the caller to add to.
     */
    fun spread(value: JsonElement?): LinkedHashMap<String, JsonElement> {
        val fields = LinkedHashMap<String, JsonElement>()
        keys(value).forEach { key -> prop(value, key)?.let { fields[key] = it } }
        return fields
    }

    /**
     * The key a JavaScript `Map` files [id] under (SameValueZero): `undefined` (Kotlin's null), null, text, a number (1 and 1.0 the
     * same) and a boolean each by value. An object or a list is a key by reference, which two versions of a document never share, so
     * [unique] gives each one a key of its own.
     */
    fun mapKey(id: JsonElement?, unique: () -> String): String = when (id) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonObject, is JsonArray -> "ref:" + unique()
        is JsonPrimitive -> if (id.isString) "string:" + id.content else numberOf(id)?.let { "number:" + JsNumber.toText(it) } ?: ("boolean:" + id.content)
    }

    /**
     * `a === b` between two versions of a document: [strictEquals] on values, but an object or a list is never the same as anything
     * (the web compares versions it built apart, so they share no object; here they may share an instance that no edit touched).
     */
    fun sameValue(a: JsonElement?, b: JsonElement?): Boolean =
        a !is JsonObject && a !is JsonArray && b !is JsonObject && b !is JsonArray && strictEquals(a, b)
}
