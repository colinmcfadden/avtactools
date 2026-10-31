package app.ezpztac.android.incoming

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.data.IncomingFile
import app.ezpztac.data.IncomingFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reading what another app handed over: all of it read at once and held, none of it imported, and every way it can go wrong ending as a file the person is told
 * about rather than an exception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomingIntakeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val incoming = IncomingFiles()
    private fun intake() = IncomingIntake(context, incoming)
    private fun uri(text: String) = Uri.parse(text)

    private fun serve(address: String, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri(address), ByteArrayInputStream(bytes))
    }

    private fun view(address: String) = Intent(Intent.ACTION_VIEW, uri(address))

    private val names get() = incoming.files.value.map { it.name }

    @Test
    fun `a file opened with the app is read, named, and held for the person's answer`() = runTest {
        serve("content://files/doc/SA-6%20site.ths", byteArrayOf(1, 2, 3))
        intake().accept(view("content://files/doc/SA-6%20site.ths"))
        val held = incoming.files.value.single() as IncomingFile.Received
        assertEquals("SA-6 site.ths", held.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), held.bytes)
    }

    @Test
    fun `several shared files are all read`() = runTest {
        serve("content://p/a.lps", byteArrayOf(1)); serve("content://p/b.ths", byteArrayOf(2))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri("content://p/a.lps"), uri("content://p/b.ths")))
        intake().accept(send)
        assertEquals(listOf("a.lps", "b.ths"), names)
    }

    @Test
    fun `a file that cannot be opened is a file the person is told about`() = runTest {
        intake().accept(view("content://p/gone.lps"))                                         // nothing is served at this address
        val held = incoming.files.value.single() as IncomingFile.Unreadable
        assertEquals("gone.lps", held.name)
        assertEquals("That file could not be read.", held.reason)
    }

    @Test
    fun `a provider that fails part way is a file that could not be read, not a crash`() = runTest {
        val broken = object : InputStream() {
            private var n = 0
            override fun read(): Int = if (n++ < 10) 1 else throw IOException("provider went away")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("provider went away")
        }
        shadowOf(context.contentResolver).registerInputStream(uri("content://p/flaky.lps"), broken)
        intake().accept(view("content://p/flaky.lps"))
        assertEquals("That file could not be read.", (incoming.files.value.single() as IncomingFile.Unreadable).reason)
    }

    @Test
    fun `a file past the byte limit is refused unread, and is not held`() = runTest {
        serve("content://p/huge.lps", ByteArray(2_000))
        intake().apply { maxBytes = 1_000 }.accept(view("content://p/huge.lps"))
        val held = incoming.files.value.single() as IncomingFile.Unreadable
        assertEquals(IncomingIntake.TOO_BIG, held.reason)
    }

    @Test
    fun `a file exactly at the limit is held`() = runTest {
        serve("content://p/edge.lps", ByteArray(1_000))
        intake().apply { maxBytes = 1_000 }.accept(view("content://p/edge.lps"))
        assertTrue(incoming.files.value.single() is IncomingFile.Received)
    }

    @Test
    fun `a file path is never read, even when the file is there`() = runTest {
        val own = java.io.File(context.filesDir, "secret.txt").apply { writeText("private") }
        // From the URI Java makes (forward slashes everywhere): Uri.fromFile keeps a Windows path's backslashes in one segment.
        intake().accept(view(Uri.parse(own.toURI().toString()).toString()))
        val held = incoming.files.value.single() as IncomingFile.Unreadable
        assertEquals("secret.txt", held.name)
        assertEquals(IncomingIntake.NOT_SHARED, held.reason)
        assertTrue(incoming.files.value.none { it is IncomingFile.Received })
    }

    @Test
    fun `the app's own provider is never read back in`() = runTest {
        serve("content://${context.packageName}.exports/exports/ROUTE%201.msnx", byteArrayOf(1))
        intake().accept(view("content://${context.packageName}.exports/exports/ROUTE%201.msnx"))
        assertEquals(IncomingIntake.NOT_SHARED, (incoming.files.value.single() as IncomingFile.Unreadable).reason)
    }

    @Test
    fun `files past the limit are left out and the person is told how many`() = runTest {
        val addresses = (1..7).map { "content://p/f$it.lps" }
        addresses.forEach { serve(it, byteArrayOf(1)) }
        intake().accept(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(addresses.map(::uri))))
        assertEquals((1..IncomingIntents.MAX_FILES).map { "f$it.lps" } + "2 more", names)
        assertTrue(incoming.files.value.last() is IncomingFile.Unreadable)
    }

    @Test
    fun `when the app is already holding as many as it will, a further file is turned away in words`() = runTest {
        repeat(IncomingFiles.MAX_PENDING) { incoming.offer("held$it", byteArrayOf(1)) }
        serve("content://p/late.lps", byteArrayOf(1))
        intake().accept(view("content://p/late.lps"))
        assertEquals(IncomingFiles.MAX_PENDING, incoming.files.value.size)                    // the list is full: nothing more is kept, and nothing throws
        assertFalse(names.contains("late.lps"))
    }

    @Test
    fun `an intent that carries no files adds nothing, and says so`() = runTest {
        val intake = intake()
        assertFalse(intake.carriesFiles(Intent(Intent.ACTION_MAIN)))
        assertFalse(intake.carriesFiles(view("https://ezpztac.app/?auth=reset&token=t")))
        assertTrue(intake.carriesFiles(view("content://p/a.lps")))
        assertTrue(intake.carriesFiles(view("file:///x")))                                    // a refused address is still something to tell the person about
        intake.accept(Intent(Intent.ACTION_MAIN))
        assertTrue(incoming.files.value.isEmpty())
    }
}
