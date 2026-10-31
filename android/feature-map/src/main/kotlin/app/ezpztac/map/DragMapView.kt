package app.ezpztac.map

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import org.maplibre.android.maps.MapView

/**
 * The MapLibre view with one thing added: a **long press on something that can be dragged picks it up**, and the finger then moves it until it lifts; a long press on
 * nothing may open the map's menu. What is decided about a touch is [LongPressTouches]'s (tried without a screen), and what is under the finger is [DragHitTest]'s and
 * `MapDrag`'s; this only hands it the touches and lets it reach the map.
 */
internal class DragMapView(context: Context) : MapView(context) {
    private val touches = LongPressTouches(
        context,
        toMap = { super@DragMapView.dispatchTouchEvent(it) },
        taken = {
            parent?.requestDisallowInterceptTouchEvent(true)                    // the sheet and its scroll must not take the touch now
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        },
    )

    var listener: LongPressTouches.Listener?
        get() = touches.listener
        set(value) {
            touches.listener = value
        }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = touches.dispatch(ev)

    override fun onDetachedFromWindow() {
        touches.detached()
        super.onDetachedFromWindow()
    }
}
