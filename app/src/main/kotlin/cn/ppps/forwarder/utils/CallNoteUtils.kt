package cn.ppps.forwarder.utils

import cn.ppps.forwarder.entity.CallNote
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 通话备注的纯逻辑（无 Android 依赖，便于单测）：弹窗判定、队列序列化、上报报文。
 */
object CallNoteUtils {

    //通话记录时间戳允许比广播观测到的开始时刻略早（拨号器与系统时钟的偏差）
    private const val CLOCK_TOLERANCE_MILLIS = 5_000L

    private val gson = Gson()

    //只在真正通过话的电话后弹窗：1=来电挂机 2=去电挂机；未接来电没有可写的内容
    fun shouldPrompt(callType: Int): Boolean = callType == 1 || callType == 2

    fun callTypeName(callType: Int): String = when (callType) {
        1 -> "incoming"
        2 -> "outgoing"
        else -> "unknown"
    }

    fun parseQueue(json: String?): List<CallNote> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<CallNote>>(json, object : TypeToken<List<CallNote>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeQueue(list: List<CallNote>): String = gson.toJson(list)

    //只移除已确认送达的；发送期间新加入队列的备注保留
    fun removeByIds(list: List<CallNote>, ids: Set<String>): List<CallNote> = list.filter { it.noteId !in ids }

    //通话记录条目是否属于这通电话：时间戳落在 [开始-容差, 结束]
    fun matchesCall(logDate: Long, callStart: Long, callEnd: Long): Boolean =
        logDate >= callStart - CLOCK_TOLERANCE_MILLIS && logDate <= callEnd

    fun singlePayload(note: CallNote): Map<String, Any?> = linkedMapOf(
        "type" to "call_note",
        "note_id" to note.noteId,
        "device_mark" to note.deviceMark,
        "number" to note.number,
        "contact_name" to note.contactName,
        "call_type" to note.callType,
        "call_type_name" to callTypeName(note.callType),
        "call_start" to note.callStart,
        "call_end" to note.callEnd,
        "call_date" to note.callDate,
        "call_duration" to note.callDuration,
        "note" to note.note,
        "created_at" to note.createdAt,
    )

    fun batchPayload(deviceMark: String, notes: List<CallNote>): Map<String, Any?> = linkedMapOf(
        "type" to "call_note_batch",
        "device_mark" to deviceMark,
        "count" to notes.size,
        "notes" to notes.map { singlePayload(it) },
    )

    fun toJson(payload: Map<String, Any?>): String = gson.toJson(payload)
}
