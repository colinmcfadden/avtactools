package app.ezpztac.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.DocumentFileException
import app.ezpztac.data.DocumentKey
import app.ezpztac.data.DocumentState
import app.ezpztac.data.DocumentViewing
import app.ezpztac.model.DocumentHits
import app.ezpztac.model.DocumentSearch
import app.ezpztac.network.ApiException
import app.ezpztac.network.DocumentText
import app.ezpztac.network.NetworkException
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where the PDF stands: being fetched or opened, open to read, or not to be had, with why in words ([retry] offered where a try again may do). */
sealed interface PdfStage {
    data object Loading : PdfStage

    data class Ready(val pdf: DocumentPdf) : PdfStage

    data class Failed(val message: String, val retry: Boolean) : PdfStage
}

/**
 * Find in the document (docs/DOCUMENTS.md §8.2): what is typed, the hits in page order with [current] the one shown, and why there are none where that is not
 * simply no match: a scan has no text ([noText]), or the text could not be had ([failed]).
 */
data class FindUi(
    val open: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val hits: DocumentHits = DocumentHits.NONE,
    val current: Int = -1,
    val noText: Boolean = false,
    val failed: Boolean = false,
) {
    /** "3 of 12", "No matches", or what stands in for them; nothing before anything is typed. */
    val count: String?
        get() = when {
            noText -> "No text to search"
            failed -> "Not available"
            query.isBlank() || searching -> null
            hits.count == 0 -> "No matches"
            current >= 0 -> "${current + 1} of ${withCommas(hits.count)}${if (hits.more && current >= hits.hits.size - 1) "+" else ""}"
            else -> PLURAL(hits.count)
        }

    private companion object {
        val PLURAL = { n: Int -> if (n == 1) "1 match" else "${withCommas(n)} matches" }
    }
}

/** The viewer as it is drawn: the document's name and what it is, its PDF, whether it is kept on this device, and the find box. Not [open]: nothing is shown. */
data class DocumentViewerUi(
    val open: Boolean = false,
    val name: String = "",
    val detail: String = "",
    /** The pack is still opening: the document is not known yet. */
    val waiting: Boolean = false,
    val stage: PdfStage = PdfStage.Loading,
    val pinned: Boolean = false,
    val find: FindUi = FindUi(),
)

/** A PDF handed to the share sheet, which the frame opens with it. */
class DocumentExport(val fileName: String, val bytes: ByteArray)

/** Writes a kept PDF where the person chose (Save to device), through the platform's content resolver. A seam, so the view model is tried without Android. */
interface DocumentFileWriter {
    suspend fun write(target: Uri, source: File)

    /** Removes the empty file the picker made, when there is nothing to write in it any more. */
    suspend fun discard(target: Uri) {}
}

@Singleton
class ResolverDocumentWriter @Inject constructor(@ApplicationContext private val context: Context) : DocumentFileWriter {
    override suspend fun write(target: Uri, source: File): Unit = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(target, "w") ?: error("The place chosen cannot be written to")
        stream.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    override suspend fun discard(target: Uri): Unit = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A provider that cannot delete leaves the empty file: nothing more to do.
        }
    }
}

/**
 * The document viewer (docs/DOCUMENTS.md §8.1): the document [DocumentViewing] names, followed as it changes (a rename is shown, a new version opens its PDF, one
 * deleted or removed, or whose pack is left, closes with why), its PDF fetched once and drawn page by page, find in the document over the server's page text,
 * Share PDF ([exports], for the frame's share sheet), Save to device ([saves], the file name for the system's picker, then [saveTo]) and Keep on this device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentViewerViewModel @Inject constructor(
    private val viewing: DocumentViewing,
    private val opener: DocumentPdfOpener,
    private val writer: DocumentFileWriter,
) : ViewModel() {
    internal var worker: CoroutineDispatcher = Dispatchers.Default

    private val _state = MutableStateFlow(DocumentViewerUi())
    val state: StateFlow<DocumentViewerUi> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** What the person is told in a toast: why the document closed, what a save came to. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _exports = MutableSharedFlow<DocumentExport>(extraBufferCapacity = 1)
    val exports: SharedFlow<DocumentExport> = _exports.asSharedFlow()

    private val _saves = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saves: SharedFlow<String> = _saves.asSharedFlow()

    private val _goTo = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** A page (from 0) to bring into sight: a hit stepped to. */
    val goTo: SharedFlow<Int> = _goTo.asSharedFlow()

    // What is open now: the document as last followed, its PDF (by the hash it was opened for), and its text once asked for.
    private var shown: DocumentState.Shown? = null
    private var pdfFor: String? = null
    private var loading: Job? = null
    private var text: Pair<String?, DocumentText?>? = null
    private var finding: Job? = null
    private var attempt = 0

    init {
        viewModelScope.launch {
            viewing.key.flatMapLatest { key -> if (key == null) flowOf(null to null) else viewing.follow(key).map { key to it } }.collect { (key, document) -> follow(key, document) }
        }
        viewModelScope.launch {
            viewing.pinned.collect { pins -> _state.update { it.copy(pinned = shown?.info?.pdf?.let { pdf -> pdf in pins } == true) } }
        }
    }

    private fun follow(key: DocumentKey?, document: DocumentState?) {
        when {
            key == null || document == null -> shut()
            document is DocumentState.Waiting -> _state.update { it.copy(open = true, waiting = true) }
            document is DocumentState.Gone -> {
                shut()
                viewing.close()
                _notices.tryEmit(document.why)
            }
            document is DocumentState.Shown -> {
                val first = shown?.key != key
                if (first) resetFind()
                shown = document
                _state.update {
                    it.copy(
                        open = true, waiting = false, name = document.name, detail = document.info.detail,
                        pinned = document.info.pdf in viewing.pinned.value,
                    )
                }
                if (pdfFor != document.info.pdf) load(document)                                     // the first look, or a new version
                if (text?.first != null && text?.first != document.info.text) {
                    text = null                                                                    // a new version's text is another
                    if (_state.value.find.query.isNotBlank()) find(_state.value.find.query)
                }
            }
        }
    }

    private fun load(document: DocumentState.Shown) {
        loading?.cancel()
        closePdf()
        pdfFor = document.info.pdf
        val mine = ++attempt
        _state.update { it.copy(stage = PdfStage.Loading) }
        loading = viewModelScope.launch {
            val stage = try {
                val file = viewing.pdf(document)
                PdfStage.Ready(opener.open(file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure(e)
            }
            if (mine != attempt) {
                (stage as? PdfStage.Ready)?.pdf?.close()                                           // another came meanwhile
                return@launch
            }
            if (stage is PdfStage.Failed) pdfFor = null                                            // tried again by the next look, or Retry
            _state.update { it.copy(stage = stage) }
        }
    }

    /** Tries the PDF again after it could not be had. */
    fun retry() {
        shown?.let(::load)
    }

    /** Reads [key]'s document: the one before it, if any, is let go. */
    fun show(key: DocumentKey) {
        viewing.show(key)
    }

    fun close() {
        viewing.close()
    }

    private fun shut() {
        loading?.cancel()
        finding?.cancel()
        attempt++
        closePdf()
        shown = null
        pdfFor = null
        text = null
        _state.value = DocumentViewerUi()
    }

    private fun closePdf() {
        (_state.value.stage as? PdfStage.Ready)?.pdf?.close()
    }

    // -- Find in the document ---------------------------------------------------------------------------------------------------------------

    fun openFind() = _state.update { it.copy(find = it.find.copy(open = true)) }

    fun closeFind() {
        finding?.cancel()
        _state.update { it.copy(find = FindUi()) }
    }

    private fun resetFind() {
        finding?.cancel()
        text = null
        _state.update { it.copy(find = FindUi()) }
    }

    /** Finds [query] in the document's page text, a moment after the last key, fetched the first time it is asked for. */
    fun find(query: String) {
        _state.update { it.copy(find = it.find.copy(query = query, searching = query.isNotBlank(), current = -1)) }
        finding?.cancel()
        val document = shown ?: return
        if (query.isBlank()) {
            _state.update { it.copy(find = it.find.copy(hits = DocumentHits.NONE, searching = false, failed = false)) }
            return
        }
        finding = viewModelScope.launch {
            delay(FIND_PAUSE_MS)
            val pages = try {
                textOf(document)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(find = it.find.copy(searching = false, failed = true)) }
                return@launch
            }
            if (pages == null) {
                _state.update { it.copy(find = it.find.copy(searching = false, noText = true)) }
                return@launch
            }
            val hits = withContext(worker) { DocumentSearch.findInDocument(pages.pages, query) }
            _state.update { it.copy(find = it.find.copy(searching = false, hits = hits, current = if (hits.hits.isEmpty()) -1 else 0, noText = false, failed = false)) }
            hits.hits.firstOrNull()?.let { _goTo.tryEmit(it.page - 1) }
        }
    }

    private suspend fun textOf(document: DocumentState.Shown): DocumentText? {
        text?.takeIf { it.first == document.info.text }?.let { return it.second }
        val read = if (document.info.text == null) null else viewing.text(document)
        text = document.info.text to read
        return read
    }

    /** The next hit, round to the first after the last. */
    fun nextHit() = step(+1)

    fun previousHit() = step(-1)

    /** Shows hit [index] of those listed. */
    fun showHit(index: Int) {
        val hits = _state.value.find.hits.hits
        if (index !in hits.indices) return
        _state.update { it.copy(find = it.find.copy(current = index)) }
        _goTo.tryEmit(hits[index].page - 1)
    }

    private fun step(by: Int) {
        val hits = _state.value.find.hits.hits
        if (hits.isEmpty()) return
        val now = _state.value.find.current
        showHit(if (now < 0) (if (by > 0) 0 else hits.size - 1) else Math.floorMod(now + by, hits.size))
    }

    // -- Share, save, keep ------------------------------------------------------------------------------------------------------------------

    /** Hands the PDF to the share sheet (through [exports]): read off the main thread, as it can be tens of megabytes. */
    fun share() {
        val document = shown ?: return
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { viewing.pdf(document).readBytes() }
                _exports.tryEmit(DocumentExport(fileNameOf(document.name), bytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _notices.tryEmit(failure(e).message)
            }
        }
    }

    /** Asks for where to save the PDF ([saves] the name it is offered under). */
    fun askSave() {
        val document = shown ?: return
        _saves.tryEmit(fileNameOf(document.name))
    }

    /** Writes the PDF where the person chose; nothing chosen, nothing written. */
    fun saveTo(target: Uri?) {
        target ?: return
        val document = shown
        viewModelScope.launch {
            if (document == null) {
                writer.discard(target)
                return@launch
            }
            val words = try {
                writer.write(target, viewing.pdf(document))
                "Saved ${fileNameOf(document.name)}."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                "The PDF could not be saved there."
            }
            _notices.tryEmit(words)
        }
    }

    /** Keeps the PDF on this device past the limit, or lets it go back under it. */
    fun keep(on: Boolean) {
        val document = shown ?: return
        viewing.pin(document, on)
        _notices.tryEmit(if (on) "Kept on this device, to read offline." else "No longer kept: it may be let go of when space is needed.")
    }

    override fun onCleared() {
        shut()
    }

    companion object {
        internal const val FIND_PAUSE_MS = 200L
        const val OFFLINE = "The document is not on this device yet and there is no connection. Try again when you are online."
        const val NOT_OPENED = "This document cannot be opened on this device."
        const val NOT_FETCHED = "The document could not be fetched. Try again."

        /** Why the PDF cannot be shown, in words: no connection, a file that is not this document or that the platform cannot read, or the server's refusal. */
        internal fun failure(e: Throwable): PdfStage.Failed = when (e) {
            is NetworkException -> PdfStage.Failed(OFFLINE, retry = true)
            is ApiException -> PdfStage.Failed(if (e.status == 404) "That document is no longer there." else NOT_FETCHED, retry = e.status != 404)
            is DocumentFileException -> PdfStage.Failed(e.message ?: NOT_OPENED, retry = true)
            else -> PdfStage.Failed(NOT_OPENED, retry = false)
        }

        /** The file a PDF is shared or saved as: its name with `.pdf`, nothing in it a file system or a chat app trips over. */
        fun fileNameOf(name: String): String {
            val stem = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "Document" }
            return if (stem.endsWith(".pdf", ignoreCase = true)) stem else "$stem.pdf"
        }

    }
}

/** What the viewer's view model is given on a device. */
@Module
@InstallIn(SingletonComponent::class)
internal interface DocumentsModule {
    @Binds
    fun opener(impl: RendererDocumentPdfOpener): DocumentPdfOpener

    @Binds
    fun writer(impl: ResolverDocumentWriter): DocumentFileWriter
}

/** `1,203`: written by hand, not by the platform's formatter, which can use another locale's digits. */
internal fun withCommas(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
