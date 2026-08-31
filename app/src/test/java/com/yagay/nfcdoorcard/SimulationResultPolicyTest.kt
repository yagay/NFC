package com.yagay.nfcdoorcard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationResultPolicyTest {
    private fun verifiedState(effective: String, uid: String? = null) = RuntimeStatus(
        currentPid = 1027,
        commandGeneration = 10,
        handledGeneration = 10,
        commandStatus = "SUCCESS",
        commandPid = 1027,
        operationState = "IDLE",
        effectiveState = effective,
        verificationConfidence = "VERIFIED",
        rfAccepted = true,
        rfUid = uid,
        rfPid = 1027,
        rfGeneration = 10
    )

    @Test fun applyRequiresMatchingUidAndCurrentPidProof() {
        val state = verifiedState("ACTIVE", "04A1B2C3")
        assertTrue(SimulationResultPolicy.isApplySuccess(state, 10, "04a1b2c3"))
        assertFalse(SimulationResultPolicy.isApplySuccess(state.copy(rfPid = 999), 10, "04A1B2C3"))
        assertFalse(SimulationResultPolicy.isApplySuccess(state, 10, "DEADBEEF"))
    }

    @Test fun stopRequiresVerifiedStockProof() {
        val state = verifiedState("STOCK")
        assertTrue(SimulationResultPolicy.isStopSuccess(state, 10))
        assertFalse(SimulationResultPolicy.isStopSuccess(state.copy(verificationConfidence = "PENDING"), 10))
        assertFalse(SimulationResultPolicy.isStopSuccess(state.copy(effectiveState = "ACTIVE"), 10))
    }
}
