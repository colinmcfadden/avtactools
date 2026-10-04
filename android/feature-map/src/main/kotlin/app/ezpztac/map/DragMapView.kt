package app.ezpztac.map

import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import org.maplibre.android.maps.MapView

/**
 * The MapLibre view with one thing added: a **long press on something that can be dragged picks it up**, and the finger then moves it until it lifts.
 *
 * A press that is not on such a thing, or any touch that moves before the press is long enough, or has a second finger, is MapLibre's as it always was (pan, pinch, turn). Once
 * the [listener] says it has picked something up, the touch is taken from the map (the map is sent a cancel, so it stops following the finger) and every move of it, until it
 * lifts, is the [listener]'s. Coordinates are in this view's pixels.
 *
 * Nothing here can be tried without a screen; what is decided (what is under the finger, where it goes) is in [DragHitTest] and `MapDrag`, which are.
 */
internal class DragMapView(context: Context) : MapView(context) {
    interface Listener {
        /** A long press at ([x], [y]): true when something there was picked up, so the touch is a drag from now on. */
        fun onPickUp(x: Float, y: Float): Boolean

        fun onDrag(x: Float, y: Float)

        fun onDrop(x: Float, y: Float)

        /** The touch was taken away (a system gesture, or the view went): put it back. */
        fun onDragCancelled()
    }

    var listener: Listener? = null

    private var dragging = false

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (dragging || listener?.onPickUp(e.x, e.y) != true) return
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)                // the sheet and its scroll must not take the touch now
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // The map has been following this touch since it began: tell it the touch is over, so it neither pans nor flings with the finger.
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super@DragMapView.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        },
    ).apply { setIsLongpressEnabled(true) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> listener?.onDrag(ev.x, ev.y)
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    listener?.onDrop(ev.x, ev.y)
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    listener?.onDragCancelled()
                }
                else -> Unit                                                    // a second finger while something is held changes nothing
            }
            return true
        }
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        if (dragging) {
            dragging = false
            listener?.onDragCancelled()
        }
        super.onDetachedFromWindow()
    }
}
