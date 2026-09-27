package cn.ppps.forwarder.utils.sdkinit

import android.app.Application
import android.content.Context
import cn.ppps.forwarder.App
import cn.ppps.forwarder.BuildConfig
import cn.ppps.forwarder.utils.KEY_PREVIEW_URL
import cn.ppps.forwarder.utils.KEY_UPDATE_URL
import cn.ppps.forwarder.utils.update.CustomUpdateDownloader
import cn.ppps.forwarder.utils.update.CustomUpdateFailureListener
import cn.ppps.forwarder.utils.update.CustomUpdateParser
import cn.ppps.forwarder.utils.update.XHttpUpdateHttpServiceImpl
import com.xuexiang.xupdate.XUpdate
import com.xuexiang.xupdate.utils.UpdateUtils
import com.xuexiang.xutil.common.StringUtils

/**
 * XUpdate 版本更新 SDK 初始化
 *
 * 详细使用参见：https://github.com/xuexiangjys/XUpdate/wiki
 *
 * @author xuexiang
 * @since 2019-06-18 15:51
 */
@Suppress("SameParameterValue")
class XUpdateInit private constructor() {
    companion object {
        /**
         * 应用版本更新的检查地址
         */
        fun init(application: Application) {
            XUpdate.get()
                .debug(App.isDebug)
                //默认设置只在wifi下检查版本更新
                .isWifiOnly(false)
                //默认设置使用get请求检查版本
                .isGet(true)
                //默认设置非自动模式，可根据具体使用配置
                //.isAutoMode(false)
                //设置默认公共请求参数
                .param("versionCode", UpdateUtils.getVersionCode(application))
                .param("appKey", application.packageName)
                //设置预览计划请求参数
                .param("versionName", UpdateUtils.getVersionName(application))
                .param("buildTime", BuildConfig.BUILD_TIME)
                .param("gitCommitId", BuildConfig.GIT_COMMIT_ID)
                //这个必须设置！实现网络请求功能。
                .setIUpdateHttpService(XHttpUpdateHttpServiceImpl())
                //版本信息来自 GitHub Releases，用自定义解析器转成 XUpdate 的更新信息
                .setIUpdateParser(CustomUpdateParser())
                .setIUpdateDownLoader(CustomUpdateDownloader())
                //这个必须初始化
                .init(application)
        }

        /**
         * 进行版本更新检查
         */
        fun checkUpdate(context: Context, needErrorTip: Boolean, joinPreviewProgram: Boolean) {
            if (joinPreviewProgram) {
                checkUpdate(context, KEY_PREVIEW_URL, needErrorTip)
            } else {
                checkUpdate(context, KEY_UPDATE_URL, needErrorTip)
            }
        }

        /**
         * 打开 App 时的更新检查：自动模式，有新版本直接后台下载，下载完调起系统安装界面
         * （非 root 无法静默安装，只能由用户点「安装」）。
         */
        fun checkUpdateAuto(context: Context) {
            XUpdate.newBuild(context).updateUrl(KEY_UPDATE_URL).isAutoMode(true).update()
            XUpdate.get().debug(App.isDebug).setOnUpdateFailureListener(CustomUpdateFailureListener(false))
        }

        /**
         * 进行版本更新检查
         *
         * @param context      上下文
         * @param url          版本更新检查的地址
         * @param needErrorTip 是否需要错误的提示
         */
        private fun checkUpdate(context: Context, url: String, needErrorTip: Boolean) {
            if (StringUtils.isEmpty(url)) {
                return
            }
            XUpdate.newBuild(context).updateUrl(url).update()
            XUpdate.get().debug(App.isDebug).setOnUpdateFailureListener(CustomUpdateFailureListener(needErrorTip))
        }
    }

    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}