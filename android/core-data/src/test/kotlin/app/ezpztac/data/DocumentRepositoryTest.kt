package app.ezpztac.data

import app.ezpztac.network.ApiException
import app.ezpztac.network.DocumentFile
import app.ezpztac.network.DocumentSummary
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RevisionConflictException
import app.ezpztac.sync.FileHash
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Library's documents (docs/DOCUMENTS.md §6): every change made online and held as the server answered it, never queued; refusals in words. */
class DocumentRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pdf = "%PDF-1.4 OPORD".toByteArray()

    /** The server: one record per identity, its revision moved by each change; [fail] answers the next call with it. */
    private inner class Server : DocumentApi, DocumentFileSource {
        val log = mutableListOf<String>()
        var fail: Exception? = null
        var revision = 0
        var name = ""
        var bytes = pdf
        var wholeFails = false

        private fun check() = fail?.let { fail = null; throw it }

        private fun summary(uuid: String) = DocumentSummary(
            id = 7, name = name, pages = 1, bytes = bytes.size.toLong(), sourceType = "pdf", hasText = false, createdAt = "2026-11-22T12:00:00",
            updatedAt = "2026-11-22T12:0$revision:00", clientUuid = uuid, revision = revision,
        )

        override suspend fun upload(file: DocumentFile, clientUuid: String, name: String?): DocumentSummary {
            log += "upload ${file.name} $clientUuid ${name ?: "-"}"; check()
            if (revision == 0) { revision = 1; this.name = name ?: file.name.substringBeforeLast('.'); bytes = file.bytes }
            return summary(clientUuid)
        }

        override suspend fun record(id: Int): JsonObject {
            log += "get $id"
            if (wholeFails) throw NetworkException("offline", null, requestMayHaveBeenSent = false)
            return buildJsonObject {
                put("id", id); put("name", name); put("revision", revision); put("client_uuid", "doc-1")
                putJsonObject("data") {
                    put("version", 1)
                    putJsonObject("pdf") { put("sha256", FileHash.of(bytes)); put("bytes", bytes.size); put("pages", 1) }
                    putJsonObject("source") { put("type", "pdf"); put("bytes", bytes.size) }
                    put("added_at", "2026-11-22T12:00:00")
                    put("from_a_newer_server", true)
                }
            }
        }

        override suspend fun rename(id: Int, baseRevision: Int?, name: String): DocumentSummary {
            log += "rename $id base=$baseRevision $name"; check()
            this.name = name; revision++
            return summary("doc-1")
        }

        override suspend fun newVersion(id: Int, baseRevision: Int?, file: DocumentFile): DocumentSummary {
            log += "version $id base=$baseRevision ${file.name}"; check()
            bytes = file.bytes; revision++
            return summary("doc-1")
        }

        override suspend fun delete(id: Int, baseRevision: Int?) {
            log += "delete $id base=$baseRevision"; check()
        }

        override suspend fun pdf(at: DocumentAddress): ByteArray = bytes.also { log += "pdf $at" }

        override suspend fun text(at: DocumentAddress): ByteArray = error("no text")
    }

    private val server = Server()
    private val store = InMemorySyncStore()
    private val sync = SyncRepository(store)
    private val scheduler = RecordingScheduler()
    private val files by lazy { DocumentFiles(folder.root, server) }
    private val documents by lazy { DocumentRepository(server, sync, store, scheduler, files, newUuid = { "doc-1" }) }

    @Test
    fun `an upload is held at once as the server answered it, its JSON whole, with nothing queued`() = runBlocking {
        val done = documents.upload("OPORD 24-03.pdf", pdf, name = null) as DocumentOutcome.Done
        assertEquals(listOf("upload OPORD 24-03.pdf doc-1 -", "get 7"), server.log)
        val held = done.record!!
        assertEquals("OPORD 24-03", held.name)
        assertEquals(1, held.baseRevision)
        assertEquals(JsonPrimitive(true), held.data["from_a_newer_server"])
        assertFalse(held.dirty)
        assertEquals(0, sync.pending())
        assertEquals(1, scheduler.requested)
    }

    @Test
    fun `an upload whose record cannot be asked for after waits for the pull, and one tried again goes under the same identity`() = runBlocking {
        server.wholeFails = true
        assertNull((documents.upload("A.pdf", pdf, name = "A") as DocumentOutcome.Done).record)
        server.fail = NetworkException("offline", null, requestMayHaveBeenSent = true)
        val refused = documents.upload("A.pdf", pdf, name = "A", uuid = "doc-1") as DocumentOutcome.Refused
        assertEquals(DocumentRepository.OFFLINE, refused.message)
        assertEquals(2, server.log.count { it == "upload A.pdf doc-1 A" })
    }

    @Test
    fun `a file over 25 MB is refused before anything is sent`() = runBlocking {
        val refused = documents.upload("BIG.pdf", ByteArray(25 * 1024 * 1024 + 1), name = null) as DocumentOutcome.Refused
        assertEquals(DocumentRepository.TOO_LARGE, refused.message)
        assertEquals(emptyList<String>(), server.log)
    }

    @Test
    fun `a rename and a new version go on the revision held, and are held as answered`() = runBlocking {
        documents.upload("OPORD.pdf", pdf, name = null)
        documents.rename("doc-1", "  OPORD (FINAL) ")
        assertEquals("rename 7 base=1 OPORD (FINAL)", server.log.last())
        assertEquals("OPORD (FINAL)", sync.record(RecordKind.DOCUMENT, "doc-1")!!.name)
        val next = "%PDF-1.4 CHANGE 1".toByteArray()
        documents.newVersion("doc-1", "change1.pdf", next)
        assertEquals("version 7 base=2 change1.pdf", server.log[server.log.size - 2])
        val held = sync.record(RecordKind.DOCUMENT, "doc-1")!!
        assertEquals(3, held.baseRevision)
        assertEquals(FileHash.of(next), DocumentInfo.of(held.data)!!.pdf)
        assertEquals(0, sync.pending())
    }

    @Test
    fun `a deletion lets the record and its files go at once, and one already gone there is gone here too`() = runBlocking {
        val held = (documents.upload("OPORD.pdf", pdf, name = null) as DocumentOutcome.Done).record!!
        val file = documents.pdf(held)
        assertTrue(file.exists())
        server.fail = ApiException(404, "not_found", "Not found")
        assertTrue(documents.delete("doc-1") is DocumentOutcome.Done)
        assertNull(sync.record(RecordKind.DOCUMENT, "doc-1"))
        assertFalse(file.exists())
    }

    @Test
    fun `refusals are the server's sentence where it wrote one for people, and the app's words otherwise`() {
        assertEquals("That PDF has a password.", DocumentRepository.refusal(ApiException(422, "password_protected", "That PDF has a password."), "x"))
        assertEquals("x", DocumentRepository.refusal(ApiException(500, null, "Traceback (most recent call last)"), "x"))
        assertEquals(DocumentRepository.OFFLINE, DocumentRepository.refusal(NetworkException("reset", null, requestMayHaveBeenSent = true), "x"))
        assertEquals(DocumentRepository.GONE, DocumentRepository.refusal(ApiException(404, "not_found", "Not found"), "x"))
        assertEquals(
            DocumentRepository.CHANGED,
            DocumentRepository.refusal(RevisionConflictException("changed", JsonObject(emptyMap())), "x"),
        )
    }

    @Test
    fun `a document is opened from the device once it has been opened`() = runBlocking {
        val held = (documents.upload("OPORD.pdf", pdf, name = null) as DocumentOutcome.Done).record!!
        documents.pdf(held)
        documents.pdf(held)
        assertEquals(1, server.log.count { it.startsWith("pdf ") })
        assertTrue(files.isKept(DocumentInfo.of(held.data)!!.pdf))
    }

    @Test
    fun `a name is the file's less its extension, as the server names it`() {
        assertEquals("OPORD 24-03", documents.nameFrom("OPORD 24-03.pdf"))
        assertEquals("ANNEX", documents.nameFrom("/sdcard/Download/ANNEX.docx"))
        assertEquals(".hidden", documents.nameFrom(".hidden"))
        assertEquals("DOCUMENT", documents.nameFrom(".pdf".drop(4)))
    }
}
