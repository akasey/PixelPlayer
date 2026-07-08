package com.theveloper.pixelplay.data.navidrome.tunnel

import android.os.SystemClock
import com.theveloper.pixelplay.data.network.navidrome.NavidromeApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a single diagnostic probe. */
enum class ProbeState { PENDING, RUNNING, OK, FAIL, SKIPPED }

/**
 * One row in the diagnostics report — a named check with its current [state] and a short
 * human-readable [detail] (latency once done, or the failure reason).
 */
data class DiagnosticProbe(
    val id: String,
    val label: String,
    val state: ProbeState = ProbeState.PENDING,
    val detail: String? = null,
)

/** How the overall diagnosis should be surfaced (drives the summary colour). */
enum class DiagnosisSeverity { OK, WARN, ERROR, INFO }

/**
 * A snapshot of a diagnostics run. Emitted repeatedly while the run progresses so the UI can show
 * each probe flipping from RUNNING to OK/FAIL live; the final emission carries [diagnosis].
 */
data class DiagnosticsReport(
    val probes: List<DiagnosticProbe>,
    val inProgress: Boolean,
    val diagnosis: String? = null,
    val severity: DiagnosisSeverity = DiagnosisSeverity.INFO,
)

/**
 * Runs a layered connectivity diagnosis for the WireGuard tunnel and the Navidrome server behind it.
 *
 * The point is to separate *tunnel* problems from *server* problems. A plain "ping the server"
 * test conflates them: a failure could mean the tunnel never came up, the tunnel is up but not
 * forwarding, or the tunnel is perfect and only the Navidrome server is down. This runner probes
 * each layer independently:
 *
 *  1. **Tunnel up** — bring the tunnel up (when enabled) and confirm the SOCKS listener is live.
 *  2. **Peer handshake** — confirm a recent WireGuard handshake, proving the encrypted UDP path to
 *     the peer actually works (not just that the local listener bound).
 *  3. **Internet via tunnel** — send real HTTPS requests to a handful of well-known public hosts
 *     *through the tunnel's SOCKS proxy* (remote DNS included), proving the tunnel forwards general
 *     traffic. Skipped when the config's `AllowedIPs` is split-tunnel (public hosts aren't routed).
 *  4. **Navidrome server** — ping the configured server through the tunnel.
 *
 * The synthesized [DiagnosticsReport.diagnosis] then tells the user which layer is at fault — most
 * importantly, whether "everything but the server works", which points the finger at the server.
 */
@Singleton
class TunnelDiagnostics @Inject constructor(
    private val tunnelManager: WireGuardTunnelManager,
    private val configStore: WireGuardConfigStore,
    private val api: NavidromeApiService,
    private val baseClient: OkHttpClient,
) {

    /** A few lightweight, highly-available endpoints. Any HTTP response proves reachability. */
    private val internetTargets = listOf(
        "Google" to "https://connectivitycheck.gstatic.com/generate_204",
        "Cloudflare" to "https://cloudflare.com/cdn-cgi/trace",
        "Wikipedia" to "https://en.wikipedia.org/",
    )

    /**
     * Run the full diagnosis, emitting a fresh [DiagnosticsReport] after every state change. The
     * terminal emission has `inProgress = false` and a populated diagnosis.
     */
    fun run(): Flow<DiagnosticsReport> = flow {
        val config = configStore.parsedConfig()
        val routesAllTraffic = config == null ||
            config.allowedIps.isEmpty() ||
            config.allowedIps.any { it.trim() == "0.0.0.0/0" }

        val internetProbes = internetTargets.map { (name, _) ->
            DiagnosticProbe("net:$name", "Reach $name")
        }
        val probes = linkedMapOf<String, DiagnosticProbe>()
        probes["tunnel"] = DiagnosticProbe("tunnel", "WireGuard tunnel")
        probes["handshake"] = DiagnosticProbe("handshake", "Peer handshake")
        internetProbes.forEach { probes[it.id] = it }
        probes["server"] = DiagnosticProbe("server", "Navidrome server")

        suspend fun emitState(severity: DiagnosisSeverity = DiagnosisSeverity.INFO, diagnosis: String? = null) {
            emit(
                DiagnosticsReport(
                    probes = probes.values.toList(),
                    inProgress = diagnosis == null,
                    diagnosis = diagnosis,
                    severity = severity,
                )
            )
        }

        suspend fun update(id: String, state: ProbeState, detail: String? = null) {
            probes[id]?.let { probes[id] = it.copy(state = state, detail = detail) }
            emitState()
        }

        emitState()

        // ── 1. Tunnel up ───────────────────────────────────────────────
        if (!tunnelManager.isSupported) {
            update("tunnel", ProbeState.FAIL, "WireGuard engine not in this build")
            skipRemaining(probes, listOf("handshake", "server") + internetProbes.map { it.id })
            emitState(
                DiagnosisSeverity.ERROR,
                "This build does not include the WireGuard engine, so the tunnel can't run."
            )
            return@flow
        }
        if (config == null) {
            update("tunnel", ProbeState.FAIL, "No valid config imported")
            skipRemaining(probes, listOf("handshake", "server") + internetProbes.map { it.id })
            emitState(DiagnosisSeverity.ERROR, "No WireGuard config is imported. Upload a .conf first.")
            return@flow
        }

        update("tunnel", ProbeState.RUNNING)
        val ready = tunnelManager.ensureReady(timeoutMs = 12_000L)
        val socksProxy = tunnelManager.socksProxy()

        if (socksProxy == null) {
            // ensureReady returns true immediately when the tunnel toggle is off (direct is fine),
            // so a null proxy here means either the feature is disabled or the tunnel never came up.
            val disabled = ready // true == not enabled; false == enabled but failed to come up
            if (disabled) {
                update("tunnel", ProbeState.SKIPPED, "Tunnel is turned off")
                update("handshake", ProbeState.SKIPPED, "Tunnel is off")
                internetProbes.forEach { update(it.id, ProbeState.SKIPPED, "Tunnel is off") }
                // Still probe the server (goes direct while the tunnel is off) so the user learns
                // whether the server is reachable at all.
                val server = probeServer()
                update("server", server.state, server.detail)
                val (sev, msg) = if (server.state == ProbeState.OK) {
                    DiagnosisSeverity.INFO to
                        "The tunnel is turned off. The server is reachable directly. Enable the " +
                        "tunnel to diagnose it."
                } else {
                    DiagnosisSeverity.WARN to
                        "The tunnel is turned off and the server isn't reachable directly. Enable " +
                        "the tunnel (it exists to reach a private server) and run this again."
                }
                emitState(sev, msg)
                return@flow
            }
            update(
                "tunnel", ProbeState.FAIL,
                (tunnelManager.state.value as? TunnelState.Error)?.message ?: "Tunnel failed to start"
            )
            skipRemaining(probes, listOf("handshake", "server") + internetProbes.map { it.id })
            emitState(
                DiagnosisSeverity.ERROR,
                "The tunnel could not start. Check the .conf endpoint, keys, and that the server " +
                    "is reachable on the underlying network."
            )
            return@flow
        }

        val socksPort = (tunnelManager.state.value as? TunnelState.Up)?.socksPort
        update("tunnel", ProbeState.OK, socksPort?.let { "SOCKS on 127.0.0.1:$it" })

        // ── 2. Peer handshake ──────────────────────────────────────────
        update("handshake", ProbeState.RUNNING)
        val handshakeOk = waitForHandshake()
        if (handshakeOk) {
            val age = handshakeAgeSecs()
            update("handshake", ProbeState.OK, age?.let { "last handshake ${it}s ago" })
        } else {
            update("handshake", ProbeState.FAIL, "no handshake with peer")
            internetProbes.forEach { update(it.id, ProbeState.SKIPPED, "no handshake") }
            update("server", ProbeState.SKIPPED, "no handshake")
            emitState(
                DiagnosisSeverity.ERROR,
                "The tunnel started but never completed a WireGuard handshake with the peer " +
                    "(${config.endpoint}). The endpoint isn't answering — check the endpoint " +
                    "host/port and keys, and that WireGuard is running on the server."
            )
            return@flow
        }

        // ── 3. Internet via tunnel ─────────────────────────────────────
        var internetTested = false
        var internetOk = false
        if (!routesAllTraffic) {
            internetProbes.forEach {
                update(it.id, ProbeState.SKIPPED, "AllowedIPs is split-tunnel")
            }
        } else {
            val probeClient = buildProbeClient(socksProxy)
            for ((name, url) in internetTargets) {
                internetTested = true
                val id = "net:$name"
                update(id, ProbeState.RUNNING)
                val result = httpProbe(probeClient, url)
                result.fold(
                    onSuccess = { latency ->
                        internetOk = true
                        update(id, ProbeState.OK, "${latency}ms")
                    },
                    onFailure = { update(id, ProbeState.FAIL, it.shortReason()) }
                )
            }
        }

        // ── 4. Navidrome server ────────────────────────────────────────
        update("server", ProbeState.RUNNING)
        val server = probeServer()
        update("server", server.state, server.detail)

        // ── Diagnosis ──────────────────────────────────────────────────
        val (severity, diagnosis) = diagnose(
            serverState = server.state,
            internetTested = internetTested,
            internetOk = internetOk,
            routesAllTraffic = routesAllTraffic,
            allowedIps = config.allowedIps,
        )
        emitState(severity, diagnosis)
    }

    private fun diagnose(
        serverState: ProbeState,
        internetTested: Boolean,
        internetOk: Boolean,
        routesAllTraffic: Boolean,
        allowedIps: List<String>,
    ): Pair<DiagnosisSeverity, String> {
        // At this point the tunnel is up and handshaking — the underlay works.
        if (serverState == ProbeState.OK) {
            return DiagnosisSeverity.OK to
                "Tunnel and server are both reachable. Everything looks healthy."
        }
        if (serverState == ProbeState.SKIPPED) {
            return DiagnosisSeverity.INFO to
                "The tunnel is up and handshaking. Sign in to a server to test it."
        }
        // Server failed while the tunnel is healthy.
        if (internetTested && internetOk) {
            return DiagnosisSeverity.WARN to
                "The tunnel is healthy and carrying traffic to the internet, but the Navidrome " +
                "server didn't respond. The problem is the server, not the tunnel — check the " +
                "server URL, that it's running, and your credentials."
        }
        if (routesAllTraffic) {
            return DiagnosisSeverity.ERROR to
                "The tunnel handshakes, but no traffic is getting through it — neither public " +
                "sites nor your server responded. This points to a routing, DNS, or MTU problem " +
                "on the tunnel or peer, not the server alone."
        }
        return DiagnosisSeverity.WARN to
            "The tunnel handshakes. Public-site checks were skipped because AllowedIPs only routes " +
            "${allowedIps.joinToString(", ")}. Your server still didn't respond — check that its " +
            "address is inside AllowedIPs and that it's running."
    }

    /** Probe the configured Navidrome server through the tunnel-aware client, timing the call. */
    private suspend fun probeServer(): DiagnosticProbe {
        if (!api.hasCredentials()) {
            return DiagnosticProbe("server", "Navidrome server", ProbeState.SKIPPED, "Not signed in")
        }
        val start = SystemClock.elapsedRealtime()
        return api.ping().fold(
            onSuccess = {
                val ms = SystemClock.elapsedRealtime() - start
                DiagnosticProbe("server", "Navidrome server", ProbeState.OK, "${ms}ms")
            },
            onFailure = {
                DiagnosticProbe("server", "Navidrome server", ProbeState.FAIL, it.shortReason())
            }
        )
    }

    /** Wait briefly for the first WireGuard handshake to land after bring-up. */
    private suspend fun waitForHandshake(): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val hs = tunnelManager.stats()?.lastHandshakeEpochSec ?: 0L
            if (hs > 0L) return@withContext true
            kotlinx.coroutines.delay(500L)
        }
        (tunnelManager.stats()?.lastHandshakeEpochSec ?: 0L) > 0L
    }

    private fun handshakeAgeSecs(): Long? {
        val hs = tunnelManager.stats()?.lastHandshakeEpochSec ?: 0L
        if (hs <= 0L) return null
        return (System.currentTimeMillis() / 1000L - hs).coerceAtLeast(0L)
    }

    /** A short-timeout client that sends every request through [socksProxy] with remote DNS. */
    private fun buildProbeClient(socksProxy: Proxy): OkHttpClient =
        baseClient.newBuilder()
            .proxy(socksProxy)
            .retryOnConnectionFailure(false)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    /**
     * Issue a GET and treat *any* completed HTTP response as success — even a 4xx proves the tunnel
     * carried the request to the destination and returned a reply. Only transport failures fail.
     * Returns the round-trip latency in ms.
     */
    private suspend fun httpProbe(client: OkHttpClient, url: String): Result<Long> =
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            runCatching {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { /* reaching here == reachable */ }
                SystemClock.elapsedRealtime() - start
            }.onFailure { Timber.d(it, "Diagnostics probe failed for %s", url) }
        }

    private companion object {
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val HANDSHAKE_WAIT_MS = 8_000L
    }
}

/** Mark every still-pending probe in [ids] as skipped. */
private fun skipRemaining(probes: MutableMap<String, DiagnosticProbe>, ids: List<String>) {
    ids.forEach { id ->
        probes[id]?.let { if (it.state == ProbeState.PENDING) probes[id] = it.copy(state = ProbeState.SKIPPED) }
    }
}

/** A compact, user-facing reason from an exception. */
private fun Throwable.shortReason(): String =
    message?.take(80)?.ifBlank { null } ?: this::class.java.simpleName
