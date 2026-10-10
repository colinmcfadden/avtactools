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
 *
 * A mission pack's LZ/PZ opens here too, under an id naming the pack and the item ([app.ezpztac.missionpacks.PackRef]), so every editor works on it
 * as on any diagram; it is read from and written to [packs] ([PackItemStore]), never the library.
 */
class DiagramSession(
    repository: DiagramRepository,
    scope: CoroutineScope,
    /** Where a mission pack's LZ/PZs are read and written. None: an id naming a pack item opens nothing. */
    packs: DocumentStore<Diagram>? = null,
) : DocumentSession<Diagram>(
    store = PackRoutedStore(
        library = object : DocumentStore<Diagram> {
            override suspend fun open(uuid: String): Diagram? = repository.open(uuid)
            override suspend fun save(document: Diagram): Diagram = repository.save(document)
        },
        packs = packs,
        idOf = { it.id },
    ),
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
