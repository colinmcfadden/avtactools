package app.ezpztac.network

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Library's document calls (docs/DOCUMENTS.md §6), each against the real server's recorded answer. */
class DocumentCallsTest {
    private val uuid = "5d3c9b1a-8e47-4f26-b0a5-2c6e9d1f7a38"
    private val pdf = DocumentFile("OPORD 24-03.pdf", "%PDF-1.4 hello".toByteArray())

    @Test
    fun `an upload is a form with the file and this device's identity, and says whether it was new`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("document: upload") }
            val first = rig.client.uploadDocument(pdf, uuid, idempotencyKey = "key-d1")
            assertTrue(first.created)
            assertEquals("OPORD 24-03", first.value.name)
            assertEquals(2, first.value.pages)
            val sent = rig.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/api/documents", sent.requestUrl!!.encodedPath)
            assertEquals("key-d1", sent.getHeader("Idempotency-Key"))
            assertEquals(mapOf("client_uuid" to uuid, "file" to "OPORD 24-03.pdf: %PDF-1.4 hello"), formParts(sent))

            rig.serve { Recorded.mock("document: upload again with the same identity") }
            assertFalse(rig.client.uploadDocument(pdf, uuid, name = "OPORD").created)         // a repeat of a lost answer: 200, the first record
            assertEquals(mapOf("client_uuid" to uuid, "name" to "OPORD", "file" to "OPORD 24-03.pdf: %PDF-1.4 hello"), formParts(rig.requests.last()))
        }
    }

    @Test
    fun `an upload waits for the server as long as heavy work does, and holds background calls back meanwhile`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        Rig().use { rig ->
            rig.serve { request ->
                if (request.method == "POST") release.await(10, TimeUnit.SECONDS)
                if (request.method == "POST") Recorded.mock("document: upload") else Recorded.mock("document: list")
            }
            val upload = async { rig.client.uploadDocument(pdf, uuid) }
            withTimeout(5_000) { while (rig.requests.isEmpty()) kotlinx.coroutines.delay(10) }
            // A background call (a sync pull) waits while an upload is converted; an ordinary one does not.
            val background = async { rig.client.execute(ApiClient.Call("GET", "/api/documents", callPriority = CallPriority.BACKGROUND)) }
            assertNull(withTimeoutOrNull(300) { background.await() }, "a background call waits for the upload")
            assertEquals(3, rig.client.listDocuments().size)
            release.countDown()
            assertTrue(withTimeout(5_000) { upload.await() }.created)
            withTimeout(5_000) { background.await() }
        }
    }

    @Test
    fun `what the server would not take is its refusal, in its words and code`() = runBlocking<Unit> {
        Rig().use { rig ->
            for ((name, code) in listOf(
                "document: a file it does not take" to "unsupported_type", "document: a PDF with a password" to "password_protected", "document: no file" to "missing_file",
            )) {
                rig.serve { Recorded.mock(name) }
                val e = assertThrows<ApiException> { rig.client.uploadDocument(pdf, uuid) }
                assertEquals(code, e.code, name)
            }
        }
    }

    @Test
    fun `the list and one document, with the hashes that name its files`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("document: list") }
            assertEquals(listOf("scan", "SYNC MATRIX", "OPORD 24-03"), rig.client.listDocuments().map { it.name })
            rig.serve { Recorded.mock("document: get") }
            val one = rig.client.getDocument(1)
            assertEquals("/api/documents/1", rig.requests.last().requestUrl!!.encodedPath)
            assertEquals(64, one.data.pdf.sha256.length)
            assertEquals(73, one.data.text?.chars)
            assertEquals("pdf", one.data.source.type)
        }
    }

    @Test
    fun `a rename says which revision it was made on, and a stale one carries the server's copy`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("document: rename") }
            val renamed = rig.client.renameDocument(1, baseRevision = 1, name = "OPORD 24-03 (FINAL)", idempotencyKey = "key-d2")
            assertEquals(2, renamed.revision)
            val sent = rig.requests.single()
            assertEquals("PUT", sent.method)
            assertEquals("\"1\"", sent.getHeader("If-Match"))
            assertEquals("key-d2", sent.getHeader("Idempotency-Key"))
            assertEquals("OPORD 24-03 (FINAL)", (ApiClient.JSON.parseToJsonElement(sent.body.readUtf8()) as JsonObject)["name"]!!.jsonPrimitive.content)

            rig.serve { Recorded.mock("document: rename on a stale revision") }
            val conflict = assertThrows<RevisionConflictException> { rig.client.renameDocument(1, baseRevision = 1, name = "X") }
            assertTrue("data" in conflict.server, "the server's copy is the whole record")
        }
    }

    @Test
    fun `a new version is a form with the file, on the revision it was made on`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("document: a new version") }
            val next = rig.client.newDocumentVersion(1, baseRevision = 2, file = DocumentFile("change1.pdf", "%PDF-1.4".toByteArray()), idempotencyKey = "key-d3")
            assertEquals(601L, next.bytes)
            val sent = rig.requests.single()
            assertEquals("/api/documents/1/version", sent.requestUrl!!.encodedPath)
            assertEquals("\"2\"", sent.getHeader("If-Match"))
            assertEquals(mapOf("file" to "change1.pdf: %PDF-1.4"), formParts(sent))
        }
    }

    @Test
    fun `a deletion, and the files`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("document: delete") }
            rig.client.deleteDocument(1, baseRevision = 3, idempotencyKey = "key-d4")
            assertEquals("DELETE", rig.requests.single().method)
            assertEquals("\"3\"", rig.requests.single().getHeader("If-Match"))

            rig.serve { Recorded.mock("document: its PDF") }
            assertTrue(String(rig.client.documentPdf(1), Charsets.ISO_8859_1).startsWith("%PDF-"))
            rig.serve { Recorded.mock("document: its text") }
            assertEquals("OPORD 24-03 (OPERATION DARK KNIGHT)", rig.client.documentText(1).pages.first())
            rig.serve { Recorded.mock("document: a scan's text") }
            assertEquals("no_text", assertThrows<ApiException> { rig.client.documentText(3) }.code)
        }
    }

    /** A multipart body's parts by name: a field's value, or a file's name and its text (`name: text`). */
    private fun formParts(request: RecordedRequest): Map<String, String> {
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue(contentType.startsWith("multipart/form-data"), contentType)
        val boundary = contentType.substringAfter("boundary=")
        val sent = request.body.clone().readUtf8()
        return sent.split("--$boundary").map { it.trim('\r', '\n') }.filter { it.isNotEmpty() && it != "--" }.associate { part ->
            val (head, value) = part.split("\r\n\r\n", limit = 2)
            val name = Regex("name=\"([^\"]*)\"").find(head)!!.groupValues[1]
            val file = Regex("filename=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
            name to (if (file != null) "$file: $value" else value)
        }
    }
}
