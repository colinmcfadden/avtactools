package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.PackFacts
import app.ezpztac.missionpacks.PackHistory
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.PackEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * How far this person has looked in a pack (the web's `usePackSeen`, ported line by line): [seen] is the server's marker, which counts only once the
 * server has answered; [since] is where "new" is measured from, set to [seen] when they start looking and left there while they look, so the dots and
 * "New since you looked" do not vanish under them as the marker moves. Nothing of one pack's carries over to the next.
 */
internal data class Seen(val pack: String? = null, val seen: Long? = null, val since: Long? = null, val looking: Boolean = false) {
    fun next(pack: String?, server: Long?, looking: Boolean): Seen {
        var next = this
        if (next.pack != pack) next = Seen(pack, server, server, looking)
        // The server knowing more (another device) moves the marker; the first it says is where "new" starts.
        if (server != null && (next.seen == null || server > next.seen)) next = next.copy(seen = server, since = next.since ?: server)
        // "New" is measured from where they were when they started looking.
        if (looking != next.looking) next = next.copy(looking = looking, since = if (looking && next.seen != null) next.seen else next.since)
        return next
    }
}

/**
 * The Pack panel (the design's PackItems and PackHistory, the web's PackPanel): the open pack's items by kind, with what changed since this person
 * looked and who is in each, and its history, newest first. Opening an item puts it in its editor ([opened] says which kind, so the frame shows its
 * panel); renaming and removing one changes it for everyone. What happens to the packs and the items open in the editors is said in [notices].
 *
 * Everything shown is the open pack's, for the account packs run for: another account's history is never kept, and a page that answers for a pack
 * or an account no longer here is not shown. Who is here changes many times a second while people point; the history is not worked out again for it.
 */
@HiltViewModel
class PackPanelViewModel @Inject constructor(private val door: PackDoor, private val panel: PackPanelDoor) : ViewModel() {
    private data class History(
        val pack: String? = null,
        val user: Int? = null,
        /** The newest of the log held, oldest first. */
        val events: List<PackEvent> = emptyList(),
        val fetchedTo: Long? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
    )

    private data class Filters(val person: String? = null, val item: String? = null)

    // What the history is told in words from: the pack, its items' names and its members, and who this person is. Not who is here.
    private data class HistoryContext(val pack: String?, val names: Map<String, String> = emptyMap(), val members: List<Pair<Int, String>> = emptyList(), val me: Int? = null)

    /** Where times are read, and what time it is: a test holds them still. */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }
    internal var now: () -> Instant = { Instant.now() }

    /** How long this person has been looking before the marker moves (the web's 1.5 s). */
    internal var seenDelayMs: Long = 1_500

    private val tab = MutableStateFlow(PackTab.ITEMS)
    private val looking = MutableStateFlow(false)
    private val seen = MutableStateFlow(Seen())
    private val history = MutableStateFlow(History())
    private val filters = MutableStateFlow(Filters())
    private val historyWake = Channel<Unit>(Channel.CONFLATED)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val _kept = MutableSharedFlow<CopyNotice>(extraBufferCapacity = 4)
    private val working = MutableStateFlow(false)
    private val keeping = MutableStateFlow<String?>(null)

    /** What happened that the person should hear about, in words, for the frame's toasts. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** An item was opened in its editor: its kind (`lz`, `route`), so the frame shows the panel it is in. */
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    /** What a keep of the edits the pack would not take saved, for a toast that offers the Library ([CopyNotice.library]: the first one's kind). */
    val kept: SharedFlow<CopyNotice> = _kept.asSharedFlow()

    private val openHere = panel.openHere.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val view = combine(door.open, door.user, openHere, seen) { state, user, open, mark -> Inputs(state, user, open, mark) }

    private val historyView: StateFlow<Pair<String?, HistoryUi>> = combine(
        history,
        filters,
        combine(door.open, door.user) { state, user -> contextOf(state, user) }.distinctUntilChanged(),
        seen.map { it.pack to it.since }.distinctUntilChanged(),
    ) { history, filters, context, since ->
        context.pack to historyUi(history.takeIf { it.pack == context.pack && context.pack != null } ?: History(), filters, context, since.second.takeIf { since.first == context.pack })
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null to HistoryUi())

    val state: StateFlow<PackPanelUi> = combine(view, tab, historyView, keeping) { inputs, tab, history, keeping ->
        uiOf(inputs, tab, history).let { ui -> if (keeping != null && keeping == ui.uuid) ui.copy(keepingDropped = true) else ui }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PackPanelUi())

    private data class Inputs(val state: PackState?, val user: Int?, val open: Set<String>, val seen: Seen)

    init {
        // How far this person has looked: the server's marker, once the server has answered (the device's copy of a pack can carry an old one),
        // and where "new" starts.
        viewModelScope.launch {
            combine(door.open, looking) { state, looking -> Triple(state?.uuid, state?.takeIf(::answered)?.session?.let(::seenOf), looking) }
                .distinctUntilChanged()
                .collect { (pack, server, looking) -> seen.update { it.next(pack, server, looking) } }
        }
        // While they look, the marker moves to the newest event after a moment; a failure is tried again at the next change.
        viewModelScope.launch {
            combine(seen, door.open.map { it?.session?.let(::headOf) ?: 0L }) { mark, head -> mark to head }
                .distinctUntilChanged()
                .collectLatest { (mark, head) ->
                    val pack = mark.pack ?: return@collectLatest
                    val already = mark.seen ?: return@collectLatest
                    if (!mark.looking || head <= already) return@collectLatest
                    delay(seenDelayMs)
                    val answer = try {
                        panel.markSeen(pack, head)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@collectLatest
                    }
                    seen.update { if (it.pack == pack) it.copy(seen = maxOf(it.seen ?: 0, answer)) else it }
                }
        }
        // The history is asked for while it is shown, and again each time the pack moves on (never for who is here), one page at a time.
        viewModelScope.launch {
            combine(tab, door.open.map { it?.uuid to it?.session?.let(::headOf) }.distinctUntilChanged(), door.user) { tab, head, user -> Triple(tab, head, user) }
                .distinctUntilChanged()
                .collect { historyWake.trySend(Unit) }
        }
        viewModelScope.launch {
            for (wake in historyWake) syncHistory()
        }
        // A new pack starts on its items, with nothing filtered (the one open when this began keeps what was chosen).
        viewModelScope.launch {
            door.open.map { it?.uuid }.distinctUntilChanged().drop(1).collect {
                tab.value = PackTab.ITEMS
                filters.value = Filters()
            }
        }
        viewModelScope.launch {
            panel.notices.collect { notice ->
                val state = door.open.value
                val stillOpen = state != null && state.uuid == notice.pack && state.status != PackStatus.GONE
                val name = state?.takeIf { it.uuid == notice.pack }?.session?.let(::nameOf)
                PackPanelWords.notice(notice, name, stillOpen)?.let { _notices.tryEmit(it) }
            }
        }
    }

    /** Shows the items or the history. */
    fun selectTab(tab: PackTab) {
        this.tab.value = tab
    }

    /** Whether this person is looking at the panel now (it is the panel shown and in sight, the app in front): only then does "seen" move. */
    fun setLooking(looking: Boolean) {
        this.looking.value = looking
    }

    /** Keeps the history's events by one person ([key], their id; null for everyone). */
    fun filterPerson(key: String?) {
        filters.update { it.copy(person = key) }
    }

    /** Keeps the history's events on one item ([key], its uuid; null for all). */
    fun filterItem(key: String?) {
        filters.update { it.copy(item = key) }
    }

    /** Opens [uuid] in its editor (an LZ/PZ in the LZ/PZ panel, a route set in Routes); a point set is not drawn on the map yet, and says so. */
    fun open(uuid: String) {
        if (!act { openNow(uuid) }) _notices.tryEmit(STILL_WORKING)
    }

    private suspend fun openNow(uuid: String) {
        val item = door.open.value?.item(uuid) ?: return
        if (item.kind != "lz" && item.kind != "route") {
            _notices.tryEmit(POINT_SETS_LATER)
            return
        }
        val opened = try {
            panel.openItem(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // What was open could not be written first (DocumentSession.open keeps it open, and owed): nothing else opens.
            _notices.tryEmit("The last change to what is open could not be saved, so “${item.name}” was not opened.")
            return
        }
        if (opened) _opened.tryEmit(item.kind) else _notices.tryEmit("“${item.name}” could not be opened.")
    }

    /** Closes [uuid] here: out of its editor, still in the pack. */
    fun closeHere(uuid: String) {
        if (!act { closeNow(uuid) }) _notices.tryEmit(STILL_WORKING)
    }

    private suspend fun closeNow(uuid: String) {
        val name = door.open.value?.item(uuid)?.name ?: return
        try {
            panel.closeItem(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _notices.tryEmit("The last change to “$name” could not be saved. It is closed here.")
        }
    }

    /**
     * Renames [uuid] for everyone to [name]. [result] is told words for the field when the name will not do (blank, too long) or another action is
     * still running, or null when the dialog may close: the name is on its way, it was already that, or the pack would not take it (said in a toast).
     */
    fun rename(uuid: String, name: String, result: (String?) -> Unit) {
        if (!act { renameNow(uuid, name, result) }) result(STILL_WORKING)
    }

    private suspend fun renameNow(uuid: String, name: String, result: (String?) -> Unit) {
        val refused = try {
            panel.renameItem(uuid, name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "failed"
        }
        when (refused) {
            null, "same_name" -> result(null)
            "bad_name" -> result(NAME_REFUSED)
            "failed" -> {
                result(null)
                _notices.tryEmit("The name could not be changed. Try again.")
            }
            else -> {
                result(null)
                _notices.tryEmit(PackPanelWords.refused(refused))
            }
        }
    }

    /** Removes [uuid] from the pack, for everyone in it; the history keeps what it was. */
    fun remove(uuid: String) {
        if (!act { removeNow(uuid) }) _notices.tryEmit(STILL_WORKING)
    }

    private suspend fun removeNow(uuid: String) {
        val refused = try {
            panel.removeItem(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "failed"
        }
        when (refused) {
            null -> Unit
            "failed" -> _notices.tryEmit("It could not be removed. Try again.")
            else -> _notices.tryEmit(PackPanelWords.refused(refused))
        }
    }

    // One action at a time: one asked while another runs is said not to be done (by the caller), never run beside it. False when one was running.
    // Whatever an action throws is said, never let reach the app (each catches what it expects; this is the backstop).
    /**
     * Keeps in this person's Library their version of what the open pack would not take (the web's *Save my version to Library*), and says what was
     * saved, with the Library to open. Nothing kept (the library could not be written) says so, and the offer stays.
     */
    fun keepDropped() {
        val state = door.open.value ?: return
        if (state.session?.dropped.isNullOrEmpty()) return
        val pack = state.uuid
        val user = door.user.value
        val started = act {
            keeping.value = pack
            try {
                keepNow(pack, user)
            } finally {
                keeping.value = null
            }
        }
        if (!started) _notices.tryEmit(STILL_WORKING)
    }

    private suspend fun keepNow(pack: String, user: Int?) {
        val told = panel.keepDropped(pack)
        if (door.user.value != user) return
        val stillOpen = door.open.value?.takeIf { it.uuid == pack }
        if (told == null) {
            // Nothing was done. Still open, it is offered again; left meanwhile, its close keeps them and says so.
            if (stillOpen != null) _notices.tryEmit(KEEP_FAILED)
            return
        }
        val saved = told.filterIsInstance<PackNotice.Kept>().flatMap { it.saved }
        if (saved.isNotEmpty()) _kept.tryEmit(CopyNotice(PackPanelWords.keptYours(saved), library = saved.first().kind))
        told.filterIsInstance<PackNotice.NothingToKeep>().forEach { notice -> PackPanelWords.notice(notice, null)?.let { _notices.tryEmit(it) } }
        when {
            told.isEmpty() -> _notices.tryEmit(NOTHING_LEFT)                                            // let go, what they changed being gone
            stillOpen?.session?.dropped?.isNotEmpty() == true -> _notices.tryEmit(KEEP_REST_FAILED)     // some kept, the library stopped part way
        }
    }

    /**
     * Lets go of the edits the open pack [pack] would not take that [ids] names (those the person was shown and asked about), without keeping them. A drop
     * that came since stays. Says whether any went.
     */
    fun discardDropped(pack: String, ids: Set<String>) {
        if (door.open.value?.uuid != pack || ids.isEmpty()) return
        val user = door.user.value
        val started = act {
            val gone = panel.discardDropped(pack, ids)
            if (door.user.value == user) _notices.tryEmit(if (gone > 0) DISCARDED else NOTHING_DISCARDED)
        }
        if (!started) _notices.tryEmit(STILL_WORKING)
    }

    private fun act(block: suspend () -> Unit): Boolean {
        if (!working.compareAndSet(false, true)) return false
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notices.tryEmit("That could not be done. Try again.")
            } finally {
                working.value = false
            }
        }
        return true
    }

    /**
     * Brings the history up to the pack's newest event, a page at a time, for the pack and account it was asked for (PackPanel.jsx's History): a
     * page that says there is more than one page to come is followed by the newest 500, never the oldest; then what came since. Leaving the tab lets
     * it all go, as the web's does.
     */
    private suspend fun syncHistory() {
        val state = door.open.value
        val user = door.user.value
        val session = state?.session
        if (tab.value != PackTab.HISTORY || state == null || user == null) {
            history.value = History()
            return
        }
        if (session == null) return
        val pack = state.uuid
        if (history.value.pack != pack || history.value.user != user) history.value = History(pack, user, loading = true)
        while (true) {
            val held = history.value
            val head = door.open.value?.takeIf { it.uuid == pack }?.session?.let(::headOf) ?: return
            if (held.fetchedTo != null && head <= held.fetchedTo) break
            val since = held.fetchedTo ?: maxOf(0, head - PAGE)
            val page = try {
                val first = panel.events(pack, since, PAGE)
                if (first.hasMore) panel.events(pack, maxOf(since, first.headSeq - PAGE), PAGE) else first
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (stillFor(pack, user)) history.update { it.copy(loading = false, failed = true) }
                return
            }
            if (!stillFor(pack, user)) return
            val events = (held.events + page.events).associateBy { it.seq }.values.sortedBy { it.seq }.takeLast(PAGE)
            val to = maxOf(since, page.cursor, page.events.maxOfOrNull { it.seq } ?: since)
            history.value = held.copy(events = events, fetchedTo = to, loading = false, failed = false)
            if (page.events.isEmpty()) break                                                        // nothing more to be had: never ask without end
        }
    }

    private fun stillFor(pack: String, user: Int): Boolean =
        tab.value == PackTab.HISTORY && door.open.value?.uuid == pack && door.user.value == user && history.value.pack == pack

    private fun uiOf(inputs: Inputs, tab: PackTab, history: Pair<String?, HistoryUi>): PackPanelUi {
        val state = inputs.state ?: return PackPanelUi()
        val session = state.session
        val me = session?.me ?: inputs.user
        if (session == null) {
            return PackPanelUi(uuid = state.uuid, unreachable = state.status == PackStatus.ERROR, subtitle = if (state.status == PackStatus.ERROR) "Cannot reach the pack" else "Opening…")
        }
        val zone = zone()
        val now = now()
        val since = inputs.seen.since?.takeIf { inputs.seen.pack == state.uuid }
        val role = text(session.pack["role"])
        val readOnly = state.readOnly
        // Who is here: everyone the live service says has it open, this person included (the design's "2 here now" counts them), each once.
        val hereIds = state.people.mapNotNull(::personId).toSet()
        val others = state.people.filter { personId(it) != me }
        val members = members(session)
        val faces = members.map { (id, name) -> PersonUi(id, name, PackWords.initials(name), me = id == me, here = id in hereIds) }.sortedByDescending { it.here }
        val items = state.items
        val groups = PackPanelWords.groups().mapNotNull { (kind, title) ->
            items.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { ItemGroupUi(kind, title, it.map { item -> itemUi(item, me, since, others, inputs.open, zone, now) }) }
        }
        return PackPanelUi(
            uuid = state.uuid,
            name = nameOf(session),
            loaded = true,
            unreachable = state.status == PackStatus.OFFLINE || state.status == PackStatus.ERROR,
            subtitle = PackPanelWords.subtitle(role),
            dropped = session.dropped.size,
            droppedReasons = session.dropped.map { it.reason }.toSet(),
            droppedIds = session.dropped.mapNotNull { text(it.op["client_op_id"]) },
            finished = session.status == "finished",
            readOnly = readOnly,
            canEdit = !readOnly && (role == "owner" || role == "editor"),
            owner = role == "owner",
            description = text(session.pack["description"])?.takeIf { it.isNotBlank() },
            members = audience(session),
            faces = faces,
            here = hereIds.size,
            tab = tab,
            newCount = items.count { changedSince(it, since, me) },
            groups = groups,
            history = history.second.takeIf { history.first == state.uuid } ?: HistoryUi(loading = tab == PackTab.HISTORY),
        )
    }

    private fun itemUi(item: PackItemView, me: Int?, since: Long?, others: List<JsonObject>, open: Set<String>, zone: ZoneId, now: Instant): PackItemUi {
        val whoJson = ItemInfo.who(item)
        val whoName = ItemInfo.whoName(whoJson)
        val who = whoName?.let { PersonUi(ItemInfo.whoId(whoJson), it, PackWords.initials(it), me = ItemInfo.whoId(whoJson) == me) }
        val detail = PackPanelWords.detail(item)
        val updatedAt = ItemInfo.updatedAt(item)
        val whenText = PackPanelWords.whenText(updatedAt, zone, now)
        val meta = listOfNotNull(detail, if (item.pendingCreate) "Sending…" else whoName?.let(PackPanelWords::shortName), whenText.ifEmpty { null }).joinToString(" · ")
        val isNew = changedSince(item, since, me)
        val original = ItemInfo.original(item)
        val fromLibrary = ItemInfo.source(item) != null
        val withIt = others.filter { text((it["focus"] as? JsonObject)?.get("item")) == item.uuid }.mapNotNull { text(it["name"]) }.map(PackPanelWords::shortName).distinct()
        val canOpen = item.kind == "lz" || item.kind == "route"
        val description = buildString {
            append(item.name)
            if (isNew) append(", changed in the pack since you looked")
            detail?.let { append(", ").append(it) }
            if (item.pendingCreate) {
                append(", sending")
            } else if (whoName != null) {
                append(", by ").append(PackPanelWords.shortName(whoName))
                when {
                    whenText.isEmpty() -> Unit
                    whenText == "just now" || whenText.endsWith("ago") -> append(' ').append(whenText)
                    whenText.contains(':') -> append(" at ").append(whenText)
                    else -> append(" on ").append(whenText)
                }
            }
            append('.')
            if (fromLibrary) append(if (original == "changed") " From Library, original changed." else " From Library.")
            if (withIt.isNotEmpty()) append(' ').append(PackPanelWords.hasItOpen(withIt)).append('.')
            if (item.uuid in open) append(" Open here.")
            if (canOpen) append(" Show on the map")
        }
        return PackItemUi(item.uuid, item.kind, item.name, isNew, who, meta, fromLibrary, original, withIt, item.uuid in open, item.pendingCreate, canOpen, description)
    }

    private fun historyUi(history: History, filters: Filters, context: HistoryContext, since: Long?): HistoryUi {
        val zone = zone()
        val now = now()
        val me = context.me
        val people = listOf(FilterChoice(HistoryUi.EVERYONE, null)) + context.members.map { (id, name) -> FilterChoice(if (id == me) "You" else name, id.toString()) }
        val itemChoices = listOf(FilterChoice(HistoryUi.ALL_ITEMS, null)) + context.names.map { (uuid, name) -> FilterChoice(name, uuid) }
        // A filter whose person or item has gone keeps nothing back, and says so: it is not left filtering under a label that says it does not.
        val person = people.firstOrNull { it.key == filters.person } ?: people.first()
        val item = itemChoices.firstOrNull { it.key == filters.item } ?: itemChoices.first()
        // One line for each change, however many operations it went to the pack as (PackHistory: a doghouse turned is two, an analysis five), newest
        // first. A line's events share who, what and where; the newest says when.
        val shown = PackHistory.lines(history.events, since)
            .filter { line -> person.key == null || line.first().actor.id?.toString() == person.key }
            .filter { line -> item.key == null || line.first().item == item.key }
            .asReversed()
        fun ui(line: List<PackEvent>, at: Instant?, fresh: Boolean): EventUi {
            val event = line.last()
            val name = event.item?.let(context.names::get)
            val (lead, rest) = PackPanelWords.eventLine(event, name)
            val meta = listOfNotNull(at?.let { PackPanelWords.clockTime(it, zone) }, PackPanelWords.where(line, name)).joinToString(" · ")
            val who = event.actor.id?.let { PersonUi(it, event.actor.name, PackWords.initials(event.actor.name), me = it == me) }
            return EventUi(line.first().seq, who, lead, rest, event.status == "skipped", meta, fresh)
        }
        val timed = shown.map { it to PackPanelWords.instant(it.last().createdAt) }
        val (fresh, rest) = timed.partition { (line, _) -> since != null && line.last().seq > since && line.last().actor.id != me }
        val groups = buildList {
            if (fresh.isNotEmpty()) add(EventGroupUi("New since you looked · ${fresh.size}", true, fresh.map { (line, at) -> ui(line, at, true) }))
            rest.groupBy { (_, at) -> at?.let { PackPanelWords.dayLabel(it, zone, now) } ?: "Earlier" }
                .forEach { (title, lines) -> add(EventGroupUi(title, false, lines.map { (line, at) -> ui(line, at, false) })) }
        }
        return HistoryUi(history.loading, history.failed, groups, people, person, itemChoices, item)
    }

    companion object {
        /** The most events a page asks for, and the most the history holds (the web's PAGE). */
        const val PAGE = 500

        const val POINT_SETS_LATER = "Point sets from a pack are not drawn on the map yet."
        const val NAME_REFUSED = "Give it a name of 1 to 100 characters."
        const val STILL_WORKING = "Still working on the last change. Try again in a moment."
        const val KEEP_FAILED = "Your version could not be saved to your Library. Try again."
        const val KEEP_REST_FAILED = "The rest could not be saved to your Library. Try again."
        const val NOTHING_LEFT = "There was nothing of those changes left to keep: what they changed is no longer in the pack."
        const val DISCARDED = "Your changes the pack did not take were discarded."
        const val NOTHING_DISCARDED = "Those changes were no longer waiting, so nothing was discarded."

        /** Whether [item] was changed since [since] by someone other than [me] (usePackSeen.js's `changedSince`). */
        internal fun changedSince(item: PackItemView, since: Long?, me: Int?): Boolean {
            val seq = ItemInfo.seq(item) ?: return false
            if (since == null || seq <= since) return false
            return ItemInfo.whoId(item.info?.get("updated_by") as? JsonObject) != me
        }

        // Whether the server has answered this pack's client in this run: the device's copy is shown before, and its marker can be old.
        private fun answered(state: PackState): Boolean = state.status == PackStatus.POLLING || state.status == PackStatus.LIVE

        private fun contextOf(state: PackState?, user: Int?): HistoryContext {
            val session = state?.session ?: return HistoryContext(state?.uuid)
            return HistoryContext(state.uuid, state.items.associate { it.uuid to it.name }, members(session), session.me)
        }

        // The pack's newest event as the device holds it.
        private fun headOf(session: PackSession): Long = maxOf(session.seq, session.headSeq ?: 0)

        // How far the server says this person has looked.
        private fun seenOf(session: PackSession): Long = (session.pack["seen_seq"] as? JsonPrimitive)?.longOrNull ?: 0

        private fun nameOf(session: PackSession): String = text(session.pack["name"]) ?: "Mission Pack"

        // Who can see the pack (usePackWorkspace.jsx's packAudience): one rule for every panel.
        private fun audience(session: PackSession): Int = PackFacts.audience(session)

        private fun members(session: PackSession): List<Pair<Int, String>> = session.members.mapNotNull { member ->
            val obj = member as? JsonObject ?: return@mapNotNull null
            val id = (obj["user_id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val name = text(obj["name"])?.takeIf { it.isNotBlank() } ?: text(obj["email"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }.distinctBy { it.first }

        private fun personId(person: JsonObject): Int? = (person["user_id"] as? JsonPrimitive)?.intOrNull

        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    }
}
