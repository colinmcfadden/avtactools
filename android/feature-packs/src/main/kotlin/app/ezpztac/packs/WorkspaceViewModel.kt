package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The workspace: which one edits go to (the Library, or a Mission Pack), and the switcher's lists (the web's `usePacksHome` and the switching half of
 * `usePackWorkspace`). Opening a pack sets the person's own LZ/PZ and set of routes aside ([Parking]) and closing it puts them back, so switching never
 * discards anything; the pack open last is opened again when packs start for the account ([LastPack]). Accepting an invitation to a pack opens it.
 *
 * Switches are made one at a time, in the order asked, and the latest asked for wins over one still waiting: a pack that goes, an invitation link that
 * let the person in and the person's own choice never drop each other without a word. Everything shown belongs to the account packs run for: another
 * account's lists are cleared the moment the account changes, and an answer that comes for an account no longer here is not shown.
 */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val api: PacksApi,
    private val door: PackDoor,
    private val parking: Parking,
    private val lastPack: LastPack,
    library: LibraryTally,
) : ViewModel() {
    private data class Lists(
        val packs: List<PackSummary> = emptyList(),
        val invites: List<Invite> = emptyList(),
        val teams: List<TeamSummary> = emptyList(),
        /** A load has answered, for this account: an empty list now means none. */
        val answered: Boolean = false,
        val error: String? = null,
    )

    /** Where the switcher sends the person next: a pack, or the Library (null); [onlyIfOpen] when it is a way out of that pack only (it has gone). */
    private data class Wanted(val uuid: String?, val onlyIfOpen: String? = null)

    private val lists = MutableStateFlow(Lists())
    private val busy = MutableStateFlow(false)
    private val answerError = MutableStateFlow<String?>(null)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val _dialog = MutableStateFlow<PackDialog?>(null)

    /** A finish or a reopen under way: one at a time. */
    private var acting = false

    /** Asked for, not yet made: the latest only (a conflated channel), worked through one at a time. */
    private val wanted = Channel<Wanted>(Channel.CONFLATED)

    /** Each load's number: only the latest answer is shown, for the account it was asked for. */
    private var loads = 0

    /** The invitations whose pack has been opened for the person (an invitation link's answer is kept until its toast is put away). */
    private val joinedOpened = mutableSetOf<Int>()

    /** The accounts their last pack has been opened again for, in this process: once, at the start, never again over what the person did since. */
    private val restoredFor = mutableSetOf<Int>()

    /** A pack an invitation link let the person in to before packs ran here: opened once they do. */
    private var joinWaiting: String? = null

    /** Where a date is read (the person's zone). A test sets its own. */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    /** What an action came to, in words, for the frame's toasts. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Finishing, reopening or duplicating the open pack, asked about now: the frame shows it, whatever asked. */
    val dialog: StateFlow<PackDialog?> = _dialog.asStateFlow()

    /** Packs run here now (signed in, Mission Packs on, past the gate): the switcher opens. */
    val available: StateFlow<Boolean> = door.user.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, door.user.value != null)

    /** The pack open now, as the frame shows it; null for the Library. This person is who the pack's session says, or the account packs run for. */
    val open: StateFlow<OpenPackUi?> = combine(door.open, lists, door.user) { state, l, user -> state?.let { PackWords.openPack(it, l.packs, it.session?.me ?: user) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val switcher: StateFlow<SwitcherUi> = combine(lists, library.counts, open, busy, answerError) { l, counts, current, working, answered ->
        val rows = (l.packs.filter { it.status != "finished" } + l.packs.filter { it.status == "finished" }).map { pack ->
            val here = current?.takeIf { it.uuid == pack.uuid }?.others ?: 0
            PackRowUi(
                pack.uuid, pack.name, PackWords.packMeta(pack, zone()),
                chips = if (pack.status == "finished") emptyList() else listOfNotNull(here.takeIf { it > 0 }?.let { "$it here now" }, PackWords.counts(pack.itemCounts).ifEmpty { null }),
                finished = pack.status == "finished", current = pack.uuid == current?.uuid,
            )
        }
        // The open pack always has its row: an offline launch has no list yet, and a pack just joined may not be in the last one.
        val openRow = current?.takeIf { c -> rows.none { it.uuid == c.uuid } }?.let { c ->
            PackRowUi(c.uuid, c.name, if (c.finished) "Finished · read-only" else "Open now", listOfNotNull(c.others.takeIf { it > 0 }?.let { "$it here now" }), c.finished, current = true)
        }
        SwitcherUi(
            invites = l.invites.filter { it.status == "pending" }.map { PackWords.inviteUi(it) },
            library = PackWords.library(counts),
            packs = listOfNotNull(openRow) + rows,
            teams = PackWords.teams(l.teams),
            current = current?.uuid,
            loaded = l.answered,
            error = l.error,
            busy = working,
            answerError = answered,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SwitcherUi())

    init {
        // The account packs run for: another one's lists go at once; the new one's are asked for, and its last pack opened again, once, at once.
        viewModelScope.launch {
            var before: Int? = null
            door.user.collect { user ->
                if (user != before) {
                    lists.value = Lists()
                    _dialog.value = null                                                       // a question asked of another account
                }
                before = user
                if (user == null) return@collect
                // The pack to open first, then the lists: opening does not wait for them (the web opens the remembered pack at once too). A pack an
                // invitation link let them in to meanwhile is the one opened, not the one remembered.
                val joined = joinWaiting.also { joinWaiting = null }
                val remembered = if (restoredFor.add(user)) lastPack.id(user) else null
                (joined ?: remembered)?.let { wanted.trySend(Wanted(it)) }
                refresh()
            }
        }
        // Switches, one at a time, in the order asked.
        viewModelScope.launch {
            for (next in wanted) make(next)
        }
        // A question about a pack goes with it (another opened, or the Library): one whose copy is being made stays for its answer.
        viewModelScope.launch {
            open.map { it?.uuid }.distinctUntilChanged().collect { uuid ->
                _dialog.update { asked -> asked?.takeIf { it.uuid == uuid || (it as? PackDialog.Duplicate)?.sending == true } }
            }
        }
        // A pack that has gone (deleted, or no longer this person's to see) takes the person back to the Library at once, told why.
        viewModelScope.launch {
            open.distinctUntilChangedBy { it?.uuid to it?.status }.collect { now ->
                if (now?.status != PackStatus.GONE) return@collect
                _notices.tryEmit("${now.name} is no longer available to you.")
                wanted.trySend(Wanted(null, onlyIfOpen = now.uuid))
                refresh()
            }
        }
    }

    /** Asks for the packs, invitations and teams again: the switcher does each time it opens, since someone may have added this person meanwhile. */
    fun refresh() {
        val user = door.user.value ?: return
        val number = ++loads
        viewModelScope.launch {
            val answer = try {
                coroutineScope {
                    val p = async { api.packs() }
                    val i = async { api.invites() }
                    val t = async { api.teams() }
                    Lists(p.await(), i.await(), t.await(), answered = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What was there stays: a list that could not be refreshed is still the last one known.
                lists.value.copy(error = PackMessages.words(PackFailure.of(e)))
            }
            // Only the latest load, and only for the account it was asked for.
            if (number == loads && door.user.value == user) lists.value = answer
        }
    }

    /** The switcher has opened: its lists are asked for again (someone may have added this person meanwhile), and an old answer's failure is let go. */
    fun switcherOpened() {
        answerError.value = null
        refresh()
    }

    /** Opens [uuid], or the Library for null: asked for, and made once what is being made now is done. */
    fun switchTo(uuid: String?) {
        wanted.trySend(Wanted(uuid))
    }

    /**
     * Makes one switch. Leaving the Library sets the person's own documents aside first, and nothing more happens if they could not be written; coming back
     * to it puts them back, each on its own. From one pack to another the pack being left is written into first (`PackWorkspace`). What is open afterwards
     * is remembered for the account; a pack that did not open (packs stopped meanwhile) gives the person's own documents back.
     */
    private suspend fun make(next: Wanted) {
        val user = door.user.value ?: return                                                    // packs do not run here now: nothing to open or close
        val current = door.open.value?.uuid
        if (next.onlyIfOpen != null && current != next.onlyIfOpen) return                       // the pack that went is not open any more
        if (next.uuid == current) return
        busy.value = true
        try {
            if (current == null && next.uuid != null) {
                try {
                    parking.park()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _notices.tryEmit("Your LZ/PZ could not be saved, so the pack was not opened. It is still open here.")
                    return
                }
            }
            val failed = try {
                if (next.uuid != null) door.openPack(next.uuid) else door.closePack()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            // A pack item's last change that could not be written is said as such: the switch itself happened (PackWorkspace finishes it first).
            if (failed != null) _notices.tryEmit("The last change to an item of the pack could not be saved.")
            if (door.open.value == null) {
                parking.unpark()                                                                  // in the Library, or the pack did not open
            } else if (current == null) {
                // Something of the person's own opened while the pack did (the launch's own restore): it is set aside too, never edited inside the pack.
                try {
                    parking.park()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _notices.tryEmit("Your LZ/PZ could not be saved. It is still open, outside the pack.")
                }
            }
            if (door.user.value == user) lastPack.remember(user, door.open.value?.uuid)
        } finally {
            busy.value = false
        }
    }

    /**
     * Accepts invitation [id] (and opens the pack it let them in to), or declines it. A failure is said in the switcher ([SwitcherUi.answerError]): on a
     * phone it is a sheet over the frame's toasts. [onJoined] is told when an accept let the person in to a pack, before what was joined is said, so the
     * switcher can close and the toast be seen.
     */
    fun answer(id: Int, accept: Boolean, onJoined: () -> Unit = {}) {
        if (busy.value) return
        busy.value = true
        answerError.value = null
        viewModelScope.launch {
            try {
                if (accept) {
                    val answer = api.accept(id)
                    answer.pack?.let { joinedOpened += id; switchTo(it.uuid); onJoined() }
                    _notices.tryEmit(PackMessages.joinedMessage(answer))
                } else {
                    api.decline(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                answerError.value = PackMessages.words(PackFailure.of(e))
            } finally {
                busy.value = false
                refresh()
            }
        }
    }

    // -- Finishing, reopening and duplicating the open pack (the web's usePackWorkspace) --------------------------------------------------

    /** Asks the owner whether to finish the open pack. */
    fun askFinish() {
        val pack = open.value?.takeIf { it.owner && !it.finished && it.status != PackStatus.GONE } ?: return
        _dialog.value = PackDialog.Finish(pack.uuid, pack.name)
    }

    /** Asks the owner whether to reopen the open pack. */
    fun askReopen() {
        val pack = open.value?.takeIf { it.owner && it.finished && it.status != PackStatus.GONE } ?: return
        _dialog.value = PackDialog.Reopen(pack.uuid, pack.name)
    }

    /** Asks anyone in the open pack what to call a copy of it ("OP DK 2" to start with). */
    fun askDuplicate() {
        val pack = open.value?.takeIf { it.status != PackStatus.GONE } ?: return
        _dialog.value = PackDialog.Duplicate(pack.uuid, pack.name, PackDialog.copyName(pack.name))
    }

    /** The question is put away; not while a copy is being made, whose answer is still to come. */
    fun dismissDialog() {
        if ((_dialog.value as? PackDialog.Duplicate)?.sending == true) return
        _dialog.value = null
    }

    /**
     * Finishes the pack asked about (the dialog goes first, as on the web). This person's changes go first: what the editors hold is written, and what is on
     * its way is waited for a while, since a finished pack refuses it; one still not sent then stops it, in words. The pack's own state is asked for after
     * (its finish is an event in its log), and the lists.
     */
    fun finish() {
        val asked = _dialog.value as? PackDialog.Finish ?: return
        _dialog.value = null
        act { user ->
            try {
                door.flushOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                say(user, "The last change to ${asked.pack} could not be saved, so it was not finished.")
                return@act
            }
            // Settled only while it is still the pack open, held on the device (a client being replaced holds nothing yet), with none of this person's
            // edits waiting; leaving it, or packs stopping, is not its changes having arrived (a closed pack's client still drains them).
            val settled = withTimeoutOrNull(SETTLE_MILLIS) { door.open.first { it == null || it.uuid != asked.uuid || it.session?.pending?.isEmpty() == true } }
            val now = door.open.value
            if (now?.uuid != asked.uuid || now.session == null || door.user.value != user) {
                say(user, "${asked.pack} was not finished: it is not open here now.")
                return@act
            }
            if (settled == null || now.session?.pending?.isEmpty() != true) {
                say(user, "Your last changes are still on their way to ${asked.pack}. Finish it once they are sent.")
                return@act
            }
            api.finish(asked.uuid, user)
            door.refresh()
            say(user, "${asked.pack} is finished and read-only for everyone.")
        }
    }

    /** Reopens the pack asked about, for its editors to change again; its state and the lists are asked for after. */
    fun reopen() {
        val asked = _dialog.value as? PackDialog.Reopen ?: return
        _dialog.value = null
        act { user ->
            api.reopen(asked.uuid, user)
            door.refresh()
            say(user, "${asked.pack} is open for editing again.")
        }
    }

    /**
     * Makes a copy of the pack asked about, named [typed] (trimmed, in capitals), and opens it once made: "Made NAME.". While it is made the dialog stays;
     * what will not do is said in it. An answer lost on the way may have made the copy (the call is not repeatable), so it is said to look first.
     */
    fun duplicate(typed: String) {
        val asked = _dialog.value as? PackDialog.Duplicate ?: return
        if (asked.sending) return
        val name = typed.trim().uppercase()
        PackDialog.refusal(name)?.let { refused ->
            _dialog.value = asked.copy(name = typed, error = refused)
            return
        }
        val user = door.user.value ?: return
        _dialog.value = asked.copy(name = typed, sending = true, error = null)
        viewModelScope.launch {
            try {
                val made = api.duplicate(asked.uuid, name, user)
                if (door.user.value != user) return@launch
                _dialog.value = null
                switchTo(made.uuid)
                _notices.tryEmit("Made ${made.name}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (door.user.value != user) return@launch
                // Made, but its answer could not be read: it is there, and is not to be made again.
                if (e is ApiException && e.status in 200..299) {
                    _dialog.update { d -> if (d is PackDialog.Duplicate && d.uuid == asked.uuid) null else d }
                    _notices.tryEmit(COPY_MADE)
                    return@launch
                }
                val words = if (lostAnswer(e)) LOST_COPY else PackMessages.words(PackFailure.of(e))
                _dialog.update { d -> if (d is PackDialog.Duplicate && d.uuid == asked.uuid) d.copy(sending = false, error = words) else d }
            } finally {
                refresh()
            }
        }
    }

    /** The copy's name is being typed again: what was said of the last one goes, as on the web. */
    fun duplicateEdited() {
        _dialog.update { d -> if (d is PackDialog.Duplicate && !d.sending && d.error != null) d.copy(error = null) else d }
    }

    /** Runs a finish or a reopen for the account here now, one at a time; what it came to is said, and the lists are asked for again. */
    private fun act(block: suspend (user: Int) -> Unit) {
        val user = door.user.value ?: return
        if (acting) {
            _notices.tryEmit(PackPanelViewModel.STILL_WORKING)
            return
        }
        acting = true
        viewModelScope.launch {
            try {
                block(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(user, PackMessages.words(PackFailure.of(e)))
            } finally {
                acting = false
                refresh()
            }
        }
    }

    /** Says [words], if the account they are about is still the one here. */
    private fun say(user: Int, words: String) {
        if (door.user.value == user) _notices.tryEmit(words)
    }

    /** An invitation link let the person in to pack [uuid] (`InviteState.Joined`, invitation [inviteId]): it opens, as the web's does, once. */
    fun openJoined(inviteId: Int, uuid: String) {
        if (!joinedOpened.add(inviteId)) return
        if (door.user.value == null) {
            joinWaiting = uuid                                                                    // packs do not run yet: once they do
            return
        }
        refresh()
        switchTo(uuid)
    }
}

/** How long a finish waits for this person's changes to reach the pack before it says they are still on their way. */
private const val SETTLE_MILLIS = 10_000L

/** A copy whose answer did not come back: it may have been made (the call is not repeatable). */
internal const val LOST_COPY = "The answer did not come back, so the copy may have been made. Look in the switcher before trying again."

/** A copy made whose answer could not be read: it is there. */
internal const val COPY_MADE = "The copy was made. Open it from the switcher."

/**
 * An answer that may have been lost after the server made the copy: no answer after the request went, or a gateway's (the server is reached through a proxy, which
 * answers on its own when the server is slow or its connection drops).
 */
private fun lostAnswer(e: Exception): Boolean =
    (e is NetworkException && e.requestMayHaveBeenSent) || (e is ApiException && (e.status == 502 || e.status == 504 || e.status in 520..524))
