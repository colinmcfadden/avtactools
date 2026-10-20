package app.ezpztac.viewer3d

import app.ezpztac.data.LidarProblem

/**
 * What the 3D view says, in the web's words where it has them (`Lz3DWindow.jsx`) and the app's own for a failure: the server's own text
 * for one is never shown (a failed build's can carry a tool's output and the place's coordinates).
 */
object Viewer3DWords {
    /** The bar's title: the owner's `3D · NAME` (2026-10-20). */
    fun title(name: String): String = "3D · $name"

    fun pane(name: String): String = "3D view of $name"

    const val CLOSE = "Close 3D view"

    const val LOOKING_UP = "Looking up"
    const val LOOKING_UP_BODY = "Checking for a point cloud at this location…"

    const val BUILDING = "Building point cloud"
    const val BUILDING_HINT = "Takes several minutes the first time; opens at once after that."
    const val BUILDING_SAVED = "This LZ/PZ is saved, so you may close this and the build finishes in the background."
    const val BUILDING_UNSAVED = "Closing this stops the build. Save the LZ/PZ to keep it building."
    const val BUILDING_IN_PACK = "Closing this stops the build."

    /**
     * What closing the view does to the build: a saved LZ/PZ's goes on; anyone else's stops, and saving keeps it going, except a mission pack's, which
     * is the pack's and is never saved to the Library (**not like the web**, which tells a pack's members to save it).
     */
    fun closing(saved: Boolean, inPack: Boolean): String = when {
        saved -> BUILDING_SAVED
        inPack -> BUILDING_IN_PACK
        else -> BUILDING_UNSAVED
    }

    /** The build service's stages as a crew member would want to read them (the web's STAGE_LABELS); one this version does not know, as it is. */
    fun stage(stage: String?): String = when (stage) {
        null, "" -> "Starting"
        "waiting" -> "Queued"
        "starting" -> "Starting"
        "locating survey" -> "Finding the LiDAR survey"
        "processing points" -> "Processing points"
        "building tiles" -> "Building 3D tiles"
        "building context" -> "Building the surrounding area"
        else -> stage
    }

    /** The stage, and how many builds are ahead of it in the queue. */
    fun progress(stage: String?, ahead: Int?): String = stage(stage) + if (ahead != null && ahead > 0) " — $ahead ahead in the queue" else ""

    /** Minutes and seconds, `4:07`, by hand (no locale). */
    fun elapsed(seconds: Int): String {
        val s = maxOf(0, seconds)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    const val MISSING = "Not generated"
    const val MISSING_BODY = "This server can’t build point clouds. Whoever runs it can set up the build service (backend/lidar/SERVER_SETUP.md)."
    const val CHECK_AGAIN = "Check again"

    const val RETRY = "Retry"

    fun failedTitle(buildFailed: Boolean): String = if (buildFailed) "Build failed" else "Lookup failed"

    fun problem(problem: LidarProblem?): String = when (problem) {
        LidarProblem.LOOKUP_FAILED, null -> "The server could not look up a point cloud for this place."
        LidarProblem.LOOKUP_UNREACHABLE -> "The server could not be reached. Check the connection and try again."
        LidarProblem.BUILD_REFUSED -> "The point-cloud build service is not available right now."
        LidarProblem.QUEUE_FULL -> "The build queue is full. Try again in a few minutes."
        LidarProblem.BUILD_UNREACHABLE -> "The server could not be reached to start the build. Check the connection and try again."
        LidarProblem.BUILD_FAILED -> "The point cloud could not be built for this place."
        LidarProblem.LOST_CONTACT -> "Lost contact with the build."
    }

    const val NO_GROUND = "No terrain here: the server has no ground for this place, so the points have nothing drawn under them."

    // The scene, once its point cloud is in hand (the web's `Viewer3D.jsx`).
    const val SCENE_LOADING = "Loading"
    const val SCENE_LOADING_BODY = "Preparing point cloud…"
    const val SCENE_FAILED = "3D view failed"

    /** Why the scene is not drawn, in the app's words (the web shows the error's own text). */
    fun sceneProblem(problem: SceneProblem): String = when (problem) {
        SceneProblem.UNREACHABLE -> "The point cloud could not be fetched. Check the connection and try again."
        SceneProblem.UNREADABLE -> "The point cloud's files could not be read."
        SceneProblem.NOT_A_TILESET -> "The server named a point cloud this version of the app cannot open."
        SceneProblem.NO_GL -> "This device cannot draw the 3D view: it needs OpenGL ES 3.0."
        SceneProblem.GL_FAILED -> "This device could not set up the 3D view."
    }

    const val LZ = "LZ"
    const val FRAME_LZ = "Frame the landing zone"
    const val ROUTES = "Routes"
    const val FRAME_ROUTES = "Frame the visible routes"
    const val NO_ROUTES = "No visible routes to show"
    const val FACE_NORTH = "Face north"
    const val POINT_SIZE = "Point size"

    /**
     * What a screen reader hears of the scene itself: once it is [drawn], that it is, and how the fingers move it and its actions step it (the change
     * is said as it comes).
     */
    fun scene(name: String, drawn: Boolean): String = if (!drawn) "Point cloud of $name." else
        "Point cloud of $name, drawn. One finger moves the view, two fingers pinch to zoom and twist to turn, and two fingers up or down tilt it; " +
            "its actions do each a step at a time."

    /** A screen reader's steps of the camera ([SceneSession.step]). */
    fun step(step: SceneStep): String = when (step) {
        SceneStep.TURN_LEFT -> "Turn left"
        SceneStep.TURN_RIGHT -> "Turn right"
        SceneStep.TILT_UP -> "Tilt toward the horizon"
        SceneStep.TILT_DOWN -> "Tilt to look down"
        SceneStep.ZOOM_IN -> "Zoom in"
        SceneStep.ZOOM_OUT -> "Zoom out"
    }
}
