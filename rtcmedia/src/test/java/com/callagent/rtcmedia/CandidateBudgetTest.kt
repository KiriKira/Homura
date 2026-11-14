package com.callagent.rtcmedia
import org.junit.Assert.*
import org.junit.Test
class CandidateBudgetTest {
 @Test fun turnBurstCannotConsumeHostAndReflexiveReserve(){val budget=CandidateBudget();assertEquals(64,(1..500).count{budget.accept("candidate:1 1 udp 1 127.0.0.1 123 typ relay")});assertEquals(32,(1..100).count{budget.accept("candidate:2 1 udp 1 ::1 123 typ host")});assertEquals(32,(1..100).count{budget.accept("candidate:3 1 udp 1 1.1.1.1 123 typ srflx")});assertFalse(budget.accept("malformed"))}
}
