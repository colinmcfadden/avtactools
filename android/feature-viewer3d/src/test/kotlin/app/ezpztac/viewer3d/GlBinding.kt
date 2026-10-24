package app.ezpztac.viewer3d

import android.opengl.GLES20
import android.opengl.GLES30
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

// Android's OpenGL ES binding as a device runs it, for the renderer's tests. Robolectric's GL does nothing and checks nothing, so every JVM test
// passed while the 3D view failed on the owner's devices: the binding's JNI (android_opengl_GLES20.cpp and GLES30's, written by glgen) refuses,
// before it calls the driver, what the C calls would only misread. These shadows refuse it as the binding does, in its own words: an array
// shorter than its offset and count, a buffer holding less than its size, fewer floats than its matrices. Names are handed out, and what was
// put on the GPU and drawn is written down ([GlCalls]). The rest of the GL stays Robolectric's no-op; what the driver would make of it is not here.

/** What the GL was asked, for a test to read, and a fault to throw where a test wants one. */
internal object GlCalls {
    /** A `glBufferData`: its target, the size asked, and the bytes the buffer held from its position to its limit. */
    data class Upload(val target: Int, val size: Int, val remaining: Int)

    val uploads: MutableList<Upload> = mutableListOf()

    /** The index type of each `glDrawElements`. */
    val elements: MutableList<Int> = mutableListOf()

    /** The mode and count of each `glDrawArrays`. */
    val arrays: MutableList<Pair<Int, Int>> = mutableListOf()

    var blend: Boolean = false
    var depthMask: Boolean = true

    /** Asked with each call that can fault (`glDrawElements:<type>`, `glBufferData:<target>`); true throws there. */
    var fault: ((String) -> Boolean)? = null

    private var names = 0

    fun reset() {
        uploads.clear()
        elements.clear()
        arrays.clear()
        blend = false
        depthMask = true
        fault = null
        names = 0
    }

    fun name(): Int = ++names

    fun call(name: String) {
        if (fault?.invoke(name) == true) throw IllegalArgumentException("A fault in $name")
    }

    // The binding's own check of an array and its offset against what the call reads or writes.
    fun array(array: IntArray?, offset: Int, needed: Int, what: String) {
        if (array == null) throw IllegalArgumentException("$what == null")
        if (offset < 0) throw IllegalArgumentException("offset < 0")
        if (array.size - offset < needed) throw IllegalArgumentException("length - offset < n < needed")
    }

    fun generate(n: Int, array: IntArray?, offset: Int, what: String) {
        array(array, offset, n, what)
        for (i in 0 until n) array!![offset + i] = name()
    }

    // getPointer's remaining, in bytes: the elements from position to limit, each its type's size.
    fun bytes(b: Buffer): Int = b.remaining() * when (b) {
        is ByteBuffer -> 1
        is ShortBuffer, is CharBuffer -> 2
        is IntBuffer, is FloatBuffer -> 4
        else -> 8
    }
}

@Implements(GLES20::class)
class GlBinding20 {
    companion object {
        @JvmStatic
        @Implementation
        fun glGenBuffers(n: Int, buffers: IntArray?, offset: Int) = GlCalls.generate(n, buffers, offset, "buffers")

        @JvmStatic
        @Implementation
        fun glGenTextures(n: Int, textures: IntArray?, offset: Int) = GlCalls.generate(n, textures, offset, "textures")

        @JvmStatic
        @Implementation
        fun glGenFramebuffers(n: Int, framebuffers: IntArray?, offset: Int) = GlCalls.generate(n, framebuffers, offset, "framebuffers")

        @JvmStatic
        @Implementation
        fun glDeleteBuffers(n: Int, buffers: IntArray?, offset: Int) = GlCalls.array(buffers, offset, n, "buffers")

        @JvmStatic
        @Implementation
        fun glDeleteTextures(n: Int, textures: IntArray?, offset: Int) = GlCalls.array(textures, offset, n, "textures")

        @JvmStatic
        @Implementation
        fun glDeleteFramebuffers(n: Int, framebuffers: IntArray?, offset: Int) = GlCalls.array(framebuffers, offset, n, "framebuffers")

        @JvmStatic
        @Implementation
        fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) {
            GlCalls.call("glBufferData:$target")
            val remaining = data?.let(GlCalls::bytes) ?: 0
            if (data != null && remaining < size) throw IllegalArgumentException("remaining() < size < needed")
            GlCalls.uploads += GlCalls.Upload(target, size, remaining)
        }

        @JvmStatic
        @Implementation
        fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray?, offset: Int) {
            if (value == null) throw IllegalArgumentException("value == null")
            if (offset < 0) throw IllegalArgumentException("offset < 0")
            if (value.size - offset < count * 16) throw IllegalArgumentException("length - offset < count*16 < needed")
        }

        @JvmStatic
        @Implementation
        fun glGetShaderiv(shader: Int, pname: Int, params: IntArray?, offset: Int) {
            GlCalls.array(params, offset, 1, "params")
            params!![offset] = if (pname == GLES20.GL_COMPILE_STATUS) GLES20.GL_TRUE else 0
        }

        @JvmStatic
        @Implementation
        fun glGetProgramiv(program: Int, pname: Int, params: IntArray?, offset: Int) {
            GlCalls.array(params, offset, 1, "params")
            params!![offset] = if (pname == GLES20.GL_LINK_STATUS) GLES20.GL_TRUE else 0
        }

        @JvmStatic
        @Implementation
        fun glCreateShader(type: Int): Int = GlCalls.name()

        @JvmStatic
        @Implementation
        fun glCreateProgram(): Int = GlCalls.name()

        @JvmStatic
        @Implementation
        fun glCheckFramebufferStatus(target: Int): Int = GLES20.GL_FRAMEBUFFER_COMPLETE

        @JvmStatic
        @Implementation
        fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) {
            GlCalls.call("glDrawElements:$type")
            GlCalls.elements += type
        }

        @JvmStatic
        @Implementation
        fun glDrawArrays(mode: Int, first: Int, count: Int) {
            GlCalls.arrays += mode to count
        }

        @JvmStatic
        @Implementation
        fun glEnable(cap: Int) {
            if (cap == GLES20.GL_BLEND) GlCalls.blend = true
        }

        @JvmStatic
        @Implementation
        fun glDisable(cap: Int) {
            if (cap == GLES20.GL_BLEND) GlCalls.blend = false
        }

        @JvmStatic
        @Implementation
        fun glDepthMask(flag: Boolean) {
            GlCalls.depthMask = flag
        }
    }
}

@Implements(GLES30::class)
class GlBinding30 {
    companion object {
        @JvmStatic
        @Implementation
        fun glGenVertexArrays(n: Int, arrays: IntArray?, offset: Int) = GlCalls.generate(n, arrays, offset, "arrays")

        @JvmStatic
        @Implementation
        fun glDeleteVertexArrays(n: Int, arrays: IntArray?, offset: Int) = GlCalls.array(arrays, offset, n, "arrays")
    }
}
