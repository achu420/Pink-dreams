package com.pinkdreams.chat.continuity

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient

/**
 * Generates/updates the conversation's continuity summary via a dedicated,
 * structured LLM call — never the persona conversation prompt. Uses the
 * existing LlmClient abstraction only (no direct provider coupling).
 *
 * The prompt explicitly instructs the model to let newer information replace
 * older/contradicted information within the summary itself, and to phrase the
 * summary as established/past context rather than as current-moment fact —
 * reinforcing (together with block ordering in RepositoryContextAssembler)
 * that recent native conversation always outranks this summary.
 */
class LlmContinuitySummarizer(
    private val client: LlmClient,
    private val config: GenerationConfig = GenerationConfig(maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS),
) : ContinuitySummarizer {

    override fun summarize(turn: CompletedTurn, previousSummary: String?, olderMessages: List<ContextBlock>): String? {
        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", INSTRUCTIONS),
                ContextBlock("system", "PREVIOUS SUMMARY:\n${previousSummary?.takeIf { it.isNotBlank() } ?: "(none yet)"}"),
            ) + olderMessages,
            engineVersionId = requireNotNull(turn.context.engineVersionId) { "Completed turn is missing engineVersionId" },
            personaCoreVersionId = requireNotNull(turn.context.personaCoreVersionId) { "Completed turn is missing personaCoreVersionId" },
        )
        val generationRequest = GenerationRequest(
            requestId = turn.request.requestId,
            userId = turn.request.userId,
            conversationId = turn.request.conversationId,
            personaId = turn.request.personaId,
            engineVersionId = context.engineVersionId!!,
            personaCoreVersionId = context.personaCoreVersionId!!,
            context = context,
            config = config,
        )

        val response = client.generate(generationRequest)
        return response.content.trim().takeIf { it.isNotBlank() }
    }

    companion object {
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 300

        private val INSTRUCTIONS = """
            You maintain a compact CONTINUITY SUMMARY of an ongoing conversation's
            older content — content that has just scrolled outside the active
            recent-message window and would otherwise be lost.

            You will be given the PREVIOUS summary (may be "(none yet)") followed by
            NEW older messages (native user/assistant turns) that need to be folded in.

            Produce an UPDATED summary that:
            - Preserves important things the user disclosed (facts, preferences, plans).
            - Preserves meaningful previous topics and conversational developments.
            - Preserves commitments or promises made by either party that still matter.
            - Preserves notable things the persona said or established about herself.
            - Preserves clearly unresolved conversational threads, when present.
            - Omits small talk, filler, and anything not useful for future continuity.
            - Is compact — a few sentences, never a transcript or verbatim quotes.
            - If the new messages contradict something in the previous summary (for
              example a changed fact), the updated summary must reflect the NEWER
              information and drop the outdated version — never keep both as if both
              were still true.
            - Is phrased as established/past context ("user mentioned...", "they
              discussed..."), never as something happening right now — this summary
              is background only, and any more recent message always takes priority
              over it if the two ever conflict.

            Respond with the updated summary text ONLY — no preamble, no labels, no
            JSON, no markdown — under 800 characters. If there is truly nothing worth
            retaining, respond with an empty string.
        """.trimIndent()
    }
}
