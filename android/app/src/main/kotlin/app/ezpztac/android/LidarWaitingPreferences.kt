package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.data.LidarWaiting
import app.ezpztac.data.newWatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LidarWaiting] in a plain preference: this device's watcher id (random, made once) and the opaque keys of the point-cloud builds it was waiting
 * on, so the next launch can let go of what an app ended without a word was waiting for. Nothing in it names a place.
 */
@Singleton
class LidarWaitingPreferences @Inject constructor(@ApplicationContext context: Context) : LidarWaiting {
    private val prefs = context.getSharedPreferences("viewer3d", Context.MODE_PRIVATE)
    private val valid = Regex("[A-Za-z0-9_-]{8,64}")
    private val key = Regex("[0-9a-f]{16}")

    @Synchronized
    override fun watcher(): String = prefs.getString(WATCHER, null)?.takeIf { valid.matches(it) }
        ?: LidarWaiting.newWatcher().also { prefs.edit { putString(WATCHER, it) } }

    // Read as untrusted text: only what could be a key is let go of.
    override fun inFlight(): Set<String> = prefs.getStringSet(IN_FLIGHT, null).orEmpty().filterTo(HashSet()) { key.matches(it) }

    override fun setInFlight(keys: Set<String>) = prefs.edit { putStringSet(IN_FLIGHT, HashSet(keys)) }

    private companion object {
        const val WATCHER = "watcher"
        const val IN_FLIGHT = "builds_waited_on"
    }
}
