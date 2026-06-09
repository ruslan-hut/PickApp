package ua.com.programmer.pick.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.websocket.RestTransport
import ua.com.programmer.pick.data.remote.websocket.SyncTransport
import ua.com.programmer.pick.data.remote.websocket.WebSocketManager
import javax.inject.Singleton

/**
 * Binds the [SyncTransport] implementation chosen by the `transport` flag
 * (AppPreferences.transportMode). Resolved once per process (Singleton), so a
 * flag change takes effect on next app start — appropriate for a rollout
 * toggle. Defaults to the WebSocket transport so existing installs are
 * unaffected until explicitly opted into REST.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideSyncTransport(
        appPreferences: AppPreferences,
        webSocketManager: WebSocketManager,
        restTransport: RestTransport,
    ): SyncTransport = when (appPreferences.getTransportModeSync()) {
        Constants.Transport.REST -> restTransport
        else -> webSocketManager
    }
}
