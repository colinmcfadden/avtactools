package app.ezpztac.viewer3d

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The 3D view's activity made again for real (Robolectric's recreation, the view model kept in the activity's store as Hilt's is). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Viewer3DActivityRecreateTest {
    /** An activity that hosts the view over the view model it was handed, as the app's does over Hilt's. */
    class Host : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(target, night = false, onClose = { target = null }, viewModel = model!!, surface = {}) } }
        }

        companion object {
            var model: Viewer3DViewModel? = null
            var target: Viewer3DTarget? = null
        }
    }

    @After
    fun forget() {
        Host.model = null
        Host.target = null
    }

    // The main thread's clock stepped until [done], a little at a time and no further than a few seconds.
    private fun idleUntil(done: () -> Boolean) {
        repeat(200) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
    }

    @Test
    fun `an activity made again keeps the point cloud's scene as it was`() {
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        Host.model = model
        Host.target = Viewer3DTarget("lz-1", "LZ HAWK", 34.591552, -84.128225, saved = false)
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        idleUntil { model.session.value != null }
        val session = model.session.value
        assertNotNull(session)
        controller.recreate()
        idleUntil { false }
        assertSame("the same scene, its camera and tiles in hand", session, model.session.value)
        controller.pause().stop().destroy()
        idleUntil { model.session.value == null }
        assertEquals(null, model.session.value)
    }
}
