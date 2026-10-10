package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Night mode, as the device keeps it: off until the crew turns it on, and still on after a launch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DisplayPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `night mode is off until it is turned on`() {
        assertEquals(false, DisplayPreferences(context).night.value)
    }

    @Test
    fun `turned on it is on at once, and for a fresh instance as after a launch`() {
        val kept = DisplayPreferences(context)
        kept.setNight(true)
        assertEquals(true, kept.night.value)
        assertEquals(true, DisplayPreferences(context).night.value)
        kept.setNight(false)
        assertEquals(false, DisplayPreferences(context).night.value)
    }
}
