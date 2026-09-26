package cn.ppps.forwarder.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
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

    /**
     * 通话记录经 Webhook（JSON body）发送时自动带上 call_uuid，无需修改各手机上的模板配置。
     * - 只处理 JSON 对象；数组 / 文本 / 非法 JSON 原样返回；
     * - 模板里已写了 call_uuid（如 "{{CALL_UUID}}"）则尊重模板，不覆盖；
     * - UUID 为空（拿不到通话记录）不写入，避免服务端唯一索引冲突。
     * 以文本方式追加在末尾，不重新序列化，保证原有字段（数字格式、转义）一字不差。
     */
    fun injectCallUuid(body: String, callUuid: String): String {
        if (callUuid.isEmpty()) return body
        val obj = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            return body
        }
        if (!obj.isJsonObject || obj.asJsonObject.has(FIELD)) return body
        val end = body.lastIndexOf('}')
        if (end < 0) return body
        val field = "\"$FIELD\":" + gson.toJson(callUuid)
        val sep = if (obj.asJsonObject.size() == 0) "" else ","
        return body.substring(0, end).trimEnd() + sep + field + body.substring(end)
    }

    private const val FIELD = "call_uuid"
    private val gson = Gson()
}
