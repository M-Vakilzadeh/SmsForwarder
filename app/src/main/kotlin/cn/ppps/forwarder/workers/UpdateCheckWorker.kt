package cn.ppps.forwarder.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.update.GithubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 每天检查一次 GitHub Releases 是否有更新的版本；有就发一条通知（同一版本只提示一次）。
 * 点通知会打开应用，应用启动时的更新检查会弹出下载并安装的对话框。
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!SettingUtils.autoCheckUpdate) {
                Log.d(TAG, "自动检查更新已关闭，跳过")
                return@withContext Result.success()
            }

            val conn = URL(GithubRelease.LATEST_URL).openConnection() as HttpURLConnection
            val json = try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "SmsForwarder-UpdateCheck")
                if (conn.responseCode != 200) {
                    Log.w(TAG, "检查更新失败：HTTP ${conn.responseCode}")
                    return@withContext Result.retry()
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val info = GithubRelease.parseLatest(json)
            if (info == null || !GithubRelease.isNewer(info.tag, AppUtils.getAppVersionName())) {
                Log.d(TAG, "没有新版本")
                return@withContext Result.success()
            }
            if (SettingUtils.lastNotifiedUpdateTag == info.tag) {
                Log.d(TAG, "版本 ${info.tag} 已提示过")
                return@withContext Result.success()
            }

            notifyNewVersion(info.tag)
            SettingUtils.lastNotifiedUpdateTag = info.tag
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "检查更新异常：${e.message}", e)
            Result.retry()
        }
    }

    private fun notifyNewVersion(tag: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), flags)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_forwarder)
            .setContentTitle(applicationContext.getString(R.string.update_available_title))
            .setContentText(applicationContext.getString(R.string.update_available_text, tag))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, notification)
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val UNIQUE = "update_check"
        private const val CHANNEL_ID = "app_update"
        private const val NOTIFY_ID = 20260921

        //每天一次，仅在有网络时运行；KEEP：重复调用不会重置周期
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
