package cn.ppps.forwarder.utils

import android.content.Context
import cn.ppps.forwarder.entity.CallNote
import java.io.File

/**
 * 待发送的通话备注队列，持久化在 filesDir 的独立文件里。
 *
 * 不放 SharedPreferences：配置导入会 clearPreference() 清空全部 SP，配置导出也会把 SP 全量带出，
 * 放在 SP 里既会在导入时丢掉用户手写的备注，又会把备注泄露进导出的配置文件。
 * 写入用「临时文件 + rename」，进程中途被杀也不会留下半截 JSON。
 */
object CallNoteStore {

    private const val FILE_NAME = "call_notes_queue.json"
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    fun add(context: Context, note: CallNote) = synchronized(lock) {
        write(context, read(context) + note)
    }

    fun snapshot(context: Context): List<CallNote> = synchronized(lock) { read(context) }

    fun remove(context: Context, ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        write(context, CallNoteUtils.removeByIds(read(context), ids))
    }

    private fun read(context: Context): List<CallNote> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return CallNoteUtils.parseQueue(f.readText())
    }

    private fun write(context: Context, list: List<CallNote>) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(CallNoteUtils.serializeQueue(list))
        if (!tmp.renameTo(f)) {
            //部分旧机型 rename 不能覆盖已存在文件
            f.delete()
            if (!tmp.renameTo(f)) throw IllegalStateException("rename call note queue failed")
        }
    }
}
