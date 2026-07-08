package com.theveloper.pixelplay.presentation.navidrome.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.navidrome.tunnel.DiagnosticsReport
import com.theveloper.pixelplay.data.navidrome.tunnel.TunnelDiagnostics
import com.theveloper.pixelplay.data.navidrome.tunnel.TunnelState
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardConfigParser
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardConfigStore
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardStats
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardTunnelManager
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live tunnel statistics for the UI: cumulative totals plus per-second rates derived by sampling.
 *
 * @param downBytesPerSec Download rate (rx delta over the sample interval).
 * @param upBytesPerSec Upload rate (tx delta over the sample interval).
 */
data class TunnelStatsUi(
    val lastHandshakeEpochSec: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val downBytesPerSec: Long,
    val upBytesPerSec: Long,
)

/**
 * Drives the WireGuard tunnel section of the Navidrome dashboard: enable toggle, `.conf` import,
 * live connection state, and a layered connectivity diagnosis (tunnel → handshake → internet →
 * server) that separates tunnel problems from server problems.
 */
@HiltViewModel
class NavidromeTunnelViewModel @Inject constructor(
    private val tunnelManager: WireGuardTunnelManager,
    private val configStore: WireGuardConfigStore,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val diagnostics: TunnelDiagnostics,
) : ViewModel() {

    val isSupported: Boolean get() = tunnelManager.isSupported

    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    val enabled: StateFlow<Boolean> = userPreferencesRepository.navidromeTunnelEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _endpoint = MutableStateFlow(configStore.parsedConfig()?.endpoint)
    /** Configured peer endpoint ("host:port"), or null if no config imported. */
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    /** Latest diagnostics snapshot, or null when a run has never been started. */
    private val _diagnostics = MutableStateFlow<DiagnosticsReport?>(null)
    val diagnostics: StateFlow<DiagnosticsReport?> = _diagnostics.asStateFlow()

    private var diagnosticsJob: Job? = null

    /**
     * Live tunnel stats, polled once a second while observed. Speeds are computed from the byte
     * deltas between consecutive samples. Null while the tunnel is down.
     */
    val stats: StateFlow<TunnelStatsUi?> = flow {
        var prev: WireGuardStats? = null
        var prevAt = 0L
        while (true) {
            val cur = tunnelManager.stats()
            if (cur == null) {
                prev = null
                prevAt = 0L
                emit(null)
            } else {
                val now = System.currentTimeMillis()
                val last = prev
                if (last != null && prevAt > 0L) {
                    val dtMs = (now - prevAt).coerceAtLeast(1L)
                    val down = ((cur.rxBytes - last.rxBytes) * 1000L / dtMs).coerceAtLeast(0L)
                    val up = ((cur.txBytes - last.txBytes) * 1000L / dtMs).coerceAtLeast(0L)
                    emit(TunnelStatsUi(cur.lastHandshakeEpochSec, cur.rxBytes, cur.txBytes, down, up))
                } else {
                    emit(TunnelStatsUi(cur.lastHandshakeEpochSec, cur.rxBytes, cur.txBytes, 0L, 0L))
                }
                prev = cur
                prevAt = now
            }
            delay(1_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun hasConfig(): Boolean = configStore.hasConfig()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setNavidromeTunnelEnabled(enabled) }
    }

    /** Validate and persist an uploaded `.conf`. Sets [importError] on failure. */
    fun importConfig(confText: String) {
        try {
            val parsed = WireGuardConfigParser.parse(confText)
            configStore.rawConfig = confText
            _endpoint.value = parsed.endpoint
            _importError.value = null
        } catch (e: Exception) {
            _importError.value = e.message ?: "Invalid WireGuard config"
        }
    }

    fun clearConfig() {
        configStore.clear()
        _endpoint.value = null
        diagnosticsJob?.cancel()
        _diagnostics.value = null
        setEnabled(false)
    }

    /**
     * Run the layered tunnel diagnosis, streaming progressive updates into [diagnostics] so the UI
     * shows each probe resolving live. A run in progress is left to finish (button is disabled).
     */
    fun runDiagnostics() {
        if (diagnosticsJob?.isActive == true) return
        diagnosticsJob = viewModelScope.launch {
            diagnostics.run().collect { report -> _diagnostics.value = report }
        }
    }
}
