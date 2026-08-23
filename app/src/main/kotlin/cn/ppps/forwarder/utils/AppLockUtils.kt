package cn.ppps.forwarder.utils

import java.security.MessageDigest

/**
 * 应用锁：打开 App 需要密码。密码只以 SHA-256 哈希存储，不保存明文。
 */
object AppLockUtils {

    fun isLockSet(): Boolean = SettingUtils.appLockHash.isNotEmpty()

    /** 设置或修改密码；传空字符串表示清除应用锁。 */
    fun setPassword(plain: String) {
        SettingUtils.appLockHash = if (plain.isEmpty()) "" else sha256(plain)
    }

    fun clear() {
        SettingUtils.appLockHash = ""
    }

    fun verify(plain: String): Boolean = isLockSet() && sha256(plain) == SettingUtils.appLockHash

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
