package cn.ppps.forwarder.receiver

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import cn.ppps.forwarder.App.Companion.CALL_TYPE_MAP
import cn.ppps.forwarder.R
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.CallNotePopup
import cn.ppps.forwarder.utils.FORWARD_TIMING_DAILY
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.Worker
import cn.ppps.forwarder.workers.CallLogWorker
import cn.ppps.forwarder.workers.SendWorker
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xutil.resource.ResUtils.getString
import java.util.Date

open class CallReceiver : PhoneStateReceiver() {

    companion object {
        private val TAG = CallReceiver::class.java.simpleName

        //const val ACTION_IN = "android.intent.action.PHONE_STATE"
        const val ACTION_OUT = "android.intent.action.NEW_OUTGOING_CALL"
        const val EXTRA_PHONE_NUMBER = "android.intent.extra.PHONE_NUMBER"

        //转发通话提醒
        //callLogMissing：通话记录在超时时间内始终没有落库，此条记录没有真实通话时长
        //ringSeconds：等待接听秒数（拿不到通话记录时由调用方用已知信息给出）
        fun sendNotice(context: Context, callType: Int, phoneNumber: String?, callLogMissing: Boolean = false, ringSeconds: Int = 0) {
            if (TextUtils.isEmpty(phoneNumber)) return

            //每日汇总模式下不实时转发通话，改由 DailyForwardWorker 到点统一读通话记录转发
            if (SettingUtils.forwardTiming == FORWARD_TIMING_DAILY && SettingUtils.dailyIncludeCall) {
                Log.d(TAG, "每日汇总模式，跳过实时通话通知，callType=$callType")
                return
            }

            //判断是否开启该类型转发
            if ((callType == 4 && !SettingUtils.enableCallType4) || (callType == 5 && !SettingUtils.enableCallType5) || (callType == 6 && !SettingUtils.enableCallType6)) {
                Log.w(TAG, "未开启该类型转发，type=$callType")
                return
            }

            val contacts = PhoneUtils.getContactByNumber(phoneNumber)
            val contactName = if (contacts.isNotEmpty()) contacts[0].name else getString(R.string.unknown_number)

            val msg = StringBuilder()
            msg.append(getString(R.string.contact)).append(contactName).append("\n")
            msg.append(getString(R.string.mandatory_type))
            msg.append(CALL_TYPE_MAP[callType.toString()] ?: getString(R.string.unknown_call))

            val msgInfo = MsgInfo("call", phoneNumber.toString(), msg.toString(), Date(), "", -1, 0, callType)
            msgInfo.callLogMissing = callLogMissing
            msgInfo.ringSeconds = ringSeconds
            enqueueSend(context, msgInfo)
        }

        //提交给 SendWorker 走转发规则
        fun enqueueSend(context: Context, msgInfo: MsgInfo) {
            val request = OneTimeWorkRequestBuilder<SendWorker>().setInputData(
                workDataOf(
                    Worker.SEND_MSG_INFO to Gson().toJson(msgInfo)
                )
            ).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    //来电提醒
    override fun onIncomingCallReceived(context: Context, number: String?, start: Date) {
        Log.d(TAG, "onIncomingCallReceived：$number")
        //响铃时收起通话备注悬浮窗，避免挡住接听键
        CallNotePopup.onCallStarted(context)
        if (SettingUtils.enablePhone) sendNotice(context, 4, number)
    }

    //来电接通
    override fun onIncomingCallAnswered(context: Context, number: String?, start: Date) {
        Log.d(TAG, "onIncomingCallAnswered：$number")
        if (SettingUtils.enablePhone) sendNotice(context, 5, number, ringSeconds = ((Date().time - start.time) / 1000).toInt().coerceAtLeast(0))
    }

    //来电挂机
    override fun onIncomingCallEnded(context: Context, number: String?, start: Date, end: Date) {
        Log.d(TAG, "onIncomingCallEnded：$number")
        CallNotePopup.onCallEnded(context, 1, number, start, end)
        if (SettingUtils.enablePhone) sendCallMsg(context, 1, number, start, end)
    }

    //去电拨出
    override fun onOutgoingCallStarted(context: Context, number: String?, start: Date) {
        Log.d(TAG, "onOutgoingCallStarted：$number")
        CallNotePopup.onCallStarted(context)
        if (SettingUtils.enablePhone) sendNotice(context, 6, number)
    }

    //去电挂机
    override fun onOutgoingCallEnded(context: Context, number: String?, start: Date, end: Date) {
        Log.d(TAG, "onOutgoingCallEnded：$number")
        CallNotePopup.onCallEnded(context, 2, number, start, end)
        if (SettingUtils.enablePhone) sendCallMsg(context, 2, number, start, end)
    }

    //未接来电
    override fun onMissedCall(context: Context, number: String?, start: Date) {
        Log.d(TAG, "onMissedCall：$number")
        val end = Date()
        //未接来电不弹备注，但要恢复响铃时收起的悬浮窗
        CallNotePopup.onCallEnded(context, 3, number, start, end)
        if (SettingUtils.enablePhone) sendCallMsg(context, 3, number, start, end)
    }

    //转发通话记录
    //拨号器是异步写入通话记录的，这里不再在主线程 sleep 等待，改为交给 CallLogWorker 轮询，
    //并且只接受时间戳不早于本次通话开始时间的记录，避免匹配到同一号码更早的那一通电话。
    //等待接听时长：来电有接听时刻，精确 = 接听 − 响铃开始；去电/未接广播里没有接听时刻，交给 CallLogWorker 用「总时长 − 通话时长」推算
    private fun sendCallMsg(context: Context, callType: Int, phoneNumber: String?, start: Date, end: Date) {
        //每日汇总模式下不实时转发通话记录，改由 DailyForwardWorker 到点统一处理
        if (SettingUtils.forwardTiming == FORWARD_TIMING_DAILY && SettingUtils.dailyIncludeCall) {
            Log.d(TAG, "每日汇总模式，跳过实时通话转发，callType=$callType")
            return
        }
        Log.d(TAG, "callType = $callType, phoneNumber = $phoneNumber, callStart = $start")

        val answered = if (callType == 1) PhoneStateReceiver.answerTime else null
        val ringMillis = if (answered != null) maxOf(0L, answered.time - start.time) else -1L

        val request = OneTimeWorkRequestBuilder<CallLogWorker>().setInputData(
            workDataOf(
                Worker.CALL_TYPE to callType,
                Worker.PHONE_NUMBER to phoneNumber,
                Worker.CALL_START_MILLIS to start.time,
                Worker.CALL_END_MILLIS to end.time,
                Worker.CALL_RING_MILLIS to ringMillis
            )
        ).build()
        WorkManager.getInstance(context).enqueue(request)
    }

}
