package app.ezpztac.sync

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The list a screen shows: it follows the store, hides what is deleted, and says nothing when nothing it shows has changed. */
class RecordFeedTest {
    private fun record(uuid: String, kind: RecordKind = RecordKind.LZ, deleted: Boolean = false) =
        LocalRecord(kind, uuid, null, null, uuid, doc("n" to 1), dirty = true, deleted = deleted, localVersion = 1)

    @Test
    fun `a list follows the store in the order it holds them`() = runTest {
        val store = InMemorySyncStore()
        store.observe(RecordKind.LZ).test {
            assertEquals(emptyList<String>(), awaitItem().map { it.uuid })
            store.transaction { put(record("m")); put(record("c")) }
            assertEquals(listOf("m", "c"), awaitItem().map { it.uuid })
            store.transaction { put(record("m").copy(name = "renamed")) }                       // an edit keeps its place
            assertEquals(listOf("m", "c"), awaitItem().map { it.uuid })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a deleted record leaves the list at once, and another kind changes nothing`() = runTest {
        val store = InMemorySyncStore()
        store.observe(RecordKind.LZ).test {
            awaitItem()
            store.transaction { put(record("a")) }
            assertEquals(listOf("a"), awaitItem().map { it.uuid })
            store.transaction { put(record("x", RecordKind.AIRCRAFT)) }                         // nothing to say about the LZ list
            expectNoEvents()
            store.transaction { put(record("a", deleted = true)) }
            assertEquals(emptyList<String>(), awaitItem().map { it.uuid })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a transaction that fails changes nothing the list shows`() = runTest {
        val store = InMemorySyncStore()
        store.observe(RecordKind.LZ).test {
            awaitItem()
            runCatching { store.transaction { put(record("a")); error("boom") } }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
