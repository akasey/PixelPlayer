package com.theveloper.pixelplay.data.navidrome.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback [WireGuardTunnel] used when the wireguard-go + netstack AAR is not bundled
 * (i.e. `pixelplay.enableWireguard` builds without `app/libs/wireguard-netstack.aar`).
 *
 * It never establishes a tunnel and always reports an error, so callers transparently fall
 * back to direct connections.
 */
@Singleton
class NoOpWireGuardTunnel @Inject constructor() : WireGuardTunnel {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    override val state: StateFlow<TunnelState> = _state

    override val isSupported: Boolean = false

    override suspend fun start(config: WireGuardConfig) {
        Timber.w("WireGuard tunnel requested but no userspace engine is bundled in this build.")
        _state.value = TunnelState.Error("WireGuard engine not available in this build")
    }

    override suspend fun stop() {
        _state.value = TunnelState.Down
    }

    override fun socksProxy(): Proxy? = null

    override fun stats(): WireGuardStats? = null
}
