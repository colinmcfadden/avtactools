package app.ezpztac.viewer3d

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The point size the person chose (2 to 16), kept on the device as the web keeps it in the browser: how big the points should look depends on the
 * screen as much as on the data, so it is the viewer's to pick.
 */
interface PointSizeMemory {
    fun get(): Int?

    fun set(size: Int)
}

/** [PointSizeMemory] in memory, for tests. */
class InMemoryPointSizes(@Volatile private var size: Int? = null) : PointSizeMemory {
    override fun get(): Int? = size

    override fun set(size: Int) {
        this.size = size
    }
}

/** [PointSizeMemory] in a plain preference: a number, nothing about a place. One that cannot be read is none. */
class PointSizePreferences @Inject constructor(@ApplicationContext context: Context) : PointSizeMemory {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(): Int? = try {
        if (prefs.contains(KEY)) prefs.getInt(KEY, 0) else null
    } catch (_: ClassCastException) {
        null
    }

    override fun set(size: Int) {
        prefs.edit { putInt(KEY, size) }
    }

    private companion object {
        const val FILE = "viewer3d"
        const val KEY = "point_size"
    }
}
