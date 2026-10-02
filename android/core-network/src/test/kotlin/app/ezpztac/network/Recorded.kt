package app.ezpztac.network

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse

/** The server's own responses, recorded by `backend/tests/test_network_fixtures.py`, for the tests to decode and replay. */
internal object Recorded {
    class Entry(val name: String, val method: String, val path: String, val status: Int, val headers: Map<String, String>, val body: JsonElement?)

    val all: List<Entry> by lazy {
        Fixtures.load("network/responses.json").getValue("responses").jsonArray.map { e ->
            val o = e.jsonObject
            Entry(
                name = o.getValue("name").jsonPrimitive.content,
                method = o.getValue("method").jsonPrimitive.content,
                path = o.getValue("path").jsonPrimitive.content,
                status = o.getValue("status").jsonPrimitive.content.toInt(),
                headers = (o["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                body = o["body"],
            )
        }
    }

    operator fun get(name: String): Entry = all.singleOrNull { it.name == name } ?: error("No recorded response named \"$name\"")

    /** The recorded body as text, with the placeholders for tokens and the like replaced by [values]. */
    fun text(name: String, values: Map<String, String> = emptyMap()): String {
        var text = (this[name].body ?: JsonPrimitive("")).toString()
        for ((placeholder, value) in values) text = text.replace("\"<$placeholder>\"", "\"$value\"")
        return text
    }

    /** A mock server response replaying the recorded one: its status, the headers it kept and its body. */
    fun mock(name: String, values: Map<String, String> = emptyMap()): MockResponse {
        val entry = this[name]
        val response = MockResponse().setResponseCode(entry.status).setBody(text(name, values))
        entry.headers.forEach { (k, v) -> response.setHeader(k, v) }
        if (entry.body != null) response.setHeader("Content-Type", "application/json")
        return response
    }

    /** The user from a recorded sign-in, for tests that start signed in. */
    fun user(): ApiUser = ApiClient.JSON.decodeFromString(ApiUser.serializer(), (this["me"].body as JsonObject).toString())

    @Suppress("unused")
    fun array(name: String): JsonArray = this[name].body!!.jsonArray
}
