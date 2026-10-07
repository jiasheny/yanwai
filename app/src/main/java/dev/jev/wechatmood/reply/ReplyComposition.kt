package dev.jev.wechatmood.reply

/** Local editor state. Opening, selecting and changing roles never initiate a request. */
class ReplyComposition(remembered: RememberedReply? = null, identity: ReplyIdentitySetting = ReplyIdentitySetting(),
    preferredLimit: Int = ReplyHistoryLimit.DEFAULT, var background: ContactBackground = ContactBackground()) {
    var relationship = identity.relationship
    var customRelationship = identity.customText
    var advisor: ReplyAdvisor = identity.advisor
    val activeCustomRelationship: String get() = relationship.customValue(customRelationship)
    val hasValidRelationship: Boolean get() = relationship != ReplyRelationship.OTHER || activeCustomRelationship.isNotBlank()
    var historyLimit = preferredLimit.also { require(ReplyHistoryLimit.valid(it)) }
        set(value) { require(ReplyHistoryLimit.valid(value)); field = value }
    var result: RememberedReply? = remembered?.copy(selectedPart = if (remembered.suggestion.parts.isEmpty()) 0
        else remembered.selectedPart.coerceIn(0, remembered.suggestion.parts.lastIndex))
        private set
    val selectedPart: Int get() = result?.selectedPart ?: 0
    val canUse: Boolean get() = hasValidRelationship && result?.context?.background == background && result?.relationship == relationship &&
        result?.customRelationship == activeCustomRelationship && result?.context?.requestedMessages == historyLimit &&
        result?.advisor == advisor
    val selectedText: String? get() = result?.takeIf { canUse }?.suggestion?.parts?.getOrNull(selectedPart)
    val previousText: String get() = result?.takeIf { canUse }?.suggestion?.text.orEmpty()

    fun select(index: Int) {
        val current = requireNotNull(result)
        require(index in current.suggestion.parts.indices)
        result = current.copy(selectedPart = index)
    }

    /** Convenience for callers that only have messages; teaching modes use the outcome overload. */
    fun accept(context: ReplyContext, suggestion: ReplySuggestion, direction: String, focusMessageId: Long?,
        requestedRelationship: ReplyRelationship, requestedCustomRelationship: String = "",
        requestedAdvisor: ReplyAdvisor = advisor): Boolean =
        accept(context, ReplyOutcome(suggestion), direction, focusMessageId, requestedRelationship,
            requestedCustomRelationship, requestedAdvisor)

    fun accept(context: ReplyContext, outcome: ReplyOutcome, direction: String, focusMessageId: Long?,
        requestedRelationship: ReplyRelationship, requestedCustomRelationship: String = "",
        requestedAdvisor: ReplyAdvisor = advisor): Boolean {
        if (!hasValidRelationship || context.background != background || requestedRelationship != relationship || context.requestedMessages != historyLimit ||
            requestedRelationship.customValue(requestedCustomRelationship) != activeCustomRelationship || requestedAdvisor != advisor) return false
        result = RememberedReply(context, outcome.suggestion, direction, focusMessageId, requestedRelationship,
            customRelationship = activeCustomRelationship, advisor = advisor, teaching = outcome.teaching)
        return true
    }

    fun acceptTopics(context: ReplyContext, topics: List<TopicSuggestion>, key: TopicKey, focusMessageId: Long?): Boolean {
        if (!hasValidRelationship || context.background != background || key.fingerprint != context.fingerprint || key.limit != historyLimit || context.requestedMessages != historyLimit ||
            key.relationship != relationship || key.customRelationship != activeCustomRelationship || key.advisor != advisor) return false
        val batch = TopicBatch(key, topics.toList())
        result = RememberedReply(context, batch.current.asReply(), key.notes, focusMessageId, relationship, topics = batch,
            customRelationship = activeCustomRelationship, advisor = advisor)
        return true
    }

    fun nextTopic(key: TopicKey): Boolean {
        val current = result ?: return false
        val batch = current.topics?.takeIf { canUse && it.key == key }?.next() ?: return false
        result = current.copy(suggestion = batch.current.asReply(), selectedPart = 0, topics = batch)
        return true
    }
}
