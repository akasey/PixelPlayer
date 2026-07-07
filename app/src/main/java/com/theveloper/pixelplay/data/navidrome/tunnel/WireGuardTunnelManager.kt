package com.theveloper.pixelplay.data.navidrome.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the userspace WireGuard tunnel used to reach a private Navidrome/Subsonic server.
 *
 * Consulted by the `@NavidromeOkHttpClient` proxy selector via [requiredProxy] (fail-closed: while
 * the tunnel is enabled, Navidrome traffic is never silently sent off-tunnel), and by the Navidrome
 * API/stream paths via [ensureReady] so the tunnel is up before traffic is sent.
 *
 * Auto-starts/stops by observing [UserPreferencesRepository.navidromeTunnelEnabledFlow]. Has no
 * dependency on the Navidrome API graph, avoiding a Hilt cycle (OkHttp → manager → ... → OkHttp).
 *
 * ### Roaming resilience — verified recovery
 * [TunnelState] cannot observe its own engine dying: the netstack SOCKS listener and the WireGuard
 * UDP link fail independently (a NAT rebind can blackhole UDP while the loopback listener still
 * accepts, and a reclaimed engine kills the listener while state still says `Up`). All recovery
 * therefore flows through one primitive, [recover], which is only invoked after [isHealthy]
 * *measures* the tunnel as dead:
 *  - **listener probe** — a short loopback TCP connect detects "connection refused" (engine died);
 *  - **handshake freshness** — [WireGuardTunnel.stats]'s `lastHandshakeEpochSec` detects a
 *    blackholed UDP path (with keepalives flowing, a healthy peer re-handshakes ~every 2 min, so a
 *    handshake older than [HANDSHAKE_STALE_SECS] while `Up` means the link is dead even though the
 *    listener accepts). A tunnel that has *never* handshaked is deliberately not restarted — that
 *    is a config/endpoint problem a restart cannot fix, and restarting would just churn.
 *
 * Triggers (default-network change, a failed proxy connect reported by OkHttp, or [ensureReady]
 * finding the tunnel unhealthy) only *schedule a verification*; they never blind-restart, so a
 * down Navidrome *server* — which also surfaces as proxy connect failures — cannot flap a healthy
 * tunnel. Restarts of an `Up` tunnel are additionally rate-limited by [RESTART_COOLDOWN_MS].
 *
 * The manager keeps its own default-network callback (rather than reusing ConnectivityStateHolder)
 * because it needs the network *identity* to detect handoffs, not just an online/offline boolean,
 * and it must work while the UI-driven holder is not initialized.
 */
@Singleton
class WireGuardTunnelManager @Inject constructor(
    private val tunnel: WireGuardTunnel,
    private val configStore: WireGuardConfigStore,
    private val userPreferencesRepository: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
) {
    val state: StateFlow<TunnelState> get() = tunnel.state

    /** True only when a real userspace WireGuard engine is bundled in this build. */
    val isSupported: Boolean get() = tunnel.isSupported

    @Volatile
    private var enabled = false

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Serializes every tunnel start/stop (enable, disable, recover) so overlapping triggers
    // can't interleave a stop into a fresh start. Lock order is always manager → tunnel.
    private val restartMutex = Mutex()

    // Guarded by @Synchronized: written from the ConnectivityManager callback thread, OkHttp
    // connection threads (via onProxyConnectFailed) and appScope coroutines.
    private var verifyJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Wall-clock ms of the last restart of an Up tunnel; read/written only under restartMutex.
    private var lastRestartAtMs = 0L

    // Timestamp of the last successful SOCKS probe; lets steady-state ensureReady calls skip
    // the socket connect. Reset on every restart (the port changes).
    @Volatile
    private var lastProbeOkAtMs = 0L

    // networkHandle of the last default network seen. Deliberately NOT cleared in onLost: in a
    // break-before-make handoff (WiFi fully drops before cellular attaches) the stale handle is
    // exactly what lets the next onAvailable be recognized as a handoff.
    @Volatile
    private var lastNetworkHandle: Long? = null

    /**
     * SOCKS address guaranteed to refuse connections (loopback port 1 is root-only, never bound).
     * Returned by [requiredProxy] while the tunnel is enabled but not up, so requests fail fast
     * and on-device instead of leaking off-tunnel; the resulting connectFailed then schedules a
     * verification, driving recovery.
     */
    private val failClosedProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1))

    init {
        appScope.launch {
            userPreferencesRepository.navidromeTunnelEnabledFlow
                .distinctUntilChanged()
                .collect { on ->
                    enabled = on
                    if (on) {
                        registerNetworkCallback()
                        recover("tunnel enabled")
                    } else {
                        unregisterNetworkCallback()
                        // Under the mutex so an in-flight recover() cannot resurrect the engine
                        // after this stop; recover() re-checks `enabled` once it gets the lock.
                        restartMutex.withLock { tunnel.stop() }
                    }
                }
        }
    }

    /** The live SOCKS5 proxy when the tunnel is up, else null. */
    fun socksProxy(): Proxy? = tunnel.socksProxy()

    /**
     * Proxy the Navidrome OkHttp client must use right now. Fail-closed:
     *  - tunnel disabled → null (direct is the user's choice);
     *  - tunnel up → the live SOCKS proxy;
     *  - tunnel enabled but down/starting → [failClosedProxy], so traffic (Subsonic auth tokens
     *    ride in query params) is never silently sent off-tunnel during a restart window.
     */
    fun requiredProxy(): Proxy? {
        if (!enabled) return null
        return tunnel.socksProxy() ?: failClosedProxy
    }

    /** Current transport counters, or null when the tunnel is not up. */
    fun stats(): WireGuardStats? = tunnel.stats()

    /**
     * Ensure the tunnel is up *and measured healthy* before a Navidrome request, when enabled.
     *
     * @return true if not enabled (direct is fine) or the tunnel is up and healthy; false if it
     *         failed to come up within [timeoutMs] (or immediately, when no config is stored).
     */
    suspend fun ensureReady(timeoutMs: Long = 8_000L): Boolean {
        if (!enabled) return true
        if (isHealthy()) return true
        // Unhealthy (dead listener, stale handshake) or simply not started yet — recover() sorts
        // out which under the mutex. A false return means no start was even attempted (no config):
        // fail fast instead of burning the timeout waiting for a state change that cannot come.
        if (!recover("ensureReady: tunnel not healthy")) return false
        val terminal = withTimeoutOrNull(timeoutMs) {
            state.first { it is TunnelState.Up || it is TunnelState.Error }
        }
        return terminal is TunnelState.Up
    }

    /**
     * Recovery hook for the `@NavidromeOkHttpClient` proxy selector. A failed proxy connect is
     * ambiguous — it fires for a dead localhost listener but *also* when the SOCKS server relays
     * an upstream failure (Navidrome server down through a healthy tunnel) — so this only
     * schedules a health verification; [isHealthy] decides whether a restart is warranted.
     * Safe to call from any thread.
     */
    fun onProxyConnectFailed() {
        scheduleVerify("proxy connect failed")
    }

    // ─── Health ────────────────────────────────────────────────────────

    /** Up + listener accepting + handshake not stale. */
    private suspend fun isHealthy(): Boolean {
        if (state.value !is TunnelState.Up) return false
        if (!isSocksAlive()) return false
        return !isHandshakeStale()
    }

    /**
     * TCP-connect to the SOCKS port (cached for [PROBE_CACHE_MS]). Detects the "connection
     * refused" symptom: the localhost listener is present iff the connect succeeds. Never throws.
     */
    private suspend fun isSocksAlive(): Boolean {
        val address = (tunnel.socksProxy()?.address() as? InetSocketAddress) ?: return false
        val now = System.currentTimeMillis()
        if (now - lastProbeOkAtMs < PROBE_CACHE_MS) return true
        val alive = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address.address, address.port), SOCKS_PROBE_TIMEOUT_MS)
                }
                true
            }.getOrDefault(false)
        }
        if (alive) lastProbeOkAtMs = now
        return alive
    }

    /**
     * True when the peer handshake is older than [HANDSHAKE_STALE_SECS] — the UDP path is
     * blackholed (NAT rebind, network handoff) even though the loopback listener still accepts.
     * A tunnel that never handshaked (`lastHandshakeEpochSec == 0`) is NOT considered stale:
     * that's a config/endpoint problem a restart cannot fix. Unknown stats ⇒ assume fresh.
     */
    private fun isHandshakeStale(): Boolean {
        val lastHandshake = tunnel.stats()?.lastHandshakeEpochSec ?: return false
        if (lastHandshake <= 0L) return false
        val ageSecs = System.currentTimeMillis() / 1000 - lastHandshake
        return ageSecs > HANDSHAKE_STALE_SECS
    }

    // ─── Recovery ──────────────────────────────────────────────────────

    /**
     * Serialized stop-if-needed + start. Queued callers collapse: once a predecessor has restored
     * health, followers return without touching the engine. Restarting an `Up` tunnel is
     * rate-limited by [RESTART_COOLDOWN_MS] so failure storms can't flap it.
     *
     * @return false only when no start could be attempted because no valid config is stored.
     */
    private suspend fun recover(reason: String): Boolean = restartMutex.withLock {
        if (!enabled) return@withLock true
        // Collapse queued recoveries: predecessor already fixed it.
        if (isHealthy()) return@withLock true

        val now = System.currentTimeMillis()
        if (state.value is TunnelState.Up) {
            if (now - lastRestartAtMs < RESTART_COOLDOWN_MS) {
                Timber.d("recover(%s): within cooldown, skipping restart", reason)
                return@withLock true
            }
            Timber.i("recover(%s): restarting tunnel", reason)
            lastRestartAtMs = now
            tunnel.stop()
            lastProbeOkAtMs = 0L
        }

        val config = configStore.parsedConfig()
        if (config == null) {
            Timber.w("WireGuard enabled but no valid config stored; staying direct")
            return@withLock false
        }
        tunnel.start(config)
        true
    }

    /**
     * Schedule a debounced health verification. Coalesces without resetting: while one is pending,
     * further triggers are dropped (a cancel-and-rearm debounce could be starved forever by a
     * failure storm arriving faster than the delay). The verification restarts only if
     * [isHealthy] fails at fire time.
     */
    @Synchronized
    private fun scheduleVerify(reason: String) {
        if (!enabled || !tunnel.isSupported) return
        if (verifyJob?.isActive == true) return
        verifyJob = appScope.launch {
            delay(VERIFY_SETTLE_MS)
            if (!enabled) return@launch
            if (isHealthy()) {
                Timber.d("verify(%s): tunnel healthy, no restart", reason)
                return@launch
            }
            recover(reason)
        }
    }

    @Synchronized
    private fun cancelPendingVerify() {
        verifyJob?.cancel()
        verifyJob = null
    }

    // ─── Network-change awareness ──────────────────────────────────────

    private fun registerNetworkCallback() {
        if (!tunnel.isSupported || networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val previous = lastNetworkHandle
                lastNetworkHandle = handle
                // First network after registration: the enable path already starts the tunnel.
                // Any different handle afterwards is a handoff (make-before-break delivers it
                // directly; break-before-make still compares against the retained old handle).
                if (previous != null && previous != handle) {
                    scheduleVerify("default network changed")
                }
            }
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Timber.w(it, "Failed to register tunnel network callback") }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        lastNetworkHandle = null
        cancelPendingVerify()
    }

    private companion object {
        private const val SOCKS_PROBE_TIMEOUT_MS = 600
        /** A probe that succeeded this recently is trusted without re-connecting. */
        private const val PROBE_CACHE_MS = 3_000L
        /** Settle delay before a scheduled verification fires (coalesces trigger bursts). */
        private const val VERIFY_SETTLE_MS = 750L
        /** Minimum interval between restarts of an Up tunnel. */
        private const val RESTART_COOLDOWN_MS = 20_000L
        /**
         * With keepalives flowing a healthy peer re-handshakes at least every ~2 min
         * (REKEY_AFTER_TIME = 120s); 3 min of silence while Up means the link is dead.
         */
        private const val HANDSHAKE_STALE_SECS = 180L
    }
}
