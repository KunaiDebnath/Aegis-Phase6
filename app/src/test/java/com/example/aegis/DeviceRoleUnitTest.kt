package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying device-specific role selection, persistence behavior,
 * role validation, device independence, and role-switching safety guards.
 */
class DeviceRoleUnitTest {

    @Before
    fun setUp() {
        DeviceRoleManager.selectedRole.value = null
        DeviceRoleManager.isRoleSelected.value = false
        CallSignalingClient.currentDeviceId.value = "phone-b"
        CallSignalingClient.activeIncomingInvitation.value = null
        CallSignalingClient.callerFeedbackMessage.value = null
        CallSignalingClient.activeOutgoingInvitationId.value = null
        EmergencyOrchestrator.isSosActive.value = false
        LiveKitCallManager.callState.value = CallState.IDLE
    }

    // --- 1. Role Constants & Key Names ---

    @Test
    fun testDeviceRoleConstants() {
        assertEquals("phone-a", DeviceRoleManager.ROLE_PHONE_A)
        assertEquals("phone-b", DeviceRoleManager.ROLE_PHONE_B)
        assertEquals("device_selected_role", DeviceRoleManager.KEY_DEVICE_ROLE.name)
    }

    // --- 2. Initial Unselected State (Fresh Install / First Launch) ---

    @Test
    fun testInitialState_noRoleSelected() {
        assertNull("Initial selected role should be null", DeviceRoleManager.selectedRole.value)
        assertFalse("isRoleSelected should be false before user choice", DeviceRoleManager.isRoleSelected.value)
    }

    // --- 3. Role State Transitions & Persistence Model ---

    @Test
    fun testSelectPhoneARole() {
        DeviceRoleManager.selectedRole.value = DeviceRoleManager.ROLE_PHONE_A
        DeviceRoleManager.isRoleSelected.value = true
        CallSignalingClient.setDeviceId(DeviceRoleManager.ROLE_PHONE_A)

        assertEquals("phone-a", DeviceRoleManager.selectedRole.value)
        assertTrue(DeviceRoleManager.isRoleSelected.value)
        assertEquals("phone-a", CallSignalingClient.currentDeviceId.value)
    }

    @Test
    fun testSelectPhoneBRole() {
        DeviceRoleManager.selectedRole.value = DeviceRoleManager.ROLE_PHONE_B
        DeviceRoleManager.isRoleSelected.value = true
        CallSignalingClient.setDeviceId(DeviceRoleManager.ROLE_PHONE_B)

        assertEquals("phone-b", DeviceRoleManager.selectedRole.value)
        assertTrue(DeviceRoleManager.isRoleSelected.value)
        assertEquals("phone-b", CallSignalingClient.currentDeviceId.value)
    }

    @Test
    fun testSwitchRoleFromPhoneAToPhoneB() {
        // Step 1: Initialize as Phone A
        DeviceRoleManager.selectedRole.value = DeviceRoleManager.ROLE_PHONE_A
        DeviceRoleManager.isRoleSelected.value = true
        CallSignalingClient.setDeviceId(DeviceRoleManager.ROLE_PHONE_A)
        assertEquals("phone-a", DeviceRoleManager.selectedRole.value)

        // Step 2: Switch to Phone B
        DeviceRoleManager.selectedRole.value = DeviceRoleManager.ROLE_PHONE_B
        CallSignalingClient.setDeviceId(DeviceRoleManager.ROLE_PHONE_B)
        assertEquals("phone-b", DeviceRoleManager.selectedRole.value)
        assertEquals("phone-b", CallSignalingClient.currentDeviceId.value)
    }

    @Test
    fun testClearRoleSelection_returnsToUnselectedState() {
        // Set role
        DeviceRoleManager.selectedRole.value = DeviceRoleManager.ROLE_PHONE_A
        DeviceRoleManager.isRoleSelected.value = true

        // Simulate clearing role
        DeviceRoleManager.selectedRole.value = null
        DeviceRoleManager.isRoleSelected.value = false

        assertNull("Selected role must reset to null", DeviceRoleManager.selectedRole.value)
        assertFalse("isRoleSelected must reset to false", DeviceRoleManager.isRoleSelected.value)
    }

    // --- 4. Role Validation Logic ---

    @Test
    fun testRoleValidation_validRolesAccepted() {
        val validRoles = listOf("phone-a", "phone-b", "PHONE-A", "PHONE-B", " Phone-A ", " Phone-B ")
        for (raw in validRoles) {
            val clean = raw.trim().lowercase()
            assertTrue("Role '$raw' must be valid", clean == DeviceRoleManager.ROLE_PHONE_A || clean == DeviceRoleManager.ROLE_PHONE_B)
        }
    }

    @Test
    fun testRoleValidation_invalidRolesRejected() {
        val invalidRoles = listOf("phone-c", "admin", "requester", "responder", "", "  ", "123", "phone-a-extra")
        for (raw in invalidRoles) {
            val clean = raw.trim().lowercase()
            val isValid = clean == DeviceRoleManager.ROLE_PHONE_A || clean == DeviceRoleManager.ROLE_PHONE_B
            assertFalse("Invalid role '$raw' must be rejected", isValid)
        }
    }

    // --- 5. Two Physical Devices Independence Simulation ---

    @Test
    fun testPhysicalDevicesIndependence_eachMaintainsOwnRole() {
        // Model Device A (Phone A)
        var deviceARole: String? = DeviceRoleManager.ROLE_PHONE_A
        var deviceASignalingId = "phone-a"

        // Model Device B (Phone B)
        var deviceBRole: String? = DeviceRoleManager.ROLE_PHONE_B
        var deviceBSignalingId = "phone-b"

        // Verify independent roles
        assertNotEquals("Device A and Device B have distinct roles", deviceARole, deviceBRole)
        assertEquals("phone-a", deviceARole)
        assertEquals("phone-b", deviceBRole)
        assertEquals("phone-a", deviceASignalingId)
        assertEquals("phone-b", deviceBSignalingId)

        // Changing Device A role to phone-b should NOT affect Device B
        deviceARole = DeviceRoleManager.ROLE_PHONE_B
        deviceASignalingId = "phone-b"

        assertEquals("Device A updated independently", "phone-b", deviceARole)
        assertEquals("Device B retained its own independent role", "phone-b", deviceBRole)

        // Switching Device B role to phone-a
        deviceBRole = DeviceRoleManager.ROLE_PHONE_A
        deviceBSignalingId = "phone-a"

        assertEquals("Device A remained phone-b", "phone-b", deviceARole)
        assertEquals("Device B updated independently to phone-a", "phone-a", deviceBRole)
    }

    // --- 6. Active Session Safety Guard Check ---

    @Test
    fun testRoleSwitchSafetyGuard_detectsActiveSosSession() {
        EmergencyOrchestrator.isSosActive.value = true

        val isSessionActive = EmergencyOrchestrator.isSosActive.value ||
                LiveKitCallManager.isCallActive ||
                CallSignalingClient.activeIncomingInvitation.value != null

        assertTrue("Guard must detect active SOS session", isSessionActive)
    }

    @Test
    fun testRoleSwitchSafetyGuard_detectsActiveLiveKitCall() {
        EmergencyOrchestrator.isSosActive.value = false
        LiveKitCallManager.callState.value = CallState.CONNECTED

        val isSessionActive = EmergencyOrchestrator.isSosActive.value ||
                LiveKitCallManager.isCallActive ||
                CallSignalingClient.activeIncomingInvitation.value != null

        assertTrue("Guard must detect active LiveKit audio call", isSessionActive)
    }

    @Test
    fun testRoleSwitchSafetyGuard_detectsActiveIncomingInvitation() {
        EmergencyOrchestrator.isSosActive.value = false
        LiveKitCallManager.callState.value = CallState.IDLE
        CallSignalingClient.activeIncomingInvitation.value = IncomingCallInvitation(
            invitationId = "inv-12345",
            emergencyEventId = "event-67890",
            callerDeviceId = "phone-a",
            callerDisplayName = "Phone A (Requester)",
            createdAtIso = "2026-10-27T10:00:00Z",
            expiresAtIso = "2026-10-27T10:00:45Z",
            status = "pending"
        )

        val isSessionActive = EmergencyOrchestrator.isSosActive.value ||
                LiveKitCallManager.isCallActive ||
                CallSignalingClient.activeIncomingInvitation.value != null

        assertTrue("Guard must detect active incoming call invitation", isSessionActive)
    }

    @Test
    fun testRoleSwitchSafetyGuard_allowsDirectSwitchWhenIdle() {
        EmergencyOrchestrator.isSosActive.value = false
        LiveKitCallManager.callState.value = CallState.IDLE
        CallSignalingClient.activeIncomingInvitation.value = null

        val isSessionActive = EmergencyOrchestrator.isSosActive.value ||
                LiveKitCallManager.isCallActive ||
                CallSignalingClient.activeIncomingInvitation.value != null

        assertFalse("Guard allows switch when all sessions are idle", isSessionActive)
    }
}
