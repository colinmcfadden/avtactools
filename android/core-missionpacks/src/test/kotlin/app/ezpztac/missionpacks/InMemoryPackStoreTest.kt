package app.ezpztac.missionpacks

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** The in-memory store the client is tried with, held to what every store must do ([PackStoreContract]; Room's in the app). */
class InMemoryPackStoreTest {
    @TestFactory
    fun contract(): List<DynamicTest> = PackStoreContract.cases.map { case ->
        dynamicTest(case.name) { runBlocking<Unit> { case.run(InMemoryPackStore()) } }
    }

    /** A store that does something to a session as it reads it back, where it must give it back as it was written. */
    private class Reworking(
        private val rework: (PackSession) -> PackSession,
        private val inner: InMemoryPackStore = InMemoryPackStore(),
    ) : PackStore by inner {
        override suspend fun load(pack: String, me: Int): PackSession? = inner.load(pack, me)?.let(rework)
    }

    // The contract has to catch the two ways a store could rebuild a session wrongly: putting the batch out back in the queue
    // (the client would then skip the catch-up that says what the server took of it), and settling what it reads (the edits a
    // read-only pack holds for the server's answer would be dropped as never sent).
    @Test
    fun `the contract fails a store that puts a batch out back in the queue, or settles what it reads`() {
        val comesBack = PackStoreContract.cases.first()
        listOf<(PackSession) -> PackSession>(
            PackSessions::restored,
            { PackSessions.receive(it, emptyList()).session },
        ).forEach { rework ->
            assertThrows(AssertionError::class.java) { runBlocking<Unit> { comesBack.run(Reworking(rework)) } }
        }
    }
}
