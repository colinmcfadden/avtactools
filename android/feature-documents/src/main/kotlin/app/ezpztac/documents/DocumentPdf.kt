package app.ezpztac.documents

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.sqrt

/** One page's size in the PDF's own points, for laying a page out before it is drawn. */
data class PageSize(val width: Int, val height: Int) {
    /** Height over width; a page with no size is taken as A4's. */
    val ratio: Float get() = if (width > 0 && height > 0) height.toFloat() / width else A4

    private companion object {
        const val A4 = 297f / 210f
    }
}

/** An open PDF: how many pages, each one's size, a page drawn as a picture at a width, and let go of. */
interface DocumentPdf {
    val pages: Int

    /** Each page's size, in order ([pages] of them). */
    val sizes: List<PageSize>

    /** Page [index] (from 0) drawn [widthPx] wide on white, or null where it cannot be (a damaged page, the PDF closed). */
    suspend fun page(index: Int, widthPx: Int): ImageBitmap?

    fun close()
}

/** Opens a kept PDF in place, for reading: nothing is copied. */
fun interface DocumentPdfOpener {
    suspend fun open(file: File): DocumentPdf
}

/**
 * [DocumentPdf] over the platform's renderer (docs/DOCUMENTS.md §8.1), as the brief's review draws its PDF: no dependency, nothing followed, filled or run.
 * The file is the one kept on the device ([app.ezpztac.data.DocumentFiles]), read in place: one let go of under it (the limit, a sign-out) stays readable while it
 * is open, as an open file does.
 */
class RendererDocumentPdfOpener @Inject constructor() : DocumentPdfOpener {
    override suspend fun open(file: File): DocumentPdf = withContext(Dispatchers.IO) { RendererPdf(file) }

    private class RendererPdf(file: File) : DocumentPdf {
        private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Throwable) {
            // The platform's renderer throws errors as well as exceptions for a file it cannot read: whichever, nothing is left open.
            descriptor.close()
            throw e
        }

        // One page open at a time, and the renderer is not safe across threads: one drawing at a time, waited for without holding a thread.
        private val drawing = Mutex()

        @Volatile
        private var shut = false

        override val pages: Int = renderer.pageCount

        override val sizes: List<PageSize> = try {
            (0 until pages).map { index -> renderer.openPage(index).use { PageSize(it.width, it.height) } }
        } catch (e: Throwable) {
            runCatching { renderer.close() }
            descriptor.close()
            throw e
        }

        override suspend fun page(index: Int, widthPx: Int): ImageBitmap? = drawing.withLock {
            if (shut || index !in 0 until pages || widthPx <= 0) return@withLock null
            withContext(Dispatchers.IO) {
                try {
                    renderer.openPage(index).use { page ->
                        val (width, height) = drawnSize(widthPx, page.width, page.height)
                        val bitmap = createBitmap(width, height)
                        bitmap.eraseColor(Color.WHITE)                                         // paper, in every theme
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null                                                                        // a page too large for what is left: not drawn, never the app's end
                }
            }
        }

        override fun close() {
            if (shut) return
            shut = true
            CLOSING.launch {
                drawing.withLock {
                    runCatching { renderer.close() }
                    runCatching { descriptor.close() }
                }
            }
        }
    }

    companion object {
        // Where a PDF closes: never cancelled with a screen, so it is always let go of.
        private val CLOSING = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** No side of a page drawn is more than this: a tablet's screen is under it. */
internal const val MAX_PAGE_PX = 4096

/** Nor is a page drawn of more pixels than this (24 MB at four bytes each): zoomed in, it is drawn a little softer rather than run the app out of memory. */
internal const val MAX_PAGE_PIXELS = 6_000_000L

/** A page [widthPt] by [heightPt] drawn [widthPx] wide, within [MAX_PAGE_PX] a side and [MAX_PAGE_PIXELS] in all, its shape kept. */
internal fun drawnSize(widthPx: Int, widthPt: Int, heightPt: Int): Pair<Int, Int> {
    val ratio = if (widthPt > 0 && heightPt > 0) heightPt.toDouble() / widthPt else 297.0 / 210.0
    var width = widthPx.coerceIn(1, MAX_PAGE_PX).toDouble()
    if (width * ratio > MAX_PAGE_PX) width = MAX_PAGE_PX / ratio
    if (width * width * ratio > MAX_PAGE_PIXELS) width = sqrt(MAX_PAGE_PIXELS / ratio)
    val w = width.toInt().coerceAtLeast(1)
    return w to (w * ratio).toInt().coerceIn(1, MAX_PAGE_PX)
}
