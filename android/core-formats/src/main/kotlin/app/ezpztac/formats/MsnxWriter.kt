package app.ezpztac.formats

import app.ezpztac.formats.MsnxXml.MSNX_NS
import app.ezpztac.formats.MsnxXml.children
import app.ezpztac.formats.MsnxXml.clearChildren
import app.ezpztac.formats.MsnxXml.clone
import app.ezpztac.formats.MsnxXml.descendants
import app.ezpztac.formats.MsnxXml.directChildren
import app.ezpztac.formats.MsnxXml.findCoordinateValueEl
import app.ezpztac.formats.MsnxXml.findDirectChild
import app.ezpztac.formats.MsnxXml.formatCoordinate
import app.ezpztac.formats.MsnxXml.getItemValue
import app.ezpztac.formats.MsnxXml.requireChild
import app.ezpztac.formats.MsnxXml.setDirectChildText
import app.ezpztac.formats.MsnxXml.setItemValue
import app.ezpztac.formats.MsnxXml.setText
import app.ezpztac.model.JsNumber
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteCalc
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds an AMPS mission (`.msnx`) from sketched routes: a port of `buildSketchMsnxZip` in `frontend/src/feature/msnxImport/createMsnx.js`, held to the
 * files the web itself exports (`contracts/fixtures/msnx/sketch-*.msnx`).
 *
 * It takes the bundled template (an AMPS-authored mission) apart for its *prototypes* — one route, segment, leg, AMPS point, serpentine point, GPX route
 * and so on — empties every list, and rebuilds them from the sketches. Designated points become full AMPS route points, and shaping points become
 * serpentine geometry (a leg's trackpoints plus display points), the way Mission X encodes a hand-shaped leg. What AMPS is given to plan with (airspeeds,
 * altitudes, winds, ground elevations, clock times) is the app's own plan; the rest of the performance data stays as the template had it, to be
 * recalculated in AMPS.
 *
 * **Identifiers.** Every route, segment, leg and point gets a fresh id from [newId], and they are asked for in the web's order, so a counter that
 * hands out the same ids the web's test stub does gives the same file. That order is the contract: do not reorder a call to [newId].
 *
 * The document is built with the DOM and the serializer is [MsnxXml.serialize]. The whole template is held in memory: a template of tens of megabytes
 * (the vehicle model is a zip entry and is copied through as bytes; `legs.xml` is the part that grows) will want its first `<leg>` cut out before it is
 * parsed. The bundled one is 1.1 MB.
 */
public object MsnxWriter {
    private const val GPX = "mission.gpx"
    private const val POINTS = "mission/points.xml"
    private const val LEGS = "mission/legs.xml"
    private const val SEGMENTS = "mission/segments.xml"
    private const val ROUTES = "mission/routes.xml"
    private const val MISSION = "mission/mission.xml"
    private const val SUMMARY = "mission/missionsummary.xml"

    // PtType vocabulary from Mission X (see the sample mission's points.xml).
    private val PT_TYPE_VALUES = mapOf(
        "turn" to "RtePtTurn,Circle.png:",
        "ip" to "RtePtIP,Square.png:",
        "target" to "RtePtTarget,Triangle.png:",
    )

    private const val DECL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
    private const val GPX_DECL = "<?xml version=\"1.0\"?>"

    /**
     * The `.msnx` for [routes], on [template]'s package, as zip bytes. [today] is the day a clock time falls on when a plan names none.
     * @throws MsnxException if the template is not one this can use, or a route has fewer than two designated points.
     */
    public fun build(
        template: ByteArray,
        routes: List<SketchRoute>,
        missionName: String,
        newId: () -> String = { UUID.randomUUID().toString() },
        today: LocalDate = LocalDate.now(),
    ): ByteArray {
        if (routes.isEmpty()) throw MsnxException("There is no route to export.")
        val entries = readZip(template)
        val docs = LinkedHashMap<String, Document>()
        for (path in listOf(GPX, POINTS, LEGS, SEGMENTS, ROUTES, MISSION, SUMMARY)) {
            val bytes = entries[path] ?: throw MsnxException("The mission template is missing $path.")
            docs[path] = MsnxXml.parse(bytes.toString(Charsets.UTF_8), path)
        }
        build(docs, routes, missionName, newId, today)

        entries[GPX] = MsnxXml.serialize(docs.getValue(GPX), GPX_DECL, bom = false).toByteArray(Charsets.UTF_8)
        for (path in listOf(POINTS, LEGS, SEGMENTS, ROUTES, MISSION, SUMMARY)) {
            entries[path] = MsnxXml.serialize(docs.getValue(path), DECL, bom = true).toByteArray(Charsets.UTF_8)
        }
        return writeZip(entries)
    }

    private fun build(docs: Map<String, Document>, sketches: List<SketchRoute>, missionName: String, newId: () -> String, today: LocalDate) {
        val gpxDoc = docs.getValue(GPX)
        val pointsDoc = docs.getValue(POINTS)
        val legsDoc = docs.getValue(LEGS)
        val segmentsDoc = docs.getValue(SEGMENTS)
        val routesDoc = docs.getValue(ROUTES)
        val summaryDoc = docs.getValue(SUMMARY)
        val missionDoc = docs.getValue(MISSION)

        // --- prototypes (cloned before their containers are cleared) ---
        val routesRoot = first(routesDoc, "routes")
        val routeProto = clone(directChildren(routesRoot, "route").firstOrNull() ?: missing("a route", ROUTES))

        val segmentsRoot = first(segmentsDoc, "segments")
        val segmentProto = clone(directChildren(segmentsRoot, "segment").firstOrNull() ?: missing("a segment", SEGMENTS))

        val legsRoot = first(legsDoc, "legs")
        val legProto = clone(directChildren(legsRoot, "leg").firstOrNull() ?: missing("a leg", LEGS))

        val protoTrackpointSet = requireChild(requireChild(legProto, "commandbase"), "trackpointset")
        val trackpointItemProto = clone(directChildren(protoTrackpointSet, "item").firstOrNull() ?: throw MsnxException("The mission template's leg has no trackpoint to clone."))

        val pointsRoot = first(pointsDoc, "points")
        val templatePoints = directChildren(pointsRoot, "point")
        val ampsPointProto = templatePoints.firstOrNull { getItemValue(it, "IsCalcPt") == "False" }?.let(::clone)
        val calcPointProto = templatePoints.firstOrNull { (getItemValue(it, "PtType") ?: "").startsWith("CalcPtSerpentine") }?.let(::clone)
        if (ampsPointProto == null || calcPointProto == null) throw MsnxException("The mission template is missing the point prototypes.")
        val ampsPointProtoId = requireChild(ampsPointProto, "id").textContent
        val calcPointProtoId = requireChild(calcPointProto, "id").textContent

        val gpxRoot = first(gpxDoc, "gpx")
        val rteProto = clone(descendants(gpxRoot, "rte").firstOrNull() ?: throw MsnxException("The mission template's GPX has no route."))
        fun findProtoRtept(pointId: String): Element? = descendants(rteProto, "rtept")
            .firstOrNull { descendants(it, "msnx:point").firstOrNull()?.getAttribute("msnx:id") == pointId }?.let(::clone)
        val rteptAmpsProto = findProtoRtept(ampsPointProtoId)
        val rteptCalcProto = findProtoRtept(calcPointProtoId)
        if (rteptAmpsProto == null || rteptCalcProto == null) throw MsnxException("The mission template's GPX is missing the prototype route points.")

        val rootExtensions = directChildren(gpxRoot, "extensions").firstOrNull() ?: missing("extensions", GPX)
        val msnxMission = descendants(rootExtensions, "msnx:mission").firstOrNull() ?: missing("an msnx:mission", GPX)
        val manifestSegmentsEl = directChildren(msnxMission, "msnx:segments").firstOrNull() ?: missing("msnx:segments", GPX)
        val manifestSegmentProto = clone(children(manifestSegmentsEl).firstOrNull() ?: missing("a manifest segment", GPX))
        val manifestLegsEl = directChildren(msnxMission, "msnx:legs").firstOrNull() ?: missing("msnx:legs", GPX)
        val manifestLegProto = clone(children(manifestLegsEl).firstOrNull() ?: missing("a manifest leg", GPX))
        val manifestPointPlaceholderProto = descendants(manifestLegProto, "msnx:point").firstOrNull()?.let(::clone)

        val summaryRoutesRoot = first(summaryDoc, "routes")
        val summaryRouteProto = clone(directChildren(summaryRoutesRoot, "route").firstOrNull() ?: missing("a route", SUMMARY))

        // --- clear all mission content; rebuild from the sketches ---
        for (root in listOf(routesRoot, segmentsRoot, legsRoot, pointsRoot, summaryRoutesRoot, manifestSegmentsEl, manifestLegsEl)) clearChildren(root)
        for (rte in descendants(gpxRoot, "rte")) rte.parentNode.removeChild(rte)

        var firstRouteId: String? = null
        var firstSegmentId: String? = null
        var firstPointId: String? = null

        for (sketch in sketches) {
            val (ampsPoints, legShaping) = partition(sketch)

            // Planned performance data (airspeeds, altitudes, winds, TOT-anchored clock times), computed as the panel shows it. The plan's points and
            // legs are index-aligned with ampsPoints (the same shaping filter).
            val planResult = RouteCalc.computeRoutePlan(sketch.points, sketch.plan, sketch.elevations, today)

            val routeId = newId()
            val segmentId = newId()
            val ampsIds = ampsPoints.map { newId() }
            val legIds = legShaping.map { newId() }
            // The command id of each AMPS point and leg, mirrored into the GPX manifest below; and each leg's calc point ids, for the GPX route points.
            val ampsCmdIds = ArrayList<String?>()
            val legCmdInfo = ArrayList<LegCommands>()
            val calcIdsPerLeg = legShaping.map { shaping -> shaping.map { newId() } }

            if (firstRouteId == null) {
                firstRouteId = routeId
                firstSegmentId = segmentId
                firstPointId = ampsIds[0]
            }

            fun ampsName(p: RoutePoint, i: Int): String = p.name?.takeIf { it.isNotEmpty() } ?: ".ACP$i"

            // --- points.xml: AMPS points ---
            ampsPoints.forEachIndexed { i, point ->
                val el = clone(ampsPointProto)
                setText(requireChild(el, "id"), ampsIds[i])
                val commandMap = regenerateCommandIds(el, newId)
                ampsCmdIds += commandMap.values.firstOrNull()
                setIdList(pointsDoc, requireChild(el, "incominglegs"), if (i > 0) listOf(legIds[i - 1]) else emptyList())
                setIdList(pointsDoc, requireChild(el, "outgoinglegs"), if (i < ampsPoints.size - 1) listOf(legIds[i]) else emptyList())
                findCoordinateValueEl(el)?.let { setText(it, formatCoordinate(point.lat, point.lon)) }
                setItemValue(el, "PtNum", (i + 1).toString())
                setItemValue(el, "DtdID", ampsName(point, i))
                setItemValue(el, "PtNameFix", ampsName(point, i))
                setItemValue(el, "PtDesc", ampsName(point, i))
                setItemValue(el, "PtType", PT_TYPE_VALUES[point.ptType] ?: PT_TYPE_VALUES.getValue("turn"))
                setItemValue(el, "PtDbLookup", "")
                setItemValue(el, "PtDesc", "")

                // Planned altitude, elevation and clock time. AMPS recomputes the derived values on Calc, but honours these as the plan inputs.
                planResult.points.getOrNull(i)?.let { planPoint ->
                    setItemValue(el, "PlanAltitudeValue", AmpsFormats.planAltitude(planPoint.value, planPoint.ref))
                    planPoint.mslFt?.let {
                        setItemValue(el, "CmdAlt", AmpsFormats.cmdAlt(it))
                        setItemValue(el, "CmdAltValid", "True")
                    }
                    planPoint.groundFt?.let { setItemValue(el, "Elevation", AmpsFormats.elevation(it)) }
                    planPoint.clockTime?.let { setItemValue(el, "CmdClockTime", AmpsFormats.clockTime(it)) }
                }
                pointsRoot.appendChild(el)
            }

            // --- points.xml: serpentine calc points (display copies) ---
            legShaping.forEachIndexed { legIndex, shaping ->
                shaping.forEachIndexed { i, point ->
                    val el = clone(calcPointProto)
                    setText(requireChild(el, "id"), calcIdsPerLeg[legIndex][i])
                    regenerateCommandIds(el, newId)
                    findCoordinateValueEl(el)?.let { setText(it, formatCoordinate(point.lat, point.lon)) }
                    setItemValue(el, "PtNameFix", ".Serpentine ${i + 1}")
                    setItemValue(el, "Elevation", "0 m User")
                    setItemValue(el, "MSLAltitude", "0 MM")
                    pointsRoot.appendChild(el)
                }
            }

            // --- legs.xml ---
            legIds.forEachIndexed { i, legId ->
                val el = clone(legProto)
                setText(requireChild(el, "id"), legId)
                setIdList(legsDoc, requireChild(el, "segments"), listOf(segmentId))
                setDirectChildText(el, "startpt", ampsIds[i])
                setDirectChildText(el, "endpt", ampsIds[i + 1])
                regenerateCommandIds(el, newId)

                // Planned leg airspeed and winds (the CmdStdLeg inputs AMPS plans with): both are the "to" values of the leg's arrival point.
                planResult.legs.getOrNull(i)?.let { planLeg ->
                    setItemValue(el, "AirspeedValue", AmpsFormats.airspeed(planLeg.airspeed))
                    val wind = AmpsFormats.wind(planLeg.wind.dirTrue, planLeg.wind.speedKts)
                    setItemValue(el, "CruiseWind", wind)
                    setItemValue(el, "ClimbDescentWind", wind)
                }

                val shaping = legShaping[i]
                val trackpointSet = requireChild(requireChild(el, "commandbase"), "trackpointset")
                clearChildren(trackpointSet)
                if (shaping.isNotEmpty()) {
                    for (point in shaping) {
                        val item = clone(trackpointItemProto)
                        val trackpointId = newId()
                        setText(requireChild(item, "key"), trackpointId)
                        val trackpoint = requireChild(item, "trackpoint")
                        setText(requireChild(trackpoint, "id"), trackpointId)
                        setItemValue(trackpoint, "TrackPtCoordinate", formatCoordinate(point.lat, point.lon))
                        setItemValue(trackpoint, "TrackPtElv", "0 m User")
                        trackpointSet.appendChild(item)
                    }
                } else {
                    removeCommand(el, "CmdSerpentine")
                }

                legCmdInfo += LegCommands(
                    stdId = findCommandByType(el, "CmdStdLeg").id,
                    serpId = if (shaping.isNotEmpty()) findCommandByType(el, "CmdSerpentine").id else null,
                    shapingCount = shaping.size,
                )
                legsRoot.appendChild(el)
            }

            // --- segments.xml ---
            run {
                val el = clone(segmentProto)
                setText(requireChild(el, "id"), segmentId)
                setIdList(segmentsDoc, requireChild(el, "routes"), listOf(routeId))
                setIdList(segmentsDoc, requireChild(el, "legs"), legIds)
                findDirectChild(el, "refpoint")?.let { setDirectChildText(it, "id", newId()) }
                segmentsRoot.appendChild(el)
            }

            // --- routes.xml ---
            run {
                val el = clone(routeProto)
                setText(requireChild(el, "id"), routeId)
                setIdList(routesDoc, requireChild(el, "segments"), listOf(segmentId))
                setItemValue(el, "RouteName", sketch.name)
                routesRoot.appendChild(el)
            }

            // --- missionsummary.xml ---
            run {
                val el = clone(summaryRouteProto)
                setText(requireChild(el, "id"), routeId)
                setDirectChildText(el, "name", sketch.name)
                descendants(el, "segment").firstOrNull()?.let { setDirectChildText(it, "id", segmentId) }
                summaryRoutesRoot.appendChild(el)
            }

            // --- mission.gpx: a route with the AMPS route points and the serpentine ones, in order ---
            run {
                val rte = clone(rteProto)
                setDirectChildText(rte, "name", sketch.name)
                descendants(rte, "msnx:route").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:name", sketch.name)
                descendants(rte, "msnx:segment").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:id", segmentId)
                for (rtept in descendants(rte, "rtept")) rtept.parentNode.removeChild(rtept)

                fun appendRtept(proto: Element, lat: Double, lon: Double, ele: Double?, pointId: String, name: String, commandId: String?) {
                    val el = clone(proto)
                    el.setAttribute("lat", JsNumber.toText(lat))
                    el.setAttribute("lon", JsNumber.toText(lon))
                    setDirectChildText(el, "ele", JsNumber.toText(ele ?: 0.0))
                    setDirectChildText(el, "name", name)
                    setDirectChildText(el, "desc", "")
                    descendants(el, "msnx:point").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:id", pointId)
                    if (commandId != null) descendants(el, "msnx:command").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:id", commandId)
                    rte.appendChild(el)
                }

                ampsPoints.forEachIndexed { i, point ->
                    // The ground elevation (m) in the GPX when the plan fetched one.
                    val groundFt = planResult.points.getOrNull(i)?.groundFt
                    val ele = if (groundFt != null) groundFt * AmpsFormats.FT_TO_M else point.ele
                    appendRtept(rteptAmpsProto, point.lat, point.lon, ele, ampsIds[i], ampsName(point, i), ampsCmdIds[i])
                    if (i < legShaping.size) {
                        legShaping[i].forEachIndexed { j, sp -> appendRtept(rteptCalcProto, sp.lat, sp.lon, sp.ele, calcIdsPerLeg[i][j], ".Serpentine ${j + 1}", null) }
                    }
                }
                gpxRoot.appendChild(rte)
            }

            // --- the GPX root's manifest: a segment entry and a leg entry for each ---
            run {
                val segmentEntry = clone(manifestSegmentProto)
                segmentEntry.setAttributeNS(MSNX_NS, "msnx:id", segmentId)
                val legList = descendants(segmentEntry, "msnx:legs").firstOrNull() ?: missing("msnx:legs in a manifest segment", GPX)
                clearChildren(legList)
                for (legId in legIds) {
                    val ref = gpxDoc.createElementNS(MSNX_NS, "msnx:leg")
                    ref.setAttributeNS(MSNX_NS, "msnx:id", legId)
                    legList.appendChild(ref)
                }
                manifestSegmentsEl.appendChild(segmentEntry)

                legIds.forEachIndexed { i, legId ->
                    val legEntry = clone(manifestLegProto)
                    legEntry.setAttributeNS(MSNX_NS, "msnx:id", legId)
                    descendants(legEntry, "msnx:startpoint").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:id", ampsIds[i])
                    descendants(legEntry, "msnx:endpoint").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:id", ampsIds[i + 1])

                    val (stdId, serpId, shapingCount) = legCmdInfo[i]
                    val commandsEl = descendants(legEntry, "msnx:commands").firstOrNull() ?: missing("msnx:commands in a manifest leg", GPX)
                    for (cmd in descendants(commandsEl, "msnx:command")) {
                        val type = cmd.getAttribute("msnx:type")
                        if (type == "CmdStdLeg" && stdId != null) {
                            cmd.setAttributeNS(MSNX_NS, "msnx:id", stdId)
                        } else if (type == "CmdSerpentine") {
                            if (serpId != null) cmd.setAttributeNS(MSNX_NS, "msnx:id", serpId) else commandsEl.removeChild(cmd)
                        }
                    }
                    val tracksEl = descendants(commandsEl, "msnx:tracks").firstOrNull()
                    for (placeholder in descendants(commandsEl, "msnx:point")) commandsEl.removeChild(placeholder)
                    if (shapingCount > 0 && manifestPointPlaceholderProto != null) {
                        repeat(shapingCount) { commandsEl.appendChild(clone(manifestPointPlaceholderProto)) }
                    } else if (tracksEl != null && shapingCount == 0) {
                        commandsEl.removeChild(tracksEl)
                    }
                    manifestLegsEl.appendChild(legEntry)
                }
            }
        }

        // --- mission.xml: the mission's focus and primary ids point at the first route ---
        val missionEl = first(missionDoc, "mission")
        setItemValue(missionEl, "MissionName", missionName)
        setItemValue(missionEl, "FocusRoute", firstRouteId!!)
        setItemValue(missionEl, "PrimaryRoute", firstRouteId)
        setItemValue(missionEl, "FocusSegment", firstSegmentId!!)
        setItemValue(missionEl, "FocusRtePt", firstPointId!!)

        // The GPX root's extensions block also carries the mission name.
        descendants(gpxRoot, "msnx:mission").firstOrNull()?.setAttributeNS(MSNX_NS, "msnx:name", missionName)
    }

    private data class LegCommands(val stdId: String?, val serpId: String?, val shapingCount: Int)

    private class Partition(val ampsPoints: List<RoutePoint>, val legShaping: List<List<RoutePoint>>) {
        operator fun component1() = ampsPoints
        operator fun component2() = legShaping
    }

    /**
     * Splits a sketch's points into the AMPS leg vertices and the shaping points that fall on each leg between consecutive vertices. A point with no
     * `kind` (from a sketch made before designations existed) counts as an AMPS point; shaping points before the first AMPS point are dropped.
     */
    private fun partition(sketch: SketchRoute): Partition {
        val ampsPoints = ArrayList<RoutePoint>()
        val legShaping = ArrayList<List<RoutePoint>>()
        var current: MutableList<RoutePoint>? = null
        for (p in sketch.points) {
            if (p.kind != RoutePoint.KIND_SHAPING) {
                ampsPoints += p
                if (ampsPoints.size > 1) legShaping += (current ?: mutableListOf())
                current = mutableListOf()
            } else {
                current?.add(p)
            }
        }
        if (ampsPoints.size < 2) throw MsnxException("Route \"${sketch.name}\" needs at least two designated AMPS points to export.")
        return Partition(ampsPoints, legShaping)
    }

    /** Replaces the ordered `<id>` children of a list element (`<legs>`, `<routes>`). */
    private fun setIdList(doc: Document, list: Element, ids: List<String>) {
        clearChildren(list)
        for (id in ids) {
            val el = doc.createElement("id")
            setText(el, id)
            list.appendChild(el)
        }
    }

    /**
     * Gives a cloned point or leg's command base fresh ids, keeping them consistent with each other (the command base's id, the command sequence's ids,
     * and each command item's key and id). Returns old command id -> new command id, in sequence order, so a caller can mirror them into the GPX.
     * The order of the calls to [newId] is the web's: the command base first, then each id of the sequence.
     */
    private fun regenerateCommandIds(container: Element, newId: () -> String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val commandBase = findDirectChild(container, "commandbase") ?: return map

        findDirectChild(commandBase, "id")?.let { setText(it, newId()) }

        val sequence = findDirectChild(commandBase, "commandsequence")
        for (idEl in if (sequence != null) directChildren(sequence, "id") else emptyList()) {
            val fresh = newId()
            map[idEl.textContent] = fresh
            setText(idEl, fresh)
        }

        val commands = findDirectChild(commandBase, "commands")
        for (item in if (commands != null) directChildren(commands, "item") else emptyList()) {
            val keyEl = findDirectChild(item, "key")
            val fresh = map[keyEl?.textContent] ?: continue
            setText(keyEl!!, fresh)
            findDirectChild(item, "command")?.let { command -> findDirectChild(command, "id")?.let { setText(it, fresh) } }
        }
        return map
    }

    private class FoundCommand(val item: Element?, val commandsEl: Element?, val id: String?)

    /** A leg command base's command item and id, by its `CmdTypeID`. */
    private fun findCommandByType(leg: Element, type: String): FoundCommand {
        val commandBase = findDirectChild(leg, "commandbase")
        val commands = commandBase?.let { findDirectChild(it, "commands") }
        for (item in if (commands != null) directChildren(commands, "item") else emptyList()) {
            val command = findDirectChild(item, "command")
            if (command != null && getItemValue(command, "CmdTypeID") == type) {
                return FoundCommand(item, commands, findDirectChild(command, "id")?.textContent)
            }
        }
        return FoundCommand(null, commands, null)
    }

    private fun removeCommand(leg: Element, type: String) {
        val found = findCommandByType(leg, type)
        val item = found.item ?: return
        val command = findDirectChild(item, "command") ?: return
        val commandId = findDirectChild(command, "id")?.textContent
        found.commandsEl?.removeChild(item)
        val commandBase = findDirectChild(leg, "commandbase") ?: return
        val sequence = findDirectChild(commandBase, "commandsequence") ?: return
        for (idEl in directChildren(sequence, "id")) {
            if (idEl.textContent == commandId) sequence.removeChild(idEl)
        }
    }

    private fun first(doc: Document, tagName: String): Element {
        val nodes = doc.getElementsByTagName(tagName)
        if (nodes.length == 0) throw MsnxException("The mission template has no <$tagName>.")
        return nodes.item(0) as Element
    }

    private fun missing(what: String, part: String): Nothing = throw MsnxException("The mission template has no $what in $part.")

    // -- The package ---------------------------------------------------------------------------------------------------

    /** The zip's entries by name, in order, as bytes. */
    private fun readZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw MsnxException("The mission template is not a mission package.", e)
        }
        return entries
    }

    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
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
