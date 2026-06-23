package com.theveloper.pixelplay.presentation.navidrome.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.navidrome.tunnel.TunnelState
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardConfigParser
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardConfigStore
import com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardTunnelManager
import com.theveloper.pixelplay.data.network.navidrome.NavidromeApiService
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Transient result of a "Test tunnel" run, shown to the user. */
sealed interface TunnelTestResult {
    data object Idle : TunnelTestResult
    data object Running : TunnelTestResult
    data object Success : TunnelTestResult
    data class Failure(val message: String) : TunnelTestResult
}

/**
 * Drives the WireGuard tunnel section of the Navidrome dashboard: enable toggle, `.conf` import,
 * live connection state, and a ping-based connectivity test.
 */
@HiltViewModel
class NavidromeTunnelViewModel @Inject constructor(
    private val tunnelManager: WireGuardTunnelManager,
    private val configStore: WireGuardConfigStore,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val api: NavidromeApiService,
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

    private val _testResult = MutableStateFlow<TunnelTestResult>(TunnelTestResult.Idle)
    val testResult: StateFlow<TunnelTestResult> = _testResult.asStateFlow()

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
        _testResult.value = TunnelTestResult.Idle
        setEnabled(false)
    }

    /** Bring the tunnel up (if enabled) and ping the server through it. */
    fun testTunnel() {
        viewModelScope.launch {
            _testResult.value = TunnelTestResult.Running
            val ready = tunnelManager.ensureReady()
            if (!ready && enabled.value) {
                _testResult.value = TunnelTestResult.Failure(
                    (tunnelManager.state.value as? TunnelState.Error)?.message ?: "Tunnel failed to connect"
                )
                return@launch
            }
            api.ping().fold(
                onSuccess = { _testResult.value = TunnelTestResult.Success },
                onFailure = { _testResult.value = TunnelTestResult.Failure(it.message ?: "Ping failed") }
            )
        }
    }
}
