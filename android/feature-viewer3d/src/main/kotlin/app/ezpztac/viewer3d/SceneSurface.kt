package app.ezpztac.viewer3d

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.ezpztac.scene3d.ScreenPoint
import app.ezpztac.scene3d.TouchGestures

/** Whether this device can draw the 3D view: OpenGL ES 3.0 or later, as it says of itself. Anything that cannot be asked is a no. */
internal fun canDraw3D(context: Context): Boolean = try {
    (context.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= GLES_3_0
} catch (_: RuntimeException) {
    false
}

private const val GLES_3_0 = 0x30000

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
    private var lifecycle: Lifecycle? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(SceneConfigChooser)
        preserveEGLContextOnPause = true
        setZOrderMediaOverlay(true)
        setRenderer(SceneRenderer(session, resources.displayMetrics.density.toDouble(), ask))
        renderMode = RENDERMODE_WHEN_DIRTY
        session.listener = ask
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(this) }
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(this)
        lifecycle = null
        super.onDetachedFromWindow()
    }

    override fun onResume(owner: LifecycleOwner) {
        onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
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
