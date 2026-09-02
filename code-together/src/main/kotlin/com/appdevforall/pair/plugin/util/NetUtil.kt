package com.appdevforall.pair.plugin.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    const val DEFAULT_PAIR_PORT: Int = 7050
    const val PAIR_TOKEN_HEADER: String = "X-Pair-Token"

    data class Invite(val host: String, val port: Int, val token: String)

    fun findLanIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces().asSequence().toList()
            PairLog.d("[NET] enumerating ${interfaces.size} interfaces")
            val candidates = mutableListOf<Pair<NetworkInterface, Inet4Address>>()
            for (iface in interfaces) {
                val v4 = iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .toList()
                val excluded = isExcludedInterface(iface.name)
                PairLog.d(
                    "[NET] iface=${iface.name} up=${iface.isUp} loopback=${iface.isLoopback} " +
                        "virtual=${iface.isVirtual} excluded=$excluded ipv4=${v4.map { it.hostAddress }}"
                )
                if (!iface.isUp || iface.isLoopback || iface.isVirtual || excluded) continue
                for (address in v4) candidates += iface to address
            }
            for ((iface, address) in candidates) {
                PairLog.d(
                    "[NET] candidate ${address.hostAddress} on ${iface.name} " +
                        "siteLocal=${address.isSiteLocalAddress} score=${addressScore(iface.name, address)}"
                )
            }
            val chosen = candidates.maxByOrNull { (iface, address) -> addressScore(iface.name, address) }
            PairLog.d("[NET] chosen=${chosen?.second?.hostAddress} on ${chosen?.first?.name}")
            chosen?.second?.hostAddress
        }.onFailure { PairLog.e("[NET] findLanIpv4 failed", it) }.getOrNull()
    }

    private fun isExcludedInterface(name: String): Boolean {
        val lower = name.lowercase()
        return EXCLUDED_INTERFACE_PREFIXES.any { lower.startsWith(it) }
    }

    private fun addressScore(interfaceName: String, address: Inet4Address): Int {
        var score = 0
        if (address.isSiteLocalAddress) score += 100
        val lower = interfaceName.lowercase()
        score += when {
            lower.startsWith("wlan") -> 50
            lower.startsWith("ap") || lower.startsWith("swlan") -> 40
            lower.startsWith("eth") -> 30
            else -> 0
        }
        return score
    }

    private val EXCLUDED_INTERFACE_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "ppp", "rndis", "tun", "tap", "dummy", "docker", "p2p",
    )

    fun parseAddress(input: String): Pair<String, Int>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":")
        val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        return when (parts.size) {
            1 -> host to DEFAULT_PAIR_PORT
            2 -> host to (parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
            else -> null
        }
    }

    fun buildInvite(host: String, port: Int, token: String): String =
        "ws://$host:$port?t=$token"

    fun parseInvite(input: String): Invite? {
        val withoutScheme = input.trim().removePrefix("ws://").removePrefix("wss://")
        if (withoutScheme.isEmpty()) return null
        val queryIndex = withoutScheme.indexOf('?')
        val authority = if (queryIndex >= 0) withoutScheme.substring(0, queryIndex) else withoutScheme
        val query = if (queryIndex >= 0) withoutScheme.substring(queryIndex + 1) else ""
        val address = parseAddress(authority) ?: return null
        return Invite(address.first, address.second, tokenFromQuery(query))
    }

    private fun tokenFromQuery(query: String): String {
        if (query.isEmpty()) return ""
        for (pair in query.split("&")) {
            val separator = pair.indexOf('=')
            if (separator > 0 && pair.substring(0, separator) == "t") {
                return pair.substring(separator + 1)
            }
        }
        return ""
    }
}
