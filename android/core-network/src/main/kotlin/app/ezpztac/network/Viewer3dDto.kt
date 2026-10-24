package app.ezpztac.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The 3D view's bodies (contracts/openapi.yaml, *The 3D LZ view*; docs/ANDROID_3D_PLAN.md), held to the recorded responses as Dto.kt's
 * are. A tileset's own files are not here: `tileset.json` is a 3D Tiles document, read by core-scene3d's reader, which passes over what
 * it does not know, as the format asks.
 */

/** Where a point cloud is, or the key a build of it would have. */
@Serializable
public data class LidarResolveDto(
    val key: String,
    val available: Boolean,
    /** The tileset's root, relative to the API's root (`/lidar/tilesets/<key>/tileset.json`); null when it is not built. */
    val url: String? = null,
    /** The thinned ring round it, relative to the API's root; null when none was built. */
    val contextUrl: String? = null,
    /** Not built only: whether this server can build it. */
    val canBuild: Boolean? = null,
    /** Not built only: the caller's own place, echoed. */
    val target: LidarTargetDto? = null,
)

@Serializable
public data class LidarTargetDto(val lat: Double, val lon: Double, @SerialName("radius_m") val radiusM: Double)

/**
 * A build as the service reports it (`state` queued, running, done, failed or cancelled; `stage` a word for what it is doing, which may be
 * one this version does not know), or a place already built (`state` done, with where it is, and no stage).
 */
@Serializable
public data class LidarBuildDto(
    val key: String,
    val state: String,
    val stage: String? = null,
    val error: String? = null,
    /** How many builds are ahead of a queued one. */
    val position: Int? = null,
    /** How long a started one has run, in whole seconds. */
    @SerialName("elapsed_s") val elapsedS: Int? = null,
    val available: Boolean? = null,
    val url: String? = null,
    val contextUrl: String? = null,
)

/** Ground and geoid at each point asked about, in its order: ground above the ellipsoid (null where no DEM covers it), and the geoid there. */
@Serializable
public data class TerrainHeightsDto(val groundM: List<Double?>, val geoidM: List<Double>)
