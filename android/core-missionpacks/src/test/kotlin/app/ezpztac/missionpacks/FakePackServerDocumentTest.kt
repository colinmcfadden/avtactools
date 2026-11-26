package app.ezpztac.missionpacks

import app.ezpztac.network.ApiException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The fake server keeps a document as `pack_routes.apply_ops` does (docs/DOCUMENTS.md §7): the server makes one from a file it
 * checked, so a client's `item.create` of one is malformed, and a client renames and removes one but never edits what it names.
 */
class FakePackServerDocumentTest {
    private val data = Json.parseToJsonElement(
        """{"version": 1, "pdf": {"sha256": "${"a".repeat(64)}", "bytes": 1000, "pages": 2}, "text": null, "source": {"kind": "pdf"}}""",
    )

    private fun server() = FakePackServer().apply {
        person(1, "Colin M.")
        createPack("p-1", "OP DK", owner = 1)
        // The upload's event, which the server writes itself.
        elsewhere("p-1", 1, op("item.create", "doc-1", "kind" to JsonPrimitive("document"), "name" to JsonPrimitive("ANNEX C"), "data" to data))
    }

    private fun op(type: String, item: String, vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive(type), "item" to JsonPrimitive(item)) + fields)

    private var sent = 0

    private fun batch(vararg ops: JsonObject): JsonObject = JsonObject(
        mapOf("ops" to JsonArray(ops.map { op -> JsonObject(op + ("client_op_id" to JsonPrimitive("c-${++sent}"))) })),
    )

    private suspend fun refused(server: FakePackServer, batch: JsonObject): ApiException =
        runCatching { server.sendOps("p-1", batch, asUser = 1) }.exceptionOrNull() as? ApiException ?: throw AssertionError("not refused")

    @Test
    fun `a client may rename and remove a document`() = runTest {
        val server = server()
        server.sendOps("p-1", batch(op("item.rename", "doc-1", "name" to JsonPrimitive("ANNEX D"))), asUser = 1)
        assertEquals("ANNEX D", server.name("p-1", "doc-1"))
        server.sendOps("p-1", batch(op("item.delete", "doc-1")), asUser = 1)
        assertEquals(null, server.data("p-1", "doc-1"))
    }

    @Test
    fun `a client may not make a document, nor edit what one names`() = runTest {
        val server = server()
        val create = refused(server, batch(op("item.create", "doc-2", "kind" to JsonPrimitive("document"), "name" to JsonPrimitive("X"), "data" to data)))
        assertEquals("bad_kind", create.body?.get("reason")?.jsonPrimitive?.content)
        val rename = op("item.rename", "doc-1", "name" to JsonPrimitive("ANNEX D"))
        val patch = refused(server, batch(rename, op("set", "doc-1", "path" to JsonArray(listOf(JsonPrimitive("version"))), "value" to JsonPrimitive(2))))
        assertEquals(400, patch.status)
        assertEquals("bad_kind", patch.body?.get("reason")?.jsonPrimitive?.content)
        assertEquals(1, patch.body?.get("index")?.jsonPrimitive?.content?.toInt())
        assertEquals("ANNEX C", server.name("p-1", "doc-1"), "the whole batch is refused")
        assertEquals(1, server.data("p-1", "doc-1")?.jsonObject?.get("version")?.jsonPrimitive?.content?.toInt())
    }
}
