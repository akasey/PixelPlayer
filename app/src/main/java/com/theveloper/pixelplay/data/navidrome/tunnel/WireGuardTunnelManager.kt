package com.theveloper.pixelplay.data.navidrome.tunnel

import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the userspace WireGuard tunnel used to reach a private Navidrome/Subsonic server.
 *
 * Consulted by the `@NavidromeOkHttpClient` proxy selector for the live SOCKS proxy, and by the
 * Navidrome API/stream paths via [ensureReady] so the tunnel is up before traffic is sent.
 *
 * Auto-starts/stops by observing [UserPreferencesRepository.navidromeTunnelEnabledFlow]. Has no
 * dependency on the Navidrome API graph, avoiding a Hilt cycle (OkHttp → manager → ... → OkHttp).
 */
@Singleton
class WireGuardTunnelManager @Inject constructor(
    private val tunnel: WireGuardTunnel,
    private val configStore: WireGuardConfigStore,
    private val userPreferencesRepository: UserPreferencesRepository,
    @AppScope private val appScope: CoroutineScope,
) {
    val state: StateFlow<TunnelState> get() = tunnel.state

    /** True only when a real userspace WireGuard engine is bundled in this build. */
    val isSupported: Boolean get() = tunnel.isSupported

    @Volatile
    private var enabled = false

    init {
        appScope.launch {
            userPreferencesRepository.navidromeTunnelEnabledFlow
                .distinctUntilChanged()
                .collect { on ->
                    enabled = on
                    if (on) startInternal() else tunnel.stop()
                }
        }
    }

    /** The live SOCKS5 proxy when the tunnel is up, else null (caller goes direct). */
    fun socksProxy(): Proxy? = tunnel.socksProxy()

    /**
     * Ensure the tunnel is up before a Navidrome request, when enabled.
     *
     * @return true if not enabled (direct is fine) or the tunnel is up; false if it failed to
     *         come up within [timeoutMs].
     */
    suspend fun ensureReady(timeoutMs: Long = 8_000L): Boolean {
        if (!enabled) return true
        if (state.value is TunnelState.Up) return true
        startInternal()
        val terminal = withTimeoutOrNull(timeoutMs) {
            state.first { it is TunnelState.Up || it is TunnelState.Error }
        }
        return terminal is TunnelState.Up
    }

    private suspend fun startInternal() {
        if (state.value is TunnelState.Up) return
        val config = configStore.parsedConfig()
        if (config == null) {
            Timber.w("WireGuard enabled but no valid config stored; staying direct")
            return
        }
        tunnel.start(config)
    }
}
