package com.pinkdreams.admin2

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase ADMIN-2 sections 19-21, 31: persona PROFILE metadata, and the
 * separation between profile metadata and the versioned Persona Core.
 */
class PersonaMetadataTest {

    private fun repos(): Pair<PersonaRepository, PersonaCoreVersionRepository> {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        return PersonaRepository(db) to PersonaCoreVersionRepository(db)
    }

    private fun PersonaRepository.newPersona() = create(
        slug = "simran", displayName = "Simran", gender = "female",
        orientation = "straight", apparentAge = 26, languageProfile = mapOf("primary" to "en"),
    )

    @Test
    fun `a new persona starts with empty profile metadata`() {
        val (personas, _) = repos()
        val persona = personas.newPersona()

        assertNull(persona.bio)
        assertNull(persona.city)
        assertNull(persona.occupation)
        assertNull(persona.interests)
        assertEquals(emptyList(), persona.tags)
    }

    @Test
    fun `profile metadata round trips through the database`() {
        val (personas, _) = repos()
        val persona = personas.newPersona()

        personas.update(
            id = persona.id,
            bio = "Architect in Pune who likes long conversations.",
            city = "Pune",
            occupation = "Architect",
            interests = "Travel, Music, Photography",
            tags = listOf("warm", "playful", "travel"),
        )

        val reloaded = personas.findById(persona.id)!!
        assertEquals("Architect in Pune who likes long conversations.", reloaded.bio)
        assertEquals("Pune", reloaded.city)
        assertEquals("Architect", reloaded.occupation)
        assertEquals("Travel, Music, Photography", reloaded.interests)
        assertEquals(listOf("warm", "playful", "travel"), reloaded.tags)
    }

    @Test
    fun `omitting a field leaves it unchanged and an empty string clears it`() {
        val (personas, _) = repos()
        val persona = personas.newPersona()
        personas.update(id = persona.id, bio = "original bio", city = "Pune")

        // Omitted => unchanged.
        personas.update(id = persona.id, occupation = "Architect")
        assertEquals("original bio", personas.findById(persona.id)!!.bio)
        assertEquals("Pune", personas.findById(persona.id)!!.city)

        // Empty string => cleared.
        personas.update(id = persona.id, bio = "")
        assertNull(personas.findById(persona.id)!!.bio)
        assertEquals("Pune", personas.findById(persona.id)!!.city, "Clearing one field must not clear another")
    }

    @Test
    fun `updating profile metadata never touches identity fields`() {
        val (personas, _) = repos()
        val persona = personas.newPersona()

        personas.update(id = persona.id, bio = "a bio", tags = listOf("x"))

        val reloaded = personas.findById(persona.id)!!
        assertEquals("simran", reloaded.slug)
        assertEquals("Simran", reloaded.displayName)
        assertEquals("female", reloaded.gender)
        assertEquals("straight", reloaded.orientation)
        assertEquals(26, reloaded.apparentAge)
        assertEquals(mapOf("primary" to "en"), reloaded.languageProfile)
    }

    @Test
    fun `tags are normalised - trimmed, de-duplicated, blanks dropped`() {
        val (personas, _) = repos()
        val persona = personas.newPersona()

        personas.update(id = persona.id, tags = listOf(" warm ", "warm", "", "  ", "playful"))

        assertEquals(listOf("warm", "playful"), personas.findById(persona.id)!!.tags)
    }

    @Test
    fun `an empty tag list clears the tags`() {
        val (personas, _) = repos()
        val persona = personas.newPersona()
        personas.update(id = persona.id, tags = listOf("a", "b"))

        personas.update(id = persona.id, tags = emptyList())

        assertEquals(emptyList(), personas.findById(persona.id)!!.tags)
    }

    // ---------- section 21: profile is not the Persona Core ----------

    @Test
    fun `profile metadata and persona core are stored and versioned independently`() {
        val (personas, cores) = repos()
        val persona = personas.newPersona()
        val core = cores.createNextVersion(
            personaId = persona.id, content = BaselineConfiguration.SIMRAN_PERSONA_CORE, author = "admin",
        )
        personas.activateCoreVersion(persona.id, cores.publishCoreVersion(core.id).id)
        val activeCoreBefore = personas.findById(persona.id)!!.activeCoreVersionId

        // Editing the profile must not create a Persona Core version or change
        // which one is active — profile is metadata, Core is behavior.
        personas.update(id = persona.id, bio = "new bio", city = "Mumbai")

        assertEquals(1, cores.findForPersona(persona.id).size, "Editing profile must not version the Persona Core")
        assertEquals(activeCoreBefore, personas.findById(persona.id)!!.activeCoreVersionId)
        assertEquals(BaselineConfiguration.SIMRAN_PERSONA_CORE, cores.findById(activeCoreBefore!!)!!.content)
    }

    @Test
    fun `the canonical persona core does not restate profile metadata fields`() {
        // Section 21: do not duplicate the same information across layers. The
        // Core describes who she is; the profile columns describe her record.
        val core = BaselineConfiguration.SIMRAN_PERSONA_CORE
        assertTrue(!core.contains("occupation:"), "Profile field labels belong to the metadata layer, not the Core")
        assertTrue(!core.contains("tags:"))
    }
}
