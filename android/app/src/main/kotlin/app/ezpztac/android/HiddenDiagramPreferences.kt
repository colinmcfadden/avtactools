package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.HiddenDiagramStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HiddenDiagramStore] in a plain preference, beside the open LZ/PZs ([OpenDiagramPreferences]): the ids of those hidden from the map, oldest first. Ids only,
 * which name no place; this device's own, never synced or sent in a pack. A value that cannot be read hides nothing: a damaged preference costs the person their
 * choice of what is hidden, never the app.
 */
@Singleton
class HiddenDiagramPreferences @Inject constructor(@ApplicationContext context: Context) : HiddenDiagramStore {
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
        const val KEY = "hidden_diagrams"
    }
}
