package app.ezpztac.map

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** What a touch on the map does before the map has it: a long press picks up, or opens the menu, and nothing else is a long press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LongPressTouchesTest {
    private val said = mutableListOf<String>()
    private val toMap = mutableListOf<Int>()
    private var under = false                                                   // something under the finger to pick up
    private var menu = true                                                     // the menu opens for a press on nothing

    private val touches = LongPressTouches(ApplicationProvider.getApplicationContext(), toMap = { toMap += it.actionMasked; true }, taken = { said += "taken" }).apply {
        listener = object : LongPressTouches.Listener {
            override fun onPickUp(x: Float, y: Float) = under.also { said += "pickUp" }
            override fun onDrag(x: Float, y: Float) { said += "drag" }
            override fun onDrop(x: Float, y: Float) { said += "drop" }
            override fun onDragCancelled() { said += "cancelled" }
            override fun onPress(x: Float, y: Float) = menu.also { said += "press" }
        }
    }

    private var down = 0L

    private fun send(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) down = now
        val ev = MotionEvent.obtain(down, now, action, x, y, 0)
        touches.dispatch(ev)
        ev.recycle()
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `a still long press on nothing opens the menu, and the rest of that touch is nobody's`() {
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        pass(700)
        assertEquals(listOf("pickUp", "press", "taken"), said)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), toMap)        // the map is told the touch is over
        send(MotionEvent.ACTION_MOVE, 160f, 300f)
        send(MotionEvent.ACTION_UP, 160f, 300f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), toMap)        // neither the move nor the lift reached it
        send(MotionEvent.ACTION_DOWN, 10f, 10f)                                               // the next touch is the map's again
        assertEquals(MotionEvent.ACTION_DOWN, toMap.last())
    }

    @Test
    fun `a long press on something picks it up and the finger drags it`() {
        under = true
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        pass(700)
        send(MotionEvent.ACTION_MOVE, 140f, 100f)
        send(MotionEvent.ACTION_UP, 140f, 100f)
        assertEquals(listOf("pickUp", "taken", "drag", "drop"), said)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), toMap)
    }

    @Test
    fun `a press the menu does not take stays the map's`() {
        menu = false
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        pass(700)
        send(MotionEvent.ACTION_MOVE, 100f, 300f)
        assertEquals(listOf("pickUp", "press"), said)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), toMap)
    }

    @Test
    fun `a slow quick zoom, a tap and then a touch dragged down, is never a long press, and the map has every event`() {
        send(MotionEvent.ACTION_DOWN, 200f, 200f)
        pass(40)
        send(MotionEvent.ACTION_UP, 200f, 200f)
        pass(120)
        send(MotionEvent.ACTION_DOWN, 202f, 202f)                                             // the second touch, where the first was
        for (step in 1..12) {                                                                   // dragged down slowly, well past the long-press time
            pass(80)
            send(MotionEvent.ACTION_MOVE, 202f, 202f + step * 20)
        }
        send(MotionEvent.ACTION_UP, 202f, 442f)
        assertEquals(emptyList<String>(), said)
        assertEquals(16, toMap.size)
        assertTrue(MotionEvent.ACTION_CANCEL !in toMap)
    }

    @Test
    fun `a pan that moves before the press is long enough is the map's`() {
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        pass(100)
        send(MotionEvent.ACTION_MOVE, 160f, 100f)
        pass(700)
        assertEquals(emptyList<String>(), said)
    }
}
