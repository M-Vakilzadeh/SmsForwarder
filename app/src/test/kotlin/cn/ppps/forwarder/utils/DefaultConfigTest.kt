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
        assertEquals("https://hooks.example.com", DefaultConfig.normalizeBaseUrl("hooks.example.com"))
        assertEquals("https://hooks.example.com", DefaultConfig.normalizeBaseUrl("  https://hooks.example.com/  "))
        assertEquals("http://10.0.0.5:5678", DefaultConfig.normalizeBaseUrl("http://10.0.0.5:5678//"))
        assertEquals("https://a.b/hooks", DefaultConfig.normalizeBaseUrl("https://a.b/hooks/"))
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

    //Bundled config: every sender (including the copy embedded in the rule) points at the entered base URL, same paths as the original
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

    //The repo is public: no IPs, device name, battery or SIM data from the source phone; the alert URL is a placeholder rebased after import
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
    }

    //Calls use daily batch forwarding; the time is not in the config but taken at first setup, spreading devices out
    @Test
    fun asset_callsForwardedDailyAtFirstRunTime() {
        val map = SharedPreference.deSerialization<Map<String, Any>>(DefaultConfig.parse(template).settings)
        assertEquals(FORWARD_TIMING_DAILY, map["forward_timing"])
        assertEquals(BATCH_INTERVAL_DAILY, map["batch_interval_minutes"])
        assertEquals(true, map["daily_include_call"])
        assertFalse(map.containsKey("daily_forward_time"))
    }

    //Call notes: on, sent to {{BASE_URL}}/webhook/call-note in a daily batch at 14:00
    @Test
    fun asset_callNoteEnabledDailyAt1400() {
        val map = SharedPreference.deSerialization<Map<String, Any>>(DefaultConfig.parse(template).settings)
        assertEquals(true, map["call_note_enabled"])
        assertEquals("{{BASE_URL}}/webhook/call-note", map["call_note_url"])
        assertEquals(CALL_NOTE_SEND_BATCH, map["call_note_send_mode"])
        assertEquals(BATCH_INTERVAL_DAILY, map["call_note_batch_interval_minutes"])
        assertEquals(DailyTime.slotOf(14, 0), map["call_note_batch_time"])
    }

    //App lock on by default with PIN 1331 (only the hash is stored)
    @Test
    fun asset_appLockIs1331() {
        val map = SharedPreference.deSerialization<Map<String, Any>>(DefaultConfig.parse(template).settings)
        assertEquals(AppLockUtils.sha256("1331"), map["app_lock_hash"])
    }

    //The default config must not mention n8n or the source phone's device name
    @Test
    fun asset_hasNoServerOrOwnerNames() {
        assertFalse(template.contains("n8n", ignoreCase = true))
        assertFalse(template.contains("kzmn"))
        val settings = String(java.net.URLDecoder.decode(DefaultConfig.parse(template).settings, "UTF-8").toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        assertFalse(settings.contains("n8n", ignoreCase = true))
        assertFalse(settings.contains("محسن"))
        val names = DefaultConfig.parse(template).senderList!!.map { it.name }
        assertFalse(names.any { it.contains("n8n", ignoreCase = true) })
    }

    @Test
    fun asset_hasNoLeftoverPlaceholderAfterApply() {
        val applied = DefaultConfig.applyBaseUrl(template, "https://example.org")
        //The placeholder inside settings is URL-encoded, so text replacement skips it; it is rebased after restore
        assertTrue(applied.contains("%7B%7BBASE_URL%7D%7D"))
        assertFalse(applied.contains("{{BASE_URL}}"))
    }
}
