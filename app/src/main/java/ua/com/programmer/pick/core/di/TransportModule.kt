package ua.com.programmer.pick.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ua.com.programmer.pick.data.remote.transport.RoutingTransport
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import javax.inject.Singleton

/**
 * Binds the device transport. [RoutingTransport] is the seam the orchestrator
 * and repositories see; it delegates to the real REST transport or the offline
 * demo transport based on the persisted demo-mode flag.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @Singleton
    abstract fun bindSyncTransport(routingTransport: RoutingTransport): SyncTransport
}
