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
    const val LOOKING_UP_BODY = "Checking for a point cloud at this place…"

    const val BUILDING = "Building point cloud"
    const val BUILDING_HINT = "Takes several minutes the first time; opens at once after that."
    const val BUILDING_SAVED = "This LZ/PZ is saved, so you may close this and the build finishes in the background."
    const val BUILDING_UNSAVED = "Closing this stops the build. Save the LZ/PZ to keep it building."

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

    const val READY = "Point cloud ready"
}
