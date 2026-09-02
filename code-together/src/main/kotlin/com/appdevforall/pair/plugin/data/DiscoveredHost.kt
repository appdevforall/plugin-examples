package com.appdevforall.pair.plugin.data

data class DiscoveredHost(
    val serviceName: String,
    val peerId: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val token: String,
    val protocolVersion: Int,
)
