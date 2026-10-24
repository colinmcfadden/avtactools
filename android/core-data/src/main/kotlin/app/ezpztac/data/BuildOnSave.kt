package app.ezpztac.data

import app.ezpztac.model.LatLon

/**
 * An LZ/PZ saved in this session asks for its point cloud to be built in the background and kept, so it is ready when someone opens it in 3D
 * (the web's `useBuildOnSave`). Only a save seen happening counts: one that arrives saved (opened from the Library, pulled from another device) is
 * no new reason to spend a build, and opening a dozen old LZ/PZs would otherwise queue a dozen of them.
 *
 * "Saved" is the web's `savedId`, which on Android is an LZ/PZ that has its own name (not its made-up `LZ/PZ n`) and is analysed: *Save* names
 * one (the owner's decision 1: autosave stays), and one is not saved before it is analysed. A Mission Pack's LZ/PZ is never saved in this sense,
 * as on the web (`packLz.js` gives it no `savedId`).
 */
public open class BuildOnSave(private val request: (LatLon) -> Unit) {
    /** One LZ/PZ as it stands: whether it is saved, and its target, if it has one. */
    public data class Seen(val uuid: String, val saved: Boolean, val target: LatLon?)

    // Each LZ/PZ's saved-ness when last seen, and those asked for already.
    private val seen = HashMap<String, Boolean>()
    private val asked = HashSet<String>()

    /**
     * The LZ/PZs as they stand now: one seen unsaved before and saved now, with a target, asks for its build, once. Once: a boundary drawn again
     * clears an analysis, and the analysis after it would be a save again, where the web's `savedId` never changes.
     */
    @Synchronized
    public open fun see(diagrams: List<Seen>) {
        for (diagram in diagrams) {
            val before = seen.put(diagram.uuid, diagram.saved)
            if (before == false && diagram.saved && diagram.target != null && asked.add(diagram.uuid)) request(diagram.target)
        }
    }

    public companion object {
        /** Asks for nothing: for an editor that is not the person's own. */
        public val None: BuildOnSave = object : BuildOnSave({}) {
            override fun see(diagrams: List<Seen>) = Unit
        }
    }
}
