package app.ezpztac.data

import app.ezpztac.network.ApiClient
import app.ezpztac.network.PackItemReply
import app.ezpztac.network.PackSource
import app.ezpztac.network.Saved
import app.ezpztac.network.copyIntoPack
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.StopReason
import app.ezpztac.sync.SyncRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/** Uploads what the Library has waiting, now, for account [user], if this device may: signed in as them, its plans theirs, the app not too old. */
fun interface LibraryUpload {
    suspend fun upload(user: Int): Uploaded
}

/**
 * What an upload came to: whether it [ran], why it stopped short, the records it gave a new identity (old uuid to new), the records another device had changed
 * too ([conflicts]: each became the server's version, this device's kept as a copy beside it), and the records whose change the server refused for good
 * ([blocked]).
 */
data class Uploaded(
    val ran: Boolean,
    val stopped: StopReason? = null,
    val recreated: Map<String, String> = emptyMap(),
    val conflicts: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
)

/** Writes the open document now if it is [uuid] of [kind], and says the uuid it is held under afterwards (one deleted elsewhere is kept under a new one). */
fun interface OpenWrites {
    suspend fun write(kind: RecordKind, uuid: String): String
}

/** The open LZ/PZ and route set: written now if [uuid] is the one open, which may then be held under a new id (its record was deleted elsewhere). */
class SessionWrites(private val diagrams: DiagramSession, private val routes: RouteSession) : OpenWrites {
    override suspend fun write(kind: RecordKind, uuid: String): String = when (kind) {
        RecordKind.LZ -> diagrams.writeIfOpen(uuid) { it.id }
        RecordKind.ROUTE -> routes.writeIfOpen(uuid) { it.id }
        else -> uuid                                                                              // a set of points is written as it is changed
    }

    private suspend fun <D : Any> DocumentSession<D>.writeIfOpen(uuid: String, idOf: (D) -> String): String {
        if (active.value?.let(idOf) != uuid) return uuid
        flush()
        return active.value?.let(idOf) ?: uuid
    }
}

/** What a copy into a pack came to. */
sealed interface CopyIn {
    /** In the pack as [name]; [again] when the server had made it already (the same copy, asked again after an answer that was lost). */
    data class Copied(val name: String, val again: Boolean) : CopyIn

    /** It is no longer in the Library. */
    data object Gone : CopyIn

    /**
     * Its last change has not reached the server, so the server cannot copy it: [why] the upload stopped (no signal, the session ended), or none when it [ran] and
     * this record still did not go (refused, or changed again meanwhile), or when none could run (another account's plans here).
     */
    data class NotUploaded(val why: StopReason?, val ran: Boolean) : CopyIn

    /** It is open, and its last change could not be written. */
    data object Unsaved : CopyIn

    /** Another device changed it too: the record is now that version, and this device's is a copy beside it to settle in the Library first. */
    data object Conflicted : CopyIn

    /** The server will not take its last change (an outbox entry refused for good), so it cannot go in a pack as it is. */
    data object Refused : CopyIn
}

/** Copying one of the person's own Library records into a mission pack ([LibraryToPack]). */
interface LibraryCopier {
    /** Copies record [uuid] of [kind] into pack [pack] as item [item], for account [user]. A refusal from the server is thrown, as the call throws it. */
    suspend fun copyIn(pack: String, kind: RecordKind, uuid: String, item: String, user: Int): CopyIn

    /** The uuid record [uuid] is held under now: another, once a copy has seen it kept under a new identity (deleted elsewhere). */
    fun currentId(uuid: String): String = uuid

    /**
     * Before a copy is updated from its original, record [uuid] of [kind]: its last change written and on the server (null), or why not. One this device does not
     * hold is left to the server, which says what it has of it.
     */
    suspend fun readyOriginal(kind: RecordKind, uuid: String, user: Int): CopyIn? = null
}

/**
 * Copies a Library record into a mission pack (the web's `copyFromLibrary`). The server copies the version it holds, so the record's last change goes
 * first: the open document is written, and a record with changes the server has not had is uploaded and then looked at again. What the person sees is
 * what goes in; a record whose change cannot be uploaded (no signal) is not copied in an older version. The copy is asked for by the record's own
 * identity (`client_uuid`), with an item id the caller keeps through every try, so a try after an answer that was lost finds the first copy (200)
 * rather than making a second. The server writes the history's sentence (it knows who is asking).
 *
 * A record seen moving to a new uuid (its open document kept under a new id, or the upload recreating it) is remembered here ([currentId]), so a try made
 * again with the uuid it was asked for finds it. One the upload found changed on another device too is not copied: the record is now the other device's
 * version, which the person has not seen.
 */
class LibraryToPack(
    private val records: SyncRepository,
    private val open: OpenWrites,
    private val upload: LibraryUpload,
    private val copy: suspend (pack: String, source: PackSource, item: String, user: Int) -> Saved<PackItemReply>,
) : LibraryCopier {
    /** Old uuid to new, for the records seen moving. */
    private val moved = ConcurrentHashMap<String, String>()

    override fun currentId(uuid: String): String {
        var id = uuid
        repeat(MAX_MOVES) { id = moved[id] ?: return id }
        return id
    }

    private fun move(from: String, to: String) {
        if (from != to) moved[from] = to
    }

    override suspend fun copyIn(pack: String, kind: RecordKind, uuid: String, item: String, user: Int): CopyIn {
        val word = requireNotNull(KINDS[kind]) { "A $kind cannot go in a pack" }
        return when (val ready = ready(kind, uuid, user)) {
            is Ready.Not -> ready.why
            Ready.Missing -> CopyIn.Gone
            is Ready.Up -> {
                val saved = copy(pack, PackSource.ClientUuid(word, ready.id), item, user)
                CopyIn.Copied(saved.value.item.name, again = !saved.created)
            }
        }
    }

    override suspend fun readyOriginal(kind: RecordKind, uuid: String, user: Int): CopyIn? = (ready(kind, uuid, user) as? Ready.Not)?.why

    /** Where record [uuid] stands for the server: up under an id, not on this device, or not up and why. */
    private sealed interface Ready {
        data class Up(val id: String) : Ready
        data object Missing : Ready
        data class Not(val why: CopyIn) : Ready
    }

    /** The record's last change written and uploaded, as a copy or an update needs it: the server works from the version it holds. */
    private suspend fun ready(kind: RecordKind, uuid: String, user: Int): Ready {
        val asked = currentId(uuid)
        var id = try {
            open.write(kind, asked)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Ready.Not(CopyIn.Unsaved)
        }
        move(asked, id)
        var record = records.record(kind, id) ?: return Ready.Missing
        if (!uploaded(record)) {
            val result = upload.upload(user)
            result.recreated.forEach { (from, to) -> move(from, to) }
            if (id in result.conflicts) return Ready.Not(CopyIn.Conflicted)
            id = result.recreated[id] ?: id
            record = records.record(kind, id) ?: return Ready.Missing
            if (!uploaded(record)) return Ready.Not(if (id in result.blocked) CopyIn.Refused else CopyIn.NotUploaded(result.stopped, result.ran))
        }
        return Ready.Up(id)
    }

    companion object {
        /** What can go in a pack, as the server names it: an imported mission cannot yet. */
        val KINDS = mapOf(RecordKind.LZ to "lz", RecordKind.ROUTE to "route", RecordKind.POINT_SET to "pointset")

        /** The server has this version: it has the record, and nothing changed here since. A conflict copy counts once it is up. */
        fun uploaded(record: LocalRecord): Boolean = record.serverId != null && !record.dirty && !record.deleted

        /** How many moves of one record are followed: a record cannot move more often than it is copied, and a loop is never followed for ever. */
        private const val MAX_MOVES = 16
    }
}

/** The copy itself, made for the account asked for: never sent under another account's session (`asUser`), whoever is signed in by the time it goes. */
internal fun copyCall(api: ApiClient): suspend (pack: String, source: PackSource, item: String, user: Int) -> Saved<PackItemReply> =
    { pack, source, item, user -> api.copyIntoPack(pack, source, item, asUser = user) }
