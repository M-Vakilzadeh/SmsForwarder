package cn.ppps.forwarder.utils.sender

import android.text.TextUtils
import android.util.Base64
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.entity.Rule
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.entity.setting.WebhookSetting
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.STATUS_ON
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.TYPE_WEBHOOK
import com.google.gson.Gson
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Webhook 的「同步」发送结果。
 *
 * - [Success]：HTTP 200 且（配置了 response 校验串时）响应体包含该串。
 * - [RetryableFailure]：超时 / IO 错误 / 5xx / 非 200 的其它状态 / 200 但校验串不匹配 —— 交给 WorkManager 重试。
 * - [PermanentFailure]：4xx（请求本身有问题）/ 构造请求时的配置错误 —— 重试也没用。
 */
sealed class WebhookResult {
    data class Success(val body: String) : WebhookResult()
    data class RetryableFailure(val reason: String) : WebhookResult()
    data class PermanentFailure(val reason: String) : WebhookResult()
}

/**
 * Webhook 的同步发送实现。
 *
 * 原 [WebhookUtils.sendMsg] 用 XHttp 的 `.execute(SimpleCallBack)` 异步发送，
 * SendWorker.doWork() 在请求真正完成之前就返回了 success，WorkManager 的重试与 wakelock 都覆盖不到，
 * 一次 POST 失败就是一通电话的永久丢失。
 *
 * 这里改用 OkHttp 直接同步发送，自己掌握超时、拿到同步结果，供 [cn.ppps.forwarder.workers.WebhookDeliveryWorker]
 * 判断该重试还是该失败。请求的 URL / body / header / HMAC 构造与 [WebhookUtils] 保持一致
 * （复用其 replaceMd5Template / formatDateTime）。
 */
object WebhookSyncUtils {

    private const val TAG = "WebhookSyncUtils"

    fun send(setting: WebhookSetting, msgInfo: MsgInfo, rule: Rule? = null): WebhookResult {
        val content: String = if (rule != null) {
            msgInfo.getContentForSend(rule.smsTemplate, rule.regexReplace, rule.title)
        } else {
            msgInfo.getContentForSend(SettingUtils.smsTemplate)
        }

        //构造请求：配置类错误（代理端口非法等）视为永久失败，重试无意义
        val request: Request = try {
            buildRequest(setting, msgInfo, content, rule)
        } catch (e: Exception) {
            Log.e(TAG, "构造 webhook 请求失败：${e.message}", e)
            return WebhookResult.PermanentFailure("build request failed: ${e.message}")
        }

        val client: OkHttpClient = try {
            buildClient(setting)
        } catch (e: Exception) {
            Log.e(TAG, "构造 OkHttpClient 失败：${e.message}", e)
            return WebhookResult.PermanentFailure("build client failed: ${e.message}")
        }

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code()
                val body = response.body()?.string() ?: ""
                Log.i(TAG, "webhook 响应 code=$code, body=${body.take(500)}")
                classify(code, body, setting.response)
            }
        } catch (e: IOException) {
            //超时、连接失败等网络问题：可重试
            Log.w(TAG, "webhook 发送 IO 异常（可重试）：${e.message}")
            WebhookResult.RetryableFailure("io: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "webhook 发送异常（可重试）：${e.message}")
            WebhookResult.RetryableFailure("exception: ${e.message}")
        }
    }

    /**
     * 响应校验（对应 T4）：
     * 必须 HTTP 200，且——配置了 response 校验串时——响应体包含该串，才算成功。
     * 其余一律按可重试处理，4xx 例外（请求本身有问题，视为永久失败）。
     *
     * 这样可以堵住旧实现的漏洞：response 为空即无条件成功、NoContentInterceptor 把 201-299 全当成功，
     * 运营商插页(通常 200 一段 HTML)会被当成转发成功。
     */
    private fun classify(code: Int, body: String, responseToken: String): WebhookResult {
        if (code == 200) {
            return if (responseToken.isEmpty() || body.contains(responseToken)) {
                WebhookResult.Success(body)
            } else {
                WebhookResult.RetryableFailure("http 200 但未包含校验串「$responseToken」：${body.take(200)}")
            }
        }
        if (code in 400..499) {
            return WebhookResult.PermanentFailure("http $code：${body.take(200)}")
        }
        return WebhookResult.RetryableFailure("http $code：${body.take(200)}")
    }

    /**
     * 构造 OkHttp 请求，逻辑与 WebhookUtils.sendMsg 一致：
     * 支持 GET 查询参数 / JSON body / 文本 body / 表单参数四种，HMAC 签名、HTTP 基本认证、自定义 header。
     */
    private fun buildRequest(setting: WebhookSetting, msgInfo: MsgInfo, content: String, rule: Rule?): Request {
        val from: String = msgInfo.from
        var requestUrl: String = setting.webServer
        val timestamp = System.currentTimeMillis()
        val orgContent: String = msgInfo.content
        val deviceMark: String = SettingUtils.extraDeviceMark
        val appVersion: String = AppUtils.getAppVersionName()
        val simInfo: String = msgInfo.simInfo
        val receiveTimeTag = Regex("\\[receive_time(:(.*?))?]")

        //HMAC-SHA256 签名
        var sign = ""
        if (!TextUtils.isEmpty(setting.secret)) {
            val stringToSign = "$timestamp\n" + setting.secret
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(setting.secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val signData = mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8))
            sign = URLEncoder.encode(String(Base64.encode(signData, Base64.NO_WRAP)), "UTF-8")
        }

        var webParams = setting.webParams.trim()
        //复用 WebhookUtils 的 md5([变量]+...) 模板替换（这就是幂等键的来源）
        webParams = WebhookUtils.replaceMd5Template(webParams, msgInfo, from, content, orgContent, deviceMark, appVersion, simInfo, receiveTimeTag, timestamp, sign)

        //HTTP 基本认证：从 http(s)://user:pass@host 里拆出账号密码
        var basicUser: String? = null
        var basicPass: String? = null
        val authRegex = "^(https?://)([^:]+):([^@]+)@(.+)"
        val matches = Regex(authRegex, RegexOption.IGNORE_CASE).findAll(requestUrl).toList().flatMap(MatchResult::groupValues)
        if (matches.isNotEmpty()) {
            requestUrl = matches[1] + matches[4]
            basicUser = matches[2]
            basicPass = matches[3]
        }

        //根据 Content-Type 判断 body 格式
        var isJson = false
        var isText = false
        var contentTypeHeader: String? = null
        for ((key, value) in setting.headers.entries) {
            if (key.equals("Content-Type", ignoreCase = true)) {
                contentTypeHeader = value
                if (value.contains("application/json")) {
                    isJson = true
                    break
                } else if (value.startsWith("text/")) {
                    isText = true
                    break
                }
            }
        }

        val builder = Request.Builder()

        if (setting.method == "GET" && TextUtils.isEmpty(webParams)) {
            var url = requestUrl
            url += (if (url.contains("?")) "&" else "?") + "from=" + URLEncoder.encode(from, "UTF-8")
            url += "&content=" + URLEncoder.encode(content, "UTF-8")
            if (!TextUtils.isEmpty(sign)) {
                url += "&timestamp=$timestamp"
                url += "&sign=$sign"
            }
            builder.url(url).get()
        } else if (setting.method == "GET" && !TextUtils.isEmpty(webParams)) {
            webParams = msgInfo.replaceTemplate(webParams, "", "URLEncoder", rule?.title ?: "")
            webParams = webParams.replace("[from]", URLEncoder.encode(from, "UTF-8"))
                .replace("[content]", URLEncoder.encode(content, "UTF-8"))
                .replace("[msg]", URLEncoder.encode(content, "UTF-8"))
                .replace("[org_content]", URLEncoder.encode(orgContent, "UTF-8"))
                .replace("[device_mark]", URLEncoder.encode(deviceMark, "UTF-8"))
                .replace("[app_version]", URLEncoder.encode(appVersion, "UTF-8"))
                .replace("[title]", URLEncoder.encode(simInfo, "UTF-8"))
                .replace("[card_slot]", URLEncoder.encode(simInfo, "UTF-8"))
                .replace(receiveTimeTag) {
                    val format = it.groups[2]?.value
                    URLEncoder.encode(WebhookUtils.formatDateTime(msgInfo.date, format), "UTF-8")
                }
                .replace("\n", "%0A")
            if (!TextUtils.isEmpty(setting.secret)) {
                webParams = webParams.replace("[timestamp]", timestamp.toString())
                    .replace("[sign]", URLEncoder.encode(sign, "UTF-8"))
            }
            requestUrl += if (webParams.startsWith("/")) {
                webParams
            } else {
                (if (requestUrl.contains("?")) "&" else "?") + webParams
            }
            builder.url(requestUrl).get()
        } else if (webParams.isNotEmpty() && (isJson || isText || webParams.startsWith("{"))) {
            webParams = msgInfo.replaceTemplate(webParams, "", "Gson", rule?.title ?: "")
            val bodyMsg = webParams.replace("[from]", from)
                .replace("[content]", escapeJson(content))
                .replace("[msg]", escapeJson(content))
                .replace("[org_content]", escapeJson(orgContent))
                .replace("[device_mark]", escapeJson(deviceMark))
                .replace("[app_version]", appVersion)
                .replace("[title]", escapeJson(simInfo))
                .replace("[card_slot]", escapeJson(simInfo))
                .replace(receiveTimeTag) {
                    val format = it.groups[2]?.value
                    WebhookUtils.formatDateTime(msgInfo.date, format)
                }
                .replace("[timestamp]", timestamp.toString())
                .replace("[sign]", sign)
            //body 自带 Content-Type，用用户配置的（没有则按 JSON/文本兜底），后面加 header 时跳过 Content-Type 避免重复
            val mediaTypeStr = contentTypeHeader ?: if (isText) "text/plain; charset=utf-8" else "application/json; charset=utf-8"
            val body = RequestBody.create(MediaType.parse(mediaTypeStr), bodyMsg)
            builder.url(requestUrl).method(setting.method, body)
        } else {
            if (webParams.isEmpty()) {
                webParams = "from=[from]&content=[content]&timestamp=[timestamp]"
                if (!TextUtils.isEmpty(sign)) webParams += "&sign=[sign]"
            }
            webParams = msgInfo.replaceTemplate(webParams, "", "", rule?.title ?: "")
            val formBuilder = FormBody.Builder()
            webParams.trim('&').split("&").forEach {
                val sepIndex = it.indexOf("=")
                if (sepIndex != -1) {
                    val key = it.substring(0, sepIndex).trim()
                    val value = it.substring(sepIndex + 1).trim()
                        .replace("[from]", from)
                        .replace("[content]", content)
                        .replace("[msg]", content)
                        .replace("[org_content]", orgContent)
                        .replace("[device_mark]", deviceMark)
                        .replace("[app_version]", appVersion)
                        .replace("[title]", simInfo)
                        .replace("[card_slot]", simInfo)
                        .replace(receiveTimeTag) { t ->
                            val format = t.groups[2]?.value
                            WebhookUtils.formatDateTime(msgInfo.date, format)
                        }
                        .replace("[timestamp]", timestamp.toString())
                        .replace("[sign]", sign)
                    formBuilder.add(key, value)
                }
            }
            builder.url(requestUrl).method(setting.method, formBuilder.build())
        }

        //自定义 header（Content-Type 由 body 承载，跳过避免重复）
        for ((key, value) in setting.headers.entries) {
            if (key.equals("Content-Type", ignoreCase = true)) continue
            builder.header(key, value)
        }

        //HTTP 基本认证
        if (basicUser != null && basicPass != null) {
            builder.header("Authorization", Credentials.basic(basicUser, basicPass))
        }

        return builder.build()
    }

    private fun buildClient(setting: WebhookSetting): OkHttpClient {
        val timeoutSec = SettingUtils.requestTimeout.toLong().coerceAtLeast(1L)
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)

        //忽略 https 证书（与原 .ignoreHttpsCert() 保持一致，配合 T4 的 200+校验串防止运营商插页误判成功）
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

        //代理
        if ((setting.proxyType == Proxy.Type.HTTP || setting.proxyType == Proxy.Type.SOCKS)
            && !TextUtils.isEmpty(setting.proxyHost) && !TextUtils.isEmpty(setting.proxyPort)
        ) {
            val proxyPort = setting.proxyPort.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid proxy port")
            builder.proxy(Proxy(setting.proxyType, InetSocketAddress(setting.proxyHost, proxyPort)))

            if (setting.proxyAuthenticator && (!TextUtils.isEmpty(setting.proxyUsername) || !TextUtils.isEmpty(setting.proxyPassword))) {
                if (setting.proxyType == Proxy.Type.HTTP) {
                    builder.proxyAuthenticator { _: Route?, response: Response ->
                        val credential = Credentials.basic(setting.proxyUsername, setting.proxyPassword)
                        response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                } else {
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication {
                            return PasswordAuthentication(setting.proxyUsername, setting.proxyPassword.toCharArray())
                        }
                    })
                }
            }
        }

        return builder.build()
    }

    //JSON 需要转义的字符（与 WebhookUtils.escapeJson 一致）
    private fun escapeJson(str: String?): String {
        if (str == null) return "null"
        val jsonStr: String = Gson().toJson(str)
        return if (jsonStr.length >= 2) jsonStr.substring(1, jsonStr.length - 1) else jsonStr
    }

    // ---------- 供 T7 通道 C（对账批量）/ D（心跳）复用 ----------

    /**
     * 取第一个「启用」的 Webhook 通道配置，用于派生 /call/batch、/heartbeat 的地址，
     * 并复用其代理 / 忽略证书 / 超时设置。必须在工作线程调用（读数据库）。
     */
    fun resolveWebhookSetting(): WebhookSetting? {
        return try {
            Core.sender.getAllNonCache()
                .firstOrNull { it.type == TYPE_WEBHOOK && it.status == STATUS_ON && it.jsonSetting.isNotBlank() }
                ?.let { Gson().fromJson(it.jsonSetting, WebhookSetting::class.java) }
                ?.takeIf { !TextUtils.isEmpty(it.webServer) }
        } catch (e: Exception) {
            Log.e(TAG, "解析 Webhook 通道配置失败：${e.message}", e)
            null
        }
    }

    //约定 webServer 指向 /call：批量端点即 /call/batch
    fun batchUrl(setting: WebhookSetting): String = setting.webServer.trim().trimEnd('/') + "/batch"

    //心跳端点 /heartbeat 与 /call 同级
    fun heartbeatUrl(setting: WebhookSetting): String {
        val base = setting.webServer.trim().trimEnd('/')
        val parent = base.substringBeforeLast('/', base)
        return "$parent/heartbeat"
    }

    /**
     * 直接 POST 一段 JSON（通道 C/D 用）。校验规则与 [send] 一致：HTTP 200 且（配了 response 串时）包含它才算成功。
     * 复用 webhook 通道的代理/证书/超时与自定义 header。
     */
    fun postJson(setting: WebhookSetting, url: String, jsonBody: String): WebhookResult {
        val client = try {
            buildClient(setting)
        } catch (e: Exception) {
            return WebhookResult.PermanentFailure("build client failed: ${e.message}")
        }
        val request = try {
            val builder = Request.Builder().url(url)
            for ((key, value) in setting.headers.entries) {
                if (key.equals("Content-Type", ignoreCase = true)) continue
                builder.header(key, value)
            }
            val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonBody)
            builder.post(body).build()
        } catch (e: Exception) {
            return WebhookResult.PermanentFailure("build request failed: ${e.message}")
        }
        return try {
            client.newCall(request).execute().use { response ->
                classify(response.code(), response.body()?.string() ?: "", setting.response)
            }
        } catch (e: IOException) {
            WebhookResult.RetryableFailure("io: ${e.message}")
        } catch (e: Exception) {
            WebhookResult.RetryableFailure("exception: ${e.message}")
        }
    }
}
