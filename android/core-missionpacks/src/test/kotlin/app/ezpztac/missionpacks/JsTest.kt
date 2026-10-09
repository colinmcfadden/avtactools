package app.ezpztac.missionpacks

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What JavaScript does that the ports lean on, each against what JavaScript (Node) answers. */
class JsTest {
    @Test
    fun `keys go in JavaScript's order, array indexes first and smallest first`() {
        // Object.keys(JSON.parse('{"b":1,"10":2,"a":3,"2":4,"01":5,"-1":6,"1.5":7,"4294967294":8,"4294967295":9}'))
        val parsed = Json.parseToJsonElement(
            """{"b":1,"10":2,"a":3,"2":4,"01":5,"-1":6,"1.5":7,"4294967294":8,"4294967295":9}""",
        ).jsonObject
        assertEquals(
            listOf("2", "10", "4294967294", "b", "a", "01", "-1", "1.5", "4294967295"),
            Js.orderedKeys(parsed.keys),
        )
        assertEquals(listOf("z", "y", "x"), Js.orderedKeys(listOf("z", "y", "x")))         // no indexes: as they were added
        assertEquals(listOf("0", "z"), Js.orderedKeys(listOf("z", "0")))
        assertEquals(emptyList<String>(), Js.orderedKeys(emptyList()))
    }

    @Test
    fun `an array index is a whole number's own text, below 2^32 - 1`() {
        listOf("0", "7", "10", "4294967294").forEach { assertTrue(Js.isArrayIndex(it), it) }
        listOf("", "00", "01", "-0", "-1", "+1", "1.0", "1e3", " 1", "4294967295", "99999999999", "x").forEach {
            assertFalse(Js.isArrayIndex(it), it)
        }
    }

    @Test
    fun `strict equality compares numbers by value and never text with a number`() {
        fun same(a: String, b: String) = Js.strictEquals(Json.parseToJsonElement(a), Json.parseToJsonElement(b))
        assertTrue(same("1", "1.0"))                      // 1 === 1.0
        assertTrue(same("1e2", "100"))
        assertTrue(same("-0", "0"))                       // -0 === 0
        assertTrue(same("\"1\"", "\"1\""))
        assertFalse(same("\"1\"", "1"))                   // "1" !== 1
        assertFalse(same("1", "\"1\""))
        assertFalse(same("\"a\"", "\"A\""))
        assertTrue(same("true", "true"))
        assertFalse(same("true", "1"))                    // true !== 1
        assertFalse(same("true", "\"true\""))
        assertTrue(same("null", "null"))
        assertFalse(same("null", "0"))
        assertFalse(same("null", "\"null\""))
        assertFalse(same("{}", "{}"))                     // two objects, however alike
        assertFalse(same("[1]", "[1]"))
        assertFalse(same("{\"id\":1}", "1"))
        // 2^53 and 2^53 + 1 are one double, so one number to JavaScript.
        assertTrue(same("9007199254740992", "9007199254740993"))
    }

    @Test
    fun `undefined is only itself, an object is itself, and NaN is not even itself`() {
        val objectValue = JsonObject(emptyMap())
        val list = JsonArray(emptyList())
        assertTrue(Js.strictEquals(null, null))
        assertFalse(Js.strictEquals(null, JsonNull))
        assertFalse(Js.strictEquals(JsonNull, null))
        assertTrue(Js.strictEquals(objectValue, objectValue))
        assertTrue(Js.strictEquals(list, list))
        val nan = JsonPrimitive(Double.NaN)
        assertFalse(Js.strictEquals(nan, nan))
        assertTrue(Js.strictEquals(JsonPrimitive(Double.POSITIVE_INFINITY), JsonPrimitive(Double.POSITIVE_INFINITY)))
        assertTrue(Js.strictEquals(JsonPrimitive(1), JsonPrimitive(1.0)))
        assertTrue(Js.strictEquals(JsonPrimitive(-0.0), JsonPrimitive(0)))
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)                                       // JsonUnquotedLiteral
    fun `a number is what JSON writes as one`() {
        assertEquals(1.0, Js.numberOf(Json.parseToJsonElement("1")))
        assertEquals(-12.5, Js.numberOf(Json.parseToJsonElement("-1.25e1")))
        assertEquals(Double.POSITIVE_INFINITY, Js.numberOf(Json.parseToJsonElement("1e400")))    // JSON.parse("1e400") is Infinity
        assertNull(Js.numberOf(Json.parseToJsonElement("\"1\"")))
        assertNull(Js.numberOf(Json.parseToJsonElement("true")))
        assertNull(Js.numberOf(JsonNull))
        assertNull(Js.numberOf(null))
        assertNull(Js.numberOf(Json.parseToJsonElement("[1]")))
        // Text kotlinx would carry unquoted but no JSON writer produces, Java's own suffixes and hex included.
        listOf("1d", "1f", "0x10", "01", "+1", ".5", "abc").forEach { assertNull(Js.numberOf(JsonUnquotedLiteral(it)), it) }
    }

    @Test
    fun `an id is text or a finite number`() {
        assertTrue(Js.isId(JsonPrimitive("h-1")))
        assertTrue(Js.isId(JsonPrimitive("")))
        assertTrue(Js.isId(JsonPrimitive(7)))
        assertTrue(Js.isId(JsonPrimitive(-0.5)))
        assertFalse(Js.isId(Json.parseToJsonElement("1e400")))
        assertFalse(Js.isId(JsonPrimitive(Double.NaN)))
        assertFalse(Js.isId(JsonPrimitive(true)))
        assertFalse(Js.isId(JsonNull))
        assertFalse(Js.isId(null))
        assertFalse(Js.isId(JsonObject(emptyMap())))
    }

    @Test
    fun `an id's key keeps text and numbers apart, and one number one key`() {
        val ids = Json.parseToJsonElement("""["1", 1, 1.0, 1e0, -0, 0, 0.1, 1e21, "number:1"]""").jsonArray
        // `${typeof id}:${id}` for each, as the web keys them.
        assertEquals(
            listOf("string:1", "number:1", "number:1", "number:1", "number:0", "number:0", "number:0.1", "number:1e+21", "string:number:1"),
            ids.map { Js.idKey(it) },
        )
        assertThrows<IllegalArgumentException> { Js.idKey(JsonPrimitive(true)) }
        assertThrows<IllegalArgumentException> { Js.idKey(JsonNull) }
        assertThrows<IllegalArgumentException> { Js.idKey(JsonObject(emptyMap())) }
    }

    @Test
    fun `truthiness is JavaScript's`() {
        // [false, 0, -0, 0.0, "", null, true, 1, -1, 0.5, " ", "0", "false", [], {}].map((x) => !!x), then undefined and NaN.
        val values = Json.parseToJsonElement("""[false, 0, -0, 0.0, "", null, true, 1, -1, 0.5, " ", "0", "false", [], {}]""").jsonArray
        assertEquals(
            listOf(false, false, false, false, false, false, true, true, true, true, true, true, true, true, true),
            values.map { Js.truthy(it) },
        )
        assertFalse(Js.truthy(null))
        assertFalse(Js.truthy(JsonPrimitive(Double.NaN)))
    }
}
