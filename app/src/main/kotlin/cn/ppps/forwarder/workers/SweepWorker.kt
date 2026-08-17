package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SendUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 补投兜底（T7 通道 B / Sweep）。
 *
 * 重新投递所有 forward_status ∈ {0(失败), 1(处理中)}、落库超过 2 分钟且在 7 天以内的日志。
 *
 * 为什么要带上「处理中(1)」：Logs.forwardStatus 默认就是 1，任何在投递中途被杀掉的记录
 * 会永远停在 1；而 App 自带的重发任务默认只看状态 0，永远碰不到它们。
 *
 * 触发时机：
 * - 周期任务：每 15 分钟一次（App.onCreate 里作为唯一周期任务入队，人人都有、不需配置、关不掉）；
 * - 网络恢复：NetworkChangeReceiver 检测到重新联网时立即触发一次，不必等下一个周期。
 *
 * 补投走 [SendUtils.retrySendMsg]（复用同一条 logId），webhook 通道会因此重新入队
 * [WebhookDeliveryWorker]（唯一任务，正在重试的不会重复入队）。服务端按幂等键去重，重复投递是安全的。
 */
class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val settleBefore = System.currentTimeMillis() - SETTLE_MILLIS
            //最近 7 天内、状态为 0/1 的日志
            val pending = Core.logs.getIdsByTimeAndStatus(WINDOW_HOURS, listOf(0, 1))
                //只补投「落库已超过 2 分钟」的，避开正在飞行的实时投递
                .filter { it.time.time <= settleBefore }
            Log.i(TAG, "Sweep：待补投 ${pending.size} 条")
            for (log in pending) {
                try {
                    SendUtils.retrySendMsg(log.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Sweep 补投失败 logId=${log.id}：${e.message}")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sweep 执行异常：${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SweepWorker"
        private const val UNIQUE_PERIODIC = "sweep_pending_logs_periodic"
        private const val UNIQUE_ONCE = "sweep_pending_logs_once"

        //补投窗口：最近 7 天
        private const val WINDOW_HOURS = 24 * 7

        //落库满 2 分钟才补投，避免和实时投递撞车
        private const val SETTLE_MILLIS = 2 * 60 * 1000L

        //周期：15 分钟（WorkManager 周期下限即 15 分钟）
        private const val PERIOD_MINUTES = 15L

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * 作为唯一周期任务入队。KEEP：已存在则沿用原有排程（跨进程/重启存活）。
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SweepWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /**
         * 立即补投一次（网络恢复时调用）。KEEP：已有待执行的一次性补投则不重复入队，避免联网抖动刷屏。
         */
        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SweepWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE, ExistingWorkPolicy.KEEP, request
            )
        }
    }
}
