package app.ezpztac.brief

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The live sandtable's steps, as plain data (docs/MISSION_BRIEF.md §2.8): what each step is called and what the map shows for it, read from the compiled brief and
 * nothing else, so the sandtable says what the PDF says. A port of the web's `feature/brief/sandtableSteps.js`, function by function, held to every case of
 * `contracts/fixtures/brief/sandtable.json` strictly (null kept apart from absent, numbers as JavaScript writes them, every object's keys in JavaScript's order).
 *
 * What is drawn mirrors the PDF's figures (backend/brief/figures.py): a route is lit on a route or departure step when it is the one in focus, the others dimmed on
 * a route step; point names show on the lit route and on overview and rally steps; the LZ/PZ in focus shows its boundary, aircraft and landing heading; threats
 * always; rally points on the rally step. A step with no figure shows the whole plan, unlit, over the brief's overview. A step is never called by a page: where
 * Chromium breaks the printed pages is not known here.
 *
 * The model is JSON as [CompileBrief] gives it, read with the web's loose rules ([Js]), and [steps] and [view] answer JSON as the web's objects are, so the fixture
 * holds them exactly; [listed] and [look] read the same answers into types for the screens. **Not like the web**, where it reads what no compiler writes: a field
 * is an object's own (a ref named `constructor` finds nothing, where the web would find JavaScript's), and a list or an object of the wrong kind is empty (the web
 * would throw).
 */
public object SandtableSteps {
    /** The colours a figure draws in, the same as the PDF's (backend/brief/figures.py) and the web's `FIGURE_COLOURS`. */
    public object Colours {
        public const val ROUTE: String = "#5aa7d4"
        public const val FOCUS: String = "#ffd166"
        public const val CASING: String = "#101317"
        public const val AIRCRAFT: String = "#64f338"
        public const val THREAT: String = "#e06a62"
        public const val THREAT_LABEL: String = "#ffb3ad"
        public const val RALLY: String = "#ffd166"

        /** Each flow role's colour, an LZ's for one the figures do not know. */
        public val ROLES: Map<String, String> = linkedMapOf(
            "LZ" to "#ffd166", "PZ" to "#6fe3a5", "FARP" to "#f0b429", "HA" to "#c9d0d8", "LAAGER" to "#c9d0d8", "FALSE_LZ" to "#e0a0ff",
        )
    }

    /** A role's colour (the web's `roleColour`): an LZ's for a role the figures do not know, or none. */
    public fun roleColour(role: JsonElement?): String = (if (role == null) null else Colours.ROLES[Js.text(role)]) ?: Colours.ROLES.getValue("LZ")

    /** A role's colour, by its name. */
    public fun roleColour(role: String?): String = Colours.ROLES[role] ?: Colours.ROLES.getValue("LZ")

    // `Array.isArray(b) && b.length === 2 && b.every((c) => Array.isArray(c) && c.length === 2 && c.every((v) => typeof v === "number" && Number.isFinite(v)))`
    private fun validBounds(bounds: JsonElement?): Boolean =
        bounds is JsonArray && bounds.size == 2 && bounds.all { c -> c is JsonArray && c.size == 2 && c.all { Js.num(it) != null } }

    private fun stepsOf(model: JsonElement?): JsonArray = Js.prop(model, "steps") as? JsonArray ?: EMPTY_LIST

    /**
     * Where a step with no figure looks (the web's `overviewBounds`): the brief's first overview figure's area, else every figure's area together; JSON null for
     * none.
     */
    public fun overviewBounds(model: JsonElement?): JsonElement {
        val figures = stepsOf(model).map { Js.prop(it, "figure") }.filter { Js.truthy(it) && validBounds(Js.prop(it, "bounds")) }
        figures.firstOrNull { Js.isText(Js.prop(it, "kind"), "overview") }?.let { return Js.prop(it, "bounds")!! }
        if (figures.isEmpty()) return JsonNull
        fun corner(f: JsonElement?, i: Int, j: Int): Double = Js.num(Js.at(Js.at(Js.prop(f, "bounds"), i), j))!!
        var s = corner(figures[0], 0, 0)
        var w = corner(figures[0], 0, 1)
        var n = corner(figures[0], 1, 0)
        var e = corner(figures[0], 1, 1)
        figures.drop(1).forEach { f ->
            s = minOf(s, corner(f, 0, 0))
            w = minOf(w, corner(f, 0, 1))
            n = maxOf(n, corner(f, 1, 0))
            e = maxOf(e, corner(f, 1, 1))
        }
        return buildJsonArray {
            add(buildJsonArray { add(Js.number(s)); add(Js.number(w)) })
            add(buildJsonArray { add(Js.number(n)); add(Js.number(e)) })
        }
    }

    /**
     * The steps as the sandtable lists them (the web's `sandtableSteps`): where each is in the brief ("Step 3 of 18"), its number, the section it belongs to
     * ("Landing sequence"), its title (the route or LZ/PZ it is about, else the section), who briefs it, and the planned time of what it is about (a route's start,
     * an LZ/PZ's arrival), when the plan has one. As JSON, keyed as the web's objects are; a field the step has not is not there, as JSON.stringify leaves it out.
     */
    public fun steps(model: JsonElement?): JsonArray {
        val steps = stepsOf(model)
        val flow = Js.prop(model, "flow") as? JsonArray ?: EMPTY_LIST
        return JsonArray(
            steps.mapIndexed { i, step ->
                val ref = Js.prop(step, "ref")
                val entry: JsonElement = if (Js.truthy(ref)) {
                    flow.firstOrNull { f -> Js.strictEquals(Js.prop(f, "ref"), ref) && !Js.truthy(Js.prop(f, "gone")) } ?: JsonNull
                } else {
                    JsonNull
                }
                // `entry?.name ?? null`
                val instance: JsonElement = Js.present(Js.prop(entry, "name")) ?: JsonNull
                val suffix = if (Js.truthy(instance)) " · ${Js.text(instance)}" else ""
                val title = Js.prop(step, "title")
                val titleText = Js.stringOf(title)
                val section: JsonElement? =
                    if (Js.truthy(instance) && titleText != null && titleText.endsWith(suffix)) JsonPrimitive(Js.slice(titleText, 0, -suffix.length)) else title
                val time: JsonElement = if (entry is JsonNull) {
                    JsonNull
                } else {
                    Js.present(if (Js.isText(Js.prop(entry, "kind"), "route")) Js.prop(entry, "start") else Js.prop(entry, "arrive")) ?: JsonNull
                }
                buildJsonObject {
                    Js.prop(step, "id")?.let { put("id", it) }
                    put("position", JsonPrimitive("Step ${i + 1} of ${steps.size}"))
                    Js.prop(step, "number")?.let { put("number", it) }
                    section?.let { put("section", it) }
                    // `instance ?? section`
                    (if (instance is JsonNull) section else instance)?.let { put("title", it) }
                    Js.prop(step, "who")?.let { put("who", it) }
                    put("time", time)
                }
            },
        )
    }

    /**
     * What the map shows for step [index] (the web's `stepView`): the area to fit, the kind of figure and the item in focus, and for each route and LZ/PZ of the
     * figure how it is drawn. Routes and LZ/PZs are named by ref; their lines, points and words are in the brief's `geometry`. Null for a step that is not there.
     */
    public fun view(model: JsonElement?, index: Int): JsonObject? {
        val step = stepsOf(model).getOrNull(index)
        if (!Js.truthy(step)) return null
        val figure: JsonElement = Js.present(Js.prop(step, "figure")) ?: JsonNull
        val geometry = Js.present(Js.prop(model, "geometry")) ?: EMPTY_OBJECT
        val allRoutes = Js.present(Js.prop(geometry, "routes")) ?: EMPTY_OBJECT
        val allStops = Js.present(Js.prop(geometry, "stops")) ?: EMPTY_OBJECT
        val kind: JsonElement = Js.present(Js.prop(figure, "kind")) ?: JsonNull
        val focus: JsonElement = Js.present(Js.prop(figure, "ref")) ?: JsonNull
        // A figure names what it shows; one that names nothing shows everything, as the PDF's does.
        val routeRefs = named(figure, "routes") ?: keysOf(allRoutes)
        val stopRefs = named(figure, "stops") ?: keysOf(allStops)
        val litKind = Js.isText(kind, "route") || Js.isText(kind, "departure")
        return buildJsonObject {
            Js.prop(step, "id")?.let { put("stepId", it) }
            put("kind", kind)
            put("focus", focus)
            put("bounds", if (Js.truthy(figure)) Js.prop(figure, "bounds")?.takeIf(::validBounds) ?: JsonNull else overviewBounds(model))
            put(
                "routes",
                JsonArray(
                    routeRefs.filter { Js.truthy(Js.prop(allRoutes, Js.text(it))) }.map { ref ->
                        val lit = litKind && Js.strictEquals(ref, focus)
                        buildJsonObject {
                            put("ref", ref)
                            put("lit", JsonPrimitive(lit))
                            put("dimmed", JsonPrimitive(!lit && Js.isText(kind, "route")))
                            put("labelled", JsonPrimitive(lit || Js.isText(kind, "overview") || Js.isText(kind, "rally")))
                        }
                    },
                ),
            )
            put(
                "stops",
                JsonArray(
                    stopRefs.filter { Js.truthy(Js.prop(allStops, Js.text(it))) }.map { ref ->
                        buildJsonObject {
                            put("ref", ref)
                            put("close", JsonPrimitive(Js.isText(kind, "stop") && Js.strictEquals(ref, focus)))
                        }
                    },
                ),
            )
            put("rally", JsonPrimitive(Js.isText(kind, "rally")))
        }
    }

    // `figure?.routes?.length ? figure.routes : …`: the figure's own list when it names anything.
    private fun named(figure: JsonElement, key: String): List<JsonElement>? = (Js.prop(figure, key) as? JsonArray)?.takeIf { it.isNotEmpty() }

    // `Object.keys(o)`, in JavaScript's order.
    private fun keysOf(o: JsonElement): List<JsonElement> = (o as? JsonObject)?.let { Js.orderedKeys(it.keys).map(::JsonPrimitive) }.orEmpty()

    /** The index of the step [id]; else the same place in the list ([fallback], a step that went), within it; -1 for a brief with no steps. */
    public fun indexOfStep(model: JsonElement?, id: JsonElement?, fallback: Int = 0): Int {
        val steps = stepsOf(model)
        if (steps.isEmpty()) return -1
        val found = steps.indexOfFirst { Js.strictEquals(Js.prop(it, "id"), id) }
        if (found >= 0) return found
        return minOf(maxOf(0, fallback), steps.size - 1)
    }

    /** [indexOfStep] by a step's id as the screens hold it (null: none chosen yet, the web's `null`, which no step's id is). */
    public fun indexOfStep(model: JsonElement?, id: String?, fallback: Int = 0): Int = indexOfStep(model, id?.let(::JsonPrimitive) ?: JsonNull, fallback)

    // -- Read for the screens --------------------------------------------------------------------------------------------------------------------------------------

    /** A step as the sandtable lists it ([steps]), its words as text. */
    public data class Step(
        val id: String,
        /** "Step 3 of 18": a step is called by its place in the brief, never by a page. */
        val position: String,
        val number: String,
        val section: String,
        val title: String,
        val who: String,
        /** The planned time of what it is about, when the plan has one. */
        val time: String?,
    )

    /** The steps as the sandtable lists them, as [Step]s. */
    public fun listed(model: JsonElement?): List<Step> = steps(model).map { it as JsonObject }.map { o ->
        Step(
            id = textOf(o["id"]),
            position = textOf(o["position"]),
            number = textOf(o["number"]),
            section = textOf(o["section"]),
            title = textOf(o["title"]),
            who = textOf(o["who"]),
            time = Js.present(o["time"])?.let(Js::text),
        )
    }

    /** The area a figure shows, south-west to north-east, in degrees. */
    public data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    /** How a route of a figure is drawn: [lit] (the one in focus), [dimmed] (the others on a route step) and [labelled] (its points' names shown). */
    public data class RouteLook(val ref: String, val lit: Boolean, val dimmed: Boolean, val labelled: Boolean)

    /** How an LZ/PZ of a figure is drawn: [close] (the one in focus, with its boundary, aircraft and landing heading). */
    public data class StopLook(val ref: String, val close: Boolean)

    /** What the map shows for a step ([view]), typed. */
    public data class View(
        val stepId: String,
        /** The figure's kind (`overview`, `route`, `departure`, `stop`, `rally`…); null for a step with no figure. */
        val kind: String?,
        /** The route or LZ/PZ in focus, by ref. */
        val focus: String?,
        /** The area to fly to; null for none (a figure with no valid area, or no figure in the brief at all). */
        val bounds: Bounds?,
        val routes: List<RouteLook>,
        val stops: List<StopLook>,
        /** The rally points are drawn (the rally step). */
        val rally: Boolean,
    )

    /** What the map shows for step [index], typed; null for a step that is not there. */
    public fun look(model: JsonElement?, index: Int): View? = view(model, index)?.let { v ->
        View(
            stepId = textOf(v["stepId"]),
            kind = Js.present(v["kind"])?.let(Js::text),
            focus = Js.present(v["focus"])?.let(Js::text),
            bounds = boundsOf(v["bounds"]),
            routes = (v["routes"] as JsonArray).map { r ->
                val o = r as JsonObject
                RouteLook(Js.text(o["ref"]), o.flag("lit"), o.flag("dimmed"), o.flag("labelled"))
            },
            stops = (v["stops"] as JsonArray).map { s ->
                val o = s as JsonObject
                StopLook(Js.text(o["ref"]), o.flag("close"))
            },
            rally = v.flag("rally"),
        )
    }

    /** [bounds] (`[[s, w], [n, e]]`) as [Bounds]; null when it is not one. */
    public fun boundsOf(bounds: JsonElement?): Bounds? {
        if (!validBounds(bounds)) return null
        fun at(i: Int, j: Int) = Js.num(Js.at(Js.at(bounds, i), j))!!
        return Bounds(at(0, 0), at(0, 1), at(1, 0), at(1, 1))
    }

    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.content == "true"

    // A step's words: text as it is, a number as JavaScript writes it, nothing as empty.
    private fun textOf(value: JsonElement?): String = Js.present(value)?.let(Js::text).orEmpty()

    // -- Where a figure is fitted ------------------------------------------------------------------------------------------------------------------------------------

    /** A box on the screen: its edges, in pixels (or points) from the top left. */
    public data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** How far in from each edge of the map a figure is fitted. */
    public data class Insets(val top: Double, val right: Double, val bottom: Double, val left: Double)

    /**
     * How far in from each edge of the map the figure is fitted (the web's `fitInsets`), so the script card and the constants box do not cover it. [map] is the
     * map's box; [covers] are the boxes laid over it. Each box still over the map that is left, the largest first, pushes in from the edge (top, bottom, left or
     * right) that leaves the most map: a card along the bottom pushes the bottom up, and a box in a corner then pushes in from the side. At least [min] of map is
     * always left each way.
     */
    public fun fitInsets(map: Box?, covers: List<Box?> = emptyList(), margin: Double = 16.0, min: Double = 160.0): Insets {
        val insets = doubleArrayOf(margin, margin, margin, margin)                     // top, right, bottom, left
        fun result() = Insets(insets[TOP], insets[RIGHT], insets[BOTTOM], insets[LEFT])
        if (map == null || !(map.right > map.left) || !(map.bottom > map.top)) return result()
        val width = map.right - map.left
        val height = map.bottom - map.top
        fun areaLeft(i: DoubleArray) = maxOf(0.0, width - i[LEFT] - i[RIGHT]) * maxOf(0.0, height - i[TOP] - i[BOTTOM])
        val overMap = covers.filterNotNull()
            .filter { box -> box.right > map.left && box.left < map.right && box.bottom > map.top && box.top < map.bottom }
            // `.sort((a, b) => area(b) - area(a))`: the largest first, a stable sort, as V8's.
            .sortedByDescending { (it.right - it.left) * (it.bottom - it.top) }
        overMap.forEach { box ->
            val freeLeft = map.left + insets[LEFT]
            val freeRight = map.right - insets[RIGHT]
            val freeTop = map.top + insets[TOP]
            val freeBottom = map.bottom - insets[BOTTOM]
            if (!(minOf(box.right, freeRight) > maxOf(box.left, freeLeft)) || !(minOf(box.bottom, freeBottom) > maxOf(box.top, freeTop))) return@forEach
            val ways = listOf(
                TOP to box.bottom - map.top + margin,
                BOTTOM to map.bottom - box.top + margin,
                LEFT to box.right - map.left + margin,
                RIGHT to map.right - box.left + margin,
            ).map { (side, inset) ->
                val next = insets.copyOf().also { it[side] = maxOf(it[side], inset) }
                next to areaLeft(next)
            }
            // The first of the ways that leave the most map.
            val best = ways.reduce { best, way -> if (way.second > best.second) way else best }
            best.first.copyInto(insets)
        }
        fun squeeze(a: Int, b: Int, size: Double) {
            val over = insets[a] + insets[b] - maxOf(0.0, size - min)
            if (over <= 0) return
            // Give back from the larger side first.
            val (big, small) = if (insets[a] >= insets[b]) a to b else b to a
            val fromBig = minOf(over, insets[big] - margin)
            insets[big] -= maxOf(0.0, fromBig)
            val rest = over - maxOf(0.0, fromBig)
            if (rest > 0) insets[small] = maxOf(0.0, insets[small] - rest)
        }
        squeeze(TOP, BOTTOM, height)
        squeeze(LEFT, RIGHT, width)
        return result()
    }

    private const val TOP = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val LEFT = 3

    private val EMPTY_LIST = JsonArray(emptyList())
    private val EMPTY_OBJECT = JsonObject(emptyMap())
}
