package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheDatabase
import dev.jev.wechatmood.core.AnalysisCacheKey
import org.json.JSONObject

/** One principle worth remembering, with the situation it first showed up in. */
class LearnCard(val id: String, val principle: String, val evidence: String, val source: String,
    val hits: Int, val box: Int, val dueAt: Long, val updatedAt: Long) {
    init {
        require(id.matches(Regex("[0-9a-f]{64}")))
        require(principle.isNotBlank() && principle.length <= MAX_PRINCIPLE)
        require(evidence.length <= MAX_EVIDENCE)
        require(source.length <= 64 && hits >= 1 && box in 1..MAX_BOX)
    }

    fun encode(): String = JSONObject().put("principle", principle).put("evidence", evidence).put("source", source)
        .put("hits", hits).put("box", box).put("dueAt", dueAt).put("updatedAt", updatedAt).toString()

    companion object {
        const val MAX_PRINCIPLE = 200
        const val MAX_EVIDENCE = 200
        const val MAX_BOX = 4

        /** Review interval per box, in milliseconds: one day, three, seven, twenty-one. */
        val INTERVALS = longArrayOf(1, 3, 7, 21).map { it * 24L * 60 * 60 * 1000 }.toLongArray()

        /**
         * The same idea said twice is one card, so the identity ignores case, spacing and
         * punctuation: only letters and digits take part in the digest.
         */
        fun id(principle: String): String = AnalysisCacheKey.digest("learn-card-v1", normalize(principle))

        fun normalize(principle: String): String = principle.lowercase().filter { it.isLetterOrDigit() }

        fun decode(id: String, payload: String): LearnCard {
            require(payload.length <= 2048)
            val json = JSONObject(payload)
            return LearnCard(id, json.getString("principle"), json.optString("evidence"), json.optString("source"),
                json.optInt("hits", 1), json.optInt("box", 1), json.optLong("dueAt"), json.optLong("updatedAt"))
        }
    }
}

/**
 * Cards live in the app's own database next to the reply identity and are only ever written
 * through the UID-restricted provider. Nothing here is fetched from the network.
 */
class LearnCardStore(private val db: AnalysisCacheDatabase) {
    init { db.execute("CREATE TABLE IF NOT EXISTS learn_cards (card_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)") }

    fun count(): Int = db.query("SELECT COUNT(*) FROM learn_cards", emptyList())?.toIntOrNull() ?: 0

    /** Returns the stored card with its new hit count, or null when there is nothing worth keeping. */
    @Synchronized fun record(principle: String, evidence: String, source: String, now: Long): LearnCard? {
        val clean = principle.trim().take(LearnCard.MAX_PRINCIPLE)
        if (LearnCard.normalize(clean).isBlank()) return null
        val id = LearnCard.id(clean)
        val existing = find(id)
        val card = if (existing != null) existing.copy(hits = existing.hits + 1, updatedAt = now) else {
            // Only a brand new card can push the store over its cap; drop the weakest one instead.
            if (count() >= MAX_CARDS) weakest()?.let { db.execute("DELETE FROM learn_cards WHERE card_id = ?", listOf(it)) }
            LearnCard(id, clean, evidence.trim().take(LearnCard.MAX_EVIDENCE), source.trim().take(64), 1, 1, now, now)
        }
        db.execute("INSERT OR REPLACE INTO learn_cards(card_id, payload) VALUES(?, ?)", listOf(id, card.encode()))
        return card
    }

    /** Most seen first: repetition is the honest signal that a principle keeps applying. */
    fun list(limit: Int = 50): List<LearnCard> = keys().mapNotNull(::find)
        .sortedWith(compareByDescending<LearnCard> { it.hits }.thenByDescending { it.updatedAt }).take(limit)

    /** Spaced review: a remembered card moves up a box, a forgotten one starts over. */
    @Synchronized fun review(id: String, remembered: Boolean, now: Long): LearnCard? {
        val card = find(id) ?: return null
        val box = if (remembered) (card.box + 1).coerceAtMost(LearnCard.MAX_BOX) else 1
        val updated = card.copy(box = box, dueAt = now + LearnCard.INTERVALS[box - 1], updatedAt = now)
        db.execute("INSERT OR REPLACE INTO learn_cards(card_id, payload) VALUES(?, ?)", listOf(id, updated.encode()))
        return updated
    }

    fun due(now: Long, limit: Int = 3): List<LearnCard> = list(LearnCard.MAX_PRINCIPLE).filter { it.dueAt <= now }.take(limit)

    private fun keys(): List<String> = buildList {
        var cursor = ""
        while (true) {
            val key = db.query("SELECT card_id FROM learn_cards WHERE card_id > ? ORDER BY card_id LIMIT 1", listOf(cursor)) ?: break
            add(key); cursor = key
        }
    }

    /** A row that cannot be decoded is ignored instead of failing the whole store. */
    private fun find(id: String): LearnCard? = db.query("SELECT payload FROM learn_cards WHERE card_id = ?", listOf(id))
        ?.let { payload -> runCatching { LearnCard.decode(id, payload) }.getOrNull() }

    private fun weakest(): String? = keys().mapNotNull(::find)
        .minWithOrNull(compareBy({ it.hits }, { it.updatedAt }))?.id

    companion object { const val MAX_CARDS = 200 }
}
