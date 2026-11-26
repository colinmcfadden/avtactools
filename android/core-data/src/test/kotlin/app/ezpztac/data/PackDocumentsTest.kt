package app.ezpztac.data

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a pack's document calls say when they do not go (docs/DOCUMENTS.md §7): the server's sentence where it wrote one for people, the pack's words otherwise. */
class PackDocumentsTest {
    @Test
    fun `a refusal is the server's sentence for a file, the pack's words for the pack, and the app's for anything else`() {
        assertEquals("That PDF has a password.", PackDocuments.refusal(ApiException(422, "password_protected", "That PDF has a password."), "x"))
        assertEquals(
            "This pack is finished. It is read-only until its owner reopens it.",
            PackDocuments.refusal(ApiException(423, "pack_finished", "Pack is finished"), "x"),
        )
        assertEquals("You can view this pack but not change it.", PackDocuments.refusal(ApiException(403, "pack_read_only", "read only"), "x"))
        assertEquals("Documents are not turned on for your account.", PackDocuments.refusal(ApiException(403, "feature_disabled", "off"), "x"))
        assertEquals(DocumentRepository.OFFLINE, PackDocuments.refusal(NetworkException("reset", null, requestMayHaveBeenSent = true), "x"))
        assertEquals("x", PackDocuments.refusal(ApiException(500, null, "Traceback (most recent call last)"), "x"))
        assertEquals("x", PackDocuments.refusal(IllegalStateException("inside"), "x"))
    }

    @Test
    fun `where packs do not run, nothing is sent and each call says why, and a new item's id is the server's shape`() = runBlocking {
        assertEquals(DocumentOutcome.Refused(PackDocuments.NOT_OPEN), PackDocuments.None.upload("p", "doc-1", "A.pdf", ByteArray(1), null))
        assertEquals(DocumentOutcome.Refused(PackDocuments.NOT_OPEN), PackDocuments.None.newVersion("p", "doc-1", "A.pdf", ByteArray(1)))
        assertEquals(DocumentOutcome.Refused(PackDocuments.NOT_OPEN), PackDocuments.None.saveToLibrary("p", "doc-1", "A"))
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(PackDocuments.None.newItem()))
    }
}
