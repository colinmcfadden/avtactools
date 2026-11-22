package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.ThreatDisplayStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ThreatDisplayStore] in plain preferences: the ids of the threats whose range rings, and whose terrain mask, this device does not draw. Ids only, which name no
 * place; this device's own. A value that cannot be read turns nothing off: a damaged preference costs the person their choice, never the app.
 */
@Singleton
class ThreatDisplayPreferences @Inject constructor(@ApplicationContext context: Context) : ThreatDisplayStore {
    private val prefs = context.getSharedPreferences("threat_display", Context.MODE_PRIVATE)

    override fun load(): Pair<List<String>, List<String>> = read(RINGS_OFF) to read(MASKS_OFF)

    override fun save(ringsOff: List<String>, masksOff: List<String>) {
        prefs.edit {
            putString(RINGS_OFF, JsonArray(ringsOff.map(::JsonPrimitive)).toString())
            putString(MASKS_OFF, JsonArray(masksOff.map(::JsonPrimitive)).toString())
        }
    }

    private fun read(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val list = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    private companion object {
        const val RINGS_OFF = "rings_off"
        const val MASKS_OFF = "masks_off"
    }
}
