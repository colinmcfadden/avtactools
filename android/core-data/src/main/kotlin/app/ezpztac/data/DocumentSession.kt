package app.ezpztac.data

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

/** Where a [DocumentSession]'s document is read from and written to. */
interface DocumentStore<D : Any> {
    /** The document of record [uuid], or null if there is none (deleted, or never here). */
    suspend fun open(uuid: String): D?

    /**
     * Writes [document] and returns it as it is now stored: the same one, unless its record was deleted meanwhile and the work was kept under a new
     * identity, in which case that is returned and the session adopts it.
     */
    suspend fun save(document: D): D

    /** The document of record [uuid] has been closed: anything held for it while it was open (a mission's file) can go. */
    fun release(uuid: String) {}
}

/**
 * The document that is open (an LZ diagram, a set of routes): held in memory while it is edited, written back to the database after a pause, and
 * undoable.
 *
 * A drag moves a graphic many times a second; writing each position to the database would be both slow and a flood of sync work, so edits
 * change the in-memory document at once (what the map draws) and are saved once they have been still for [SAVE_AFTER_STILL_MS]. A
 * change is never lost to that wait: [flush] writes now, and the app calls it when it goes to the background.
 *
 * Undo is per open document and lives only as long as it is open (the plan: "Undo everywhere", every map edit).
 *
 * @param idOf the record identity a document is saved under
 * @param tidy brings a document into line with itself after a change, given the one it was before (null on open): a diagram's flight data follows its
 *   doghouses. Applied to each version a change is applied to, so undo takes what followed an edit back with it.
 * @param reidentify the document under the identity [store] gave it when it kept the work under a new record
 */
open class DocumentSession<D : Any>(
    private val store: DocumentStore<D>,
    private val scope: CoroutineScope,
    private val idOf: (D) -> String,
    private val tidy: (before: D?, after: D) -> D = { _, after -> after },
    private val reidentify: (D, saved: D) -> D,
) {
    private val _active = MutableStateFlow<D?>(null)

    /** The open document, as the screens and the map should show it right now (edits not yet saved included). */
    val active: StateFlow<D?> = _active.asStateFlow()

    private val _saveFailed = MutableStateFlow(false)

    /** True while the last attempt to write the open document failed: its changes are still here, and the next save tries again. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    private val _undoDepth = MutableStateFlow(0)
    private val _redoDepth = MutableStateFlow(0)

    /** How many edits can be undone, for the toolbar's button. */
    val undoDepth: StateFlow<Int> = _undoDepth.asStateFlow()
    val redoDepth: StateFlow<Int> = _redoDepth.asStateFlow()

    private class Step<D>(val label: String, val before: D, val after: D, val key: Any? = null)

    private val undo = ArrayDeque<Step<D>>()
    private val redo = ArrayDeque<Step<D>>()
    private var pending: Job? = null
    private var unsaved = false
    private val writing = Mutex()

    /** Held while the open document changes (open, close, or a change to one that is not open), so no two of them see each other half done. */
    private val switching = Mutex()

    /** Opens a document, saving whatever was open first. False if it is not there. */
    suspend fun open(uuid: String): Boolean = switching.withLock {
        flush()
        val stored = store.open(uuid) ?: return@withLock false
        val document = tidy(null, stored)
        undo.clear(); redo.clear(); publishDepths()
        _active.value = document
        true
    }

    /**
     * Closes the open document, saving it first. It is closed even if the save fails (and the failure still reaches the caller): this is called
     * when the person signs out or deletes the document, and one that cannot be closed would stay on screen for the next account.
     */
    suspend fun close() {
        switching.withLock {
            val closing = _active.value?.let(idOf)
            try {
                flush()
            } finally {
                closing?.let(store::release)
                pending?.cancel()
                undo.clear(); redo.clear(); publishDepths()
                _active.value = null
                unsaved = false
                _saveFailed.value = false
            }
        }
    }

    /**
     * A change to document [id] that is not the person's own edit, such as an analysis that has just come back: it belongs to the document it
     * was asked for, whichever is open by now. If that one is open it is changed quietly (as [setQuietly], so undoing a real edit never
     * takes it away); if not, its stored record is. False if there is no such document any more. Call it from the thread that edits, as
     * [edit] is: the open document is changed without a lock.
     */
    suspend fun update(id: String, change: (D) -> D): Boolean = switching.withLock {
        if (_active.value?.let(idOf) == id) {
            setQuietly(change)
            return@withLock true
        }
        val stored = store.open(id) ?: return@withLock false
        val changed = settled(change)(stored)
        if (changed != stored) store.save(changed)
        true
    }

    /**
     * Applies [change] to the open document. [label] names the edit for an undo button ("Move helicopter"). A change that leaves the document as
     * it was is not an edit: nothing is recorded or saved.
     *
     * Edits made with the same [coalesce] key, one straight after another, are **one step** to undo: a drag changes the document many times a second, and the person
     * who drags a helicopter wants one undo to put it back where it was, not a hundred. Each such change must say where the thing now is (not how far it has moved
     * since the last), and one that brings the document back to what it was before the first takes the step away. Null is no merging.
     */
    fun edit(label: String, coalesce: Any? = null, change: (D) -> D) {
        val before = _active.value ?: return
        val after = settled(change)(before)
        if (after == before) return
        val last = undo.lastOrNull()
        if (coalesce != null && last != null && last.key == coalesce) {
            if (after == last.before) undo.removeLast() else undo[undo.lastIndex] = Step(last.label, last.before, after, coalesce)
        } else {
            undo.addLast(Step(label, before, after, coalesce))
            while (undo.size > MAX_UNDO) undo.removeFirst()
        }
        redo.clear()
        apply(after)
    }

    /**
     * A change that is not an edit to take back, such as the base map the person is looking at: it is applied to the open document and to
     * every undo and redo step, so undoing a real edit never brings an old one back. It is saved with the document like any other.
     */
    fun setQuietly(change: (D) -> D) {
        val before = _active.value ?: return
        val change = settled(change)
        val after = change(before)
        if (after == before) return
        patchHistory(change)
        apply(after)
    }

    /** [change], then [tidy]: done to each version of the document it is applied to, so undo takes what followed the edit back with it. */
    private fun settled(change: (D) -> D): (D) -> D = { d -> tidy(d, change(d)) }

    private fun patchHistory(change: (D) -> D) {
        for (i in undo.indices) undo[i] = undo[i].let { Step(it.label, change(it.before), change(it.after), it.key) }
        for (i in redo.indices) redo[i] = redo[i].let { Step(it.label, change(it.before), change(it.after), it.key) }
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

    private fun apply(document: D) {
        _active.value = document
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

    /** Writes the open document now, if it has changes the database does not: before it is closed, another is opened, or the app is backgrounded. */
    suspend fun flush() {
        pending?.cancel()
        write()
    }

    /** The write itself, which never cancels the pending one (it may *be* the pending one, and a save cut off half-way helps nobody). */
    private suspend fun write() {
        writing.withLock {
            val document = _active.value
            if (!unsaved || document == null) return
            unsaved = false
            try {
                val saved = store.save(document)
                _saveFailed.value = false
                if (idOf(saved) != idOf(document)) adopt(idOf(document), saved)
            } catch (e: Exception) {
                unsaved = true                                  // still owed: the next flush tries again
                _saveFailed.value = true
                throw e
            }
        }
    }

    /**
     * The sync engine gave the record [oldId] a new identity, [newId] (the server held the old one as a deleted record, so the work went up again as a new one). If that
     * is the open document it takes the new id, keeping what has not been saved yet; otherwise a save would find no record under the old id and keep the work as one more
     * new record, and every sync would then make another.
     */
    suspend fun follow(oldId: String, newId: String) {
        switching.withLock {
            writing.withLock {
                if (_active.value?.let(idOf) != oldId) return
                val moved = store.open(newId) ?: return
                adopt(oldId, moved)
            }
        }
    }

    /** The record was made anew under another id: the open document, and every step that can be undone to, take it, keeping edits made meanwhile. */
    private fun adopt(oldId: String, saved: D) {
        val change = { d: D -> reidentify(d, saved) }
        if (_active.value?.let(idOf) != oldId) return
        patchHistory(change)
        _active.update { it?.let(change) }
    }

    companion object {
        const val SAVE_AFTER_STILL_MS = 600L
        const val MAX_UNDO = 100
    }
}
