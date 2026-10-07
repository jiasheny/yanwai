package dev.jev.wechatmood.reply

/**
 * Selected methodology. Upstream knowledge is bundled verbatim and read locally at chat time;
 * this enum only decides which set of original documents one request loads.
 */
enum class ReplyAdvisor(
    val id: String,
    val label: String,
    val shortLabel: String,
    val sourceUrl: String,
    val licenseAsset: String,
    val revision: String,
    val note: String,
) {
    JUNSHI("junshi", "狗头军师", "军师", "https://github.com/shengjidaguai-china/goutoujunshi", "goutoujunshi/LICENSE",
        "6db7354a4002dc7c448a9c87ffdad8132570c9d3", "通用沟通、情绪回应、家庭与职场"),
    QINGSHENG("qingsheng", "情圣", "情圣", "https://github.com/tomwong001/qingsheng-skill", "qingsheng/LICENSE",
        "fd762df2e0ce4b8ec68b0087ba7178761045bd1a", "恋爱推进、话术生成、阶段判断");

    companion object {
        val DEFAULT = JUNSHI

        /** Unknown ids fall back instead of failing, so an older build never breaks on newer data. */
        fun of(id: String?): ReplyAdvisor = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
