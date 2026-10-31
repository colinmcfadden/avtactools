package app.ezpztac.formats

import app.ezpztac.formats.AmpsParse.jsParseFloat
import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.Mission
import app.ezpztac.model.MissionAircraft
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.Wind
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** A mission file that cannot be read. The message is for the user. */
public class MsnxException(message: String, cause: Throwable? = null) : FormatException(message, cause)

/**
 * Reads an AMPS mission (`.msnx`) into the routes and plans the app works with.
 *
 * A `.msnx` is a zip of XML documents. This is a port of `parseMsnxFile` in
 * `frontend/src/feature/msnxImport/parseMsnx.js`, held to
 * `contracts/fixtures/msnx`, which includes missions the web app itself exported.
 *
 * - `points.xml` says what each point *is*; the GPX gives the route order and
 *   positions; `segments.xml` ties a route to its legs; `legs.xml` carries each leg's
 *   airspeed and wind.
 * - `legs.xml` is most of the file (tens of MB in a real mission) and the app wants
 *   three short values per leg, so it is read as text and never built into a DOM.
 *
 * These files arrive by email and AirDrop, so the reader does not trust them: DTDs and
 * entities are refused (an external entity could read a file off the device), and the
 * zip is read with a size limit (a hostile archive could expand to gigabytes).
 */
public object MsnxReader {
    private const val GPX = "mission.gpx"
    private const val POINTS = "mission/points.xml"
    private const val LEGS = "mission/legs.xml"
    private const val SEGMENTS = "mission/segments.xml"
    // Optional: present in real AMPS missions, absent from hand-built ones.
    private const val VEHICLES = "mission/vehicles.xml"

    private val REQUIRED = listOf(GPX, POINTS, LEGS, SEGMENTS)
    private val WANTED = REQUIRED + VEHICLES

    /** Most one part may expand to. A real `legs.xml` is a few tens of MB. */
    public const val DEFAULT_MAX_PART_BYTES: Int = 256 * 1024 * 1024

    private val RELEASE_POINT = Regex("""^RP\d*$""", RegexOption.IGNORE_CASE)
    private val TARGET = Regex("""^(LZ|PZ)""", RegexOption.IGNORE_CASE)
    private val VEHICLE_DESCRIPTION = Regex("""<vehicledescription>([\s\S]*?)</vehicledescription>""", RegexOption.IGNORE_CASE)

    // JavaScript's \s is wider than Java's: no-break and Unicode spaces count.
    private const val JS_SPACE = """\s   -     　﻿"""
    private val XML_PROLOG = Regex("""^﻿?(?:[$JS_SPACE]*<\?xml[^>]*\?>)+[$JS_SPACE]*""")
    private val LEG = Regex("""<leg>([\s\S]*?)</leg>""")
    private val LEG_ID = Regex("""<id>([^<]*)</id>""")
    private val LEG_END = Regex("""<endpt>([^<]*)</endpt>""")

    public fun read(bytes: ByteArray, maxPartBytes: Int = DEFAULT_MAX_PART_BYTES): Mission {
        val parts = unzip(bytes, maxPartBytes)
        val text = REQUIRED.associateWith { name ->
            parts[name]?.let { String(it, Charsets.UTF_8) }
                ?: throw MsnxException("This doesn't look like a valid .msnx mission file (missing expected mission data).")
        }
        val aircraft = readAircraft(parts[VEHICLES]?.let { String(it, Charsets.UTF_8) })

        val legPlan = legPlanData(text.getValue(LEGS))
        val gpx = parseXml(text.getValue(GPX), "mission.gpx")
        val points = parseXml(text.getValue(POINTS), "points.xml")
        val segments = parseXml(text.getValue(SEGMENTS), "segments.xml")

        val rteElements = gpx.getElementsByTagName("rte").elements()
        if (rteElements.isEmpty()) throw MsnxException("No routes found in this mission file.")

        val pointInfo = buildPointInfo(points)
        // Index points.xml once for every route, rather than re-walking it per route.
        val pointById = LinkedHashMap<String, Element>()
        for (el in points.getElementsByTagName("point").elements()) {
            val id = el.directChild("id")?.textContent ?: continue
            pointById[id] = el
        }

        val routes = rteElements.mapIndexed { routeIndex, rte ->
            val name = rte.childText("name").ifEmpty { "Unnamed Route" }
            val segmentId = rte.getElementsByTagName("msnx:segment").elements().firstOrNull()
                ?.getAttribute("msnx:id")?.ifEmpty { null }

            val routePoints = rte.getElementsByTagName("rtept").elements().mapIndexed { index, rtept ->
                val eleText = rtept.childText("ele")
                val pointName = rtept.childText("name")
                val id = rtept.getElementsByTagName("msnx:point").elements().firstOrNull()
                    ?.getAttribute("msnx:id")?.ifEmpty { null }
                val info = id?.let { pointInfo[it] }
                RoutePoint(
                    id = id,
                    // `id` is AMPS's identifier and is legitimately absent on some legacy or
                    // geometry-only GPX points; keep a stable identity of our own so several
                    // anonymous points are not all "null".
                    uiId = id ?: "msnx-point-${segmentId ?: routeIndex}-$index",
                    lat = jsParseFloat(rtept.getAttribute("lat")),
                    lon = jsParseFloat(rtept.getAttribute("lon")),
                    ele = if (eleText.isNotEmpty()) jsParseFloat(eleText) else null,
                    name = pointName,
                    // points.xml is the authority on what a point IS; the name-based role
                    // is a fallback for rendering older data. AMPS writes calculated
                    // serpentine geometry two ways: as points flagged CalcPtSerpentine, or
                    // as bare rtepts with no extensions at all. Neither is a plannable point.
                    kind = info?.kind ?: (if (id != null) null else "shaping"),
                    ptType = info?.ptType,
                    role = classifyPoint(pointName, index),
                )
            }

            val (plan, elevations) = buildRoutePlan(segments, routePoints, segmentId, pointById, legPlan)
            MissionRoute(name, segmentId, routePoints, plan, elevations)
        }
        return Mission(routes, aircraft)
    }

    /** The airframe a mission was planned for, or null when the file carries no vehicle data. */
    internal fun readAircraft(vehiclesText: String?): MissionAircraft? {
        if (vehiclesText.isNullOrEmpty()) return null
        val description = VEHICLE_DESCRIPTION.find(vehiclesText)?.groupValues?.get(1)?.trim()
        if (description.isNullOrEmpty()) return null
        val designation = description.split(":").last().trim().ifEmpty { null }
        return MissionAircraft(description, designation)
    }

    private fun classifyPoint(name: String, index: Int): String {
        if (index == 0) return "start"
        val stripped = name.removePrefix(".")
        return when {
            RELEASE_POINT.matches(stripped) -> "release"
            TARGET.containsMatchIn(stripped) -> "target"
            else -> "waypoint"
        }
    }

    private class PointInfo(val kind: String?, val ptType: String?)

    /**
     * What each point in points.xml is: `PtType` separates real AMPS route points
     * (turn, IP, target, STTO) from calculation-only shaping points (CalcPt..., IsCalcPt).
     */
    private fun buildPointInfo(points: Document): Map<String, PointInfo> {
        val map = HashMap<String, PointInfo>()
        for (point in points.getElementsByTagName("point").elements()) {
            val id = point.directChild("id")?.textContent?.takeIf { it.isNotEmpty() } ?: continue
            val ptTypeValue = itemValue(point, "PtType") ?: ""
            val isCalc = itemValue(point, "IsCalcPt") == "True" || ptTypeValue.startsWith("CalcPt")
            var kind: String? = null
            var ptType: String? = null
            if (isCalc) {
                kind = "shaping"
            } else if (ptTypeValue.isNotEmpty()) {
                kind = "amps"
                ptType = when {
                    ptTypeValue.startsWith("RtePtIP") -> "ip"
                    ptTypeValue.startsWith("RtePtTarget") -> "target"
                    else -> "turn"                           // RtePtTurn, RtePtSTTO, anything unrecognised
                }
            }
            map[id] = PointInfo(kind, ptType)
        }
        return map
    }

    /** The first `<item>` under [el] whose `<key>` is exactly [key]: its `attribute/value` text, or null. */
    private fun itemValue(el: Element, key: String): String? {
        for (item in el.getElementsByTagName("item").elements()) {
            val keyEl = item.directChild("key")
            if (keyEl != null && keyEl.textContent == key) {
                // The first match decides, even when it has no value: as in the web.
                return item.directChild("attribute")?.directChild("value")?.textContent
            }
        }
        return null
    }

    internal class LegPlan(val endpt: String?, val airspeed: String?, val wind: String?)

    /**
     * Each leg's end point, airspeed and wind, taken from the `legs.xml` text. The same
     * answer a DOM walk would give, without building a DOM of the largest part of the file.
     */
    internal fun legPlanData(legsText: String): Map<String, LegPlan> {
        val byId = HashMap<String, LegPlan>()
        for (match in LEG.findAll(legsText)) {
            val chunk = match.groupValues[1]
            val id = LEG_ID.find(chunk)?.groupValues?.get(1) ?: continue
            if (id.isEmpty()) continue
            fun value(key: String) =
                Regex("<key>$key</key>[\\s\\S]*?<value>([^<]*)</value>").find(chunk)?.groupValues?.get(1)
            byId[id] = LegPlan(LEG_END.find(chunk)?.groupValues?.get(1), value("AirspeedValue"), value("CruiseWind"))
        }
        return byId
    }

    /**
     * Rebuilds the inline plan (per-point altitudes and clock, per-leg airspeed and wind as
     * "to" values on the arriving point) and the ground elevations from a mission's
     * points.xml, legs.xml and segments.xml, so an imported route drives the same plan
     * editor a sketched route does.
     */
    private fun buildRoutePlan(
        segments: Document,
        routePoints: List<RoutePoint>,
        segmentId: String?,
        pointById: Map<String, Element>,
        legPlan: Map<String, LegPlan>,
    ): Pair<RoutePlan, Map<String, Double>> {
        var plan = RoutePlan.forAircraft()
        val perPoint = LinkedHashMap<String, PointOverride>()
        val elevations = LinkedHashMap<String, Double>()
        val ampsPoints = routePoints.filter { it.kind != RoutePoint.KIND_SHAPING && !it.id.isNullOrEmpty() }

        var firstAltitude: AltitudeSetting? = null
        val clockByPoint = LinkedHashMap<String, AmpsParse.Clock>()
        for (p in ampsPoints) {
            val el = pointById[p.id] ?: continue

            val groundM = AmpsParse.meters(itemValue(el, "Elevation"))
            if (groundM != null) elevations[p.id!!] = AmpsParse.feetFromMeters(groundM)

            // CmdAlt is the planned altitude, in metres MSL.
            val mslM = AmpsParse.meters(itemValue(el, "CmdAlt"))
            if (mslM != null) {
                val altitude = AltitudeSetting(AmpsParse.feetFromMeters(mslM), AltitudeSetting.REF_MSL)
                perPoint[p.id!!] = (perPoint[p.id] ?: PointOverride()).copy(altitude = altitude)
                if (firstAltitude == null) firstAltitude = altitude
            }

            AmpsParse.clock(itemValue(el, "CmdClockTime"))?.let { clockByPoint[p.id!!] = it }
        }
        if (firstAltitude != null) plan = plan.copy(altitude = firstAltitude)

        // TOT / clock detection: with no time on target, AMPS leaves every point sharing one
        // midnight placeholder. A real timing plan, including one this app exported, gives the
        // points distinct clock times, so treat that as an anchored plan and pin it to the first
        // point (the rolling times recompute the rest identically whichever point holds it).
        if (clockByPoint.values.map { "${it.date}T${it.time}" }.toSet().size >= 2) {
            val anchorId = ampsPoints.firstOrNull { clockByPoint.containsKey(it.id) }?.id
            if (anchorId != null) {
                val anchor = clockByPoint.getValue(anchorId)
                perPoint[anchorId] = (perPoint[anchorId] ?: PointOverride()).copy(clock = anchor.time)
                plan = plan.copy(date = anchor.date)
            }
        }

        // Per-leg airspeed and wind, assigned to the leg's arrival point.
        val segmentEl = segments.getElementsByTagName("segment").elements()
            .firstOrNull { it.directChild("id")?.textContent == segmentId }
        val legIds = segmentEl?.directChild("legs")?.children()
            ?.filter { it.tagName == "id" }?.map { it.textContent }.orEmpty()

        var firstAirspeed: Airspeed? = null
        var firstWind: Wind? = null
        for (legId in legIds) {
            val leg = legPlan[legId]
            val endId = leg?.endpt?.takeIf { it.isNotEmpty() } ?: continue
            AmpsParse.airspeed(leg.airspeed)?.let {
                perPoint[endId] = (perPoint[endId] ?: PointOverride()).copy(airspeed = it)
                if (firstAirspeed == null) firstAirspeed = it
            }
            AmpsParse.wind(leg.wind)?.let {
                perPoint[endId] = (perPoint[endId] ?: PointOverride()).copy(wind = it)
                if (firstWind == null) firstWind = it
            }
        }
        if (firstAirspeed != null) plan = plan.copy(airspeed = firstAirspeed)
        if (firstWind != null) plan = plan.copy(wind = firstWind)

        return plan.copy(perPoint = perPoint) to elevations
    }

    // -- the zip and the XML, which are untrusted ---------------------------------------------------

    /** The wanted parts of the archive, each read up to [maxPartBytes]. */
    private fun unzip(bytes: ByteArray, maxPartBytes: Int): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var sawEntry = false
                while (entry != null) {
                    sawEntry = true
                    if (!entry.isDirectory && entry.name in WANTED) {
                        parts[entry.name] = readBounded(zip, maxPartBytes, entry.name)
                    }
                    entry = zip.nextEntry
                }
                if (!sawEntry) throw MsnxException("Couldn't open this file as a .msnx mission archive.")
            }
        } catch (e: MsnxException) {
            throw e
        } catch (e: ZipException) {
            throw MsnxException("Couldn't open this file as a .msnx mission archive.", e)
        } catch (e: IOException) {
            throw MsnxException("Couldn't open this file as a .msnx mission archive.", e)
        }
        return parts
    }

    private fun readBounded(input: java.io.InputStream, max: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw MsnxException("This mission file is too large to open ($name expands past ${max / (1024 * 1024)} MB).")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Test seam: false stands in for an XML runtime that knows none of the feature flags
     * below, as Android's does, so the check that must hold there can be tested here.
     */
    @Volatile
    internal var hardenXmlFactory: Boolean = true

    private fun newBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        // Names stay as written ("msnx:point"), which is how the reader looks them up.
        isNamespaceAware = false
        // Best effort, like the features below: Android's parser throws UnsupportedOperationException for *turning XInclude off* (it supports no XInclude to turn on),
        // which refused every mission on a device until a real one was tried. The text check in parseXml is what holds.
        try { isXIncludeAware = false } catch (_: UnsupportedOperationException) { /* this parser has none */ }
        try { isExpandEntityReferences = false } catch (_: UnsupportedOperationException) { /* nor does it expand any */ }
        // Best effort: not every XML runtime (Android's among them) knows every flag. The text
        // check in parseXml is what holds on all of them.
        if (hardenXmlFactory) for ((feature, value) in listOf(
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            try { setFeature(feature, value) } catch (_: Exception) { /* unsupported here */ }
        }
    }

    /**
     * Strips a BOM and any leading XML declaration (the declaration is optional, and files
     * exported by earlier builds of the web app carried a doubled one, which is invalid XML),
     * then parses. A document with a DTD or an entity is refused outright.
     */
    private fun parseXml(text: String, label: String): Document {
        val sanitized = text.replace(XML_PROLOG, "")
        if (sanitized.contains("<!DOCTYPE", ignoreCase = true) || sanitized.contains("<!ENTITY", ignoreCase = true)) {
            throw MsnxException("Failed to parse $label in this mission file.")
        }
        return try {
            newBuilderFactory().newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }   // never fetch anything
                setErrorHandler(null)
            }.parse(InputSource(java.io.StringReader(sanitized)))
        } catch (e: SAXException) {
            throw MsnxException("Failed to parse $label in this mission file.", e)
        } catch (e: IOException) {
            throw MsnxException("Failed to parse $label in this mission file.", e)
        }
    }

    // -- DOM helpers, with the semantics of the DOM the web uses ---------------------------------

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element) out += child
            child = child.nextSibling
        }
        return out
    }

    private fun Element.directChild(tag: String): Element? = children().firstOrNull { it.tagName == tag }

    private fun Element.childText(tag: String): String = directChild(tag)?.textContent?.trim() ?: ""
}
