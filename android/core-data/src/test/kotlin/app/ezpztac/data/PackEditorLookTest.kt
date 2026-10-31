package app.ezpztac.data

import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the editors' join to a pack looks again: never for people pointing, which changes the pack's state many times a second. */
class PackEditorLookTest {
    private val session = PackSession(
        1, JsonObject(mapOf("uuid" to JsonPrimitive("p"))), JsonArray(emptyList()), emptyMap(), emptyMap(), emptyList(), 3, emptyList(), emptyList(), false, null, false, emptyMap(),
    )
    private val state = PackState("p", PackStatus.LIVE, session, emptyList(), emptyList(), null)
    private fun pointing(at: Double) = JsonObject(mapOf("user_id" to JsonPrimitive(2), "focus" to JsonObject(mapOf("at" to JsonArray(listOf(JsonPrimitive(at), JsonPrimitive(-84.0)))))))

    @Test
    fun `a presence frame is no reason to look, a change to the pack is`() {
        assertTrue(sameForALook(state, state.copy(people = listOf(pointing(34.7)))))
        assertTrue(sameForALook(state.copy(people = listOf(pointing(34.7))), state.copy(people = listOf(pointing(34.8)))))
        assertFalse(sameForALook(state, state.copy(session = session.copy(seq = 4))))                       // its events
        assertFalse(sameForALook(state, state.copy(items = ArrayList())))                                    // its items, by instance
        assertFalse(sameForALook(state, state.copy(status = PackStatus.PAUSED)))
        assertFalse(sameForALook(state, state.copy(uuid = "q")))
        assertFalse(sameForALook(state, null))
        assertTrue(sameForALook(null, null))
    }
}
