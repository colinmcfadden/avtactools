package app.ezpztac.android.incoming

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app takes from an intent another app started it with. The addresses are the sender's, so the rule is the narrowest that is safe: a content address,
 * never a file path and never the app's own provider, and no more than a few.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomingIntentsTest {
    private val own = "app.ezpztac.unreleased.exports"
    private fun uri(text: String) = Uri.parse(text)
    private fun view(address: String) = Intent(Intent.ACTION_VIEW, uri(address))
    private fun send(address: String) = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri(address))
    private fun sendMany(vararg addresses: String) =
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(addresses.map(::uri)))

    @Test
    fun `a file opened with the app is taken from the intent's data`() {
        val found = IncomingIntents.find(view("content://com.android.providers.downloads.documents/document/42"), own)
        assertEquals(listOf(uri("content://com.android.providers.downloads.documents/document/42")), found.uris)
        assertTrue(found.refused.isEmpty())
        assertEquals(0, found.leftOut)
    }

    @Test
    fun `a file shared to the app is taken from the stream`() {
        val found = IncomingIntents.find(send("content://mail.provider/attachments/SA-6%20site.ths"), own)
        assertEquals(1, found.uris.size)
        assertEquals("content", found.uris.single().scheme)
    }

    @Test
    fun `several files shared at once are all taken, in the order sent`() {
        val found = IncomingIntents.find(sendMany("content://p/a", "content://p/b", "content://p/c"), own)
        assertEquals(listOf("a", "b", "c"), found.uris.map { it.lastPathSegment })
    }

    @Test
    fun `no more than the limit are taken, and the rest are counted`() {
        val found = IncomingIntents.find(sendMany(*(1..8).map { "content://p/$it" }.toTypedArray()), own)
        assertEquals(IncomingIntents.MAX_FILES, found.uris.size)
        assertEquals(8 - IncomingIntents.MAX_FILES, found.leftOut)
        assertEquals((1..IncomingIntents.MAX_FILES).map { it.toString() }, found.uris.map { it.lastPathSegment })
    }

    @Test
    fun `the same file named twice is read once`() {
        val found = IncomingIntents.find(sendMany("content://p/a", "content://p/a"), own)
        assertEquals(1, found.uris.size)
    }

    @Test
    fun `a file path is refused, because it could name one of the app's own private files`() {
        val found = IncomingIntents.find(view("file:///data/data/app.ezpztac.unreleased/databases/ezpz.db"), own)
        assertTrue(found.uris.isEmpty())
        assertEquals(listOf("ezpz.db"), found.refused)
    }

    @Test
    fun `an address with no scheme, or another one, is refused`() {
        assertEquals(listOf("x"), IncomingIntents.find(view("/sdcard/x"), own).refused)
        assertEquals(listOf("x"), IncomingIntents.find(view("ftp://host/x"), own).refused)
        assertEquals(listOf("x"), IncomingIntents.find(view("android.resource://app.ezpztac.unreleased/raw/x"), own).refused)
    }

    @Test
    fun `the scheme is read without regard to case, so a spelling cannot slip a file path past or lock a real file out`() {
        assertEquals(1, IncomingIntents.find(view("CONTENT://p/a"), own).uris.size)
        assertEquals(listOf("x"), IncomingIntents.find(view("FILE:///data/x"), own).refused)
    }

    @Test
    fun `the app's own provider is refused, whatever the case`() {
        assertEquals(listOf("ROUTE 1.msnx"), IncomingIntents.find(view("content://$own/exports/ROUTE%201.msnx"), own).refused)
        assertEquals(1, IncomingIntents.find(view("content://${own.uppercase()}/exports/a"), own).refused.size)
        assertTrue(IncomingIntents.find(view("content://$own/exports/a"), own).uris.isEmpty())
    }

    @Test
    fun `a content address with no authority is refused`() {
        val found = IncomingIntents.find(view("content:///a"), own)
        assertTrue(found.uris.isEmpty())
        assertEquals(1, found.refused.size)
    }

    @Test
    fun `an emailed link is the auth flow's, and is neither taken nor refused here`() {
        val found = IncomingIntents.find(view("https://ezpztac.app/?auth=verify&token=abc"), own)
        assertTrue(found.isEmpty)
        assertTrue(IncomingIntents.find(view("http://example.com/x.ths"), own).isEmpty)
    }

    @Test
    fun `a launch from the home screen, and an intent of another kind, carry nothing`() {
        assertTrue(IncomingIntents.find(Intent(Intent.ACTION_MAIN), own).isEmpty)
        assertTrue(IncomingIntents.find(null, own).isEmpty)
        assertTrue(IncomingIntents.find(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello"), own).isEmpty)
        assertTrue(IncomingIntents.find(Intent("com.example.OTHER").setData(uri("content://p/a")), own).isEmpty)
    }

    @Test
    fun `a refused address among good ones leaves the good ones`() {
        val found = IncomingIntents.find(sendMany("content://p/a", "file:///data/x", "content://p/b"), own)
        assertEquals(listOf("a", "b"), found.uris.map { it.lastPathSegment })
        assertEquals(listOf("x"), found.refused)
        assertFalse(found.isEmpty)
    }

    @Test
    fun `an address is called by the end of its path, cut short, since it is the sender's text`() {
        assertEquals("a file", IncomingIntents.label(uri("content://p")))
        assertEquals("a file", IncomingIntents.label(uri("content://p/%20%20")))
        assertEquals(80, IncomingIntents.label(uri("content://p/" + "x".repeat(500))).length)
    }
}
