package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaCoreVersions
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class PersonaCoreVersionRepository(private val db: Database) {
    data class PersonaCoreVersion(
        val id: UUID,
        val personaId: UUID,
        val version: Int,
        val content: String,
        val status: String,
        val changelogNote: String?,
        val author: String?,
    )

    fun create(
        personaId: UUID,
        version: Int,
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        author: String? = null,
    ): PersonaCoreVersion = transaction(db) {
        val id = UUID.randomUUID()
        PersonaCoreVersions.insert {
            it[PersonaCoreVersions.id] = id
            it[PersonaCoreVersions.personaId] = personaId
            it[PersonaCoreVersions.version] = version
            it[PersonaCoreVersions.content] = content
            it[PersonaCoreVersions.status] = status
            it[PersonaCoreVersions.changelogNote] = changelogNote
            it[PersonaCoreVersions.author] = author
            it[PersonaCoreVersions.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    fun findById(id: UUID): PersonaCoreVersion? = transaction(db) {
        PersonaCoreVersions.select { PersonaCoreVersions.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findForPersona(personaId: UUID): List<PersonaCoreVersion> = transaction(db) {
        PersonaCoreVersions.select { PersonaCoreVersions.personaId eq personaId }
            .map(::rowToModel)
    }

    fun publishCoreVersion(versionId: UUID): PersonaCoreVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "draft") { "Only draft versions can be published" }

        PersonaCoreVersions.update({ PersonaCoreVersions.id eq versionId }) {
            it[PersonaCoreVersions.status] = "published"
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    fun archiveCoreVersion(versionId: UUID): PersonaCoreVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "published") { "Only published versions can be archived" }

        val activeForPersona = com.pinkdreams.persistence.database.Personas
            .select { com.pinkdreams.persistence.database.Personas.activeCoreVersionId eq versionId }
            .count()
        if (activeForPersona != 0L) {
            throw IllegalStateException("Active version cannot be archived")
        }

        PersonaCoreVersions.update({ PersonaCoreVersions.id eq versionId }) {
            it[PersonaCoreVersions.status] = "archived"
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    fun updateContent(versionId: UUID, newContent: String): PersonaCoreVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "draft") { "Published or active content is immutable" }

        PersonaCoreVersions.update({ PersonaCoreVersions.id eq versionId }) {
            it[PersonaCoreVersions.content] = newContent
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    private fun rowToModel(row: ResultRow): PersonaCoreVersion = PersonaCoreVersion(
        id = row[PersonaCoreVersions.id],
        personaId = row[PersonaCoreVersions.personaId],
        version = row[PersonaCoreVersions.version],
        content = row[PersonaCoreVersions.content],
        status = row[PersonaCoreVersions.status],
        changelogNote = row[PersonaCoreVersions.changelogNote],
        author = row[PersonaCoreVersions.author],
    )
}
