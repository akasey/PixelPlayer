package com.theveloper.pixelplay.data.navidrome.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
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
 * accepts, and a reclaimed engine kills the listener while state still says `Up`). Recovery flows
 * through one primitive, [recover], invoked after [isHealthy] *measures* the tunnel as dead:
 *  - **listener probe** — a short loopback TCP connect detects "connection refused" (engine died);
 *  - **live handshake** — [WireGuardTunnel.stats]'s `lastHandshakeEpochSec` detects a blackholed
 *    UDP path (with keepalives flowing, a healthy peer re-handshakes ~every 2 min, so a handshake
 *    older than [HANDSHAKE_STALE_SECS] while `Up` means the link is dead even though the listener
 *    accepts). A tunnel that has *never* handshaked is live only during an initial
 *    [HANDSHAKE_GRACE_MS] window; past it, `rx == 0` while uploads climb is a failed-to-establish
 *    session, and a restart (which rebuilds the underlay UDP bind on the current default network)
 *    is exactly what a roamed connection needs, so it is retried rather than trusted.
 *
 * Every trigger (a failed proxy connect reported by OkHttp, a default-network change, or
 * [ensureReady] finding the tunnel unhealthy) only *schedules a verification*; nothing
 * blind-restarts, so a down Navidrome *server* — which also surfaces as proxy connect failures —
 * cannot flap a healthy tunnel. A default-network *handoff* additionally invalidates the engine's
 * underlay UDP bind (`bindInvalidatedAtMs`): the handshake is still recent enough to read
 * "healthy" while the old socket silently blackholes, so health is measured against the bind's
 * validity, and every recovery path then converges on the rebind restart. Restarts of an `Up`
 * tunnel are rate-limited by [RESTART_COOLDOWN_MS] so a flapping network can't churn them; a
 * cooldown-suppressed restart re-arms its own retry, so it is delayed, never lost.
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

    // All *AtMs fields below hold SystemClock.elapsedRealtime() stamps — monotonic, immune to the
    // NTP/timezone wall-clock jumps that tend to accompany exactly the network changes we react to.

    // When the last restart of an Up tunnel began; read/written only under restartMutex.
    private var lastRestartAtMs = 0L

    // When the current engine (re)started, i.e. when the current WG session got its chance to
    // complete a handshake. Bounds how long a never-handshaked tunnel is trusted before it is
    // treated as failed (tx rising, rx zero). Written under restartMutex, read on hot paths.
    @Volatile
    private var tunnelStartedAtMs = 0L

    // When the engine's underlay UDP bind was last invalidated (a default-network handoff). An
    // engine started before this instant is measured UNHEALTHY regardless of handshake freshness:
    // after a handoff the old socket silently blackholes while the last handshake still looks
    // recent, so freshness alone would read a dead link as fine. Routing the handoff through
    // health (instead of a special forced restart) means every recovery trigger — ensureReady,
    // scheduled verifications, proxy-connect failures — converges on the rebind, and the restart
    // cooldown can delay it but never lose it.
    @Volatile
    private var bindInvalidatedAtMs = 0L

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

    /** Up + underlay bind still valid + listener accepting + live (established, non-stale) session. */
    private suspend fun isHealthy(): Boolean {
        if (state.value !is TunnelState.Up) return false
        // Engine predates the last default-network handoff: its UDP socket is bound to a network
        // that no longer exists, so it must be measured dead even while the handshake is recent.
        if (tunnelStartedAtMs < bindInvalidatedAtMs) return false
        if (!isSocksAlive()) return false
        return isHandshakeLive()
    }

    /**
     * TCP-connect to the SOCKS port (cached for [PROBE_CACHE_MS]). Detects the "connection
     * refused" symptom: the localhost listener is present iff the connect succeeds. Never throws.
     */
    private suspend fun isSocksAlive(): Boolean {
        val address = (tunnel.socksProxy()?.address() as? InetSocketAddress) ?: return false
        val now = SystemClock.elapsedRealtime()
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
     * True when the peer has a live WireGuard session: it handshaked and that handshake is not
     * older than [HANDSHAKE_STALE_SECS] (an older one means the UDP path is blackholed — NAT
     * rebind, network handoff — even though the loopback listener still accepts).
     *
     * A tunnel that has *never* handshaked (`lastHandshakeEpochSec == 0`) is treated as live only
     * during the initial [HANDSHAKE_GRACE_MS] window after (re)start, while the first handshake is
     * still in flight. Past that window a tunnel with no handshake is NOT live — this is exactly
     * the "uploads climb, downloads stay zero" symptom (handshake initiations go out, nothing
     * comes back). Unlike a stale-but-once-established link, a restart *can* fix it: it rebuilds
     * the underlay UDP bind on the current default network, which is precisely what a roamed
     * connection needs. Unknown stats (a transient IpcGet failure) ⇒ assume live, so a hiccup in
     * reading counters never triggers a needless restart.
     */
    private fun isHandshakeLive(): Boolean {
        val lastHandshake = tunnel.stats()?.lastHandshakeEpochSec ?: return true
        if (lastHandshake <= 0L) {
            // Never handshaked: trusted only while the first handshake could still be completing.
            return SystemClock.elapsedRealtime() - tunnelStartedAtMs < HANDSHAKE_GRACE_MS
        }
        // Deliberately wall-clock: lastHandshake is a Unix timestamp from wireguard-go.
        val ageSecs = System.currentTimeMillis() / 1000 - lastHandshake
        return ageSecs <= HANDSHAKE_STALE_SECS
    }

    // ─── Recovery ──────────────────────────────────────────────────────

    /**
     * Serialized stop-if-needed + start. Queued callers collapse: once a predecessor has restored
     * health, followers return without touching the engine. Restarting an `Up` tunnel is
     * rate-limited by [RESTART_COOLDOWN_MS] so failure storms can't flap it — but a suppressed
     * restart re-arms a verification at cooldown expiry, so recovery is delayed, never dropped
     * (without this, a handoff landing inside the cooldown would leave the tunnel dead until the
     * handshake aged past [HANDSHAKE_STALE_SECS], because the fresh-looking handshake would pass
     * every later health check).
     *
     * @return false only when no start could be attempted because no valid config is stored.
     */
    private suspend fun recover(reason: String): Boolean = restartMutex.withLock {
        if (!enabled) return@withLock true
        // Collapse queued recoveries: predecessor already fixed it. (A network handoff cannot be
        // masked here: it bumps bindInvalidatedAtMs, which makes isHealthy() false until restart.)
        if (isHealthy()) return@withLock true

        val now = SystemClock.elapsedRealtime()
        if (state.value is TunnelState.Up) {
            val sinceLastRestart = now - lastRestartAtMs
            if (sinceLastRestart < RESTART_COOLDOWN_MS) {
                Timber.d("recover(%s): within cooldown, retrying after it expires", reason)
                scheduleVerify(reason, delayMs = RESTART_COOLDOWN_MS - sinceLastRestart)
                return@withLock true
            }
            Timber.i("recover(%s): restarting tunnel", reason)
            lastRestartAtMs = now
            tunnel.stop()
        }

        val config = configStore.parsedConfig()
        if (config == null) {
            Timber.w("WireGuard enabled but no valid config stored; staying direct")
            return@withLock false
        }
        lastProbeOkAtMs = 0L // the SOCKS port changes across restarts; drop the cached probe
        tunnel.start(config)
        tunnelStartedAtMs = SystemClock.elapsedRealtime()
        true
    }

    /**
     * Schedule a debounced health verification. Coalesces without resetting: while one is pending,
     * further triggers are dropped (a cancel-and-rearm debounce could be starved forever by a
     * failure storm arriving faster than the delay). The verification restarts only if
     * [isHealthy] fails at fire time.
     */
    @Synchronized
    private fun scheduleVerify(reason: String, delayMs: Long = VERIFY_SETTLE_MS) {
        if (!enabled || !tunnel.isSupported) return
        if (verifyJob?.isActive == true) return
        verifyJob = appScope.launch {
            delay(delayMs)
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
                // Any different handle after the first is a handoff (make-before-break delivers
                // it directly; break-before-make still compares against the retained old handle).
                // A handoff invalidates the engine's underlay UDP bind: mark it so isHealthy()
                // measures the pre-handoff engine as dead — after a handoff the last handshake is
                // still recent enough to read "healthy" while the socket silently blackholes
                // (uploads climb, downloads stay zero) — then verify, which restarts and rebinds
                // on the new network. The restart cooldown keeps a flapping network in check, and
                // a cooldown-suppressed restart re-arms itself (see recover()).
                if (previous != null && previous != handle) {
                    bindInvalidatedAtMs = SystemClock.elapsedRealtime()
                    scheduleVerify("default network changed")
                } else if (previous == null) {
                    // First network after registration. Usually the enable path has already
                    // started the tunnel, but when the feature was enabled while offline that
                    // start failed — verify (cheap no-op when healthy) so connectivity arriving
                    // later brings the tunnel up without waiting for the next request.
                    scheduleVerify("first network available")
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
        /**
         * How long a freshly (re)started tunnel is trusted before its first handshake must have
         * landed. WireGuard's handshake is a single round-trip; a few seconds covers a slow mobile
         * RTT and retransmits (REKEY_TIMEOUT = 5s). Past this with `rx == 0` the session failed to
         * establish and a rebind restart is warranted.
         */
        private const val HANDSHAKE_GRACE_MS = 15_000L
    }
}
