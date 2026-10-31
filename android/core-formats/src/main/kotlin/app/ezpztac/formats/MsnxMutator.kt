package app.ezpztac.formats

import app.ezpztac.formats.MsnxXml.MSNX_NS
import app.ezpztac.formats.MsnxXml.children
import app.ezpztac.formats.MsnxXml.clearChildren
import app.ezpztac.formats.MsnxXml.clone
import app.ezpztac.formats.MsnxXml.descendants
import app.ezpztac.formats.MsnxXml.findCoordinateValueEl
import app.ezpztac.formats.MsnxXml.findDirectChild
import app.ezpztac.formats.MsnxXml.formatCoordinate
import app.ezpztac.formats.MsnxXml.setItemValue
import app.ezpztac.formats.MsnxXml.setText
import app.ezpztac.model.JsNumber
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePoint
import app.ezpztac.planning.RouteCalc
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * Writes a person's edits to an imported AMPS mission back into **the file it came from**, the way the web does (`mutateMsnx.js`): the `.msnx` stays the saved
 * document, so everything AMPS keeps in it that this app never reads (the vehicle model, the calculation blobs of every leg) survives untouched.
 *
 * It is given the original file and the routes as they are now ([rewrite]), and works out what changed by reading the original again: a point that moved or was renamed is
 * patched in `points.xml` and the GPX (a serpentine point also in its leg's trackpoints), a point that is new is spliced onto the leg it was put on (which is split in two),
 * and a route whose plan changed has its altitudes, elevations and clock times written into its points and its airspeeds and winds into its legs. Comparing with the
 * original, rather than recording edits, means a file that is saved again and again always ends as "the original plus the difference", and nothing needs undoing.
 *
 * What it does not do, as on the web: remove a point or a route from the file, change a point's kind, or recalculate (the cloned point and leg keep the calculated data they
 * were copied with, "to be recalculated in AMPS").
 *
 * **Not like the web:** the web writes every route's plan on every export. Here a plan is written only for a route whose plan, ground elevations or points changed. The plan is
 * kept in feet and rounded, so writing it back unchanged would quietly round every altitude in a file that was only opened.
 *
 * The small parts (`points.xml`, `segments.xml`, the GPX) go through a DOM; `legs.xml`, which is most of the file, is edited as text ([LegsText]).
 */
public object MsnxMutator {
    private const val GPX = "mission.gpx"
    private const val POINTS = "mission/points.xml"
    private const val LEGS = "mission/legs.xml"
    private const val SEGMENTS = "mission/segments.xml"
    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val DECL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
    private const val GPX_DECL = "<?xml version=\"1.0\"?>"

    /** The name a point made by inserting one on a leg is given, as the web does. */
    public const val NEW_POINT_NAME: String = ".NEWPT"

    /**
     * The mission [original] with [current] written into it, as zip bytes.
     *
     * @param current the routes as they stand, in the file's order. A point of one whose id is not in the file is a point to insert; it goes after the point before it.
     * @param newId names the legs made by splitting (the inserted points are named by the caller, so that the same id is on screen and in the file).
     * @throws MsnxException if the file cannot be read, or its routes are no longer the ones [current] holds.
     */
    public fun rewrite(
        original: ByteArray,
        current: List<MissionRoute>,
        newId: () -> String = { UUID.randomUUID().toString() },
        today: LocalDate = LocalDate.now(),
        maxPartBytes: Int = MsnxReader.DEFAULT_MAX_PART_BYTES,
    ): ByteArray {
        val base = MsnxReader.read(original, maxPartBytes).routes
        if (base.size != current.size || base.indices.any { base[it].segmentId != current[it].segmentId }) {
            throw MsnxException("The routes of this mission no longer match its file, so the changes cannot be written into it.")
        }
        val entries = readPackage(original, maxPartBytes)
        fun part(name: String) = entries[name]?.toString(Charsets.UTF_8) ?: throw MsnxException("This doesn't look like a valid .msnx mission file (missing $name).")

        val docs = Docs(
            gpx = MsnxXml.parse(part(GPX), GPX),
            points = MsnxXml.parse(part(POINTS), POINTS),
            segments = MsnxXml.parse(part(SEGMENTS), SEGMENTS),
            legs = LegsText(part(LEGS)),
        )
        for (i in base.indices) edit(docs, base[i], current[i], newId, today)

        entries[GPX] = MsnxXml.serialize(docs.gpx, GPX_DECL, bom = false).toByteArray(Charsets.UTF_8)
        entries[POINTS] = MsnxXml.serialize(docs.points, DECL, bom = true).toByteArray(Charsets.UTF_8)
        entries[SEGMENTS] = MsnxXml.serialize(docs.segments, DECL, bom = true).toByteArray(Charsets.UTF_8)
        entries[LEGS] = docs.legs.build().toByteArray(Charsets.UTF_8)
        return writePackage(entries)
    }

    private class Docs(val gpx: Document, val points: Document, val segments: Document, val legs: LegsText) {
        /** `points.xml`'s points by id, and the GPX route points by the point they hold: found once, kept up to date as points are added. */
        val pointById: MutableMap<String, Element> = LinkedHashMap<String, Element>().also { map ->
            for (p in descendants(points.documentElement, "point")) findDirectChild(p, "id")?.textContent?.let { map.putIfAbsent(it, p) }
        }
        val rteptById: MutableMap<String, Element> = LinkedHashMap<String, Element>().also { map ->
            for (r in descendants(gpx.documentElement, "rtept")) {
                val id = descendants(r, "msnx:point").firstOrNull()?.getAttribute("msnx:id")
                if (!id.isNullOrEmpty()) map.putIfAbsent(id, r)
            }
        }
    }

    private fun edit(docs: Docs, base: MissionRoute, current: MissionRoute, newId: () -> String, today: LocalDate) {
        val baseById = base.points.filter { it.id != null }.associateBy { it.id!! }

        // 1. Points that moved or were renamed.
        for (point in current.points) {
            val id = point.id ?: continue
            val before = baseById[id] ?: continue
            if (point.lat != before.lat || point.lon != before.lon) updateCoordinate(docs, id, point.lat, point.lon)
            val name = point.name
            if (name != null && name != before.name) updateName(docs, id, name)
        }

        // 2. Points that are new: each goes after the point before it, which is in the file by then (a point inserted a moment ago counts).
        val working = ArrayList(base.points)
        var inserted = false
        current.points.forEachIndexed { index, point ->
            val id = point.id
            if (id == null || id in baseById) return@forEachIndexed
            val after = current.points.getOrNull(index - 1)
                ?: throw MsnxException("A point cannot be put before the start of a route.")
            val at = working.indexOfFirst { it.id == after.id }
            if (at < 0) throw MsnxException("A new point was put after a point that is not in the file.")
            insertAfter(docs, current.segmentId, working, at, point, newId)
            working.add(at + 1, point)
            inserted = true
        }

        // 3. The plan, only when it changed (see the class comment).
        if (inserted || current.plan != base.plan || current.elevations != base.elevations) applyPlan(docs, current, today)
    }

    // -- Moving and naming -------------------------------------------------------------------------------------------------

    private fun updateCoordinate(docs: Docs, pointId: String, lat: Double, lon: Double) {
        docs.rteptById[pointId]?.let {
            it.setAttribute("lat", JsNumber.toText(lat))
            it.setAttribute("lon", JsNumber.toText(lon))
        }
        val newCoord = formatCoordinate(lat, lon)
        val pointEl = docs.pointById[pointId] ?: return
        val valueEl = findCoordinateValueEl(pointEl) ?: return
        val oldCoord = valueEl.textContent
        setText(valueEl, newCoord)
        // A serpentine display point mirrors a trackpoint in its leg: the geometry AMPS calculates from.
        if (oldCoord != null && oldCoord != newCoord) docs.legs.swapTrackpoint(oldCoord, newCoord)
    }

    private fun updateName(docs: Docs, pointId: String, name: String) {
        docs.pointById[pointId]?.let {
            setItemValue(it, "PtNameFix", name)
            setItemValue(it, "PtDesc", name)
        }
        docs.rteptById[pointId]?.let { rtept -> descendants(rtept, "name").firstOrNull()?.let { setText(it, name) } }
    }

    // -- Inserting a point on a leg ------------------------------------------------------------------------------------------

    private class SegmentLeg(val id: String, val start: String?, val end: String?, val idEl: Element)

    private fun segmentLegs(docs: Docs, segmentId: String?): Pair<Element, List<SegmentLeg>> {
        val segment = descendants(docs.segments.documentElement, "segment").firstOrNull { findDirectChild(it, "id")?.textContent == segmentId }
            ?: throw MsnxException("Couldn't find this route's segment data.")
        val list = findDirectChild(segment, "legs") ?: throw MsnxException("Couldn't find this route's segment data.")
        val legs = children(list).filter { it.tagName == "id" }.map { SegmentLeg(it.textContent, docs.legs.startOf(it.textContent), docs.legs.endOf(it.textContent), it) }
        return list to legs
    }

    /**
     * Puts [point] after the one at [index] of [route], on the leg between the real points either side of it. Many of a route's points are not leg vertices (serpentine
     * shaping, command-only duplicates): the whole wiggle can be one leg between two named points, so this walks outward to the nearest real vertices and splits the leg that
     * joins them. The leg graph does not care where among the cosmetic points the new one sits in the GPX, so it goes right after [index]'s.
     */
    private fun insertAfter(docs: Docs, segmentId: String?, route: List<RoutePoint>, index: Int, point: RoutePoint, newId: () -> String) {
        val newPointId = point.id ?: throw MsnxException("A new point needs an id.")
        val (legsList, legs) = segmentLegs(docs, segmentId)
        val vertices = HashSet<String>().also { set -> legs.forEach { l -> l.start?.let(set::add); l.end?.let(set::add) } }

        var a = index
        var c = index + 1
        if (c > route.lastIndex) throw MsnxException("Couldn't find a route segment near this location to split.")
        while (a > 0 && route[a].id !in vertices) a -= 1
        while (c < route.lastIndex && route[c].id !in vertices) c += 1
        val pointA = route[a]
        val pointC = route[c]
        val leg = legs.firstOrNull { it.start == pointA.id && it.end == pointC.id }
            ?: throw MsnxException("Couldn't find the connecting leg between these two points — this spot on the line may not be directly editable.")

        val newLegId = newId()
        // 1. The leg is split: a copy runs from the new point to C, and the original now stops at the new point.
        docs.legs.splitLeg(leg.id, newLegId, newPointId)

        // 2. The new leg joins the segment's ordered legs, right after the one it was split from.
        val newLegIdEl = docs.segments.createElement("id")
        newLegIdEl.textContent = newLegId
        legsList.insertBefore(newLegIdEl, leg.idEl.nextSibling)

        // 3. The new point is a copy of a plain one: the first with exactly one command, else any with an id.
        val templateId = route.firstNotNullOfOrNull { p -> p.id?.takeIf { id -> docs.pointById[id]?.let(::countCommands) == 1 } } ?: route.firstNotNullOfOrNull { it.id }
        val templateEl = templateId?.let { docs.pointById[it] } ?: throw MsnxException("Couldn't find a point template to clone for the new point.")
        val pointEl = clone(templateEl)
        findDirectChild(pointEl, "id")?.let { setText(it, newPointId) }
        findDirectChild(pointEl, "incominglegs")?.let { list ->
            clearChildren(list)
            list.appendChild(docs.points.createElement("id").also { it.textContent = leg.id })
        }
        findDirectChild(pointEl, "outgoinglegs")?.let { list ->
            clearChildren(list)
            list.appendChild(docs.points.createElement("id").also { it.textContent = newLegId })
        }
        findCoordinateValueEl(pointEl)?.let { setText(it, formatCoordinate(point.lat, point.lon)) }
        setItemValue(pointEl, "PtDbLookup", "")
        setItemValue(pointEl, "PtNameFix", NEW_POINT_NAME)
        setItemValue(pointEl, "PtDesc", "")
        templateEl.parentNode.appendChild(pointEl)
        docs.pointById[newPointId] = pointEl

        // 4. C's incoming leg is now the new one.
        docs.pointById[pointC.id]?.let { cEl ->
            findDirectChild(cEl, "incominglegs")?.let { incoming ->
                for (idEl in children(incoming)) if (idEl.tagName == "id" && idEl.textContent == leg.id) setText(idEl, newLegId)
            }
        }

        // 5. A matching route point in the GPX, right after the one the person put it after.
        val anchor = route[index]
        val anchorRtept = anchor.id?.let { docs.rteptById[it] }
        val templateRtept = docs.rteptById[templateId]
        val rtept = docs.gpx.createElementNS(GPX_NS, "rtept")
        rtept.setAttribute("lat", JsNumber.toText(point.lat))
        rtept.setAttribute("lon", JsNumber.toText(point.lon))
        rtept.appendChild(docs.gpx.createElementNS(GPX_NS, "ele").also { it.textContent = JsNumber.toText(point.ele ?: anchor.ele ?: 0.0) })
        rtept.appendChild(docs.gpx.createElementNS(GPX_NS, "name").also { it.textContent = NEW_POINT_NAME })
        val extensions = docs.gpx.createElementNS(GPX_NS, "extensions")
        val msnxPoint = docs.gpx.createElementNS(MSNX_NS, "msnx:point")
        msnxPoint.setAttributeNS(MSNX_NS, "msnx:id", newPointId)
        val commands = docs.gpx.createElementNS(MSNX_NS, "msnx:commands")
        templateRtept?.let { source -> descendants(source, "msnx:commands").firstOrNull()?.let { c0 -> for (cmd in children(c0)) commands.appendChild(cmd.cloneNode(true)) } }
        msnxPoint.appendChild(commands)
        extensions.appendChild(msnxPoint)
        rtept.appendChild(extensions)
        anchorRtept?.parentNode?.insertBefore(rtept, anchorRtept.nextSibling)
        docs.rteptById[newPointId] = rtept
    }

    private fun countCommands(pointEl: Element): Int = descendants(pointEl, "key").count { it.textContent == "CmdTypeID" }

    // -- The plan ------------------------------------------------------------------------------------------------------------

    /**
     * Writes a route's plan into its points (altitude, elevation, clock) and its legs (airspeed and wind, to the leg arriving at each point), as the sketch exporter does, so
     * a re-exported mission carries the planning edits. AMPS recomputes what is derived when it calculates.
     */
    private fun applyPlan(docs: Docs, route: MissionRoute, today: LocalDate) {
        val result = RouteCalc.computeRoutePlan(route.points, route.plan, route.elevations, today)
        for (rp in result.points) {
            val pointEl = rp.id?.let { docs.pointById[it] } ?: continue
            setItemValue(pointEl, "PlanAltitudeValue", AmpsFormats.planAltitude(rp.value, rp.ref))
            rp.mslFt?.let {
                setItemValue(pointEl, "CmdAlt", AmpsFormats.cmdAlt(it))
                setItemValue(pointEl, "CmdAltValid", "True")
            }
            rp.groundFt?.let { setItemValue(pointEl, "Elevation", AmpsFormats.elevation(it)) }
            rp.clockTime?.let { setItemValue(pointEl, "CmdClockTime", AmpsFormats.clockTime(it)) }
        }

        // No segment or no legs: the points were still updated above, as on the web.
        val legs = try { segmentLegs(docs, route.segmentId).second } catch (_: MsnxException) { return }
        val legByEnd = LinkedHashMap<String, String>()
        for (leg in legs) if (leg.end != null && docs.legs.hasLeg(leg.id)) legByEnd[leg.end] = leg.id
        for (leg in result.legs) {
            val legId = leg.toId?.let { legByEnd[it] } ?: continue
            docs.legs.setItemValue(legId, "AirspeedValue", AmpsFormats.airspeed(leg.airspeed))
            val wind = AmpsFormats.wind(leg.wind.dirTrue, leg.wind.speedKts)
            docs.legs.setItemValue(legId, "CruiseWind", wind)
            docs.legs.setItemValue(legId, "ClimbDescentWind", wind)
        }
    }

    // -- The package ---------------------------------------------------------------------------------------------------------

    /** Every entry of the zip, in order, each read up to [maxPartBytes]: the file comes from anyone, so an archive that expands past it is refused. */
    private fun readPackage(bytes: ByteArray, maxPartBytes: Int): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > max(maxPartBytes, 0)) throw MsnxException("This mission file is too large to open (${entry.name} expands past ${maxPartBytes / (1024 * 1024)} MB).")
                        out.write(buffer, 0, n)
                    }
                    entries[entry.name] = out.toByteArray()
                }
            }
        } catch (e: ZipException) {
            throw MsnxException("Couldn't open this file as a .msnx mission archive.", e)
        } catch (e: IOException) {
            throw MsnxException("Couldn't open this file as a .msnx mission archive.", e)
        }
        return entries
    }

    private fun writePackage(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
