package com.pinkdreams.chat.memory

import java.util.UUID

/**
 * Resolves the (userId, personaId) key actually used to read/write
 * [MemoryFactRepository] rows for a turn — separate from the persona used to
 * resolve Persona Core/engine/skill content, because those two concerns must
 * be allowed to diverge for a TEST conversation (Phase ADMIN-3 section 11).
 *
 * [ChatRequest.personaId][com.pinkdreams.chat.ChatRequest] must always stay
 * the REAL persona (context assembly, ownership checks, and message
 * provenance all depend on it matching `conversations.persona_id`). Memory
 * scope is therefore resolved by a dedicated hook rather than by ever
 * substituting `request.personaId` itself.
 */
fun interface MemoryScopeResolver {
    fun resolve(userId: UUID, personaId: UUID, conversationId: UUID): UUID
}

/** Default, pre-ADMIN-3 behavior: memory is scoped by the real (userId, personaId). */
object ProductionMemoryScope : MemoryScopeResolver {
    override fun resolve(userId: UUID, personaId: UUID, conversationId: UUID): UUID = personaId
}

/**
 * Test isolation (Phase ADMIN-3 section 11): every TEST conversation gets its
 * own memory scope — a dedicated "shadow" Personas row created once at
 * conversation-creation time (see TestChatService), never the real personaId,
 * so it can never collide with (and therefore can never read, write, or leak
 * into) any production memory row for that persona.
 *
 * This MUST be a real row in `personas`, not merely a distinct UUID:
 * `memory_facts.persona_id` carries a foreign-key constraint to `personas.id`
 * on real PostgreSQL. H2 (via SchemaUtils.createMissingTablesAndColumns)
 * creates no foreign keys at all, so a purely-derived UUID passed every
 * automated test yet violated that constraint on the first live write —
 * exactly the class of gap V006's CHECK-constraint bug was. See
 * ConversationsMemoryScopeForeignKeyTest / MemoryFactsForeignKeyLiveTest.
 */
class TestMemoryScope(private val scopePersonaId: UUID) : MemoryScopeResolver {
    override fun resolve(userId: UUID, personaId: UUID, conversationId: UUID): UUID = scopePersonaId
}
