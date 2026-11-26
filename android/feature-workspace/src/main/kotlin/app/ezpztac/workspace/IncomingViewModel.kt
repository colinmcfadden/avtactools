package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.DocumentOutcome
import app.ezpztac.data.DocumentUploads
import app.ezpztac.data.FileInspector
import app.ezpztac.data.FilePreview
import app.ezpztac.data.ImportOutcome
import app.ezpztac.data.IncomingFile
import app.ezpztac.data.IncomingFiles
import app.ezpztac.data.MissionImportOutcome
import app.ezpztac.data.MissionImporter
import app.ezpztac.data.OpenPack
import app.ezpztac.data.PackDocuments
import app.ezpztac.data.PackFacts
import app.ezpztac.data.PackLock
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.SessionPointSets
import app.ezpztac.data.ThreatFileLog
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.model.ThreatSets
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a file goes (the design's ImportReview): saved to the Library, held for this session only, kept on this device, or into the open pack (a document). */
enum class ImportPlace { LIBRARY, SESSION, DEVICE, PACK }

/**
 * What the signed-in account and the server allow of documents: [on], the account's `documents` feature; [conversion], the server turns Office files into PDFs
 * (its config's `services.documentConversion`); [saved], the account has cloud save, without which a document can go only into the open pack.
 */
data class DocumentRules(val on: Boolean = false, val conversion: Boolean = false, val saved: Boolean = false)

/** What a document is, in the web's words (`detectImport.js`' `DOCUMENT_LABELS`). */
internal val DOCUMENT_LABELS = mapOf(
    "pdf" to "PDF",
    "docx" to "Word document · turned into a PDF",
    "doc" to "Word document · turned into a PDF",
    "xlsx" to "Excel workbook · turned into a PDF, a page a sheet",
    "xls" to "Excel workbook · turned into a PDF, a page a sheet",
    "pptx" to "PowerPoint presentation · turned into a PDF",
    "ppt" to "PowerPoint presentation · turned into a PDF",
    "office" to "Office file · turned into a PDF",
)

/** One file in the review: what it turned out to be, how much is in it, and where it would go. Nothing is imported until the person says so. */
sealed interface ImportItemUi {
    val id: Long
    val fileName: String

    /** The file is being looked at. */
    data class Reading(override val id: Long, override val fileName: String) : ImportItemUi

    /** Local points: [count] points, as a set called [name], to the Library or this session ([place]). */
    data class Points(override val id: Long, override val fileName: String, val size: String, val count: Int, val name: String, val place: ImportPlace) : ImportItemUi

    /** Threats: [count] threats, a set of their own called [name] in the Library ([saved]), or into the picture on this device. */
    data class Threats(override val id: Long, override val fileName: String, val size: String, val count: Int, val name: String = "", val saved: Boolean = false, val pack: String? = null) : ImportItemUi

    /** An AMPS mission: [routes] routes and [namedPoints] named points, kept with its file in the Library. [aircraft] is what AMPS planned it for, if it says. */
    data class Mission(override val id: Long, override val fileName: String, val size: String, val routes: Int, val namedPoints: Int, val aircraft: String?) : ImportItemUi

    /**
     * A document (docs/DOCUMENTS.md §9.2): a PDF, or a Word, Excel or PowerPoint file the server turns into one ([type]: `pdf`, `docx`, …), uploaded as [name] to
     * [place], the Library or the open pack ([pack], with [members] in it), as [places] allows. [error] is why the last try did not go: the file stays in the
     * review, to be tried again under the same identity.
     */
    data class Document(
        override val id: Long,
        override val fileName: String,
        val size: String,
        val type: String,
        val name: String,
        val place: ImportPlace,
        val places: List<ImportPlace>,
        val pack: String? = null,
        val members: Int = 0,
        val error: String? = null,
    ) : ImportItemUi {
        /** What the file is, in the web's words: `PDF`, `Word document · turned into a PDF`. */
        val label: String get() = DOCUMENT_LABELS[type] ?: DOCUMENT_LABELS.getValue("office")

        /** The server turns it into a PDF. */
        val converted: Boolean get() = type != "pdf"
    }

    /** Not something that can be brought in, or could not be read; [message] says why, in words. Nothing is imported from it. */
    data class Problem(override val id: Long, override val fileName: String, val message: String) : ImportItemUi
}

data class IncomingUiState(
    val items: List<ImportItemUi> = emptyList(),
    /** The files are being brought in. */
    val busy: Boolean = false,
) {
    /** Whether the review is to be shown. */
    val visible: Boolean get() = items.isNotEmpty()

    /** How many of the files can be brought in. */
    val importable: Int get() = items.count { it is ImportItemUi.Points || it is ImportItemUi.Threats || it is ImportItemUi.Mission || it is ImportItemUi.Document }

    /** An Office file is among those being brought in: the footer says the server takes a while to turn it into a PDF. */
    val converting: Boolean get() = busy && items.any { it is ImportItemUi.Document && it.converted }

    /** A file is still being looked at: Import waits for it. */
    val reading: Boolean get() = items.any { it is ImportItemUi.Reading }

    /** Where the files go, in a line under them (the design's): `1 to Library · 1 stays in this session · 2 stay on this device`. */
    val summary: String get() {
        if (reading) return "Looking at what is in the files…"
        val library = items.count {
            (it is ImportItemUi.Points && it.place == ImportPlace.LIBRARY) || it is ImportItemUi.Mission || (it is ImportItemUi.Threats && it.saved && it.pack == null) ||
                (it is ImportItemUi.Document && it.place == ImportPlace.LIBRARY)
        }
        val toPack = items.count { (it is ImportItemUi.Threats && it.pack != null) || (it is ImportItemUi.Document && it.place == ImportPlace.PACK) }
        val session = items.count { it is ImportItemUi.Points && it.place == ImportPlace.SESSION }
        val device = items.count { it is ImportItemUi.Threats && !it.saved && it.pack == null }
        val parts = listOfNotNull(
            "$library to Library".takeIf { library > 0 },
            "$toPack to the pack".takeIf { toPack > 0 },
            (if (session == 1) "1 stays in this session" else "$session stay in this session").takeIf { session > 0 },
            (if (device == 1) "1 stays on this device" else "$device stay on this device").takeIf { device > 0 },
        )
        return if (parts.isEmpty()) "Nothing here can be imported." else parts.joinToString(" · ")
    }
}

/**
 * The import review (docs/native-design: ImportReview): every file another app handed over or the person chose, what each turned out to be, where it goes,
 * and nothing brought in until the person says so. A file waits in [IncomingFiles]; this looks inside it (what it is, how much is in it) and, on Import,
 * brings in each the same way however it arrived: a threat file joins the picture on the device, a local-points file becomes a saved set or one held for
 * this session ([SessionPointSets]), a mission is kept with its file. What each came to is said in a toast ([notices]).
 */
@HiltViewModel
class IncomingViewModel @Inject constructor(
    private val incoming: IncomingFiles,
    private val inspector: FileInspector,
    private val threats: ThreatStore,
    private val threatSelection: ThreatSelection,
    private val points: PointSetRepository,
    private val missions: MissionImporter,
    private val sessionPoints: SessionPointSets,
    private val threatFiles: ThreatFileLog,
    private val pack: OpenPack = OpenPack.None,
    private val documents: DocumentUploads = DocumentUploads.None,
    private val packDocuments: PackDocuments = PackDocuments.None,
) : ViewModel() {
    internal var worker: CoroutineDispatcher = Dispatchers.IO

    /** What each waiting file turned out to be, by its id, so a file is read once however often the screen redraws. */
    private val looked = MutableStateFlow<Map<Long, FilePreview>>(emptyMap())

    /** The person's choices for each file: a set's name, and where a set of points goes. */
    private val names = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val places = MutableStateFlow<Map<Long, ImportPlace>>(emptyMap())

    /** Each document's identity, made once for the file and kept for every try of it, so an upload tried again after a lost answer is the same document. */
    private val uploadIds = HashMap<Long, String>()

    /** Why a document's last upload did not go, by file. */
    private val failures = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val busy = MutableStateFlow(false)
    private val said = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 16)

    /** How many times the review was cancelled: a pick still being read when it was ([pickStarted]) puts nothing more in it. */
    private var cancels = 0

    /**
     * What bringing each file in came to, in words, for the frame's toasts: each once the review has closed, since a toast under it would be gone before
     * anyone saw it (the files after it can take a while), and none from before a sign-out.
     */
    val notices: Flow<String> = said
        .onEach { state.first { !it.visible } }
        .filter { (generation, _) -> generation == incoming.generation }
        .map { it.second }

    /**
     * Whether the signed-in account has threats (its `threats` feature, which the screen knows: [allowThreats]). A threat file is not added to the picture
     * until it says so: an account an administrator has turned threats off for has no Threats panel to see them in, and the web greys out the file.
     */
    private val threatsAllowed = MutableStateFlow(false)

    /** Says whether the signed-in account has threats ([threatsAllowed]). */
    fun allowThreats(allowed: Boolean) {
        threatsAllowed.value = allowed
    }

    /** What the account and the server allow of documents (docs/DOCUMENTS.md §9.3): none without the feature, PDFs only where the server cannot convert. */
    private val documentRules = MutableStateFlow(DocumentRules())

    /** Says what the signed-in account and the server allow of documents ([DocumentRules]). */
    fun allowDocuments(rules: DocumentRules) {
        documentRules.value = rules
    }

    private val choices = combine(names, places, failures) { n, p, f -> Choices(n, p, f) }
    private val allowed = combine(threatsAllowed, documentRules, pack.state) { threatsOn, rules, _ -> threatsOn to rules }

    val state: StateFlow<IncomingUiState> = combine(incoming.files, looked, choices, busy, allowed) { files, seen, chosen, working, (threatsOn, rules) ->
        IncomingUiState(items = itemsOf(files, seen, chosen, threatsOn, rules), busy = working)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IncomingUiState())

    /** The files as they are now, read from the sources and not from [state], which follows them a dispatch behind: what Import brings in is what was shown. */
    private fun current(): List<ImportItemUi> =
        itemsOf(incoming.files.value, looked.value, Choices(names.value, places.value, failures.value), threatsAllowed.value, documentRules.value)

    private fun itemsOf(files: List<IncomingFile>, seen: Map<Long, FilePreview>, chosen: Choices, threatsOn: Boolean, rules: DocumentRules) =
        files.map { item(it, seen[it.id], chosen, threatsOn, rules) }

    private data class Choices(val names: Map<Long, String>, val places: Map<Long, ImportPlace>, val failures: Map<Long, String>)

    init {
        // Look inside each file as it arrives, off the main thread: a large threat file or a set of thousands of points is not instant.
        viewModelScope.launch {
            incoming.files.collect { files ->
                val ids = files.mapTo(HashSet()) { it.id }
                looked.update { seen -> seen.filterKeys { it in ids } }                                 // what has gone is forgotten
                names.update { it.filterKeys { id -> id in ids } }
                places.update { it.filterKeys { id -> id in ids } }
                failures.update { it.filterKeys { id -> id in ids } }
                uploadIds.keys.retainAll(ids)
                for (file in files) {
                    if (file !is IncomingFile.Received || file.id in looked.value) continue
                    val result = try {
                        withContext(worker) { inspector.inspect(file.name, file.bytes) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        FilePreview.Problem("That file could not be read.")                                // whatever is inside is not shown
                    }
                    looked.update { it + (file.id to result) }
                }
            }
        }
    }

    private fun item(file: IncomingFile, seen: FilePreview?, chosen: Choices, threatsOn: Boolean, rules: DocumentRules): ImportItemUi = when (file) {
        is IncomingFile.Unreadable -> ImportItemUi.Problem(file.id, file.name, file.reason)
        is IncomingFile.Received -> {
            val size = sizeOf(file.bytes.size.toLong())
            val name = chosen.names[file.id]
            val place = chosen.places[file.id]
            when (seen) {
                null -> ImportItemUi.Reading(file.id, file.name)
                is FilePreview.Threats ->
                    if (threatsOn) {
                        val open = pack.state.value
                        val lock = open?.let(PackLock::of)
                        if (lock != null) {
                            ImportItemUi.Problem(file.id, file.name, lock.reason)                     // the pack's threats are the ones on the map, and this one takes none
                        } else {
                            ImportItemUi.Threats(file.id, file.name, size, seen.threats.size, name ?: ThreatSets.nameFromFile(file.name), threats.saves.value || open != null, open?.let(PackFacts::name))
                        }
                    } else {
                        ImportItemUi.Problem(file.id, file.name, NO_THREATS)
                    }
                is FilePreview.Points -> ImportItemUi.Points(file.id, file.name, size, seen.count, name ?: seen.setName, place ?: ImportPlace.LIBRARY)
                is FilePreview.Mission -> ImportItemUi.Mission(file.id, file.name, size, seen.routes, seen.namedPoints, seen.aircraft)
                is FilePreview.Document -> documentItem(file, size, seen, name, place, chosen.failures[file.id], rules)
                is FilePreview.Problem -> ImportItemUi.Problem(file.id, file.name, seen.message)
            }
        }
    }

    /**
     * A document as the rules allow it (the web's `documentRules` and `destinationsFor`): none without the feature, an Office file only where the server converts,
     * into the open pack for one who can change it, else the Library, which needs cloud save; with nowhere to go, why.
     */
    private fun documentItem(
        file: IncomingFile.Received, size: String, seen: FilePreview.Document, name: String?, place: ImportPlace?, error: String?, rules: DocumentRules,
    ): ImportItemUi {
        if (!rules.on) return ImportItemUi.Problem(file.id, file.name, DOCUMENTS_OFF)
        if (seen.type != "pdf" && !rules.conversion) return ImportItemUi.Problem(file.id, file.name, NO_CONVERSION)
        val open = pack.state.value
        val lock = open?.let(PackLock::of)
        val packOk = open != null && lock == null && open.session != null
        val places = listOfNotNull(ImportPlace.PACK.takeIf { packOk }, ImportPlace.LIBRARY.takeIf { rules.saved })
        if (places.isEmpty()) {
            val why = when {
                open == null -> "Open a Mission Pack to upload it there."
                lock != null -> lock.reason
                else -> "The pack is still opening."
            }
            return ImportItemUi.Problem(file.id, file.name, "Cloud save is off for this account, so a document has nowhere to go. $why")
        }
        val session = open?.session
        return ImportItemUi.Document(
            file.id, file.name, size, seen.type, name ?: seen.name, place?.takeIf { it in places } ?: places.first(), places,
            pack = if (packOk) open.let(PackFacts::name) else null, members = session?.let(PackFacts::audience) ?: 0, error = error,
        )
    }

    // -- The person's choices -----------------------------------------------------------------------------------------------------------

    /** The name a set of points is brought in as. */
    fun setName(id: Long, name: String) = names.update { it + (id to name) }

    /** Where a set of points goes (the Library or this session), or a document (the open pack or the Library); the other kinds have one place only. */
    fun setPlace(id: Long, place: ImportPlace) {
        if (place == ImportPlace.DEVICE) return
        places.update { it + (id to place) }
        failures.update { it - id }
    }

    /** Takes one file out of the review: nothing is brought in from it. Not while the files are being brought in. */
    fun remove(id: Long) {
        if (!busy.value) incoming.dismiss(id)
    }

    /** Closes the review without bringing anything in: every file is let go, and so is any file of a pick still being read ([pickStarted]). */
    fun cancel() {
        if (busy.value) return
        cancels++
        incoming.files.value.forEach { incoming.dismiss(it.id) }
    }

    /**
     * Brings in every file that can be, each where the person chose, one after another; what each came to is said in a toast, and the review closes. A file
     * still being looked at waits (the screen does not offer Import then).
     */
    fun importAll() {
        val items = current()
        if (busy.value || items.any { it is ImportItemUi.Reading }) return
        busy.value = true
        val generation = incoming.generation
        viewModelScope.launch {
            try {
                for (item in items) {
                    if (incoming.generation != generation) break                                  // signed out meanwhile: the files went with the account
                    val file = incoming.files.value.firstOrNull { it.id == item.id } as? IncomingFile.Received
                    if (item is ImportItemUi.Document && file != null) {
                        // A document that did not go stays in the review with why, to be tried again as the same document; one that went is said.
                        when (val outcome = upload(item, file)) {
                            is DocumentOutcome.Done -> {
                                said.tryEmit(generation to if (item.place == ImportPlace.PACK) "Uploaded “${item.name.trim().ifEmpty { item.fileName }}” to ${item.pack}." else "Uploaded “${item.name.trim().ifEmpty { item.fileName }}” to your Library.")
                                incoming.dismiss(item.id)
                            }
                            is DocumentOutcome.Refused -> if (incoming.generation == generation) failures.update { it + (item.id to outcome.message) }
                        }
                        continue
                    }
                    val outcome = if (file == null) null else bringIn(item, file, generation)
                    outcome?.let { said.tryEmit(generation to it) }
                    incoming.dismiss(item.id)
                }
                // A file that came in while these were brought in stays for the next look.
            } finally {
                busy.value = false
            }
        }
    }

    /** Uploads a document where it was chosen to go, under the identity kept for its file. */
    private suspend fun upload(item: ImportItemUi.Document, file: IncomingFile.Received): DocumentOutcome {
        val uuid = uploadIds.getOrPut(item.id) { java.util.UUID.randomUUID().toString() }
        val name = item.name.trim().takeIf { it.isNotEmpty() }
        return try {
            if (item.place == ImportPlace.PACK) {
                val open = pack.state.value
                when {
                    open == null -> DocumentOutcome.Refused(PackDocuments.NOT_OPEN)
                    pack.lockNow() != null -> DocumentOutcome.Refused(pack.lockNow()!!.reason)             // it turned read-only with the review open
                    else -> packDocuments.upload(open.uuid, "doc-$uuid", file.name, file.bytes, name)
                }
            } else {
                documents.upload(file.name, file.bytes, name, uuid)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DocumentOutcome.Refused("${file.name} could not be uploaded. Try again.")
        }
    }

    /**
     * Brings one file in. What it came to, in words, or null for one that had nothing to bring. What lands in memory or on the map (a session's set, an opened
     * mission) lands only if nobody signed out while it was read ([generation]): it would be the next person's to see.
     */
    private suspend fun bringIn(item: ImportItemUi, file: IncomingFile.Received, generation: Int): String? = try {
        when (item) {
            is ImportItemUi.Threats -> {
                val seen = looked.value[item.id] as? FilePreview.Threats
                val lock = pack.lockNow()
                if (seen == null || !threatsAllowed.value) {
                    null
                } else if (lock != null) {
                    lock.reason                                                                       // it turned read-only with the review open
                } else {
                    val ids = threats.addAsSet(seen.threats, item.name.ifBlank { ThreatSets.nameFromFile(file.name) })
                    ids.lastOrNull()?.let(threatSelection::select)
                    threatFiles.record(file.name, seen.threats.size, item.saved, item.pack)
                    "Added ${count(seen.threats.size, "threat")} from ${file.name}."
                }
            }
            is ImportItemUi.Points -> if (item.place == ImportPlace.SESSION) {
                when (val read = withContext(worker) { points.read(file.bytes, file.name, item.name) }) {
                    is ImportOutcome.Imported -> when {
                        incoming.generation != generation -> null
                        sessionPoints.add(read.set) -> "Showing ${read.set.name} for this session: ${count(read.set.points.size, "point")}, not saved."
                        else -> SessionPointSets.FULL
                    }
                    is ImportOutcome.Refused -> read.message
                }
            } else {
                when (val saved = withContext(worker) { points.import(file.bytes, file.name, item.name) }) {
                    is ImportOutcome.Imported -> "Saved ${saved.set.name} with ${count(saved.set.points.size, "point")}."
                    is ImportOutcome.Refused -> saved.message
                }
            }
            is ImportItemUi.Mission -> {
                val seen = looked.value[item.id] as? FilePreview.Mission
                if (seen == null) {
                    null
                } else {
                    val stillWanted = { incoming.generation == generation }
                    when (val outcome = withContext(worker) { missions.import(file.bytes, seen.mission, file.name, stillWanted) }) {
                        is MissionImportOutcome.Imported -> missionResult(outcome.set.routes.size, file.name, outcome.set.name, outcome.opened, outcome.inPack)
                        is MissionImportOutcome.Refused -> outcome.message
                    }
                }
            }
            is ImportItemUi.Reading, is ImportItemUi.Problem, is ImportItemUi.Document -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        "${file.name} could not be brought in."                                                         // a failure inside is the app's to word
    }

    // -- Files chosen in the app -----------------------------------------------------------------------------------------------------------

    /**
     * A pick in the app's own picker begins: its files are read one by one, which from a cloud provider takes a while, and once the person has cancelled the
     * review none still being read is put in it ([offer], [refuse]), or it would open again for files they said no to.
     */
    fun pickStarted(): Int = cancels

    /** A file the person chose in the app's own picker ([pick]): put in the review like any other, so the same question is asked however a file arrives. */
    fun offer(name: String, bytes: ByteArray, pick: Int = cancels) {
        if (pick != cancels) return
        if (!incoming.offer(name, bytes)) incoming.refuse(name, FULL)
    }

    /** A chosen file that could not be read ([reason] is in words): told to the person in the same place. */
    fun refuse(name: String, reason: String, pick: Int = cancels) {
        if (pick != cancels) return
        incoming.refuse(name, reason)
    }

    /**
     * What the person is told once a mission is in. A mission that could not be opened (another one open could not be saved first) is where to find it; one brought
     * in while a Mission Pack is open ([inPack]) is in the Library, for when the pack is left.
     */
    internal fun missionResult(routes: Int, fileName: String, setName: String, opened: Boolean, inPack: Boolean = false): String =
        "Brought in ${count(routes, "route")} from $fileName as $setName." + when {
            inPack -> " It is in your Library: switch to Personal to open it."
            opened -> ""
            else -> " It is in your list of route sets and missions."
        }

    private fun count(n: Int, one: String) = if (n == 1) "1 $one" else "${withCommas(n.toLong())} ${one}s"

    companion object {
        const val TOO_BIG = "That file is too large to be a document, local points, threat or mission file."
        const val DOCUMENTS_OFF = "Not supported. Nothing will be imported from this file."
        const val NO_CONVERSION = "This server cannot turn Office files into PDFs. Save it as a PDF and upload that."
        const val FULL = "EZ/PZ is already holding as many files as it can. Deal with those first, then open this one again."
        const val NO_THREATS = "Threats are turned off for your account, so this threat file cannot be added."

        /** A file's size as the design says it: `82 KB`, `1.5 MB`. Written by hand, not by the platform's formatter, which can use another locale's digits. */
        internal fun sizeOf(bytes: Long): String = when {
            bytes < 1024 -> "$bytes bytes"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> "${oneDecimal(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
