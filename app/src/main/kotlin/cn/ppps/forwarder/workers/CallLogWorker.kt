package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.ppps.forwarder.R
import cn.ppps.forwarder.entity.CallInfo
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.receiver.CallReceiver
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.Worker
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xutil.resource.ResUtils.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 通话结束后，拨号器是「异步」写入通话记录的，写入耗时在不同机型/负载下差异很大。
 *
 * 原实现在主线程 `Thread.sleep(1000)` 后读取一次，取不到就降级成没有时长的通知，
 * 并且匹配条件只有 `NUMBER like '%号码%'`，会匹配到同一号码更早的那一通电话。
 *
 * 这里改为轮询等待，并且只接受「时间戳不早于本次通话开始时间」的记录。
 */
class CallLogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private val TAG: String = CallLogWorker::class.java.simpleName

        //轮询间隔
        private const val POLL_INTERVAL_MILLIS = 500L

        //最长等待时间
        private const val POLL_TIMEOUT_MILLIS = 30_000L

        //允许通话记录的时间戳比本次通话开始时间略早，容忍系统时钟与拨号器写入之间的偏差
        private const val CLOCK_TOLERANCE_MILLIS = 5_000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val callType = inputData.getInt(Worker.CALL_TYPE, 0)
            val phoneNumber = inputData.getString(Worker.PHONE_NUMBER)
            val callStartMillis = inputData.getLong(Worker.CALL_START_MILLIS, 0L)
            Log.d(TAG, "callType = $callType, phoneNumber = $phoneNumber, callStartMillis = $callStartMillis")

            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS
            var callInfo: CallInfo? = null
            while (true) {
                callInfo = findCallInfo(callType, phoneNumber, callStartMillis)
                if (callInfo != null) break
                if (System.currentTimeMillis() >= deadline) break
                delay(POLL_INTERVAL_MILLIS)
            }

            if (callInfo == null) {
                //宁可发一条没有时长的记录，也不能把这次通话整个丢掉
                Log.w(TAG, "等待 ${POLL_TIMEOUT_MILLIS}ms 仍查不到通话记录，降级为无时长通知，callType=$callType, phoneNumber=$phoneNumber")
                CallReceiver.sendNotice(applicationContext, callType, phoneNumber, callLogMissing = true)
                return@withContext Result.success()
            }

            Log.d(TAG, "callInfo = $callInfo")

            //判断是否开启该类型转发
            if ((callInfo.type == 1 && !SettingUtils.enableCallType1) || (callInfo.type == 2 && !SettingUtils.enableCallType2) || (callInfo.type == 3 && !SettingUtils.enableCallType3)) {
                Log.w(TAG, "未开启该类型转发，type=" + callInfo.type)
                return@withContext Result.success()
            }

            //卡槽id：-1=获取失败、0=卡槽1、1=卡槽2
            val simSlot = callInfo.simId
            //获取卡槽信息
            val simInfo = when (simSlot) {
                0 -> "SIM1_" + SettingUtils.extraSim1
                1 -> "SIM2_" + SettingUtils.extraSim2
                else -> ""
            }

            //获取联系人姓名
            if (TextUtils.isEmpty(callInfo.name)) {
                val contacts = PhoneUtils.getContactByNumber(callInfo.number.ifBlank { phoneNumber ?: "" })
                callInfo.name = if (contacts.isNotEmpty()) contacts[0].name else getString(R.string.unknown_number)
            }

            val number = callInfo.number.ifBlank { phoneNumber ?: "" }
            val msgInfo = MsgInfo("call", number, PhoneUtils.getCallMsg(callInfo), Date(), simInfo, simSlot, callInfo.subId, callType)
            CallReceiver.enqueueSend(applicationContext, msgInfo)

        } catch (e: Exception) {
            Log.e(TAG, "CallLogWorker error: ${e.message}", e)
            return@withContext Result.failure()
        }

        return@withContext Result.success()
    }

    /**
     * 只接受时间戳不早于本次通话开始时间的记录，避免匹配到同一号码更早的那一通电话。
     *
     * 先用号码精确定位；部分机型的通话记录里号码格式与广播携带的不一致（或者根本拿不到号码），
     * 再退化成「同类型的最新一条」——配合时间戳校验，这条就是刚刚结束的那一通。
     */
    private fun findCallInfo(callType: Int, phoneNumber: String?, callStartMillis: Long): CallInfo? {
        val minDate = callStartMillis - CLOCK_TOLERANCE_MILLIS

        if (!TextUtils.isEmpty(phoneNumber)) {
            val byNumber = PhoneUtils.getLastCallInfo(callType, phoneNumber)
            if (byNumber != null && byNumber.dateLong >= minDate) return byNumber
        }

        val byType = PhoneUtils.getLastCallInfo(callType, null)
        if (byType != null && byType.dateLong >= minDate) return byType

        return null
    }

}
