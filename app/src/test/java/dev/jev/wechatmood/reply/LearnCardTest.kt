package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.PersistentAnalysisCacheTest.Jdbc
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cards only need to be honest: same idea counts once, repetition counts up. */
class LearnCardTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = LearnCardStore(Jdbc(folder.newFile()))

    @Test fun `the same principle said twice is one card with two hits`() {
        val cards = store()
        val first = cards.record("先接情绪，再给方案", "她说今天好累", "qingsheng", 1000L)!!
        assertEquals(1, first.hits)
        assertEquals(1000L, first.dueAt)
        val again = cards.record("先接情绪,再给方案", "换了一个场景", "qingsheng", 2000L)!!
        assertEquals(first.id, again.id)
        assertEquals(2, again.hits)
        // The first situation stays the anchor, only the counters move.
        assertEquals("她说今天好累", again.evidence)
        assertEquals(2000L, again.updatedAt)
        assertEquals(1, cards.count())
    }

    @Test fun `principles with nothing to compare are refused`() {
        val cards = store()
        assertNull(cards.record("   ", "她说今天好累", "junshi", 1L))
        assertNull(cards.record("！？。、", "她说今天好累", "junshi", 1L))
        assertEquals(0, cards.count())
    }

    @Test fun `a digest ignores case spacing and punctuation but not wording`() {
        assertEquals(LearnCard.id("先接情绪，再给方案"), LearnCard.id("先接情绪 再给方案"))
        assertEquals(LearnCard.id("Be Direct"), LearnCard.id("be direct!"))
        assertNotEquals(LearnCard.id("先接情绪"), LearnCard.id("先给方案"))
    }

    @Test fun `list ranks by hits and then by recency`() {
        val cards = store()
        cards.record("A", "", "junshi", 10L)
        cards.record("B", "", "junshi", 20L)
        cards.record("B", "", "junshi", 30L)
        cards.record("C", "", "junshi", 40L)
        assertEquals(listOf("B", "C", "A"), cards.list().map { it.principle })
        assertEquals(listOf("B"), cards.list(limit = 1).map { it.principle })
    }

    @Test fun `the oldest weakest card steps aside at capacity`() {
        val cards = store()
        repeat(LearnCardStore.MAX_CARDS) { index -> cards.record("原则$index", "", "junshi", index.toLong()) }
        assertEquals(LearnCardStore.MAX_CARDS, cards.count())
        val fresh = cards.record("最新的原则", "", "junshi", 9_999L)!!
        assertEquals(1, fresh.hits)
        assertEquals(LearnCardStore.MAX_CARDS, cards.count())
        val kept = cards.list(LearnCardStore.MAX_CARDS).map { it.principle }
        assertFalse(kept.contains("原则0"))
        assertTrue(kept.contains("最新的原则"))
    }

    @Test fun `review moves a remembered card up a box and a forgotten one back to the first`() {
        val cards = store()
        val card = cards.record("p", "", "junshi", 0L)!!
        assertEquals(1, card.box)
        val up = cards.review(card.id, remembered = true, now = 1_000L)!!
        assertEquals(2, up.box)
        assertEquals(1_000L + LearnCard.INTERVALS[1], up.dueAt)
        assertEquals(3, cards.review(card.id, remembered = true, now = 2_000L)!!.box)
        // The top box stays the top box, and forgetting sends the card back to the start.
        assertEquals(4, cards.review(card.id, remembered = true, now = 3_000L)!!.box)
        assertEquals(4, cards.review(card.id, remembered = true, now = 4_000L)!!.box)
        val back = cards.review(card.id, remembered = false, now = 5_000L)!!
        assertEquals(1, back.box)
        assertEquals(5_000L + LearnCard.INTERVALS[0], back.dueAt)
        assertNull(cards.review("0".repeat(64), remembered = true, now = 1L))
    }

    @Test fun `due returns only what the schedule says is ready`() {
        val cards = store()
        cards.record("p", "", "junshi", 0L)
        assertEquals(1, cards.due(now = 1L).size)
        assertEquals(0, cards.due(now = -1L).size)
        assertEquals(1, cards.due(now = 1L, limit = 3).size)
    }

    @Test fun `a row that cannot be decoded is ignored instead of breaking the store`() {
        val db = Jdbc(folder.newFile())
        val cards = LearnCardStore(db)
        cards.record("p", "", "junshi", 0L)
        db.execute("INSERT OR REPLACE INTO learn_cards(card_id, payload) VALUES(?, ?)", listOf("a".repeat(64), "{ not json"))
        assertEquals(2, cards.count())
        assertEquals(listOf("p"), cards.list().map { it.principle })
    }
}
