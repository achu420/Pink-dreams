package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaVisualReferenceImages
import com.pinkdreams.persistence.database.defaultNow
import com.pinkdreams.storage.ObjectStorage
import com.pinkdreams.storage.buildStorageKey
import com.pinkdreams.visual.identity.ReferenceImage
import com.pinkdreams.visual.identity.ReferenceRole
import com.pinkdreams.visual.identity.ReferenceStatus
import com.pinkdreams.visual.identity.ReferenceSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class ReferenceImageRepository(
    private val db: Database,
    private val objectStorage: ObjectStorage
) {

    fun uploadReference(
        personaVisualVersionId: UUID,
        personaIdentityId: UUID,
        content: ByteArray,
        contentType: String,
        role: ReferenceRole,
        source: ReferenceSource,
        width: Int? = null,
        height: Int? = null,
        checksum: String? = null,
        notes: String? = null
    ): ReferenceImage = transaction(db) {
        val version = PersonaVisualVersionRepository(db).findById(personaVisualVersionId)
            ?: throw IllegalArgumentException("Visual version not found: $personaVisualVersionId")

        require(version.status == "draft") { "References can only be uploaded to draft versions" }

        val referenceId = UUID.randomUUID()
        val storageKey = buildStorageKey(personaIdentityId, personaVisualVersionId, referenceId)

        // Store object first
        val storageMeta = objectStorage.store(storageKey, content, contentType)

        // Then create DB record, with compensating cleanup on failure
        try {
            val reference = ReferenceImage(
                id = referenceId,
                personaVisualVersionId = personaVisualVersionId,
                storageKey = storageKey,
                contentType = storageMeta.contentType,
                fileSize = storageMeta.size,
                width = width,
                height = height,
                checksum = checksum,
                role = role,
                status = ReferenceStatus.UPLOADED,
                source = source,
                notes = notes,
                createdAt = LocalDateTime.now(),
                finalizedAt = null
            )

            val validation = reference.validate()
            require(validation.valid) { "Invalid reference image: ${validation.errors.joinToString(", ")}" }

            PersonaVisualReferenceImages.insert {
                it[PersonaVisualReferenceImages.id] = reference.id
                it[PersonaVisualReferenceImages.personaVisualVersionId] = personaVisualVersionId
                it[PersonaVisualReferenceImages.storageKey] = storageKey
                it[PersonaVisualReferenceImages.contentType] = contentType
                it[PersonaVisualReferenceImages.fileSize] = content.size.toLong()
                it[PersonaVisualReferenceImages.width] = width
                it[PersonaVisualReferenceImages.height] = height
                it[PersonaVisualReferenceImages.checksum] = checksum
                it[PersonaVisualReferenceImages.role] = role.name
                it[PersonaVisualReferenceImages.status] = ReferenceStatus.UPLOADED.name
                it[PersonaVisualReferenceImages.referenceSource] = source.name
                it[PersonaVisualReferenceImages.notes] = notes
                it[PersonaVisualReferenceImages.createdAt] = defaultNow()
            }

            findById(reference.id)!!
        } catch (e: Exception) {
            try {
                objectStorage.delete(storageKey)
            } catch (cleanup: Exception) {
                System.err.println("Warning: failed to clean up orphaned storage object $storageKey after failed reference creation: ${cleanup.message}")
            }
            throw e
        }
    }

    fun findById(id: UUID): ReferenceImage? = transaction(db) {
        PersonaVisualReferenceImages.select { PersonaVisualReferenceImages.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findForVersion(personaVisualVersionId: UUID): List<ReferenceImage> = transaction(db) {
        PersonaVisualReferenceImages.select { PersonaVisualReferenceImages.personaVisualVersionId eq personaVisualVersionId }
            .map(::rowToModel)
    }

    fun findByRole(personaVisualVersionId: UUID, role: ReferenceRole): List<ReferenceImage> = transaction(db) {
        PersonaVisualReferenceImages.select {
            (PersonaVisualReferenceImages.personaVisualVersionId eq personaVisualVersionId) and
            (PersonaVisualReferenceImages.role eq role.name)
        }
            .map(::rowToModel)
    }

    fun findByStatus(personaVisualVersionId: UUID, status: ReferenceStatus): List<ReferenceImage> = transaction(db) {
        PersonaVisualReferenceImages.select {
            (PersonaVisualReferenceImages.personaVisualVersionId eq personaVisualVersionId) and
            (PersonaVisualReferenceImages.status eq status.name)
        }
            .map(::rowToModel)
    }

    fun finalizeReference(referenceId: UUID): ReferenceImage = transaction(db) {
        val reference = findById(referenceId)
            ?: throw IllegalArgumentException("Reference not found: $referenceId")

        val version = PersonaVisualVersionRepository(db).findById(reference.personaVisualVersionId)
            ?: throw IllegalStateException("Visual version not found: ${reference.personaVisualVersionId}")

        require(version.status == "draft") { "References can only be finalized for draft versions" }
        require(reference.status == ReferenceStatus.UPLOADED) { "Only uploaded references can be finalized" }

        PersonaVisualReferenceImages.update({ PersonaVisualReferenceImages.id eq referenceId }) {
            it[PersonaVisualReferenceImages.status] = ReferenceStatus.FINALIZED.name
            it[PersonaVisualReferenceImages.finalizedAt] = LocalDateTime.now()
        }

        findById(referenceId)!!
    }

    fun archiveReference(referenceId: UUID): ReferenceImage = transaction(db) {
        val reference = findById(referenceId)
            ?: throw IllegalArgumentException("Reference not found: $referenceId")

        val version = PersonaVisualVersionRepository(db).findById(reference.personaVisualVersionId)
            ?: throw IllegalStateException("Visual version not found: ${reference.personaVisualVersionId}")

        require(version.status == "draft") { "References can only be archived from draft versions" }
        require(reference.status == ReferenceStatus.FINALIZED) { "Only finalized references can be archived" }

        PersonaVisualReferenceImages.update({ PersonaVisualReferenceImages.id eq referenceId }) {
            it[PersonaVisualReferenceImages.status] = ReferenceStatus.ARCHIVED.name
        }

        findById(referenceId)!!
    }

    fun removeReference(referenceId: UUID) = transaction(db) {
        val reference = findById(referenceId)
            ?: throw IllegalArgumentException("Reference not found: $referenceId")

        val version = PersonaVisualVersionRepository(db).findById(reference.personaVisualVersionId)
            ?: throw IllegalStateException("Visual version not found: ${reference.personaVisualVersionId}")

        require(version.status == "draft") { "References can only be removed from draft versions" }

        // Delete DB record first (transacted). Storage delete is best-effort.
        // If storage delete fails after DB delete succeeds, the reference record is gone
        // but the object may remain orphaned in storage.
        PersonaVisualReferenceImages.deleteWhere { PersonaVisualReferenceImages.id eq referenceId }

        val deleted = objectStorage.delete(reference.storageKey)
        if (!deleted) {
            System.err.println("Warning: failed to delete storage object ${reference.storageKey} for reference $referenceId")
        }
    }

    /**
     * For standard identity slots (and PRIVATE), archive prior non-archived refs of the same role
     * on the draft version so only one active slot remains after upload.
     */
    fun archiveActiveForRole(personaVisualVersionId: UUID, role: ReferenceRole) = transaction(db) {
        if (!role.isStandardIdentitySlot() && role != ReferenceRole.PRIVATE) return@transaction
        val version = PersonaVisualVersionRepository(db).findById(personaVisualVersionId)
            ?: throw IllegalArgumentException("Visual version not found: $personaVisualVersionId")
        require(version.status == "draft") { "References can only be modified on draft versions" }
        findByRole(personaVisualVersionId, role)
            .filter { it.status != ReferenceStatus.ARCHIVED }
            .forEach { ref ->
                PersonaVisualReferenceImages.update({ PersonaVisualReferenceImages.id eq ref.id }) {
                    it[PersonaVisualReferenceImages.status] = ReferenceStatus.ARCHIVED.name
                }
            }
    }

    fun updateNotes(referenceId: UUID, notes: String?): ReferenceImage = transaction(db) {
        val reference = findById(referenceId)
            ?: throw IllegalArgumentException("Reference not found: $referenceId")
        val version = PersonaVisualVersionRepository(db).findById(reference.personaVisualVersionId)
            ?: throw IllegalStateException("Visual version not found: ${reference.personaVisualVersionId}")
        require(version.status == "draft") { "References can only be updated on draft versions" }
        PersonaVisualReferenceImages.update({ PersonaVisualReferenceImages.id eq referenceId }) {
            it[PersonaVisualReferenceImages.notes] = notes
        }
        findById(referenceId)!!
    }

    fun retrieveContent(referenceId: UUID): ByteArray? = transaction(db) {
        val reference = findById(referenceId) ?: return@transaction null
        objectStorage.retrieve(reference.storageKey)?.content
    }

    fun retrieveContentWithMeta(referenceId: UUID): Pair<ReferenceImage, ByteArray>? = transaction(db) {
        val reference = findById(referenceId) ?: return@transaction null
        val bytes = objectStorage.retrieve(reference.storageKey)?.content ?: return@transaction null
        reference to bytes
    }

    private fun rowToModel(row: ResultRow): ReferenceImage = ReferenceImage(
        id = row[PersonaVisualReferenceImages.id],
        personaVisualVersionId = row[PersonaVisualReferenceImages.personaVisualVersionId],
        storageKey = row[PersonaVisualReferenceImages.storageKey],
        contentType = row[PersonaVisualReferenceImages.contentType],
        fileSize = row[PersonaVisualReferenceImages.fileSize],
        width = row[PersonaVisualReferenceImages.width],
        height = row[PersonaVisualReferenceImages.height],
        checksum = row[PersonaVisualReferenceImages.checksum],
        role = ReferenceRole.valueOf(row[PersonaVisualReferenceImages.role]),
        status = ReferenceStatus.valueOf(row[PersonaVisualReferenceImages.status]),
        source = ReferenceSource.valueOf(row[PersonaVisualReferenceImages.referenceSource]),
        notes = row[PersonaVisualReferenceImages.notes],
        createdAt = row[PersonaVisualReferenceImages.createdAt],
        finalizedAt = row[PersonaVisualReferenceImages.finalizedAt]
    )
}
