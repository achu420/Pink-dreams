package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaVisualVersions
import com.pinkdreams.visual.identity.PhysicalGuide
import com.pinkdreams.visual.identity.ValidationResult
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class PersonalGuideRepository(private val db: Database) {

    fun setPhysicalGuide(versionId: UUID, guide: PhysicalGuide): PhysicalGuide = transaction(db) {
        val version = PersonaVisualVersionRepository(db).findById(versionId)
            ?: throw IllegalArgumentException("Visual version not found: $versionId")

        require(version.status == "draft") { "Physical guide can only be set on draft versions" }

        val validation = guide.validate()
        require(validation.valid) { "Invalid physical guide: ${validation.errors.joinToString(", ")}" }

        val guidJson = Json.encodeToString(PhysicalGuide.serializer(), guide)

        PersonaVisualVersions.update({ PersonaVisualVersions.id eq versionId }) {
            it[PersonaVisualVersions.physicalGuide] = guidJson
        }

        getPhysicalGuide(versionId)!!
    }

    fun getPhysicalGuide(versionId: UUID): PhysicalGuide? = transaction(db) {
        val row = PersonaVisualVersions.select { PersonaVisualVersions.id eq versionId }
            .map { it[PersonaVisualVersions.physicalGuide] }
            .singleOrNull()
            ?: return@transaction null

        try {
            Json.decodeFromString(PhysicalGuide.serializer(), row)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to deserialize physical guide: ${e.message}")
        }
    }

    fun updatePhysicalGuideAdultFlag(versionId: UUID, adult: Boolean): PhysicalGuide = transaction(db) {
        val version = PersonaVisualVersionRepository(db).findById(versionId)
            ?: throw IllegalArgumentException("Visual version not found: $versionId")

        require(version.status == "draft") { "Physical guide can only be updated on draft versions" }

        val currentGuide = getPhysicalGuide(versionId)
            ?: throw IllegalStateException("No physical guide exists for version $versionId")

        val updated = currentGuide.copy(
            agePresentation = currentGuide.agePresentation.copy(adult = adult)
        )

        setPhysicalGuide(versionId, updated)
    }
}
