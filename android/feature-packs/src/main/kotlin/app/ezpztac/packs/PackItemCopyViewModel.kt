package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.CopyIn
import app.ezpztac.data.LibraryCopier
import app.ezpztac.data.LibraryToPack
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackItemSource
import app.ezpztac.sync.MAX_NAME
import app.ezpztac.sync.cutToCharacters
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The calls updating a copy from its original and saving a pack's item make, each for the account asked for. */
interface PackItemCalls {
    /** The item as the server has it now, with what its original says (`source`: when it changed, what the update would replace). */
    suspend fun source(pack: String, item: String, user: Int): PackItemSource?

    /** Replaces the item's content with its Library original as the server has it. Not repeatable: a lost answer may have done it. */
    suspend fun updateFromOriginal(pack: String, item: String, user: Int)

    /** Saves [data] (an item's, as this person sees it) to their Library as a new record named [name], on the device, queued for the server. */
    suspend fun saveToLibrary(pack: String, kind: String, name: String, data: JsonElement): KeptOutcome
}

/** Updating copy [item] ([name]) of pack [pack] from its Library original ([kind] record [original]): the dialog, and what the server says of the change. */
data class UpdateUi(
    val pack: String,
    val packName: String,
    val item: String,
    val name: String,
    val kind: String,
    val original: String,
    /** What the server says of the original now; null while asked for, or when it could not be had ([failed]). */
    val source: PackItemSource? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    /** Which ask this is: an answer about the item lands only on the dialog it was asked for, never a later one for the same item. */
    val ask: Int = 0,
    /** The account asking, so its own last change in the pack is said as theirs ("your last"). */
    val me: Int? = null,
)

/** Saving pack [pack]'s item [item] to this person's Library, under [name] (the design's "NAME copy" to start with). */
data class SaveCopyUi(
    val pack: String,
    val packName: String,
    val item: String,
    val kind: String,
    val name: String,
    val nameError: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

/**
 * Between a pack's items and the person's own Library, the other way to [PackCopyViewModel]: updating a copy from its Library original (the design's
 * PackDialogs B: the copier only, while the original has changed), and saving any item to the Library (D: anyone in the pack, finished or not). One at a time,
 * for the account asked for, about the pack open: another pack opened, or another account, puts the dialog away.
 *
 * An update is not repeatable, so it goes once this person's own changes are in (the editors' last change written, what is on its way waited for a while, as a
 * finish does) and the original's own last change is on the server (it replaces the copy with the version the server holds). A copy to the Library is made on
 * the device, from the item as this person sees it (the editor's document while it is open in one, a change the pack has not taken yet or is holding
 * back while it is paused included), and works with no signal. **Not like the web**, which asks the server to copy its own version. An answer that
 * comes once its pack is no longer open is said in a toast, its dialog put away.
 */
@HiltViewModel
class PackItemCopyViewModel @Inject constructor(
    private val door: PackDoor,
    private val calls: PackItemCalls,
    private val copier: LibraryCopier,
) : ViewModel() {
    private val _update = MutableStateFlow<UpdateUi?>(null)
    private val _save = MutableStateFlow<SaveCopyUi?>(null)
    private val _notices = MutableSharedFlow<CopyNotice>(extraBufferCapacity = 8)

    val update: StateFlow<UpdateUi?> = _update.asStateFlow()
    val save: StateFlow<SaveCopyUi?> = _save.asStateFlow()
    val notices: SharedFlow<CopyNotice> = _notices.asSharedFlow()

    private var askedFor: Int? = null
    private var working: Job? = null
    private var asks = 0

    /** Where a date is read (the person's zone). A test sets its own. */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }
    internal var now: () -> Instant = { Instant.now() }

    /** How long an update waits for this person's changes to reach the pack. A test sets its own. */
    internal var settleMillis = SETTLE_MILLIS

    init {
        viewModelScope.launch {
            var before: Int? = door.user.value
            door.user.collect { user ->
                if (user != before) {
                    working?.cancel()
                    _update.value = null
                    _save.value = null
                    askedFor = null
                }
                before = user
            }
        }
        // A question about one pack goes with it (another opened, or the Library); one whose answer is still to come stays for it.
        // Only a change of pack counts, never a presence frame: an answer that came back once the pack changed is said, not wiped by the next frame.
        viewModelScope.launch {
            door.open.map { it?.uuid }.distinctUntilChanged().collect { uuid ->
                _update.update { u -> u?.takeIf { it.pack == uuid || it.sending } }
                _save.update { s -> s?.takeIf { it.pack == uuid || it.sending } }
            }
        }
    }

    // -- Update from the original ------------------------------------------------------------------------------------------

    /** Asks whether to update item [item] of the pack open from its Library original, and asks the server what that would replace. */
    fun askUpdate(item: String) {
        val user = door.user.value ?: return
        val state = door.open.value ?: return
        val view = state.item(item) ?: return
        val source = view.info?.get("source") as? JsonObject ?: return
        val original = (source["uuid"] as? JsonPrimitive)?.contentOrNull ?: return
        if (_update.value?.sending == true) return
        askedFor = user
        val asked = UpdateUi(state.uuid, packName(state), item, view.name, view.kind, original, ask = ++asks, me = user)
        _update.value = asked
        viewModelScope.launch {
            val fresh = try {
                calls.source(asked.pack, item, user)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _update.update { u -> if (u?.ask == asked.ask && u.loading) u.copy(loading = false, failed = true) else u }
                return@launch
            }
            _update.update { u -> if (u?.ask == asked.ask && u.loading) u.copy(source = fresh, loading = false) else u }
        }
    }

    fun dismissUpdate() {
        _update.update { if (it?.sending == true) it else null }
    }

    /**
     * Updates the copy asked about. This person's changes go first (a pack that replaced the copy would then take them on top of the original, or refuse
     * them), and the original's own last change; then the server replaces the copy, and the pack is asked for what is new.
     */
    fun confirmUpdate() {
        val asked = _update.value ?: return
        if (asked.sending) return
        val user = askedFor ?: return
        if (working?.isActive == true) {
            _update.value = asked.copy(error = PackPanelViewModel.STILL_WORKING)
            return
        }
        _update.value = asked.copy(sending = true, error = null)
        working = viewModelScope.launch {
            val refused = updateOnce(asked, user)
            if (door.user.value != user) return@launch
            when {
                refused == null -> {
                    _update.value = null
                    door.refresh()
                    say(user, CopyNotice("Updated “${asked.name}” from your Library"))
                }
                // Its pack is no longer open: the dialog goes with it, and why it was not updated is said where it can be seen.
                door.open.value?.uuid != asked.pack -> {
                    _update.update { u -> if (u?.ask == asked.ask) null else u }
                    say(user, CopyNotice("“${asked.name}” was not updated. $refused"))
                }
                else -> _update.update { u -> if (u?.ask == asked.ask) u.copy(sending = false, error = refused) else u }
            }
        }
    }

    /** One update: null when it was made, else why not, in words. */
    private suspend fun updateOnce(asked: UpdateUi, user: Int): String? {
        try {
            door.flushOpen()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return "The last change to it could not be saved, so it was not updated."
        }
        val settled = withTimeoutOrNull(settleMillis) { door.open.first { it == null || it.uuid != asked.pack || it.session?.pending?.isEmpty() == true } }
        val now = door.open.value
        if (now?.uuid != asked.pack || now.session == null) return "${asked.packName} is not open here now."
        if (settled == null || now.session?.pending?.isEmpty() != true) return "Your last changes are still on their way to ${asked.packName}. Update once they are sent."
        val kind = KINDS[asked.kind] ?: return PackMessages.words(PackFailure(400, "unreadable_source"))
        val notReady = try {
            copier.readyOriginal(kind, asked.original, user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return NOT_SENT                                                                            // the device's own store failed: never the app's end
        }
        notReady?.let { return updateRefusal(it) }
        return try {
            calls.updateFromOriginal(asked.pack, asked.item, user)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                e is ApiException && e.status in 200..299 -> null                                    // done, but its answer could not be read
                lostAnswer(e) -> {
                    door.refresh()                                                                     // it may have been made: the pack shows whether
                    LOST_UPDATE
                }
                else -> PackMessages.words(PackFailure.of(e))
            }
        }
    }

    // -- Save a copy to the Library -------------------------------------------------------------------------------------

    /** Asks what to call a copy of item [item] of the pack open, saved to this person's Library ("NAME copy" to start with). */
    fun askSave(item: String) {
        val user = door.user.value ?: return
        val state = door.open.value ?: return
        val view = state.item(item) ?: return
        if (_save.value?.sending == true) return
        askedFor = user
        _save.value = SaveCopyUi(state.uuid, packName(state), item, view.kind, copyName(view.name))
    }

    /** The name as typed, never past 100 characters (one too long can always be made shorter). */
    fun saveName(typed: String) {
        _save.update { s ->
            if (s == null || s.sending) s
            else if (characters(typed) > MAX_NAME && characters(typed) > characters(s.name)) s
            else s.copy(name = typed, nameError = null, error = null)
        }
    }

    fun dismissSave() {
        _save.update { if (it?.sending == true) it else null }
    }

    /** Saves the copy: the editors' last change written first, then the item as this person sees it now, to their Library. */
    fun confirmSave() {
        val asked = _save.value ?: return
        if (asked.sending) return
        val user = askedFor ?: return
        val name = jsTrim(asked.name)
        refusal(name)?.let { why ->
            _save.value = asked.copy(nameError = why)
            return
        }
        if (working?.isActive == true) {
            _save.value = asked.copy(error = PackPanelViewModel.STILL_WORKING)
            return
        }
        val sending = asked.copy(sending = true, error = null, nameError = null)
        _save.value = sending
        working = viewModelScope.launch {
            val refused = saveOnce(asked, name)
            if (door.user.value != user) return@launch
            when {
                refused == null -> {
                    _save.value = null
                    say(user, CopyNotice("Saved “$name” to your Library", library = asked.kind))
                }
                door.open.value?.uuid != asked.pack -> {
                    _save.update { s -> if (s === sending) null else s }
                    say(user, CopyNotice("“$name” was not saved to your Library. $refused"))
                }
                else -> _save.update { s -> if (s === sending) s.copy(sending = false, error = refused) else s }
            }
        }
    }

    private suspend fun saveOnce(asked: SaveCopyUi, name: String): String? {
        try {
            door.flushOpen()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return "The last change to it could not be saved, so no copy was made."
        }
        if (door.open.value?.uuid != asked.pack) return "${asked.packName} is not open here now."
        return try {
            // As this person sees it now: the editor's document while the item is open in one, else the pack's.
            val data = door.seenData(asked.item) ?: return PackMessages.words(PackFailure(404, "item_not_found"))
            when (val kept = calls.saveToLibrary(asked.pack, asked.kind, name, data)) {
                is KeptOutcome.Saved -> null
                is KeptOutcome.NothingToKeep -> if (kept.reason == "empty_point_set") PackMessages.words(PackFailure(400, "empty_point_set")) else CANNOT_SAVE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CANNOT_SAVE
        }
    }

    private fun packName(state: app.ezpztac.missionpacks.PackState) = (state.session?.pack?.get("name") as? JsonPrimitive)?.contentOrNull ?: "the pack"

    private fun say(user: Int, notice: CopyNotice) {
        if (door.user.value == user) _notices.tryEmit(notice)
    }

    companion object {
        private val KINDS = LibraryToPack.KINDS.entries.associate { (kind, word) -> word to kind }

        /** The design's name for a copy: "LZ IBIS (OWEST) copy", cut so the whole fits the server's 100 characters (by characters, never half of one). */
        internal fun copyName(name: String): String = cutToCharacters(name, MAX_NAME - COPY.length).trimEnd() + COPY

        internal fun refusal(name: String): String? = when {
            name.isEmpty() -> "Give it a name."
            characters(name) > MAX_NAME || name.any { it.code < 0x20 || it.code == 0x7F } -> "Give it a name of 1 to 100 characters."
            else -> null
        }

        /** Why the original could not be made ready for an update, in the update's words (P4a's are about adding it). */
        internal fun updateRefusal(outcome: CopyIn): String = when (outcome) {
            CopyIn.Unsaved -> NOT_SAVED
            CopyIn.Conflicted -> CONFLICTED
            CopyIn.Refused -> REFUSED
            else -> PackCopyViewModel.refusal(outcome)                                                   // not uploaded: the same words as a copy's
        }

        private fun lostAnswer(e: Exception): Boolean =
            (e is NetworkException && e.requestMayHaveBeenSent) || (e is ApiException && (e.status == 502 || e.status == 504 || e.status in 520..524))

        private const val COPY = " copy"
        private const val SETTLE_MILLIS = 10_000L
        private val EDGES = Regex("^[\\s\\p{Z}\\x{FEFF}]+|[\\s\\p{Z}\\x{FEFF}]+$")
        private fun jsTrim(text: String) = text.replace(EDGES, "")
        private fun characters(text: String) = text.codePointCount(0, text.length)

        const val LOST_UPDATE = "The answer did not come back, so it may have been updated. Look at it before trying again."
        const val NOT_SAVED = "The last change to your Library version could not be saved, so the copy was not updated. Try again."
        const val NOT_SENT = "Your Library version could not be sent, so the copy was not updated. Try again."
        const val CONFLICTED = "Your Library version was changed on another device too. Settle that in the Library, then update the copy."
        const val REFUSED = "The server would not take the last change to your Library version, so the copy cannot be updated from it."
        const val CANNOT_SAVE = "That item could not be saved to your Library."
    }
}
