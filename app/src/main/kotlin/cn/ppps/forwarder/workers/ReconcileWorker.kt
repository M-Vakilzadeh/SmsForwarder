package cn.ppps.forwarder.workers

import cn.ppps.forwarder.utils.CallIdentity
import android.annotation.SuppressLint
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.entity.CallInfo
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.sender.WebhookResult
import cn.ppps.forwarder.utils.sender.WebhookSyncUtils
import com.google.gson.Gson
import com.xuexiang.xutil.security.CipherUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 通话记录对账（T7 通道 C，全套方案里最有价值的一环）。
 *
 * 直接读通话记录数据库最近 72 小时的记录，打成紧凑 JSON 批量 POST 到 /call/batch。
 * 这条通道完全不依赖广播接收器：即便捕获整段失效（权限被撤、进程被强杀两天、某个没预料到的 OEM 怪癖），
 * 只要 App 在 6 小时内跑过一次，数据仍能到账。
 *
 * 每行带一个与实时 /call 一致的幂等键 md5([device_mark]+[from]+[receive_time]+[org_content])，
 * 服务端据此跨通道去重（前提：服务端对 /call 用同样的公式、同样的字段拼接）。
 * 每批 ≤200 行，沿用 T4 的持久化 Worker 模式（NetworkType.CONNECTED + 电量低也执行）。
 */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @SuppressLint("SimpleDateFormat")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val setting = WebhookSyncUtils.resolveWebhookSetting()
            if (setting == null) {
                Log.d(TAG, "未配置可用的 Webhook 通道，跳过对账")
                return@withContext Result.success()
            }
            val url = WebhookSyncUtils.batchUrl(setting)
            val deviceMark = SettingUtils.extraDeviceMark

            val windowStart = System.currentTimeMillis() - WINDOW_MILLIS
            val calls = PhoneUtils.getCallInfoList(0, MAX_ROWS, 0, null).filter { it.dateLong >= windowStart }
            if (calls.isEmpty()) {
                Log.i(TAG, "对账：窗口内无通话记录")
                return@withContext Result.success()
            }
            if (calls.size >= MAX_ROWS) Log.w(TAG, "对账通话记录达到读取上限 $MAX_ROWS，更早的未纳入")

            val rows = calls.map { toRow(it, deviceMark) }
            var anyRetryable = false
            for (chunk in rows.chunked(CHUNK_SIZE)) {
                val payload = mapOf(
                    "device_mark" to deviceMark,
                    "count" to chunk.size,
                    "calls" to chunk
                )
                when (val r = WebhookSyncUtils.postJson(setting, url, Gson().toJson(payload))) {
                    is WebhookResult.Success -> Log.i(TAG, "对账：${chunk.size} 行已上报")
                    is WebhookResult.RetryableFailure -> {
                        Log.w(TAG, "对账批次可重试失败：${r.reason}")
                        anyRetryable = true
                    }
                    is WebhookResult.PermanentFailure -> {
                        //4xx：格式/配置问题，重试无意义；下个 6 小时周期再试，别把 Worker 卡在无限重试
                        Log.e(TAG, "对账批次永久失败（本轮放弃，等下个周期）：${r.reason}")
                    }
                }
            }
            if (anyRetryable) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "对账执行异常：${e.message}", e)
            Result.retry()
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun toRow(callInfo: CallInfo, deviceMark: String): Map<String, Any> {
        val orgContent = PhoneUtils.getCallMsg(callInfo)
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(callInfo.dateLong))
        //与实时 /call 的服务端计算保持一致：md5([device_mark]+[from]+[receive_time]+[org_content])
        val idempotencyKey = CipherUtils.md5(deviceMark + callInfo.number + dateStr + orgContent)
        return mapOf(
            "number" to callInfo.number,
            "date" to callInfo.dateLong,
            "date_str" to dateStr,
            "duration" to callInfo.duration,
            "type" to callInfo.type,
            "sim_slot" to callInfo.simId,
            "idempotency_key" to idempotencyKey,
            //与实时 /call 的 {{CALL_UUID}}、通话备注的 call_uuid 同一公式
            "call_uuid" to CallIdentity.callUuid(deviceMark, callInfo.dateLong)
        )
    }

    companion object {
        private const val TAG = "ReconcileWorker"
        private const val UNIQUE = "reconcile_calllog"

        //对账窗口：最近 72 小时
        private const val WINDOW_MILLIS = 72L * 60 * 60 * 1000

        //单次读取上限
        private const val MAX_ROWS = 2000

        //每批行数上限
        private const val CHUNK_SIZE = 200

        //周期：6 小时
        private const val PERIOD_HOURS = 6L

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
