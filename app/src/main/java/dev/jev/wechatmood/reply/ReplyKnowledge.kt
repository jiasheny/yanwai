package dev.jev.wechatmood.reply

import android.content.Context

/** Original MIT sources are bundled, pinned and read locally, never fetched at chat time. */
object ReplyKnowledge {
    private val cached = mutableMapOf<Pair<ReplyAdvisor, ReplyRelationship>, String>()

    @Synchronized fun load(context: Context, relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED,
        advisor: ReplyAdvisor = ReplyAdvisor.DEFAULT): String {
        val key = advisor to relationship
        cached[key]?.let { return it }
        val module = if (context.packageName == "dev.jev.wechatmood") context else
            context.createPackageContext("dev.jev.wechatmood", Context.CONTEXT_IGNORE_SECURITY)
        val assets = module.assets
        val paths = ReplyKnowledgeCatalog.paths(advisor, relationship)
        return paths.joinToString("\n\n") { path ->
            "## 来源：$path\n" + assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.also { cached[key] = it }
    }
}
