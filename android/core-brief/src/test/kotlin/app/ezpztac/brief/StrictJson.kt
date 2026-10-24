package app.ezpztac.brief

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict JSON equality for holding the port to a fixture JavaScript wrote: null is not absent, text is not a number, a number is the text
 * JavaScript writes for it (so `1` is not `1.0`), and every object's keys in the order JavaScript enumerates them. `JsonCompare` in
 * core-testing calls null and absent the same and numbers equal within a tolerance, which is right for a plan and wrong for a model the
 * server prints.
 */
internal object StrictJson {
    /** Every place [actual] differs from [expected], as `path: reason`. Empty means they agree. */
    fun differences(expected: JsonElement, actual: JsonElement, path: String = "$"): List<String> = when {
        expected is JsonObject && actual is JsonObject -> {
            // The order the model is written in, as it is: the server reads JSON text, so an object built in another order than JavaScript's
            // would print differently even where its keys are all there.
            val order = actual.keys.toList()
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
}
