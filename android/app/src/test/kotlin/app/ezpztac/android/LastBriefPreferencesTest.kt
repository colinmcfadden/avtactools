package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The brief opened last, as the device keeps it: each account its own (the web's `ezpz.brief.<user>`), so a shared tablet never opens one person's for another. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LastBriefPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `each account finds its own brief, whoever opened one since`() {
        val last = LastBriefPreferences(context)
        last.remember(1, "sams-brief")
        last.remember(2, "colins-brief")
        assertEquals("sams-brief", LastBriefPreferences(context).id(1))                    // after a launch, and after Colin's
        assertEquals("colins-brief", LastBriefPreferences(context).id(2))
        assertNull(LastBriefPreferences(context).id(3))
        last.remember(1, null)
        assertNull(last.id(1))
        assertEquals("colins-brief", last.id(2))
    }
}
