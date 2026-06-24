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
 * Live WireGuard transport counters for the peer.
 *
 * @param lastHandshakeEpochSec Unix seconds of the last successful handshake, or 0 if none yet.
 * @param rxBytes Total bytes received from the peer (download).
 * @param txBytes Total bytes sent to the peer (upload).
 */
data class WireGuardStats(
    val lastHandshakeEpochSec: Long,
    val rxBytes: Long,
    val txBytes: Long,
) {
    companion object {
        /** Parse the secret-free UAPI subset returned by the native `Stats()`. */
        fun parse(uapi: String): WireGuardStats {
            var handshake = 0L
            var rx = 0L
            var tx = 0L
            uapi.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx)
                val value = line.substring(idx + 1).trim().toLongOrNull() ?: return@forEach
                when (key) {
                    "last_handshake_time_sec" -> handshake = value
                    "rx_bytes" -> rx = value
                    "tx_bytes" -> tx = value
                }
            }
            return WireGuardStats(handshake, rx, tx)
        }
    }
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

    /** Current transport counters, or null when the tunnel is not up / unsupported. */
    fun stats(): WireGuardStats?

    /** Whether a real userspace WireGuard engine is available in this build. */
    val isSupported: Boolean
}
