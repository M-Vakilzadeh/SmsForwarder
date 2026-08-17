package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.ppps.forwarder.database.entity.Rule
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.entity.setting.WebhookSetting
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SendUtils
import cn.ppps.forwarder.utils.Worker
import cn.ppps.forwarder.utils.sender.WebhookResult
import cn.ppps.forwarder.utils.sender.WebhookSyncUtils
import com.google.gson.Gson
import com.xuexiang.xutil.XUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Webhook 持久化投递（T4，最高优先级）。
 *
 * 原路径 [cn.ppps.forwarder.utils.sender.WebhookUtils.sendMsg] 以异步 RxJava 发送，
 * SendWorker.doWork() 在请求完成前就返回了 success，WorkManager 的重试与 wakelock 都覆盖不到，
 * 一次 POST 失败即永久丢失。
 *
 * 改由本 Worker 同步发送 [WebhookSyncUtils.send]：
 * - 成功：写日志为成功，继续发送通道逻辑；
 * - 可重试失败（超时/IO/5xx/200 但校验串不匹配）：Result.retry()，交给 WorkManager 指数退避重试；
 * - 永久失败（4xx / 配置错误）：写日志为失败，Result.failure()。
 *
 * WorkManager 会把任务持久化到自己的库里，网络不可用时一直挂着，可用后重试，且能跨重启存活，
 * 从而去掉「电话结束的那一刻 App 进程必须活着」这一根本前提 —— 也就是多日静默丢失的真正根因。
 */
class WebhookDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settingJson = inputData.getString(Worker.WEBHOOK_SETTING)
        val msgInfoJson = inputData.getString(Worker.SEND_MSG_INFO)
        val ruleJson = inputData.getString(Worker.RULE)
        val senderIndex = inputData.getInt(Worker.SENDER_INDEX, 0)
        val logId = inputData.getLong(Worker.LOG_ID, 0L)
        val msgId = inputData.getLong(Worker.MSG_ID, 0L)

        if (settingJson.isNullOrBlank() || msgInfoJson.isNullOrBlank()) {
            Log.e(TAG, "输入数据缺失，settingJson/msgInfoJson 为空")
            return@withContext Result.failure()
        }

        val setting: WebhookSetting
        val msgInfo: MsgInfo
        val rule: Rule?
        try {
            setting = Gson().fromJson(settingJson, WebhookSetting::class.java)
            msgInfo = Gson().fromJson(msgInfoJson, MsgInfo::class.java)
            rule = if (ruleJson.isNullOrBlank()) null else Gson().fromJson(ruleJson, Rule::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "反序列化失败：${e.message}", e)
            return@withContext Result.failure()
        }

        Log.d(TAG, "webhook 投递开始，logId=$logId, msgId=$msgId, senderIndex=$senderIndex, runAttemptCount=$runAttemptCount")

        when (val result = WebhookSyncUtils.send(setting, msgInfo, rule)) {
            is WebhookResult.Success -> {
                SendUtils.updateLogs(logId, 2, result.body)
                SendUtils.senderLogic(2, msgInfo, rule, senderIndex, msgId)
                Result.success()
            }

            is WebhookResult.RetryableFailure -> {
                //保持「处理中」状态，交给 WorkManager 退避重试；不触发发送通道 fail 逻辑，避免过早失败切换
                Log.w(TAG, "webhook 可重试失败，将由 WorkManager 重试：${result.reason}")
                SendUtils.updateLogs(logId, 1, result.reason)
                Result.retry()
            }

            is WebhookResult.PermanentFailure -> {
                Log.e(TAG, "webhook 永久失败：${result.reason}")
                SendUtils.updateLogs(logId, 0, result.reason)
                SendUtils.senderLogic(0, msgInfo, rule, senderIndex, msgId)
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "WebhookDeliveryWorker"

        /**
         * 入队一次持久化投递。
         * 仅在网络可用时执行，失败按 30s 起步的指数退避重试。
         */
        fun enqueue(
            settingJson: String,
            msgInfo: MsgInfo,
            rule: Rule?,
            senderIndex: Int,
            logId: Long,
            msgId: Long
        ) {
            val request = OneTimeWorkRequestBuilder<WebhookDeliveryWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        Worker.WEBHOOK_SETTING to settingJson,
                        Worker.SEND_MSG_INFO to Gson().toJson(msgInfo),
                        Worker.RULE to (rule?.let { Gson().toJson(it) } ?: ""),
                        Worker.SENDER_INDEX to senderIndex,
                        Worker.LOG_ID to logId,
                        Worker.MSG_ID to msgId
                    )
                )
                .build()
            val wm = WorkManager.getInstance(XUtil.getContext())
            if (logId > 0) {
                //按 logId 做唯一投递：一条日志同一时间只保留一个投递任务，正在重试(ENQUEUED/RUNNING)时
                //Sweep 的重复入队会被 KEEP 忽略，避免长时间断网时同一条日志堆积出大量并行 Worker；
                //而任务终态(成功/永久失败)后，后续 Sweep 仍可重新入队补投。
                wm.enqueueUniqueWork("webhook_delivery_$logId", ExistingWorkPolicy.KEEP, request)
            } else {
                wm.enqueue(request)
            }
        }
    }
}
