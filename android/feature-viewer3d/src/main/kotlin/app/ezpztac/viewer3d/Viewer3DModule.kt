package app.ezpztac.viewer3d

import android.app.ActivityManager
import android.content.Context
import app.ezpztac.data.ApiClientTilesetFiles
import app.ezpztac.data.TilesetFiles
import app.ezpztac.network.ApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** What the 3D view's scene needs from the app: the tilesets' files from the server, the point size kept on the device, and the points it may hold. */
@Module
@InstallIn(SingletonComponent::class)
internal object Viewer3DModule {
    @Provides
    fun tilesetFiles(client: ApiClient): TilesetFiles = ApiClientTilesetFiles(client)

    @Provides
    fun pointSizes(impl: PointSizePreferences): PointSizeMemory = impl

    @Provides
    fun budgets(@ApplicationContext context: Context): SceneBudgets =
        SceneBudgets.forHeap(context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0)

    @Provides
    fun scenes(files: TilesetFiles, budgets: SceneBudgets): SceneMaker =
        SceneMaker { target, core, ring, parent, pointSize -> SceneSession(target, core, ring, files, budgets, parent, pointSize) }
}
