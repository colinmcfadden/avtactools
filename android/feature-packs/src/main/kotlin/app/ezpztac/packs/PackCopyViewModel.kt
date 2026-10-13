package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.CopyIn
import app.ezpztac.data.LibraryCopier
import app.ezpztac.data.LibraryToPack
import app.ezpztac.data.PackFacts
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.missionpacks.PackSentences
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackSummary
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.StopReason
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A pack a copy can go into, as the dialog lists it: [usable] unless it is finished or this person can only view it. */
data class PackChoice(val uuid: String, val name: String, val meta: String, val usable: Boolean, val finished: Boolean)

/**
 * Adding a copy of one of the person's own Library items to a pack (the design's PackDialogs A, the web's AddToPackDialog): [kind] record [uuid], called
 * [name]. [item] is the item it is made as, kept through every try of this dialog, so a try after an answer that was lost finds the first copy.
 */
data class AddToPackUi(
    val kind: RecordKind,
    val uuid: String,
    val name: String,
    val item: String,
    /** The packs to choose from; null while they are asked for. */
    val packs: List<PackChoice>? = null,
    val listError: String? = null,
    val chosen: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

/**
 * What a copy came to, for the frame's toasts: [openPack] when the toast offers *Open pack* (a copy into a pack that is not the one open), [library] the kind
 * (`lz`, `route`, `pointset`) when it offers *Open Library* (a copy saved to the Library).
 */
data class CopyNotice(val words: String, val openPack: String? = null, val library: String? = null)

/**
 * Copies of the person's own Library items into a mission pack: into one chosen in a dialog (from the Library's menu and the LZ/PZ and Routes panels, outside a
 * pack), or straight into the open pack (the Library's *Add to pack* while one is open). One copy at a time, for the account it was asked for: another account
 * puts the dialog away and ends a copy under way. Each copy's item id is kept until it is in, so trying again after an answer that was lost never makes a
 * second in that pack; a refusal that the pack has that item already (a copy since removed, a record kept under a new uuid) takes a new one. The dialog can
 * be put away while its copy is made; what the copy came to is then said in a toast. What a copy came to is said only to its account.
 */
@HiltViewModel
class PackCopyViewModel @Inject constructor(
    private val api: PacksApi,
    private val door: PackDoor,
    private val copier: LibraryCopier,
) : ViewModel() {
    private val _add = MutableStateFlow<AddToPackUi?>(null)
    private val _adding = MutableStateFlow<String?>(null)
    private val _notices = MutableSharedFlow<CopyNotice>(extraBufferCapacity = 8)

    /** The dialog choosing the pack a copy goes into, while it is up. */
    val add: StateFlow<AddToPackUi?> = _add.asStateFlow()

    /** The Library record being added to the open pack now (the Library's *Add to pack* says so on its row), null when none is. */
    val adding: StateFlow<String?> = _adding.asStateFlow()

    /** What a copy came to, in words. */
    val notices: SharedFlow<CopyNotice> = _notices.asSharedFlow()

    /** The account the dialog was opened for. */
    private var askedFor: Int? = null
    private var loads = 0
    private var working: Job? = null

    /** The item each Library record goes into the open pack as ((pack, record) to item), kept until it is in. */
    private val openItems = mutableMapOf<Pair<String, String>, String>()

    /** Where a date is read (the person's zone). A test sets its own. */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }
    internal var now: () -> Instant = { Instant.now() }

    init {
        viewModelScope.launch {
            var before: Int? = door.user.value
            door.user.collect { user ->
                if (user != before) {
                    // Another account (or packs stopping): nothing asked for the last one stays, nothing it began is kept, and a copy under way ends (it
                    // would go on as that account, under whoever is signed in now).
                    working?.cancel()
                    _add.value = null
                    _adding.value = null
                    askedFor = null
                    openItems.clear()
                }
                before = user
            }
        }
    }

    /** Asks which pack a copy of [kind] record [uuid] ([name]) goes into. Not for an imported mission, which a pack cannot hold yet. */
    fun ask(kind: RecordKind, uuid: String, name: String) {
        val user = door.user.value ?: return
        val word = LibraryToPack.KINDS[kind] ?: return
        if (_add.value?.sending == true) return                                                   // the copy under way keeps its dialog
        askedFor = user
        _add.value = AddToPackUi(kind, uuid, name, item = newItem(word))
        loadPacks()
    }

    /** Asks for the packs again (after a failure). The one chosen stays while it can still take a copy; else the first that can is. */
    fun loadPacks() {
        val user = askedFor ?: return
        val asked = _add.value ?: return
        val number = ++loads
        _add.update { it?.copy(listError = null) }
        viewModelScope.launch {
            val listed = try {
                api.packs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (number == loads && current(asked, user)) _add.update { it?.copy(listError = PackMessages.words(PackFailure.of(e))) }
                return@launch
            }
            if (number != loads || !current(asked, user)) return@launch
            val choices = choicesOf(listed, zone(), now())
            _add.update { s ->
                s?.copy(packs = choices, listError = null, chosen = s.chosen?.takeIf { c -> choices.any { it.uuid == c && it.usable } } ?: choices.firstOrNull { it.usable }?.uuid)
            }
        }
    }

    /** Chooses pack [uuid], if it can take a copy. */
    fun choose(uuid: String) {
        _add.update { s -> if (s == null || s.sending || s.packs?.none { it.uuid == uuid && it.usable } != false) s else s.copy(chosen = uuid, error = null) }
    }

    /** The dialog is put away; a copy being made goes on, and what it came to is said in a toast. */
    fun dismiss() {
        _add.value = null
    }

    /**
     * Makes the copy into the pack chosen. While it is made the dialog stays unless put away; what it came to is said in it when it did not go (the item id kept,
     * so trying again is safe), and in a toast when it did, with *Open pack* unless it is the pack open, or when the dialog was put away meanwhile.
     */
    fun addCopy() {
        val asked = _add.value ?: return
        if (asked.sending) return
        val pack = asked.chosen?.let { c -> asked.packs?.firstOrNull { it.uuid == c && it.usable } } ?: return
        val user = askedFor ?: return
        if (working?.isActive == true) {
            _add.value = asked.copy(error = PackPanelViewModel.STILL_WORKING)                         // said where it is seen: a toast is under the dialog
            return
        }
        _add.value = asked.copy(sending = true, error = null)
        working = viewModelScope.launch {
            val answer = attempt(pack.uuid, asked.kind, asked.uuid, asked.item, user)
            if (door.user.value != user) return@launch
            val open = pack.uuid.takeIf { it != door.open.value?.uuid }
            if (answer.done) {
                if (current(asked, user)) _add.value = null
                if (open == null) door.refresh()
                say(user, CopyNotice(answer.words(asked.name, pack.name), openPack = open))
                return@launch
            }
            val words = answer.words(asked.name, pack.name)
            if (current(asked, user)) {
                val word = LibraryToPack.KINDS.getValue(asked.kind)
                _add.update { s -> s?.copy(sending = false, error = words, item = if (answer.newItem) newItem(word) else s.item) }
            } else {
                say(user, CopyNotice("“${asked.name}” was not added to ${pack.name}. $words"))       // the dialog was put away: said in a toast
            }
        }
    }

    /**
     * Adds a copy of [kind] record [uuid] ([name]) to the pack open now (the Library's *Add to pack*). Only while this person can change the pack: one still
     * opening says so; one at a time. The open pack is asked for what is new once it is in, so it shows at once.
     */
    fun addToOpen(kind: RecordKind, uuid: String, name: String) {
        val user = door.user.value ?: return
        val word = LibraryToPack.KINDS[kind] ?: return
        val state = door.open.value ?: return
        val session = state.session
        val packName = (session?.pack?.get("name") as? JsonPrimitive)?.contentOrNull ?: "the pack"
        if (session == null) {
            say(user, CopyNotice(STILL_OPENING))
            return
        }
        if (state.readOnly) {
            say(user, CopyNotice(PackMessages.words(PackFailure(403, "pack_read_only"))))
            return
        }
        if (working?.isActive == true) {
            say(user, CopyNotice(PackPanelViewModel.STILL_WORKING))
            return
        }
        // Keyed by the record as it is held now, so its row's uuid after a move finds the item id a try before it kept.
        val key = state.uuid to copier.currentId(uuid)
        val item = openItems.getOrPut(key) { newItem(word) }
        _adding.value = uuid
        working = viewModelScope.launch {
            try {
                val answer = attempt(state.uuid, kind, uuid, item, user)
                if (door.user.value != user) return@launch
                if (answer.done || answer.newItem) {
                    openItems.remove(key)
                } else {
                    // The record may have been seen moving during the try: the item stays with it, found by its row's new uuid.
                    val moved = state.uuid to copier.currentId(uuid)
                    if (moved != key) openItems.remove(key)?.let { openItems[moved] = it }
                }
                if (answer.done) {
                    if (door.open.value?.uuid == state.uuid) door.refresh()
                    say(user, CopyNotice(answer.words(name, packName)))
                } else {
                    say(user, CopyNotice("“$name” was not added. ${answer.words(name, packName)}"))
                }
            } finally {
                if (_adding.value == uuid) _adding.value = null
            }
        }
    }

    /** What one try came to: [done] (made, or found made), or the words for why not, and whether the item id is spent ([newItem]: the pack has that id). */
    private class Answer(val done: Boolean, val again: Boolean = false, val refusal: String? = null, val newItem: Boolean = false) {
        fun words(name: String, pack: String) = when {
            !done -> refusal.orEmpty()
            again -> "“$name” was in $pack already, from an earlier try"
            else -> added(name, pack)
        }
    }

    private suspend fun attempt(pack: String, kind: RecordKind, uuid: String, item: String, user: Int): Answer = try {
        when (val outcome = copier.copyIn(pack, kind, uuid, item, user)) {
            is CopyIn.Copied -> Answer(done = true, again = outcome.again)
            else -> Answer(done = false, refusal = refusal(outcome))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when {
            e is ApiException && e.status in 200..299 -> Answer(done = true)                          // made, but its answer could not be read
            e is ApiException && e.code == "item_exists" -> Answer(done = false, refusal = failure(e), newItem = true)
            else -> Answer(done = false, refusal = failure(e))
        }
    }

    private fun newItem(word: String) = PackSentences.packItemId(word, UUID.randomUUID().toString())

    private fun current(asked: AddToPackUi, user: Int) = askedFor == user && door.user.value == user && _add.value?.item == asked.item

    private fun say(user: Int, notice: CopyNotice) {
        if (door.user.value == user) _notices.tryEmit(notice)
    }

    companion object {
        /** A copy that did not go, in words: the Library item gone, changed on another device too, its last change not uploaded or refused, or not written. */
        internal fun refusal(outcome: CopyIn): String = when (outcome) {
            is CopyIn.Copied -> ""
            CopyIn.Gone -> PackMessages.words(PackFailure(404, "source_not_found"))
            CopyIn.Unsaved -> UNSAVED
            CopyIn.Conflicted -> CONFLICTED
            CopyIn.Refused -> REFUSED
            is CopyIn.NotUploaded -> when (outcome.why) {
                StopReason.Offline -> NOT_UP_OFFLINE
                is StopReason.SessionEnded -> NOT_UP_SIGNED_OUT
                else -> NOT_UP
            }
        }

        /** A refusal from the server: an answer that may have been lost says a try again will not add a second copy to that pack (the item id is kept). */
        internal fun failure(e: Exception): String = if (lostAnswer(e)) LOST else PackMessages.words(PackFailure.of(e))

        private fun lostAnswer(e: Exception): Boolean =
            (e is NetworkException && e.requestMayHaveBeenSent) || (e is ApiException && (e.status == 502 || e.status == 504 || e.status in 520..524))

        internal fun added(name: String, pack: String) = "Added a copy of “$name” to $pack"

        /** The packs as the dialog lists them: those still worked on first, then the finished ones (which cannot take a copy until reopened). */
        internal fun choicesOf(packs: List<PackSummary>, zone: ZoneId, now: Instant): List<PackChoice> =
            (packs.filter { it.status != "finished" } + packs.filter { it.status == "finished" }).map { pack ->
                val finished = pack.status == "finished"
                val viewer = pack.role == "viewer"
                val meta = if (finished) {
                    val at = PackFacts.instant(pack.finishedAt)?.let { " ${PackFacts.shortDate(it, zone, now)}" }.orEmpty()
                    "Finished$at. Reopen it to add items."
                } else {
                    val n = pack.audienceCount
                    "$n ${if (n == 1) "member" else "members"} · ${if (viewer) "you can view only" else "you can edit"}"
                }
                PackChoice(pack.uuid, pack.name, meta, usable = !finished && !viewer, finished = finished)
            }

        const val UNSAVED = "Its last change could not be saved, so it was not added. Try again."
        const val NOT_UP_OFFLINE = "Its last changes have not reached the server yet. Try again when you are back online."
        const val NOT_UP_SIGNED_OUT = "Its last changes could not be sent: sign in again, then try again."
        const val NOT_UP = "Its last changes have not reached the server yet. Try again in a moment."
        const val CONFLICTED = "It was changed on another device too. Settle that in the Library, then add it."
        const val REFUSED = "The server would not take its last change, so it cannot go in a pack as it is."
        const val STILL_OPENING = "The pack is still opening. Try again in a moment."
        const val LOST = "The answer did not come back. Try again: it is not added to the same pack twice."
        const val NO_PACKS = "You are not in a pack yet. Make one from the workspace switcher."
        const val NOTE = "A copy goes into the pack. Your Library item stays as it is, and you can update the copy from it later."
    }
}
