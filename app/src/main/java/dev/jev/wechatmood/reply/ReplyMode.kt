package dev.jev.wechatmood.reply

/**
 * What one request should produce. Orthogonal to the advisor (which methodology) and to the
 * relationship (who the other person is). Kept per request on purpose: learning is a habit,
 * not a property of a contact, so it is never stored next to a contact identity.
 */
enum class ReplyMode(val id: String, val label: String, val shortLabel: String, val note: String) {
    ASSIST("assist", "代打", "代打", "直接给我可以发送的消息"),
    PREDICT("predict", "先猜后给", "先猜", "先写我的回法，再对比"),
    READ("read", "只拆解", "拆解", "只给分析，话术我自己写"),
    GRADE("grade", "批改", "批改", "我写完整回复，它只改");

    /** Modes that take a draft from the user before the request. */
    val wantsDraft: Boolean get() = this == PREDICT || this == GRADE

    /** Modes whose contract requires the draft, so the request cannot be sent without it. */
    val requiresDraft: Boolean get() = this == GRADE

    companion object {
        val DEFAULT = ASSIST

        fun of(id: String?): ReplyMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
