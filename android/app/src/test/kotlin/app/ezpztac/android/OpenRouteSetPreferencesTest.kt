package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.data.OpenRouteSets
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The open route sets as the device keeps them, as the open LZ/PZs are: in order, still there after a launch, apart from the LZ/PZs, and a damaged value is no list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenRouteSetPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().remove("open_route_sets").commit()
    }

    @Test
    fun `nothing kept is nothing open`() {
        assertEquals(emptyList<String>(), OpenRouteSetPreferences(context).load())
    }

    @Test
    fun `what is opened is there, in order, after a launch`() {
        val open = OpenRouteSets(OpenRouteSetPreferences(context))
        open.add("a")
        open.add("b")
        open.add("a")                                                                              // already open: its place is kept
        assertEquals(listOf("a", "b"), OpenRouteSets(OpenRouteSetPreferences(context)).ids.value)
        open.remove("a")
        assertEquals(listOf("b"), OpenRouteSets(OpenRouteSetPreferences(context)).ids.value)
    }

    @Test
    fun `a mission pack's set is never kept, and the set keeps only the last twenty`() {
        val open = OpenRouteSets(OpenRouteSetPreferences(context))
        open.add("pack:7c1e:3f9a")
        assertEquals(emptyList<String>(), open.ids.value)
        repeat(25) { open.add("set-$it") }
        assertEquals((5 until 25).map { "set-$it" }, OpenRouteSets(OpenRouteSetPreferences(context)).ids.value)
    }

    @Test
    fun `a damaged value is no list`() {
        prefs.edit().putString("open_route_sets", "{not a list").commit()
        assertEquals(emptyList<String>(), OpenRouteSetPreferences(context).load())
        prefs.edit().putString("open_route_sets", "[\"a\", 3, null, \"b\"]").commit()
        assertEquals(listOf("a", "b"), OpenRouteSetPreferences(context).load())                    // what cannot be an id is skipped
    }

    @Test
    fun `the open route sets and the open landing zones are kept apart`() {
        OpenRouteSets(OpenRouteSetPreferences(context)).add("set-1")
        val diagrams = app.ezpztac.data.OpenDiagrams(OpenDiagramPreferences(context))
        diagrams.add("lz-1")
        assertEquals(listOf("set-1"), OpenRouteSetPreferences(context).load())
        assertEquals(listOf("lz-1"), OpenDiagramPreferences(context).load())
        diagrams.remove("lz-1")
    }
}
