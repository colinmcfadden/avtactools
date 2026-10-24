package app.ezpztac.briefs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.BriefState
import app.ezpztac.data.BriefWords
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The setup's three steps, as the design draws them: its tabs. */
enum class SetupStep(val title: String) {
    FLOW("Mission flow"),
    DETAILS("Brief details"),
    REVIEW("Review"),
}

/** A PDF made of the brief: opened to be shown ([pdf]), its [bytes] for Share and Save, the compiled brief it was made from ([model]) and its file's name. */
class MadePdf(val pdf: BriefPdf, val bytes: ByteArray, val model: JsonObject, val fileName: String)

/** The PDF as the review shows it: the one made last (null: none yet), whether one is being made, and why the last try did not make one ([failure]). */
data class PdfUi(val made: MadePdf? = null, val making: Boolean = false, val failure: String? = null)

/** A file the brief hands to another app through the share sheet. */
class BriefExport(val fileName: String, val bytes: ByteArray)

/**
 * The setup as the screens draw it: whether it is open, which step shows, the brief compiled now ([brief]; null for a moment as it opens), whether it could not
 * be put together ([failed]), and the PDF. The PDF is [stale] when the brief has changed since it was made (an answer, an option, the plan): the review says so,
 * and Share and Save make it again first.
 */
data class BriefUi(
    val open: Boolean = false,
    val step: SetupStep = SetupStep.FLOW,
    val brief: BriefState? = null,
    val pdf: PdfUi = PdfUi(),
    val failed: Boolean = false,
) {
    val stale: Boolean get() = pdf.made != null && brief != null && pdf.made.model != brief.model
}

/** Writes a PDF where the person chose to save it (the system's create-document picker); tests give their own. */
fun interface BriefFileWriter {
    suspend fun write(target: Uri, bytes: ByteArray)

    /** Removes the empty file the picker made, when there is no PDF to write in it any more (the setup closed, the app ended while the picker was up). */
    suspend fun discard(target: Uri) {}
}

/** [BriefFileWriter] through the platform's content resolver. */
@Singleton
class ResolverFileWriter @Inject constructor(@ApplicationContext private val context: Context) : BriefFileWriter {
    override suspend fun write(target: Uri, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(target, "w") ?: error("The place chosen cannot be written to")
        stream.use { it.write(bytes) }
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
 * The brief's setup (docs/MISSION_BRIEF.md, B8; the design's Android tablet boards *brief setup* and *review and share*): the steps over the brief being set up
 * ([BriefDoor]), the PDF made on the server and shown with the platform's renderer ([BriefPdfs]), *Share PDF* through the share sheet ([exports], which the frame
 * hands to it) and *Save to device* through the system's picker ([saves], which the screen opens, then [saveTo]).
 *
 * The PDF is made of the brief once it has settled after it is asked for (a field written as its step leaves the screen, and the weather asked for as the setup
 * opened, land a moment later), and kept with it, so what is shared or saved is always a PDF of the brief the person sees: one made of an older brief is made again
 * first. It needs a connection, and says so in the app's words when it cannot be made.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class BriefViewModel @Inject constructor(
    private val door: BriefDoor,
    private val pdfs: BriefPdfs,
    private val files: BriefFileWriter,
) : ViewModel() {
    private val local = MutableStateFlow(BriefUi())
    private val mutableNotices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val mutableExports = MutableSharedFlow<BriefExport>(extraBufferCapacity = 1)
    private val mutableSaves = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var making: Job? = null

    val state: StateFlow<BriefUi> = combine(local, door.state, door.failed) { ui, brief, failed -> ui.copy(brief = brief, failed = failed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BriefUi())

    /** What the person is told: the pack would not take a change, a PDF saved or not. */
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()

    /** A PDF to hand to the share sheet. */
    val exports: SharedFlow<BriefExport> = mutableExports.asSharedFlow()

    /** A file name for the system's create-document picker: the screen opens it, and gives the place chosen back to [saveTo]. */
    val saves: SharedFlow<String> = mutableSaves.asSharedFlow()

    init {
        viewModelScope.launch { door.refusals.collect { mutableNotices.emit(it) } }
        // Closed by itself (another account): the setup goes with it, and the PDF shown, which holds the last person's plan.
        viewModelScope.launch { door.opened.collect { open -> if (!open && local.value.open) letGo() } }
    }

    /** Opens the setup at its first step: the brief compiles from now. */
    fun open() {
        door.open()
        local.update { BriefUi(open = true, step = SetupStep.FLOW, pdf = it.pdf) }
    }

    /** Closes the setup, writing a change still waiting; the PDF shown is let go (its file deleted). */
    fun close() {
        if (!local.value.open) return
        letGo()
        viewModelScope.launch { door.close() }
    }

    private fun letGo() {
        making?.cancel()
        val pdf = local.value.pdf.made
        local.update { BriefUi() }
        pdf?.pdf?.close()
    }

    /** Shows a step. The review makes the PDF the first time it shows, as the design shows the real pages there. */
    fun show(step: SetupStep) {
        local.update { it.copy(step = step) }
        if (step == SetupStep.REVIEW && local.value.pdf.made == null && !local.value.pdf.making) makePdf()
    }

    /** The next step, from the one showing. */
    fun next() {
        val steps = SetupStep.entries
        val at = steps.indexOf(local.value.step)
        if (at < steps.lastIndex) show(steps[at + 1])
    }

    /**
     * Changes the brief (core-brief's `BriefEdits` make the transforms), also as the setup closes: a field writes what was typed in it as it leaves, after the
     * setup has been told to close.
     */
    fun edit(transform: (JsonObject) -> JsonObject) = door.change(transform)

    fun refreshWeather() = door.refreshWeather()

    /** Makes the PDF of the brief as it is compiled now (a heavy request: the figures are drawn on the server, then printed). */
    fun makePdf() = make(then = null)

    /** Hands the PDF to the share sheet, made again first if the brief changed since it was made. */
    fun share() = withCurrent { made -> mutableExports.tryEmit(BriefExport(made.fileName, made.bytes)) }

    /** Asks where to save the PDF (the system's picker, opened by the screen), made again first if the brief changed since it was made. */
    fun save() = withCurrent { made -> mutableSaves.tryEmit(made.fileName) }

    /** Writes the PDF where the person chose ([target], from the picker); nothing when they chose nowhere, and the empty file let go when there is no PDF now. */
    fun saveTo(target: Uri?) {
        if (target == null) return
        val made = local.value.pdf.made
        if (made == null) {
            viewModelScope.launch {
                files.discard(target)
                mutableNotices.emit(NOTHING_TO_SAVE)
            }
            return
        }
        viewModelScope.launch {
            val words = try {
                files.write(target, made.bytes)
                "Saved \"${made.fileName}\"."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "The PDF could not be saved there."
            }
            mutableNotices.emit(words)
        }
    }

    private fun withCurrent(then: (MadePdf) -> Unit) {
        val made = local.value.pdf.made
        val model = door.state.value?.model
        if (made != null && model != null && made.model == model) then(made) else make(then)
    }

    private fun make(then: ((MadePdf) -> Unit)?) {
        if (door.state.value == null) return
        if (making?.isActive == true) return
        local.update { it.copy(pdf = it.pdf.copy(making = true, failure = null)) }
        making = viewModelScope.launch {
            try {
                val brief = settled()
                if (brief == null) {
                    local.update { it.copy(pdf = it.pdf.copy(making = false)) }
                    return@launch
                }
                val bytes = door.print(brief.model)
                val opened = pdfs.open(bytes)
                val made = MadePdf(opened, bytes, brief.model, fileNameOf(brief))
                val old = local.value.pdf.made
                local.update { it.copy(pdf = PdfUi(made = made)) }
                old?.pdf?.close()
                then?.invoke(made)
            } catch (e: CancellationException) {
                local.update { it.copy(pdf = it.pdf.copy(making = false)) }
                throw e
            } catch (e: Exception) {
                local.update { it.copy(pdf = it.pdf.copy(making = false, failure = BriefWords.printFailure(e))) }
            }
        }
    }

    // The brief once nothing has changed it for a moment, or as it is after a few seconds of changes.
    private suspend fun settled(): BriefState? = withTimeoutOrNull(SETTLE_MAX_MS) { door.state.debounce(SETTLE_MS).filterNotNull().first() } ?: door.state.value

    override fun onCleared() {
        local.value.pdf.made?.pdf?.close()
    }

    companion object {
        /** How long the brief must be still before its PDF is made, and the longest the PDF waits for it. */
        const val SETTLE_MS = 300L
        const val SETTLE_MAX_MS = 3_000L

        internal const val NOTHING_TO_SAVE = "There is no PDF to save now: make it again on the review, then save it."

        /**
         * The PDF's file name, the web's: a pack's brief is named for the mission (its name, with only letters, digits, spaces, `_` and `-` kept), the session's
         * "Mission brief.pdf".
         */
        fun fileNameOf(brief: BriefState): String {
            val name = if (brief.inputs.pack != null) {
                ((brief.model["mission"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
            } else {
                "Mission"
            }
            val kept = name.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '_' || it == '-' }.trim()
            return "${kept.ifEmpty { "Mission" }} brief.pdf"
        }
    }
}
