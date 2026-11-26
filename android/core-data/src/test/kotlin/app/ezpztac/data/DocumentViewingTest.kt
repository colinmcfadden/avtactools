package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.network.DocumentFile
import app.ezpztac.network.DocumentSummary
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The document being read follows it (docs/DOCUMENTS.md §8.1): a rename and a new version are shown, and one gone, or whose pack is left, says why. */
class DocumentViewingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pdfA = "a".repeat(64)
    private val pdfB = "b".repeat(64)

    private fun data(pdf: String, pages: Int = 3, type: String = "docx") = buildJsonObject {
        put("version", 1)
        putJsonObject("pdf") { put("sha256", pdf); put("bytes", 100); put("pages", pages) }
        putJsonObject("source") { put("type", type); put("bytes", 100) }
    }

    private class NoServer : DocumentApi, DocumentFileSource {
        override suspend fun upload(file: DocumentFile, clientUuid: String, name: String?): DocumentSummary = error("not here")
        override suspend fun record(id: Int): JsonObject = error("not here")
        override suspend fun rename(id: Int, baseRevision: Int?, name: String): DocumentSummary = error("not here")
        override suspend fun newVersion(id: Int, baseRevision: Int?, file: DocumentFile): DocumentSummary = error("not here")
        override suspend fun delete(id: Int, baseRevision: Int?) = error("not here")
        override suspend fun pdf(at: DocumentAddress): ByteArray = error("not here")
        override suspend fun text(at: DocumentAddress): ByteArray = error("not here")
    }

    private class Pack : OpenPack {
        override val state: StateFlow<PackState?> get() = mutable
        val mutable = MutableStateFlow<PackState?>(null)
        override suspend fun createLz(target: DiagramTarget, name: String?): String? = null
        override suspend fun createRouteSet(name: String?): String? = null
        override suspend fun openItem(itemUuid: String) = false
        override suspend fun closeItem(itemUuid: String) = Unit
        override suspend fun removeItem(itemUuid: String): String? = null
    }

    private val store = InMemorySyncStore()
    private val sync = SyncRepository(store)
    private val server = NoServer()
    private val files by lazy { DocumentFiles(folder.root, server) }
    private val documents by lazy { DocumentRepository(server, sync, store, RecordingScheduler(), files) }
    private val pack = Pack()
    private val viewing by lazy { DocumentViewing(documents, pack, files) }

    private fun session(loaded: Boolean = true) = if (!loaded) null else PackSession(
        1, buildJsonObject { put("uuid", "p"); put("name", "OP DK"); put("status", "active") }, buildJsonArray {},
        emptyMap(), emptyMap(), emptyList(), 10, emptyList(), emptyList(), false, null, false, emptyMap(),
    )

    private fun packWith(vararg items: PackItemView, loaded: Boolean = true) = PackState("p", PackStatus.LIVE, session(loaded), items.toList(), emptyList(), null)

    @Test
    fun `a Library document is shown with its name and what it is, then its rename and its new version, and said gone once deleted`() = runBlocking {
        sync.take(RecordKind.DOCUMENT, "doc-1", 7, 1, "OPORD 24-03", data(pdfA))
        val key = DocumentKey.Library("doc-1")
        val first = viewing.follow(key).first() as DocumentState.Shown
        assertEquals("OPORD 24-03", first.name)
        assertEquals("3 pages · from Word", first.info.detail)
        assertEquals(DocumentAddress.Library(7), first.at)

        sync.take(RecordKind.DOCUMENT, "doc-1", 7, 2, "OPORD (FINAL)", data(pdfB, pages = 4, type = "pdf"))
        val next = viewing.follow(key).first() as DocumentState.Shown
        assertEquals("OPORD (FINAL)", next.name)
        assertEquals(pdfB, next.info.pdf)

        sync.forget(RecordKind.DOCUMENT, "doc-1")
        assertEquals(DocumentState.Gone(DocumentViewing.NOT_IN_LIBRARY), viewing.follow(key).first())
    }

    @Test
    fun `a pack's document waits while the pack opens, is shown once it is held, and is gone once removed or the pack is left`() = runBlocking {
        val key = DocumentKey.Pack("p", "doc-9")
        val item = PackItemView("doc-9", "document", "ANNEX C", data(pdfA, pages = 12, type = "pptx"))
        pack.mutable.value = packWith(item, loaded = false)
        assertEquals(DocumentState.Waiting, viewing.follow(key).first())

        pack.mutable.value = packWith(item)
        val shown = viewing.follow(key).first() as DocumentState.Shown
        assertEquals("ANNEX C", shown.name)
        assertEquals("12 pages · from PowerPoint", shown.info.detail)
        assertEquals(DocumentAddress.Pack("p", "doc-9"), shown.at)

        pack.mutable.value = packWith()
        assertEquals(DocumentState.Gone(DocumentViewing.NOT_IN_PACK), viewing.follow(key).first())
        pack.mutable.value = null
        assertEquals(DocumentState.Gone(DocumentViewing.PACK_CLOSED), viewing.follow(key).first())
    }

    @Test
    fun `an item that is not a document, or whose data names no file this device could keep, is not one to read`() = runBlocking {
        val key = DocumentKey.Pack("p", "doc-9")
        pack.mutable.value = packWith(PackItemView("doc-9", "lz", "LZ HAWK", data(pdfA)))
        assertEquals(DocumentState.Gone(DocumentViewing.NOT_IN_PACK), viewing.follow(key).first())
        pack.mutable.value = packWith(PackItemView("doc-9", "document", "ANNEX C", data("../../session")))
        assertEquals(DocumentState.Gone(DocumentViewing.NOT_IN_PACK), viewing.follow(key).first())
    }

    @Test
    fun `one document is read at a time`() {
        viewing.show(DocumentKey.Library("a"))
        viewing.show(DocumentKey.Pack("p", "b"))
        assertEquals(DocumentKey.Pack("p", "b"), viewing.key.value)
        viewing.close()
        assertEquals(null, viewing.key.value)
    }
}
