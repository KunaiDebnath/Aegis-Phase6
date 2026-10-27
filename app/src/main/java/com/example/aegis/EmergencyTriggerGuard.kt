package com.example.aegis

/**
 * Thread-safe emergency trigger guard.
 * Coordinates manual and voice triggers, enforcing:
 * 1. Single active emergency session at any time.
 * 2. Atomic acquisition so rapid recognition callbacks cannot trigger concurrent workflows.
 * 3. A 5-second debounce cooldown window between emergency activations.
 * 4. Explicit reset capability when starting a new emergency event.
 */
object EmergencyTriggerGuard {

    private val lock = Any()

    @Volatile
    private var isEmergencyActive = false

    @Volatile
    private var isWorkflowExecuting = false

    @Volatile
    private var lastTriggerTimestamp = 0L

    private const val DEBOUNCE_COOLDOWN_MS = 5000L

    /**
     * Atomically attempts to acquire permission to trigger an emergency SOS.
     * Returns true only if no emergency is currently active, no workflow is in flight,
     * and cooldown duration has elapsed.
     */
    fun tryAcquireTrigger(): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()

            if (isEmergencyActive || isWorkflowExecuting) {
                return false
            }

            if (now - lastTriggerTimestamp < DEBOUNCE_COOLDOWN_MS) {
                return false
            }

            isWorkflowExecuting = true
            isEmergencyActive = true
            lastTriggerTimestamp = now
            return true
        }
    }

    /**
     * Marks the active emergency workflow execution as completed (idle),
     * while keeping isEmergencyActive true until explicitly reset.
     */
    fun markWorkflowComplete() {
        synchronized(lock) {
            isWorkflowExecuting = false
        }
    }

    /**
     * Resets the guard, allowing a new emergency to be triggered.
     * Called when the user taps "NEW SOS EVENT".
     */
    fun reset() {
        synchronized(lock) {
            isEmergencyActive = false
            isWorkflowExecuting = false
            lastTriggerTimestamp = 0L
        }
    }

    val isActive: Boolean
        get() = isEmergencyActive

    val isExecuting: Boolean
        get() = isWorkflowExecuting
}
