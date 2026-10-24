package app.ezpztac.brief

import app.ezpztac.model.JsNumber
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * What JavaScript does that the brief's port has to do the same way: the web's `compile.js` reads loosely typed JSON (a brief document, a
 * plan's saved LZ/PZs and routes, the weather answers) with `??`, truthiness, `String()` and `===`, and wrote every fixture in
 * `contracts/fixtures/brief`. The same rules as core-missionpacks' `Js`, kept here so this module needs nothing of packs.
 *
 * Kotlin's `null` is JavaScript's `undefined`, a key that is not there; JSON's null is [JsonNull].
 */
internal object Js {
    private const val MAX_ARRAY_INDEX = 4_294_967_294L
    private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** [keys] in the order JavaScript enumerates an object's own keys: the array indexes first, smallest first, then the rest as added. */
    fun orderedKeys(keys: Collection<String>): List<String> {
        val indexes = keys.filter(::isArrayIndex)
        if (indexes.isEmpty()) return keys.toList()
        return indexes.sortedBy { it.toLong() } + keys.filterNot(::isArrayIndex)
    }

    /** Whether [key] is an array index: the canonical text of a whole number below 2^32 - 1. */
    fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > 10 || key.any { it !in '0'..'9' }) return false
        if (key.length > 1 && key[0] == '0') return false
        return key.toLong() <= MAX_ARRAY_INDEX
    }

    /** The number [value] is if JavaScript's `typeof` would say `number`, else null. */
    fun numberOf(value: JsonElement?): Double? {
        if (value !is JsonPrimitive || value is JsonNull || value.isString) return null
        val text = value.content
        return when {
            JSON_NUMBER.matches(text) -> text.toDouble()
            text == "NaN" -> Double.NaN
            text == "Infinity" -> Double.POSITIVE_INFINITY
            text == "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> null
        }
    }

    /** The web's `num(v)`: [value] if it is a finite number, else null. */
    fun num(value: JsonElement?): Double? = numberOf(value)?.takeIf { it.isFinite() }

    fun isString(value: JsonElement?): Boolean = value is JsonPrimitive && value.isString

    /** The text of [value] when it is text, else null (`typeof value === "string"`). */
    fun stringOf(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The web's `isObject`: a JSON object, not null and not a list. */
    fun isObject(value: JsonElement?): Boolean = value is JsonObject

    /** JavaScript truthiness: everything but a missing key, null, false, 0, -0, NaN and "". An empty object or list is true. */
    fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            else -> numberOf(value)?.let { it != 0.0 && !it.isNaN() } ?: (value.content == "true")
        }
    }

    /** `value ?? undefined`: JSON null and a missing key are both nothing. */
    fun present(value: JsonElement?): JsonElement? = value?.takeIf { it !is JsonNull }

    /** The web's `nonBlank(v)`: text that is not blank once trimmed, or any value but null and undefined. */
    fun nonBlank(value: JsonElement?): Boolean = when {
        isString(value) -> trim((value as JsonPrimitive).content).isNotEmpty()
        else -> present(value) != null
    }

    /**
     * JavaScript's `===` on JSON: numbers by value, text never a number, an object or a list only ever itself (compared by reference, so
     * two that look alike are not equal), and a missing key only another missing key.
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
        return x.content == y.content
    }

    /** Whether [value] is the text [expected] (`value === "expected"`). */
    fun isText(value: JsonElement?, expected: String): Boolean = isString(value) && (value as JsonPrimitive).content == expected

    /** `Math.round`: a half goes up, toward positive infinity, so 2.5 is 3 and -2.5 is -2 (Kotlin's `round` goes to even). */
    fun round(value: Double): Double {
        if (!value.isFinite()) return value
        val floor = Math.floor(value)
        return if (value - floor >= 0.5) floor + 1 else floor
    }

    /** `String(value)` as a template literal writes it: a number as JavaScript writes it, a list joined by commas, `[object Object]`. */
    fun text(value: JsonElement?): String = when (value) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonObject -> "[object Object]"
        is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else text(it) }
        is JsonPrimitive -> when {
            value.isString -> value.content
            else -> numberOf(value)?.let(JsNumber::toText) ?: value.content
        }
    }

    /** `String(n)` for a number. */
    fun text(value: Double): String = JsNumber.toText(value)

    /** `list.join(separator)`: each element by [text], null and undefined as nothing. */
    fun join(list: JsonArray, separator: String): String = list.joinToString(separator) { if (it is JsonNull) "" else text(it) }

    private fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    /** `text.trim()` as JavaScript trims: the no-break spaces and the byte order mark are taken, U+001C to U+001F are not. */
    fun trim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isSpace(text[start])) start++
        while (end > start && isSpace(text[end - 1])) end--
        return text.substring(start, end)
    }

    /** `text.slice(start, end)`, a negative bound counting from the end, in UTF-16 units as JavaScript counts. */
    fun slice(text: String, start: Int, end: Int = text.length): String {
        fun clamp(i: Int) = if (i < 0) maxOf(0, text.length + i) else minOf(i, text.length)
        val from = clamp(start)
        val to = clamp(end)
        return if (from >= to) "" else text.substring(from, to)
    }

    /** `value?.key` for JSON: an object's own field, never one from JavaScript's prototype; undefined for anything else. */
    fun prop(value: JsonElement?, key: String): JsonElement? = (value as? JsonObject)?.get(key)

    /** A path of [prop]s, as `value?.a?.b`. */
    fun path(value: JsonElement?, vararg keys: String): JsonElement? = keys.fold(value) { at, key -> prop(at, key) }

    /** `value[index]` of a list, or undefined. */
    fun at(value: JsonElement?, index: Int): JsonElement? = (value as? JsonArray)?.getOrNull(index)

    /**
     * A number as JSON.stringify writes it: JavaScript's digits ([JsNumber.toText]), so 1 is `1` and never `1.0`, and null for NaN and the
     * infinities, which JSON has no text for.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun number(value: Double): JsonElement = if (value.isFinite()) JsonUnquotedLiteral(JsNumber.toText(value)) else JsonNull

    fun number(value: Int): JsonElement = JsonPrimitive(value)

    /** [value] as JSON.stringify writes it, a number by [number]; other values as they are. */
    fun normalized(value: JsonElement): JsonElement {
        val n = numberOf(value) ?: return value
        return number(n)
    }
}
