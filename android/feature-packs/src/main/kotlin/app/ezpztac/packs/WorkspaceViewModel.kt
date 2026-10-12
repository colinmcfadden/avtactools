package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.Invite
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

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
                if (user != before) lists.value = Lists()
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
