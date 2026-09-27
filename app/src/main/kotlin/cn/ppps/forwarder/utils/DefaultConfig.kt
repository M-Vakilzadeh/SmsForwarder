package cn.ppps.forwarder.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.workers.NetAlertWorker
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import java.net.URI
import java.util.Date

/**
 * 内置默认配置：assets/default_config.json（「一键换新机」导出的 CloneInfo 结构），
 * 其中所有地址的域名部分写成 [PLACEHOLDER]，首次打开时由用户填写 baseurl 与设备名称后导入。
 *
 * settings 是序列化后的 SharedPreferences，里面的网络告警地址是 URL 编码的，文本替换够不到，
 * 所以还原之后再对 [SettingUtils.netAlertUrl] 单独替换一次。
 *
 * 「是否已完成」存放在独立的 SharedPreferences 文件里，不随配置导入被清空；只有导入成功才标记。
 */
object DefaultConfig {

    private const val TAG = "DefaultConfig"
    const val PLACEHOLDER = "{{BASE_URL}}"
    const val DEFAULT_BASE_URL = "https://n8n.kzmn.ir"
    private const val ASSET = "default_config.json"
    private const val PREFS = "provision_prefs"
    private const val KEY_APPLIED = "default_config_applied"

    private val mainHandler = Handler(Looper.getMainLooper())

    //补全协议、去掉末尾的 /；空白、引号、反斜杠等会破坏 JSON 或不是合法地址的输入返回 null
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
            //Date 字段统一替换为当前时间（与 CloneFragment/ProvisionUtils 导入一致）
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
     * 导入内置配置并写入设备名称。在子线程执行，onResult(success, errorMessage) 在主线程回调。
     * 内置配置随 App 一起发布，不做 compareVersion 校验。
     */
    fun apply(context: Context, baseUrl: String, deviceName: String, onResult: (Boolean, String?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            var err: String? = null
            try {
                val template = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                HttpServerUtils.restoreSettings(parse(applyBaseUrl(template, baseUrl)))
                //restoreSettings 会保留旧的设备名称，这里再覆盖成用户填写的
                SettingUtils.extraDeviceMark = deviceName
                SettingUtils.netAlertUrl = applyBaseUrl(SettingUtils.netAlertUrl, baseUrl)
                NetAlertWorker.schedule(appContext)
                markApplied(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "导入内置配置失败：${e.message}", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val fErr = err
            mainHandler.post { onResult(fErr == null, fErr) }
        }.start()
    }
}
