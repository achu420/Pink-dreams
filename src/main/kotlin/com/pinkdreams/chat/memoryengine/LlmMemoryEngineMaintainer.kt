package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * LLM-backed Memory Engine maintenance — follows the same shape as
 * LlmMemoryExtractor/LlmContinuitySummarizer/LlmIntentDiscovery: a small,
 * dedicated, structured LLM call, never the persona generation prompt.
 *
 * If no Memory Engine version is active, this returns an empty result
 * immediately WITHOUT calling the LLM at all — "Memory Engine unavailable"
 * (Phase D section 32) degrades to doing nothing, not to a hardcoded
 * fallback prompt.
 */
class LlmMemoryEngineMaintainer(
    private val client: LlmClient,
    private val memoryEngineRepository: MemoryEngineRepository,
    private val config: GenerationConfig = GenerationConfig(maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS),
) : MemoryEngineMaintainer {

    @Serializable
    private data class ChangeDto(
        val action: String = "IGNORE",
        val memoryId: String? = null,
        val memoryType: String? = null,
        val content: String? = null,
        val criticality: String? = null,
    )

    @Serializable
    private data class MaintenanceResponseDto(
        val userMemoryChanges: List<ChangeDto> = emptyList(),
        val personaMemoryChanges: List<ChangeDto> = emptyList(),
    )

    override fun maintain(
        turn: CompletedTurn,
        batch: List<MessageRepository.Message>,
        userWorkingMemory: List<MemoryFactRepository.MemoryFact>,
        personaWorkingMemory: List<MemoryFactRepository.MemoryFact>,
    ): MemoryMaintenanceResult {
        val engine = memoryEngineRepository.getActiveEngine() ?: return MemoryMaintenanceResult()
        if (batch.isEmpty()) return MemoryMaintenanceResult()

        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", engine.content),
                ContextBlock("user", buildInputPayload(batch, userWorkingMemory, personaWorkingMemory)),
            ),
            engineVersionId = turn.context.engineVersionId,
            personaCoreVersionId = turn.context.personaCoreVersionId,
        )
        val engineVersionId = context.engineVersionId ?: return MemoryMaintenanceResult()
        val personaCoreVersionId = context.personaCoreVersionId ?: return MemoryMaintenanceResult()

        val generationRequest = GenerationRequest(
            requestId = turn.request.requestId,
            userId = turn.request.userId,
            conversationId = turn.request.conversationId,
            personaId = turn.request.personaId,
            engineVersionId = engineVersionId,
            personaCoreVersionId = personaCoreVersionId,
            context = context,
            config = config,
        )

        val response = client.generate(generationRequest)
        return parse(response.content)
    }

    private fun buildInputPayload(
        batch: List<MessageRepository.Message>,
        userWorkingMemory: List<MemoryFactRepository.MemoryFact>,
        personaWorkingMemory: List<MemoryFactRepository.MemoryFact>,
    ): String = buildString {
        append("RECENT CONVERSATION\n\n")
        batch.forEachIndexed { index, message ->
            append(index + 1).append(". ").append(message.role).append(":\n")
            append(message.content).append("\n\n")
        }
        append("CURRENT USER MEMORY (id, type, content, learned)\n\n")
        appendWorkingMemory(userWorkingMemory)
        append("\nCURRENT PERSONA MEMORY (id, type, content, learned)\n\n")
        appendWorkingMemory(personaWorkingMemory)
    }

    private fun StringBuilder.appendWorkingMemory(facts: List<MemoryFactRepository.MemoryFact>) {
        if (facts.isEmpty()) {
            append("(none)\n")
            return
        }
        facts.forEach { fact ->
            append("- id=").append(fact.id).append(" [").append(fact.factType).append("] ").append(fact.fact)
            append("\n  learned: ").append(fact.learnedAt.format(DATE_FORMAT)).append('\n')
        }
    }

    private fun parse(rawContent: String): MemoryMaintenanceResult {
        // Reasoning models routinely prepend prose to the JSON in `content`; take
        // the JSON object out of the response rather than requiring the whole
        // response to be JSON. See JsonResponseExtractor.
        val cleaned = com.pinkdreams.llm.JsonResponseExtractor.extractJsonObject(rawContent) ?: stripMarkdownFences(rawContent)
        val dto = try {
            json.decodeFromString<MaintenanceResponseDto>(cleaned)
        } catch (e: Exception) {
            // Logged rather than swallowed: an unparseable maintenance response is
            // a prompt/model problem, not "no maintenance needed".
            System.err.println(
                "MEMORY_ENGINE: unparseable maintenance response (${e.javaClass.simpleName}: ${e.message}); " +
                    "raw=${cleaned.take(300)}",
            )
            return MemoryMaintenanceResult()
        }
        return MemoryMaintenanceResult(
            userMemoryChanges = dto.userMemoryChanges.mapNotNull(::toChange),
            personaMemoryChanges = dto.personaMemoryChanges.mapNotNull(::toChange),
        )
    }

    private fun toChange(dto: ChangeDto): MemoryChange? {
        val action = try {
            MemoryChangeAction.valueOf(dto.action.trim().uppercase())
        } catch (_: Exception) {
            return null // unknown action — drop this one change, never the whole batch
        }
        val memoryId = dto.memoryId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return MemoryChange(
            action = action,
            memoryId = memoryId,
            memoryType = dto.memoryType,
            content = dto.content,
            criticality = dto.criticality,
        )
    }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else {
            trimmed
        }
    }

    companion object {
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 800
        private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
        private val json = Json { ignoreUnknownKeys = true }
    }
}
