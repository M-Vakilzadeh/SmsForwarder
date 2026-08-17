package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.R
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.receiver.CallReceiver
import cn.ppps.forwarder.utils.FORWARD_TIMING_DAILY
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SettingUtils
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xutil.resource.ResUtils.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 每日汇总转发（用户可选的「每天定时统一转发」模式）。
 *
 * 到点后直接读通话记录 / 短信数据库，把最近窗口内的记录逐条走正常的转发管道（SendWorker → 规则 → 通道 → T4 持久化投递）。
 * 因为直接读数据库、不依赖广播接收器，即便实时捕获整段失效，数据到点也能补出去（与 T7 通道 C 的思路一致）。
 *
 * 幂等关键：msgInfo.date 一律设为「记录真实发生时间」(通话 dateLong / 短信 date)。
 * SendWorker 用 msgInfo.date 作为 msg.time，于是重叠窗口重读同一条记录时，
 * md5([device_mark]+[from]+[receive_time]+[org_content]) 完全一致，服务端可安全去重。
 *
 * 窗口取最近 25 小时（比 24 小时多 1 小时重叠），宁可重复也不漏——重复由服务端去重兜底。
 */
class DailyForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            //模式随时可能被改回实时，跑之前再确认一次
            if (SettingUtils.forwardTiming != FORWARD_TIMING_DAILY) {
                Log.d(TAG, "当前非每日汇总模式，跳过")
                return@withContext Result.success()
            }

            val windowStart = System.currentTimeMillis() - WINDOW_MILLIS

            if (SettingUtils.dailyIncludeCall) forwardCalls(windowStart)
            if (SettingUtils.dailyIncludeSms) forwardSms(windowStart)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "每日汇总执行异常：${e.message}", e)
            Result.retry()
        }
    }

    private fun forwardCalls(windowStart: Long) {
        val callList = PhoneUtils.getCallInfoList(0, MAX_ROWS, 0, null)
        var count = 0
        for (callInfo in callList) {
            if (callInfo.dateLong < windowStart) continue
            //只处理 1.来电 2.去电 3.未接（与实时通话转发语义一致）；CallLog 的 4/5/6=语音信箱/拒接/拦截，跳过
            if (callInfo.type !in 1..3) continue
            //尊重通话类型开关
            if ((callInfo.type == 1 && !SettingUtils.enableCallType1)
                || (callInfo.type == 2 && !SettingUtils.enableCallType2)
                || (callInfo.type == 3 && !SettingUtils.enableCallType3)
            ) continue

            val simSlot = callInfo.simId
            val simInfo = when (simSlot) {
                0 -> "SIM1_" + SettingUtils.extraSim1
                1 -> "SIM2_" + SettingUtils.extraSim2
                else -> ""
            }
            if (TextUtils.isEmpty(callInfo.name)) {
                val contacts = PhoneUtils.getContactByNumber(callInfo.number)
                callInfo.name = if (contacts.isNotEmpty()) contacts[0].name else getString(R.string.unknown_number)
            }

            //date 用通话真实发生时间，保证幂等键稳定
            val msgInfo = MsgInfo("call", callInfo.number, PhoneUtils.getCallMsg(callInfo), Date(callInfo.dateLong), simInfo, simSlot, callInfo.subId, callInfo.type)
            msgInfo.callDuration = callInfo.duration
            msgInfo.callDateLong = callInfo.dateLong
            CallReceiver.enqueueSend(applicationContext, msgInfo)
            count++
        }
        if (callList.size >= MAX_ROWS) Log.w(TAG, "通话记录达到读取上限 $MAX_ROWS，可能有更早的记录未纳入本次汇总")
        Log.i(TAG, "每日汇总：通话 $count 条已入队")
    }

    private fun forwardSms(windowStart: Long) {
        //type=1：收件箱（已接收），与实时短信转发一致
        val smsList = PhoneUtils.getSmsInfoList(1, MAX_ROWS, 0, "")
        var count = 0
        for (smsInfo in smsList) {
            if (smsInfo.date < windowStart) continue

            val simSlot = smsInfo.simId
            val simInfo = when (simSlot) {
                0 -> "SIM1_" + SettingUtils.extraSim1
                1 -> "SIM2_" + SettingUtils.extraSim2
                else -> ""
            }
            //date 用短信真实时间，保证幂等键稳定
            val msgInfo = MsgInfo("sms", smsInfo.number, smsInfo.content, Date(smsInfo.date), simInfo, simSlot, smsInfo.subId)
            CallReceiver.enqueueSend(applicationContext, msgInfo)
            count++
        }
        if (smsList.size >= MAX_ROWS) Log.w(TAG, "短信达到读取上限 $MAX_ROWS，可能有更早的短信未纳入本次汇总")
        Log.i(TAG, "每日汇总：短信 $count 条已入队")
    }

    companion object {
        private const val TAG = "DailyForwardWorker"
        private const val UNIQUE = "daily_forward_digest"

        //窗口：最近 25 小时（1 小时重叠，宁可重复也不漏）
        private const val WINDOW_MILLIS = 25L * 60 * 60 * 1000

        //单次读取上限，避免极端情况一次拉爆内存
        private const val MAX_ROWS = 500

        /**
         * 按当前设置（重新）安排每日汇总任务。
         * - 非每日模式：取消任务；
         * - 每日模式：以「距下一个设定时间点」为初始延迟，安排 24 小时周期任务。
         *
         * 用 REPLACE：每次调用都按最新时间重新锚定到「下一个设定时间」，设置变更或重启后都能生效。
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (SettingUtils.forwardTiming != FORWARD_TIMING_DAILY) {
                wm.cancelUniqueWork(UNIQUE)
                Log.d(TAG, "非每日模式，已取消每日汇总任务")
                return
            }
            val request = PeriodicWorkRequestBuilder<DailyForwardWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(computeInitialDelayMillis(), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.REPLACE, request)
            Log.d(TAG, "已安排每日汇总任务，初始延迟 ${computeInitialDelayMillis() / 60000} 分钟")
        }

        //根据设置的时间下标（10 分钟一档）算出距离下一个该时间点的毫秒数
        private fun computeInitialDelayMillis(): Long {
            val totalMinutes = SettingUtils.dailyForwardTime * 10
            val hour = totalMinutes / 60
            val minute = totalMinutes % 60
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
