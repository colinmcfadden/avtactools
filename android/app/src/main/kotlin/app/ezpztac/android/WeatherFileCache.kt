package app.ezpztac.android

import android.content.Context
import app.ezpztac.data.WeatherCache
import app.ezpztac.data.WeatherCodec
import app.ezpztac.model.WeatherSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WeatherCache] in a file in the app's own storage, which is not backed up (the manifest turns backup off, and a landing zone's position is in it). It is
 * written whole, to a temporary file that then replaces the real one, so a process that ends part way through never leaves half a file; and a file that
 * cannot be read is no cache, never a crash.
 */
@Singleton
class WeatherFileCache @Inject constructor(@ApplicationContext context: Context) : WeatherCache {
    private val file = File(context.filesDir, "weather.json")

    override fun load(): Map<String, WeatherSnapshot> = try {
        if (file.isFile) WeatherCodec.decode(file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    override fun save(snapshots: Map<String, WeatherSnapshot>) {
        if (snapshots.isEmpty()) {
            file.delete()
            return
        }
        val temp = File(file.parentFile, "weather.json.tmp")
        temp.writeText(WeatherCodec.encode(snapshots))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "the weather cache could not be replaced" }
        }
    }
}
