package cn.ppps.forwarder.utils.update

import cn.ppps.forwarder.utils.AppUtils
import com.xuexiang.xupdate.entity.UpdateEntity
import com.xuexiang.xupdate.proxy.impl.AbstractUpdateParser

/**
 * 把 GitHub Releases API（/releases/latest）的返回解析成 XUpdate 的更新信息。
 * 有新版本时，XUpdate 自带的弹窗负责下载并调起系统安装。
 */
class CustomUpdateParser : AbstractUpdateParser() {
    @Throws(Exception::class)
    override fun parseJson(json: String): UpdateEntity {
        val info = GithubRelease.parseLatest(json, AppUtils.getAppVersionCode())
            ?: return UpdateEntity().setHasUpdate(false)

        return UpdateEntity()
            .setHasUpdate(GithubRelease.isNewer(info.tag, AppUtils.getAppVersionName()))
            .setVersionName(info.tag.removePrefix("v"))
            .setUpdateContent(info.body)
            .setDownloadUrl(info.downloadUrl)
            //XUpdate 的 size 单位是 KB
            .setSize(info.sizeBytes / 1024)
            //始终保持最新版：不允许「忽略此版本」，否则之后打开 App 也不会再更新
            .setIsIgnorable(false)
    }
}
