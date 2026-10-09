package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict JSON equality for holding a port to a fixture JavaScript wrote: null is not absent (an operation's `"after":
 * null` and a patch's `{field: null}` mean something), text is not a number, numbers as written, and every object's keys
 * in the order JavaScript would enumerate them. `JsonCompare` in core-testing calls null and absent the same, which is
 * right for a plan and wrong here.
 */
internal object StrictJson {
    /** Every place [actual] differs from [expected], as `path: reason`. Empty means they agree. */
    fun differences(expected: JsonElement, actual: JsonElement, path: String = "$"): List<String> = when {
        expected is JsonObject && actual is JsonObject -> {
            val order = Js.orderedKeys(actual.keys)
            if (order != expected.keys.toList()) listOf("$path: keys $order, expected ${expected.keys.toList()}")
            else expected.keys.flatMap { differences(expected.getValue(it), actual.getValue(it), "$path.$it") }
        }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) listOf("$path: ${actual.size} elements, expected ${expected.size}")
            else expected.indices.flatMap { differences(expected[it], actual[it], "$path[$it]") }
        expected is JsonNull || actual is JsonNull -> if (expected is JsonNull && actual is JsonNull) emptyList() else listOf("$path: $actual, expected $expected")
        expected is JsonPrimitive && actual is JsonPrimitive ->
            if (expected.isString == actual.isString && expected.content == actual.content) emptyList() else listOf("$path: $actual, expected $expected")
        else -> listOf("$path: $actual, expected $expected")
    }

    /**
     * Every place [actual] differs from [expected] as JSON values, which is how shared.json, describe.json and edit.json are compared:
     * key order does not matter, null is still not absent, text is not a number, and a number is its value (`34` is `34.0`, which is
     * what a Kotlin double writes).
     */
    fun valueDifferences(expected: JsonElement?, actual: JsonElement?, path: String = "$"): List<String> = when {
        expected == null || actual == null -> if (expected == null && actual == null) emptyList() else listOf("$path: $actual, expected $expected")
        expected is JsonObject && actual is JsonObject ->
            if (expected.keys != actual.keys) {
                listOf("$path: keys ${actual.keys.sorted()}, expected ${expected.keys.sorted()}")
            } else {
                expected.keys.flatMap { valueDifferences(expected.getValue(it), actual.getValue(it), "$path.$it") }
            }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) listOf("$path: ${actual.size} elements, expected ${expected.size}")
            else expected.indices.flatMap { valueDifferences(expected[it], actual[it], "$path[$it]") }
        expected is JsonNull || actual is JsonNull -> if (expected is JsonNull && actual is JsonNull) emptyList() else listOf("$path: $actual, expected $expected")
        expected is JsonPrimitive && actual is JsonPrimitive -> {
            val numbers = Js.numberOf(expected) to Js.numberOf(actual)
            val same = when {
                numbers.first != null || numbers.second != null -> numbers.first == numbers.second
                else -> expected.isString == actual.isString && expected.content == actual.content
            }
            if (same) emptyList() else listOf("$path: $actual, expected $expected")
        }
        else -> listOf("$path: $actual, expected $expected")
    }
}
