package app.ezpztac.data

import app.ezpztac.sync.FileHash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The documents' files on the device (docs/DOCUMENTS.md §9.2): kept by the hash their record names and checked against it, within a limit, gone at sign-out. */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The server: holds files by what they are, writes down every ask; [gate] holds an answer until it is completed, [lie] answers other bytes. */
    private class Server : DocumentFileSource {
        val pdfs = HashMap<DocumentAddress, ByteArray>()
        val texts = HashMap<DocumentAddress, ByteArray>()
        val asked = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var lie = false

        override suspend fun pdf(at: DocumentAddress): ByteArray {
            asked += "pdf $at"
            gate?.await()
            return if (lie) "%PDF-not this one".toByteArray() else pdfs.getValue(at)
        }

        override suspend fun text(at: DocumentAddress): ByteArray {
            asked += "text $at"
            return texts.getValue(at)
        }
    }

    private var now = 1_000_000_000_000L

    private fun TestScope.files(server: Server, maxBytes: Long = DocumentFiles.MAX_BYTES, dir: File = folder.root) =
        DocumentFiles(dir, server, maxBytes, clock = { now }, io = StandardTestDispatcher(testScheduler))

    private fun document(server: Server, at: DocumentAddress, size: Int = 100, text: String? = null, seed: Int = 0): DocumentInfo {
        val pdf = ByteArray(size) { (it * 7 + seed).toByte() }
        server.pdfs[at] = pdf
        val textBytes = text?.toByteArray()
        if (textBytes != null) server.texts[at] = textBytes
        return DocumentInfo(FileHash.of(pdf), size.toLong(), 1, textBytes?.let(FileHash::of), "pdf", "x.pdf")
    }

    private val library = DocumentAddress.Library(1)

    @Test
    fun `a PDF is fetched once and kept under its hash, for this process and the next`() = runTest {
        val server = Server()
        val info = document(server, library)
        val first = files(server).pdf(info, library)
        assertEquals("${info.pdf}.pdf", first.name)
        assertArrayEquals(server.pdfs.getValue(library), first.readBytes())
        files(server).pdf(info, library)
        assertEquals(1, server.asked.size)
        assertTrue(files(server).isKept(info.pdf))
    }

    @Test
    fun `bytes that are not the file named are refused and never kept`() = runTest {
        val server = Server()
        val info = document(server, library)
        server.lie = true
        try {
            files(server).pdf(info, library)
            fail("refused")
        } catch (e: DocumentFileException) {
            assertTrue(e.message!!.contains("not this document"))
        }
        assertFalse(files(server).isKept(info.pdf))
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.endsWith(".pdf") })
    }

    @Test
    fun `a hash that is not one names no file, and nothing is asked`() = runTest {
        val server = Server()
        val info = document(server, library).copy(pdf = "../../session")
        try {
            files(server).pdf(info, library)
            fail("refused")
        } catch (_: DocumentFileException) {
        }
        assertEquals(emptyList<String>(), server.asked)
    }

    @Test
    fun `asked for twice at once, a PDF is fetched once`() = runTest {
        val server = Server()
        val info = document(server, library)
        val store = files(server)
        server.gate = CompletableDeferred()
        val a = async { store.pdf(info, library) }
        val b = async { store.pdf(info, library) }
        runCurrent()
        server.gate!!.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `the longest unopened go first past the limit, never the one just asked for nor one kept on the device`() = runTest {
        val server = Server()
        val docs = (1..4).map { n -> DocumentAddress.Library(n).let { at -> at to document(server, at, size = 100, seed = n) } }
        val store = files(server, maxBytes = 250)
        val (pinnedAt, pinned) = docs[0]
        store.pdf(pinned, pinnedAt); now += 1
        store.pin(pinned, on = true)
        store.pdf(docs[1].second, docs[1].first); now += 1
        store.pdf(docs[2].second, docs[2].first); now += 1                         // 300 held: the oldest unpinned (2) goes
        assertTrue(store.isKept(pinned.pdf))
        assertFalse(store.isKept(docs[1].second.pdf))
        assertTrue(store.isKept(docs[2].second.pdf))
        store.pdf(docs[3].second, docs[3].first)                                  // 3 goes, 4 just asked for stays
        assertFalse(store.isKept(docs[2].second.pdf))
        assertTrue(store.isKept(docs[3].second.pdf))
        assertTrue(store.isPinned(pinned))
        assertTrue("pins outlive the process", files(server, maxBytes = 250).isPinned(pinned))
        store.pin(pinned, on = false)                                             // under the limit's rule again, and within it: it stays
        assertTrue(store.isKept(pinned.pdf))
        assertFalse(store.isPinned(pinned))
    }

    @Test
    fun `a clear lets everything go, and a file fetched across it is not kept`() = runTest {
        val server = Server()
        val info = document(server, library)
        val store = files(server)
        store.pin(info, on = true)
        server.gate = CompletableDeferred()
        val fetching = async { runCatching { store.pdf(info, library) } }
        runCurrent()
        store.clear()
        server.gate!!.complete(Unit)
        assertTrue(fetching.await().exceptionOrNull() is DocumentFileException)
        assertFalse(store.isKept(info.pdf))
        assertFalse(store.isPinned(info))
        assertEquals(0L, store.heldBytes)
    }

    @Test
    fun `the page text is read for search, a scan has none, and one that will not read is let go`() = runTest {
        val server = Server()
        val info = document(server, library, text = """{"version":1,"pages":["OPORD 24-03","Annex C"],"truncated":false}""")
        assertEquals(listOf("OPORD 24-03", "Annex C"), files(server).text(info, library)!!.pages)
        assertNull(files(server).text(info.copy(text = null), library))

        val other = DocumentAddress.Library(2)
        val bad = document(server, other, seed = 3, text = "not json")
        try {
            files(server).text(bad, other)
            fail("refused")
        } catch (_: DocumentFileException) {
        }
        assertFalse(File(folder.root, "${bad.text}.json").exists())
    }

    @Test
    fun `what a deleted Library document named goes, and what a pack's document was opened with is left to the limit`() = runTest {
        val server = Server()
        val lib = document(server, library, seed = 1)
        val pack = DocumentAddress.Pack("a4f7e2c9-1b3d-4e85-9c60-8f2a5d7b0e13", "doc-1")
        val inPack = document(server, pack, seed = 2)
        val store = files(server)
        store.pdf(lib, library)
        store.pdf(inPack, pack)
        store.prune(library = emptySet())
        assertFalse(store.isKept(lib.pdf))
        assertTrue(store.isKept(inPack.pdf))
        assertTrue("a later process knows it too", files(server).let { it.prune(emptySet()); it.isKept(inPack.pdf) })
    }

    @Test
    fun `a write the process did not live to finish is cleared, and nothing else in the folder is taken for a document`() = runTest {
        File(folder.root, ".part-123").writeText("half")
        File(folder.root, "notes.txt").writeText("not ours")
        val store = files(Server())
        assertEquals(0L, store.heldBytes)
        assertFalse(File(folder.root, ".part-123").exists())
    }
}
