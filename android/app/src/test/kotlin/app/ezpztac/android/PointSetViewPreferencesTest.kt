package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.data.PointSetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the device keeps of how each set of points is shown: a colour and whether it is on the map, by the set's uuid, and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PointSetViewPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun raw(json: String) = context.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().putString("point_set_views", json).commit()

    @Test
    fun `nothing kept is no views`() {
        assertTrue(PointSetViewPreferences(context).load().isEmpty())
    }

    @Test
    fun `what is saved is what is loaded, by a fresh instance as after a launch`() {
        val views = mapOf("a" to PointSetView("#0A84FF", visible = true), "b" to PointSetView("#FF453A", visible = false))
        PointSetViewPreferences(context).save(views)
        assertEquals(views, PointSetViewPreferences(context).load())
    }

    @Test
    fun `saving replaces what was there, so a set that is gone is gone`() {
        val preferences = PointSetViewPreferences(context)
        preferences.save(mapOf("a" to PointSetView("#0A84FF"), "b" to PointSetView("#FF453A")))
        preferences.save(mapOf("a" to PointSetView("#0A84FF")))
        assertEquals(setOf("a"), preferences.load().keys)
    }

    @Test
    fun `a damaged preference costs the colours, not the app`() {
        raw("{ not json")
        assertTrue(PointSetViewPreferences(context).load().isEmpty())
        raw("[1, 2]")
        assertTrue(PointSetViewPreferences(context).load().isEmpty())
        raw("\"text\"")
        assertTrue(PointSetViewPreferences(context).load().isEmpty())
    }

    @Test
    fun `an entry that cannot be read is skipped and the others are kept`() {
        raw(
            """{"good":{"color":"#0A84FF","visible":false},"noColor":{"visible":true},"notHex":{"color":"red"},"short":{"color":"#FFF"},"notAnObject":3,
               "injected":{"color":"#FF0000\"}, \"x"}}""",
        )
        val views = PointSetViewPreferences(context).load()
        assertEquals(setOf("good"), views.keys)
        assertEquals(PointSetView("#0A84FF", visible = false), views.getValue("good"))
    }

    @Test
    fun `a missing or odd visible means shown`() {
        raw("""{"a":{"color":"#0A84FF"},"b":{"color":"#0A84FF","visible":"no"}}""")
        val views = PointSetViewPreferences(context).load()
        assertTrue(views.getValue("a").visible)
        assertTrue(views.getValue("b").visible)
    }
}
