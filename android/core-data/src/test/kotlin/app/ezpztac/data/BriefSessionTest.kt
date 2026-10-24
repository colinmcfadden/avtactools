package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.SAM
import app.ezpztac.brief.BriefFormat
import app.ezpztac.brief.CompileBrief
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.Instant
import java.time.ZoneId

/**
 * The brief compiled (the web's useBrief): only while it is open, from the document the keeper holds and the plan as it is now, with the weather asked for at the
 * planned times and compiled in. Held to the web's own answer: OP DK's air movement brief, compiled here with the weather of compile.json's first case, is that case's
 * expected model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefSessionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val opdk = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray[0].jsonObject
    private val context = opdk["context"]!!.jsonObject
    private val weather = context["weather"]!!.jsonObject
    private val opened = Instant.parse(context["generatedAt"]!!.jsonPrimitive.content).toEpochMilli()

    /** The server as the brief asks it: the weather the case was compiled with, for the places and times the brief asks about, and a print it keeps. */
    private inner class FakeApi : BriefApi {
        val asked = mutableListOf<Triple<Double, Double, String?>>()
        val printed = mutableListOf<JsonObject>()
        var offline = false

        override suspend fun weatherAt(lat: Double, lon: Double, at: String?): JsonObject? {
            asked += Triple(lat, lon, at)
            if (offline) return null
            return when (Triple(lat, lon, at)) {
                // The NOTAMs come with the departure's report, as the server sends them; the case keeps them apart.
                Triple(33.9151, -84.5163, "2026-10-16T15:56:32.000Z") -> JsonObject(weather["departure"]!!.jsonObject + ("notams" to context["notams"]!!))
                Triple(33.9151, -84.5163, "2026-10-16T20:26:28.000Z") -> weather["arrival"]!!.jsonObject
                Triple(34.5501, -84.1102, "2026-10-16T16:25:54.000Z") -> weather["stops"]!!.jsonObject["lz-hawk"]!!.jsonObject
                else -> null
            }
        }

        override suspend fun print(model: JsonObject): ByteArray {
            printed += model
            return byteArrayOf(0x25, 0x50, 0x44, 0x46)
        }
    }

    private class Env(val rig: PackEditorRig, val keeper: BriefKeeper, val api: BriefApi, val inputs: MutableStateFlow<BriefInputs>, val account: MutableStateFlow<Int?>, val session: BriefSession)

    private fun sessionTest(
        clock: Long = opened,
        compile: (BriefFormat, JsonObject, JsonObject, JsonObject) -> JsonObject = CompileBrief::compile,
        block: suspend TestScope.(Env, FakeApi) -> Unit,
    ) = runTest {
        val rig = PackEditorRig(this)
        val keeper = BriefKeeper(rig.library.repository, rig.scheduler, rig.engine, InMemoryLastBrief(), rig.scope)
        val api = FakeApi()
        val inputs = MutableStateFlow(BriefInputs(opdk["plan"]!!.jsonObject, threatCount = 1, pack = null))
        val account = MutableStateFlow<Int?>(SAM.id)
        val session = BriefSession(keeper, inputs, api, rig.scope, rig.dispatcher, account, clock = { clock }, zone = { ZoneId.of("America/New_York") }, compile = compile)
        runCurrent()
        try {
            block(Env(rig, keeper, api, inputs, account, session), api)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `nothing is compiled until the brief is opened, and nothing once it is closed`() = sessionTest { env, _ ->
        assertNull(env.session.state.value)
        env.session.open()
        runCurrent()
        assertNotNull(env.session.state.value)
        val state = env.session.state.value!!
        assertEquals(JsonPrimitive("ezpz-brief-model"), state.model["format"])
        assertFalse("nothing answered yet", state.hasDoc)
        assertEquals(BriefKeeper.newDocument("amb"), state.doc)
        assertEquals("amb", state.formatId)
        assertEquals("5 LZ/PZs, 1 route set, 1 threat", state.summary)
        env.session.close()
        runCurrent()
        assertNull(env.session.state.value)
    }

    @Test
    fun `the brief is the web's, OP DK compiled with the weather at its planned times is compile json's first case`() = sessionTest { env, api ->
        env.keeper.change(null) { opdk["document"]!!.jsonObject }
        env.session.open()
        runCurrent()
        val state = env.session.state.value!!
        assertTrue(state.hasDoc)
        assertEquals(BriefWeather(loading = false, fetchedAt = opened), state.weather)
        // The departure, the arrival and each stop, at the times the routes give them, as the web asks.
        val places = state.model["places"]!!.jsonObject
        assertEquals(2 + places["stops"]!!.jsonArray.size, api.asked.size)
        assertTrue(Triple(33.9151, -84.5163, "2026-10-16T15:56:32.000Z") in api.asked)
        assertTrue("a stop with no time is asked about now", api.asked.any { it.third == null })
        // The generation time is the moment the brief was opened, written as JavaScript's toISOString writes it.
        assertEquals(JsonPrimitive("2026-10-16T18:02:00.000Z"), state.model["generatedAt"])
        val expected = JsonObject(opdk["expected"]!!.jsonObject + ("generatedAt" to JsonPrimitive("2026-10-16T18:02:00.000Z")))
        assertEquals(emptyList<String>(), JsonCompare.differences(expected, state.model).take(20))
    }

    @Test
    fun `the weather is asked for again only when its places or times change, or when asked`() = sessionTest { env, api ->
        env.keeper.change(null) { opdk["document"]!!.jsonObject }
        env.session.open()
        runCurrent()
        val first = api.asked.size
        // An answer changes nothing the weather is asked for.
        env.session.change { doc -> JsonObject(doc + ("answers" to buildJsonObject { put("2.b", buildJsonObject { put("value", "SEIZE OBJ BLUE") }) })) }
        runCurrent()
        assertEquals(first, api.asked.size)
        env.session.refreshWeather()
        runCurrent()
        assertEquals(first * 2, api.asked.size)
        // Closed and opened again on the same plan: what was fetched still stands.
        env.session.close()
        env.session.open()
        runCurrent()
        assertEquals(first * 2, api.asked.size)
        // A route set gone from the plan moves the departure: asked again.
        val plan = opdk["plan"]!!.jsonObject
        env.inputs.value = BriefInputs(JsonObject(plan + ("routeSets" to JsonArray(plan["routeSets"]!!.jsonArray.drop(1)))), 1, null)
        runCurrent()
        assertTrue(api.asked.size > first * 2)
    }

    @Test
    fun `a refresh with no answer at all keeps the weather there was, with its age`() = sessionTest { env, api ->
        env.keeper.change(null) { opdk["document"]!!.jsonObject }
        env.session.open()
        runCurrent()
        val before = env.session.state.value!!
        assertEquals(opened, before.weather.fetchedAt)
        api.offline = true
        env.session.refreshWeather()
        runCurrent()
        val after = env.session.state.value!!
        assertEquals(BriefWeather(loading = false, fetchedAt = opened), after.weather)
        assertEquals(before.model, after.model)
    }

    @Test
    fun `the print carries every strip chart's MGRS grid, worked out for it`() = sessionTest { env, api ->
        env.keeper.change(null) { opdk["document"]!!.jsonObject }
        env.session.open()
        runCurrent()
        val pdf = env.session.print()
        assertEquals(4, pdf!!.size)
        val strips = api.printed.single()["strips"]!!.jsonArray.map { it.jsonObject }
        assertEquals(12, strips.size)
        assertTrue(strips.all { it["grid"]!!.jsonObject["lines"]!!.jsonArray.isNotEmpty() })
        assertTrue(env.session.state.value!!.model["strips"]!!.jsonArray.none { "grid" in it.jsonObject })   // the brief itself is unchanged
    }

    private fun answered(value: String): (JsonObject) -> JsonObject = { doc -> JsonObject(doc + ("answers" to buildJsonObject { put("2.b", buildJsonObject { put("value", value) }) })) }

    @Test
    fun `another account closes the brief, forgets its weather, and starts from nothing, the account before's kept`() = sessionTest { env, api ->
        env.session.open()
        env.session.change(answered("SAM'S"))
        runCurrent()
        assertTrue(env.session.state.value!!.hasDoc)
        val asked = api.asked.size
        env.account.value = COLIN.id
        runCurrent()
        assertFalse(env.session.opened.value)
        assertNull(env.session.state.value)
        env.session.change(answered("WRITTEN AS THE SETUP WENT"))                       // a field written as it leaves: not Colin's
        env.session.open()
        runCurrent()
        assertFalse(env.session.state.value!!.hasDoc)
        assertTrue("asked again for Colin, nothing of Sam's weather kept", api.asked.size > asked)
        val briefs = env.rig.library.repository.records(app.ezpztac.sync.RecordKind.BRIEF)
        assertEquals("SAM'S", briefs.single().data["answers"]!!.jsonObject["2.b"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        env.account.value = null
        runCurrent()
        env.session.change { it }
        assertNull(env.session.state.value)
    }

    @Test
    fun `the weather asked for again is what was asked for by itself, across a change of daylight saving`() =
        sessionTest(clock = Instant.parse("2026-12-01T18:02:00Z").toEpochMilli()) { env, api ->
            env.keeper.change(null) { opdk["document"]!!.jsonObject }
            env.session.open()
            runCurrent()
            val first = api.asked.toList()
            assertTrue(first.isNotEmpty())
            api.asked.clear()
            env.session.refreshWeather()
            runCurrent()
            assertEquals(first, api.asked)
            // ... and nothing asks again once the next change comes.
            api.asked.clear()
            env.session.change(answered("SEIZE OBJ BLUE"))
            runCurrent()
            assertTrue(api.asked.isEmpty())
        }

    @Test
    fun `a brief that cannot be compiled is said, and the app and the brief go on`() {
        var breaks = true
        sessionTest(compile = { format, doc, plan, context -> if (breaks) error("a plan no client writes") else CompileBrief.compile(format, doc, plan, context) }) { env, _ ->
            env.session.open()
            runCurrent()
            assertTrue(env.session.failed.value)
            assertNull(env.session.state.value)
            breaks = false
            env.session.change(answered("AGAIN"))
            runCurrent()
            assertFalse(env.session.failed.value)
            assertNotNull(env.session.state.value)
        }
    }

    @Test
    fun `the print of a brief not open is nothing`() = sessionTest { env, api ->
        assertNull(env.session.print())
        assertTrue(api.printed.isEmpty())
    }
}
