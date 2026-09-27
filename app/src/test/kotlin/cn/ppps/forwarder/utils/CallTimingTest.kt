package cn.ppps.forwarder.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CallTimingTest {

    @Test
    fun incomingAnswered_usesExactRingMillis() {
        assertEquals(7, CallTiming.ringSeconds(7_400L, 60, 50))
    }

    @Test
    fun outgoingUnanswered_wholeCallIsWaiting() {
        assertEquals(35, CallTiming.ringSeconds(-1L, 35, 0))
    }

    @Test
    fun outgoingAnswered_totalMinusTalk() {
        assertEquals(12, CallTiming.ringSeconds(-1L, 72, 60))
    }

    @Test
    fun negativeResultClampedToZero() {
        assertEquals(0, CallTiming.ringSeconds(-1L, 10, 30))
    }

    @Test
    fun showRingWait_onlyForWaitingCallTypesWithPositiveSeconds() {
        //1 来电挂机、2 去电挂机、3 未接、5 来电接通 —— 用户在等
        for (t in listOf(1, 2, 3, 5)) assertEquals(true, CallTiming.showRingWait(t, 7))
        //4 来电提醒、6 去电拨出、0 未知：还没有等待时长
        for (t in listOf(0, 4, 6)) assertEquals(false, CallTiming.showRingWait(t, 7))
        assertEquals(false, CallTiming.showRingWait(1, 0))
        assertEquals(false, CallTiming.showRingWait(1, -1))
    }

    @Test
    fun totalSeconds_endBeforeStartIsZero() {
        assertEquals(0, CallTiming.totalSeconds(5_000L, 1_000L))
        assertEquals(4, CallTiming.totalSeconds(1_000L, 5_900L))
    }
}
