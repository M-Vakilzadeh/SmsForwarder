package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import java.util.Date

@Suppress("DEPRECATION")
abstract class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return

        //总开关
        if (!SettingUtils.enablePhone) return

        //We listen to two intents.  The new outgoing call only tells us of an outgoing call.  We use it to get the number.
        for (key in intent.extras!!.keySet()) {
            val value = intent.extras!!.get(key)
            Log.d(TAG, "EXTRA [$key] = $value")
        }
        if (intent.action == CallReceiver.ACTION_OUT) {
            //去电号码仅作为号码的补充来源，取不到时不能覆盖已捕获的号码
            val outgoingNumber = intent.extras!!.getString(CallReceiver.EXTRA_PHONE_NUMBER)
            if (!outgoingNumber.isNullOrBlank()) savedNumber = outgoingNumber
            Log.d(TAG, "savedNumber：$savedNumber")
        } else {
            val stateStr = intent.extras!!.getString(TelephonyManager.EXTRA_STATE)
            val number = intent.extras!!.getString(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Log.d(TAG, "stateStr：$stateStr，number：$number，savedNumber：$savedNumber")
            var state = 0

            //遍历intent.extras的所有key，打印出内容
            for (key in intent.extras!!.keySet()) {
                Log.d(TAG, "key：$key，value：${intent.extras!!.get(key)}")
            }

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_IDLE -> state = TelephonyManager.CALL_STATE_IDLE
                TelephonyManager.EXTRA_STATE_OFFHOOK -> state = TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_RINGING -> state = TelephonyManager.CALL_STATE_RINGING
            }
            onCallStateChanged(context, state, number)
        }
    }

    //Derived classes should override these to respond to specific events of interest
    protected abstract fun onIncomingCallReceived(context: Context, number: String?, start: Date)

    protected abstract fun onIncomingCallAnswered(context: Context, number: String?, start: Date)

    protected abstract fun onIncomingCallEnded(context: Context, number: String?, start: Date, end: Date)

    protected abstract fun onOutgoingCallStarted(context: Context, number: String?, start: Date)

    protected abstract fun onOutgoingCallEnded(context: Context, number: String?, start: Date, end: Date)

    protected abstract fun onMissedCall(context: Context, number: String?, start: Date)

    //Deals with actual events

    //Incoming call-  goes from IDLE to RINGING when it rings, to OFFHOOK when it's answered, to IDLE when its hung up
    //Outgoing call-  goes from IDLE to OFFHOOK when it dials out, to IDLE when hung up
    private fun onCallStateChanged(context: Context, state: Int, number: String?) {
        //Android 9+ 同一次状态变化会广播两次：一次带 EXTRA_INCOMING_NUMBER（需要 READ_CALL_LOG），一次不带。
        //所以只在拿到非空号码时才覆盖，避免刚捕获到的号码被后一条广播置空。
        if (!number.isNullOrBlank()) savedNumber = number

        //仅根据状态去重：IDLE 广播通常不带号码，若因号码为空而提前返回，挂机事件会被整个吞掉，
        //lastState 会永远停在 OFFHOOK，导致后续每一通电话都不再转发，直到进程重启。
        if (lastState == state) {
            //No change, debounce extras
            return
        }

        val previousState = lastState
        //先更新状态再触发回调，回调耗时期间到达的重复广播才能被正确去重
        lastState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isIncoming = true
                callStartTime = Date()

                onIncomingCallReceived(context, savedNumber, callStartTime)
            }

            TelephonyManager.CALL_STATE_OFFHOOK ->
                //Transition of ringing->offhook are pickups of incoming calls.  Nothing done on them
                if (previousState != TelephonyManager.CALL_STATE_RINGING) {
                    isIncoming = false
                    callStartTime = Date()

                    onOutgoingCallStarted(context, savedNumber, callStartTime)
                } else {
                    isIncoming = true
                    callStartTime = Date()

                    onIncomingCallAnswered(context, savedNumber, callStartTime)
                }

            TelephonyManager.CALL_STATE_IDLE -> {
                //Went to idle-  this is the end of a call.  What type depends on previous state(s)
                if (previousState == TelephonyManager.CALL_STATE_RINGING) {
                    //Ring but no pickup
                    onMissedCall(context, savedNumber, callStartTime)
                } else if (isIncoming) {
                    onIncomingCallEnded(context, savedNumber, callStartTime, Date())
                } else {
                    onOutgoingCallEnded(context, savedNumber, callStartTime, Date())
                }

                //本次通话已结束，清空号码，避免残留号码污染下一通电话
                savedNumber = null
            }
        }
    }

    companion object {
        private val TAG = PhoneStateReceiver::class.java.simpleName

        //The receiver will be recreated whenever android feels like it.  We need a static variable to remember data between instantiations
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callStartTime: Date = Date()
        private var isIncoming: Boolean = false
        private var savedNumber: String? = null  //because the passed incoming is only valid in ringing
    }
}
