package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.OpenThreatSetStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OpenThreatSetStore] in a plain preference, as [OpenRouteSetPreferences] is for the route sets: the open threat sets as their record uuids, in order. Nothing about a
 * threat is kept here, and an id the signed-in account does not have is simply never found. A value that cannot be read is no list.
 */
@Singleton
class OpenThreatSetPreferences @Inject constructor(@ApplicationContext context: Context) : OpenThreatSetStore {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val list = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return list.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull }
    }

    override fun save(ids: List<String>) {
        prefs.edit { putString(KEY, JsonArray(ids.map(::JsonPrimitive)).toString()) }
    }

    private companion object {
        const val KEY = "open_threat_sets"
    }
}
