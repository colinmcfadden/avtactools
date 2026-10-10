package app.ezpztac.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an open document's session offers the mission-pack editors beside what every editor uses ([DiagramSessionTest] has that): closing one
 * document by its id, a save owed though no edit made it, and reading a document whichever is open. Over a plain store, so only the session is
 * under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSessionTest {
    private data class Doc(val id: String, val text: String)

    /** Documents in memory, and every save made, at the moment it was made. */
    private class Store(private val test: TestScope) : DocumentStore<Doc> {
        val docs = mutableMapOf("a" to Doc("a", "A"), "b" to Doc("b", "B"))
        val saves = mutableListOf<Pair<Long, Doc>>()

        override suspend fun open(uuid: String): Doc? = docs[uuid]

        override suspend fun save(document: Doc): Doc {
            saves += test.testScheduler.currentTime to document
            docs[document.id] = document
            return document
        }
    }

    private fun TestScope.session(store: Store) = DocumentSession(store, backgroundScope, { it.id }, reidentify = { d, _ -> d })

    @Test
    fun `closing a document by its id closes only that one, and saves it first`() = runTest {
        val store = Store(this)
        val session = session(store)
        session.open("a")
        session.edit("Write") { it.copy(text = "A2") }
        assertFalse(session.closeIfOpen("b"))
        assertEquals(Doc("a", "A2"), session.active.value)
        assertEquals(1, session.undoDepth.value)
        assertTrue(session.closeIfOpen("a"))
        assertNull(session.active.value)
        assertEquals("A2", store.docs.getValue("a").text)
    }

    @Test
    fun `a save owed though no edit changed the document goes after the usual pause`() = runTest {
        val store = Store(this)
        val session = session(store)
        session.open("a")
        val start = testScheduler.currentTime
        session.owe()
        advanceTimeBy(DocumentSession.SAVE_AFTER_STILL_MS - 1)
        runCurrent()
        assertTrue(store.saves.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(start + DocumentSession.SAVE_AFTER_STILL_MS to Doc("a", "A")), store.saves)
        assertEquals("not an edit to take back", 0, session.undoDepth.value)
    }

    @Test
    fun `a save owed does not put off one already waiting`() = runTest {
        val store = Store(this)
        val session = session(store)
        session.open("a")
        val start = testScheduler.currentTime
        session.edit("Write") { it.copy(text = "A2") }
        advanceTimeBy(300)
        session.owe()
        advanceTimeBy(DocumentSession.SAVE_AFTER_STILL_MS)
        runCurrent()
        assertEquals(listOf(start + DocumentSession.SAVE_AFTER_STILL_MS), store.saves.map { it.first })
    }

    @Test
    fun `with nothing open nothing is owed`() = runTest {
        val store = Store(this)
        val session = session(store)
        session.owe()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `a quiet change that fails part-way through the history leaves the history as it was`() = runTest {
        val store = Store(this)
        val session = session(store)
        session.open("a")
        session.edit("One") { it.copy(text = "A1") }
        session.edit("Two") { it.copy(text = "A2") }
        session.undo()
        // Laid over the document and the step undone first, then fails on the step that can be redone.
        val failed = runCatching { session.setQuietly { d -> if (d.text == "A2") error("cannot") else d.copy(text = d.text + "!") } }
        assertTrue(failed.isFailure)
        assertEquals("A1", session.active.value!!.text)
        session.undo()
        assertEquals("nothing of the failed change stayed in the history", "A", session.active.value!!.text)
        session.redo()
        session.redo()
        assertEquals("A2", session.active.value!!.text)
    }

    @Test
    fun `a document is read as it is open, or as its store has it, or not at all`() = runTest {
        val store = Store(this)
        val session = session(store)
        session.open("a")
        session.edit("Write") { it.copy(text = "A2") }
        assertEquals(Doc("a", "A2"), session.document("a"))
        assertEquals(Doc("b", "B"), session.document("b"))
        assertNull(session.document("c"))
    }
}
