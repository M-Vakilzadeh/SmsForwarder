package cn.ppps.forwarder.utils

import java.util.UUID

/**
 * 通话的稳定标识 call_uuid：同一通电话无论走哪条通道（实时 /call、每日汇总、对账 /call/batch、重发、通话备注），
 * 算出来都完全一致，服务端可直接按 call_uuid 把通话记录和通话备注合并。
 *
 * 不能用随机 UUID：各通道各自生成会得到不同值。这里用基于名称的 UUID（v3）：
 *   UUID.nameUUIDFromBytes("smsf-call|<device_mark 去首尾空格>|<通话记录 date 毫秒>")
 * 通话记录的 date 是运营商时间戳，同一台设备上一毫秒内只会有一通电话。
 * 【算法一旦上线不要改】改了新旧数据就合并不上。
 */
object CallIdentity {

    //通话时间未知（拿不到通话记录）时返回空串，服务端应视为「待对账补齐」
    fun callUuid(deviceMark: String, callDateMillis: Long): String {
        if (callDateMillis <= 0L) return ""
        val name = "smsf-call|${deviceMark.trim()}|$callDateMillis"
        return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
    }
}
