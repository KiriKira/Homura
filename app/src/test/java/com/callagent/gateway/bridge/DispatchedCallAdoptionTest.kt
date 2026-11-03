package com.callagent.gateway.bridge

import android.telecom.Call
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchedCallAdoptionTest {
    @Test fun anIncomingCallIsNeverTakenForTheDispatchedCall() {
        assertFalse(CallOrchestrator.mayBeDispatchedCall(Call.STATE_RINGING, CallOrchestrator.DIRECTION_UNKNOWN))
        assertFalse(CallOrchestrator.mayBeDispatchedCall(Call.STATE_DISCONNECTED, CallOrchestrator.DIRECTION_INCOMING))
        assertFalse(CallOrchestrator.mayBeDispatchedCall(Call.STATE_ACTIVE, CallOrchestrator.DIRECTION_INCOMING))
    }

    @Test fun anOutgoingOrUnknownCallThatIsNotRingingMayBe() {
        assertTrue(CallOrchestrator.mayBeDispatchedCall(Call.STATE_DIALING, CallOrchestrator.DIRECTION_OUTGOING))
        assertTrue(CallOrchestrator.mayBeDispatchedCall(Call.STATE_DIALING, CallOrchestrator.DIRECTION_UNKNOWN))
        assertTrue(CallOrchestrator.mayBeDispatchedCall(Call.STATE_DISCONNECTED, CallOrchestrator.DIRECTION_OUTGOING))
    }
}
