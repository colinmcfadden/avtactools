package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Who waits for point-cloud builds from this device, and which builds it waited on, as kept between launches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LidarWaitingPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("viewer3d", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `the watcher is made once and is the same after a launch`() {
        val first = LidarWaitingPreferences(context).watcher()
        assertTrue(Regex("[A-Za-z0-9_-]{8,64}").matches(first))
        assertEquals(first, LidarWaitingPreferences(context).watcher())
    }

    @Test
    fun `a stored watcher the service would drop is made again`() {
        prefs.edit().putString("watcher", "short").commit()
        val made = LidarWaitingPreferences(context).watcher()
        assertNotEquals("short", made)
        assertTrue(Regex("[A-Za-z0-9_-]{8,64}").matches(made))
    }

    @Test
    fun `the builds waited on outlive a launch, and only what could be a key is read back`() {
        LidarWaitingPreferences(context).setInFlight(setOf("0123456789abcdef", "fedcba9876543210"))
        assertEquals(setOf("0123456789abcdef", "fedcba9876543210"), LidarWaitingPreferences(context).inFlight())
        prefs.edit().putStringSet("builds_waited_on", setOf("0123456789abcdef", "../../auth", "34.59,-84.12")).commit()
        assertEquals(setOf("0123456789abcdef"), LidarWaitingPreferences(context).inFlight())
    }
}
