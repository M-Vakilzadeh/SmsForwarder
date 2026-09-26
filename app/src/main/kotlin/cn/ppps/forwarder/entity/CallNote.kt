package cn.ppps.forwarder.entity

/**
 * 通话备注：每通电话结束后，用户在弹窗里写下的说明（可为空），发往单独配置的 URL。
 *
 * callStart/callEnd 为广播观测到的起止时刻；callDate/callDuration 在发送前尽量从通话记录补齐
 * （callDate 即运营商时间戳，与主通道幂等键中的 receive_time 一致，服务端据此把备注关联到通话）。
 */
data class CallNote(
    //幂等键：重试/批量重发时服务端据此去重
    val noteId: String,
    val deviceMark: String,
    val number: String,
    val contactName: String,
    //1=来电 2=去电
    val callType: Int,
    val callStart: Long,
    val callEnd: Long,
    val note: String,
    val createdAt: Long,
    //通话记录里的时间戳（0=未匹配到）
    val callDate: Long = 0L,
    //通话记录里的通话秒数（-1=未匹配到）
    val callDuration: Int = -1,
)
