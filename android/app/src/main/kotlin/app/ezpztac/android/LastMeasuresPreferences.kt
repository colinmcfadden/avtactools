package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.LastMeasures
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LastMeasures] in a plain preference, as [LastRouteSetPreferences] is for route sets: the set's *id* only (a random uuid, nothing about a line or a place),
 * and an id the signed-in account does not have is simply not found.
 */
@Singleton
class LastMeasuresPreferences @Inject constructor(@ApplicationContext context: Context) : LastMeasures {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun id(): String? = prefs.getString(LAST_MEASURES, null)

    override fun remember(id: String?) = prefs.edit { if (id == null) remove(LAST_MEASURES) else putString(LAST_MEASURES, id) }

    private companion object {
        const val LAST_MEASURES = "last_measures"
    }
}
