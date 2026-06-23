package com.theveloper.pixelplay.data.navidrome.tunnel

import java.util.Base64

/**
 * Parser for standard `wg-quick` `.conf` files into a [WireGuardConfig].
 *
 * Supports the subset of fields relevant for an application-layer (userspace) tunnel:
 * `[Interface]` PrivateKey/Address/DNS/MTU and a single `[Peer]`
 * PublicKey/PresharedKey/Endpoint/AllowedIPs/PersistentKeepalive.
 */
object WireGuardConfigParser {

    /** Thrown when a `.conf` is malformed or missing required fields. */
    class InvalidConfigException(message: String) : IllegalArgumentException(message)

    fun parse(conf: String): WireGuardConfig {
        var section = ""
        var privateKey: String? = null
        val addresses = mutableListOf<String>()
        val dns = mutableListOf<String>()
        var mtu: Int? = null

        var peerPublicKey: String? = null
        var presharedKey: String? = null
        var endpoint: String? = null
        val allowedIps = mutableListOf<String>()
        var keepalive: Int? = null

        conf.lineSequence().forEach { rawLine ->
            // Strip inline comments and whitespace.
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                return@forEach
            }

            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty()) return@forEach

            when (section) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += value.splitCsv()
                    "dns" -> dns += value.splitCsv()
                    "mtu" -> mtu = value.toIntOrNull()
                }
                "peer" -> when (key) {
                    "publickey" -> peerPublicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowedIps += value.splitCsv()
                    "persistentkeepalive" -> keepalive = value.toIntOrNull()
                }
            }
        }

        val pk = privateKey ?: throw InvalidConfigException("Missing [Interface] PrivateKey")
        val peerKey = peerPublicKey ?: throw InvalidConfigException("Missing [Peer] PublicKey")
        val ep = endpoint ?: throw InvalidConfigException("Missing [Peer] Endpoint")
        if (addresses.isEmpty()) throw InvalidConfigException("Missing [Interface] Address")
        if (!ep.contains(':')) throw InvalidConfigException("Endpoint must be host:port")

        // Validate keys decode to 32 bytes so failures surface at upload, not connect time.
        requireKey(pk, "Interface PrivateKey")
        requireKey(peerKey, "Peer PublicKey")
        presharedKey?.let { requireKey(it, "Peer PresharedKey") }

        return WireGuardConfig(
            privateKey = pk,
            addresses = addresses,
            dnsServers = dns,
            mtu = mtu ?: WireGuardConfig.DEFAULT_MTU,
            peerPublicKey = peerKey,
            presharedKey = presharedKey,
            endpoint = ep,
            allowedIps = allowedIps,
            persistentKeepalive = keepalive,
        )
    }

    private fun String.splitCsv(): List<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun requireKey(base64: String, label: String) {
        val bytes = try {
            Base64.getDecoder().decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            throw InvalidConfigException("$label is not valid base64")
        }
        if (bytes.size != 32) throw InvalidConfigException("$label must be a 32-byte key")
    }
}
