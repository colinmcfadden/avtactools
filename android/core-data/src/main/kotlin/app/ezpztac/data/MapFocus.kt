package app.ezpztac.data

import app.ezpztac.geo.MapZoom
import app.ezpztac.model.LatLon
import app.ezpztac.planning.MissionRoutes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A request, from anywhere in the sheet, to take the map somewhere: what a person who has just brought a mission in wants to see is where it is. The map
 * listens (`MapHome`); nothing here knows what a map is. A request nobody is listening for is dropped, not replayed: moving the map when it comes back
 * to the screen long after would be a surprise.
 */
@Singleton
public class MapFocus @Inject constructor() {
    public data class Request(val at: LatLon, val zoom: Double)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    public val requests: SharedFlow<Request> = _requests.asSharedFlow()

    public fun show(at: LatLon, zoom: Double) {
        _requests.tryEmit(Request(at, zoom))
    }

    /** Takes the map to [extent], at the zoom that fits it. */
    public fun show(extent: MissionRoutes.Extent) {
        show(extent.center, MapZoom.fitting(extent.center.lat, extent.spanMeters))
    }
}
