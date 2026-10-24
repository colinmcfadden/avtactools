package app.ezpztac.viewer3d

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.ezpztac.scene3d.ScreenPoint
import app.ezpztac.scene3d.TouchGestures

/**
 * Whether this device can draw the 3D view: OpenGL ES 3.0 or later, as it says of itself, with a surface EGL can give it
 * ([SceneConfigChooser.onDevice]). Anything that cannot be asked is a no.
 */
internal fun canDraw3D(context: Context): Boolean = try {
    (context.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= GLES_3_0 && SceneConfigChooser.onDevice()
} catch (_: RuntimeException) {
    false
}

private const val GLES_3_0 = 0x30000

/**
 * Where on a scene [widthPx] by [heightPx] a finger pans it rather than going Back (which would close the view and lose its camera): a band
 * [EDGE_DP] wide down each side, over the middle [EXCLUDED_DP] of it, as much of each edge as Android lets an app take from its back gesture.
 * Back is still there above and below them, and on the page's own close button.
 */
internal fun edgeBands(widthPx: Int, heightPx: Int, density: Float): List<Rect> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val band = (EXCLUDED_DP * density).toInt().coerceAtMost(heightPx)
    val top = (heightPx - band) / 2
    val edge = (EDGE_DP * density).toInt().coerceAtMost(widthPx / 2)
    return listOf(Rect(0, top, edge, top + band), Rect(widthPx - edge, top, widthPx, top + band))
}

/** How far in from each side the back gesture reaches, about: wider than any phone's own (24 to 32 points). */
internal const val EDGE_DP = 40f

/** The most of an edge Android lets an app take from its gestures. */
internal const val EXCLUDED_DP = 200f

/**
 * The view the scene is drawn in (OpenGL ES 3.0, [SceneRenderer]), made only where [canDraw3D]: drawn when the session asks (a tile came, a
 * flight goes on) and when the fingers move the camera, as Cesium's touch controls move the web's ([TouchGestures]). Its surface sits above the
 * map's own (MapLibre draws into a surface too) and under the window, so the page's bar and controls are drawn over it. It pauses and resumes
 * with the screen it is on, and keeps its context through a pause where the device can.
 */
@SuppressLint("ViewConstructor")
internal class SceneSurface(context: Context, private val session: SceneSession) : GLSurfaceView(context), DefaultLifecycleObserver {
    private val gestures = TouchGestures(ViewConfiguration.get(context).scaledTouchSlop.toDouble())
    private val ask: () -> Unit = { requestRender() }
    private val renderer = SceneRenderer(session, resources.displayMetrics.density.toDouble(), ask)
    private var lifecycle: Lifecycle? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(SceneConfigChooser)
        preserveEGLContextOnPause = true
        setZOrderMediaOverlay(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        session.listener = ask
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(this) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        systemGestureExclusionRects = edgeBands(width, height, resources.displayMetrics.density)
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(this)
        lifecycle = null
        super.onDetachedFromWindow()
    }

    override fun onResume(owner: LifecycleOwner) {
        onResume()
    }

    // Going to the back, the less the app holds the less likely the system is to end it: what is not on the screen is let go of, its GPU copies
    // freed by an event the GL thread runs before it pauses, its context still current (a paused thread draws no frame to free them in).
    override fun onPause(owner: LifecycleOwner) {
        session.trim()
        queueEvent(renderer::releaseTrimmed)
        onPause()
    }

    /** The view is going: the session no longer asks it for frames. */
    fun release() {
        if (session.listener === ask) session.listener = null
    }

    // Every finger down, as a map from its id to where it is; the one lifting is left out of the event that lifts it.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestures.lifted()
            else -> {
                val lifting = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
                val fingers = HashMap<Int, ScreenPoint>(event.pointerCount)
                for (i in 0 until event.pointerCount) {
                    if (i != lifting) fingers[event.getPointerId(i)] = ScreenPoint(event.getX(i).toDouble(), event.getY(i).toDouble())
                }
                session.gesture(gestures.fingers(fingers, height.toDouble()))
            }
        }
        return true
    }
}
