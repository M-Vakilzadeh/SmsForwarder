package cn.ppps.forwarder.utils

import cn.ppps.forwarder.entity.CallInfo
import cn.ppps.forwarder.entity.CallNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNoteUtilsTest {

    private fun note(id: String, text: String = "note $id") = CallNote(
        noteId = id,
        deviceMark = "dev1",
        number = "09120000000",
        contactName = "Ali",
        callType = 2,
        callStart = 1_000L,
        callEnd = 61_000L,
        note = text,
        createdAt = 70_000L,
    )

    @Test
    fun shouldPrompt_onlyForAnsweredIncomingAndOutgoing() {
        assertTrue(CallNoteUtils.shouldPrompt(1))
        assertTrue(CallNoteUtils.shouldPrompt(2))
        assertFalse(CallNoteUtils.shouldPrompt(3))
        assertFalse(CallNoteUtils.shouldPrompt(4))
        assertFalse(CallNoteUtils.shouldPrompt(0))
    }

    @Test
    fun queue_roundTripsThroughJson() {
        val list = listOf(note("a"), note("b", "متن فارسی"))
        val parsed = CallNoteUtils.parseQueue(CallNoteUtils.serializeQueue(list))
        assertEquals(list, parsed)
    }

    @Test
    fun parseQueue_blankOrCorruptGivesEmptyList() {
        assertTrue(CallNoteUtils.parseQueue(null).isEmpty())
        assertTrue(CallNoteUtils.parseQueue("").isEmpty())
        assertTrue(CallNoteUtils.parseQueue("{not json").isEmpty())
    }

    @Test
    fun removeByIds_keepsNotesAddedAfterSnapshot() {
        val queue = listOf(note("a"), note("b"), note("c"))
        val left = CallNoteUtils.removeByIds(queue, setOf("a", "c"))
        assertEquals(listOf("b"), left.map { it.noteId })
    }

    @Test
    fun singlePayload_hasStableFieldNames() {
        val p = CallNoteUtils.singlePayload(note("a").copy(callDate = 900L, callDuration = 55))
        assertEquals("call_note", p["type"])
        assertEquals("a", p["note_id"])
        assertEquals("dev1", p["device_mark"])
        assertEquals("09120000000", p["number"])
        assertEquals("Ali", p["contact_name"])
        assertEquals(2, p["call_type"])
        assertEquals("outgoing", p["call_type_name"])
        assertEquals(1_000L, p["call_start"])
        assertEquals(61_000L, p["call_end"])
        assertEquals(900L, p["call_date"])
        assertEquals(55, p["call_duration"])
        assertEquals("note a", p["note"])
        assertEquals(70_000L, p["created_at"])
        assertEquals(CallIdentity.callUuid("dev1", 900L), p["call_uuid"])
    }

    @Test
    fun singlePayload_unmatchedCallHasEmptyUuid() {
        assertEquals("", CallNoteUtils.singlePayload(note("a"))["call_uuid"])
    }

    @Test
    fun findMatch_picksThisCallAmongRecentOnes() {
        val calls = listOf(
            CallInfo(number = "+989120000000", dateLong = 200_000L, duration = 30, type = 2), //之后的另一通
            CallInfo(number = "+989120000000", dateLong = 1_200L, duration = 55, type = 2),   //本通
            CallInfo(number = "+989120000000", dateLong = 1_100L, duration = 10, type = 1),   //类型不同
            CallInfo(number = "+989120000000", dateLong = -50_000L, duration = 20, type = 2), //更早那一通
        )
        val m = CallNoteUtils.findMatch(calls, callType = 2, callStart = 1_000L, callEnd = 61_000L)
        assertEquals(1_200L, m?.dateLong)
        assertEquals(null, CallNoteUtils.findMatch(calls, callType = 2, callStart = 100_000L, callEnd = 150_000L))
    }

    @Test
    fun batchPayload_wrapsNotesWithCount() {
        val p = CallNoteUtils.batchPayload("dev1", listOf(note("a"), note("b")))
        assertEquals("call_note_batch", p["type"])
        assertEquals("dev1", p["device_mark"])
        assertEquals(2, p["count"])
        @Suppress("UNCHECKED_CAST")
        val notes = p["notes"] as List<Map<String, Any?>>
        assertEquals(listOf("a", "b"), notes.map { it["note_id"] })
    }

    @Test
    fun callTypeName_incomingOutgoing() {
        assertEquals("incoming", CallNoteUtils.callTypeName(1))
        assertEquals("outgoing", CallNoteUtils.callTypeName(2))
    }

    @Test
    fun matchesCall_acceptsLogEntryInsideCallWindowOnly() {
        //通话记录 date = 开始响铃/拨出时刻，允许 5s 时钟偏差
        assertTrue(CallNoteUtils.matchesCall(logDate = 1_500L, callStart = 1_000L, callEnd = 61_000L))
        assertTrue(CallNoteUtils.matchesCall(logDate = -3_000L, callStart = 1_000L, callEnd = 61_000L))
        //更早那一通电话
        assertFalse(CallNoteUtils.matchesCall(logDate = -10_000L, callStart = 1_000L, callEnd = 61_000L))
        //之后的另一通电话
        assertFalse(CallNoteUtils.matchesCall(logDate = 70_000L, callStart = 1_000L, callEnd = 61_000L))
    }
}
