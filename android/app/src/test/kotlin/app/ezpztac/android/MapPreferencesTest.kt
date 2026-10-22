package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the person likes the map to look, as the device keeps it between launches: here, the slope map's opacity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.getSharedPreferences("map", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `no slope opacity is kept until one is set, and then it is there after a launch`() {
        assertNull(MapPreferences(context).slopeOpacity())
        MapPreferences(context).setSlopeOpacity(35)
        assertEquals(35, MapPreferences(context).slopeOpacity())
    }

    @Test
    fun `something else under its name is none, not a crash`() {
        context.getSharedPreferences("map", Context.MODE_PRIVATE).edit(commit = true) { putString("slopeOpacity", "half") }
        assertNull(MapPreferences(context).slopeOpacity())
    }
}
