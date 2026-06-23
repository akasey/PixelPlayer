package com.theveloper.pixelplay.data.navidrome.tunnel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WireGuardConfigParserTest {

    // 32 zero bytes -> base64 ("AAAA...=") -> 64 hex zeros. Handy deterministic key for tests.
    private val zeroKeyB64 = "A".repeat(43) + "="
    private val zeroKeyHex = "0".repeat(64)

    private val sampleConf = """
        [Interface]
        PrivateKey = $zeroKeyB64
        Address = 10.0.0.2/32, fd00::2/128
        DNS = 10.0.0.1
        MTU = 1320

        [Peer]
        PublicKey = $zeroKeyB64
        PresharedKey = $zeroKeyB64
        Endpoint = vpn.example.com:51820
        AllowedIPs = 10.0.0.0/24, 192.168.1.0/24
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `parses interface and peer fields`() {
        val cfg = WireGuardConfigParser.parse(sampleConf)

        assertThat(cfg.privateKey).isEqualTo(zeroKeyB64)
        assertThat(cfg.addresses).containsExactly("10.0.0.2/32", "fd00::2/128").inOrder()
        assertThat(cfg.localAddresses).containsExactly("10.0.0.2", "fd00::2").inOrder()
        assertThat(cfg.dnsServers).containsExactly("10.0.0.1")
        assertThat(cfg.mtu).isEqualTo(1320)
        assertThat(cfg.peerPublicKey).isEqualTo(zeroKeyB64)
        assertThat(cfg.presharedKey).isEqualTo(zeroKeyB64)
        assertThat(cfg.endpoint).isEqualTo("vpn.example.com:51820")
        assertThat(cfg.allowedIps).containsExactly("10.0.0.0/24", "192.168.1.0/24").inOrder()
        assertThat(cfg.persistentKeepalive).isEqualTo(25)
    }

    @Test
    fun `renders hex-keyed UAPI config`() {
        val uapi = WireGuardConfigParser.parse(sampleConf).toUapiConfig()

        assertThat(uapi).contains("private_key=$zeroKeyHex")
        assertThat(uapi).contains("public_key=$zeroKeyHex")
        assertThat(uapi).contains("preshared_key=$zeroKeyHex")
        assertThat(uapi).contains("endpoint=vpn.example.com:51820")
        assertThat(uapi).contains("persistent_keepalive_interval=25")
        assertThat(uapi).contains("allowed_ip=10.0.0.0/24")
        assertThat(uapi).contains("allowed_ip=192.168.1.0/24")
        assertThat(uapi).contains("replace_allowed_ips=true")
    }

    @Test
    fun `defaults mtu and allowed ips when absent`() {
        val conf = """
            [Interface]
            PrivateKey = $zeroKeyB64
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = $zeroKeyB64
            Endpoint = 1.2.3.4:51820
        """.trimIndent()

        val cfg = WireGuardConfigParser.parse(conf)
        assertThat(cfg.mtu).isEqualTo(WireGuardConfig.DEFAULT_MTU)
        assertThat(cfg.presharedKey).isNull()
        // Empty AllowedIPs falls back to a full tunnel in the UAPI rendering.
        assertThat(cfg.toUapiConfig()).contains("allowed_ip=0.0.0.0/0")
    }

    @Test
    fun `ignores comments and blank lines`() {
        val conf = """
            # leading comment
            [Interface]
            PrivateKey = $zeroKeyB64  # inline comment

            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $zeroKeyB64
            Endpoint = 1.2.3.4:51820
        """.trimIndent()

        val cfg = WireGuardConfigParser.parse(conf)
        assertThat(cfg.privateKey).isEqualTo(zeroKeyB64)
        assertThat(cfg.endpoint).isEqualTo("1.2.3.4:51820")
    }

    @Test
    fun `throws when private key missing`() {
        val conf = """
            [Interface]
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $zeroKeyB64
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        assertThrows<WireGuardConfigParser.InvalidConfigException> { WireGuardConfigParser.parse(conf) }
    }

    @Test
    fun `throws when endpoint has no port`() {
        val conf = """
            [Interface]
            PrivateKey = $zeroKeyB64
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $zeroKeyB64
            Endpoint = 1.2.3.4
        """.trimIndent()
        assertThrows<WireGuardConfigParser.InvalidConfigException> { WireGuardConfigParser.parse(conf) }
    }

    @Test
    fun `throws when key is not 32 bytes`() {
        val conf = """
            [Interface]
            PrivateKey = c2hvcnQ=
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $zeroKeyB64
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        assertThrows<WireGuardConfigParser.InvalidConfigException> { WireGuardConfigParser.parse(conf) }
    }
}
