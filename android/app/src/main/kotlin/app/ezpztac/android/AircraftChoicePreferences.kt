package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.ActiveAircraftChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which airframe this device plans with, by slug (the web keeps it in `localStorage` under `avtac.aircraftProfile`). It is a preference of this
 * device and not of the account, as on the web; a slug that the signed-in account does not have is simply not found, and the UH-60L stands in.
 */
@Singleton
class AircraftChoicePreferences @Inject constructor(@ApplicationContext context: Context) : ActiveAircraftChoice {
    private val prefs = context.getSharedPreferences("aircraft", Context.MODE_PRIVATE)

    override fun slug(): String? = prefs.getString(SLUG, null)

    override fun choose(slug: String) = prefs.edit { putString(SLUG, slug) }

    private companion object {
        const val SLUG = "active_slug"
    }
}
