package app.ezpztac.network

import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// -- The 3D LZ view ---------------------------------------------------------------------
//
// Finding an LZ/PZ's point cloud or having it built, its files, and the ground under it (contracts/openapi.yaml, *The 3D LZ view*;
// docs/ANDROID_3D_PLAN.md). **Coordinates go only in POST bodies**: a build's progress and a tileset's files are asked for by an opaque key,
// so where an LZ is never lands in a URL, a request log or a proxy's cache.

/**
 * The paths with a key or a file in them, checked before anything is sent. The transport splits a path on `/` and OkHttp resolves `.` and
 * `..`, so a tile's `content.uri` (written by whoever built the tileset) could otherwise reach a different route: a file is refused here
 * with [IllegalArgumentException] unless every part of it is a plain name.
 */
internal object LidarPaths {
    // As the server makes them (lidar/catalog.py, KEY_PATTERN).
    private val KEY = Regex("[0-9a-f]{16}")
    // A plain name that does not start with a dot: never `.`, `..` or a hidden file.
    private val PART = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")
    private const val MAX_PARTS = 16

    fun key(key: String): String {
        require(KEY.matches(key)) { "Not a tileset key: \"$key\"" }
        return key
    }

    fun build(key: String): String = "/api/lidar/build/${key(key)}"

    fun file(key: String, file: String): String {
        val parts = file.split('/')
        require(parts.size in 1..MAX_PARTS && parts.all { PART.matches(it) }) { "Not a file of a tileset: \"$file\"" }
        return "/api/lidar/tilesets/${key(key)}/$file"
    }
}

private fun place(at: LatLon, radiusM: Double?) = buildJsonObject {
    put("lat", at.lat)
    put("lon", at.lon)
    if (radiusM != null) put("radius_m", radiusM)
}

/** Whether the point cloud covering [at] is built, and where it is if so; if not, the key a build would have and whether this server can build. */
public suspend fun ApiClient.lidarResolve(at: LatLon, radiusM: Double? = null): LidarResolveDto =
    decode(execute(ApiClient.Call("POST", "/api/lidar/resolve", body = place(at, radiusM))))

/**
 * Asks for the point cloud at [at] to be built: the build, queued or running (202), or the tileset when it is built already (200). [watcher]
 * says who waits (the service drops a build nobody has asked after for 90 s); [keep] has it finish regardless, as for a saved LZ/PZ.
 */
public suspend fun ApiClient.lidarBuild(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double? = null): LidarBuildDto =
    decode(
        execute(
            ApiClient.Call(
                "POST", "/api/lidar/build",
                body = buildJsonObject {
                    put("lat", at.lat)
                    put("lon", at.lon)
                    if (radiusM != null) put("radius_m", radiusM)
                    put("keep", keep)
                    if (watcher != null) put("watcher", watcher)
                },
            ),
        ),
    )

/** How the build [key] is going. Asking counts as still waiting for it; [keep] marks it to be finished regardless (the LZ/PZ was saved meanwhile). */
public suspend fun ApiClient.lidarBuildStatus(key: String, watcher: String?, keep: Boolean): LidarBuildDto {
    val query = buildMap {
        if (watcher != null) put("watcher", watcher)
        if (keep) put("keep", "1")
    }
    return decode(execute(ApiClient.Call("GET", LidarPaths.build(key), query = query)))
}

/** Stops waiting for the build [key]: the service drops it unless it is kept or someone else waits. Its answer says nothing the app needs. */
public suspend fun ApiClient.lidarRelease(key: String, watcher: String?) {
    execute(ApiClient.Call("DELETE", LidarPaths.build(key), query = if (watcher != null) mapOf("watcher" to watcher) else emptyMap()))
}

/** The most a tileset's file may hold: py3dtiles writes tiles of tens of thousands of points, well under a megabyte each. */
public const val TILESET_FILE_MAX_BYTES: Long = 32L * 1024 * 1024

/**
 * One file of the tileset [key]: [file] is its path inside it (`tileset.json`, `points/r0.pnts`, `context/tileset.json`), as the bytes that
 * came, at most [TILESET_FILE_MAX_BYTES] (`too_large` past it). Not held back for heavy work: the files are static and cost the server almost
 * nothing.
 */
public suspend fun ApiClient.lidarTilesetFile(key: String, file: String): ByteArray =
    execute(ApiClient.Call("GET", LidarPaths.file(key, file), binary = true, maxBytes = TILESET_FILE_MAX_BYTES)).bytes

/** A terrain tile's heights: [TERRAIN_SAMPLES] × [TERRAIN_SAMPLES], rows from north to south, metres above the ellipsoid. */
public const val TERRAIN_SAMPLES: Int = 65

/** The deepest terrain level the server serves. */
public const val TERRAIN_MAX_LEVEL: Int = 22

private const val HEIGHTMAP_BYTES = TERRAIN_SAMPLES * TERRAIN_SAMPLES * 2

/**
 * The ground of the terrain tile [level]/[x]/[y] (Cesium's geographic tiling), or null where no DEM reaches it (204): no data, never sea level.
 * Background work: a tile not yet cached is computed on the server, so it waits while an analysis, a viewshed or an export runs. An answer
 * that is not [TERRAIN_SAMPLES]² heights is an [ApiException], never read as ground.
 */
public suspend fun ApiClient.terrainHeightmap(level: Int, x: Int, y: Int): ShortArray? {
    require(level in 0..TERRAIN_MAX_LEVEL && x >= 0 && y >= 0) { "Not a terrain tile: $level/$x/$y" }
    val response = execute(
        ApiClient.Call("GET", "/api/terrain/heightmap/$level/$x/$y", binary = true, callPriority = CallPriority.BACKGROUND, maxBytes = HEIGHTMAP_BYTES.toLong()),
    )
    if (response.status == 204) return null
    val bytes = response.bytes
    if (bytes.size != HEIGHTMAP_BYTES) {
        throw ApiException(response.status, "unreadable_response", "A terrain tile of ${bytes.size} bytes is not one.")
    }
    return ShortArray(TERRAIN_SAMPLES * TERRAIN_SAMPLES) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
}

/** The most points one heights request may ask about (terrain_tiles.MAX_POINTS). */
public const val TERRAIN_HEIGHTS_MAX: Int = 5000

/**
 * Ground and geoid at each of [points], in their order: the ground in metres above the ellipsoid from the DEMs the terrain tiles come from
 * (null where none covers it), and the geoid separation, which turns an MSL height into one above the ellipsoid. An answer that is not one of
 * each for every point is refused as unreadable, never read against the wrong points.
 */
public suspend fun ApiClient.terrainHeights(points: List<LatLon>): TerrainHeightsDto {
    require(points.size <= TERRAIN_HEIGHTS_MAX) { "At most $TERRAIN_HEIGHTS_MAX points, not ${points.size}" }
    if (points.isEmpty()) return TerrainHeightsDto(emptyList(), emptyList())
    val response = execute(
        ApiClient.Call(
            "POST", "/api/terrain/heights",
            body = buildJsonObject { put("points", JsonArray(points.map { buildJsonObject { put("lat", it.lat); put("lon", it.lon) } })) },
        ),
    )
    val answer: TerrainHeightsDto = decode(response)
    if (answer.groundM.size != points.size || answer.geoidM.size != points.size) {
        throw ApiException(response.status, "unreadable_response", "Heights for ${answer.groundM.size} and ${answer.geoidM.size} points, not ${points.size}.")
    }
    return answer
}
