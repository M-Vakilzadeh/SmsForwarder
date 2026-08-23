package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.entity.setting.WebhookSetting
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.sender.WebhookResult
import cn.ppps.forwarder.utils.sender.WebhookSyncUtils
import com.google.gson.Gson
import com.xuexiang.xutil.net.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 网络状态告警（设置里可开关）：按用户设定的周期，检查本机网络连接情况，并调用用户设定的 webhook 上报。
 *
 * 用途：让服务端/用户端据此判断设备是否还在线。收到上报=设备联网正常；连续多次收不到=可能断网或掉线，触发告警。
 * 不加 NetworkType.CONNECTED 约束：到点就跑，断网时 POST 自然失败——「收不到」本身就是告警信号。
 */
class NetAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val url = SettingUtils.netAlertUrl.trim()
            if (!SettingUtils.netAlertEnabled || url.isEmpty()) {
                Log.d(TAG, "网络告警未开启或地址为空，跳过")
                return@withContext Result.success()
            }

            val netType = try {
                NetworkUtils.getNetStateType().name.removePrefix("NET_")
            } catch (e: Exception) {
                "UNKNOWN"
            }
            val connected = netType != "NO" && netType != "UNKNOWN"

            val payload = mapOf(
                "device_mark" to SettingUtils.extraDeviceMark,
                "app_version" to AppUtils.getAppVersionName(),
                "connected" to connected,
                "network_type" to netType,
                "timestamp" to System.currentTimeMillis()
            )

            //postJson 用 setting 提供代理/证书/超时；这里用默认 setting，请求地址由 url 决定
            val setting = WebhookSetting(webServer = url)
            when (val r = WebhookSyncUtils.postJson(setting, url, Gson().toJson(payload))) {
                is WebhookResult.Success -> Log.i(TAG, "网络告警已上报，connected=$connected")
                is WebhookResult.RetryableFailure -> {
                    Log.w(TAG, "网络告警上报可重试失败：${r.reason}")
                    return@withContext Result.retry()
                }
                is WebhookResult.PermanentFailure -> Log.e(TAG, "网络告警上报永久失败（等下个周期）：${r.reason}")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "网络告警执行异常：${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NetAlertWorker"
        private const val UNIQUE = "net_alert"
        private const val MIN_PERIOD_MINUTES = 15L

        /**
         * 立即向指定地址发一条测试上报，返回 (是否成功, 说明)。必须在子线程调用。
         * 与定时上报走同一套构造与校验逻辑，测试通过即代表正式上报也能通。
         */
        fun sendTest(url: String): Pair<Boolean, String> {
            return try {
                val netType = try {
                    NetworkUtils.getNetStateType().name.removePrefix("NET_")
                } catch (e: Exception) {
                    "UNKNOWN"
                }
                val payload = mapOf(
                    "device_mark" to SettingUtils.extraDeviceMark,
                    "app_version" to AppUtils.getAppVersionName(),
                    "connected" to (netType != "NO" && netType != "UNKNOWN"),
                    "network_type" to netType,
                    "timestamp" to System.currentTimeMillis(),
                    "test" to true
                )
                val setting = WebhookSetting(webServer = url)
                when (val r = WebhookSyncUtils.postJson(setting, url, Gson().toJson(payload))) {
                    is WebhookResult.Success -> true to ("HTTP 200 " + r.body.take(120))
                    is WebhookResult.RetryableFailure -> false to r.reason
                    is WebhookResult.PermanentFailure -> false to r.reason
                }
            } catch (e: Exception) {
                false to (e.message ?: "exception")
            }
        }

        /**
         * 按当前设置（重新）安排网络告警任务。未开启/地址为空则取消。
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!SettingUtils.netAlertEnabled || SettingUtils.netAlertUrl.isBlank()) {
                wm.cancelUniqueWork(UNIQUE)
                Log.d(TAG, "网络告警未开启，已取消任务")
                return
            }
            val period = SettingUtils.netAlertInterval.toLong().coerceAtLeast(MIN_PERIOD_MINUTES)
            val request = PeriodicWorkRequestBuilder<NetAlertWorker>(period, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
            Log.d(TAG, "已安排网络告警任务，period=${period}min")
        }
    }
}
