# Userspace WireGuard tunnel (Navidrome)

This directory builds an **application-layer** WireGuard engine that PixelPlayer uses to reach a
private Navidrome/Subsonic server — **without** a system VPN (`VpnService`) or `tun` interface.

It wraps [`wireguard-go`](https://git.zx2c4.com/wireguard-go) + gVisor **netstack** and runs a
localhost **SOCKS5** proxy. The Navidrome `OkHttpClient` (`@NavidromeOkHttpClient`) routes through
that proxy when the tunnel is enabled; SOCKS5 remote-DNS resolves private hostnames inside the
tunnel.

```
OkHttp ──SOCKS5 127.0.0.1:port──> wgnetstack ──encrypted UDP──> WireGuard peer ──> Navidrome
```

## How it fits together

| Piece | Where |
|-------|-------|
| Go engine + SOCKS5 server | `wgnetstack.go` (this dir) |
| AAR build | `build-aar.sh` → `app/libs/wireguard-netstack.aar` |
| Kotlin bridge (reflection) | `data/navidrome/tunnel/NetstackWireGuardTunnel.kt` |
| Lifecycle / proxy selection | `data/navidrome/tunnel/WireGuardTunnelManager.kt` |
| Gradle flag | `-Ppixelplay.enableWireguard=true` + `BuildConfig.ENABLE_WIREGUARD` |

The app **compiles and runs without the AAR**: `NetstackWireGuardTunnel` resolves the native class
reflectively and degrades to a no-op (tunnel reports unavailable) when it is missing, and the
Gradle flag only links the AAR when the file is present.

## Build

```bash
# One-time toolchain setup
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init                      # needs Android SDK + NDK

# Build + install the AAR into app/libs/
./tools/wireguard/build-aar.sh

# Build the app with the tunnel engine linked
./gradlew :app:assembleDebug -Ppixelplay.enableWireguard=true
```

The generated Java class is `com.theveloper.pixelplay.wgnetstack.Wgnetstack` with static methods
`long startProxy(String uapi, String localAddrsCsv, String dnsCsv, long mtu, long socksPort)` and
`void stopProxy()` — kept in sync with `NetstackWireGuardTunnel.kt`.

## Native surface

- `StartProxy(uapiConfig, localAddrsCsv, dnsCsv, mtu, socksPort) -> boundPort` — brings up the
  interface (via `Device.IpcSet`) and the SOCKS5 listener; pass `socksPort=0` to auto-pick.
- `StopProxy()` — tears everything down (idempotent).

`uapiConfig` is produced by `WireGuardConfig.toUapiConfig()` (hex-encoded keys). The user supplies
a standard `wg-quick` `.conf` in-app (Accounts → Navidrome → upload), parsed by
`WireGuardConfigParser`.
