package cn.ppps.forwarder.utils

object CallTiming {

    //等待接听秒数：来电有精确的接听时刻（knownRingMillis >= 0）；
    //去电/未接没有，用「总时长 − 通话时长」推算（未接通时通话时长为 0，即整段都在等）
    fun ringSeconds(knownRingMillis: Long, totalSeconds: Int, durationSeconds: Int): Int =
        if (knownRingMillis >= 0) (knownRingMillis / 1000).toInt()
        else maxOf(0, totalSeconds - durationSeconds)

    //默认模板里是否显示「等待接听」一行：只在用户确实在等的类型（来电挂机/去电挂机/未接/来电接通）且有时长时显示
    fun showRingWait(callType: Int, ringSeconds: Int): Boolean =
        ringSeconds > 0 && callType in intArrayOf(1, 2, 3, 5)

    //起止毫秒 → 总秒数，结束早于开始时按 0
    fun totalSeconds(startMillis: Long, endMillis: Long): Int =
        if (endMillis > startMillis) ((endMillis - startMillis) / 1000).toInt() else 0
}
