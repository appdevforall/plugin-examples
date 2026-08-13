package com.appdevforall.pair.plugin.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.appdevforall.pair.plugin.util.PairLog
import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

class PairDiscoveryService(
    context: Context,
    private val logger: PluginLogger,
) {

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts

    private val lock = Any()
    private var localPeerId: String = ""

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue: ArrayDeque<NsdServiceInfo> = ArrayDeque()
    private var resolving: Boolean = false

    fun register(displayName: String, port: Int, token: String, peerId: String) {
        synchronized(lock) {
            unregisterLocked()
            val info = NsdServiceInfo().apply {
                serviceName = sanitizeName(displayName)
                serviceType = SERVICE_TYPE
                this.port = port
                setAttribute(ATTR_PEER_ID, peerId)
                setAttribute(ATTR_DISPLAY_NAME, displayName)
                setAttribute(ATTR_TOKEN, token)
                setAttribute(ATTR_PROTOCOL, ProtocolMessage.PROTOCOL_VERSION.toString())
            }
            val listener = registrationListener()
            registrationListener = listener
            runCatching {
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                registrationListener = null
                logger.warn("PairPlugin: NSD register failed (${it.message})")
            }
        }
    }

    fun unregister() {
        synchronized(lock) { unregisterLocked() }
    }

    fun startDiscovery(localPeerId: String) {
        synchronized(lock) {
            this.localPeerId = localPeerId
            if (discoveryListener != null) return
            _hosts.value = emptyList()
            val listener = discoveryListener()
            discoveryListener = listener
            runCatching {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                discoveryListener = null
                logger.warn("PairPlugin: NSD discovery failed to start (${it.message})")
            }
        }
    }

    fun stopDiscovery() {
        synchronized(lock) {
            val listener = discoveryListener ?: return
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            discoveryListener = null
            resolveQueue.clear()
            resolving = false
            _hosts.value = emptyList()
        }
    }

    fun shutdown() {
        unregister()
        stopDiscovery()
    }

    private fun unregisterLocked() {
        val listener = registrationListener ?: return
        runCatching { nsdManager.unregisterService(listener) }
        registrationListener = null
    }

    private fun registrationListener() = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            logger.info("PairPlugin: NSD registered as ${serviceInfo.serviceName}")
            PairLog.d("[DISCOVERY] advertising as '${serviceInfo.serviceName}' ($SERVICE_TYPE)")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            logger.warn("PairPlugin: NSD registration failed (code $errorCode)")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            logger.warn("PairPlugin: NSD unregistration failed (code $errorCode)")
        }
    }

    private fun discoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            logger.warn("PairPlugin: NSD start discovery failed (code $errorCode)")
            synchronized(lock) { discoveryListener = null }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            logger.warn("PairPlugin: NSD stop discovery failed (code $errorCode)")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            _hosts.update { current -> current.filterNot { it.serviceName == name } }
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(lock) {
            resolveQueue.addLast(info)
            pumpResolveQueueLocked()
        }
    }

    private fun pumpResolveQueueLocked() {
        if (resolving) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        runCatching {
            nsdManager.resolveService(next, resolveListener())
        }.onFailure {
            logger.warn("PairPlugin: NSD resolve failed to start (${it.message})")
            resolving = false
            pumpResolveQueueLocked()
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            logger.warn("PairPlugin: NSD resolve failed for ${serviceInfo.serviceName} (code $errorCode)")
            onResolveFinished()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            addResolved(serviceInfo)
            onResolveFinished()
        }
    }

    private fun onResolveFinished() {
        synchronized(lock) {
            resolving = false
            pumpResolveQueueLocked()
        }
    }

    private fun addResolved(info: NsdServiceInfo) {
        val attributes = info.attributes ?: return
        val peerId = attributes.text(ATTR_PEER_ID) ?: return
        if (peerId == localPeerId) return
        val token = attributes.text(ATTR_TOKEN) ?: return
        val host = info.host?.hostAddress ?: return
        val protocol = attributes.text(ATTR_PROTOCOL)?.toIntOrNull() ?: 0
        val displayName = attributes.text(ATTR_DISPLAY_NAME) ?: info.serviceName
        val discovered = DiscoveredHost(
            serviceName = info.serviceName,
            peerId = peerId,
            displayName = displayName,
            host = host,
            port = info.port,
            token = token,
            protocolVersion = protocol,
        )
        PairLog.d("[DISCOVERY] resolved host '$displayName' at $host:${info.port} (peerId=$peerId)")
        _hosts.update { current ->
            current.filterNot { it.peerId == peerId } + discovered
        }
    }

    private fun Map<String, ByteArray?>.text(key: String): String? =
        this[key]?.let { String(it, Charsets.UTF_8) }

    private fun sanitizeName(name: String): String =
        name.trim().take(MAX_SERVICE_NAME).ifBlank { "Pair host" }

    private companion object {
        const val SERVICE_TYPE = "_pairide._tcp."
        const val ATTR_PEER_ID = "pid"
        const val ATTR_DISPLAY_NAME = "dn"
        const val ATTR_TOKEN = "t"
        const val ATTR_PROTOCOL = "v"
        const val MAX_SERVICE_NAME = 60
    }
}
