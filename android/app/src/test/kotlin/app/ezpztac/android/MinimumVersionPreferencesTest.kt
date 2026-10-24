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
        kept.remember("1.9.0", null)
        assertEquals("1.9.0", kept.remembered.value)
        assertEquals("1.9.0", MinimumVersionPreferences(context).remembered.value)
    }

    @Test
    fun `a config with no minimum forgets the one remembered`() {
        MinimumVersionPreferences(context).remember("1.9.0", null)
        val kept = MinimumVersionPreferences(context)
        kept.remember(null, null)
        assertNull(kept.remembered.value)
        assertNull(MinimumVersionPreferences(context).remembered.value)
    }

    @Test
    fun `the API majors the server serves are kept beside the minimum`() {
        MinimumVersionPreferences(context).remember(null, listOf(1, 2))
        assertEquals(listOf(1, 2), MinimumVersionPreferences(context).rememberedApi.value)
        MinimumVersionPreferences(context).remember(null, null)
        assertNull(MinimumVersionPreferences(context).rememberedApi.value)
    }

    @Test
    fun `kept majors that cannot be read are none, which holds nothing back`() {
        context.getSharedPreferences("server", Context.MODE_PRIVATE).edit().putString("apiMajorsServed", "1,x").commit()
        assertNull(MinimumVersionPreferences(context).rememberedApi.value)
    }
}
