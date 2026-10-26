package app.ezpztac.viewer3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import app.ezpztac.scene3d.Mat4
import app.ezpztac.scene3d.PointShading
import app.ezpztac.scene3d.SceneDepth
import app.ezpztac.scene3d.SceneLook
import app.ezpztac.scene3d.TerrainMeshes
import app.ezpztac.scene3d.ViewCamera
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws a [SceneSession] on the GL thread (docs/ANDROID_3D_PLAN.md, V4), only when asked: the view renders when something changed (a finger, a
 * tile, a flight, the point size, the routes), never on a clock. Each tileset's points go into the [PointTarget], sized as Cesium's attenuation
 * sizes them, then are lit by the eye-dome lighting onto the screen at their own depth, the core first and the ring after it, over the scene's
 * `#05080d`, as Cesium lights each tileset on its own; the routes over them ([RouteDrawing]). A context that is lost takes every buffer with it; the next is set up afresh, and the tiles go onto
 * it again from the session, which keeps them. Its calls are held to Android's GL binding's own checks (SceneRendererTest); what they draw cannot
 * be seen from an agent session.
 */
internal class SceneRenderer(
    private val session: SceneSession,
    private val pixelRatio: Double,
    private val again: () -> Unit,
) : GLSurfaceView.Renderer {
    private var programs: ScenePrograms? = null
    private var routePrograms: RoutePrograms? = null
    private var target: PointTarget? = null
    private val buffers = PointBuffers()
    private val ground = GroundBuffers()
    private val textures = ImageryTextures()
    private val routes = RouteDrawing()
    private var width = 0
    private var height = 0
    private var failed = false

    // Every call in from the GL thread is guarded: an exception there has nothing above it, and would end the app. A RuntimeException is what
    // Android's binding throws for a call it refuses (a buffer shorter than its size, an array shorter than its count).
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever the last one held went with it. The imagery is fetched again (the HTTP cache has it); the rest is in hand.
        buffers.forget()
        ground.forget()
        textures.forget()
        routes.forget()
        session.texturesLost(again)
        target = null
        programs = try {
            ScenePrograms.build()
        } catch (_: GlException) {
            fail()
            null
        } catch (_: RuntimeException) {
            fail()
            null
        }
        // The routes have programs of their own: ones that will not build cost the routes, never the scene.
        routePrograms = if (programs == null) null else try {
            RoutePrograms.build(ScenePrograms::link)
        } catch (_: GlException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        try {
            target?.release()
            target = null
            if (programs == null || w <= 0 || h <= 0) return
            target = PointTarget.create(w, h)
        } catch (_: RuntimeException) {
            target = null
        }
        if (target == null) fail()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(BACKGROUND_R, BACKGROUND_G, BACKGROUND_B, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // Once the view could not draw, nothing is drawn behind the words that say so, though a new context might build its programs.
        if (session.cannotBeDrawn) return
        val p = programs ?: return
        val t = target ?: return
        // A fault in drawing (or no memory to draw with) is the 3D view's to say, never the app's end: an exception on the GL thread has nothing
        // above it to catch it.
        try {
            draw(p, routePrograms, t)
        } catch (_: RuntimeException) {
            fail()
            programs = null
        } catch (_: OutOfMemoryError) {
            fail()
            programs = null
        }
    }

    private fun draw(p: ScenePrograms, r: RoutePrograms?, t: PointTarget) {
        val work = session.frame(width, height, pixelRatio, again) ?: return
        for (gone in work.letGo) buffers.release(gone)
        for (gone in work.groundLetGo) ground.release(gone)
        for (gone in work.imageryLetGo) textures.release(gone)
        // The imagery decoded since the last frame, onto the GPU (a few a frame).
        for ((id, image) in work.uploads) {
            if (textures.upload(id, image)) session.uploaded(id, image.bytesOnGpu, again) else session.notUploaded(id, again)
            image.release()
        }
        val camera = work.camera
        val view = camera.view.floats()
        val projection = camera.projection.floats()
        drawGround(p, work, view, projection)
        // The points are not hidden by the ground, as the web's are not (its globe's depth is cleared before them: `depthTestAgainstTerrain`
        // off), or the ground's own returns would sink into it.
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        val largest = PointShading.maximumPx(work.pointSize, camera).toFloat()
        val least = PointShading.minimumPx(camera).toFloat()
        val multiplier = PointShading.depthMultiplier(camera).toFloat()
        val radius = PointShading.edlRadiusPx(camera).toFloat()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        for (cloud in work.clouds) {
            if (cloud.isEmpty()) continue
            // The cloud's points into the target, nearest tiles first.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.framebuffer)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glUseProgram(p.points)
            GLES30.glUniformMatrix4fv(p.pointsView, 1, false, view, 0)
            GLES30.glUniformMatrix4fv(p.pointsProjection, 1, false, projection, 0)
            GLES30.glUniform2f(p.pointsDepth, SceneDepth.NEAR.toFloat(), (1.0 / SceneDepth.logRange).toFloat())
            for (drawn in cloud) {
                val vertices = buffers.of(drawn.tile, drawn.points)
                if (vertices == 0) continue
                GLES30.glUniform4f(p.pointsSize, largest, drawn.geometricError.toFloat(), multiplier, least)
                GLES30.glBindVertexArray(vertices)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, drawn.points.count)
            }
            GLES30.glBindVertexArray(0)
            // Lit onto the screen, each point at its own depth, so the next cloud is drawn in front of it or behind it.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glUseProgram(p.edl)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.color)
            GLES30.glUniform1i(p.edlColor, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.depth)
            GLES30.glUniform1i(p.edlDepth, 1)
            GLES30.glUniform3f(p.edlParameters, radius, SceneLook.EDL_STRENGTH.toFloat(), SceneDepth.logRange.toFloat())
            GLES30.glUniform2f(p.edlTexel, 1f / width, 1f / height)
            GLES30.glBindVertexArray(p.emptyVertices)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        // The routes over the points, against their depth: a leg flown through the canopy is dashed across it, as the web's is.
        val drawn = r != null && drawRoutes(r, work, camera, view, projection)
        // A flight is drawn frame by frame until it lands; imagery just put on the GPU, ground left unbuilt, or labels left unpainted, want
        // another frame.
        if (work.animating || work.uploads.isNotEmpty() || ground.behind || (drawn && routes.behind)) again()
    }

    /**
     * The routes, made again when they changed and drawn; false when they failed. A fault in them costs the routes, never the scene (on the
     * owner's first device runs it was the scene's): they are not drawn again on this context (a new one tries afresh), what they had on its
     * GPU is let go of, and the state they may have left half set (blending, depth writes off, a vertex array bound) is put back as the next
     * frame expects it.
     */
    private fun drawRoutes(r: RoutePrograms, work: SceneWork, camera: ViewCamera, view: FloatArray, projection: FloatArray): Boolean {
        try {
            routes.update(work.routes, work.routesVersion, pixelRatio.toFloat(), work.routeLook)
            routes.draw(r, camera, view, projection, width, height)
            return true
        } catch (_: RuntimeException) {
            // Below.
        } catch (_: OutOfMemoryError) {
            // Below.
        }
        routePrograms = null
        routes.drop()
        r.release()
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        return false
    }

    // The ground, nearest tiles first, each with its imagery laid on coarse to fine, or in its own colour where it has none yet.
    private fun drawGround(p: ScenePrograms, work: SceneWork, view: FloatArray, projection: FloatArray) {
        ground.startFrame()
        if (work.ground.isEmpty()) return
        GLES30.glUseProgram(p.terrain)
        GLES30.glUniformMatrix4fv(p.terrainView, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(p.terrainProjection, 1, false, projection, 0)
        GLES30.glUniform2f(p.terrainDepth, SceneDepth.NEAR.toFloat(), (1.0 / SceneDepth.logRange).toFloat())
        GLES30.glUniform1i(p.terrainImagery, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        val count = TerrainMeshes.INDICES.size
        for (draw in work.ground) {
            val vertices = ground.of(draw, session.frame)
            if (vertices == 0) continue
            GLES30.glBindVertexArray(vertices)
            val laid = work.imagery[draw.tile].orEmpty().filter { textures[it.imagery] != null }
            if (draw.tile !in work.imageryWhole || laid.isEmpty()) {
                GLES30.glUniform4f(p.terrainGround, GROUND_R, GROUND_G, GROUND_B, 1f)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
            }
            GLES30.glUniform4f(p.terrainGround, 0f, 0f, 0f, 0f)
            for (image in laid) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[image.imagery] ?: continue)
                GLES30.glUniform4f(p.terrainUv, image.uv[0], image.uv[1], image.uv[2], image.uv[3])
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
            }
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    /**
     * On the GL thread, as the app goes to the back and before the thread pauses: frees on the GPU what the session let go of ([SceneSession.trim]).
     * What will not be freed is left for the context to take with it.
     */
    fun releaseTrimmed() {
        val gone = session.takeTrimmed(again) ?: return
        try {
            for (tile in gone.tiles) buffers.release(tile)
            for (id in gone.imagery) textures.release(id)
        } catch (_: RuntimeException) {
            // Left for the context.
        }
    }

    // Said once, in words on the page; nothing is logged (the app logs nothing).
    private fun fail() {
        if (failed) return
        failed = true
        session.cannotDraw(SceneProblem.GL_FAILED)
    }

    private companion object {
        val BACKGROUND_R = ((SceneLook.BACKGROUND shr 16) and 0xFF) / 255f
        val BACKGROUND_G = ((SceneLook.BACKGROUND shr 8) and 0xFF) / 255f
        val BACKGROUND_B = (SceneLook.BACKGROUND and 0xFF) / 255f

        // The ground's own colour where its imagery has not come (or cannot): a dark grey-green. **Not like the web**, whose Cesium globe is
        // plain blue there.
        const val GROUND_R = 0x1E / 255f
        const val GROUND_G = 0x24 / 255f
        const val GROUND_B = 0x20 / 255f

        fun Mat4.floats(): FloatArray {
            val values = toList()
            return FloatArray(16) { values[it].toFloat() }
        }
    }
}
