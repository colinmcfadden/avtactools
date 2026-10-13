package app.ezpztac.android

import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.sync.RecordKind
import app.ezpztac.workspace.LibraryTab

/** What the frame offers for copies of the person's own items into a mission pack (P4a), decided apart from the screen so it can be tried. */
internal object CopyRules {
    /** *Add to Mission Pack…* is asked from the Library's and the panels' menus while packs run here and none is open. */
    fun asksWhere(packsHere: Boolean, open: OpenPackUi?): Boolean = packsHere && open == null

    /**
     * The pack a Library item is added to rather than opened: the one open, while this person can change it (not finished, not gone, not a viewer: a viewer's
     * role is known from the switcher's list before the pack's own state is, so it is not offered then either).
     */
    fun addingTo(packsHere: Boolean, open: OpenPackUi?): String? =
        open?.takeIf { packsHere && !it.readOnly && !it.finished && it.status != PackStatus.GONE && it.role != "viewer" }?.name

    /** The Pack panel's foot shows once its pack is loaded and while it is not finished: for its owner (*Finish*) or anyone who can add to it. */
    fun footShown(loaded: Boolean, open: OpenPackUi?, canAdd: Boolean): Boolean =
        loaded && open != null && (open.owner || canAdd) && !open.finished && open.status != PackStatus.GONE

    /** What a Library tab's items are, as records (a tab's imported missions are never asked about). */
    fun recordKindOf(tab: LibraryTab): RecordKind = when (tab) {
        LibraryTab.LZ -> RecordKind.LZ
        LibraryTab.ROUTES -> RecordKind.ROUTE
        LibraryTab.POINTS -> RecordKind.POINT_SET
    }

    /** What a pack made to start with items from the Library waits for, as the pack open changes: its own opening, nothing yet, or another pack (let go). */
    enum class LibraryAfter { OPEN_LIBRARY, WAIT, LET_GO }

    fun libraryAfter(wanted: String, open: String?): LibraryAfter = when (open) {
        null -> LibraryAfter.WAIT
        wanted -> LibraryAfter.OPEN_LIBRARY
        else -> LibraryAfter.LET_GO
    }
}
