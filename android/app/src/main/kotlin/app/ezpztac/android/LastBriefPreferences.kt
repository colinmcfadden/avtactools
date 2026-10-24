package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.LastBrief
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LastBrief] in a plain preference, one for each account (the web keeps the same in `ezpz.brief.<user>`): the Library record's uuid, a random id that says
 * nothing of the brief, so another person signing in on the device never opens it, and each account finds its own again.
 */
@Singleton
class LastBriefPreferences @Inject constructor(@ApplicationContext context: Context) : LastBrief {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun id(user: Int): String? = prefs.getString(keyOf(user), null)

    override fun remember(user: Int, uuid: String?) = prefs.edit {
        if (uuid == null) remove(keyOf(user)) else putString(keyOf(user), uuid)
    }

    private fun keyOf(user: Int) = "last_brief.$user"
}
