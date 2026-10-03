package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.Doghouses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The diagram that is open: held in memory while it is edited, written back to the database after a pause, and undoable.
 *
 * A drag moves a graphic many times a second; writing each position to the database would be both slow and a flood of sync work, so edits
 * change the in-memory diagram at once (what the map draws) and are saved once they have been still for [SAVE_AFTER_STILL_MS]. A
 * change is never lost to that wait: [flush] writes now, and the app calls it when it goes to the background.
 *
 * Undo is per open diagram and lives only as long as it is open (the plan: "Undo everywhere", every map edit).
 */
class DiagramSession(
    private val repository: DiagramRepository,
    private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow<Diagram?>(null)

    /** The open diagram, as the screens and the map should show it right now (edits not yet saved included). */
    val active: StateFlow<Diagram?> = _active.asStateFlow()

    private val _saveFailed = MutableStateFlow(false)

    /** True while the last attempt to write the open diagram failed: its changes are still here, and the next save tries again. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    private val _undoDepth = MutableStateFlow(0)
    private val _redoDepth = MutableStateFlow(0)

    /** How many edits can be undone, for the toolbar's button. */
    val undoDepth: StateFlow<Int> = _undoDepth.asStateFlow()
    val redoDepth: StateFlow<Int> = _redoDepth.asStateFlow()

    private class Step(val label: String, val before: Diagram, val after: Diagram)

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()
    private var pending: Job? = null
    private var unsaved = false
    private val writing = Mutex()

    /** Held while the open diagram changes (open, close, or a change to one that is not open), so no two of them see each other half done. */
    private val switching = Mutex()

    /** Opens a diagram, saving whatever was open first. False if it is not there. */
    suspend fun open(uuid: String): Boolean = switching.withLock {
        flush()
        val stored = repository.open(uuid) ?: return@withLock false
        val diagram = Doghouses.settle(null, stored)                // as the web's effect does when a diagram is shown
        undo.clear(); redo.clear(); publishDepths()
        _active.value = diagram
        true
    }

    /**
     * Closes the open diagram, saving it first. It is closed even if the save fails (and the failure still reaches the caller): this is called
     * when the person signs out or deletes the diagram, and a diagram that cannot be closed would stay on screen for the next account.
     */
    suspend fun close() {
        switching.withLock {
            try {
                flush()
            } finally {
                pending?.cancel()
                undo.clear(); redo.clear(); publishDepths()
                _active.value = null
                unsaved = false
                _saveFailed.value = false
            }
        }
    }

    /**
     * A change to diagram [id] that is not the person's own edit, such as an analysis that has just come back: it belongs to the diagram it
     * was asked for, whichever is open by now. If that one is open it is changed quietly (as [setQuietly], so undoing a real edit never
     * takes it away); if not, its stored record is. False if there is no such diagram any more. Call it from the thread that edits, as
     * [edit] is: the open diagram is changed without a lock.
     */
    suspend fun update(id: String, change: (Diagram) -> Diagram): Boolean = switching.withLock {
        if (_active.value?.id == id) {
            setQuietly(change)
            return@withLock true
        }
        val stored = repository.open(id) ?: return@withLock false
        val changed = settled(change)(stored)
        if (changed != stored) repository.save(changed)
        true
    }

    /**
     * Applies [change] to the open diagram. [label] names the edit for an undo button ("Move helicopter"). A change that leaves the diagram as
     * it was is not an edit: nothing is recorded or saved.
     */
    fun edit(label: String, change: (Diagram) -> Diagram) {
        val before = _active.value ?: return
        val after = settled(change)(before)
        if (after == before) return
        undo.addLast(Step(label, before, after))
        while (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
        apply(after)
    }

    /**
     * A change that is not an edit to take back, such as the base map the person is looking at: it is applied to the open diagram and to
     * every undo and redo step, so undoing a real edit never brings an old one back. It is saved with the diagram like any other.
     */
    fun setQuietly(change: (Diagram) -> Diagram) {
        val before = _active.value ?: return
        val change = settled(change)
        val after = change(before)
        if (after == before) return
        patchHistory(change)
        apply(after)
    }

    /**
     * [change], then the flight data brought in line with the doghouses if the change altered them: the web's `useDoghouses` effect does the
     * same whenever its doghouses change, so editing a doghouse's heading (or analysing, which makes the standard two) sets the diagram's
     * landing and takeoff headings. Done to each version of the diagram it is applied to, so undo takes the heading back with the edit.
     */
    private fun settled(change: (Diagram) -> Diagram): (Diagram) -> Diagram = { d -> Doghouses.settle(d, change(d)) }

    private fun patchHistory(change: (Diagram) -> Diagram) {
        for (i in undo.indices) undo[i] = undo[i].let { Step(it.label, change(it.before), change(it.after)) }
        for (i in redo.indices) redo[i] = redo[i].let { Step(it.label, change(it.before), change(it.after)) }
    }

    /** Undoes the last edit. Returns its label, or null if there was nothing to undo. */
    fun undo(): String? {
        val step = undo.removeLastOrNull() ?: return null
        redo.addLast(step)
        apply(step.before)
        return step.label
    }

    fun redo(): String? {
        val step = redo.removeLastOrNull() ?: return null
        undo.addLast(step)
        apply(step.after)
        return step.label
    }

    private fun apply(diagram: Diagram) {
        _active.value = diagram
        publishDepths()
        unsaved = true
        pending?.cancel()
        pending = scope.launch {
            delay(SAVE_AFTER_STILL_MS)
            // Nothing here may escape: this scope has no one above it, and an uncaught failure would end the app. The changes are kept
            // and still owed ([saveFailed]); the next flush, or the next edit, tries again and reports to whoever asked.
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun publishDepths() {
        _undoDepth.update { undo.size }
        _redoDepth.update { redo.size }
    }

    /** Writes the open diagram now, if it has changes the database does not: before it is closed, another is opened, or the app is backgrounded. */
    suspend fun flush() {
        pending?.cancel()
        write()
    }

    /** The write itself, which never cancels the pending one (it may *be* the pending one, and a save cut off half-way helps nobody). */
    private suspend fun write() {
        writing.withLock {
            val diagram = _active.value
            if (!unsaved || diagram == null) return
            unsaved = false
            try {
                val saved = repository.save(diagram)
                _saveFailed.value = false
                if (saved.id != diagram.id) adopt(diagram.id, saved)
            } catch (e: Exception) {
                unsaved = true                                  // still owed: the next flush tries again
                _saveFailed.value = true
                throw e
            }
        }
    }

    /** The record was made anew under another id: the open diagram, and every step that can be undone to, take it, keeping edits made meanwhile. */
    private fun adopt(oldId: String, saved: Diagram) {
        val change = { d: Diagram -> d.copy(id = saved.id, savedId = saved.savedId) }
        if (_active.value?.id != oldId) return
        patchHistory(change)
        _active.update { it?.let(change) }
    }

    companion object {
        const val SAVE_AFTER_STILL_MS = 600L
        const val MAX_UNDO = 100
    }
}
