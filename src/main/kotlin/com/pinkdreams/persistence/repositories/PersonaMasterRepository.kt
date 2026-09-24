package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaMasterDocuments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class PersonaMasterDocument(
    val id: UUID,
    val personaCoreVersionId: UUID,
    val contentJson: JsonObject,
    val schemaVersion: String,
)

class PersonaMasterRepository(private val db: Database) {

    fun create(personaCoreVersionId: UUID, contentJson: JsonObject): PersonaMasterDocument = transaction(db) {
        val id = UUID.randomUUID()
        PersonaMasterDocuments.insert {
            it[PersonaMasterDocuments.id] = id
            it[PersonaMasterDocuments.personaCoreVersionId] = personaCoreVersionId
            it[PersonaMasterDocuments.contentJson] = contentJson.toString()
            it[PersonaMasterDocuments.schemaVersion] = "1.0"
        }
        PersonaMasterDocument(
            id = id,
            personaCoreVersionId = personaCoreVersionId,
            contentJson = contentJson,
            schemaVersion = "1.0",
        )
    }

    fun findByPersonaCoreVersionId(personaCoreVersionId: UUID): PersonaMasterDocument? = transaction(db) {
        PersonaMasterDocuments.select { PersonaMasterDocuments.personaCoreVersionId eq personaCoreVersionId }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun resolvePath(json: JsonObject, path: String): Any? {
        val parts = path.split(".")
        var current: Any? = json
        for (part in parts) {
            when (current) {
                is JsonObject -> {
                    current = current[part]
                }
                else -> return null
            }
        }
        return current
    }

    private fun rowToModel(row: ResultRow): PersonaMasterDocument {
        val jsonStr = row[PersonaMasterDocuments.contentJson]
        val contentJson = try {
            Json.parseToJsonElement(jsonStr).let { it as JsonObject }
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        return PersonaMasterDocument(
            id = row[PersonaMasterDocuments.id],
            personaCoreVersionId = row[PersonaMasterDocuments.personaCoreVersionId],
            contentJson = contentJson,
            schemaVersion = row[PersonaMasterDocuments.schemaVersion],
        )
    }
}
