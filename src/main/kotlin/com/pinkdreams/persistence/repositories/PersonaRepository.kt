package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.database.defaultNow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    fun findAll(): List<Persona> = transaction(db) {
        Personas.selectAll()
            .map(::rowToModel)
    }

    fun getActiveCoreVersion(personaId: UUID): PersonaCoreVersionRepository.PersonaCoreVersion? = transaction(db) {
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
    )

    private fun parseLanguageProfile(raw: String): Map<String, String> = try {
        Json.parseToJsonElement(raw).jsonObject
            .mapValues { (_, value) -> value.jsonPrimitive.content }
    } catch (e: Exception) {
        emptyMap()
    }
}
