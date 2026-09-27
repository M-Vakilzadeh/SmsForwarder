package cn.ppps.forwarder.utils.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleaseTest {

    private fun release(
        tag: String = "v3.5.0.260921",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """
            {"name":"SmsF_3.5.0.260921_300055_arm64-v8a_release.apk","size":16775242,"browser_download_url":"https://x/arm64.apk"},
            {"name":"SmsF_3.5.0.260921_100055_universal_release.apk","size":19529195,"browser_download_url":"https://x/universal.apk"}
        """,
    ) = """{"tag_name":"$tag","body":"notes","draft":$draft,"prerelease":$prerelease,"assets":[$assets]}"""

    @Test
    fun parse_prefersUniversalApk() {
        val info = GithubRelease.parseLatest(release())!!
        assertEquals("v3.5.0.260921", info.tag)
        assertEquals("notes", info.body)
        assertEquals("https://x/universal.apk", info.downloadUrl)
        assertEquals(19529195L, info.sizeBytes)
    }

    //装的是分 ABI 包（versionCode 首位=ABI）时必须下同一 ABI 的包：universal 的 1xxxxx 比 3xxxxx 小，会被系统判为降级而装不上
    @Test
    fun parse_picksApkMatchingInstalledAbi() {
        val info = GithubRelease.parseLatest(release(), installedVersionCode = 300055)!!
        assertEquals("https://x/arm64.apk", info.downloadUrl)
        assertEquals(16775242L, info.sizeBytes)
    }

    @Test
    fun parse_universalInstalled_staysUniversal() {
        assertEquals("https://x/universal.apk", GithubRelease.parseLatest(release(), installedVersionCode = 100055)!!.downloadUrl)
    }

    @Test
    fun parse_abiNotPublished_fallsBackToUniversal() {
        assertEquals("https://x/universal.apk", GithubRelease.parseLatest(release(), installedVersionCode = 200055)!!.downloadUrl)
    }

    @Test
    fun abiPrefix_readsVersionCodeFromFileName() {
        assertEquals(3, GithubRelease.abiPrefix("SmsF_3.5.0.260921_300055_arm64-v8a_release.apk"))
        assertEquals(1, GithubRelease.abiPrefix("SmsF_3.5.0.260921_100055_universal_release.apk"))
        assertNull(GithubRelease.abiPrefix("b.apk"))
    }

    @Test
    fun parse_fallsBackToFirstApk() {
        val info = GithubRelease.parseLatest(
            release(assets = """{"name":"a.zip","size":1,"browser_download_url":"https://x/a.zip"},{"name":"b.apk","size":2,"browser_download_url":"https://x/b.apk"}""")
        )!!
        assertEquals("https://x/b.apk", info.downloadUrl)
    }

    @Test
    fun parse_noApkOrDraftOrPrerelease_isNull() {
        assertNull(GithubRelease.parseLatest(release(assets = "")))
        assertNull(GithubRelease.parseLatest(release(draft = true)))
        assertNull(GithubRelease.parseLatest(release(prerelease = true)))
        assertNull(GithubRelease.parseLatest("not json"))
        assertNull(GithubRelease.parseLatest("""{"message":"Not Found"}"""))
    }

    @Test
    fun isNewer_comparesNumericSegments() {
        assertTrue(GithubRelease.isNewer("v3.5.0.260921", "3.5.0.260920"))
        assertTrue(GithubRelease.isNewer("v3.10.0", "3.9.9.999999"))
        assertTrue(GithubRelease.isNewer("3.5.1", "3.5.0.260920"))
    }

    @Test
    fun isNewer_sameOrOlderIsFalse() {
        assertFalse(GithubRelease.isNewer("v3.5.0.260920", "3.5.0.260920"))
        assertFalse(GithubRelease.isNewer("v3.5.0.260919", "3.5.0.260920"))
        assertFalse(GithubRelease.isNewer("v3.5.0", "3.5.0.0"))
    }

    @Test
    fun isNewer_garbageTagIsNotNewer() {
        assertFalse(GithubRelease.isNewer("", "3.5.0.260920"))
        assertFalse(GithubRelease.isNewer("nightly", "3.5.0.260920"))
    }
}
