package com.theveloper.pixelplay.data.navidrome.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WireGuardTunnel] backed by the bundled wireguard-go + gVisor netstack AAR.
 *
 * The AAR (built from `tools/wireguard/`) runs WireGuard in-process and exposes a localhost
 * SOCKS5 proxy. We invoke it via reflection so the app compiles and runs even when the AAR is
 * absent — in that case [isSupported] is false and [start] reports an error, mirroring
 * [NoOpWireGuardTunnel].
 *
 * Expected gomobile surface (Go package `wgnetstack`, javapkg prefix `com.theveloper.pixelplay`):
 *   - `long startProxy(String uapiConfig, String localAddrsCsv, String dnsCsv, long mtu, long socksPort)`
 *       returns the bound SOCKS5 port (pass 0 to auto-pick). Throws on failure.
 *   - `void stopProxy()`
 */
@Singleton
class NetstackWireGuardTunnel @Inject constructor() : WireGuardTunnel {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    override val state: StateFlow<TunnelState> = _state

    private val lock = Mutex()

    // Reflectively resolve the gomobile-generated bridge once.
    private val bridge: Class<*>? by lazy {
        runCatching { Class.forName("com.theveloper.pixelplay.wgnetstack.Wgnetstack") }
            .onFailure { Timber.w("wireguard-netstack AAR not present; tunnel disabled") }
            .getOrNull()
    }

    override val isSupported: Boolean get() = bridge != null

    override suspend fun start(config: WireGuardConfig) = lock.withLock {
        if (_state.value is TunnelState.Up) return@withLock
        val cls = bridge ?: run {
            _state.value = TunnelState.Error("WireGuard engine not available in this build")
            return@withLock
        }
        _state.value = TunnelState.Connecting
        try {
            val port = withContext(Dispatchers.IO) {
                val method = cls.getMethod(
                    "startProxy",
                    String::class.java, String::class.java, String::class.java,
                    Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
                )
                (method.invoke(
                    null,
                    config.toUapiConfig(),
                    config.localAddresses.joinToString(","),
                    config.dnsServers.joinToString(","),
                    config.mtu.toLong(),
                    0L // auto-pick a free SOCKS port
                ) as Long).toInt()
            }
            _state.value = TunnelState.Up(port)
            Timber.i("WireGuard userspace tunnel up; SOCKS5 on 127.0.0.1:$port")
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Timber.e(cause, "Failed to start WireGuard tunnel")
            _state.value = TunnelState.Error(cause.message ?: "WireGuard start failed")
        }
    }

    override suspend fun stop() = lock.withLock {
        val cls = bridge
        if (cls != null && _state.value !is TunnelState.Down) {
            runCatching {
                withContext(Dispatchers.IO) { cls.getMethod("stopProxy").invoke(null) }
            }.onFailure { Timber.w(it, "Error stopping WireGuard tunnel") }
        }
        _state.value = TunnelState.Down
    }

    override fun socksProxy(): Proxy? {
        val s = _state.value
        return if (s is TunnelState.Up) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", s.socksPort))
        } else null
    }

    override fun stats(): WireGuardStats? {
        val cls = bridge ?: return null
        if (_state.value !is TunnelState.Up) return null
        return try {
            val raw = cls.getMethod("stats").invoke(null) as? String
            if (raw.isNullOrBlank()) null else WireGuardStats.parse(raw)
        } catch (e: Throwable) {
            Timber.w(e, "Failed to read WireGuard stats")
            null
        }
    }
}
