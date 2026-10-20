package app.ezpztac.viewer3d

import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import app.ezpztac.scene3d.ImageryTileId
import app.ezpztac.scene3d.LocalFrame
import app.ezpztac.scene3d.ScenePoints
import app.ezpztac.scene3d.TerrainDraw
import app.ezpztac.scene3d.TerrainMeshes
import app.ezpztac.scene3d.TerrainTileId
import app.ezpztac.scene3d.Tile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

// The 3D view's OpenGL ES 3.0 (docs/ANDROID_3D_PLAN.md, V4): compile-only here, since nothing in an agent session draws GL; what is drawn and
// with which numbers is decided in core-scene3d and the session, where it is tested.

/** A GL call that did not do what it should (a shader that would not compile, a target the driver refused), in words for the log. */
internal class GlException(message: String) : Exception(message)

/**
 * The shaders. Points are sized as Cesium's attenuation sizes them (the tile's geometric error seen from where the point is, in the buffer's
 * pixels, never past the largest size: [app.ezpztac.scene3d.PointShading]), at least a CSS pixel as the web's, and write the logarithmic depth
 * ([app.ezpztac.scene3d.SceneDepth]). The eye-dome lighting is Cesium's `PointCloudEyeDomeLighting`: four neighbours at the radius, read between
 * the texels either side of it, and the point darkened by how many octaves nearer they are.
 */
internal object SceneShaders {
    const val POINTS_VS = """#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_color;
uniform mat4 u_view;
uniform mat4 u_projection;
// x: the largest a point may be, in the buffer's pixels; y: the tile's geometric error, metres; z: the buffer's height over the sseDenominator;
// w: the least a point may be, in the buffer's pixels.
uniform vec4 u_size;
// x: the near plane, metres; y: one over log2(far / near).
uniform vec2 u_depth;
out vec3 v_color;

void main() {
    vec4 eye = u_view * vec4(a_position, 1.0);
    float distance = -eye.z;
    vec4 clip = u_projection * eye;
    clip.z = (2.0 * log2(max(distance, u_depth.x) / u_depth.x) * u_depth.y - 1.0) * clip.w;
    gl_Position = clip;
    gl_PointSize = max(u_size.w, min(u_size.y / max(distance, u_depth.x) * u_size.z, u_size.x));
    v_color = a_color;
}
"""

    const val POINTS_FS = """#version 300 es
precision mediump float;
in vec3 v_color;
layout(location = 0) out vec4 o_color;

void main() {
    o_color = vec4(v_color, 1.0);
}
"""

    // One triangle over the whole view, from the vertex's number alone.
    const val FULL_VS = """#version 300 es
out vec2 v_uv;

void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v_uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    // The ground: its vertex's place in the scene and in its tile's Mercator rectangle; the distance kept for the depth, written per fragment,
    // since a triangle's depth does not interpolate logarithmically.
    const val TERRAIN_VS = """#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec2 a_place;
uniform mat4 u_view;
uniform mat4 u_projection;
out vec2 v_place;
out float v_distance;

void main() {
    vec4 eye = u_view * vec4(a_position, 1.0);
    v_distance = -eye.z;
    v_place = a_place;
    gl_Position = u_projection * eye;
}
"""

    // The imagery laid by the vertex's Mercator place (scale then offset into the imagery tile, nothing outside it), or the ground's own colour.
    const val TERRAIN_FS = """#version 300 es
precision highp float;
uniform sampler2D u_imagery;
// x, y: the scale into the imagery tile; z, w: the offset.
uniform vec4 u_uv;
// rgb: the ground's colour; a: 1 to draw that, 0 to lay the imagery.
uniform vec4 u_ground;
// x: the near plane, metres; y: one over log2(far / near).
uniform vec2 u_depth;
in vec2 v_place;
in float v_distance;
layout(location = 0) out vec4 o_color;

void main() {
    vec3 color;
    if (u_ground.a > 0.5) {
        color = u_ground.rgb;
    } else {
        vec2 uv = v_place * u_uv.xy + u_uv.zw;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;
        color = texture(u_imagery, uv).rgb;
    }
    o_color = vec4(color, 1.0);
    gl_FragDepth = log2(max(v_distance, u_depth.x) / u_depth.x) * u_depth.y;
}
"""

    const val EDL_FS = """#version 300 es
precision highp float;
uniform sampler2D u_color;
uniform highp sampler2D u_depth;
// x: the radius, in the buffer's pixels; y: the strength; z: log2(far / near).
uniform vec3 u_edl;
uniform vec2 u_texel;
in vec2 v_uv;
layout(location = 0) out vec4 o_color;

vec2 neighbour(float centre, vec2 offset) {
    float r = u_edl.x;
    float d0 = texture(u_depth, v_uv + offset * floor(r)).r;
    float d1 = texture(u_depth, v_uv + offset * ceil(r)).r;
    if (d0 >= 1.0 || d1 >= 1.0) return vec2(0.0);
    return vec2(max(0.0, (centre - mix(d0, d1, fract(r))) * u_edl.z), 1.0);
}

void main() {
    float depth = texture(u_depth, v_uv).r;
    if (depth >= 1.0) discard;
    vec2 sum = neighbour(depth, vec2(-u_texel.x, 0.0)) + neighbour(depth, vec2(u_texel.x, 0.0))
        + neighbour(depth, vec2(0.0, -u_texel.y)) + neighbour(depth, vec2(0.0, u_texel.y));
    float response = sum.y > 0.0 ? sum.x / sum.y : 0.0;
    o_color = vec4(texture(u_color, v_uv).rgb * exp(-response * 300.0 * u_edl.y), 1.0);
    gl_FragDepth = depth;
}
"""
}

/** The two programs and their uniforms, built on the GL thread for one context. */
internal class ScenePrograms private constructor(
    val points: Int,
    val pointsView: Int,
    val pointsProjection: Int,
    val pointsSize: Int,
    val pointsDepth: Int,
    val edl: Int,
    val edlColor: Int,
    val edlDepth: Int,
    val edlParameters: Int,
    val edlTexel: Int,
    val emptyVertices: Int,
    val terrain: Int,
    val terrainView: Int,
    val terrainProjection: Int,
    val terrainImagery: Int,
    val terrainUv: Int,
    val terrainGround: Int,
    val terrainDepth: Int,
) {
    companion object {
        fun build(): ScenePrograms {
            val points = link(SceneShaders.POINTS_VS, SceneShaders.POINTS_FS)
            val edl = link(SceneShaders.FULL_VS, SceneShaders.EDL_FS)
            val terrain = link(SceneShaders.TERRAIN_VS, SceneShaders.TERRAIN_FS)
            val vao = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            fun uniform(program: Int, name: String) = GLES30.glGetUniformLocation(program, name).also {
                if (it < 0) throw GlException("No uniform $name")
            }
            return ScenePrograms(
                points, uniform(points, "u_view"), uniform(points, "u_projection"), uniform(points, "u_size"), uniform(points, "u_depth"),
                edl, uniform(edl, "u_color"), uniform(edl, "u_depth"), uniform(edl, "u_edl"), uniform(edl, "u_texel"),
                vao[0],
                terrain, uniform(terrain, "u_view"), uniform(terrain, "u_projection"), uniform(terrain, "u_imagery"), uniform(terrain, "u_uv"),
                uniform(terrain, "u_ground"), uniform(terrain, "u_depth"),
            )
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw GlException("glCreateShader gave no shader")
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw GlException("A shader would not compile: $log")
            }
            return shader
        }

        private fun link(vertex: String, fragment: String): Int {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            val program = GLES30.glCreateProgram()
            if (program == 0) throw GlException("glCreateProgram gave no program")
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw GlException("The shaders would not link: $log")
            }
            return program
        }
    }
}

/**
 * Each tile's points on the GPU, put there the first time the tile is drawn: one buffer of 16 bytes a point (x, y and z as floats, then red,
 * green and blue, then a byte to keep the next point aligned), and a vertex array over it. Let go of when the scene lets go of the tile; forgotten
 * without a call when the context went (they went with it). A tile the GPU had no room for is not tried again until something is let go of.
 */
internal class PointBuffers {
    private class OnGpu(val vertices: Int, val buffer: Int)

    private val tiles = IdentityHashMap<Tile, OnGpu>()
    private val refused: MutableSet<Tile> = Collections.newSetFromMap(IdentityHashMap())
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** The vertex array [tile]'s [points] are drawn with, put on the GPU now if they are not; 0 when the GPU would not take them. */
    fun of(tile: Tile, points: ScenePoints): Int {
        tiles[tile]?.let { return it.vertices }
        if (tile in refused || points.count == 0) return 0
        val bytes = points.count * STRIDE
        if (staging.capacity() < bytes) {
            // A phone short of memory leaves the tile undrawn rather than the app ended (the GL thread has nothing to catch it).
            staging = try {
                ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            } catch (_: OutOfMemoryError) {
                refused += tile
                return 0
            }
        }
        staging.clear()
        val p = points.positions
        val c = points.rgb
        for (i in 0 until points.count) {
            staging.putFloat(p[3 * i]).putFloat(p[3 * i + 1]).putFloat(p[3 * i + 2])
            staging.put(c[3 * i]).put(c[3 * i + 1]).put(c[3 * i + 2]).put(0)
        }
        staging.flip()
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        // Errors left from before, so that the one read after the upload is the upload's.
        var stale = GLES30.glGetError()
        while (stale != GLES30.GL_NO_ERROR) stale = GLES30.glGetError()
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, staging, GLES30.GL_STATIC_DRAW)
        if (GLES30.glGetError() == GLES30.GL_OUT_OF_MEMORY) {
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glDeleteBuffers(1, ids, 1)
            GLES30.glDeleteVertexArrays(1, ids, 0)
            refused += tile
            return 0
        }
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_UNSIGNED_BYTE, true, STRIDE, 12)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        tiles[tile] = OnGpu(ids[0], ids[1])
        return ids[0]
    }

    fun release(tile: Tile) {
        val gone = tiles.remove(tile) ?: return
        GLES30.glDeleteBuffers(1, intArrayOf(gone.buffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(gone.vertices), 0)
        // Room made: what was refused may fit now.
        refused.clear()
    }

    /** The context went, and every buffer with it. */
    fun forget() {
        tiles.clear()
        refused.clear()
    }

    private companion object {
        const val STRIDE = 16
    }
}

/**
 * The ground's tiles on the GPU: one index buffer for all of them (every tile has the same triangles, [TerrainMeshes.INDICES]) and a vertex
 * buffer for each, built from its heights the first time it is drawn (at most [MAX_BUILDS] a frame: each is a few milliseconds of the GL
 * thread). Let go of with the tile; forgotten without a call when the context went.
 */
internal class GroundBuffers {
    private class OnGpu(val vertices: Int, val buffer: Int)

    private val tiles = HashMap<TerrainTileId, OnGpu>()
    private var indices = 0
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(TerrainMeshes.VERTICES * TerrainMeshes.FLOATS * 4).order(ByteOrder.nativeOrder())
    private var built = 0

    /** A new frame: its builds are counted afresh. */
    fun startFrame() {
        built = 0
    }

    /** Whether a tile was left unbuilt this frame for want of time: another frame is wanted. */
    val behind: Boolean get() = built > MAX_BUILDS

    /** The vertex array [draw] is drawn with, built now if it is not; 0 when this frame has built enough, or the GPU would not take it. */
    fun of(draw: TerrainDraw, frame: LocalFrame): Int {
        tiles[draw.tile]?.let { return it.vertices }
        if (++built > MAX_BUILDS) return 0
        if (indices == 0) indices = indexBuffer()
        val vertices = TerrainMeshes.vertices(draw.tile, draw.heights, frame)
        staging.clear()
        staging.asFloatBuffer().put(vertices)
        staging.limit(vertices.size * 4)
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        var stale = GLES30.glGetError()
        while (stale != GLES30.GL_NO_ERROR) stale = GLES30.glGetError()
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, staging, GLES30.GL_STATIC_DRAW)
        if (GLES30.glGetError() == GLES30.GL_OUT_OF_MEMORY) {
            GLES30.glBindVertexArray(0)
            GLES30.glDeleteBuffers(1, ids, 1)
            GLES30.glDeleteVertexArrays(1, ids, 0)
            return 0
        }
        val stride = TerrainMeshes.FLOATS * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        tiles[draw.tile] = OnGpu(ids[0], ids[1])
        return ids[0]
    }

    fun release(tile: TerrainTileId) {
        val gone = tiles.remove(tile) ?: return
        GLES30.glDeleteBuffers(1, intArrayOf(gone.buffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(gone.vertices), 0)
    }

    /** The context went, and every buffer with it. */
    fun forget() {
        tiles.clear()
        indices = 0
    }

    private fun indexBuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        val data = ByteBuffer.allocateDirect(TerrainMeshes.INDICES.size * 2).order(ByteOrder.nativeOrder())
        data.asShortBuffer().put(TerrainMeshes.INDICES)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, TerrainMeshes.INDICES.size * 2, data, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        return ids[0]
    }

    companion object {
        const val MAX_BUILDS = 8
    }
}

/**
 * The imagery's textures: each tile put on the GPU once it is decoded, mipmapped (the ground is seen at a slant), and let go of with the
 * tile; forgotten without a call when the context went.
 */
internal class ImageryTextures {
    private val textures = HashMap<ImageryTileId, Int>()

    operator fun get(id: ImageryTileId): Int? = textures[id]

    /** Puts [image] on the GPU as [id]'s texture; false when it could not be (the GPU had no room, or the image was not a bitmap). */
    fun upload(id: ImageryTileId, image: ImageryImage): Boolean {
        val bitmap = (image as? BitmapImage)?.bitmap ?: return false
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        var stale = GLES30.glGetError()
        while (stale != GLES30.GL_NO_ERROR) stale = GLES30.glGetError()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glDeleteTextures(1, ids, 0)
            return false
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        textures.put(id, ids[0])?.let { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
        return true
    }

    fun release(id: ImageryTileId) {
        val gone = textures.remove(id) ?: return
        GLES30.glDeleteTextures(1, intArrayOf(gone), 0)
    }

    /** The context went, and every texture with it. */
    fun forget() {
        textures.clear()
    }
}

/** Where a cloud's points are drawn before they are lit: a colour texture and a depth texture the size of the view. */
internal class PointTarget private constructor(val framebuffer: Int, val color: Int, val depth: Int) {
    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(2, intArrayOf(color, depth), 0)
    }

    companion object {
        /** A target of [width] by [height]; null when the driver will not draw into it. */
        fun create(width: Int, height: Int): PointTarget? {
            val textures = IntArray(2)
            GLES30.glGenTextures(2, textures, 0)
            fun texture(id: Int, internal: Int, format: Int, type: Int) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }
            texture(textures[0], GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
            texture(textures[1], GLES30.GL_DEPTH_COMPONENT24, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            val framebuffer = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffer, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[0], 0)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, textures[1], 0)
            val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val target = PointTarget(framebuffer[0], textures[0], textures[1])
            if (!complete) {
                target.release()
                return null
            }
            return target
        }
    }
}

/**
 * The window's surface: eight bits of red, green and blue and a depth buffer (24 bits, else 16), which the clouds are laid onto in turn, each at
 * its own depth, from a context that can draw OpenGL ES 3.0. The first such that EGL lists, keeping to eight bits a colour where it can; failing
 * that any with a depth buffer, then any at all (the ground and the clouds then laid on without depth between them, worse but drawn).
 */
internal object SceneConfigChooser : GLSurfaceView.EGLConfigChooser {
    private const val EGL_OPENGL_ES3_BIT = 0x40

    private val wanted: List<IntArray> = listOf(
        intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
        intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
        intArrayOf(EGL10.EGL_DEPTH_SIZE, 1, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
        intArrayOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
    )

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (asked in wanted) {
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, asked, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, asked, configs, configs.size, count)) continue
            val found = configs.take(count[0]).filterNotNull()
            val value = IntArray(1)
            fun red(config: EGLConfig) = if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_RED_SIZE, value)) value[0] else 0
            (found.firstOrNull { red(it) == 8 } ?: found.firstOrNull())?.let { return it }
        }
        // GLSurfaceView has no way to say this but to throw, which ends the app: the view is made only where [onDevice] found one.
        throw IllegalArgumentException("No EGL configuration for OpenGL ES 3.0")
    }

    /**
     * Whether the device's EGL lists any configuration that draws OpenGL ES 3.0: asked before the view is made, since GLSurfaceView can only
     * throw on its own thread when there is none. The display is initialised, as GLSurfaceView would, and never terminated: MapLibre draws on it.
     */
    fun onDevice(): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        return EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0
    }
}
