package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.PersistentAnalysisCacheTest.Jdbc
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** The advisor is stored next to the identity, so a contact keeps its methodology across restarts. */
class ReplyAdvisorTest {
    @get:Rule val folder = TemporaryFolder()
    private val account = AnalysisCacheKey.digest("advisor-account")
    private val alice = requireNotNull(ReplyContactKey.of(account, "wxid_alice"))

    @Test fun `advisor survives a database reopen and stays separate from the identity`() {
        val file = folder.newFile()
        ReplyIdentityStore(Jdbc(file)).use {
            it.save(alice, ReplyIdentitySetting(ReplyRelationship.FLIRT, advisor = ReplyAdvisor.QINGSHENG))
        }
        ReplyIdentityStore(Jdbc(file)).use {
            assertEquals(ReplyAdvisor.QINGSHENG, it.find(alice).advisor)
            assertEquals(ReplyRelationship.FLIRT, it.find(alice).relationship)
        }
    }

    @Test fun `format one rows stay readable and unknown ids fall back to the default`() {
        val legacy = JSONObject(ReplyIdentitySetting(ReplyRelationship.FRIEND).encode())
            .apply { put("format", 1); remove("advisor") }.toString()
        assertEquals(ReplyAdvisor.DEFAULT, ReplyIdentitySetting.decode(legacy).advisor)
        assertEquals(ReplyRelationship.FRIEND, ReplyIdentitySetting.decode(legacy).relationship)
        assertEquals(2, JSONObject(ReplyIdentitySetting(ReplyRelationship.FRIEND).encode()).getInt("format"))
        val future = JSONObject(ReplyIdentitySetting().encode()).apply { put("advisor", "from-the-future") }.toString()
        assertEquals(ReplyAdvisor.DEFAULT, ReplyIdentitySetting.decode(future).advisor)
    }

    @Test fun `a role carries its advisor into the contact identity`() {
        val file = folder.newFile()
        ReplyIdentityStore(Jdbc(file)).use {
            val role = it.roles.save(null, "小林", "喜欢摄影", advisor = ReplyAdvisor.QINGSHENG)
            assertEquals(ReplyAdvisor.QINGSHENG, role.advisor)
            assertEquals(ReplyAdvisor.QINGSHENG, it.roles.apply(role.id, role.revision, alice).identity.advisor)
            assertEquals(ReplyAdvisor.QINGSHENG, it.find(alice).advisor)
            assertEquals(ReplyRelationship.OTHER, it.find(alice).relationship)
        }
        ReplyIdentityStore(Jdbc(file)).use {
            val stored = it.find(alice)
            assertEquals(ReplyAdvisor.QINGSHENG, stored.advisor)
            assertEquals(ReplyAdvisor.QINGSHENG, it.roles.find(requireNotNull(stored.roleId))?.advisor)
        }
    }

    @Test fun `legacy role rows without an advisor read as the default`() {
        val payload = JSONObject(ReplyRole("role:${UUID.randomUUID()}", "同学", "旧模板", "v1").encode())
            .apply { remove("advisor") }.toString()
        assertEquals(ReplyAdvisor.DEFAULT, ReplyRole.decode(payload).advisor)
        assertEquals(ReplyAdvisor.QINGSHENG,
            ReplyRole.decode(ReplyRole("role:${UUID.randomUUID()}", "小林", "", "v1", advisor = ReplyAdvisor.QINGSHENG)
                .encode()).advisor)
    }

    @Test fun `switching the advisor invalidates the previous suggestion without relabeling it`() {
        val context = ReplyContext("alice", listOf(ReplyMessage(1, "对方", 1, "明天见")))
        val suggestion = ReplySuggestion(listOf("好呀", "明天见"), "回应约定")
        val composer = ReplyComposition(identity = ReplyIdentitySetting(ReplyRelationship.FLIRT, advisor = ReplyAdvisor.QINGSHENG))
        assertTrue(composer.accept(context, suggestion, "", null, ReplyRelationship.FLIRT))
        assertTrue(composer.canUse)
        assertEquals(ReplyAdvisor.QINGSHENG, composer.result!!.advisor)

        composer.advisor = ReplyAdvisor.JUNSHI
        assertFalse(composer.canUse)
        assertEquals("", composer.previousText)
        assertEquals(ReplyAdvisor.QINGSHENG, composer.result!!.advisor)
        assertFalse(composer.accept(context, suggestion, "迟到结果", null, ReplyRelationship.FLIRT,
            requestedAdvisor = ReplyAdvisor.QINGSHENG))

        composer.advisor = ReplyAdvisor.QINGSHENG
        assertTrue(composer.canUse)
        assertEquals(suggestion.text, composer.previousText)
        val reopened = ReplyComposition(composer.result, ReplyIdentitySetting(ReplyRelationship.FLIRT, advisor = ReplyAdvisor.QINGSHENG))
        assertEquals(ReplyAdvisor.QINGSHENG, reopened.advisor)
        assertTrue(reopened.canUse)
    }

    @Test fun `topic batches are bound to the advisor that produced them`() {
        val context = ReplyContext("a", listOf(ReplyMessage(1, "对方", 1700000000000, "喜欢摄影")), requestedMessages = 30)
        val topics = (1..5).map { TopicSuggestion("话题$it", "最近还拍照吗$it", "从兴趣切入") }
        val key = TopicKey(context.fingerprint, 30, ReplyRelationship.FLIRT, "喜欢摄影", "", "2026-09-26", "",
            ReplyAdvisor.QINGSHENG)
        val composer = ReplyComposition(identity = ReplyIdentitySetting(ReplyRelationship.FLIRT, advisor = ReplyAdvisor.QINGSHENG))
        composer.historyLimit = 30
        assertTrue(composer.acceptTopics(context, topics, key, null))
        assertTrue(composer.nextTopic(key))
        assertFalse(composer.acceptTopics(context, topics, key.copy(advisor = ReplyAdvisor.JUNSHI), null))
        assertFalse(composer.nextTopic(key.copy(advisor = ReplyAdvisor.JUNSHI)))
        assertEquals(ReplyAdvisor.QINGSHENG, composer.result!!.topics!!.key.advisor)
    }

    @Test fun `each advisor keeps its own signature and the bridge keeps rituals out of the messages`() {
        val context = ReplyContext("alice", listOf(ReplyMessage(1, "对方", 1, "明天见")))
        val config = ReplySettings.fromInput("https://example.com", "key", "model")
        fun system(advisor: ReplyAdvisor) = ReplyProtocol.payload(config, context, "", "", "资料", advisor = advisor)
            .getJSONArray("messages").getJSONObject(0).getString("content")

        val junshi = system(ReplyAdvisor.JUNSHI)
        val qingsheng = system(ReplyAdvisor.QINGSHENG)
        assertTrue(junshi.contains("回复逻辑来自狗头军师"))
        assertFalse(junshi.contains("回复逻辑来自情圣"))
        assertTrue(qingsheng.contains("回复逻辑来自情圣"))
        assertFalse(qingsheng.contains("回复逻辑来自狗头军师"))
        listOf(junshi, qingsheng).forEach {
            assertTrue(it.contains("本次顾问："))
            assertTrue(it.contains("不把「平台 + 阶段」这类定位句写进消息"))
            assertTrue(it.contains("replies 只放我真正要发出去的句子"))
        }

        val evidence = ReplyProtocol.evidence(context, "", "", advisor = ReplyAdvisor.QINGSHENG)
        assertEquals("qingsheng", evidence.getJSONObject("advisor").getString("id"))
        assertEquals("情圣", evidence.getJSONObject("advisor").getString("label"))
    }

    @Test fun `topic requests carry the advisor and never ask for the coach opening`() {
        val context = ReplyContext("alice", listOf(ReplyMessage(1, "对方", 1, "喜欢摄影")), requestedMessages = 30)
        val config = ReplySettings.fromInput("https://example.com", "key", "model")
        val time = TopicTimeContext(java.time.ZonedDateTime.parse("2026-09-26T20:30:00+08:00[Asia/Shanghai]"),
            "八月十六", emptyList())
        val payload = TopicProtocol.payload(config, context, "", "", "资料", ReplyRelationship.FLIRT, time,
            advisor = ReplyAdvisor.QINGSHENG)
        val messages = payload.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("本次顾问：情圣"))
        assertTrue(system.contains("资料中的开场白、提问仪式、建档与档案流程不适用于言外"))
        assertEquals("qingsheng", JSONObject(messages.getJSONObject(1).getString("content"))
            .getJSONObject("advisor").getString("id"))
    }
}
