package cn.ppps.forwarder.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.ppps.forwarder.entity.CloneInfo
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 装机引导：从指定 URL 下载配置 JSON 并一键导入，加速批量装机。
 *
 * 配置 JSON 就是「一键换新机」导出的 SmsForwarder.json（即 CloneInfo 结构），
 * 复用 [HttpServerUtils.restoreSettings] 做完整还原（设置 + 发送通道 + 转发规则 + 任务）。
 *
 * 网络请求直接用 OkHttp（不走 XHttp 全局配置），自己掌握超时：
 * n8n 这类工作流的 Webhook 常配成 responseMode=responseNode，
 * 要等整条流程跑到「Respond to Webhook」节点才会返回，
 * 因此读取超时给足 [READ_TIMEOUT_SECONDS] 秒，避免还没等到响应就被判定失败。
 *
 * 「是否已提示过」存放在独立的 SharedPreferences 文件里，不随配置导入被清空/覆盖，避免导入后又反复弹窗。
 */
object ProvisionUtils {

    private const val TAG = "ProvisionUtils"
    private const val PREFS = "provision_prefs"
    private const val KEY_PROMPTED = "config_import_prompted"

    //等待服务端响应的时间：要覆盖 n8n 工作流跑完并到达 Respond to Webhook 节点所需的时间
    private const val READ_TIMEOUT_SECONDS = 120L
    private const val CONNECT_TIMEOUT_SECONDS = 30L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /**
     * 兼容多种服务端返回形态，解析出 CloneInfo：
     * - 直接是对象：{...}
     * - 被数组包了一层：[{...}]  —— 例如 n8n 的 Convert to File(toJson) 会把条目写成数组
     * - 被包在 data/result 字段里：{"data":{...}} / {"result":{...}}
     *
     * 这样服务端怎么返回都能吃下，不必强求某一种格式。
     */
    private fun parseCloneInfo(response: String): CloneInfo? {
        val gson = GsonBuilder()
            //Date 字段统一替换为当前时间，避免旧时间戳影响（与 CloneFragment 导入一致）
            .registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
            .create()

        var element = JsonParser.parseString(response.trim())
        //数组包了一层：取第一个元素
        if (element.isJsonArray) {
            val arr = element.asJsonArray
            if (arr.size() == 0) return null
            element = arr[0]
        }
        if (!element.isJsonObject) return null

        var obj: JsonObject = element.asJsonObject
        //包在 data/result 里：往里再取一层（识别标志是缺少 version_code）
        if (!obj.has("version_code")) {
            for (key in listOf("data", "result")) {
                val inner = obj.get(key)
                if (inner != null && inner.isJsonObject && inner.asJsonObject.has("version_code")) {
                    obj = inner.asJsonObject
                    break
                }
            }
        }
        return gson.fromJson(obj, CloneInfo::class.java)
    }

    //同步 GET，必须在子线程调用
    private fun httpGet(url: String): String {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            //关键：等待服务端工作流返回（responseMode=responseNode）
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
        //忽略 https 证书（与其余请求路径保持一致）
        try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAll, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w(TAG, "配置忽略 https 证书失败：${e.message}")
        }

        val request = Request.Builder().url(url).get().build()
        builder.build().newCall(request).execute().use { response ->
            val body = response.body()?.string() ?: ""
            if (response.code() != 200) {
                throw IOException("HTTP ${response.code()}：${body.take(200)}")
            }
            if (body.isBlank()) throw IOException("响应内容为空")
            return body
        }
    }

    /**
     * 仅下载并校验配置是否可用，不做导入。用于设置页的「测试」按钮。
     * onResult(success, message) 在主线程回调：成功时 message 为配置摘要，失败时为原因。
     */
    fun testConfigUrl(url: String, onResult: (Boolean, String) -> Unit) {
        Thread {
            val result: Pair<Boolean, String> = try {
                val body = httpGet(url)
                val cloneInfo = parseCloneInfo(body)
                if (cloneInfo == null) {
                    false to "无法解析为配置文件(CloneInfo)"
                } else {
                    true to ("v${cloneInfo.versionName ?: "?"}(${cloneInfo.versionCode}), " +
                            "senders=${cloneInfo.senderList?.size ?: 0}, " +
                            "rules=${cloneInfo.ruleList?.size ?: 0}, " +
                            "tasks=${cloneInfo.taskList?.size ?: 0}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "测试配置地址失败：${e.message}", e)
                false to (e.message ?: "request failed")
            }
            mainHandler.post { onResult(result.first, result.second) }
        }.start()
    }

    /**
     * 下载并导入配置。onResult(success, errorMessage) 在主线程回调（UI 需要弹窗）。
     * 网络与还原都在子线程执行，避免主线程网络异常。
     */
    fun downloadAndImport(url: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            var ok = false
            var err: String? = null
            try {
                val body = httpGet(url)
                val cloneInfo = parseCloneInfo(body)
                if (cloneInfo == null) {
                    err = "无法解析为配置文件(CloneInfo)"
                } else {
                    //版本一致性校验（与文件/云端导入一致）
                    HttpServerUtils.compareVersion(cloneInfo)
                    ok = HttpServerUtils.restoreSettings(cloneInfo)
                    if (!ok) err = "restore failed"
                }
            } catch (e: Exception) {
                Log.e(TAG, "导入配置失败：${e.message}", e)
                err = e.message
            }
            val fOk = ok
            val fErr = err
            mainHandler.post { onResult(fOk, fErr) }
        }.start()
    }
}
