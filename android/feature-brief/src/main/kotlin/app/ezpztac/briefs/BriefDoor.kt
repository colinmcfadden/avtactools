package app.ezpztac.briefs

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import app.ezpztac.data.BriefSession
import app.ezpztac.data.BriefState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** What the brief's screens ask of the brief being set up (core-data's [BriefSession]); tests give their own. */
interface BriefDoor {
    /** The brief compiled now while it is open; null while it is not, and while it is being read. */
    val state: StateFlow<BriefState?>

    /** Whether the brief is open: from [open] to [close], or until the account changes, when it closes by itself. */
    val opened: StateFlow<Boolean>

    /** The brief could not be put together from the plan as it is. */
    val failed: StateFlow<Boolean>

    /** What the person is told when the pack would not take a change to its brief. */
    val refusals: Flow<String>

    fun open()

    suspend fun close()

    /** Changes the brief: [transform] of the document as it is (core-brief's `BriefEdits` make them). */
    fun change(transform: (JsonObject) -> JsonObject)

    fun refreshWeather()

    /** [model] printed: the PDF's bytes. Throws the client's failures (core-data's `BriefWords.printFailure` says them). */
    suspend fun print(model: JsonObject): ByteArray
}

/** [BriefDoor] over the session. */
@Singleton
class SessionBriefDoor @Inject constructor(private val session: BriefSession) : BriefDoor {
    override val state: StateFlow<BriefState?> get() = session.state
    override val opened: StateFlow<Boolean> get() = session.opened
    override val failed: StateFlow<Boolean> get() = session.failed
    override val refusals: Flow<String> get() = session.refusals

    override fun open() = session.open()

    override suspend fun close() = session.close()

    override fun change(transform: (JsonObject) -> JsonObject) = session.change(transform)

    override fun refreshWeather() = session.refreshWeather()

    override suspend fun print(model: JsonObject): ByteArray = session.print(model)
}

/** A PDF opened to be shown: how many pages it has, and each drawn as a picture. */
interface BriefPdf : Closeable {
    val pages: Int

    /** Page [index] drawn [widthPx] wide, on white as on paper; null where it cannot be drawn. */
    suspend fun page(index: Int, widthPx: Int): ImageBitmap?
}

/** Opens a PDF's bytes to be shown (the platform's [PdfRenderer]); tests give their own. */
fun interface BriefPdfs {
    suspend fun open(bytes: ByteArray): BriefPdf
}

/**
 * [BriefPdfs] over the platform's [PdfRenderer], which reads a file: the PDF is written to the app's cache (`brief/`, where nothing else of the app's is), read from
 * there while it is shown and deleted when it is closed. It holds where a crew's LZ/PZs are in the clear, so [clear] removes whatever is left at sign-out, as the
 * shared exports are, and each PDF opened deletes the files of those no longer open (a process ended while one was shown, an open cut short).
 */
@Singleton
class RendererBriefPdfs @Inject constructor(@ApplicationContext private val context: Context) : BriefPdfs {
    private var serial = 0
    private val open = mutableSetOf<String>()                      // the files of the PDFs not closed yet, under this

    override suspend fun open(bytes: ByteArray): BriefPdf = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        val file = synchronized(this@RendererBriefPdfs) {
            folder.listFiles()?.filter { it.name !in open }?.forEach { it.delete() }
            File(folder, "brief-${++serial}.pdf").also { open += it.name }
        }
        try {
            file.writeBytes(bytes)
            RendererPdf(file) { synchronized(this@RendererBriefPdfs) { open -= file.name } }
        } catch (e: Throwable) {
            // Whatever failed (an error, not only an exception: the platform's renderer can throw either), the file is not left in the cache.
            file.delete()
            synchronized(this@RendererBriefPdfs) { open -= file.name }
            throw e
        }
    }

    private class RendererPdf(private val file: File, private val closed: () -> Unit) : BriefPdf {
        private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Throwable) {
            descriptor.close()
            throw e
        }

        // The renderer opens one page at a time and is not safe across threads: one drawing at a time, waited for without holding a thread, and closing
        // waits its turn on its own thread, never the main one (it is let go from screens and view models).
        private val drawing = Mutex()

        @Volatile
        private var shut = false

        override val pages: Int = renderer.pageCount

        // A page the renderer cannot draw (a damaged file) is none: drawn from a screen, a failure would end the app.
        override suspend fun page(index: Int, widthPx: Int): ImageBitmap? = drawing.withLock {
            if (shut || index !in 0 until pages || widthPx <= 0) return@withLock null
            withContext(Dispatchers.IO) {
                try {
                    renderer.openPage(index).use { page ->
                        val width = widthPx.coerceAtMost(MAX_PAGE_PX)
                        val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_PAGE_PX)
                        val bitmap = createBitmap(width, height)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
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
                    file.delete()
                    closed()
                }
            }
        }
    }

    companion object {
        private const val FOLDER = "brief"

        // A page drawn larger than this would be a picture of tens of megabytes: a tablet's screen is under it.
        private const val MAX_PAGE_PX = 4096

        // Where a PDF closes: never cancelled with a screen, so its file is always deleted.
        private val CLOSING = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Removes every PDF shown here and not yet closed (at sign-out, so one person's brief is not left on the device for the next). */
        fun clear(context: Context) {
            File(context.cacheDir, FOLDER).listFiles()?.forEach { it.delete() }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface BriefsModule {
    @Binds
    fun door(impl: SessionBriefDoor): BriefDoor

    @Binds
    fun pdfs(impl: RendererBriefPdfs): BriefPdfs

    @Binds
    fun files(impl: ResolverFileWriter): BriefFileWriter
}
