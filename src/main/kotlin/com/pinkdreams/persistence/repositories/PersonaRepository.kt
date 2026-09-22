package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.database.defaultNow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

open class PersonaRepository(private val db: Database) {
    data class Persona(
        val id: UUID,
        val slug: String,
        val displayName: String,
        val gender: String,
        val orientation: String,
        val apparentAge: Int,
        val languageProfile: Map<String, String>,
        val activeCoreVersionId: UUID?,
        val status: String,
        // Phase ADMIN-2 — PROFILE metadata (what describes this persona),
        // distinct from the Persona Core (who she is and how she behaves).
        val bio: String? = null,
        val city: String? = null,
        val occupation: String? = null,
        val interests: String? = null,
        val tags: List<String> = emptyList(),
    )

    fun create(
        slug: String,
        displayName: String,
        gender: String,
        orientation: String,
        apparentAge: Int,
        languageProfile: Map<String, String>,
    ): Persona = transaction(db) {
        val id = UUID.randomUUID()
        val languageProfileJson = buildJsonObject {
            languageProfile.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }.toString()
        Personas.insert {
            it[Personas.id] = id
            it[Personas.slug] = slug
            it[Personas.displayName] = displayName
            it[Personas.status] = "draft"
            it[Personas.gender] = gender
            it[Personas.orientation] = orientation
            it[Personas.apparentAge] = apparentAge
            it[Personas.languageProfile] = languageProfileJson
            it[Personas.activeCoreVersionId] = null
            it[Personas.personaIdentityId] = null
            it[Personas.createdAt] = defaultNow()
            it[Personas.updatedAt] = defaultNow()
        }
        findById(id)!!
    }

    fun findById(id: UUID): Persona? = transaction(db) {
        Personas.select { Personas.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    /**
     * Read-only accessor for the persona → persona_identity link. Kept off the
     * [Persona] model deliberately: every existing caller and response mapper
     * takes Persona apart field by field, and adding a field there would change
     * an already-shipped API shape. This is purely additive.
     */
    fun findPersonaIdentityId(id: UUID): UUID? = transaction(db) {
        Personas.select { Personas.id eq id }
            .map { it[Personas.personaIdentityId] }
            .singleOrNull()
    }

    /**
     * Link a persona to a visual identity root. Idempotent if already linked to the same id.
     * Refuses to silently switch an existing different identity.
     */
    fun linkPersonaIdentity(personaId: UUID, identityId: UUID): Unit = transaction(db) {
        val row = Personas.select { Personas.id eq personaId }.singleOrNull()
            ?: throw IllegalArgumentException("Persona not found: $personaId")
        val current = row[Personas.personaIdentityId]
        if (current != null && current != identityId) {
            throw IllegalStateException("Persona already linked to a different visual identity")
        }
        if (current == identityId) return@transaction
        Personas.update({ Personas.id eq personaId }) {
            it[Personas.personaIdentityId] = identityId
            it[Personas.updatedAt] = defaultNow()
        }
    }

    fun findAll(): List<Persona> = transaction(db) {
        Personas.selectAll()
            .map(::rowToModel)
    }

    open fun getActiveCoreVersion(personaId: UUID): PersonaCoreVersionRepository.PersonaCoreVersion? = transaction(db) {
        val persona = findById(personaId) ?: throw IllegalArgumentException("Persona not found: $personaId")
        val activeVersionId = persona.activeCoreVersionId ?: return@transaction null
        val versionRepo = PersonaCoreVersionRepository(db)
        versionRepo.findById(activeVersionId)
    }

    fun activateCoreVersion(personaId: UUID, versionId: UUID) = transaction(db) {
        val versionRepo = PersonaCoreVersionRepository(db)
        val version = versionRepo.findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.personaId == personaId) { "Version does not belong to persona $personaId" }
        if (version.status != "published") {
            throw IllegalStateException("Only published versions can be activated")
        }

        Personas.update({ Personas.id eq personaId }) {
            it[Personas.activeCoreVersionId] = versionId
            it[Personas.updatedAt] = defaultNow()
        }
    }

    /**
     * Partial update: a null argument means "leave unchanged", matching the
     * pre-existing behavior of this method. To CLEAR a profile field, pass an
     * empty string (or an empty list for tags) — that is stored as null, which
     * keeps "unchanged" and "cleared" distinguishable over a PATCH API.
     */
    fun update(
        id: UUID,
        displayName: String? = null,
        gender: String? = null,
        orientation: String? = null,
        apparentAge: Int? = null,
        bio: String? = null,
        city: String? = null,
        occupation: String? = null,
        interests: String? = null,
        tags: List<String>? = null,
    ): Persona = transaction(db) {
        findById(id) ?: throw IllegalArgumentException("Persona not found: $id")
        Personas.update({ Personas.id eq id }) {
            if (displayName != null) it[Personas.displayName] = displayName
            if (gender != null) it[Personas.gender] = gender
            if (orientation != null) it[Personas.orientation] = orientation
            if (apparentAge != null) it[Personas.apparentAge] = apparentAge
            if (bio != null) it[Personas.bio] = bio.ifBlank { null }
            if (city != null) it[Personas.city] = city.ifBlank { null }
            if (occupation != null) it[Personas.occupation] = occupation.ifBlank { null }
            if (interests != null) it[Personas.interests] = interests.ifBlank { null }
            if (tags != null) it[Personas.tags] = encodeTags(tags)
            it[Personas.updatedAt] = defaultNow()
        }
        findById(id) ?: throw IllegalStateException("Failed to reload persona $id")
    }

    fun retirePersona(personaId: UUID): Persona = transaction(db) {
        val persona = findById(personaId) ?: throw IllegalArgumentException("Persona not found: $personaId")
        Personas.update({ Personas.id eq personaId }) {
            it[Personas.status] = "retired"
            it[Personas.updatedAt] = defaultNow()
        }
        findById(personaId) ?: throw IllegalStateException("Failed to reload persona $personaId")
    }

    private fun rowToModel(row: ResultRow): Persona = Persona(
        id = row[Personas.id],
        slug = row[Personas.slug],
        displayName = row[Personas.displayName],
        gender = row[Personas.gender],
        orientation = row[Personas.orientation],
        apparentAge = row[Personas.apparentAge],
        languageProfile = parseLanguageProfile(row[Personas.languageProfile]),
        activeCoreVersionId = row[Personas.activeCoreVersionId],
        status = row[Personas.status],
        bio = row[Personas.bio],
        city = row[Personas.city],
        occupation = row[Personas.occupation],
        interests = row[Personas.interests],
        tags = parseTags(row[Personas.tags]),
    )

    private fun encodeTags(tags: List<String>): String = JsonArray(
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive),
    ).toString()

    private fun parseTags(raw: String): List<String> = try {
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
    } catch (e: Exception) {
        // Same tolerance as parseLanguageProfile: malformed stored JSON degrades
        // to "no tags" rather than breaking every persona read.
        emptyList()
    }

    private fun parseLanguageProfile(raw: String): Map<String, String> = try {
        Json.parseToJsonElement(raw).jsonObject
            .mapValues { (_, value) -> value.jsonPrimitive.content }
    } catch (e: Exception) {
        emptyMap()
    }
}
