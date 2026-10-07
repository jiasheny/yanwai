package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Teaching modes add a reading on top of the messages; 代打 keeps the original contract. */
class ReplyTeachingTest {
    private val settings = ReplySettings.fromInput("https://example.com", "key", "m")
    private val context = ReplyContext("a", listOf(ReplyMessage(1, "对方", 1, "周末有空吗")))

    private fun envelope(content: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString()

    private fun analysis() = JSONObject()
        .put("facts", JSONArray(listOf("她问周末有空吗")))
        .put("guess", JSONArray(listOf("可能想约")))
        .put("purpose", "私人互动，她主动问我的时间")
        .put("stage", "阶段3")
        .put("trend", "升温")
        .put("her_need", "希望我给一个具体时间")
        .put("my_goal", "未说明")

    private fun compare() = JSONObject()
        .put("keep", JSONArray(listOf("直接、不绕")))
        .put("fix", JSONArray(listOf("补一个具体钩子")))
        .put("verdict", "方向对，缺具体")

    @Test fun `mode ids stay stable and only draft modes ask for an attempt`() {
        assertEquals(ReplyMode.entries.map { it.id }.distinct().size, ReplyMode.entries.size)
        assertEquals(ReplyMode.ASSIST, ReplyMode.of(null))
        assertEquals(ReplyMode.DEFAULT, ReplyMode.of("nope"))
        assertTrue(ReplyMode.PREDICT.wantsDraft && ReplyMode.GRADE.wantsDraft)
        assertFalse(ReplyMode.ASSIST.wantsDraft || ReplyMode.READ.wantsDraft)
        assertTrue(ReplyMode.GRADE.requiresDraft)
        assertFalse(ReplyMode.PREDICT.requiresDraft)
    }

    @Test fun `only the analysis mode may return an empty message list`() {
        val analysisOnly = JSONObject().put("replies", JSONArray()).put("read", analysis()).put("drill", "先接情绪再给方案")
        val parsed = ReplyProtocol.parse(envelope(analysisOnly.toString()), ReplyMode.READ)
        assertEquals(0, parsed.suggestion.parts.size)
        assertEquals("阶段3", parsed.teaching.analysis!!.stage)
        assertEquals("先接情绪再给方案", parsed.teaching.drill)
        // The default mode keeps its old strictness: an empty list is still a failure.
        assertThrows(IllegalStateException::class.java) { ReplyProtocol.parse(envelope(analysisOnly.toString())) }
        // 只拆解 must not hand back messages even when the model tries to.
        val leaked = JSONObject(analysisOnly).put("replies", JSONArray(listOf("好呀")))
        assertThrows(IllegalStateException::class.java) { ReplyProtocol.parse(envelope(leaked.toString()), ReplyMode.READ) }
        // 只拆解 without a complete reading is a failure, not an empty answer.
        assertThrows(IllegalStateException::class.java) {
            ReplyProtocol.parse(envelope(JSONObject().put("replies", JSONArray()).toString()), ReplyMode.READ)
        }
    }

    @Test fun `draft modes require the comparison they asked for`() {
        val withoutCompare = JSONObject().put("replies", JSONArray(listOf("好呀"))).put("read", analysis()).toString()
        assertThrows(IllegalStateException::class.java) {
            ReplyProtocol.parse(envelope(withoutCompare), ReplyMode.PREDICT, expectCompare = true)
        }
        val withCompare = JSONObject(JSONObject(withoutCompare).toString()).put("compare", compare())
        val parsed = ReplyProtocol.parse(envelope(withCompare.toString()), ReplyMode.PREDICT, expectCompare = true)
        assertEquals("直接、不绕", parsed.teaching.compare!!.keep.single())
        assertEquals("方向对，缺具体", parsed.teaching.compare!!.verdict)
        assertNull(parsed.teaching.compare!!.score)
        // A comparison without a concrete fix is not a comparison.
        val noFix = JSONObject(withCompare.toString()).put("compare", JSONObject()
            .put("keep", JSONArray(listOf("不错"))).put("fix", JSONArray()).put("verdict", "还行"))
        assertThrows(IllegalStateException::class.java) {
            ReplyProtocol.parse(envelope(noFix.toString()), ReplyMode.PREDICT, expectCompare = true)
        }
    }

    @Test fun `a score stays optional and bounded`() {
        val base = JSONObject().put("replies", JSONArray(listOf("嗯"))).put("compare", compare())
        assertEquals(null, ReplyProtocol.parse(envelope(base.toString()), ReplyMode.GRADE, expectCompare = true).teaching.compare!!.score)
        base.getJSONObject("compare").put("score", 7)
        assertEquals(7, ReplyProtocol.parse(envelope(base.toString()), ReplyMode.GRADE, expectCompare = true).teaching.compare!!.score)
        base.getJSONObject("compare").put("score", 42)
        assertNull(ReplyProtocol.parse(envelope(base.toString()), ReplyMode.GRADE, expectCompare = true).teaching.compare!!.score)
    }

    @Test fun `each mode states its own contract and 代打 keeps the old prompt`() {
        fun system(mode: ReplyMode) = ReplyProtocol.payload(settings, context, "", "", "资料", mode = mode)
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(system(ReplyMode.ASSIST).contains("本次是学习模式"))
        assertTrue(system(ReplyMode.READ).contains("replies 必须是空数组"))
        assertTrue(system(ReplyMode.PREDICT).contains("compare 必须返回"))
        assertTrue(system(ReplyMode.GRADE).contains("score（0–10 的整数）"))
        listOf(ReplyMode.READ, ReplyMode.PREDICT, ReplyMode.GRADE).forEach { mode ->
            val content = system(mode)
            assertTrue(content.contains("本次是学习模式"))
            assertTrue(content.contains("read 用固定五步"))
            // The advisor bridge and the product contract must survive every mode.
            assertTrue(content.contains("replies 只放我真正要发出去的句子"))
            assertTrue(content.contains("本次顾问："))
        }
    }

    @Test fun `the attempt travels in the evidence and the mode is named`() {
        val payload = ReplyProtocol.payload(settings, context, "", "", "资料", mode = ReplyMode.PREDICT, myDraft = "一起吃个饭？")
        val evidence = JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("predict", evidence.getJSONObject("mode").getString("id"))
        assertEquals("一起吃个饭？", evidence.getString("my_draft"))
        val plain = ReplyProtocol.evidence(context, "", "")
        assertEquals("assist", plain.getJSONObject("mode").getString("id"))
        assertFalse(plain.has("my_draft"))
    }

    @Test fun `teaching survives reopen and an ordinary generation clears it`() {
        val composer = ReplyComposition()
        val outcome = ReplyOutcome(ReplySuggestion(emptyList(), "先看信号"), ReplyTeaching(
            ReplyAnalysis(listOf("她问了时间"), emptyList(), "私人互动", "阶段3", "升温", "想要具体时间", "未说明"),
            null, "先接情绪", "她是否主动开新话题"))
        assertTrue(composer.accept(context, outcome, "", null, ReplyRelationship.UNSPECIFIED))
        assertNull(composer.selectedText)
        val reopened = ReplyComposition(composer.result)
        assertEquals("阶段3", reopened.result!!.teaching.analysis!!.stage)
        assertEquals("先接情绪", reopened.result!!.teaching.drill)
        assertEquals("她是否主动开新话题", reopened.result!!.teaching.watch)
        assertTrue(composer.accept(context, ReplySuggestion("好呀", ""), "", null, ReplyRelationship.UNSPECIFIED))
        assertTrue(composer.result!!.teaching.isEmpty)
        assertEquals("好呀", composer.selectedText)
    }
}
