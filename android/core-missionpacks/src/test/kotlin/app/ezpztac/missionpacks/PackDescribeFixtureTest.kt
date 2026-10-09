package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.name
import app.ezpztac.missionpacks.PackFixtures.text
import app.ezpztac.missionpacks.PackFixtures.webBug
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every case of `contracts/fixtures/packs/describe.json`, which the web wrote: the sentence a client sends with each change for the
 * pack's history, word for word, and the other fixed sentences and the operation a new item is made with.
 *
 * A case marked `webBug` is one where the web's cut at 300 leaves half an emoji, which the server cannot store. The port cuts the whole
 * character instead, so those cases are left out of the replay and held below to the web's sentence less that half.
 */
class PackDescribeFixtureTest {
    private val fixture = Fixtures.load("packs/describe.json")

    private fun cases(section: String): List<JsonObject> = fixture.getValue(section).jsonArray.map { it.jsonObject }

    private val sentences: Map<String, ChangeSentence> = mapOf(
        "lz" to ChangeSentence(PackLz::describeLzChange),
        "routes" to ChangeSentence(PackRoutes::describeRouteChange),
        "points" to ChangeSentence(PackPoints::describePointsChange),
    )

    private fun said(section: String, case: JsonObject): String =
        sentences.getValue(section).describe(case["before"], case["after"], text(case["itemName"]), text(case["actor"]))

    @TestFactory
    fun `each change is written as the web writes it`(): List<DynamicTest> = sentences.keys.flatMap { section ->
        cases(section).filterNot(::webBug).map { case ->
            DynamicTest.dynamicTest("$section: ${name(case)}") {
                assertEquals(case.getValue("sentence").jsonPrimitive.content, said(section, case))
            }
        }
    }

    @Test
    fun `a sentence is cut at 300 without splitting a character, where the web leaves half of one`() {
        var marked = 0
        for (section in sentences.keys) {
            for (case in cases(section)) {
                val sentence = said(section, case)
                assertTrue(sentence.length <= PackLz.MAX_SENTENCE, name(case))
                assertFalse(PackFixtures.hasLoneSurrogate(sentence), name(case))
                if (!webBug(case)) continue
                marked++
                val web = case.getValue("sentence").jsonPrimitive.content
                assertTrue(PackFixtures.hasLoneSurrogate(web), "${name(case)} is the web's lone surrogate")
                assertEquals(web.dropLast(1), sentence, name(case))
            }
        }
        assertEquals(3, marked)
    }

    // -- The other sentences, by the web function that makes them ----------------------------------------------------------------

    private fun summaries(helper: String): List<JsonObject> = cases("summaries").filter { it.getValue("helper").jsonPrimitive.content == helper }

    @TestFactory
    fun `every other sentence is written as the web writes it`(): List<DynamicTest> = cases("summaries").map { case ->
        DynamicTest.dynamicTest(name(case)) { summarise(case) }
    }

    private fun summarise(case: JsonObject) {
        val actor = text(case["actor"])
        when (val helper = case.getValue("helper").jsonPrimitive.content) {
            "renameSummary" -> assertEquals(
                case.getValue("summary").jsonPrimitive.content,
                PackSentences.renameSummary(actor, case.getValue("from").jsonPrimitive.content, case.getValue("to").jsonPrimitive.content),
            )
            "deleteItemOp" -> assertValue(
                case.getValue("op"),
                PackSentences.deleteItemOp(case.getValue("uuid").jsonPrimitive.content, case.getValue("itemName").jsonPrimitive.content, actor),
                name(case),
            )
            "copyFromLibrary" -> {
                // Of what the web posts, the item's prefix and the sentence are the contract; the id after the prefix is the client's.
                val record = case.getValue("record").jsonObject
                val kind = case.getValue("kind").jsonPrimitive.content
                val sent = case.getValue("sent").jsonObject
                assertEquals(sent.getValue("item").jsonPrimitive.content, PackSentences.packItemId(kind, case.getValue("newId").jsonPrimitive.content))
                assertEquals(sent.getValue("summary").jsonPrimitive.content, PackSentences.copySummary(actor, kind, text(record["name"])))
                assertEquals(record.getValue("id"), sent.getValue("source").jsonObject.getValue("id"))
            }
            "actorName" -> {
                val user = case["user"] as? JsonObject
                assertEquals(case.getValue("named").jsonPrimitive.content, PackSentences.actorName(text(user?.get("name")), text(user?.get("email"))))
            }
            "newItemOp" -> assertValue(
                case.getValue("op"),
                PackSentences.newItemOp(
                    kind = case.getValue("kind").jsonPrimitive.content,
                    item = case.getValue("item").jsonPrimitive.content,
                    name = text(case["itemName"]),
                    count = case.getValue("count").jsonPrimitive.int,
                    data = case.getValue("data"),
                    actor = actor,
                ),
                name(case),
            )
            "newPointSetOp" -> {
                var taken = 0
                val made = PackPoints.newPointSetOp(text(case["itemName"]), case["points"], actor) {
                    taken++
                    case.getValue("newId").jsonPrimitive.content
                }
                val expected = case.getValue("made").jsonObject
                val refused = text(expected["refused"])
                if (refused != null) {
                    assertEquals(NewPointSet.Refused(refused), made)
                } else {
                    assertTrue(made is NewPointSet.Made, name(case))
                    assertValue(expected.getValue("op"), (made as NewPointSet.Made).op, name(case))
                }
                assertEquals(case.getValue("idsTaken").jsonPrimitive.int, taken, "ids taken")
            }
            "createSummary" -> assertEquals(
                case.getValue("summary").jsonPrimitive.content,
                PackSentences.createSummary(
                    actor,
                    case.getValue("kind").jsonPrimitive.content,
                    case.getValue("itemName").jsonPrimitive.content,
                    (case["pointCount"] as? JsonPrimitive)?.int,
                ),
            )
            "updateFromOriginalSummary" -> assertEquals(
                case.getValue("summary").jsonPrimitive.content,
                PackSentences.updateFromOriginalSummary(actor, case.getValue("itemName").jsonPrimitive.content),
            )
            else -> throw AssertionError("a helper this port does not know: $helper")
        }
    }

    @Test
    fun `every helper the web has is here, and every case of each is replayed`() {
        val helpers = cases("summaries").map { it.getValue("helper").jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("renameSummary", "deleteItemOp", "copyFromLibrary", "actorName", "newItemOp", "newPointSetOp", "createSummary", "updateFromOriginalSummary"),
            helpers,
        )
        assertEquals(2, summaries("newPointSetOp").count { it.getValue("made").jsonObject["refused"] is JsonPrimitive && text(it.getValue("made").jsonObject["refused"]) != null })
        sentences.keys.forEach { assertTrue(cases(it).size > 20, it) }
    }
}
