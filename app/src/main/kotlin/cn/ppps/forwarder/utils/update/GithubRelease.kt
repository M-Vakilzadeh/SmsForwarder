package cn.ppps.forwarder.utils.update

import com.google.gson.Gson
import com.google.gson.JsonObject

data class ReleaseInfo(
    val tag: String,
    val body: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

//解析 GitHub Releases API（/releases/latest）并比较版本号，纯函数，便于单测
object GithubRelease {

    const val LATEST_URL = "https://api.github.com/repos/M-Vakilzadeh/SmsForwarder/releases/latest"
    const val RELEASES_PAGE = "https://github.com/M-Vakilzadeh/SmsForwarder/releases"

    //草稿/预发布/没有 apk 附件/解析失败都返回 null；多个 apk 时优先 universal（适用所有 ABI）
    fun parseLatest(json: String): ReleaseInfo? {
        return try {
            val obj = Gson().fromJson(json, JsonObject::class.java) ?: return null
            val tag = obj.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            if (obj.get("draft")?.asBoolean == true || obj.get("prerelease")?.asBoolean == true) return null

            val apks = obj.getAsJsonArray("assets")?.mapNotNull { it.asJsonObject }
                ?.filter { it.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true }
                .orEmpty()
            val asset = apks.firstOrNull { it.get("name").asString.contains("universal", ignoreCase = true) }
                ?: apks.firstOrNull() ?: return null

            ReleaseInfo(
                tag = tag,
                body = obj.get("body")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                downloadUrl = asset.get("browser_download_url").asString,
                sizeBytes = asset.get("size")?.asLong ?: 0L,
            )
        } catch (e: Exception) {
            null
        }
    }

    //按 "." 分段做数字比较；tag 允许带前缀 v。无法解析的 tag 一律不算更新，避免误提示
    fun isNewer(tag: String, current: String): Boolean {
        val remote = segments(tag) ?: return false
        val local = segments(current) ?: return false
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0L }
            val l = local.getOrElse(i) { 0L }
            if (r != l) return r > l
        }
        return false
    }

    private fun segments(version: String): List<Long>? {
        val parts = version.trim().removePrefix("v").removePrefix("V").split(".")
        val nums = parts.map { it.takeWhile { c -> c.isDigit() }.toLongOrNull() }
        return if (nums.isEmpty() || nums.any { it == null }) null else nums.map { it!! }
    }
}
