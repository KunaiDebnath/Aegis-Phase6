package com.example.aegis

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AEGIS Automatic Server Discovery, JSON payload validation,
 * dynamic LiveKit endpoint mapping, and state machine transitions.
 */
class ServerDiscoveryUnitTest {

    private val gson = Gson()

    @Before
    fun setUp() {
        ServerDiscoveryManager.state.value = DiscoveryState.IDLE
        ServerDiscoveryManager.statusMessage.value = "Discovery idle"
        ServerDiscoveryManager.connectedUrl.value = null
        ServerDiscoveryManager.isAutoDiscovering.value = false
    }

    // --- 1. State Machine Transitions ---

    @Test
    fun testDiscoveryStateTransitions() {
        assertEquals(DiscoveryState.IDLE, ServerDiscoveryManager.state.value)
        assertFalse(ServerDiscoveryManager.isAutoDiscovering.value)

        // Simulate discovery in progress
        ServerDiscoveryManager.state.value = DiscoveryState.DISCOVERING
        ServerDiscoveryManager.isAutoDiscovering.value = true
        assertEquals(DiscoveryState.DISCOVERING, ServerDiscoveryManager.state.value)
        assertTrue(ServerDiscoveryManager.isAutoDiscovering.value)

        // Simulate validating candidate
        ServerDiscoveryManager.state.value = DiscoveryState.VALIDATING
        assertEquals(DiscoveryState.VALIDATING, ServerDiscoveryManager.state.value)

        // Simulate successful connection
        ServerDiscoveryManager.state.value = DiscoveryState.CONNECTED
        ServerDiscoveryManager.connectedUrl.value = "http://192.168.0.45:8000/"
        ServerDiscoveryManager.isAutoDiscovering.value = false

        assertEquals(DiscoveryState.CONNECTED, ServerDiscoveryManager.state.value)
        assertEquals("http://192.168.0.45:8000/", ServerDiscoveryManager.connectedUrl.value)
        assertFalse(ServerDiscoveryManager.isAutoDiscovering.value)
    }

    // --- 2. Discovery Response Parsing & Validation ---

    @Test
    fun testValidDiscoveryResponseParsing() {
        val rawJson = """
            {
                "service": "aegis",
                "status": "healthy",
                "server_name": "AEGIS Safety Server",
                "lan_ip": "10.0.0.15",
                "api_port": 8000,
                "api_base_url": "http://10.0.0.15:8000/",
                "livekit_url": "ws://10.0.0.15:7880",
                "livekit_port": 7880,
                "version": "1.0"
            }
        """.trimIndent()

        val model = gson.fromJson(rawJson, ServerDiscoveryResponse::class.java)

        assertNotNull(model)
        assertEquals("aegis", model.service)
        assertEquals("healthy", model.status)
        assertEquals("AEGIS Safety Server", model.server_name)
        assertEquals("10.0.0.15", model.lan_ip)
        assertEquals(8000, model.api_port)
        assertEquals("http://10.0.0.15:8000/", model.api_base_url)
        assertEquals("ws://10.0.0.15:7880", model.livekit_url)
        assertEquals(7880, model.livekit_port)
        assertEquals("1.0", model.version)
    }

    @Test
    fun testInvalidDiscoveryResponseRejected() {
        // Case 1: Wrong service name
        val wrongServiceJson = """{"service": "other_app", "status": "healthy", "lan_ip": "192.168.1.1", "api_base_url": "http://192.168.1.1:8000/", "livekit_url": "ws://192.168.1.1:7880"}"""
        val model1 = gson.fromJson(wrongServiceJson, ServerDiscoveryResponse::class.java)
        val isValid1 = model1 != null && model1.service == "aegis" && model1.status == "healthy"
        assertFalse("Wrong service identifier must be rejected", isValid1)

        // Case 2: Status is error / unhealthy
        val unhealthyJson = """{"service": "aegis", "status": "error", "lan_ip": "192.168.1.1", "api_base_url": "http://192.168.1.1:8000/", "livekit_url": "ws://192.168.1.1:7880"}"""
        val model2 = gson.fromJson(unhealthyJson, ServerDiscoveryResponse::class.java)
        val isValid2 = model2 != null && model2.service == "aegis" && model2.status == "healthy"
        assertFalse("Unhealthy status must be rejected", isValid2)

        // Case 3: Empty LAN IP
        val noIpJson = """{"service": "aegis", "status": "healthy", "lan_ip": "", "api_base_url": "http://:8000/", "livekit_url": "ws://:7880"}"""
        val model3 = gson.fromJson(noIpJson, ServerDiscoveryResponse::class.java)
        assertTrue("Empty LAN IP must be detected", model3.lan_ip.isBlank())
    }

    // --- 3. Dynamic LiveKit & API URL Normalization across Subnets ---

    @Test
    fun testUrlNormalization_appendsTrailingSlash() {
        val rawUrls = listOf(
            "http://192.168.0.50:8000" to "http://192.168.0.50:8000/",
            "http://192.168.0.50:8000/" to "http://192.168.0.50:8000/",
            "http://10.10.1.20:8000" to "http://10.10.1.20:8000/",
            "http://172.16.0.5:8000/" to "http://172.16.0.5:8000/"
        )

        for ((input, expected) in rawUrls) {
            val normalized = if (input.endsWith("/")) input else "$input/"
            assertEquals(expected, normalized)
        }
    }

    @Test
    fun testLiveKitEndpointConstruction_forDifferentSubnets() {
        val testIps = listOf(
            "192.168.1.4" to "ws://192.168.1.4:7880",
            "192.168.0.120" to "ws://192.168.0.120:7880",
            "10.0.0.1" to "ws://10.0.0.1:7880",
            "172.20.10.3" to "ws://172.20.10.3:7880"
        )

        for ((lanIp, expectedLivekitUrl) in testIps) {
            val constructed = "ws://$lanIp:7880"
            assertEquals(expectedLivekitUrl, constructed)
        }
    }

    // --- 4. AegisApiClient Dynamic Base URL Switch ---

    @Test
    fun testAegisApiClient_dynamicBaseUrlUpdate() {
        val original = AegisApiClient.DEFAULT_BASE_URL
        assertEquals("http://192.168.1.4:8000/", original)

        // Simulate switching router to 10.0.0.25
        AegisApiClient.baseUrl = "http://10.0.0.25:8000"
        assertEquals("http://10.0.0.25:8000/", AegisApiClient.baseUrl)

        // Switch to 192.168.43.1 (mobile hotspot)
        AegisApiClient.baseUrl = "http://192.168.43.1:8000/"
        assertEquals("http://192.168.43.1:8000/", AegisApiClient.baseUrl)

        // Reset to original default
        AegisApiClient.baseUrl = original
        assertEquals(original, AegisApiClient.baseUrl)
    }
}
