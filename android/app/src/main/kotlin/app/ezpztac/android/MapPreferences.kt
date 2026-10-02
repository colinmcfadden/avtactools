package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.map.CameraMemory
import app.ezpztac.map.CameraState
import app.ezpztac.map.MapTokenSource
import app.ezpztac.model.LatLon
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the app writes down the Mapbox token the server gave it. A seam, so the shell is tried without Android. */
interface MapTokenSink {
    fun update(token: String?)
}

/**
 * What the map remembers between launches: the public Mapbox token (so a start with no signal can still draw satellite imagery) and where
 * the camera last was. Neither is a secret, and both stay on the device: the token is a public `pk.` one, and a camera position is the
 * area the person was looking at, which nothing sends anywhere. Excluded from backup with the rest of the app's data.
 */
@Singleton
class MapPreferences @Inject constructor(@ApplicationContext context: Context) : MapTokenSource, MapTokenSink, CameraMemory {
    private val prefs = context.getSharedPreferences("map", Context.MODE_PRIVATE)
    private val _token = MutableStateFlow(prefs.getString(TOKEN, null)?.takeIf { it.startsWith("pk.") })

    override val token: StateFlow<String?> = _token.asStateFlow()

    override fun update(token: String?) {
        // Only a public token is kept; anything else the server might one day send is ignored rather than stored.
        val kept = token?.takeIf { it.startsWith("pk.") } ?: return
        if (kept == _token.value) return
        prefs.edit { putString(TOKEN, kept) }
        _token.value = kept
    }

    /** Where the camera was when the app was last used, or null the first time. */
    override fun last(): CameraState? {
        val lat = prefs.getString(LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(LON, null)?.toDoubleOrNull() ?: return null
        val zoom = prefs.getString(ZOOM, null)?.toDoubleOrNull() ?: DEFAULT_ZOOM
        return CameraState(LatLon(lat, lon), zoom)
    }

    override fun save(camera: CameraState) {
        // As text: a float would lose the last metre or two of a position.
        prefs.edit {
            putString(LAT, camera.center.lat.toString())
            putString(LON, camera.center.lon.toString())
            putString(ZOOM, camera.zoom.toString())
        }
    }

    companion object {
        private const val TOKEN = "mapboxToken"
        private const val LAT = "lat"
        private const val LON = "lon"
        private const val ZOOM = "zoom"
        private const val DEFAULT_ZOOM = 4.0
    }
}
