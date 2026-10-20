package app.ezpztac.viewer3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import app.ezpztac.scene3d.Mat4
import app.ezpztac.scene3d.PointShading
import app.ezpztac.scene3d.SceneDepth
import app.ezpztac.scene3d.SceneLook
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws a [SceneSession] on the GL thread (docs/ANDROID_3D_PLAN.md, V4), only when asked: the view renders when something changed (a finger, a
 * tile, a flight, the point size), never on a clock. Each tileset's points go into the [PointTarget], sized as Cesium's attenuation sizes them,
 * then are lit by the eye-dome lighting onto the screen at their own depth, the core first and the ring after it, over the scene's `#05080d`,
 * as Cesium lights each tileset on its own. A context that is lost takes every buffer with it; the next is set up afresh, and the tiles go onto
 * it again from the session, which keeps them. Compile-only: nothing here can be seen from an agent session.
 */
internal class SceneRenderer(
    private val session: SceneSession,
    private val pixelRatio: Double,
    private val again: () -> Unit,
) : GLSurfaceView.Renderer {
    private var programs: ScenePrograms? = null
    private var target: PointTarget? = null
    private val buffers = PointBuffers()
    private var width = 0
    private var height = 0
    private var failed = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever the last one held went with it.
        buffers.forget()
        target = null
        programs = try {
            ScenePrograms.build()
        } catch (e: GlException) {
            fail(e)
            null
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        target?.release()
        target = null
        if (programs == null || w <= 0 || h <= 0) return
        target = PointTarget.create(w, h)
        if (target == null) fail(GlException("No target of ${w}x$h for the points"))
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(BACKGROUND_R, BACKGROUND_G, BACKGROUND_B, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val p = programs ?: return
        val t = target ?: return
        // A fault in drawing is the 3D view's to say, never the app's end: an exception on the GL thread has nothing above it to catch it.
        try {
            draw(p, t)
        } catch (e: RuntimeException) {
            fail(GlException("A frame failed: $e"))
            programs = null
        }
    }

    private fun draw(p: ScenePrograms, t: PointTarget) {
        val work = session.frame(width, height, pixelRatio) ?: return
        for (gone in work.letGo) buffers.release(gone)
        val camera = work.camera
        val view = camera.view.floats()
        val projection = camera.projection.floats()
        val largest = PointShading.maximumPx(work.pointSize, camera).toFloat()
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
                GLES30.glUniform3f(p.pointsSize, largest, drawn.geometricError.toFloat(), multiplier)
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
        // A flight is drawn frame by frame until it lands.
        if (work.animating) again()
    }

    // Said once: the page says it in words, and the log has why.
    private fun fail(e: GlException) {
        Log.w(TAG, "The 3D view cannot draw: ${e.message}")
        if (failed) return
        failed = true
        session.cannotDraw(SceneProblem.GL_FAILED)
    }

    private companion object {
        const val TAG = "Viewer3D"
        val BACKGROUND_R = ((SceneLook.BACKGROUND shr 16) and 0xFF) / 255f
        val BACKGROUND_G = ((SceneLook.BACKGROUND shr 8) and 0xFF) / 255f
        val BACKGROUND_B = (SceneLook.BACKGROUND and 0xFF) / 255f

        fun Mat4.floats(): FloatArray {
            val values = toList()
            return FloatArray(16) { values[it].toFloat() }
        }
    }
}
