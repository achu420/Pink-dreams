package com.pinkdreams.chat.sensitive

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.SensitivePreferenceRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitivePreferenceServiceTest {

    private fun fixture(): SensitivePreferenceService {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        return SensitivePreferenceService(SensitivePreferenceRepository(db))
    }

    @Test
    fun `recordExplicit always writes provenance explicit and there is no method to write inferred`() {
        val service = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val created = service.recordExplicit(userId, personaId, "romantic", "preference", "likes slow romance")

        assertEquals("explicit", created.provenance, "recordExplicit must always persist provenance=explicit")
        // There is deliberately no SensitivePreferenceService method that accepts
        // provenance="inferred" — an ambiguous/inferred statement (e.g. a repeated
        // joke) has no code path to becoming a durable sensitive preference.
    }

    @Test
    fun `selectForContext only returns active preferences for the given user and persona`() {
        val service = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val otherPersona = UUID.randomUUID()

        service.recordExplicit(userId, personaId, "romantic", "preference", "OWN_PREFERENCE")
        service.recordExplicit(otherUser, personaId, "romantic", "preference", "OTHER_USER_PREFERENCE")
        service.recordExplicit(userId, otherPersona, "romantic", "preference", "OTHER_PERSONA_PREFERENCE")

        val results = service.selectForContext(userId, personaId)
        assertEquals(1, results.size)
        assertEquals("OWN_PREFERENCE", results.single().content)
    }

    @Test
    fun `recordExplicit called twice for the same category and type supersedes rather than duplicating`() {
        val service = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        service.recordExplicit(userId, personaId, "romantic", "preference", "likes X")
        service.recordExplicit(userId, personaId, "romantic", "preference", "no longer likes X")

        val results = service.selectForContext(userId, personaId)
        assertEquals(1, results.size, "Only one active preference should exist per category+type")
        assertEquals("no longer likes X", results.single().content)
    }

    @Test
    fun `a deactivated preference no longer reaches context selection`() {
        val service = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val created = service.recordExplicit(userId, personaId, "sexual", "boundary", "TEMP_BOUNDARY")
        assertTrue(service.selectForContext(userId, personaId).isNotEmpty())

        service.deactivate(created.id)
        assertFalse(service.selectForContext(userId, personaId).any { it.id == created.id })
    }

    @Test
    fun `relevance heuristic does not flag ordinary conversation`() {
        assertFalse(SensitivePreferenceRelevance.isRelevant("How's the weather today?"))
        assertFalse(SensitivePreferenceRelevance.isRelevant("Can you help me plan my week?"))
    }

    @Test
    fun `relevance heuristic flags romantic and intimacy related current messages`() {
        assertTrue(SensitivePreferenceRelevance.isRelevant("I want to talk about dating and romance"))
        assertTrue(SensitivePreferenceRelevance.isRelevant("Let's get intimate"))
        assertTrue(SensitivePreferenceRelevance.isRelevant("Tell me about your sexual preferences"))
    }
}
