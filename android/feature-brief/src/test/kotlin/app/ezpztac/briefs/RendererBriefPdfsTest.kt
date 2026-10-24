package app.ezpztac.briefs

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The PDF shown on the review, as the device keeps it: in the app's cache only while it is shown, never left there (it says where a crew's LZ/PZs are). What the
 * platform's renderer draws cannot be judged here; whether it opens at all under the test runner decides which of these run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RendererBriefPdfsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder get() = File(context.cacheDir, "brief")

    @Before
    fun clean() {
        folder.deleteRecursively()
    }

    private fun files() = folder.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `bytes that are not a PDF are refused, and leave nothing behind`() = runBlocking {
        assertTrue("opened", runCatching { RendererBriefPdfs(context).open("not a PDF".toByteArray()) }.isFailure)
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `a file left by a process that ended is deleted when the next PDF opens, and clear takes the rest`() = runBlocking {
        folder.mkdirs()
        File(folder, "brief-7.pdf").writeText("left when the app was ended")
        runCatching { RendererBriefPdfs(context).open("not a PDF".toByteArray()) }
        assertEquals(emptyList<String>(), files())
        File(folder, "brief-8.pdf").writeText("shown at sign-out")
        RendererBriefPdfs.clear(context)
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `a PDF shown is in the cache until it is closed, and then is not`() = runBlocking {
        val pdf = runCatching { RendererBriefPdfs(context).open(onePage()) }.getOrNull()
        assumeTrue("the platform's renderer does not open a PDF under the test runner", pdf != null)
        assertEquals(1, pdf!!.pages)
        assertEquals(1, files().size)
        pdf.close()
        repeat(50) { if (files().isNotEmpty()) Thread.sleep(20) }                          // closed on its own thread
        assertTrue(files().isEmpty())
        assertFalse(pdf.page(0, 100) != null)                                              // nothing drawn once closed
    }

    private fun onePage(): ByteArray {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        document.finishPage(page)
        return ByteArrayOutputStream().also { document.writeTo(it); document.close() }.toByteArray()
    }
}
