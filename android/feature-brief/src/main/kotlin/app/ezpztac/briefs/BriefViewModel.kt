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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.map
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
 * and Share and Save make it again first. While the brief is presented on the live sandtable, [presenting] says how (the setup is not open then).
 */
data class BriefUi(
    val open: Boolean = false,
    val step: SetupStep = SetupStep.FLOW,
    val brief: BriefState? = null,
    val pdf: PdfUi = PdfUi(),
    val failed: Boolean = false,
    val presenting: SandtableUi? = null,
) {
    val stale: Boolean get() = pdf.made != null && brief != null && pdf.made.model != brief.model
}

/**
 * The live sandtable while it runs (docs/MISSION_BRIEF.md §2.8, the web's `useSandtable`): the step followed by its id ([stepId]; null before the first is shown)
 * and where it was in the list ([anchor]), so a step that goes from the brief leaves the presenter at that place, on the step now there, which is then the one
 * followed; whether the steps and the constants show; and the workspace it was begun in ([pack], the open pack's uuid; null for this session), which it ends with.
 */
data class SandtableUi(
    val stepId: String? = null,
    val anchor: Int = 0,
    val stepsOpen: Boolean = true,
    val constantsOpen: Boolean = true,
    val pack: String? = null,
)

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

    // The workspace the frame says is open (the open pack's uuid; null for this session): what a sandtable begun now belongs to.
    private var workspace: String? = null

    // The brief's close under way, if one is: what opens it next waits for it.
    private var closing: Job? = null

    // Counts the closes (the setup closed, the sandtable ended, the brief closed for another account): an opening queued behind a close runs only if none came since.
    private var generation = 0

    val state: StateFlow<BriefUi> = combine(local, door.state, door.failed) { ui, brief, failed -> ui.copy(brief = brief, failed = failed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BriefUi())

    /** What the person is told: the pack would not take a change, a PDF saved or not. */
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()

    /** A PDF to hand to the share sheet. */
    val exports: SharedFlow<BriefExport> = mutableExports.asSharedFlow()

    /** A file name for the system's create-document picker: the screen opens it, and gives the place chosen back to [saveTo]. */
    val saves: SharedFlow<String> = mutableSaves.asSharedFlow()

    /**
     * The live sandtable as the screens draw it while it runs; null while it does not. Worked out on the main thread from a brief another client may have written (a
     * pack's): one it cannot read is said as a brief that could not be put together, never the end of the app, as a compile that fails is.
     */
    val sandtable: StateFlow<SandtableShown?> = state.map { ui -> ui.presenting?.let { shownOf(ui.brief, ui.failed, it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun shownOf(brief: BriefState?, failed: Boolean, ui: SandtableUi): SandtableShown = try {
        SandtableShown.of(brief, failed, ui)
    } catch (_: Exception) {
        SandtableShown.of(null, failed = true, ui = ui)
    }

    init {
        viewModelScope.launch { door.refusals.collect { mutableNotices.emit(it) } }
        // Closed by itself (another account): the setup and the sandtable go with it, and the PDF shown, which holds the last person's plan.
        viewModelScope.launch { door.opened.collect { open -> if (!open && (local.value.open || local.value.presenting != null)) letGo() } }
        // The step shown is the one followed, and where it is now is kept (the web's effect): if it goes from the brief the presenter stays at that place in the
        // list, and the step then shown is followed in its turn, never another that later lands at that place.
        viewModelScope.launch { sandtable.collect { shown -> shown?.let(::follow) } }
    }

    /** Opens the setup at its first step: the brief compiles from now. */
    fun open() = afterClosing {
        door.open()
        local.update { BriefUi(open = true, step = SetupStep.FLOW, pdf = it.pdf) }
    }

    /** Closes the setup, or ends the sandtable, writing a change still waiting; the PDF shown is let go (its file deleted). An opening still waiting goes too. */
    fun close() {
        generation++
        if (!local.value.open && local.value.presenting == null) return
        letGo()
        closeDoor()
    }

    // The brief closes as it writes what waits, which takes a moment: the setup or the sandtable opened meanwhile (the sandtable ended and the setup opened
    // straight after) opens it once that is over, or the close would land after the open and shut the brief under it.
    private fun closeDoor() {
        closing = viewModelScope.launch { door.close() }
    }

    // An opening waits for a close still writing, and is dropped if a close comes after it ([generation]): one asked on a tablet whose window is a phone's by the time
    // the close is over, or for an account that has gone, never opens.
    private fun afterClosing(opening: () -> Unit) {
        val wait = closing?.takeIf { it.isActive }
        if (wait == null) {
            opening()
        } else {
            val asked = generation
            viewModelScope.launch {
                wait.join()
                if (generation == asked) opening()
            }
        }
    }

    // -- The live sandtable -----------------------------------------------------------------------------------------------------------------------------------

    /**
     * Presents the brief on the live sandtable (docs/MISSION_BRIEF.md §2.8), from its first step: the setup closes for it (its PDF let go) and the brief stays open,
     * compiled as the plan changes. [narrow] (a window narrower than the steps' wide layout) starts the steps and the constants folded, as the web's does below
     * 850 px. It belongs to the workspace it is begun in ([pack]: the open pack's uuid, null for this session; by default the one [workspaceIs] last said): it ends
     * when that changes.
     */
    fun present(narrow: Boolean, pack: String? = workspace) = afterClosing {
        making?.cancel()
        val pdf = local.value.pdf.made
        local.update { BriefUi(presenting = SandtableUi(stepsOpen = !narrow, constantsOpen = !narrow, pack = pack)) }
        pdf?.pdf?.close()
        door.open()
    }

    /** Ends the sandtable (End brief, Back): the brief closes, writing a change still waiting. */
    fun endPresenting() {
        generation++
        if (local.value.presenting == null) return
        local.update { it.copy(presenting = null) }
        closeDoor()
    }

    /** The workspace now ([pack], the open pack's uuid; null for this session): a sandtable begun in another ends, as the web's does in the same render. */
    fun workspaceIs(pack: String?) {
        workspace = pack
        val presenting = local.value.presenting ?: return
        if (presenting.pack != pack) endPresenting()
    }

    /** Shows step [index] of the brief as it is now; nothing for a step that is not there. */
    fun goToStep(index: Int) {
        val shown = sandtable.value ?: return
        val step = shown.steps.getOrNull(index) ?: return
        presentingUpdate { it.copy(stepId = step.id, anchor = index) }
    }

    fun nextStep() {
        sandtable.value?.let { goToStep(it.index + 1) }
    }

    fun previousStep() {
        sandtable.value?.let { if (it.index > 0) goToStep(it.index - 1) }
    }

    fun firstStep() = goToStep(0)

    fun lastStep() {
        sandtable.value?.let { goToStep(it.steps.lastIndex) }
    }

    fun showSteps(open: Boolean) = presentingUpdate { it.copy(stepsOpen = open) }

    fun showConstants(open: Boolean) = presentingUpdate { it.copy(constantsOpen = open) }

    private fun presentingUpdate(change: (SandtableUi) -> SandtableUi) = local.update { ui -> ui.presenting?.let { ui.copy(presenting = change(it)) } ?: ui }

    private fun follow(shown: SandtableShown) {
        val step = shown.current ?: return
        val ui = shown.ui
        if (shown.index != ui.anchor || step.id != ui.stepId) presentingUpdate { it.copy(stepId = step.id, anchor = shown.index) }
    }

    private fun letGo() {
        generation++
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
                // Opened whole, then let go if the PDF is no longer wanted (the setup closed, the sandtable begun): cut short part way, it would be left open, its
                // file in the cache until sign-out.
                val opened = withContext(NonCancellable) { pdfs.open(bytes) }
                if (!isActive) {
                    opened.close()
                    throw CancellationException("The PDF is no longer wanted")
                }
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
