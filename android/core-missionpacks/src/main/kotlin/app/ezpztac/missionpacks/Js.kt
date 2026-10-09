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
}
