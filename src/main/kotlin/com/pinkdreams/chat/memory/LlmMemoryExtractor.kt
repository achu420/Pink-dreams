package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Extracts durable user facts from a completed chat turn via a dedicated,
 * structured LLM call — never the persona conversation prompt.
 *
 * Provenance: only the turn's USER-authored message is treated as a source of
 * fact. The assistant's reply is included purely as context; the extraction
 * prompt explicitly forbids deriving a memory from assistant content. Both are
 * passed as their own native user/assistant ContextBlocks (not flattened text),
 * consistent with how the primary chat pipeline preserves role provenance.
 *
 * Duplicate prevention is intentionally NOT done here: MemoryService.record()
 * already rejects facts that duplicate an existing hot fact of the same
 * factType, so this extractor can propose the same fact on every turn without
 * ever creating a second copy — reusing the existing mechanism rather than
 * building a second one.
 */
class LlmMemoryExtractor(
    private val client: LlmClient,
    private val config: GenerationConfig = GenerationConfig(maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS),
    // Nullable and defaulted so every existing direct construction (tests,
    // mainly) keeps compiling unchanged. A lookup function rather than the
    // whole PersonaRepository, so this extractor doesn't need a live database
    // to be unit-tested. Production wiring (ChatEngineFactory) passes
    // `{ id -> personaRepository.findById(id)?.displayName }` so the
    // extraction prompt can name the persona and the grounding check below
    // has something to validate fact text against.
    private val personaNameLookup: ((UUID) -> String?)? = null,
) : MemoryExtractor {

    @Serializable
    private data class ExtractedFactDto(
        val fact: String = "",
        val factType: String = "",
        val criticality: String = "",
        // Speaker-attribution safeguard (see class doc): the model must say who
        // the fact is about, and must quote the user's own message as proof.
        // Both are validated below — neither is trusted on its own.
        val subject: String = "",
        val evidenceQuote: String = "",
    )

    @Serializable
    private data class ExtractionResponseDto(
        val facts: List<ExtractedFactDto> = emptyList(),
    )

    override fun extract(turn: CompletedTurn): ExtractionResult {
        val personaName = personaNameLookup?.invoke(turn.request.personaId)
        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", buildInstructions(personaName)),
                ContextBlock("user", turn.request.content),
                ContextBlock("assistant", turn.response.content),
            ),
            // Reused only to satisfy GenerationRequest's provenance fields for this
            // side-channel call; this is not persona generation and writes no message.
            engineVersionId = turn.context.engineVersionId,
            personaCoreVersionId = turn.context.personaCoreVersionId,
        )
        val generationRequest = GenerationRequest(
            requestId = turn.request.requestId,
            userId = turn.request.userId,
            conversationId = turn.request.conversationId,
            personaId = turn.request.personaId,
            engineVersionId = requireNotNull(context.engineVersionId) { "Completed turn is missing engineVersionId" },
            personaCoreVersionId = requireNotNull(context.personaCoreVersionId) { "Completed turn is missing personaCoreVersionId" },
            context = context,
            config = config,
        )

        val response = client.generate(generationRequest)
        val parsedDtos = parseFacts(response.content)

        // Speaker-attribution safeguard. The prompt already tells the model to
        // report facts about the user only, but a prompt is not an enforcement
        // mechanism — it is the only thing that previously stood between a
        // persona's self-introduction and a permanent "user fact". Each
        // candidate must now ALSO (a) declare subject="user" and (b) quote the
        // user's own message verbatim as evidence; a candidate failing either
        // check is dropped before it ever reaches MemoryCandidate/persistence,
        // regardless of how confidently the model phrased it.
        val (attributable, misattributed) = parsedDtos.partition { isAttributableToUser(it, turn.request.content, personaName) }
        misattributed.forEach {
            System.err.println(
                "MEMORY_EXTRACTION: rejected candidate for failing speaker-attribution check subject='${it.subject}' " +
                    "evidenceQuote='${it.evidenceQuote.take(80)}' fact='${it.fact.take(80)}'",
            )
        }

        val mapped = attributable
            .map { MemoryCandidate(fact = it.fact.trim(), factType = it.factType.trim().lowercase(), criticality = it.criticality.trim().lowercase()) }
        val validCandidates = mapped.filter(::isValidCandidate).take(MAX_FACTS_PER_TURN)
        // A candidate dropped for an invalid factType/criticality is a prompt or
        // taxonomy mismatch, not "nothing to remember" — say so rather than
        // discarding it without a trace.
        mapped.filterNot(::isValidCandidate).forEach {
            System.err.println(
                "MEMORY_EXTRACTION: rejected candidate factType='${it.factType}' criticality='${it.criticality}' " +
                    "fact='${it.fact.take(80)}'",
            )
        }

        return ExtractionResult(newFacts = validCandidates)
    }

    /**
     * Returns false (reject) unless the candidate both (a) declares itself to
     * be about the user and (b) is grounded in text that actually appears in
     * the user's own message. (b) is what catches a persona self-introduction
     * even if the model mislabels (a) — "I'm Simran" only ever appears in the
     * assistant block, so no verbatim quote of it can be found inside
     * turn.request.content. As an extra, independent guard, a fact naming the
     * persona itself is rejected outright even if it happens to pass (a) and
     * (b) (e.g. the user's own message happens to contain the persona's name).
     */
    private fun isAttributableToUser(candidate: ExtractedFactDto, userMessage: String, personaName: String?): Boolean {
        if (candidate.subject.trim().lowercase() != "user") return false
        if (candidate.evidenceQuote.isBlank()) return false
        if (!normalize(userMessage).contains(normalize(candidate.evidenceQuote))) return false
        if (personaName != null && candidate.fact.lowercase().contains(personaName.lowercase())) return false
        return true
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun parseFacts(rawContent: String): List<ExtractedFactDto> {
        // Reasoning models routinely prepend prose to the JSON in `content`; take
        // the JSON object out of the response rather than requiring the whole
        // response to be JSON. See JsonResponseExtractor.
        val cleaned = com.pinkdreams.llm.JsonResponseExtractor.extractJsonObject(rawContent) ?: rawContent.trim()
        return try {
            json.decodeFromString<ExtractionResponseDto>(cleaned).facts
        } catch (e: Exception) {
            // Malformed/unparseable model output must not crash extraction — it is
            // equivalent to "nothing worth remembering" for this turn. It is still
            // logged: silently treating a broken response as "nothing to remember"
            // is indistinguishable from the model genuinely finding nothing.
            System.err.println(
                "MEMORY_EXTRACTION: unparseable extraction response (${e.javaClass.simpleName}: ${e.message}); " +
                    "raw=${cleaned.take(300)}",
            )
            emptyList()
        }
    }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else {
            trimmed
        }
    }

    private fun isValidCandidate(candidate: MemoryCandidate): Boolean =
        candidate.fact.isNotBlank() &&
            candidate.fact.length <= com.pinkdreams.chat.memory.MemoryService.MAX_FACT_LENGTH &&
            candidate.factType in com.pinkdreams.chat.memory.MemoryService.FACT_TYPES &&
            candidate.criticality in com.pinkdreams.chat.memory.MemoryService.CRITICALITIES

    companion object {
        private const val MAX_FACTS_PER_TURN = 5
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 256

        private val json = Json { ignoreUnknownKeys = true }

        private fun buildInstructions(personaName: String?): String {
            val personaClause = if (personaName != null) {
                """

                The message with role "assistant" above is written by a fictional persona
                named "$personaName" — not the user. Anything $personaName says about
                herself/himself/itself (her/his/its own name, identity, traits, or
                situation) is NEVER a fact about the user, even if phrased in the first
                person (e.g. "I'm $personaName" is the PERSONA's self-introduction, not a
                statement from the user).
                """.trimIndent()
            } else {
                ""
            }
            return """
                You are a memory-extraction system. Your ONLY job is to identify durable,
                factual information about the USER that would be useful to remember in
                future conversations with them.
                $personaClause

                Rules:
                - Use ONLY the message with role "user" as the source of facts about the user.
                - The message with role "assistant" is provided as context only. NEVER create
                  a memory based on something the assistant said, claimed, guessed, or assumed
                  about the user.
                - If the user's own message names or describes a THIRD PARTY — anyone other
                  than the user themselves, e.g. "my colleague Simran" or "my friend's dog" —
                  do NOT attribute that third party's name, traits, or circumstances to the
                  user. Only extract facts the user states about themselves.
                - Extract a fact ONLY if the user explicitly stated it about themselves. Do not
                  infer, guess, or generalize beyond what was explicitly said.
                - Do NOT extract temporary states, moods, or one-off requests
                  (e.g. "I'm hungry right now", "tell me a joke", "I think I'll order pizza tonight").
                - Prefer extracting NOTHING over inventing or over-saving information.
                - Each fact must be a short, self-contained statement, e.g. "user lives in Delhi".
                - factType must be exactly one of: past_event, future_event, mindset, weakness,
                  aspiration, desire, habit, want, interest.
                  Use "interest" for durable personal details (name, location, likes/preferences)
                  that do not clearly fit another category.
                - criticality must be exactly one of: low, medium, high.
                - subject must be exactly "user" for every fact you return — you must only
                  return facts about the user. If a candidate fact is about anyone else
                  (the assistant/persona or a third party), do not include it at all.
                - evidenceQuote must be a short quote copied VERBATIM from the user's own
                  message (the "user" role) that directly supports the fact. Never quote the
                  assistant's message as evidence, and never fabricate a quote that does not
                  appear in the user's message.

                Respond with STRICT JSON only — no prose, no markdown fences — matching exactly:
                {"facts": [{"fact": "<short factual statement>", "factType": "<type>", "criticality": "<level>", "subject": "user", "evidenceQuote": "<verbatim quote from the user's message>"}]}

                If there is nothing worth remembering, respond with exactly: {"facts": []}
            """.trimIndent()
        }
    }
}
