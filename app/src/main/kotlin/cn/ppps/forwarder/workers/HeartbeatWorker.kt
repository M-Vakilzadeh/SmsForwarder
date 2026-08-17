package cn.ppps.forwarder.workers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.core.Core
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
 * 心跳上报（T7 通道 D，把「静默失败」变成「当天可发现」）。
 *
 * 每 30 分钟上报设备状态：设备标记、App 版本、Android 版本 + 厂商、电量%、充电状态、网络类型、
 * 待发送日志数、最近一次成功投递时间、以及各关键权限的布尔值
 * （READ_CALL_LOG / READ_PHONE_STATE / 是否已加入电池优化白名单）。
 *
 * 「一台没有任何通话的手机」和「一台已经死掉的手机」在今天的系统里看起来一模一样——心跳就是用来区分它们的。
 * 服务端对「超过 N 小时没有心跳」或「待发送数持续上升」的设备告警，就能当天发现问题。
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val setting = WebhookSyncUtils.resolveWebhookSetting()
            if (setting == null) {
                Log.d(TAG, "未配置可用的 Webhook 通道，跳过心跳")
                return@withContext Result.success()
            }
            val url = WebhookSyncUtils.heartbeatUrl(setting)
            val payload = buildPayload(applicationContext)
            return@withContext when (val r = WebhookSyncUtils.postJson(setting, url, Gson().toJson(payload))) {
                is WebhookResult.Success -> Result.success()
                is WebhookResult.RetryableFailure -> {
                    Log.w(TAG, "心跳可重试失败：${r.reason}")
                    Result.retry()
                }
                is WebhookResult.PermanentFailure -> {
                    //4xx：本轮放弃，等下个 30 分钟周期；心跳丢一两次不影响告警判定
                    Log.e(TAG, "心跳永久失败（本轮放弃）：${r.reason}")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "心跳执行异常：${e.message}", e)
            Result.retry()
        }
    }

    private fun buildPayload(context: Context): Map<String, Any> {
        //电量 & 充电状态：读粘性广播
        var batteryPct = -1
        var charging = false
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取电量失败：${e.message}")
        }

        val networkType = try {
            NetworkUtils.getNetStateType().name.removePrefix("NET_")
        } catch (e: Exception) {
            "UNKNOWN"
        }

        //待发送日志数（状态 0/1，不限时间）
        val pendingLogs = try {
            Core.logs.getIdsByTimeAndStatus(0, listOf(0, 1)).size
        } catch (e: Exception) {
            -1
        }

        //isIgnoringBatteryOptimizations 是 API 23+ 才有，minSdk 19，低版本直接跳过（否则 NoSuchMethodError 无法被 catch(Exception) 捕获）
        val ignoreBatteryOptimization = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                false
            }
        } else {
            true //低版本无此限制，视为不受影响
        }

        return mapOf(
            "device_mark" to SettingUtils.extraDeviceMark,
            "app_version" to AppUtils.getAppVersionName(),
            "android_version" to Build.VERSION.RELEASE,
            "android_sdk" to Build.VERSION.SDK_INT,
            "oem" to (Build.MANUFACTURER + " " + Build.MODEL),
            "battery_pct" to batteryPct,
            "charging" to charging,
            "network_type" to networkType,
            "pending_logs" to pendingLogs,
            "last_success_time" to SettingUtils.lastForwardSuccessTime,
            "perm_read_call_log" to hasPermission(context, "android.permission.READ_CALL_LOG"),
            "perm_read_phone_state" to hasPermission(context, "android.permission.READ_PHONE_STATE"),
            "ignore_battery_optimization" to ignoreBatteryOptimization,
            "timestamp" to System.currentTimeMillis()
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val UNIQUE = "heartbeat"
        private const val PERIOD_MINUTES = 30L

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
