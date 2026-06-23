package com.theveloper.pixelplay.data.navidrome.tunnel

import java.util.Base64

/**
 * Parsed representation of a WireGuard `[Interface]` / `[Peer]` configuration.
 *
 * Keys are kept in their original base64 form (as they appear in a `wg-quick` `.conf`).
 * Use [toUapiConfig] to render the hex-encoded UAPI string that wireguard-go's `IpcSet`
 * expects.
 */
data class WireGuardConfig(
    /** Interface PrivateKey, base64. */
    val privateKey: String,
    /** Interface Address entries (may include CIDR, e.g. "10.0.0.2/32"). */
    val addresses: List<String>,
    /** Interface DNS servers (IP addresses). */
    val dnsServers: List<String>,
    /** Interface MTU; defaults to 1280 which is safe for most WireGuard links. */
    val mtu: Int = DEFAULT_MTU,
    /** Peer PublicKey, base64. */
    val peerPublicKey: String,
    /** Peer PresharedKey, base64 (optional). */
    val presharedKey: String? = null,
    /** Peer Endpoint, "host:port". */
    val endpoint: String,
    /** Peer AllowedIPs (CIDR). */
    val allowedIps: List<String>,
    /** Peer PersistentKeepalive seconds (optional). */
    val persistentKeepalive: Int? = null,
) {
    /** Interface addresses with any CIDR suffix stripped, suitable for netstack. */
    val localAddresses: List<String>
        get() = addresses.map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }

    /**
     * Render the UAPI configuration string consumed by wireguard-go `Device.IpcSet`.
     * Keys are hex-encoded; the peer section starts with `public_key`.
     */
    fun toUapiConfig(): String = buildString {
        appendLine("private_key=${base64KeyToHex(privateKey)}")
        appendLine("public_key=${base64KeyToHex(peerPublicKey)}")
        presharedKey?.takeIf { it.isNotBlank() }?.let {
            appendLine("preshared_key=${base64KeyToHex(it)}")
        }
        appendLine("endpoint=$endpoint")
        persistentKeepalive?.let { appendLine("persistent_keepalive_interval=$it") }
        // Replace, don't accumulate, allowed IPs.
        appendLine("replace_allowed_ips=true")
        val ips = allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") }
        ips.forEach { appendLine("allowed_ip=${it.trim()}") }
    }

    companion object {
        const val DEFAULT_MTU = 1280

        /** Decode a standard 32-byte WireGuard base64 key into lowercase hex. */
        fun base64KeyToHex(base64: String): String {
            val bytes = Base64.getDecoder().decode(base64.trim())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
