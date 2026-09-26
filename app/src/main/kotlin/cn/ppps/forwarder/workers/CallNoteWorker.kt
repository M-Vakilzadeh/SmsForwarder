package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.entity.CallNote
import cn.ppps.forwarder.entity.setting.WebhookSetting
import cn.ppps.forwarder.utils.CALL_NOTE_SEND_BATCH
import cn.ppps.forwarder.utils.CALL_NOTE_SEND_REALTIME
import cn.ppps.forwarder.utils.CallNoteStore
import cn.ppps.forwarder.utils.CallNoteUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.sender.WebhookResult
import cn.ppps.forwarder.utils.sender.WebhookSyncUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 把队列里的通话备注发往用户设置的 URL。
 * - 立即发送模式：每条备注单独 POST（{type:"call_note", ...}）；
 * - 批量模式：按周期把队列打包成 {type:"call_note_batch", notes:[...]}，每包最多 BATCH_MAX 条。
 *
 * 只在 HTTP 200（WebhookResult.Success）后才从队列删除。备注是用户手写的、无法重建，
 * 所以 4xx 等永久失败也不删，只记日志，等下次发送（修好地址/服务端后自动补上）。
 */
class CallNoteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val url = SettingUtils.callNoteUrl.trim()
            if (!SettingUtils.callNoteEnabled || url.isEmpty()) {
                Log.d(TAG, "通话备注未开启或地址为空，跳过（队列保留）")
                return@withContext Result.success()
            }
            val queue = CallNoteStore.snapshot(applicationContext)
            if (queue.isEmpty()) return@withContext Result.success()

            val setting = WebhookSetting(webServer = url)
            val notes = queue.map { enrich(it) }
            val batch = SettingUtils.callNoteSendMode == CALL_NOTE_SEND_BATCH
            val chunks = if (batch) notes.chunked(BATCH_MAX) else notes.map { listOf(it) }

            var retry = false
            for (chunk in chunks) {
                val body = if (batch) CallNoteUtils.batchPayload(SettingUtils.extraDeviceMark, chunk)
                else CallNoteUtils.singlePayload(chunk[0])
                when (val r = WebhookSyncUtils.postJson(setting, url, CallNoteUtils.toJson(body))) {
                    is WebhookResult.Success -> {
                        CallNoteStore.remove(applicationContext, chunk.map { it.noteId }.toSet())
                        Log.i(TAG, "通话备注已发送 ${chunk.size} 条")
                    }
                    is WebhookResult.RetryableFailure -> {
                        Log.w(TAG, "通话备注发送可重试失败：${r.reason}")
                        retry = true
                        break
                    }
                    //保留在队列里，不丢用户手写内容
                    is WebhookResult.PermanentFailure -> Log.e(TAG, "通话备注发送被拒（保留在队列，等下次）：${r.reason}")
                }
            }
            if (retry) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "通话备注发送异常：${e.message}", e)
            Result.retry()
        }
    }

    //发送前尽量从通话记录补齐运营商时间戳与通话时长，便于服务端把备注关联到主通道的通话记录
    private fun enrich(note: CallNote): CallNote {
        if (note.callDate > 0) return note
        return try {
            val info = PhoneUtils.getLastCallInfo(note.callType, note.number.ifBlank { null })
                ?.takeIf { CallNoteUtils.matchesCall(it.dateLong, note.callStart, note.callEnd) }
                ?: return note
            note.copy(callDate = info.dateLong, callDuration = info.duration)
        } catch (e: Exception) {
            Log.w(TAG, "补齐通话记录失败：${e.message}")
            note
        }
    }

    companion object {
        private const val TAG = "CallNoteWorker"
        private const val UNIQUE_FLUSH = "call_note_flush"
        private const val UNIQUE_PERIODIC = "call_note_batch"
        private const val MIN_PERIOD_MINUTES = 15L
        private const val BATCH_MAX = 50

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * 立即（有网时）发送一次队列。APPEND_OR_REPLACE：发送进行中新写的备注排在其后再发一轮，不会被 KEEP 吞掉。
         */
        fun flushNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallNoteWorker>()
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        //新写了一条备注：立即模式马上发，批量模式等周期任务
        fun onNoteQueued(context: Context) {
            if (SettingUtils.callNoteSendMode == CALL_NOTE_SEND_REALTIME) flushNow(context)
        }

        //网络恢复：立即模式下补发积压（批量模式由周期任务负责）
        fun onReconnect(context: Context) {
            if (!SettingUtils.callNoteEnabled || SettingUtils.callNoteSendMode != CALL_NOTE_SEND_REALTIME) return
            if (CallNoteStore.snapshot(context).isNotEmpty()) flushNow(context)
        }

        /**
         * 按当前设置（重新）安排批量发送任务：未开启/地址为空/立即模式 → 取消周期任务。
         * 立即模式下顺带补发一次积压（例如刚从批量切回立即）。
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val enabled = SettingUtils.callNoteEnabled && SettingUtils.callNoteUrl.isNotBlank()
            if (!enabled || SettingUtils.callNoteSendMode != CALL_NOTE_SEND_BATCH) {
                wm.cancelUniqueWork(UNIQUE_PERIODIC)
                if (enabled && CallNoteStore.snapshot(context).isNotEmpty()) flushNow(context)
                return
            }
            val period = SettingUtils.callNoteBatchInterval.toLong().coerceAtLeast(MIN_PERIOD_MINUTES)
            val request = PeriodicWorkRequestBuilder<CallNoteWorker>(period, TimeUnit.MINUTES)
                .setConstraints(connected)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
            Log.d(TAG, "已安排通话备注批量发送，period=${period}min")
        }

        /**
         * 立即向指定地址发一条测试备注，返回 (是否成功, 说明)。必须在子线程调用。
         */
        fun sendTest(url: String): Pair<Boolean, String> {
            return try {
                val now = System.currentTimeMillis()
                val note = CallNote(
                    noteId = "test-$now", deviceMark = SettingUtils.extraDeviceMark, number = "",
                    contactName = "", callType = 2, callStart = now, callEnd = now, note = "test", createdAt = now,
                )
                val payload = if (SettingUtils.callNoteSendMode == CALL_NOTE_SEND_BATCH) {
                    CallNoteUtils.batchPayload(SettingUtils.extraDeviceMark, listOf(note))
                } else {
                    CallNoteUtils.singlePayload(note)
                } + ("test" to true)
                when (val r = WebhookSyncUtils.postJson(WebhookSetting(webServer = url), url, CallNoteUtils.toJson(payload))) {
                    is WebhookResult.Success -> true to ("HTTP 200 " + r.body.take(120))
                    is WebhookResult.RetryableFailure -> false to r.reason
                    is WebhookResult.PermanentFailure -> false to r.reason
                }
            } catch (e: Exception) {
                false to (e.message ?: "exception")
            }
        }
    }
}
