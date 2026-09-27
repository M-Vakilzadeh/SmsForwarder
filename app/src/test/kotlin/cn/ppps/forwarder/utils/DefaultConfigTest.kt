package cn.ppps.forwarder.utils

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultConfigTest {

    private val template: String by lazy {
        val f = listOf("src/main/assets/default_config.json", "app/src/main/assets/default_config.json")
            .map { File(it) }.first { it.exists() }
        f.readText()
    }

    @Test
    fun normalizeBaseUrl_addsSchemeAndStripsSlash() {
        assertEquals("https://n8n.kzmn.ir", DefaultConfig.normalizeBaseUrl("n8n.kzmn.ir"))
        assertEquals("https://n8n.kzmn.ir", DefaultConfig.normalizeBaseUrl("  https://n8n.kzmn.ir/  "))
        assertEquals("http://10.0.0.5:5678", DefaultConfig.normalizeBaseUrl("http://10.0.0.5:5678//"))
        assertEquals("https://a.b/n8n", DefaultConfig.normalizeBaseUrl("https://a.b/n8n/"))
    }

    @Test
    fun normalizeBaseUrl_rejectsGarbage() {
        assertNull(DefaultConfig.normalizeBaseUrl(""))
        assertNull(DefaultConfig.normalizeBaseUrl("   "))
        assertNull(DefaultConfig.normalizeBaseUrl("https://"))
        assertNull(DefaultConfig.normalizeBaseUrl("a b.com"))
        assertNull(DefaultConfig.normalizeBaseUrl("https://a.com/\"x"))
        assertNull(DefaultConfig.normalizeBaseUrl("ftp://a.com"))
    }

    @Test
    fun applyBaseUrl_replacesEveryPlaceholder() {
        assertEquals("https://h/webhook/logger-alert", DefaultConfig.applyBaseUrl("{{BASE_URL}}/webhook/logger-alert", "https://h"))
        assertEquals("https://other/x", DefaultConfig.applyBaseUrl("https://other/x", "https://h"))
    }

    //内置配置：发送通道（含规则里内嵌的副本）都指向用户填写的 baseurl，路径与原配置一致
    @Test
    fun asset_sendersPointAtBaseUrl() {
        val info = DefaultConfig.parse(DefaultConfig.applyBaseUrl(template, "https://example.org"))
        val urls = info.senderList!!.map { JsonParser.parseString(it.jsonSetting).asJsonObject.get("webServer").asString }
        assertEquals(listOf("https://example.org/webhook-test/datasender", "https://example.org/webhook/datasender"), urls)

        val rule = info.ruleList!!.single()
        assertEquals("call", rule.type)
        assertEquals(2L, rule.senderList.single().id)
        assertEquals("https://example.org/webhook/datasender",
            JsonParser.parseString(rule.senderList.single().jsonSetting).asJsonObject.get("webServer").asString)
    }

    //仓库是公开的：内置配置不能带原手机的 IP、设备名、电量、SIM 等信息；网络告警地址是占位符，导入后再替换
    @Test
    fun asset_settingsAreSanitized() {
        val info = DefaultConfig.parse(template)
        val map = SharedPreference.deSerialization<Map<String, Any>>(info.settings)
        for (key in listOf("ip_list", "ipv4", "ipv6", "extra_device_mark", "battery_info", "wifi_ssid",
            "extra_sim1", "extra_sim2", "subid_sim1", "subid_sim2", "cn.ppps.forwarder.widget.key_is_ignore_tips_100055")) {
            assertFalse(key, map.containsKey(key))
        }
        assertEquals("{{BASE_URL}}/webhook/logger-alert", map["net_alert_url"])
        assertEquals(true, map["enable_phone"])
        assertEquals(true, map["net_alert_enabled"])
        //通话实时转发（0 = FORWARD_TIMING_REALTIME），不走每日汇总
        assertEquals(FORWARD_TIMING_REALTIME, map["forward_timing"])
        assertFalse(template.contains("kzmn"))
    }

    @Test
    fun asset_hasNoLeftoverPlaceholderAfterApply() {
        val applied = DefaultConfig.applyBaseUrl(template, "https://example.org")
        //settings 里的占位符是 URL 编码后的，不受文本替换影响，由 restore 后单独处理
        assertTrue(applied.contains("%7B%7BBASE_URL%7D%7D"))
        assertFalse(applied.contains("{{BASE_URL}}"))
    }
}
