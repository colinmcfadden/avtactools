package app.ezpztac.map

import app.ezpztac.model.GraphicRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Where each unit's picture is on the screen, relative to the unit's own position, for a finger to hit.
 *
 * A unit stands where its symbol's anchor is, and that is not where the picture is: a hostile unit stands on the end of a staff under its frame, so the frame a person
 * sees and presses is well away from the point. The pictures are drawn by the Compose layer, which alone knows their size, so it reports the box of each here as it draws it,
 * and a tap or a long press is tested against the box as well as the point. Pixels, the same as the screen's, whatever the zoom: a symbol does not scale with the map.
 */
class UnitFootprints {
    /** The box of a unit's picture, in pixels from the unit's position: [left] and [top] are usually negative. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    private val boxes = ConcurrentHashMap<GraphicRef, Box>()

    fun report(ref: GraphicRef, box: Box) {
        boxes[ref] = box
    }

    fun of(ref: GraphicRef): Box? = boxes[ref]

    /** Lets go of the boxes of units that are no longer on the map. */
    fun retain(refs: Collection<GraphicRef>) {
        boxes.keys.retainAll(refs.toSet())
    }
}
