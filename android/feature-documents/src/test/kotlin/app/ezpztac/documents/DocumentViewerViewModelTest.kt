package app.ezpztac.documents

import androidx.compose.ui.graphics.ImageBitmap
import app.ezpztac.data.DocumentAddress
import app.ezpztac.data.DocumentApi
import app.ezpztac.data.DocumentFileSource
import app.ezpztac.data.DocumentFiles
import app.ezpztac.data.DocumentKey
import app.ezpztac.data.DocumentRepository
import app.ezpztac.data.DocumentViewing
import app.ezpztac.data.OpenPack
import app.ezpztac.missionpacks.PackState
import app.ezpztac.model.DiagramTarget
import app.ezpztac.network.DocumentFile
import app.ezpztac.network.DocumentSummary
import app.ezpztac.network.NetworkException
import app.ezpztac.sync.FileHash
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The viewer (docs/DOCUMENTS.md §8): the document asked for, its PDF fetched once and opened, a new version opened in its place, one gone closed with why; find in the
 * document over the server's page text, a scan said to have none; and the file a PDF is shared as.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentViewerViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    /** The server's files, by address; [offline] fails the next fetch as no connection would. */
    private class Server : DocumentApi, DocumentFileSource {
        val pdfs = HashMap<DocumentAddress, ByteArray>()
        val texts = HashMap<DocumentAddress, ByteArray>()
        var offline = false
        override suspend fun upload(file: DocumentFile, clientUuid: String, name: String?): DocumentSummary = error("not here")
        override suspend fun record(id: Int): JsonObject = error("not here")
        override suspend fun rename(id: Int, baseRevision: Int?, name: String): DocumentSummary = error("not here")
        override suspend fun newVersion(id: Int, baseRevision: Int?, file: DocumentFile): DocumentSummary = error("not here")
        override suspend fun delete(id: Int, baseRevision: Int?) = error("not here")
        override suspend fun pdf(at: DocumentAddress): ByteArray {
            if (offline) { offline = false; throw NetworkException("no route", null, requestMayHaveBeenSent = false) }
            return pdfs.getValue(at)
        }
        override suspend fun text(at: DocumentAddress): ByteArray = texts.getValue(at)
    }

    private class NoPack : OpenPack {
        override val state: StateFlow<PackState?> = MutableStateFlow(null)
        override suspend fun createLz(target: DiagramTarget, name: String?): String? = null
        override suspend fun createRouteSet(name: String?): String? = null
        override suspend fun openItem(itemUuid: String) = false
        override suspend fun closeItem(itemUuid: String) = Unit
        override suspend fun removeItem(itemUuid: String): String? = null
    }

    private class FakePdf(val file: File, override val pages: Int) : DocumentPdf {
        var closed = false
        override val sizes = List(pages) { PageSize(612, 792) }
        override suspend fun page(index: Int, widthPx: Int): ImageBitmap? = null
        override fun close() { closed = true }
    }

    private inner class Rig(scope: TestScope) {
        val server = Server()
        val store = InMemorySyncStore()
        val sync = SyncRepository(store)
        val files = DocumentFiles(folder.root, server, io = StandardTestDispatcher(scope.testScheduler))
        val documents = DocumentRepository(server, sync, store, RecordingScheduler(), files)
        val viewing = DocumentViewing(documents, NoPack(), files)
        val opened = mutableListOf<FakePdf>()
        val model = DocumentViewerViewModel(viewing, { file -> FakePdf(file, pages = 3).also { opened += it } }, object : DocumentFileWriter {
            override suspend fun write(target: android.net.Uri, source: File) = Unit
        }).also { it.worker = StandardTestDispatcher(scope.testScheduler) }
        val said = mutableListOf<String>()
        val pages = mutableListOf<Int>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { said += it } }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.goTo.collect { pages += it } }
        }

        /** A Library document whose PDF is [pdf] and whose page text, if any, is [text]. */
        suspend fun hold(name: String, pdf: ByteArray, text: String? = null, revision: Int = 1) {
            server.pdfs[DocumentAddress.Library(7)] = pdf
            text?.let { server.texts[DocumentAddress.Library(7)] = it.toByteArray() }
            sync.take(
                RecordKind.DOCUMENT, "doc-1", 7, revision, name,
                buildJsonObject {
                    put("version", 1)
                    putJsonObject("pdf") { put("sha256", FileHash.of(pdf)); put("bytes", pdf.size); put("pages", 3) }
                    if (text != null) putJsonObject("text") { put("sha256", FileHash.of(text.toByteArray())); put("chars", text.length) }
                    putJsonObject("source") { put("type", "docx"); put("bytes", 10) }
                },
            )
        }
    }

    private val first = "%PDF-1.4 OPORD".toByteArray()
    private val second = "%PDF-1.4 OPORD CHANGE 1".toByteArray()

    @Test
    fun `a document asked for is fetched once and opened, and a new version opens in its place`() = runTest(dispatcher) {
        val r = Rig(this)
        r.hold("OPORD 24-03", first)
        r.model.show(DocumentKey.Library("doc-1"))
        advanceUntilIdle()
        val ui = r.model.state.value
        assertTrue(ui.open)
        assertEquals("OPORD 24-03", ui.name)
        assertEquals("3 pages · from Word", ui.detail)
        assertEquals(r.opened.single(), (ui.stage as PdfStage.Ready).pdf)

        r.hold("OPORD (FINAL)", second, revision = 2)
        advanceUntilIdle()
        assertEquals("OPORD (FINAL)", r.model.state.value.name)
        assertEquals(2, r.opened.size)
        assertTrue("the old version is let go", r.opened[0].closed)
        assertEquals(r.opened[1], (r.model.state.value.stage as PdfStage.Ready).pdf)
    }

    @Test
    fun `one deleted while it is read closes, says why, and lets its PDF go`() = runTest(dispatcher) {
        val r = Rig(this)
        r.hold("OPORD", first)
        r.model.show(DocumentKey.Library("doc-1"))
        advanceUntilIdle()
        r.sync.forget(RecordKind.DOCUMENT, "doc-1")
        advanceUntilIdle()
        assertFalse(r.model.state.value.open)
        assertNull(r.viewing.key.value)
        assertEquals(listOf(DocumentViewing.NOT_IN_LIBRARY), r.said)
        assertTrue(r.opened.single().closed)
    }

    @Test
    fun `one not on the device with no connection says so, and is tried again`() = runTest(dispatcher) {
        val r = Rig(this)
        r.hold("OPORD", first)
        r.server.offline = true
        r.model.show(DocumentKey.Library("doc-1"))
        advanceUntilIdle()
        assertEquals(PdfStage.Failed(DocumentViewerViewModel.OFFLINE, retry = true), r.model.state.value.stage)
        r.model.retry()
        advanceUntilIdle()
        assertTrue(r.model.state.value.stage is PdfStage.Ready)
    }

    @Test
    fun `find goes to the first hit's page and steps round, and a scan says it has no text`() = runTest(dispatcher) {
        val r = Rig(this)
        r.hold("OPORD", first, text = """{"version":1,"pages":["LZ HAWK at grid","nothing","Hawk again, and HAWK"],"truncated":false}""")
        r.model.show(DocumentKey.Library("doc-1"))
        advanceUntilIdle()
        r.model.openFind()
        r.model.find("hawk")
        advanceUntilIdle()
        val find = r.model.state.value.find
        assertEquals(3, find.hits.count)
        assertEquals("1 of 3", find.count)
        assertEquals(listOf(0), r.pages)
        r.model.nextHit()
        r.model.nextHit()
        r.model.nextHit()
        assertEquals(listOf(0, 2, 2, 0), r.pages)
        r.model.previousHit()
        assertEquals("3 of 3", r.model.state.value.find.count)

        r.hold("SCAN", second, revision = 2)
        advanceUntilIdle()
        r.model.find("hawk")
        advanceUntilIdle()
        assertTrue(r.model.state.value.find.noText)
        assertEquals("No text to search", r.model.state.value.find.count)
    }

    @Test
    fun `a PDF is shared and saved under its name, with nothing in it a file system trips over`() {
        assertEquals("OPORD 24-03.pdf", DocumentViewerViewModel.fileNameOf("OPORD 24-03"))
        assertEquals("ANNEX C_ FIRES_v2.pdf", DocumentViewerViewModel.fileNameOf("ANNEX C: FIRES/v2"))
        assertEquals("brief.pdf", DocumentViewerViewModel.fileNameOf("brief.pdf"))
        assertEquals("Document.pdf", DocumentViewerViewModel.fileNameOf(" .. "))
    }

    @Test
    fun `a page is drawn within the limits, its shape kept`() {
        assertEquals(1080 to 1397, drawnSize(1080, 612, 792))
        val (w, h) = drawnSize(4 * 1080, 612, 792)
        assertTrue(w.toLong() * h <= MAX_PAGE_PIXELS && h <= MAX_PAGE_PX)
        assertEquals(792.0 / 612, h.toDouble() / w, 0.01)
    }
}
