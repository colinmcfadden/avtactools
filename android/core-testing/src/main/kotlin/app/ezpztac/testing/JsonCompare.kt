package app.ezpztac.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max

/**
 * Structural comparison of JSON with a numeric tolerance, for holding a Kotlin
 * result to a fixture the web produced.
 *
 * An absent key and an explicit `null` are the same here: a JavaScript object
 * leaves a key out where Kotlin's serializer writes `null`, and neither is a
 * disagreement about the plan.
 */
public object JsonCompare {
    /** Every place [actual] differs from [expected], as `path: reason`. Empty means they agree. */
    public fun differences(
        expected: JsonElement,
        actual: JsonElement,
        tolerance: Double = 1e-9,
        path: String = "$",
    ): List<String> = when {
        expected is JsonObject && actual is JsonObject -> (expected.keys + actual.keys).sorted().flatMap { key ->
            differences(expected[key] ?: JsonNull, actual[key] ?: JsonNull, tolerance, "$path.$key")
        }
        expected is JsonObject && actual is JsonNull -> listOf("$path: expected an object, got null")
        expected is JsonArray && actual is JsonArray -> {
            if (expected.size != actual.size) listOf("$path: expected ${expected.size} items, got ${actual.size}")
            else expected.indices.flatMap { differences(expected[it], actual[it], tolerance, "$path[$it]") }
        }
        expected is JsonNull && actual is JsonNull -> emptyList()
        expected is JsonPrimitive && actual is JsonPrimitive -> primitive(expected, actual, tolerance, path)
        else -> listOf("$path: expected ${describe(expected)}, got ${describe(actual)}")
    }

    private fun primitive(expected: JsonPrimitive, actual: JsonPrimitive, tolerance: Double, path: String): List<String> {
        if (expected is JsonNull || actual is JsonNull) {
            return listOf("$path: expected ${describe(expected)}, got ${describe(actual)}")
        }
        if (!expected.isString && !actual.isString) {
            val e = expected.content.toDoubleOrNull()
            val a = actual.content.toDoubleOrNull()
            if (e != null && a != null) {
                return if (abs(e - a) <= tolerance * max(1.0, abs(e))) emptyList()
                else listOf("$path: expected $e, got $a")
            }
        }
        return if (expected.content == actual.content && expected.isString == actual.isString) emptyList()
        else listOf("$path: expected ${describe(expected)}, got ${describe(actual)}")
    }

    private fun describe(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "\"${element.content}\"" else element.content
        is JsonArray -> "an array"
        is JsonObject -> "an object"
    }
}
