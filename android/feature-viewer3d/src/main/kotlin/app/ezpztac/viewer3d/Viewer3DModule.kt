package app.ezpztac.viewer3d

import android.app.ActivityManager
import android.content.Context
import app.ezpztac.data.ApiClientGroundUnder
import app.ezpztac.data.ApiClientTerrainHeights
import app.ezpztac.data.ApiClientTilesetFiles
import app.ezpztac.data.CachedTilesetFiles
import app.ezpztac.data.GroundUnder
import app.ezpztac.data.RouteSession
import app.ezpztac.data.TerrainHeights
import app.ezpztac.data.TilesetCacheCleaner
import app.ezpztac.data.TilesetFiles
import app.ezpztac.map.MapTokenSource
import app.ezpztac.network.ApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * What the 3D view's scene needs from the app: the point cloud's files, the ground's heights and the heights under the routes from the server, the
 * satellite imagery from Mapbox with the 2D map's token, the routes the map draws, the point size kept on the device, and the points it may hold.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object Viewer3DModule {
    /** The imagery's own disk cache, in the app's cache (the system may clear it): about a thousand of Mapbox's tiles. */
    private const val IMAGERY_CACHE_BYTES = 256L * 1024 * 1024

    /** The point clouds' own disk cache, in the app's cache (the system may clear it), cleared at sign-out. */
    private const val TILES_CACHE = "viewer3d-tiles"

    // One for the process: two over one directory would spoil each other.
    @Provides
    @Singleton
    fun tilesetCache(client: ApiClient, @ApplicationContext context: Context): CachedTilesetFiles =
        CachedTilesetFiles(ApiClientTilesetFiles(client), File(context.cacheDir, TILES_CACHE))

    @Provides
    fun tilesetFiles(cache: CachedTilesetFiles): TilesetFiles = cache

    @Provides
    fun tilesetCacheCleaner(cache: CachedTilesetFiles): TilesetCacheCleaner = cache

    @Provides
    fun terrainHeights(client: ApiClient): TerrainHeights = ApiClientTerrainHeights(client)

    @Provides
    fun groundUnder(client: ApiClient): GroundUnder = ApiClientGroundUnder(client)

    // The routes the 2D map draws: the open set's, whatever it is (a mission pack's included).
    @Provides
    fun shownRoutes(routes: RouteSession): ShownRoutes = ShownRoutes { routes.active.map { it?.routes.orEmpty() } }

    // One for the process: two caches over one directory would spoil each other.
    @Provides
    @Singleton
    fun imagery(http: OkHttpClient, tokens: MapTokenSource, @ApplicationContext context: Context): ImageryTiles =
        MapboxImagery(http.newBuilder().cache(Cache(File(context.cacheDir, "viewer3d-imagery"), IMAGERY_CACHE_BYTES)).build(), token = { tokens.token.value })

    @Provides
    fun pointSizes(impl: PointSizePreferences): PointSizeMemory = impl

    @Provides
    fun budgets(@ApplicationContext context: Context): SceneBudgets =
        SceneBudgets.forHeap(context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0)

    @Provides
    fun scenes(files: TilesetFiles, heights: TerrainHeights, imagery: ImageryTiles, budgets: SceneBudgets): SceneMaker =
        SceneMaker { target, core, ring, parent, pointSize ->
            SceneSession(target, core, ring, SceneSources(files, heights, imagery, BitmapDecoder), budgets, parent, pointSize)
        }
}
