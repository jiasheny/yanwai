package dev.jev.wechatmood.reply

import org.json.JSONObject

/**
 * The five-step skeleton, fixed on purpose: structure that repeats is what turns into a habit.
 * Every field is optional in the payload, but a complete analysis is required in 只拆解 mode.
 */
data class ReplyAnalysis(val facts: List<String>, val guess: List<String>, val purpose: String, val stage: String,
    val trend: String, val herNeed: String, val myGoal: String) {
    val isComplete: Boolean get() = facts.isNotEmpty() && purpose.isNotBlank() && stage.isNotBlank() && herNeed.isNotBlank()

    companion object {
        const val MAX_ITEMS = 3

        fun parse(json: JSONObject?): ReplyAnalysis? {
            json ?: return null
            fun items(key: String): List<String> {
                val array = json.optJSONArray(key) ?: return emptyList()
                check(array.length() <= MAX_ITEMS) { "分析条目过多" }
                return (0 until array.length()).map { index ->
                    (array.get(index) as? String ?: error("分析条目类型错误")).trim().take(MAX_ITEM_LENGTH)
                }.filter { it.isNotEmpty() }
            }
            fun text(key: String): String = json.optString(key).trim().take(MAX_TEXT_LENGTH)
            val analysis = ReplyAnalysis(items("facts"), items("guess"), text("purpose"), text("stage"),
                text("trend"), text("her_need"), text("my_goal"))
            check(analysis.isComplete) { "拆解不完整" }
            return analysis
        }

        private const val MAX_ITEM_LENGTH = 200
        private const val MAX_TEXT_LENGTH = 200
    }
}

/** Only produced when the user wrote an attempt of their own. Never invented by the app. */
data class ReplyCompare(val keep: List<String>, val fix: List<String>, val verdict: String, val score: Int?) {
    companion object {
        const val MAX_ITEMS = 2

        fun parse(json: JSONObject?): ReplyCompare? {
            json ?: return null
            fun items(key: String): List<String> {
                val array = json.optJSONArray(key) ?: return emptyList()
                check(array.length() <= MAX_ITEMS) { "对比条目过多" }
                return (0 until array.length()).map { index ->
                    (array.get(index) as? String ?: error("对比条目类型错误")).trim().take(200)
                }.filter { it.isNotEmpty() }
            }
            val compare = ReplyCompare(items("keep"), items("fix"), json.optString("verdict").trim().take(200),
                if (json.has("score")) json.optInt("score").takeIf { it in 0..10 } else null)
            check(compare.keep.isNotEmpty() && compare.fix.isNotEmpty() && compare.verdict.isNotBlank()) { "对比不完整" }
            return compare
        }
    }
}

/** Everything a teaching mode adds on top of the messages. Empty for 代打. */
data class ReplyTeaching(val analysis: ReplyAnalysis?, val compare: ReplyCompare?, val drill: String, val watch: String) {
    val isEmpty: Boolean get() = analysis == null && compare == null && drill.isBlank() && watch.isBlank()

    companion object { val NONE = ReplyTeaching(null, null, "", "") }
}

/** One model answer: the messages plus, in teaching modes, the reading behind them. */
data class ReplyOutcome(val suggestion: ReplySuggestion, val teaching: ReplyTeaching = ReplyTeaching.NONE) {
    /** Kept so callers that only need the combined text stay unchanged. */
    val text: String get() = suggestion.text
}
