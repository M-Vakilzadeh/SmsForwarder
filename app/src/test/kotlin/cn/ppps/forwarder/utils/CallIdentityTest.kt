package cn.ppps.forwarder.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class CallIdentityTest {

    @Test
    fun callUuid_isDeterministicAcrossChannels() {
        //实时 /call、每日汇总、对账、通话备注各自算出的必须完全一致
        assertEquals(CallIdentity.callUuid("dev1", 1_726_000_000_123L), CallIdentity.callUuid("dev1", 1_726_000_000_123L))
    }

    @Test
    fun callUuid_ignoresSurroundingWhitespaceInDeviceMark() {
        assertEquals(CallIdentity.callUuid("dev1", 5L), CallIdentity.callUuid(" dev1 ", 5L))
    }

    @Test
    fun callUuid_differsPerDeviceAndPerCall() {
        assertNotEquals(CallIdentity.callUuid("dev1", 5L), CallIdentity.callUuid("dev2", 5L))
        assertNotEquals(CallIdentity.callUuid("dev1", 5L), CallIdentity.callUuid("dev1", 6L))
    }

    @Test
    fun callUuid_isStandardUuidAndPinned() {
        val u = CallIdentity.callUuid("dev1", 1_726_000_000_123L)
        assertEquals(u, UUID.fromString(u).toString())
        //固定算法：服务端/其他客户端可按同一公式复算，改动算法会导致新旧数据无法合并
        assertEquals(UUID.nameUUIDFromBytes("smsf-call|dev1|1726000000123".toByteArray(Charsets.UTF_8)).toString(), u)
    }

    @Test
    fun callUuid_emptyWhenCallTimeUnknown() {
        assertEquals("", CallIdentity.callUuid("dev1", 0L))
    }

    @Test
    fun injectCallUuid_addsFieldToJsonObjectBody() {
        val body = """{"from":"0912","msg":"a=b <x>","receive_time":"2026-09-26 10:00:00","n":1.50}"""
        val out = CallIdentity.injectCallUuid(body, "u-1")
        //原字段一字不差保留（不做 HTML 转义、不改数字格式），只在末尾追加 call_uuid
        assertEquals("""{"from":"0912","msg":"a=b <x>","receive_time":"2026-09-26 10:00:00","n":1.50,"call_uuid":"u-1"}""", out)
    }

    @Test
    fun injectCallUuid_keepsTemplateValueWhenAlreadyPresent() {
        val body = """{"call_uuid":"from-template","x":1}"""
        assertEquals(body, CallIdentity.injectCallUuid(body, "u-1"))
    }

    @Test
    fun injectCallUuid_leavesNonObjectOrInvalidBodyUntouched() {
        assertEquals("""[1,2]""", CallIdentity.injectCallUuid("""[1,2]""", "u-1"))
        assertEquals("plain text", CallIdentity.injectCallUuid("plain text", "u-1"))
        assertEquals("""{"broken":""", CallIdentity.injectCallUuid("""{"broken":""", "u-1"))
    }

    @Test
    fun injectCallUuid_skipsWhenUuidUnknown() {
        //空 UUID 不写入：否则服务端 call_uuid 唯一索引会让所有「拿不到通话记录」的行互相冲突
        val body = """{"x":1}"""
        assertEquals(body, CallIdentity.injectCallUuid(body, ""))
    }
}
