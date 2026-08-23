package cn.ppps.forwarder.utils

import android.content.Context
import cn.ppps.forwarder.entity.CloneInfo
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import java.util.Date

/**
 * 装机引导：新机首次打开时，从指定 URL 下载配置 JSON 并一键导入，加速批量装机。
 *
 * 配置 JSON 就是「一键换新机」导出的 SmsForwarder.json（即 CloneInfo 结构），
 * 复用 [HttpServerUtils.restoreSettings] 做完整还原（设置 + 发送通道 + 转发规则 + 任务）。
 *
 * 「是否已提示过」存放在独立的 SharedPreferences 文件里，不随配置导入被清空/覆盖，避免导入后又反复弹窗。
 */
object ProvisionUtils {

    private const val TAG = "ProvisionUtils"
    private const val PREFS = "provision_prefs"
    private const val KEY_PROMPTED = "config_import_prompted"

    fun hasPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /**
     * 下载并导入配置。onResult(success, errorMessage) 在主线程回调
     * （XHttp 回调在主线程；DB 已开启 allowMainThreadQueries，还原可在主线程执行）。
     */
    fun downloadAndImport(url: String, onResult: (Boolean, String?) -> Unit) {
        XHttp.get(url)
            .keepJson(true)
            .ignoreHttpsCert()
            .execute(object : SimpleCallBack<String>() {
                override fun onError(e: ApiException) {
                    Log.e(TAG, "下载配置失败：${e.detailMessage}")
                    onResult(false, e.displayMessage)
                }

                override fun onSuccess(response: String) {
                    try {
                        //Date 字段统一替换为当前时间，避免旧时间戳影响（与 CloneFragment 导入一致）
                        val gson = GsonBuilder()
                            .registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
                            .create()
                        val cloneInfo = gson.fromJson(response, CloneInfo::class.java)
                        if (cloneInfo == null) {
                            onResult(false, "empty config")
                            return
                        }
                        //版本一致性校验（与文件/云端导入一致）
                        HttpServerUtils.compareVersion(cloneInfo)
                        val ok = HttpServerUtils.restoreSettings(cloneInfo)
                        onResult(ok, null)
                    } catch (ex: Exception) {
                        Log.e(TAG, "导入配置失败：${ex.message}", ex)
                        onResult(false, ex.message)
                    }
                }
            })
    }
}
