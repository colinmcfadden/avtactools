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

/** The server's minimum version as the device remembers it: what packs, invitations and the sync go by between looks at the config. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MinimumVersionPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `nothing heard is no minimum`() {
        assertNull(MinimumVersionPreferences(context).remembered.value)
    }

    @Test
    fun `what is remembered is there at once, and for a fresh instance as after a launch`() {
        val kept = MinimumVersionPreferences(context)
        kept.remember("1.9.0")
        assertEquals("1.9.0", kept.remembered.value)
        assertEquals("1.9.0", MinimumVersionPreferences(context).remembered.value)
    }

    @Test
    fun `a config with no minimum forgets the one remembered`() {
        MinimumVersionPreferences(context).remember("1.9.0")
        val kept = MinimumVersionPreferences(context)
        kept.remember(null)
        assertNull(kept.remembered.value)
        assertNull(MinimumVersionPreferences(context).remembered.value)
    }
}
