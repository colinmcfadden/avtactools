package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.data.OpenDiagrams
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The open LZ/PZs as the device keeps them: in order, still there after a launch, and a damaged value is no list rather than a crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenDiagramPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().remove("open_diagrams").commit()
    }

    @Test
    fun `nothing kept is nothing open`() {
        assertEquals(emptyList<String>(), OpenDiagramPreferences(context).load())
    }

    @Test
    fun `what is opened is there, in order, after a launch`() {
        val open = OpenDiagrams(OpenDiagramPreferences(context))
        open.add("a")
        open.add("b")
        open.add("a")                                                                              // already open: its place is kept
        assertEquals(listOf("a", "b"), OpenDiagrams(OpenDiagramPreferences(context)).ids.value)
        open.remove("a")
        assertEquals(listOf("b"), OpenDiagrams(OpenDiagramPreferences(context)).ids.value)
    }

    @Test
    fun `a mission pack's item is never kept, and the set keeps only the last twenty`() {
        val open = OpenDiagrams(OpenDiagramPreferences(context))
        open.add("pack:7c1e:3f9a")
        assertEquals(emptyList<String>(), open.ids.value)
        repeat(25) { open.add("lz-$it") }
        assertEquals((5 until 25).map { "lz-$it" }, OpenDiagrams(OpenDiagramPreferences(context)).ids.value)
    }

    @Test
    fun `a damaged value is no list`() {
        prefs.edit().putString("open_diagrams", "{not a list").commit()
        assertEquals(emptyList<String>(), OpenDiagramPreferences(context).load())
        prefs.edit().putString("open_diagrams", "[\"a\", 3, null, \"b\"]").commit()
        assertEquals(listOf("a", "b"), OpenDiagramPreferences(context).load())                    // what cannot be an id is skipped
    }
}
