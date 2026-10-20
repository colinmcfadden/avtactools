package app.ezpztac.scene3d

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.floor

/** How the web's 3D view looks and moves (`sceneSettings.js`, held to `contracts/fixtures/viewer3d/scene.json`). */
public object SceneLook {
    /** The scene's background, `#05080d`, as ARGB. */
    public const val BACKGROUND: Int = 0xFF05080D.toInt()

    /** The opening view, and the *LZ* button's: from 30° west of north, 35° down, 2.2 times the cloud's radius back. */
    public const val CAMERA_HEADING_DEG: Double = -30.0
    public const val CAMERA_PITCH_DEG: Double = -35.0
    public const val CAMERA_RANGE_FACTOR: Double = 2.2

    /** The largest a point is drawn, in dp as the web's in CSS pixels: the person's to choose, 2 to 16, 5 at first. */
    public const val DEFAULT_POINT_SIZE: Int = 5
    public const val MIN_POINT_SIZE: Int = 2
    public const val MAX_POINT_SIZE: Int = 16

    /** The core is drawn to 2 pixels of error, the thinned ring round it to 8. */
    public const val CORE_SCREEN_SPACE_ERROR: Double = 2.0
    public const val CONTEXT_SCREEN_SPACE_ERROR: Double = 8.0

    public const val EDL_STRENGTH: Double = 0.5
    public const val EDL_RADIUS: Double = 0.5

    /** The *LZ* and *Routes* flights, and the compass's turn to face north. */
    public const val FRAME_MS: Long = 1_200
    public const val FACE_NORTH_MS: Long = 600

    /** [size] when it is one of the person's choices, else the first one (the web's `storedPointSize`). */
    public fun pointSizeOrDefault(size: Int?): Int = size?.takeIf { it in MIN_POINT_SIZE..MAX_POINT_SIZE } ?: DEFAULT_POINT_SIZE
}

/**
 * How the points are sized and shaded, as Cesium does it for the web's tilesets (attenuation on, eye-dome lighting at [SceneLook.EDL_STRENGTH]
 * and [SceneLook.EDL_RADIUS], the largest point the size the person chose): ported from `PointCloudStylingPipelineStage.js` and
 * `PointCloudStylingStageVS.glsl` (a point's size) and `PointCloudEyeDomeLighting.js` / `.glsl` (its shade). The shaders that draw them are
 * feature-viewer3d's, and these are what they are given.
 */
public object PointShading {
    /** How many of a tile's points Cesium looks at to guess how far apart they are (`computeApproximateExtrema`). */
    public const val SAMPLES: Int = 20

    /** The largest a point is drawn, in the buffer's pixels: the person's size times the pixel ratio (`model_pointCloudParameters.x`). */
    public fun maximumPx(size: Int, camera: ViewCamera): Double = size * camera.pixelRatio

    /**
     * The least a point is drawn, in the buffer's pixels: one of the web's CSS pixels. The web's Cesium draws at the browser's own resolution
     * (`useBrowserRecommendedResolution`, its default: a pixel ratio of 1), so its smallest point, a pixel, is a CSS pixel across on any screen;
     * here the buffer has the screen's pixels, and a point one of them across would be a third the size on a dense phone, and all but vanish.
     */
    public fun minimumPx(camera: ViewCamera): Double = camera.pixelRatio

    /** What turns a geometric error over an eye depth into the buffer's pixels: its height over the frustum's SSE denominator (`.z`). */
    public fun depthMultiplier(camera: ViewCamera): Double = camera.heightPx / camera.sseDenominator

    /**
     * A point's size [depth] metres in front of the eye, in the buffer's pixels (`getPointSizeFromAttenuation`), never less than [minimumPx]
     * (the least the GL draws a point, in the web's pixels): the shader's, here to be tested.
     */
    public fun sizePx(geometricError: Double, depth: Double, depthMultiplier: Double, maximumPx: Double, minimumPx: Double): Double =
        maxOf(minOf(geometricError / depth * depthMultiplier, maximumPx), minimumPx)

    /** The eye-dome lighting's radius in the buffer's pixels (`eyeDomeLightingRadius × pixelRatio`). */
    public fun edlRadiusPx(camera: ViewCamera): Double = SceneLook.EDL_RADIUS * camera.pixelRatio

    /** What is left of a point's colour once its neighbours stand [response] in front of it (the mean of how much nearer they are, in log₂ depth). */
    public fun shade(response: Double, strength: Double = SceneLook.EDL_STRENGTH): Double = exp(-response * 300.0 * strength)

    /**
     * The geometric error a tile's points are sized by (`getGeometricError`): the tile's own (scaled by its frame, as [Tile.geometricError] is) when
     * above 0, else [estimated], Cesium's guess at their spacing that the `.pnts` reader worked out ([ScenePoints.estimatedError]).
     */
    public fun geometricError(tile: Tile, estimated: Double): Double = if (tile.geometricError > 0) tile.geometricError else estimated

    /**
     * Cesium's guess at a tile's spacing: the cube root of the volume of [sampled] (the box round the points it samples, in the `.pnts`'s own
     * coordinates, before any transform: it measures them there, its node's transform and not the tile's, so a tileset that stores its points
     * scaled down has its leaves drawn as much smaller, and so they are here) over the tile's [count] of points. A sample with no extent gives 0,
     * as Cesium's does: its points are then drawn as small as a point is drawn ([minimumPx]).
     */
    public fun estimate(sampled: Vec3?, count: Int): Double {
        if (sampled == null || count <= 0) return 0.0
        return cbrt(sampled.x * sampled.y * sampled.z / count).takeIf { it.isFinite() && it > 0 } ?: 0.0
    }

    /**
     * Which of a tile's [count] points Cesium samples: `floor(r × count)` for the first `min(count, 20)` of the same twenty draws (its twister
     * from seed 0, made once for every tile: `getRandomValues`).
     */
    public fun sampleIndices(count: Int): IntArray {
        if (count <= 0) return IntArray(0)
        return IntArray(minOf(count, SAMPLES)) { i -> floor(draws[i] * count).toInt() }
    }

    internal val draws: DoubleArray = Twister(0).let { twister -> DoubleArray(SAMPLES) { twister.random() } }
}
