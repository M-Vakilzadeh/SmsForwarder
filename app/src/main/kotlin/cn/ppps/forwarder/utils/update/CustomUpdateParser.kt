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
            //Always stay on the latest version: no "ignore this version", or later opens would never update
            .setIsIgnorable(false)
    }
}
