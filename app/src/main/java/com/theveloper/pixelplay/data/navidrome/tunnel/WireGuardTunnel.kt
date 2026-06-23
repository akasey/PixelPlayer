package com.theveloper.pixelplay.data.navidrome.tunnel

import kotlinx.coroutines.flow.StateFlow
import java.net.Proxy

/** Lifecycle state of the userspace WireGuard tunnel. */
sealed interface TunnelState {
    data object Down : TunnelState
    data object Connecting : TunnelState
    /** Tunnel up; [socksPort] is the localhost SOCKS5 port carrying traffic. */
    data class Up(val socksPort: Int) : TunnelState
    data class Error(val message: String) : TunnelState
}

/**
 * Application-layer (userspace) WireGuard tunnel.
 *
 * Implementations run WireGuard entirely in-process (no `VpnService`, no `tun` interface) and
 * expose a localhost SOCKS5 proxy that callers route TCP through. See
 * [NetstackWireGuardTunnel] (backed by the wireguard-go + netstack AAR) and [NoOpWireGuardTunnel].
 */
interface WireGuardTunnel {
    val state: StateFlow<TunnelState>

    /** Bring the tunnel up for [config]. Suspends until connected or failed. Idempotent. */
    suspend fun start(config: WireGuardConfig)

    /** Tear the tunnel down. Idempotent. */
    suspend fun stop()

    /** The SOCKS5 proxy carrying tunneled traffic, or null when the tunnel is not up. */
    fun socksProxy(): Proxy?

    /** Whether a real userspace WireGuard engine is available in this build. */
    val isSupported: Boolean
}
