package com.appdevforall.pair.plugin.data

data class StoredSession(
    val id: String,
    val customName: String?,
    val address: String,
    val port: Int,
    val role: SessionRole,
    val lastConnectedMillis: Long,
)
