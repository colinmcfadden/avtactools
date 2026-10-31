package app.ezpztac.map

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent

/**
 * What [DragMapView] does with a touch before the map has it, apart from the view so it can be tried without MapLibre: a **long press on something that can be dragged
 * picks it up**, and the finger then moves it until it lifts; a long press on nothing may open the map's menu ([Listener.onPress]), and then the rest of that touch is
 * nobody's, so the map does not pan away from the menu while the finger is still down. Any other touch, or one that moves before the press is long enough, or has a second
 * finger, is the map's as it always was. Once something is taken, the map is sent a cancel ([toMap]) so it neither pans nor flings with the finger. [taken] says so to the
 * view (the sheet and its scroll must not take the touch, and the phone buzzes). Coordinates are the view's pixels.
 *
 * **A double tap is never a long press**: MapLibre's quick zoom is a tap, then a touch that drags up or down, and the platform's detector, tracking double taps, keeps the
 * second touch's long press waiting however far the finger moves; a slow zoom would open the menu (or pick something up) part way through and cut the zoom off. With no
 * double-tap listener the detector treats the second touch as any other, and a finger that moves past the touch slop is not a press.
 */
internal class LongPressTouches(
    context: Context,
    private val toMap: (MotionEvent) -> Boolean,
    private val taken: () -> Unit = {},
) {
    interface Listener {
        /** A long press at ([x], [y]): true when something there was picked up, so the touch is a drag from now on. */
        fun onPickUp(x: Float, y: Float): Boolean

        fun onDrag(x: Float, y: Float)

        fun onDrop(x: Float, y: Float)

        /** The touch was taken away (a system gesture, or the view went): put it back. */
        fun onDragCancelled()

        /** A long press at ([x], [y]) that picked nothing up: true when it was taken (the map's menu opened), so the rest of the touch is not the map's. */
        fun onPress(x: Float, y: Float): Boolean
    }

    var listener: Listener? = null

    private var dragging = false

    /** A long press opened the menu: the rest of this touch goes nowhere. */
    private var swallowing = false

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (dragging || swallowing) return
                val listener = listener ?: return
                when {
                    listener.onPickUp(e.x, e.y) -> dragging = true
                    listener.onPress(e.x, e.y) -> swallowing = true
                    else -> return
                }
                taken()
                // The map has been following this touch since it began: tell it the touch is over, so it neither pans nor flings with the finger.
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                toMap(cancel)
                cancel.recycle()
            }
        },
    ).apply {
        setIsLongpressEnabled(true)
        setOnDoubleTapListener(null)                                                     // the second touch of a quick zoom is not a press (above)
    }

    /** The touch, before the map: true when it was taken here. */
    fun dispatch(ev: MotionEvent): Boolean {
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
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
        return toMap(ev)
    }

    /** The view went: whatever was held goes back. */
    fun detached() {
        swallowing = false
        if (dragging) {
            dragging = false
            listener?.onDragCancelled()
        }
    }
}
