package cn.ppps.forwarder.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.workers.CallNoteWorker
import cn.ppps.forwarder.workers.DailyForwardWorker
import cn.ppps.forwarder.workers.NetAlertWorker
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import java.net.URI
import java.util.Calendar
import java.util.Date

/**
 * Bundled default config: assets/default_config.json (the CloneInfo format exported by "clone").
 * Every URL host is written as [PLACEHOLDER]; on first launch the user enters the base URL and device name.
 *
 * `settings` is serialized SharedPreferences whose alert/call-note URLs are URL-encoded, so plain text
 * replacement cannot reach them: [SettingUtils.netAlertUrl] and [SettingUtils.callNoteUrl] are rebased after restore.
 * The daily forwarding time is not in the config: it is the moment of first setup, so devices are
 * spread out and the server is not hit by all of them at once.
 *
 * The "done" flag lives in a separate SharedPreferences file so an import cannot clear it; set only on success.
 */
object DefaultConfig {

    private const val TAG = "DefaultConfig"
    const val PLACEHOLDER = "{{BASE_URL}}"
    private const val ASSET = "default_config.json"
    private const val PREFS = "provision_prefs"
    private const val KEY_APPLIED = "default_config_applied"

    private val mainHandler = Handler(Looper.getMainLooper())

    //Adds a scheme and strips trailing slashes; returns null for input that is not a valid URL or would break the JSON
    fun normalizeBaseUrl(input: String): String? {
        var url = input.trim()
        if (url.isEmpty() || url.any { it.isWhitespace() || it == '"' || it == '\\' }) return null
        if (!url.contains("://")) url = "https://$url"
        url = url.trimEnd('/')
        return try {
            val uri = URI(url)
            if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrEmpty()) null else url
        } catch (e: Exception) {
            null
        }
    }

    fun applyBaseUrl(text: String, baseUrl: String): String = text.replace(PLACEHOLDER, baseUrl)

    fun parse(json: String): CloneInfo {
        val gson = GsonBuilder()
            //Replace every Date field with now (same as the CloneFragment import)
            .registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
            .create()
        return gson.fromJson(json, CloneInfo::class.java)
    }

    fun isApplied(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_APPLIED, false)

    fun markApplied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_APPLIED, true).apply()
    }

    /**
     * Imports the bundled config and sets the device name. Runs on a worker thread; onResult(success, errorMessage)
     * is called on the main thread. The config ships with the app, so compareVersion is skipped.
     */
    fun apply(context: Context, baseUrl: String, deviceName: String, onResult: (Boolean, String?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            var err: String? = null
            try {
                val template = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                HttpServerUtils.restoreSettings(parse(applyBaseUrl(template, baseUrl)))
                //restoreSettings keeps the old device name; overwrite it with the one the user entered
                SettingUtils.extraDeviceMark = deviceName
                SettingUtils.netAlertUrl = applyBaseUrl(SettingUtils.netAlertUrl, baseUrl)
                SettingUtils.callNoteUrl = applyBaseUrl(SettingUtils.callNoteUrl, baseUrl)
                val now = Calendar.getInstance()
                SettingUtils.dailyForwardTime = DailyTime.slotOf(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
                NetAlertWorker.schedule(appContext)
                CallNoteWorker.schedule(appContext)
                DailyForwardWorker.schedule(appContext)
                //The process restarts next: commit() flushes the apply()-ed settings to disk so they are not lost on exit
                SharedPreference.preference.edit().commit()
                markApplied(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Importing the bundled config failed: ${e.message}", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val fErr = err
            mainHandler.post { onResult(fErr == null, fErr) }
        }.start()
    }
}
