package app.ezpztac.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.ezpztac.model.LatLon
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Where the device is. It stays on the device: nothing here is logged, stored or sent anywhere (docs/NATIVE_APPS_PLAN.md, "Security, privacy"). */
data class UserLocation(
    val at: LatLon,
    /** Horizontal accuracy radius, in metres, if the receiver says. */
    val accuracyMeters: Float?,
    /** Direction of travel, in degrees true, if moving fast enough to have one. */
    val bearingDegrees: Float?,
    val speedMetersPerSecond: Float?,
    val timeMillis: Long,
)

interface LocationSource {
    fun hasPermission(): Boolean

    /** Fixes as they arrive, starting with the last known one if there is one. Collecting starts the receiver and cancelling stops it. */
    fun updates(): Flow<UserLocation>
}

/**
 * The phone's own GPS receiver through the platform's LocationManager: no Google Play services and no third-party SDK, because a position
 * is exactly what must not leave the device. The GPS provider only: in an aircraft a network fix is no use, and a coarse one is worse
 * than none.
 */
class PlatformLocationSource(private val context: Context) : LocationSource {
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")                       // checked here, and the caller asks for it before collecting
    override fun updates(): Flow<UserLocation> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!hasPermission() || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            close()
            return@callbackFlow
        }
        val listener = LocationListener { location -> trySend(location.toUserLocation()) }
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { trySend(it.toUserLocation()) }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        awaitClose { manager.removeUpdates(listener) }
    }
}

internal fun Location.toUserLocation() = UserLocation(
    at = LatLon(latitude, longitude),
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    bearingDegrees = if (hasBearing()) bearing else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    timeMillis = time,
)
