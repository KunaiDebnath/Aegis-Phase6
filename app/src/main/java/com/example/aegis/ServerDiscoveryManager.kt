package com.example.aegis

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class DiscoveryState {
    IDLE,
    DISCOVERING,
    VALIDATING,
    CONNECTED,
    FAILED
}

/**
 * Robust, automatic LAN server discovery manager for the AEGIS system.
 * Eliminates hardcoded laptop LAN IPs across Wi-Fi router changes.
 *
 * Implements a multi-tiered pipeline:
 * 1. Fast Cache Validation: Instant health probe of previously saved server endpoint (< 1s).
 * 2. Android NSD (mDNS): Native DNS-SD discovery for '_aegis._tcp.' with MulticastLock.
 * 3. UDP Broadcast Fallback: Probes port 8001 on routers with multicast isolation.
 * 4. Authoritative Verification: Validates candidate servers via '/api/discovery' before adoption.
 */
object ServerDiscoveryManager {

    private const val TAG = "AEGIS_Discovery"
    private const val SERVICE_TYPE = "_aegis._tcp."
    private const val UDP_DISCOVERY_PORT = 8001
    private const val UDP_PING_MSG = "AEGIS_DISCOVERY_PING"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val discoveryMutex = Mutex()
    private val isRunning = AtomicBoolean(false)
    private var discoveryJob: Job? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    // Compose Observable State
    val state = mutableStateOf(DiscoveryState.IDLE)
    val statusMessage = mutableStateOf("Server discovery idle")
    val connectedUrl = mutableStateOf<String?>(null)
    val isAutoDiscovering = mutableStateOf(false)
    val lastDiscoveredAtMs = mutableStateOf(0L)

    private val fastHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()

    /**
     * Initializes discovery on app startup.
     * Tries fast cache validation first; if unsuccessful or offline, runs full LAN discovery.
     */
    fun startDiscovery(context: Context, forceFullDiscovery: Boolean = false) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (isRunning.get() && !forceFullDiscovery) {
                Log.d(TAG, "Discovery already in progress. Ignoring duplicate start request.")
                return
            }
            isRunning.set(true)
        }

        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            discoveryMutex.withLock {
                try {
                    withContext(Dispatchers.Main) {
                        isAutoDiscovering.value = true
                        state.value = DiscoveryState.DISCOVERING
                        statusMessage.value = "Locating AEGIS safety server on Wi-Fi..."
                    }

                    // --- Tier 1: Fast Cached Endpoint Validation ---
                    if (!forceFullDiscovery) {
                        val cachedUrl = AegisPreferences.getBaseUrl(appContext)
                        if (cachedUrl.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                state.value = DiscoveryState.VALIDATING
                                statusMessage.value = "Testing cached server: $cachedUrl"
                            }
                            val verified = validateServerEndpoint(cachedUrl)
                            if (verified != null) {
                                adoptDiscoveredServer(appContext, verified)
                                return@withLock
                            }
                        }
                    }

                    // --- Tier 2: Android Network Service Discovery (mDNS) ---
                    withContext(Dispatchers.Main) {
                        state.value = DiscoveryState.DISCOVERING
                        statusMessage.value = "Scanning local network via mDNS & broadcast..."
                    }

                    var discoveredResult: ServerDiscoveryResponse? = null

                    // Run NSD and UDP fallback concurrently with a bounded timeout
                    discoveredResult = discoverViaNsd(appContext)

                    // --- Tier 3: UDP Broadcast Probe Fallback ---
                    if (discoveredResult == null) {
                        Log.d(TAG, "mDNS resolution did not find server. Attempting UDP broadcast probe...")
                        discoveredResult = discoverViaUdpBroadcast()
                    }

                    if (discoveredResult != null) {
                        adoptDiscoveredServer(appContext, discoveredResult)
                    } else {
                        withContext(Dispatchers.Main) {
                            state.value = DiscoveryState.FAILED
                            val currentUrl = AegisPreferences.getBaseUrl(appContext)
                            statusMessage.value = "Could not discover AEGIS server on Wi-Fi. (Current: $currentUrl)"
                            isAutoDiscovering.value = false
                        }
                        Log.w(TAG, "Auto discovery concluded without locating a verified AEGIS server.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Discovery pipeline exception: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        state.value = DiscoveryState.FAILED
                        statusMessage.value = "Discovery error: ${e.message ?: "Network unreachable"}"
                        isAutoDiscovering.value = false
                    }
                } finally {
                    releaseMulticastLock()
                    isRunning.set(false)
                }
            }
        }
    }

    /**
     * Resolves the AEGIS server via Android Network Service Discovery (NSD / mDNS).
     */
    private suspend fun discoverViaNsd(context: Context): ServerDiscoveryResponse? = withContext(Dispatchers.IO) {
        acquireMulticastLock(context)
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withContext null
        nsdManager = nsd

        var verifiedServer: ServerDiscoveryResponse? = null
        val resolveMutex = Mutex()
        var hasResolved = false

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD discovery started for type: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                if (hasResolved) return

                val cleanType = serviceInfo.serviceType.trimEnd('.')
                if (cleanType.contains("_aegis") || serviceInfo.serviceName.contains("AEGIS")) {
                    try {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "NSD resolve failed for ${info.serviceName}: code $errorCode")
                            }

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                val host = resolvedInfo.host?.hostAddress
                                val port = resolvedInfo.port
                                Log.i(TAG, "NSD resolved AEGIS candidate: $host:$port")
                                if (!host.isNullOrBlank() && port > 0) {
                                    scope.launch {
                                        resolveMutex.withLock {
                                            if (!hasResolved) {
                                                val candidateUrl = "http://$host:$port/"
                                                val res = validateServerEndpoint(candidateUrl)
                                                if (res != null) {
                                                    verifiedServer = res
                                                    hasResolved = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception calling resolveService: ${e.message}")
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped.")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD start discovery failed: code $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop discovery failed: code $errorCode")
            }
        }

        nsdDiscoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start NSD discovery: ${e.message}")
            return@withContext null
        }

        // Wait up to 3.5 seconds for NSD resolution
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 3500) {
            if (hasResolved && verifiedServer != null) {
                break
            }
            delay(150)
        }

        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Exception) {}
        nsdDiscoveryListener = null

        return@withContext verifiedServer
    }

    /**
     * Fallback: Broadcasts UDP ping to port 8001 for routers that block mDNS multicast.
     */
    private suspend fun discoverViaUdpBroadcast(): ServerDiscoveryResponse? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 2500

            val pingData = UDP_PING_MSG.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                pingData,
                pingData.size,
                InetAddress.getByName("255.255.255.255"),
                UDP_DISCOVERY_PORT
            )

            // Send 2 broadcast probes spaced 100ms apart
            socket.send(packet)
            delay(100)
            socket.send(packet)

            val receiveBuffer = ByteArray(2048)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 2500) {
                try {
                    socket.receive(receivePacket)
                    val rawJson = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    Log.d(TAG, "Received UDP discovery response: $rawJson")
                    val parsed = try { gson.fromJson(rawJson, ServerDiscoveryResponse::class.java) } catch (_: Exception) { null }
                    if (parsed != null && parsed.service == "aegis") {
                        val candidateUrl = "http://${parsed.lan_ip}:${parsed.api_port}/"
                        val verified = validateServerEndpoint(candidateUrl)
                        if (verified != null) {
                            return@withContext verified
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP broadcast discovery probe error: ${e.message}")
        } finally {
            socket?.close()
        }
        return@withContext null
    }

    /**
     * Validates a candidate server endpoint by checking both /health and /api/discovery.
     * Guarantees that arbitrary servers or wrong ports are never adopted.
     */
    suspend fun validateServerEndpoint(rawUrl: String): ServerDiscoveryResponse? = withContext(Dispatchers.IO) {
        val normalized = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
        val discoveryUrl = "${normalized}api/discovery"
        val healthUrl = "${normalized}health"

        try {
            // 1. Health check probe
            val healthReq = Request.Builder().url(healthUrl).get().build()
            val healthResp = fastHttpClient.newCall(healthReq).execute()
            if (!healthResp.isSuccessful) {
                healthResp.close()
                return@withContext null
            }
            healthResp.close()

            // 2. Authoritative discovery metadata probe
            val discReq = Request.Builder().url(discoveryUrl).get().build()
            val discResp = fastHttpClient.newCall(discReq).execute()
            if (discResp.isSuccessful && discResp.body != null) {
                val bodyStr = discResp.body!!.string()
                val parsed = try { gson.fromJson(bodyStr, ServerDiscoveryResponse::class.java) } catch (_: Exception) { null }
                if (parsed != null && parsed.service == "aegis" && parsed.status == "healthy") {
                    discResp.close()
                    return@withContext parsed
                }
            }
            discResp.close()
        } catch (e: Exception) {
            Log.d(TAG, "Validation failed for candidate $rawUrl: ${e.message}")
        }
        return@withContext null
    }

    /**
     * Adopts and applies the validated server endpoint across the app.
     */
    private suspend fun adoptDiscoveredServer(context: Context, server: ServerDiscoveryResponse) {
        val targetUrl = if (server.api_base_url.endsWith("/")) server.api_base_url else "${server.api_base_url}/"
        Log.i(TAG, "Adopting verified AEGIS server: $targetUrl (LiveKit: ${server.livekit_url})")

        // Update preferences and API client singleton
        AegisApiClient.baseUrl = targetUrl
        AegisPreferences.setBaseUrl(context, targetUrl)

        withContext(Dispatchers.Main) {
            connectedUrl.value = targetUrl
            state.value = DiscoveryState.CONNECTED
            statusMessage.value = "Connected to ${server.server_name ?: "AEGIS Server"} at $targetUrl"
            isAutoDiscovering.value = false
            lastDiscoveredAtMs.value = System.currentTimeMillis()
        }

        // Reconnect signaling client and sync FCM token on the new endpoint
        try {
            CallSignalingClient.syncFcmToken()
        } catch (e: Exception) {
            Log.w(TAG, "FCM token sync after discovery: ${e.message}")
        }
    }

    /**
     * Allows developers to manually override the server endpoint if desired.
     */
    suspend fun applyManualEndpoint(context: Context, manualUrl: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = manualUrl.trim()
        if (trimmed.isBlank()) return@withContext false

        withContext(Dispatchers.Main) {
            state.value = DiscoveryState.VALIDATING
            statusMessage.value = "Validating manual endpoint: $trimmed..."
        }

        val verified = validateServerEndpoint(trimmed)
        if (verified != null) {
            adoptDiscoveredServer(context, verified)
            return@withContext true
        } else {
            // Apply even if unvalidated for offline/custom testing, but mark state as FAILED validation
            val fallbackUrl = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            AegisApiClient.baseUrl = fallbackUrl
            AegisPreferences.setBaseUrl(context, fallbackUrl)

            withContext(Dispatchers.Main) {
                connectedUrl.value = fallbackUrl
                state.value = DiscoveryState.FAILED
                statusMessage.value = "Manual URL set ($fallbackUrl), but server health check failed."
            }
            return@withContext false
        }
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("aegis_nsd_lock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
            Log.d(TAG, "MulticastLock acquired.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }
}
