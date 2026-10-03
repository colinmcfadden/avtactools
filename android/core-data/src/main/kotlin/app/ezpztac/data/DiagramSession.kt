package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.Doghouses
import kotlinx.coroutines.CoroutineScope

/**
 * The diagram that is open: held in memory while it is edited, written back to the database after a pause, and undoable ([DocumentSession] says
 * how, and why the wait).
 *
 * What is the diagram's own is that its flight data follows its doghouses, as the web's `useDoghouses` effect does whenever they change (editing a
 * doghouse's heading, or analysing, which makes the standard two, sets the landing and takeoff headings): done to each version of the diagram a change
 * is applied to, so undo takes the heading back with the edit.
 */
class DiagramSession(
    repository: DiagramRepository,
    scope: CoroutineScope,
) : DocumentSession<Diagram>(
    store = object : DocumentStore<Diagram> {
        override suspend fun open(uuid: String): Diagram? = repository.open(uuid)
        override suspend fun save(document: Diagram): Diagram = repository.save(document)
    },
    scope = scope,
    idOf = { it.id },
    tidy = { before, after -> Doghouses.settle(before, after) },
    reidentify = { d, saved -> d.copy(id = saved.id, savedId = saved.savedId) },
) {
    companion object {
        const val SAVE_AFTER_STILL_MS = DocumentSession.SAVE_AFTER_STILL_MS
        const val MAX_UNDO = DocumentSession.MAX_UNDO
    }
}
