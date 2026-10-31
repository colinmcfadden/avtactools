package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Which diagram was open when the app was last used, so the next launch can open it again. */
interface LastDiagram {
    fun id(): String?

    fun remember(id: String)
}

/**
 * [LastDiagram] in a plain preference. It is the diagram's *id* only (a random uuid: no place, no name), so nothing about a landing zone is kept
 * here, and an id the signed-in account does not have is simply not found.
 */
@Singleton
class LastDiagramPreferences @Inject constructor(@ApplicationContext context: Context) : LastDiagram {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun id(): String? = prefs.getString(LAST_DIAGRAM, null)

    override fun remember(id: String) = prefs.edit { putString(LAST_DIAGRAM, id) }

    private companion object {
        const val LAST_DIAGRAM = "last_diagram"
    }
}
