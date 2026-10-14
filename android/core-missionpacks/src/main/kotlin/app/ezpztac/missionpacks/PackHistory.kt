package app.ezpztac.missionpacks

import app.ezpztac.network.PackEvent

/**
 * A pack's history as people read it: one line for each change. The web's `packHistory.js`, held to `contracts/fixtures/packs/history.json`.
 *
 * A change goes to the pack as one operation or several (a doghouse turned is its heading and the landing or takeoff heading it gives; an analysis
 * is what it found, the doghouses it made and their headings), each with the change's one sentence ([PackEdit]), and the pack logs every operation
 * as an event of its own, so a line per event said the same thing two or five times. Events that follow one another in the log, by one person, on
 * one item, with one sentence and one outcome, are one line: the server counts a copy's changes the same way (`pack_support.pack_changes`).
 */
public object PackHistory {
    /**
     * [events], the log oldest first, as the history's lines, oldest first: each line the events it stands for, oldest first. A line never holds
     * events on both sides of [since] (where "new since you looked" starts), so nothing new is folded into what was seen.
     */
    public fun lines(events: List<PackEvent>, since: Long? = null): List<List<PackEvent>> {
        val lines = ArrayList<MutableList<PackEvent>>()
        for (event in events) {
            val line = lines.lastOrNull()
            val previous = line?.last()
            val acrossSince = since != null && previous != null && previous.seq <= since && event.seq > since
            if (line != null && previous != null && !acrossSince && sameLine(previous, event)) line += event else lines += mutableListOf(event)
        }
        return lines
    }

    // Whether [event], straight after [previous] in the log, belongs to its line.
    private fun sameLine(previous: PackEvent, event: PackEvent): Boolean =
        event.seq == previous.seq + 1 && event.actor == previous.actor && event.item == previous.item && event.summary == previous.summary &&
            event.status == previous.status && event.reason == previous.reason
}
