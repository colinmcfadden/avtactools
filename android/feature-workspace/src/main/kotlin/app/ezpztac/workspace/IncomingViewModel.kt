package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.FileInspector
import app.ezpztac.data.FilePreview
import app.ezpztac.data.ImportOutcome
import app.ezpztac.data.IncomingFile
import app.ezpztac.data.IncomingFiles
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The file at the front of the queue, as it is put to the person. Nothing is imported until they accept. */
sealed interface IncomingOfferUi {
    val fileName: String

    /** The file is being looked at. */
    data class Reading(override val fileName: String) : IncomingOfferUi

    /** A threat file: [count] threats would be added to the picture on this device. */
    data class Threats(override val fileName: String, val count: Int) : IncomingOfferUi

    /** A local-points file: its [count] points would be saved, and synced, as a set called [setName]. */
    data class Points(override val fileName: String, val setName: String, val count: Int) : IncomingOfferUi

    /** An AMPS mission, which the app cannot open yet. */
    data class Mission(override val fileName: String) : IncomingOfferUi

    /** Not something that can be opened, or could not be read; [message] says why, in words. */
    data class Problem(override val fileName: String, val message: String) : IncomingOfferUi
}

data class IncomingUiState(
    val offer: IncomingOfferUi? = null,
    /** Files behind the one being offered. */
    val waiting: Int = 0,
    /** An accepted file is being saved. */
    val busy: Boolean = false,
    /** What accepting the last one did, until it is closed. */
    val result: String? = null,
    val error: String? = null,
) {
    /** Whether anything is to be shown. */
    val visible: Boolean get() = offer != null || result != null || error != null
}

/**
 * What the person is asked when another app hands this one a file. A file waits in [IncomingFiles]; this looks inside it (what it is, how much is in
 * it), offers it in words, and imports it only when the person accepts. A threat file joins the picture on the device exactly as one chosen in the Threats
 * section does; a local-points file becomes a saved set exactly as one chosen in the Points section does. Both go through the same code, so a file means
 * the same thing however it arrived.
 */
@HiltViewModel
class IncomingViewModel @Inject constructor(
    private val incoming: IncomingFiles,
    private val inspector: FileInspector,
    private val threats: ThreatStore,
    private val threatSelection: ThreatSelection,
    private val points: PointSetRepository,
) : ViewModel() {
    internal var worker: CoroutineDispatcher = Dispatchers.IO

    /** What each waiting file turned out to be, by its id, so a file is read once however often the screen redraws. */
    private val looked = MutableStateFlow<Map<Long, FilePreview>>(emptyMap())
    private val local = MutableStateFlow(Local())

    private data class Local(val busy: Boolean = false, val result: String? = null, val error: String? = null)

    val state: StateFlow<IncomingUiState> = combine(incoming.files, looked, local) { files, seen, now ->
        val first = files.firstOrNull()
        IncomingUiState(
            offer = first?.let { offer(it, seen[it.id]) },
            waiting = (files.size - 1).coerceAtLeast(0),
            busy = now.busy, result = now.result, error = now.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IncomingUiState())

    init {
        // Look inside each file as it arrives, off the main thread: a large threat file or a set of thousands of points is not instant.
        viewModelScope.launch {
            incoming.files.collect { files ->
                looked.update { seen -> seen.filterKeys { id -> files.any { it.id == id } } }       // what has gone is forgotten
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

    private fun offer(file: IncomingFile, seen: FilePreview?): IncomingOfferUi = when (file) {
        is IncomingFile.Unreadable -> IncomingOfferUi.Problem(file.name, file.reason)
        is IncomingFile.Received -> when (seen) {
            null -> IncomingOfferUi.Reading(file.name)
            is FilePreview.Threats -> IncomingOfferUi.Threats(file.name, seen.threats.size)
            is FilePreview.Points -> IncomingOfferUi.Points(file.name, seen.setName, seen.count)
            FilePreview.Mission -> IncomingOfferUi.Mission(file.name)
            is FilePreview.Problem -> IncomingOfferUi.Problem(file.name, seen.message)
        }
    }

    /** The person said yes to the file at the front. */
    fun accept() {
        val file = incoming.files.value.firstOrNull() as? IncomingFile.Received ?: return
        if (local.value.busy) return                                                                // a second tap while one is being saved does nothing
        when (val seen = looked.value[file.id]) {
            is FilePreview.Threats -> {
                val ids = threats.addAll(seen.threats)
                ids.lastOrNull()?.let(threatSelection::select)
                incoming.dismiss(file.id)
                local.update { it.copy(result = "Added ${threatCount(seen.threats.size)} from ${file.name}.", error = null) }
            }
            is FilePreview.Points -> {
                local.update { it.copy(busy = true, result = null, error = null) }
                viewModelScope.launch {
                    val outcome = try {
                        withContext(worker) { points.import(file.bytes, file.name) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ImportOutcome.Refused("That file could not be saved.")
                    }
                    incoming.dismiss(file.id)
                    local.update {
                        when (outcome) {
                            is ImportOutcome.Imported -> it.copy(busy = false, result = "Saved ${outcome.set.name} with ${pointCount(outcome.set.points.size)}.")
                            is ImportOutcome.Refused -> it.copy(busy = false, error = outcome.message)
                        }
                    }
                }
            }
            else -> {}                                                                              // a mission, a problem or a file still being read has nothing to accept
        }
    }

    /** The person said no, or closed a file that cannot be opened. The file is forgotten. */
    fun decline() {
        incoming.files.value.firstOrNull()?.let { incoming.dismiss(it.id) }
    }

    /** Closes the result or the error shown after a file was accepted. */
    fun closeResult() = local.update { it.copy(result = null, error = null) }

    private fun threatCount(n: Int) = if (n == 1) "1 threat" else "${withCommas(n.toLong())} threats"

    private fun pointCount(n: Int) = if (n == 1) "1 point" else "${withCommas(n.toLong())} points"

}
