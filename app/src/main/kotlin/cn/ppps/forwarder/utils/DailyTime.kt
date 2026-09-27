package cn.ppps.forwarder.utils

import java.util.Calendar

/**
 * 「每天定时」用的时间下标：一天按 10 分钟一档切成 144 档（与 DataProvider.timePeriodOption 一致）。
 * 每日汇总转发、通话备注每日批量发送都用它表示时间点。
 */
object DailyTime {

    fun slotOf(hour: Int, minute: Int): Int = (hour * 60 + minute) / 10

    //距离下一个该时间点的毫秒数；已过或正好是现在，则算到明天
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
