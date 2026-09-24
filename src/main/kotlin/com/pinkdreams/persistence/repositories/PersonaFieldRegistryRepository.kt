package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaFieldRegistry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class PersonaFieldRegistryEntry(
    val fieldId: String,
    val path: String,
    val semanticType: String?,
    val classification: String,
    val sensitivity: String,
    val stability: String,
    val behavioralCore: Boolean,
    val defaultLoad: Boolean,
    val allowedContexts: List<String>?,
    val prohibitedContexts: List<String>?,
    val description: String?,
)

class PersonaFieldRegistryRepository(private val db: Database) {

    fun create(entry: PersonaFieldRegistryEntry): PersonaFieldRegistryEntry = transaction(db) {
        PersonaFieldRegistry.insert {
            it[fieldId] = entry.fieldId
            it[path] = entry.path
            it[semanticType] = entry.semanticType
            it[classification] = entry.classification
            it[sensitivity] = entry.sensitivity
            it[stability] = entry.stability
            it[behavioralCore] = entry.behavioralCore
            it[defaultLoad] = entry.defaultLoad
            it[allowedContexts] = entry.allowedContexts?.joinToString(",")
            it[prohibitedContexts] = entry.prohibitedContexts?.joinToString(",")
            it[description] = entry.description
        }
        entry
    }

    fun findAll(): List<PersonaFieldRegistryEntry> = transaction(db) {
        PersonaFieldRegistry.selectAll()
            .map(::rowToModel)
    }

    fun findByFieldId(fieldId: String): PersonaFieldRegistryEntry? = transaction(db) {
        PersonaFieldRegistry.select { PersonaFieldRegistry.fieldId eq fieldId }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findByClassification(classification: String): List<PersonaFieldRegistryEntry> = transaction(db) {
        PersonaFieldRegistry.select { PersonaFieldRegistry.classification eq classification }
            .map(::rowToModel)
    }

    fun findBehavioralCore(): List<PersonaFieldRegistryEntry> = transaction(db) {
        PersonaFieldRegistry.select { PersonaFieldRegistry.behavioralCore eq true }
            .map(::rowToModel)
    }

    fun update(entry: PersonaFieldRegistryEntry): PersonaFieldRegistryEntry = transaction(db) {
        PersonaFieldRegistry.update({ PersonaFieldRegistry.fieldId eq entry.fieldId }) {
            it[path] = entry.path
            it[semanticType] = entry.semanticType
            it[classification] = entry.classification
            it[sensitivity] = entry.sensitivity
            it[stability] = entry.stability
            it[behavioralCore] = entry.behavioralCore
            it[defaultLoad] = entry.defaultLoad
            it[allowedContexts] = entry.allowedContexts?.joinToString(",")
            it[prohibitedContexts] = entry.prohibitedContexts?.joinToString(",")
            it[description] = entry.description
        }
        entry
    }

    fun delete(fieldId: String): Boolean = transaction(db) {
        PersonaFieldRegistry.deleteWhere { PersonaFieldRegistry.fieldId eq fieldId } > 0
    }

    private fun rowToModel(row: ResultRow): PersonaFieldRegistryEntry {
        val allowedStr = row[PersonaFieldRegistry.allowedContexts]
        val prohibitedStr = row[PersonaFieldRegistry.prohibitedContexts]
        return PersonaFieldRegistryEntry(
            fieldId = row[PersonaFieldRegistry.fieldId],
            path = row[PersonaFieldRegistry.path],
            semanticType = row[PersonaFieldRegistry.semanticType],
            classification = row[PersonaFieldRegistry.classification],
            sensitivity = row[PersonaFieldRegistry.sensitivity],
            stability = row[PersonaFieldRegistry.stability],
            behavioralCore = row[PersonaFieldRegistry.behavioralCore],
            defaultLoad = row[PersonaFieldRegistry.defaultLoad],
            allowedContexts = allowedStr?.split(","),
            prohibitedContexts = prohibitedStr?.split(","),
            description = row[PersonaFieldRegistry.description],
        )
    }
}
