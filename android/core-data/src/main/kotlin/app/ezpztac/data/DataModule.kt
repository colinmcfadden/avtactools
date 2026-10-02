package app.ezpztac.data

import android.content.Context
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The one database, and what is built on it: the sync store and the repository the screens edit records through. */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EzpzDatabase = EzpzDatabase.open(context)

    @Provides
    @Singleton
    fun roomStore(database: EzpzDatabase): RoomSyncStore = RoomSyncStore(database)

    @Provides
    @Singleton
    fun syncStore(store: RoomSyncStore): SyncStore = store

    @Provides
    @Singleton
    fun repository(store: SyncStore): SyncRepository = SyncRepository(store)
}
