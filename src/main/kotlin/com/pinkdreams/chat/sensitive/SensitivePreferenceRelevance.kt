package com.pinkdreams.chat.sensitive

/**
 * Decides whether the CURRENT message alone gives enough reason to inject the
 * user's stored sensitive preferences into this turn's context. Deliberately
 * conservative: normal conversation must not receive the sensitive-preference
 * set merely because it exists.
 *
 * This is a minimal keyword heuristic, not an intent classifier — the codebase
 * has no existing intent/topic-classification mechanism to reuse (entitlement
 * and moderation are both no-op stubs; see Phase 4A). If a real intent signal
 * is added later, this is the seam to replace.
 */
object SensitivePreferenceRelevance {
    private val KEYWORDS = listOf(
        "romantic", "romance", "date", "dating", "relationship",
        "intimacy", "intimate", "affection", "cuddle",
        "sex", "sexual", "sexting", "turn on", "turned on", "kink", "fantasy",
        "attracted", "attraction", "flirt",
    )

    fun isRelevant(currentMessageContent: String): Boolean {
        val normalized = currentMessageContent.lowercase()
        return KEYWORDS.any { normalized.contains(it) }
    }
}
