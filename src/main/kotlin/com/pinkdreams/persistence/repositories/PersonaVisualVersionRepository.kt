package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaIdentity
import com.pinkdreams.persistence.database.PersonaVisualVersions
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class PersonaVisualVersionRepository(private val db: Database) {
    data class PersonaVisualVersion(
        val id: UUID,
        val personaIdentityId: UUID,
        val version: Int,
        val physicalGuide: String,
        val styleConstraints: String,
        val status: String,
        val changelogNote: String?,
        val author: String?,
    )

    fun create(
        personaIdentityId: UUID,
        version: Int,
        physicalGuide: String = "{}",
        styleConstraints: String = "{}",
        status: String = "draft",
        changelogNote: String? = null,
        author: String? = null,
    ): PersonaVisualVersion = transaction(db) {
        val id = UUID.randomUUID()
        PersonaVisualVersions.insert {
            it[PersonaVisualVersions.id] = id
            it[PersonaVisualVersions.personaIdentityId] = personaIdentityId
            it[PersonaVisualVersions.version] = version
            it[PersonaVisualVersions.physicalGuide] = physicalGuide
            it[PersonaVisualVersions.styleConstraints] = styleConstraints
            it[PersonaVisualVersions.status] = status
            it[PersonaVisualVersions.changelogNote] = changelogNote
            it[PersonaVisualVersions.author] = author
            it[PersonaVisualVersions.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    fun findById(id: UUID): PersonaVisualVersion? = transaction(db) {
        PersonaVisualVersions.select { PersonaVisualVersions.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findForPersonaIdentity(personaIdentityId: UUID): List<PersonaVisualVersion> = transaction(db) {
        PersonaVisualVersions.select { PersonaVisualVersions.personaIdentityId eq personaIdentityId }
            .map(::rowToModel)
    }

    fun findDraftForPersonaIdentity(personaIdentityId: UUID): PersonaVisualVersion? = transaction(db) {
        PersonaVisualVersions.select {
            (PersonaVisualVersions.personaIdentityId eq personaIdentityId) and (PersonaVisualVersions.status eq "draft")
        }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findActiveForPersonaIdentity(personaIdentityId: UUID): PersonaVisualVersion? = transaction(db) {
        val activeVersionId = PersonaIdentity.select { PersonaIdentity.id eq personaIdentityId }
            .map { it[PersonaIdentity.activeVisualVersionId] }
            .singleOrNull()
            ?: return@transaction null

        activeVersionId?.let { findById(it) }
    }

    fun updatePhysicalGuide(versionId: UUID, newPhysicalGuide: String): PersonaVisualVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "draft") { "Published or archived visual versions are immutable" }

        PersonaVisualVersions.update({ PersonaVisualVersions.id eq versionId }) {
            it[PersonaVisualVersions.physicalGuide] = newPhysicalGuide
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    fun updateStyleConstraints(versionId: UUID, newStyleConstraints: String): PersonaVisualVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "draft") { "Published or archived visual versions are immutable" }

        PersonaVisualVersions.update({ PersonaVisualVersions.id eq versionId }) {
            it[PersonaVisualVersions.styleConstraints] = newStyleConstraints
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    fun publishVisualVersion(versionId: UUID): PersonaVisualVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "draft") { "Only draft versions can be published" }

        PersonaVisualVersions.update({ PersonaVisualVersions.id eq versionId }) {
            it[PersonaVisualVersions.status] = "published"
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    fun archiveVisualVersion(versionId: UUID): PersonaVisualVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.status == "published") { "Only published versions can be archived" }

        val activeForIdentity = PersonaIdentity
            .select { PersonaIdentity.activeVisualVersionId eq versionId }
            .count()
        if (activeForIdentity != 0L) {
            throw IllegalStateException("Active version cannot be archived")
        }

        PersonaVisualVersions.update({ PersonaVisualVersions.id eq versionId }) {
            it[PersonaVisualVersions.status] = "archived"
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    fun activateVisualVersion(personaIdentityId: UUID, versionId: UUID): PersonaVisualVersion = transaction(db) {
        val version = findById(versionId) ?: throw IllegalArgumentException("Version not found: $versionId")
        require(version.personaIdentityId == personaIdentityId) { "Version does not belong to this identity" }
        require(version.status == "published") { "Only published versions can be activated" }

        PersonaIdentity.update({ PersonaIdentity.id eq personaIdentityId }) {
            it[PersonaIdentity.activeVisualVersionId] = versionId
        }

        findById(versionId) ?: throw IllegalStateException("Failed to reload version $versionId")
    }

    private fun rowToModel(row: ResultRow): PersonaVisualVersion = PersonaVisualVersion(
        id = row[PersonaVisualVersions.id],
        personaIdentityId = row[PersonaVisualVersions.personaIdentityId],
        version = row[PersonaVisualVersions.version],
        physicalGuide = row[PersonaVisualVersions.physicalGuide],
        styleConstraints = row[PersonaVisualVersions.styleConstraints],
        status = row[PersonaVisualVersions.status],
        changelogNote = row[PersonaVisualVersions.changelogNote],
        author = row[PersonaVisualVersions.author],
    )
}
