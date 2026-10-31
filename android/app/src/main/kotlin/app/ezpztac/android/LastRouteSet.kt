package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Which set of routes was open when the app was last used, so the next launch can open it again. */
interface LastRouteSet {
    fun id(): String?

    fun remember(id: String)
}

/**
 * [LastRouteSet] in a plain preference, as [LastDiagramPreferences] is for diagrams: the set's *id* only (a random uuid, nothing about a route or a
 * place), and an id the signed-in account does not have is simply not found.
 */
@Singleton
class LastRouteSetPreferences @Inject constructor(@ApplicationContext context: Context) : LastRouteSet {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun id(): String? = prefs.getString(LAST_ROUTE_SET, null)

    override fun remember(id: String) = prefs.edit { putString(LAST_ROUTE_SET, id) }

    private companion object {
        const val LAST_ROUTE_SET = "last_route_set"
    }
}
