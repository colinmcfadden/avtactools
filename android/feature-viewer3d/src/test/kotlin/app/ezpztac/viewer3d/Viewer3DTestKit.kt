package app.ezpztac.viewer3d

import app.ezpztac.data.InMemoryLidarWaiting
import app.ezpztac.data.LidarApi
import app.ezpztac.data.LidarBuilds
import app.ezpztac.data.TerrainProbe
import app.ezpztac.model.LatLon
import app.ezpztac.network.LidarBuildDto
import app.ezpztac.network.LidarResolveDto
import kotlinx.coroutines.CoroutineScope

/** The build service as the 3D view's tests need it: a lookup that finds the tileset built, or not and buildable, and every call written down. */
internal class FakeLidar(var built: Boolean = true) : LidarApi {
    val calls = mutableListOf<String>()

    override suspend fun resolve(at: LatLon, radiusM: Double?): LidarResolveDto {
        calls += "resolve ${at.lat},${at.lon}"
        return if (built) LidarResolveDto(KEY, available = true, url = "/lidar/tilesets/$KEY/tileset.json") else LidarResolveDto(KEY, available = false, canBuild = true)
    }

    override suspend fun build(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double?): LidarBuildDto {
        calls += "build keep=$keep"
        return LidarBuildDto(KEY, "queued", stage = "waiting", position = 2)
    }

    override suspend fun status(key: String, watcher: String?, keep: Boolean): LidarBuildDto {
        calls += "status keep=$keep"
        return LidarBuildDto(key, "queued", stage = "waiting", position = 2)
    }

    override suspend fun release(key: String, watcher: String?) {
        calls += "release $key"
    }

    companion object {
        const val KEY = "0123456789abcdef"
    }
}

/** A probe that answers what it is told, and counts how often it was asked. */
internal class FakeProbe(var ground: Boolean? = true) : TerrainProbe {
    var asked = 0

    override suspend fun hasGround(at: LatLon): Boolean? {
        asked++
        return ground
    }
}

internal fun lidarBuilds(api: LidarApi, scope: CoroutineScope) = LidarBuilds(api, InMemoryLidarWaiting(), scope)
