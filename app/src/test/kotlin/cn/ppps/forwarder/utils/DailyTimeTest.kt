package cn.ppps.forwarder.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyTimeTest {

    private fun at(hour: Int, minute: Int, second: Int = 0): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 27, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun slotOf_roundsDownToTenMinutes() {
        assertEquals(0, DailyTime.slotOf(0, 0))
        assertEquals(84, DailyTime.slotOf(14, 0))
        assertEquals(79, DailyTime.slotOf(13, 17))
        assertEquals(143, DailyTime.slotOf(23, 59))
    }

    @Test
    fun delayUntilSlot_laterToday() {
        assertEquals(TimeUnit.MINUTES.toMillis(120), DailyTime.delayUntilSlot(84, at(12, 0)))
    }

    @Test
    fun delayUntilSlot_passedOrNow_isTomorrow() {
        assertEquals(TimeUnit.HOURS.toMillis(24), DailyTime.delayUntilSlot(84, at(14, 0)))
        assertEquals(TimeUnit.MINUTES.toMillis(23 * 60 + 50), DailyTime.delayUntilSlot(84, at(14, 10)))
    }
}
