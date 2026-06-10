package ua.com.programmer.pick.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ua.com.programmer.pick.data.remote.websocket.RestTransport
import ua.com.programmer.pick.data.remote.websocket.SyncTransport
import javax.inject.Singleton

/**
 * Binds the device transport. The app speaks REST only — the WebSocket
 * transport was removed at cutover — so [RestTransport] is the sole
 * [SyncTransport]. The interface is kept as the orchestrator/repository seam.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideSyncTransport(restTransport: RestTransport): SyncTransport = restTransport
}
