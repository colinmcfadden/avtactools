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

    @Test
    fun `a value is read as a number as JavaScript's Number reads it`() {
        // ["0x5A", "0o17", "0b11", " ", "", "\u00A0 42 \uFEFF", "1e2", "+90", ".5", "5.", "Infinity", "-Infinity", "-0x5A", "1_000",
        //  "270°", "\u200B90", "0x", "0o19", "abc"].map(Number), then true, false, null, [], [7], [[7]], ["8"], [1, 2], [null], {}.
        val texts = listOf(
            "0x5A" to 90.0, "0o17" to 15.0, "0b11" to 3.0, "0XFF" to 255.0, " " to 0.0, "" to 0.0, "\u00A0 42 \uFEFF" to 42.0,
            "1e2" to 100.0, "+90" to 90.0, ".5" to 0.5, "5." to 5.0, "Infinity" to Double.POSITIVE_INFINITY,
            "-Infinity" to Double.NEGATIVE_INFINITY, "\u2028\t7\n" to 7.0,
        )
        texts.forEach { (text, number) -> assertEquals(number, Js.toNumber(JsonPrimitive(text)), "Number(${JsonPrimitive(text)})") }
        listOf("-0x5A", "1_000", "270°", "\u200B90", "\u001C90", "0x", "0o19", "abc", ".", "e5", "infinity", "1e", "0x1p3").forEach {
            assertTrue(Js.toNumber(JsonPrimitive(it)).isNaN(), "Number(${JsonPrimitive(it)}) is NaN")
        }
        fun of(text: String) = Js.toNumber(Json.parseToJsonElement(text))
        assertEquals(1.0, of("true"))
        assertEquals(0.0, of("false"))
        assertEquals(0.0, of("null"))
        assertEquals(0.0, of("[]"))
        assertEquals(7.0, of("[7]"))
        assertEquals(7.0, of("[[7]]"))
        assertEquals(8.0, of("[\"8\"]"))
        assertEquals(0.0, of("[null]"))
        assertTrue(of("[1, 2]").isNaN())
        assertTrue(of("{}").isNaN())
        assertTrue(Js.toNumber(null).isNaN())                                            // undefined
        assertEquals(-12.5, of("-1.25e1"))
        // Past 2^53 the digits are rounded to the nearest double, as JavaScript reads them.
        assertEquals(9.007199254740992E15, Js.toNumber(JsonPrimitive("0x20000000000001")))
    }

    @Test
    fun `Math round takes a half up, toward positive infinity`() {
        // [44.5, 359.5, -0.5, -1.5, 2.5, 0.49999999999999994, -2.6, 4503599627370497].map(Math.round)
        listOf(44.5 to 45.0, 359.5 to 360.0, -0.5 to 0.0, -1.5 to -1.0, 2.5 to 3.0, 0.49999999999999994 to 0.0, -2.6 to -3.0, 4503599627370497.0 to 4503599627370497.0)
            .forEach { (value, rounded) -> assertEquals(rounded, Js.round(value), "Math.round($value)") }
        assertTrue(Js.round(Double.NaN).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Js.round(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a value is written as a template literal writes it`() {
        fun text(json: String) = Js.text(Json.parseToJsonElement(json))
        assertEquals("0", Js.text(JsonPrimitive(-0.0)))                                 // `${-0}`
        assertEquals("0", JsonPrimitive(Js.round(-0.4)).let(Js::text))
        assertEquals("1e+21", text("1e21"))
        assertEquals("90", text("90.0"))
        assertEquals("0.1", text("0.1"))
        assertEquals("270°", text("\"270°\""))
        assertEquals("true", text("true"))
        assertEquals("null", text("null"))
        assertEquals("undefined", Js.text(null))
        assertEquals("1,,a,2,3", text("[1, null, \"a\", [2, 3]]"))
        assertEquals("[object Object]", text("{\"a\": 1}"))
        assertEquals("", text("[]"))
    }

    @Test
    fun `text is trimmed as JavaScript trims it`() {
        assertEquals("LZ CROW", Js.trim("\u00A0\uFEFFLZ CROW\u3000\u2028"))
        assertEquals("lz crow", Js.trim("  lz crow\t\n"))
        assertEquals("\u200BLZ", Js.trim("\u200BLZ "))                              // a zero-width space is not a space
        assertEquals("\u001C", Js.trim("\u001C"))                                    // Kotlin's trim takes it; JavaScript's does not
        assertEquals("", Js.trim("\u00A0\uFEFF\u00A0"))
        // Every other space JavaScript takes, the ends of the U+2000 run among them.
        assertEquals("LZ", Js.trim("\u1680\u2000\u200A\u202F\u205FLZ\u2029\u000B\u000C"))
    }

    @Test
    fun `a cut never splits a character`() {
        val heli = "🚁"
        assertEquals("abc", Js.cut("abcdef", 3))
        assertEquals("abcdef", Js.cut("abcdef", 6))
        assertEquals("ab", Js.cut("ab$heli", 3))                                         // the web's slice leaves "ab\uD83D"
        assertEquals("ab$heli", Js.cut("ab${heli}c", 4))
        assertEquals("", Js.cut(heli, 1))
        assertEquals("", Js.cut("abc", 0))
    }

    @Test
    fun `a field is read as JavaScript reads one, own fields only`() {
        val obj = Json.parseToJsonElement("""{"a": 1, "n": null}""")
        assertEquals(JsonPrimitive(1), Js.prop(obj, "a"))
        assertEquals(JsonNull, Js.prop(obj, "n"))
        assertNull(Js.prop(obj, "missing"))
        assertNull(Js.prop(obj, "constructor"))                                           // never through the prototype
        val list = Json.parseToJsonElement("""["x", "y"]""")
        assertEquals(JsonPrimitive("y"), Js.prop(list, "1"))
        assertEquals(JsonPrimitive(2), Js.prop(list, "length"))
        assertNull(Js.prop(list, "2"))
        assertNull(Js.prop(list, "01"))
        assertEquals(JsonPrimitive("b"), Js.prop(JsonPrimitive("ab"), "1"))
        assertNull(Js.prop(JsonPrimitive(5), "0"))
        assertNull(Js.prop(JsonNull, "a"))
        assertNull(Js.prop(null, "a"))
    }

    @Test
    fun `keys, values and a spread are JavaScript's`() {
        val obj = Json.parseToJsonElement("""{"b": 1, "2": 2, "a": 3}""")
        assertEquals(listOf("2", "b", "a"), Js.keys(obj))
        assertEquals(listOf(JsonPrimitive(2), JsonPrimitive(1), JsonPrimitive(3)), Js.values(obj).map { it as JsonPrimitive })
        assertEquals(listOf("0", "1"), Js.keys(Json.parseToJsonElement("[1, 2]")))
        assertEquals(listOf("0", "1"), Js.keys(JsonPrimitive("AB")))
        assertEquals(emptyList<String>(), Js.keys(JsonPrimitive(5)))
        // {...["a", "b"]}, {..."AB"}, {...null}, {...5}
        assertEquals(mapOf("0" to JsonPrimitive("a"), "1" to JsonPrimitive("b")), Js.spread(Json.parseToJsonElement("""["a", "b"]""")))
        assertEquals(mapOf("0" to JsonPrimitive("A"), "1" to JsonPrimitive("B")), Js.spread(JsonPrimitive("AB")))
        assertTrue(Js.spread(JsonNull).isEmpty())
        assertTrue(Js.spread(JsonPrimitive(5)).isEmpty())
    }

    @Test
    fun `a Map's key is SameValueZero, and an object is only ever itself`() {
        var n = 0
        val unique = { (n++).toString() }
        fun key(text: String) = Js.mapKey(Json.parseToJsonElement(text), unique)
        assertEquals(key("1"), key("1.0"))
        assertEquals(key("-0"), key("0"))
        assertTrue(key("1") != key("\"1\""))
        assertTrue(key("null") != Js.mapKey(null, unique))                               // null is not undefined
        assertTrue(key("true") != key("\"true\""))
        assertTrue(key("{}") != key("{}"))
        assertTrue(Js.sameValue(JsonPrimitive(1), JsonPrimitive(1.0)))
        val shared = Json.parseToJsonElement("{}")
        assertFalse(Js.sameValue(shared, shared))
        assertTrue(Js.sameValue(null, null))
        assertFalse(Js.sameValue(null, JsonNull))
    }
}
