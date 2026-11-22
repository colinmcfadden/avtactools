package app.ezpztac.planning

import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Where a FLOT's label goes: the middle of the line by distance, and the unit vector away from the side its scallops are on. */
public data class FlotLabelSpot(val x: Double, val y: Double, val awayX: Double, val awayY: Double)

/**
 * The FLOT, the forward line of own troops (the owner's, 2026-11-08; the web's feature/flot): a line of semicircles drawn across the map,
 * `{id, points: [{lat, lon}, ...]}` under `flots` of a set's document, with `flipped: true` when its scallops face the other way. Its scallops are the
 * size of the screen's, not the ground's, so they are worked out for the zoom in screen pixels; by default they are to the right of the way the
 * line was drawn. Held to `contracts/fixtures/planning/flots.json`.
 */
public object Flots {
    /** Across one scallop, in screen pixels. */
    public const val SCALLOP_PX: Double = 18.0

    private const val ARC_STEPS = 8

    /** A new FLOT of [points] (places only). */
    public fun create(points: List<LatLon>, id: String): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive(id), "points" to SetLines.plain(points)))

    /** [flot] with its scallops on the other side: `flipped` is left out unless it is true, so an unflipped FLOT keeps one shape (`flipFlot`). */
    public fun flip(flot: JsonObject): JsonObject {
        val rest = JsonObject(flot.filterKeys { it != "flipped" })
        return if (SetLines.truthy(flot["flipped"])) rest else JsonObject(rest + ("flipped" to JsonPrimitive(true)))
    }

    /** Whether [flot]'s scallops face the other way. */
    public fun flipped(flot: JsonObject): Boolean = SetLines.truthy(flot["flipped"])

    /**
     * The scallops along a line given in screen pixels ([pixels]): lists of pixels, one for each unbroken run. A whole number of scallops as near
     * [diameter] as fits, each between two places on the line, so they follow it round a corner and the last ends where it ends. With [within] a
     * scallop whose chord's middle is off that box is left out: at a close zoom a long FLOT is tens of thousands of them (`scallopRuns`).
     */
    public fun scallopRuns(pixels: List<Pixel>, diameter: Double = SCALLOP_PX, flipped: Boolean = false, within: PixelBox? = null): List<List<Pixel>> {
        if (pixels.size < 2) return emptyList()
        val legs = legsOf(pixels)
        val total = legs.sum()
        if (!(total > 0)) return emptyList()
        val count = max(1.0, RouteCalc.jsRound(total / diameter)).toInt()
        val chord = total / count

        var leg = 0
        var start = 0.0
        fun placeAt(distance: Double): Pixel {
            while (leg < legs.size - 1 && distance > start + legs[leg]) {
                start += legs[leg]
                leg += 1
            }
            val along = if (legs[leg] > 0) (distance - start) / legs[leg] else 0.0
            return Pixel(pixels[leg].x + (pixels[leg + 1].x - pixels[leg].x) * along, pixels[leg].y + (pixels[leg + 1].y - pixels[leg].y) * along)
        }

        val runs = ArrayList<MutableList<Pixel>>()
        var run: MutableList<Pixel>? = null
        var from = pixels[0]
        for (i in 1..count) {
            val to = if (i == count) pixels.last() else placeAt(i * chord)
            val middle = Pixel((from.x + to.x) / 2, (from.y + to.y) / 2)
            if (outside(middle, within)) {
                run = null
            } else {
                val current = run ?: ArrayList<Pixel>().also { it += from; runs += it; run = it }
                current += arcAfter(from, to, flipped)
            }
            from = to
        }
        return runs
    }

    /** The middle of the line by distance along it, and the unit vector away from its scallops (`flotLabelSpot`); null for a line with no length. */
    public fun labelSpot(pixels: List<Pixel>, flipped: Boolean = false): FlotLabelSpot? {
        if (pixels.size < 2) return null
        val legs = legsOf(pixels)
        var toGo = legs.sum() / 2
        for (i in legs.indices) {
            if (legs[i] > 0 && toGo <= legs[i]) {
                val a = pixels[i]
                val b = pixels[i + 1]
                val along = toGo / legs[i]
                val ux = (b.x - a.x) / legs[i]
                val uy = (b.y - a.y) / legs[i]
                val side = if (flipped) -1.0 else 1.0
                return FlotLabelSpot(a.x + (b.x - a.x) * along, a.y + (b.y - a.y) * along, side * uy, side * -ux)
            }
            toGo -= legs[i]
        }
        return null
    }

    private fun legsOf(pixels: List<Pixel>): List<Double> = (1 until pixels.size).map { hypot(pixels[it].x - pixels[it - 1].x, pixels[it].y - pixels[it - 1].y) }

    private fun outside(p: Pixel, within: PixelBox?): Boolean =
        within != null && (p.x < within.minX || p.x > within.maxX || p.y < within.minY || p.y > within.maxY)

    /** A semicircle from [a] to [b], without its first point. Screen y runs downward, so (-dy, dx) is to the right of a to b. */
    private fun arcAfter(a: Pixel, b: Pixel, flipped: Boolean): List<Pixel> {
        val cx = (a.x + b.x) / 2
        val cy = (a.y + b.y) / 2
        val rx = a.x - cx
        val ry = a.y - cy
        val side = if (flipped) -1.0 else 1.0
        val nx = side * ry
        val ny = side * -rx
        return (1..ARC_STEPS).map { i ->
            val t = (i.toDouble() / ARC_STEPS) * PI
            Pixel(cx + rx * cos(t) + nx * sin(t), cy + ry * cos(t) + ny * sin(t))
        }
    }
}
