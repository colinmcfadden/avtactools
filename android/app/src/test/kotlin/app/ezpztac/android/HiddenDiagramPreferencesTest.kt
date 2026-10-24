package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.data.DiagramsOnMap
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The open LZ/PZs hidden from the map as the device keeps them: still hidden after a launch, and a damaged value hides nothing rather than crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HiddenDiagramPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().remove("hidden_diagrams").commit()
    }

    @Test
    fun `nothing kept hides nothing`() {
        assertEquals(emptyList<String>(), HiddenDiagramPreferences(context).load())
    }

    @Test
    fun `what is hidden stays hidden after a launch, and shown again is no longer kept`() {
        val onMap = DiagramsOnMap(HiddenDiagramPreferences(context))
        onMap.toggle("a")
        onMap.toggle("pack:7c1e:3f9a")                                                            // a pack's item is hidden by its own id
        assertEquals(setOf("a", "pack:7c1e:3f9a"), DiagramsOnMap(HiddenDiagramPreferences(context)).hidden.value)
        onMap.toggle("a")
        assertEquals(listOf("pack:7c1e:3f9a"), HiddenDiagramPreferences(context).load())
    }

    @Test
    fun `a damaged value hides nothing`() {
        prefs.edit().putString("hidden_diagrams", "{not a list").commit()
        assertEquals(emptyList<String>(), HiddenDiagramPreferences(context).load())
        prefs.edit().putString("hidden_diagrams", "[\"a\", 3, null, \"b\"]").commit()
        assertEquals(listOf("a", "b"), HiddenDiagramPreferences(context).load())                  // what cannot be an id is skipped
    }
}
