package cn.ppps.forwarder.utils

import java.util.Calendar

/**
 * Time-of-day slot for "daily at a set time": the day is cut into 144 ten-minute slots
 * (same as DataProvider.timePeriodOption). Used by daily batch forwarding and the daily call-note batch.
 */
object DailyTime {

    fun slotOf(hour: Int, minute: Int): Int = (hour * 60 + minute) / 10

    //Milliseconds until the next occurrence of the slot; if it has passed or is now, the next one is tomorrow
    fun delayUntilSlot(slot: Int, now: Calendar = Calendar.getInstance()): Long {
        val totalMinutes = slot * 10
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, totalMinutes / 60)
            set(Calendar.MINUTE, totalMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
