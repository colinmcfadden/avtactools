package app.ezpztac.viewer3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import app.ezpztac.scene3d.RouteGeometry
import app.ezpztac.scene3d.SceneDepth
import app.ezpztac.scene3d.Vec3
import app.ezpztac.scene3d.ViewCamera
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

// The routes in 3D (docs/ANDROID_3D_PLAN.md, V6), drawn as the web's Cesium entities are (`routeEntities.js`): compile-only here, like all the
// GL; what is drawn, where and with which words is RouteScene3D's and RouteGeometry's, where it is tested.

/** The routes' shaders. Every depth is the scene's logarithmic one ([SceneDepth]), written per fragment, so a line is hidden by the points exactly. */
internal object RouteShaders {
    // A line as a ribbon a few pixels wide: each end moved sideways on the screen, by half the width, across the segment as the screen shows it.
    // An end behind the eye is first moved along the segment to just in front of it.
    const val RIBBON_VS = """#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_other;
// x: which edge of the ribbon, -1 or 1; y: 1 when the other end is ahead along the line, -1 when behind.
layout(location = 2) in vec2 a_side;
uniform mat4 u_view;
uniform mat4 u_projection;
uniform vec2 u_viewport;
uniform float u_width;
out float v_distance;
flat out float v_angle;

void main() {
    const float NEAR = 0.1;
    vec4 eye = u_view * vec4(a_position, 1.0);
    vec4 otherEye = u_view * vec4(a_other, 1.0);
    if (-eye.z < NEAR && -otherEye.z < NEAR) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        v_distance = NEAR;
        v_angle = 0.0;
        return;
    }
    if (-eye.z < NEAR) eye = mix(eye, otherEye, (NEAR + eye.z) / (eye.z - otherEye.z));
    if (-otherEye.z < NEAR) otherEye = mix(otherEye, eye, (NEAR + otherEye.z) / (otherEye.z - eye.z));
    vec4 clip = u_projection * eye;
    vec4 otherClip = u_projection * otherEye;
    vec2 screen = clip.xy / clip.w * 0.5 * u_viewport;
    vec2 otherScreen = otherClip.xy / otherClip.w * 0.5 * u_viewport;
    vec2 along = otherScreen - screen;
    vec2 dir = length(along) > 1e-6 ? normalize(along) * a_side.y : vec2(1.0, 0.0);
    vec2 normal = vec2(-dir.y, dir.x);
    clip.xy += normal * a_side.x * u_width * 0.5 / (0.5 * u_viewport) * clip.w;
    gl_Position = clip;
    v_distance = -eye.z;
    v_angle = atan(dir.y, dir.x);
}
"""

    // Solid, or dashed as Cesium's PolylineDash is (half of each period on, along the line's direction on the screen).
    const val RIBBON_FS = """#version 300 es
precision highp float;
uniform vec4 u_color;
// x: the near plane; y: one over log2(far / near).
uniform vec2 u_depth;
// The dash's period in pixels, or 0 for a solid line.
uniform float u_dash;
in float v_distance;
flat in float v_angle;
layout(location = 0) out vec4 o_color;

void main() {
    if (u_dash > 0.0) {
        float along = cos(v_angle) * gl_FragCoord.x + sin(v_angle) * gl_FragCoord.y;
        if (fract(along / u_dash) >= 0.5) discard;
    }
    o_color = u_color;
    gl_FragDepth = log2(max(v_distance, u_depth.x) / u_depth.x) * u_depth.y;
}
"""

    const val CURTAIN_VS = """#version 300 es
layout(location = 0) in vec3 a_position;
uniform mat4 u_view;
uniform mat4 u_projection;
out float v_distance;

void main() {
    vec4 eye = u_view * vec4(a_position, 1.0);
    v_distance = -eye.z;
    gl_Position = u_projection * eye;
}
"""

    const val CURTAIN_FS = """#version 300 es
precision highp float;
uniform vec4 u_color;
uniform vec2 u_depth;
in float v_distance;
layout(location = 0) out vec4 o_color;

void main() {
    o_color = u_color;
    gl_FragDepth = log2(max(v_distance, u_depth.x) / u_depth.x) * u_depth.y;
}
"""

    // A named point's dot: white inside, ringed in its route's colour, always in front.
    const val DOT_VS = """#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec4 a_ring;
layout(location = 2) in float a_size;
uniform mat4 u_view;
uniform mat4 u_projection;
// x: the screen's density; y: the ring's width, in points.
uniform vec2 u_dot;
out vec4 v_ring;
out float v_inner;
out float v_total;

void main() {
    gl_Position = u_projection * (u_view * vec4(a_position, 1.0));
    v_inner = a_size * u_dot.x;
    v_total = (a_size + 2.0 * u_dot.y) * u_dot.x;
    gl_PointSize = v_total;
    v_ring = a_ring;
}
"""

    const val DOT_FS = """#version 300 es
precision mediump float;
in vec4 v_ring;
in float v_inner;
in float v_total;
layout(location = 0) out vec4 o_color;

void main() {
    float r = length(gl_PointCoord - 0.5) * v_total;
    if (r > v_total * 0.5) discard;
    o_color = r <= v_inner * 0.5 ? vec4(1.0) : vec4(v_ring.rgb, 1.0);
}
"""

    // A label's picture, its bottom middle a little above its point, always in front.
    const val LABEL_VS = """#version 300 es
layout(location = 0) in vec2 a_corner;
uniform vec3 u_anchor;
uniform mat4 u_view;
uniform mat4 u_projection;
uniform vec2 u_viewport;
// x, y: the picture's size in pixels, as scaled for its distance; z: how far above its point its foot is, in pixels.
uniform vec3 u_label;
out vec2 v_uv;

void main() {
    vec4 clip = u_projection * (u_view * vec4(u_anchor, 1.0));
    v_uv = a_corner;
    if (clip.w <= 0.0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    vec2 px = vec2((a_corner.x - 0.5) * u_label.x, u_label.z + (1.0 - a_corner.y) * u_label.y);
    gl_Position = vec4(clip.xy / clip.w + px / (0.5 * u_viewport), 0.0, 1.0);
}
"""

    const val LABEL_FS = """#version 300 es
precision mediump float;
uniform sampler2D u_picture;
in vec2 v_uv;
layout(location = 0) out vec4 o_color;

void main() {
    o_color = texture(u_picture, v_uv);
}
"""
}

/** The routes' programs and their uniforms, for one context. */
internal class RoutePrograms private constructor(val handles: Map<String, Int>) {
    operator fun get(name: String): Int = handles.getValue(name)

    companion object {
        fun build(link: (String, String) -> Int): RoutePrograms {
            val out = HashMap<String, Int>()
            fun program(key: String, vs: String, fs: String, vararg uniforms: String) {
                val id = link(vs, fs)
                out[key] = id
                for (u in uniforms) out["$key.$u"] = GLES30.glGetUniformLocation(id, u).also { if (it < 0) throw GlException("No uniform $u") }
            }
            program("ribbon", RouteShaders.RIBBON_VS, RouteShaders.RIBBON_FS, "u_view", "u_projection", "u_viewport", "u_width", "u_color", "u_depth", "u_dash")
            program("curtain", RouteShaders.CURTAIN_VS, RouteShaders.CURTAIN_FS, "u_view", "u_projection", "u_color", "u_depth")
            program("dot", RouteShaders.DOT_VS, RouteShaders.DOT_FS, "u_view", "u_projection", "u_dot")
            program("label", RouteShaders.LABEL_VS, RouteShaders.LABEL_FS, "u_anchor", "u_view", "u_projection", "u_viewport", "u_label", "u_picture")
            return RoutePrograms(out)
        }
    }
}

/**
 * A label's picture as the web's Cesium label is drawn (`routeEntities.js`): its lines in a semibold 12-point sans-serif, each centred, white
 * (amber, `#fbbf24`, where the AGL measured disagrees with the plan's) with a black outline, on a dark box padded 6 by 4 points.
 */
internal object LabelPainter {
    private const val MISMATCH = 0xFFFBBF24.toInt()
    private val BACKGROUND = android.graphics.Color.argb(0.78f, 0.03f, 0.05f, 0.08f)

    fun paint(text: String, mismatch: Boolean, density: Float): Bitmap {
        val font = 12f * density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = font
            typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
        }
        val lines = text.split('\n')
        val lineHeight = font * 1.2f
        val padX = 6f * density
        val padY = 4f * density
        val outline = 2f * density
        val width = ceil(lines.maxOf { paint.measureText(it) } + 2 * padX + outline).toInt().coerceAtLeast(1)
        val height = ceil(lines.size * lineHeight + 2 * padY).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 3f * density, 3f * density, box)
        val metrics = paint.fontMetrics
        for ((i, line) in lines.withIndex()) {
            val x = (width - paint.measureText(line)) / 2
            val baseline = padY + i * lineHeight + (lineHeight - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outline
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = android.graphics.Color.BLACK
            canvas.drawText(line, x, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (mismatch) MISMATCH else android.graphics.Color.WHITE
            canvas.drawText(line, x, baseline, paint)
        }
        return bitmap
    }
}

/**
 * The routes on the GPU, made again when the routes change ([update]): their lines and drop lines as ribbons, their curtains, their dots, and a
 * texture for each label. [draw] lays them over the points in Cesium's order: the lines where they show; the curtains, a fifth of their colour;
 * the lines again, dashed at 60%, where the points hide them; the drop lines; then the dots and the labels in front of everything.
 */
internal class RouteDrawing {
    private class Run(val first: Int, val count: Int, val color: FloatArray)
    private class Picture(val texture: Int, val width: Int, val height: Int)

    private var version = -1
    private var buffers = IntArray(0)
    private var ribbonVao = 0
    private var curtainVao = 0
    private var dotVao = 0
    private var quadVao = 0
    private var lines = emptyList<Run>()
    private var drops = emptyList<Run>()
    private var curtains = emptyList<Run>()
    private var dots = 0
    private var labels = emptyList<Pair<Vec3, Picture>>()
    private val pictures = HashMap<Pair<String, Boolean>, Picture>()

    /** The routes changed (a new [version] of them): their buffers made again, and the labels' pictures painted where they are new. */
    fun update(geometry: RouteGeometry, version: Int, density: Float) {
        if (version == this.version) return
        release()
        this.version = version
        val ribbon = ArrayList<Float>()
        val indices = ArrayList<Int>()
        fun segment(a: Vec3, b: Vec3) {
            val base = ribbon.size / 8
            for ((p, other, ahead) in listOf(Triple(a, b, 1f), Triple(b, a, -1f))) {
                for (side in floatArrayOf(-1f, 1f)) {
                    ribbon += listOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), other.x.toFloat(), other.y.toFloat(), other.z.toFloat(), side, ahead)
                }
            }
            indices += listOf(base, base + 1, base + 2, base + 2, base + 1, base + 3)
        }
        fun runOf(color: FloatArray, points: List<Vec3>): Run {
            val first = indices.size
            for (i in 1 until points.size) segment(points[i - 1], points[i])
            return Run(first, indices.size - first, color)
        }
        lines = geometry.lines.map { runOf(it.color, it.points) }
        val white = floatArrayOf(1f, 1f, 1f, RouteGeometry.DROP_ALPHA.toFloat())
        drops = geometry.drops.map { runOf(white, listOf(it.ground, it.point)) }
        val curtain = ArrayList<Float>()
        curtains = geometry.curtains.map { c ->
            val first = curtain.size / 3
            for (i in c.tops.indices) for (p in listOf(c.tops[i], c.bottoms[i])) curtain += listOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
            Run(first, curtain.size / 3 - first, c.color)
        }
        val dot = ArrayList<Float>()
        for (d in geometry.dots) dot += listOf(d.at.x.toFloat(), d.at.y.toFloat(), d.at.z.toFloat(), d.ring[0], d.ring[1], d.ring[2], 1f, d.size.toFloat())
        dots = geometry.dots.size
        buffers = IntArray(6)
        GLES30.glGenBuffers(6, buffers, 0)
        val vaos = IntArray(4)
        GLES30.glGenVertexArrays(4, vaos, 0)
        ribbonVao = vaos[0]
        curtainVao = vaos[1]
        dotVao = vaos[2]
        quadVao = vaos[3]
        GLES30.glBindVertexArray(ribbonVao)
        upload(GLES30.GL_ARRAY_BUFFER, buffers[0], floats(ribbon))
        attribute(0, 3, 32, 0)
        attribute(1, 3, 32, 12)
        attribute(2, 2, 32, 24)
        upload(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1], ints(indices))
        GLES30.glBindVertexArray(curtainVao)
        upload(GLES30.GL_ARRAY_BUFFER, buffers[2], floats(curtain))
        attribute(0, 3, 12, 0)
        GLES30.glBindVertexArray(dotVao)
        upload(GLES30.GL_ARRAY_BUFFER, buffers[3], floats(dot))
        attribute(0, 3, 32, 0)
        attribute(1, 4, 32, 12)
        attribute(2, 1, 32, 28)
        GLES30.glBindVertexArray(quadVao)
        upload(GLES30.GL_ARRAY_BUFFER, buffers[4], floats(listOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)))
        attribute(0, 2, 8, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        // Each label's picture, painted once for its words.
        val wanted = geometry.labels.map { it.text to it.mismatch }.toSet()
        for (key in pictures.keys - wanted) pictures.remove(key)?.let { GLES30.glDeleteTextures(1, intArrayOf(it.texture), 0) }
        labels = geometry.labels.map { label -> label.at to pictures.getOrPut(label.text to label.mismatch) { picture(LabelPainter.paint(label.text, label.mismatch, density)) } }
    }

    fun draw(p: RoutePrograms, camera: ViewCamera, view: FloatArray, projection: FloatArray, width: Int, height: Int) {
        if (version < 0 || (lines.isEmpty() && curtains.isEmpty() && dots == 0)) return
        val ribbon = p["ribbon"]
        val curtain = p["curtain"]
        val dot = p["dot"]
        val label = p["label"]
        val ratio = camera.pixelRatio.toFloat()
        val near = SceneDepth.NEAR.toFloat()
        val invLog = (1.0 / SceneDepth.logRange).toFloat()
        fun ribbons(runs: List<Run>, widthPt: Double, alpha: Float?, dashed: Boolean) {
            GLES30.glUseProgram(ribbon)
            GLES30.glUniformMatrix4fv(p["ribbon.u_view"], 1, false, view, 0)
            GLES30.glUniformMatrix4fv(p["ribbon.u_projection"], 1, false, projection, 0)
            GLES30.glUniform2f(p["ribbon.u_viewport"], width.toFloat(), height.toFloat())
            GLES30.glUniform1f(p["ribbon.u_width"], (widthPt * ratio).toFloat())
            GLES30.glUniform2f(p["ribbon.u_depth"], near, invLog)
            GLES30.glUniform1f(p["ribbon.u_dash"], if (dashed) (RouteGeometry.DASH_LENGTH * ratio).toFloat() else 0f)
            GLES30.glBindVertexArray(ribbonVao)
            for (run in runs) {
                GLES30.glUniform4f(p["ribbon.u_color"], run.color[0], run.color[1], run.color[2], alpha ?: run.color[3])
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, run.count, GLES30.GL_UNSIGNED_INT, run.first * 4)
            }
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // The lines where nothing hides them, at their own depth.
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        ribbons(lines, RouteGeometry.LINE_WIDTH, null, dashed = false)
        // What follows is laid over, at a fraction of its colour, writing no depth.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        if (curtains.isNotEmpty()) {
            GLES30.glUseProgram(curtain)
            GLES30.glUniformMatrix4fv(p["curtain.u_view"], 1, false, view, 0)
            GLES30.glUniformMatrix4fv(p["curtain.u_projection"], 1, false, projection, 0)
            GLES30.glUniform2f(p["curtain.u_depth"], near, invLog)
            GLES30.glBindVertexArray(curtainVao)
            for (run in curtains) {
                GLES30.glUniform4f(p["curtain.u_color"], run.color[0], run.color[1], run.color[2], run.color[3])
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, run.first, run.count)
            }
        }
        // The lines again where the points hide them: dashed, at 60%.
        GLES30.glDepthFunc(GLES30.GL_GREATER)
        ribbons(lines, RouteGeometry.LINE_WIDTH, RouteGeometry.HIDDEN_ALPHA.toFloat(), dashed = true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        ribbons(drops, RouteGeometry.DROP_WIDTH, null, dashed = false)
        // The dots and the labels, in front of everything.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        if (dots > 0) {
            GLES30.glUseProgram(dot)
            GLES30.glUniformMatrix4fv(p["dot.u_view"], 1, false, view, 0)
            GLES30.glUniformMatrix4fv(p["dot.u_projection"], 1, false, projection, 0)
            GLES30.glUniform2f(p["dot.u_dot"], ratio, RouteGeometry.DOT_RING.toFloat())
            GLES30.glBindVertexArray(dotVao)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, dots)
        }
        if (labels.isNotEmpty()) {
            GLES30.glEnable(GLES30.GL_BLEND)
            // The pictures are premultiplied, as Android paints them.
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(label)
            GLES30.glUniformMatrix4fv(p["label.u_view"], 1, false, view, 0)
            GLES30.glUniformMatrix4fv(p["label.u_projection"], 1, false, projection, 0)
            GLES30.glUniform2f(p["label.u_viewport"], width.toFloat(), height.toFloat())
            GLES30.glUniform1i(p["label.u_picture"], 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindVertexArray(quadVao)
            for ((at, picture) in labels) {
                val scale = RouteGeometry.labelScale((at - camera.position).length).toFloat()
                GLES30.glUniform3f(p["label.u_anchor"], at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                GLES30.glUniform3f(p["label.u_label"], picture.width * scale, picture.height * scale, LABEL_LIFT * ratio)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, picture.texture)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    /** The context went, and everything on it. */
    fun forget() {
        buffers = IntArray(0)
        ribbonVao = 0
        curtainVao = 0
        dotVao = 0
        quadVao = 0
        pictures.clear()
        labels = emptyList()
        version = -1
    }

    private fun release() {
        if (buffers.isNotEmpty()) GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        val vaos = intArrayOf(ribbonVao, curtainVao, dotVao, quadVao).filter { it != 0 }.toIntArray()
        if (vaos.isNotEmpty()) GLES30.glDeleteVertexArrays(vaos.size, vaos, 0)
        buffers = IntArray(0)
    }

    private fun picture(bitmap: Bitmap): Picture {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val picture = Picture(ids[0], bitmap.width, bitmap.height)
        bitmap.recycle()
        return picture
    }

    private companion object {
        /** A label's foot is this many points above its point (Cesium's `pixelOffset` of 10 up). */
        const val LABEL_LIFT = 10f

        fun floats(values: List<Float>): ByteBuffer {
            val b = ByteBuffer.allocateDirect(maxOf(values.size, 1) * 4).order(ByteOrder.nativeOrder())
            for (v in values) b.putFloat(v)
            b.flip()
            return b
        }

        fun ints(values: List<Int>): ByteBuffer {
            val b = ByteBuffer.allocateDirect(maxOf(values.size, 1) * 4).order(ByteOrder.nativeOrder())
            for (v in values) b.putInt(v)
            b.flip()
            return b
        }

        fun upload(target: Int, buffer: Int, data: ByteBuffer) {
            GLES30.glBindBuffer(target, buffer)
            GLES30.glBufferData(target, data.capacity(), data, GLES30.GL_STATIC_DRAW)
        }

        fun attribute(index: Int, size: Int, stride: Int, offset: Int) {
            GLES30.glEnableVertexAttribArray(index)
            GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offset)
        }
    }
}
