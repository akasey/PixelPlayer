package wgnetstack

// End-to-end tests for the userspace WireGuard + SOCKS5 engine.
//
// Spins up a second in-process WireGuard peer ("server") over localhost UDP, runs an HTTP
// server inside its netstack, and drives the exported StartProxy/StopProxy surface exactly the
// way the Kotlin side does — then streams data through the SOCKS proxy and verifies integrity.
// This covers the full data path PixelPlayer uses for Navidrome audio: OkHttp → SOCKS5 →
// wireguard-go → netstack TCP → server, including the bulk-download direction (client rx).

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func genKeypair(t *testing.T) (privHex, pubHex string) {
	t.Helper()
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		t.Fatal(err)
	}
	// Clamp per curve25519 convention.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(priv[:]), hex.EncodeToString(pub)
}

func freeUDPPort(t *testing.T) int {
	t.Helper()
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	port := pc.LocalAddr().(*net.UDPAddr).Port
	pc.Close()
	return port
}

// startServerPeer brings up the "remote" WireGuard peer at 10.9.9.1 listening on listenPort,
// with an in-netstack HTTP server at :80 serving deterministic pseudo-random blobs.
func startServerPeer(t *testing.T, listenPort int, clientPubHex, serverPrivHex string, blob []byte) *device.Device {
	t.Helper()
	tun, tnet, err := netstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr("10.9.9.1")}, nil, 1280,
	)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv "))
	uapi := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.9.9.2/32\n",
		serverPrivHex, listenPort, clientPubHex,
	)
	if err := dev.IpcSet(uapi); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}

	ln, err := tnet.ListenTCP(&net.TCPAddr{Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/blob", func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "blob.bin", time.Now(), bytes.NewReader(blob))
	})
	go http.Serve(ln, mux) //nolint:errcheck — torn down with the device
	t.Cleanup(func() { ln.Close(); dev.Close() })
	return dev
}

// socksGet performs a raw SOCKS5 CONNECT to 10.9.9.1:80 through the proxy at socksPort and
// issues a single HTTP/1.1 GET, returning the response.
func socksGet(t *testing.T, socksPort int, path, rangeHeader string) *http.Response {
	t.Helper()
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), 5*time.Second)
	if err != nil {
		t.Fatalf("dial socks: %v", err)
	}
	t.Cleanup(func() { c.Close() })

	// Greeting: no auth.
	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatal(err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(c, resp); err != nil || resp[0] != 0x05 || resp[1] != 0x00 {
		t.Fatalf("greeting failed: %v % x", err, resp)
	}
	// CONNECT 10.9.9.1:80 (ATYP=IPv4).
	req := []byte{0x05, 0x01, 0x00, 0x01, 10, 9, 9, 1, 0, 80}
	if _, err := c.Write(req); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatalf("connect reply: %v", err)
	}
	if reply[1] != 0x00 {
		t.Fatalf("socks connect refused: status=%d", reply[1])
	}

	httpReq := "GET " + path + " HTTP/1.1\r\nHost: 10.9.9.1\r\n"
	if rangeHeader != "" {
		httpReq += "Range: " + rangeHeader + "\r\n"
	}
	httpReq += "Connection: close\r\n\r\n"
	if _, err := c.Write([]byte(httpReq)); err != nil {
		t.Fatal(err)
	}
	c.SetReadDeadline(time.Now().Add(60 * time.Second))
	httpResp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	return httpResp
}

func startTunnelPair(t *testing.T, blob []byte) (socksPort int) {
	t.Helper()
	serverPriv, serverPub := genKeypair(t)
	clientPriv, clientPub := genKeypair(t)
	wgPort := freeUDPPort(t)

	startServerPeer(t, wgPort, clientPub, serverPriv, blob)

	uapi := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\npersistent_keepalive_interval=25\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\n",
		clientPriv, serverPub, wgPort,
	)
	port, err := StartProxy(uapi, "10.9.9.2", "", 1280, 0)
	if err != nil {
		t.Fatalf("StartProxy: %v", err)
	}
	t.Cleanup(StopProxy)
	return port
}

func mustReadAll(t *testing.T, r io.Reader) []byte {
	t.Helper()
	b, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return b
}

func TestBulkDownloadThroughSocks(t *testing.T) {
	// 32 MiB — a typical FLAC track; large enough to exercise TCP windowing, the netstack
	// receive path, and the SOCKS relay pump well beyond the handshake/ping regime.
	blob := make([]byte, 32<<20)
	if _, err := rand.Read(blob); err != nil {
		t.Fatal(err)
	}
	wantSum := sha256.Sum256(blob)

	socksPort := startTunnelPair(t, blob)

	start := time.Now()
	resp := socksGet(t, socksPort, "/blob", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status: %s", resp.Status)
	}
	body := mustReadAll(t, resp.Body)
	elapsed := time.Since(start)

	if len(body) != len(blob) {
		t.Fatalf("body length: got %d want %d", len(body), len(blob))
	}
	if sha256.Sum256(body) != wantSum {
		t.Fatal("body corrupted in transit")
	}
	t.Logf("downloaded %d MiB in %v (%.1f MiB/s)",
		len(body)>>20, elapsed, float64(len(body))/(1<<20)/elapsed.Seconds())

	// The download must show up in the client's rx counters (the user-facing metric).
	stats := Stats()
	if !strings.Contains(stats, "rx_bytes=") {
		t.Fatalf("stats missing rx_bytes: %q", stats)
	}
	for _, line := range strings.Split(stats, "\n") {
		if v, ok := strings.CutPrefix(line, "rx_bytes="); ok {
			n, _ := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
			if n < int64(len(blob)) {
				t.Fatalf("rx_bytes=%d, want >= %d", n, len(blob))
			}
		}
	}
}

func TestRangeRequestThroughSocks(t *testing.T) {
	// ExoPlayer re-opens with "Range: bytes=N-" on every seek / buffer refill; make sure a
	// partial-content fetch through the tunnel returns exactly the requested suffix.
	blob := make([]byte, 4<<20)
	if _, err := rand.Read(blob); err != nil {
		t.Fatal(err)
	}
	socksPort := startTunnelPair(t, blob)

	const offset = 1_234_567
	resp := socksGet(t, socksPort, "/blob", fmt.Sprintf("bytes=%d-", offset))
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("status: %s", resp.Status)
	}
	body := mustReadAll(t, resp.Body)
	if !bytes.Equal(body, blob[offset:]) {
		t.Fatalf("range body mismatch: got %d bytes want %d", len(body), len(blob)-offset)
	}
}

func TestHalfCloseClientStillReceivesFullBody(t *testing.T) {
	// A strict HTTP/1.0-style client half-closes its write side after sending the request and
	// then reads the response. The relay must forward the FIN upstream without tearing down the
	// server→client direction, or the body arrives truncated.
	blob := make([]byte, 4<<20)
	if _, err := rand.Read(blob); err != nil {
		t.Fatal(err)
	}
	socksPort := startTunnelPair(t, blob)

	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatal(err)
	}
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Write([]byte{0x05, 0x01, 0x00, 0x01, 10, 9, 9, 1, 0, 80}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil || reply[1] != 0 {
		t.Fatalf("connect: %v status=%d", err, reply[1])
	}
	if _, err := c.Write([]byte("GET /blob HTTP/1.1\r\nHost: 10.9.9.1\r\nConnection: close\r\n\r\n")); err != nil {
		t.Fatal(err)
	}
	// Half-close: no more request bytes will follow.
	if err := c.(*net.TCPConn).CloseWrite(); err != nil {
		t.Fatal(err)
	}
	c.SetReadDeadline(time.Now().Add(60 * time.Second))
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatalf("read response after half-close: %v", err)
	}
	body := mustReadAll(t, resp.Body)
	if len(body) != len(blob) {
		t.Fatalf("half-close truncated body: got %d bytes want %d", len(body), len(blob))
	}
}

func TestConcurrentStreamsThroughSocks(t *testing.T) {
	// DualPlayerEngine pre-rolls the next track while the current one plays: two concurrent
	// bulk streams through one tunnel must not starve or corrupt each other.
	blob := make([]byte, 8<<20)
	if _, err := rand.Read(blob); err != nil {
		t.Fatal(err)
	}
	wantSum := sha256.Sum256(blob)
	socksPort := startTunnelPair(t, blob)

	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		go func() {
			defer func() {
				if r := recover(); r != nil {
					errs <- fmt.Errorf("panic: %v", r)
				}
			}()
			c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), 5*time.Second)
			if err != nil {
				errs <- err
				return
			}
			defer c.Close()
			if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
				errs <- err
				return
			}
			hdr := make([]byte, 2)
			if _, err := io.ReadFull(c, hdr); err != nil {
				errs <- err
				return
			}
			if _, err := c.Write([]byte{0x05, 0x01, 0x00, 0x01, 10, 9, 9, 1, 0, 80}); err != nil {
				errs <- err
				return
			}
			reply := make([]byte, 10)
			if _, err := io.ReadFull(c, reply); err != nil || reply[1] != 0 {
				errs <- fmt.Errorf("connect: %v status=%d", err, reply[1])
				return
			}
			if _, err := c.Write([]byte("GET /blob HTTP/1.1\r\nHost: 10.9.9.1\r\nConnection: close\r\n\r\n")); err != nil {
				errs <- err
				return
			}
			c.SetReadDeadline(time.Now().Add(60 * time.Second))
			resp, err := http.ReadResponse(bufio.NewReader(c), nil)
			if err != nil {
				errs <- err
				return
			}
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				errs <- err
				return
			}
			if sha256.Sum256(body) != wantSum {
				errs <- fmt.Errorf("stream corrupted")
				return
			}
			errs <- nil
		}()
	}
	for i := 0; i < 2; i++ {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
}
