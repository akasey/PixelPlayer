// Package wgnetstack runs WireGuard entirely in userspace (no tun device / VpnService) using
// wireguard-go + gVisor netstack, and exposes a localhost SOCKS5 proxy that carries TCP through
// the tunnel. PixelPlayer points its Navidrome OkHttpClient at this proxy.
//
// Built into an Android AAR via gomobile (see build-aar.sh). The exported, gomobile-friendly
// surface is StartProxy / StopProxy; the Kotlin side calls them reflectively
// (com.theveloper.pixelplay.wgnetstack.Wgnetstack).
package wgnetstack

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

var (
	mu       sync.Mutex
	dev      *device.Device
	listener net.Listener
	tnet     *netstack.Net
)

// StartProxy brings up a userspace WireGuard interface and a SOCKS5 server.
//
//	uapiConfig    wireguard-go IpcSet string (hex keys; see WireGuardConfig.toUapiConfig)
//	localAddrsCsv comma-separated interface addresses (e.g. "10.0.0.2")
//	dnsCsv        comma-separated DNS servers used for in-tunnel name resolution
//	mtu           interface MTU (e.g. 1280)
//	socksPort     desired localhost SOCKS5 port; pass 0 to auto-pick
//
// Returns the bound SOCKS5 port. Safe to call repeatedly; an existing tunnel is torn down first.
func StartProxy(uapiConfig string, localAddrsCsv string, dnsCsv string, mtu int, socksPort int) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()

	localAddrs, err := parseAddrs(localAddrsCsv)
	if err != nil {
		return 0, fmt.Errorf("local addresses: %w", err)
	}
	if len(localAddrs) == 0 {
		return 0, errors.New("no local addresses")
	}
	dnsAddrs, err := parseAddrs(dnsCsv)
	if err != nil {
		return 0, fmt.Errorf("dns: %w", err)
	}
	if mtu <= 0 {
		mtu = 1280
	}

	tun, net0, err := netstack.CreateNetTUN(localAddrs, dnsAddrs, mtu)
	if err != nil {
		return 0, fmt.Errorf("create netstack tun: %w", err)
	}

	// wireguard-go's IpcSet requires endpoints as literal IP:port; it does not resolve DNS.
	// Resolve the peer endpoint on the real (underlay) network before configuring the device.
	resolvedConfig, err := resolveEndpoints(uapiConfig)
	if err != nil {
		return 0, fmt.Errorf("resolve endpoint: %w", err)
	}

	d := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wg "))
	if err := d.IpcSet(resolvedConfig); err != nil {
		d.Close()
		return 0, fmt.Errorf("ipc set: %w", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return 0, fmt.Errorf("device up: %w", err)
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
	if err != nil {
		d.Close()
		return 0, fmt.Errorf("listen socks: %w", err)
	}

	dev = d
	tnet = net0
	listener = ln

	go serveSocks(ln, net0)

	return ln.Addr().(*net.TCPAddr).Port, nil
}

// StopProxy tears down the SOCKS server and WireGuard interface. Idempotent.
func StopProxy() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	if listener != nil {
		listener.Close()
		listener = nil
	}
	if dev != nil {
		dev.Close()
		dev = nil
	}
	tnet = nil
}

// resolveEndpoints rewrites every "endpoint=host:port" line in a UAPI config so the host is a
// literal IP address, resolving DNS via the device's real network. IP endpoints pass through
// unchanged.
func resolveEndpoints(uapi string) (string, error) {
	lines := strings.Split(uapi, "\n")
	for i, line := range lines {
		const prefix = "endpoint="
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		hostPort := strings.TrimSpace(line[len(prefix):])
		// Already a literal IP:port? Leave it.
		if _, err := netip.ParseAddrPort(hostPort); err == nil {
			continue
		}
		addr, err := net.ResolveUDPAddr("udp", hostPort)
		if err != nil {
			return "", fmt.Errorf("%s: %w", hostPort, err)
		}
		lines[i] = prefix + addr.String()
	}
	return strings.Join(lines, "\n"), nil
}

func parseAddrs(csv string) ([]netip.Addr, error) {
	out := make([]netip.Addr, 0)
	for _, p := range strings.Split(csv, ",") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		a, err := netip.ParseAddr(p)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, nil
}

// ── Minimal SOCKS5 (no auth, CONNECT only) with remote DNS via netstack ──────────────────────

func serveSocks(ln net.Listener, n *netstack.Net) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return // listener closed
		}
		go handleSocks(c, n)
	}
}

func handleSocks(c net.Conn, n *netstack.Net) {
	defer c.Close()

	// Greeting: VER, NMETHODS, METHODS...
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil || hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	// Reply: no authentication required.
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request: VER, CMD, RSV, ATYP, ADDR, PORT
	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil || req[0] != 0x05 {
		return
	}
	if req[1] != 0x01 { // CONNECT only
		reply(c, 0x07) // command not supported
		return
	}

	var host string
	switch req[3] {
	case 0x01: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 0x04: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 0x03: // domain name (remote DNS, resolved inside the tunnel)
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return
		}
		name := make([]byte, int(l[0]))
		if _, err := io.ReadFull(c, name); err != nil {
			return
		}
		host = string(name)
	default:
		reply(c, 0x08) // address type not supported
		return
	}

	pb := make([]byte, 2)
	if _, err := io.ReadFull(c, pb); err != nil {
		return
	}
	port := int(pb[0])<<8 | int(pb[1])

	target := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	upstream, err := n.DialContext(context.Background(), "tcp", target)
	if err != nil {
		reply(c, 0x05) // connection refused
		return
	}
	defer upstream.Close()

	if err := reply(c, 0x00); err != nil { // success
		return
	}

	// Pump bidirectionally.
	done := make(chan struct{}, 2)
	go func() { io.Copy(upstream, c); done <- struct{}{} }()
	go func() { io.Copy(c, upstream); done <- struct{}{} }()
	<-done
}

func reply(c net.Conn, status byte) error {
	// VER, REP, RSV, ATYP=IPv4, BND.ADDR=0.0.0.0, BND.PORT=0
	_, err := c.Write([]byte{0x05, status, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}
