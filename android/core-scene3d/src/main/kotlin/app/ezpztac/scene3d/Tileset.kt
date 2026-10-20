package app.ezpztac.scene3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min

/** How a tile's children refine it: drawn with it ([ADD]) or in its place once they are all there ([REPLACE]). */
public enum class Refine { ADD, REPLACE }

/** What a tile holds, by its path inside the tileset's store (as `points/r0.pnts`, `context/tileset.json`). */
public sealed interface TileContent {
    public val path: String
}

/** A `.pnts` point cloud, which the view draws. */
public data class PointsContent(override val path: String) : TileContent

/** A tileset of its own, whose root becomes this tile's child once it is read. */
public data class TilesetContent(override val path: String) : TileContent

/** A format this view does not draw (a model, an instanced or composite tile): kept so a tile with it is still traversed, never fetched. */
public data class OtherContent(override val path: String) : TileContent

/**
 * One tile of a tileset: how much error it carries ([geometricError], metres), how its children refine it, where it is ([volume], in the
 * scene's frame) and what it holds. [world] is its frame to geocentric metres, every `transform` from the root composed (Cesium's
 * `computedTransform`), and [geometricError] the file's error times the most [world] stretches anything (Cesium's
 * `updateGeometricErrorScale`): py3dtiles writes an octree's points ten or a hundred times smaller than they are, with a transform that scales
 * them back, and its errors for the points as they are drawn. [source] is the tileset.json it was read from.
 */
public class Tile internal constructor(
    public val depth: Int,
    public val parent: Tile?,
    public val geometricError: Double,
    public val refine: Refine,
    public val world: Mat4,
    public val volume: BoundingVolume,
    public val content: TileContent?,
    public val source: String,
    internal val tree: TileTree,
) {
    private val kids = ArrayList<Tile>()

    /** Its children; a tile whose content is a tileset gains that tileset's root once it is read. */
    public val children: List<Tile> get() = kids

    internal fun addChild(child: Tile) {
        kids += child
    }

    /** Points to draw (Cesium's `hasRenderableContent`): not an empty tile, a tileset or a format not drawn. */
    public val hasRenderableContent: Boolean get() = content is PointsContent

    public val hasTilesetContent: Boolean get() = content is TilesetContent

    override fun toString(): String = "Tile(depth=$depth, error=$geometricError, $refine, ${content?.path})"
}

/**
 * A tileset as read: its own geometric error (what its root is measured with from afar, scaled as the root's is: Cesium's
 * `_scaledGeometricError`), its root, and how many tiles it has.
 */
public class TilesetDocument internal constructor(public val path: String, public val geometricError: Double, public val root: Tile, public val tileCount: Int)

/** Every tile one tileset and the tilesets joined into it hold, counted together, so one that names itself or branches cannot grow without end. */
internal class TileTree {
    var tiles = 0
}

/** A tileset that cannot be read, in words for a log; the view says so in its own. */
public class TilesetException(message: String) : Exception(message)

/**
 * Reads a `tileset.json` (3D Tiles 1.0, as py3dtiles 12.1.1 writes it: contracts/fixtures/viewer3d/tileset) into tiles in the scene's frame.
 * The file is the server's, built by a toolchain, and read as untrusted: it is at most [MAX_BYTES] and nested no deeper than
 * [JsonNesting.MAX_DEPTH] (a parser recurses into arrays, and a few kilobytes of brackets would exhaust its stack); every number must be a
 * finite number, a bounding volume one of the three kinds with the right count and finite in the scene, a transform sixteen values, affine
 * and finite once composed, the tree no deeper than [MAX_DEPTH] nor larger than [MAX_TILES] with every tileset joined into it, and a
 * content's path one inside the store ([TilesetPaths]). Anything else in it is passed over, as the format asks. py3dtiles splits a tree
 * into files of about 100 KB, so the limits leave it room many times over.
 */
public object TilesetReader {
    public const val MAX_BYTES: Int = 2 * 1024 * 1024
    public const val MAX_DEPTH: Int = 32
    public const val MAX_TILES: Int = 200_000

    private val json = Json { isLenient = false }

    /**
     * The tileset at [path] (in its store), in [frame]. [parent]: the tile whose content it is, for a tileset inside another, which this
     * joins as its one child; a tile joined already, one whose content is not this tileset, and a tileset that one of the tile's own holds
     * (it would hold itself) are refused.
     */
    public fun read(bytes: ByteArray, path: String, frame: LocalFrame, parent: Tile? = null): TilesetDocument {
        if (bytes.size > MAX_BYTES) throw TilesetException("A tileset of ${bytes.size} bytes is more than $MAX_BYTES")
        if (parent != null) {
            if ((parent.content as? TilesetContent)?.path != path) throw TilesetException("The tile does not hold $path")
            if (parent.children.isNotEmpty()) throw TilesetException("$path has joined already")
            if (generateSequence(parent) { it.parent }.any { it.source == path }) throw TilesetException("$path holds itself")
        }
        val text = bytes.decodeToString()
        if (!JsonNesting.within(text)) throw TilesetException("Nested deeper than ${JsonNesting.MAX_DEPTH}")
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw TilesetException("Not JSON: ${e.message}")
        } as? JsonObject ?: throw TilesetException("A tileset is an object")
        val asset = root["asset"] as? JsonObject ?: throw TilesetException("No asset")
        (asset["version"] as? JsonPrimitive)?.contentOrNull ?: throw TilesetException("No asset version")
        val geometricError = finite(root["geometricError"], "the tileset's geometricError").also { if (it < 0) throw TilesetException("A negative geometricError") }
        val top = root["root"] as? JsonObject ?: throw TilesetException("No root")
        val tree = parent?.tree ?: TileTree()
        // Counted on a copy, so a tileset refused part way leaves the tree as it was.
        val counter = intArrayOf(tree.tiles)
        val tile = tile(top, path, frame, parent, parent?.world ?: Mat4.IDENTITY, parent?.refine, (parent?.depth ?: -1) + 1, counter, tree)
        val count = counter[0] - tree.tiles
        tree.tiles = counter[0]
        parent?.addChild(tile)
        return TilesetDocument(path, geometricError * tile.world.maximumScale, tile, count)
    }

    private fun tile(
        o: JsonObject, path: String, frame: LocalFrame, parent: Tile?, parentWorld: Mat4, parentRefine: Refine?, depth: Int, counter: IntArray, tree: TileTree,
    ): Tile {
        if (depth > MAX_DEPTH) throw TilesetException("Tiles deeper than $MAX_DEPTH")
        if (++counter[0] > MAX_TILES) throw TilesetException("More than $MAX_TILES tiles")
        // The parent's frame first, then the tile's own: Cesium's computedTransform.
        val world = (o["transform"]?.takeIf { it !is JsonNull }?.let(::matrix) ?: Mat4.IDENTITY).let { parentWorld * it }
        if (!world.isFinite) throw TilesetException("A transform that is not finite once composed")
        val error = finite(o["geometricError"], "a tile's geometricError").also { if (it < 0) throw TilesetException("A negative geometricError") } * world.maximumScale
        if (!error.isFinite()) throw TilesetException("A geometricError that is not finite once scaled")
        // Cesium's rule: REPLACE in any case, else ADD; none given, the parent's; none at the root, REPLACE.
        val refine = (o["refine"] as? JsonPrimitive)?.contentOrNull?.let { if (it.uppercase() == "REPLACE") Refine.REPLACE else Refine.ADD }
            ?: parentRefine ?: Refine.REPLACE
        val volume = volume(o["boundingVolume"] as? JsonObject ?: throw TilesetException("A tile with no boundingVolume"), world, frame)
        val content = (o["content"] as? JsonObject)?.let { c ->
            // 3D Tiles 1.0 says `uri`; the drafts before it said `url`, which Cesium still reads. An empty one is no content, as Cesium takes it.
            val uri = ((c["uri"] ?: c["url"]) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw TilesetException("A content with no uri")
            if (uri.isEmpty()) return@let null
            val resolved = TilesetPaths.resolve(path, uri) ?: throw TilesetException("A content outside the tileset: \"$uri\"")
            when (resolved.substringAfterLast('.', "").lowercase()) {
                "pnts" -> PointsContent(resolved)
                "json" -> TilesetContent(resolved)
                else -> OtherContent(resolved)
            }
        }
        val tile = Tile(depth, parent, error, refine, world, volume, content, path, tree)
        when (val children = o["children"]) {
            null, is JsonNull -> Unit
            is JsonArray -> children.forEach { child ->
                tile.addChild(tile(child as? JsonObject ?: throw TilesetException("A child that is not a tile"), path, frame, tile, world, refine, depth + 1, counter, tree))
            }
            else -> throw TilesetException("children is not a list")
        }
        return tile
    }

    private fun finite(element: JsonElement?, what: String): Double {
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw TilesetException("No number for $what")
        if (!value.isFinite()) throw TilesetException("$what is not finite")
        return value
    }

    private fun numbers(element: JsonElement?, count: Int, what: String): List<Double> {
        val array = element as? JsonArray ?: throw TilesetException("$what is not a list")
        if (array.size != count) throw TilesetException("$what has ${array.size} values, not $count")
        return array.map { finite(it, what) }
    }

    private fun matrix(element: JsonElement): Mat4 {
        val m = Mat4.columnMajor(numbers(element, 16, "a transform"))
        if (!m.isAffine) throw TilesetException("A transform that is not affine")
        return m
    }

    private fun volume(o: JsonObject, world: Mat4, frame: LocalFrame): BoundingVolume {
        val volume = shape(o, world, frame)
        // Finite numbers can still make an infinite volume once moved into the scene (a huge transform, a huge box).
        val finite = volume.center.isFinite && volume.radius.isFinite() && (volume !is OrientedBox || volume.halfAxes.all { it.isFinite })
        if (!finite) throw TilesetException("A bounding volume that is not finite in the scene")
        return volume
    }

    private fun shape(o: JsonObject, world: Mat4, frame: LocalFrame): BoundingVolume {
        val toScene = frame.fromEcef * world
        o["box"]?.let { box ->
            val v = numbers(box, 12, "a box")
            return OrientedBox(Vec3(v[0], v[1], v[2]), listOf(Vec3(v[3], v[4], v[5]), Vec3(v[6], v[7], v[8]), Vec3(v[9], v[10], v[11]))).transformed(toScene)
        }
        o["sphere"]?.let { sphere ->
            val v = numbers(sphere, 4, "a sphere")
            if (v[3] < 0) throw TilesetException("A sphere of negative radius")
            return Sphere(Vec3(v[0], v[1], v[2]), v[3]).transformed(toScene)
        }
        o["region"]?.let { region ->
            val v = numbers(region, 6, "a region")
            return regionBox(v[0], v[1], v[2], v[3], v[4], v[5], frame)
        }
        throw TilesetException("A bounding volume that is not a box, a sphere or a region")
    }
}

/**
 * A region (radians west, south, east and north, and metres above the ellipsoid; a tile's transform does not apply to it) as a box along the
 * scene's axes that holds it: its corners, edges' middles and centre at both heights, and the bulge of the earth between them. Coarser than
 * Cesium's own box for a region, and only ever larger, so it culls nothing that should show.
 */
internal fun regionBox(west: Double, south: Double, east0: Double, north: Double, low: Double, high: Double, frame: LocalFrame): OrientedBox {
    val east = if (east0 < west) east0 + 2 * Math.PI else east0
    var lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    var hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    for (i in 0..4) for (j in 0..4) for (h in listOf(low, high)) {
        val lon = west + (east - west) * i / 4.0
        val lat = south + (north - south) * j / 4.0
        val p = frame.fromGeodetic(Math.toDegrees(lat), Math.toDegrees(lon), h)
        lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
        hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
    }
    // Between the samples the ground bulges up by at most the sagitta of the widest span.
    val span = maxOf(hi.x - lo.x, hi.y - lo.y)
    val sagitta = span * span / (8 * Wgs84.A)
    hi = Vec3(hi.x, hi.y, hi.z + sagitta)
    val center = (lo + hi) * 0.5
    val half = (hi - lo) * 0.5
    return OrientedBox(center, listOf(Vec3(half.x, 0.0, 0.0), Vec3(0.0, half.y, 0.0), Vec3(0.0, 0.0, half.z)))
}

/**
 * Where a tile's content is in the tileset's store. A `uri` is relative to the tileset.json that names it, and was written by whoever built
 * the tileset, so it may not leave the store: an absolute path or URL, a query, a fragment, an escape, a backslash or any part that is not a
 * plain name is refused, and `..` may climb only as far as the store's top.
 */
public object TilesetPaths {
    private val PART = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")

    /** [uri] from the tileset at [base] (`tileset.json`, `context/tileset.json`), as a path in the store, or null when it is not one. */
    public fun resolve(base: String, uri: String): String? {
        if (uri.isEmpty() || uri.length > 1024) return null
        if (uri.any { it == '\\' || it == '?' || it == '#' || it == '%' || it == ':' || it.isWhitespace() } || uri.startsWith("/")) return null
        val parts = ArrayList(base.split('/').dropLast(1))
        if (parts.any { !PART.matches(it) }) return null
        for (part in uri.split('/')) {
            when (part) {
                "." -> continue
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> if (PART.matches(part)) parts += part else return null
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }
}

/**
 * How deeply a JSON text nests its arrays and objects, read before it is parsed: kotlinx.serialization's parser recurses into arrays, and five
 * thousand brackets (ten kilobytes) end in a StackOverflowError, which is no exception a caller catches. What the 3D view reads nests a few
 * levels: a tileset's children go deepest, and [TilesetReader.MAX_DEPTH] of them, three levels each, fit with room to spare.
 */
internal object JsonNesting {
    const val MAX_DEPTH: Int = 128

    /** Whether [text] nests no deeper than [max], counting brackets outside its strings. */
    fun within(text: String, max: Int = MAX_DEPTH): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[', '{' -> if (++depth > max) return false
                ']', '}' -> depth--
            }
        }
        return true
    }
}
