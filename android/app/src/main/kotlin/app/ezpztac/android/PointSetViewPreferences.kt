package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.PointSetView
import app.ezpztac.data.PointSetViewStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PointSetViewStore] in a plain preference: for each set, by its random uuid, the colour it is drawn in and whether it is shown. Nothing about where a
 * point is or what it is called is kept here, and a uuid this account does not have is simply never looked up (and is dropped the next time the sets settle).
 * A value that cannot be read is skipped, so a damaged preference costs a colour, never the app.
 */
@Singleton
class PointSetViewPreferences @Inject constructor(@ApplicationContext context: Context) : PointSetViewStore {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun load(): Map<String, PointSetView> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val obj = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        return obj.mapNotNull { (uuid, element) ->
            val view = element as? JsonObject ?: return@mapNotNull null
            val color = (view["color"] as? JsonPrimitive)?.contentOrNull?.takeIf { COLOR.matches(it) } ?: return@mapNotNull null
            val visible = (view["visible"] as? JsonPrimitive)?.booleanOrNull ?: true
            uuid to PointSetView(color, visible)
        }.toMap()
    }

    override fun save(views: Map<String, PointSetView>) {
        val obj = JsonObject(views.mapValues { (_, v) -> JsonObject(mapOf("color" to JsonPrimitive(v.color), "visible" to JsonPrimitive(v.visible))) })
        prefs.edit { putString(KEY, obj.toString()) }
    }

    private companion object {
        const val KEY = "point_set_views"
        val COLOR = Regex("#[0-9A-Fa-f]{6}")
    }
}
