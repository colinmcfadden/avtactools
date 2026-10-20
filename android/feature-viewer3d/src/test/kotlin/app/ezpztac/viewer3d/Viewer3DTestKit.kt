package app.ezpztac.viewer3d

import app.ezpztac.data.InMemoryLidarWaiting
import app.ezpztac.data.LidarApi
import app.ezpztac.data.LidarBuilds
import app.ezpztac.data.TerrainProbe
import app.ezpztac.data.TilesetFiles
import app.ezpztac.model.LatLon
import app.ezpztac.network.LidarBuildDto
import app.ezpztac.network.LidarResolveDto
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Collections

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

/**
 * The tileset py3dtiles made (contracts/fixtures/viewer3d/tileset) as the server keeps it, under any key, every ask written down as `key/path`;
 * [failing] says what to throw for a path instead of its bytes (null: its bytes).
 */
internal class FixtureFiles(var failing: (String) -> Exception? = { null }) : TilesetFiles {
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun file(key: String, path: String): ByteArray {
        asked += "$key/$path"
        failing(path)?.let { throw it }
        return Fixtures.bytes("viewer3d/tileset/$path")
    }
}

/** Scenes over [files] whose fetches and reading run on [dispatcher] (at once, with the default), on [clock]. */
internal fun scenes(
    files: TilesetFiles = FixtureFiles(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) = SceneMaker { target, core, ring, parent, size -> SceneSession(target, core, ring, files, SceneBudgets.DEFAULT, parent, size, dispatcher, dispatcher, clock) }

/** The view model over stand-ins: a lookup over [api], the probe, scenes over the fixture's files, and the point size kept in memory. */
internal fun viewModel(
    api: LidarApi,
    scope: CoroutineScope,
    probe: TerrainProbe = FakeProbe(),
    scenes: SceneMaker = scenes(),
    sizes: PointSizeMemory = InMemoryPointSizes(),
) = Viewer3DViewModel(lidarBuilds(api, scope), probe, scenes, sizes)
