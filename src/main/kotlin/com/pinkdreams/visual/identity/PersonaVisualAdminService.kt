package com.pinkdreams.visual.identity

import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Admin orchestration for persona visual identity: ensure identity/draft,
 * update guides, publish/activate, reference slot management.
 */
class PersonaVisualAdminService(
    private val personaRepository: PersonaRepository,
    private val personaIdentityRepository: PersonaIdentityRepository,
    private val visualVersionRepository: PersonaVisualVersionRepository,
    private val personalGuideRepository: PersonalGuideRepository,
    private val referenceImageRepository: ReferenceImageRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class EnsureResult(
        val personaId: UUID,
        val identityId: UUID,
        val draftVersionId: UUID,
        val draftVersion: Int,
        val createdIdentity: Boolean,
        val createdDraft: Boolean,
    )

    fun ensureDraft(personaId: UUID, author: String? = null): EnsureResult {
        val persona = personaRepository.findById(personaId)
            ?: throw IllegalArgumentException("Persona not found: $personaId")

        var createdIdentity = false
        var identityId = personaRepository.findPersonaIdentityId(personaId)
        if (identityId == null) {
            identityId = personaIdentityRepository.create().id
            personaRepository.linkPersonaIdentity(personaId, identityId)
            createdIdentity = true
        }

        var createdDraft = false
        var draft = visualVersionRepository.findDraftForPersonaIdentity(identityId)
        if (draft == null) {
            val active = visualVersionRepository.findActiveForPersonaIdentity(identityId)
            val next = visualVersionRepository.nextVersionNumber(identityId)
            draft = visualVersionRepository.create(
                personaIdentityId = identityId,
                version = next,
                physicalGuide = active?.physicalGuide ?: defaultPhysicalGuideJson(persona),
                styleConstraints = active?.styleConstraints ?: "{}",
                privateGuide = active?.privateGuide ?: "{}",
                status = "draft",
                changelogNote = if (active != null) "Draft based on v${active.version}" else "Initial visual identity draft",
                author = author,
            )
            createdDraft = true
            if (active != null) {
                copyActiveReferences(fromVersionId = active.id, toVersionId = draft.id, identityId = identityId)
            }
        }

        return EnsureResult(
            personaId = personaId,
            identityId = identityId,
            draftVersionId = draft.id,
            draftVersion = draft.version,
            createdIdentity = createdIdentity,
            createdDraft = createdDraft,
        )
    }

    fun requireDraftForPersona(personaId: UUID): PersonaVisualVersionRepository.PersonaVisualVersion {
        val identityId = personaRepository.findPersonaIdentityId(personaId)
            ?: throw IllegalStateException("Persona has no visual identity — call ensure first")
        return visualVersionRepository.findDraftForPersonaIdentity(identityId)
            ?: throw IllegalStateException("No draft visual version — call ensure first")
    }

    fun updatePhysicalGuide(personaId: UUID, guide: PhysicalGuide): PhysicalGuide {
        val draft = requireDraftForPersona(personaId)
        return personalGuideRepository.setPhysicalGuide(draft.id, guide)
    }

    fun updatePrivateGuide(personaId: UUID, guide: PrivateVisualGuide): PrivateVisualGuide {
        val draft = requireDraftForPersona(personaId)
        val encoded = json.encodeToString(PrivateVisualGuide.serializer(), guide)
        visualVersionRepository.updatePrivateGuide(draft.id, encoded)
        return guide
    }

    fun updateStyleConstraints(personaId: UUID, styleConstraintsJson: String): String {
        val draft = requireDraftForPersona(personaId)
        // Accept raw JSON object string; normalize empty
        val normalized = if (styleConstraintsJson.isBlank()) "{}" else styleConstraintsJson
        json.parseToJsonElement(normalized) // validate JSON
        visualVersionRepository.updateStyleConstraints(draft.id, normalized)
        return normalized
    }

    fun publishDraft(personaId: UUID): PersonaVisualVersionRepository.PersonaVisualVersion {
        val draft = requireDraftForPersona(personaId)
        return visualVersionRepository.publishVisualVersion(draft.id)
    }

    fun activateVersion(personaId: UUID, versionId: UUID): PersonaVisualVersionRepository.PersonaVisualVersion {
        val identityId = personaRepository.findPersonaIdentityId(personaId)
            ?: throw IllegalStateException("Persona has no visual identity")
        return visualVersionRepository.activateVisualVersion(identityId, versionId)
    }

    fun publishAndActivateDraft(personaId: UUID): PersonaVisualVersionRepository.PersonaVisualVersion {
        val published = publishDraft(personaId)
        return activateVersion(personaId, published.id)
    }

    fun uploadReference(
        personaId: UUID,
        role: ReferenceRole,
        content: ByteArray,
        contentType: String,
        notes: String? = null,
        replaceSlot: Boolean = true,
        finalize: Boolean = true,
    ): ReferenceImage {
        val draft = requireDraftForPersona(personaId)
        val identityId = draft.personaIdentityId
        if (replaceSlot) {
            referenceImageRepository.archiveActiveForRole(draft.id, role)
        }
        val uploaded = referenceImageRepository.uploadReference(
            personaVisualVersionId = draft.id,
            personaIdentityId = identityId,
            content = content,
            contentType = contentType,
            role = role,
            source = ReferenceSource.HUMAN_UPLOADED,
            notes = notes,
        )
        return if (finalize) {
            referenceImageRepository.finalizeReference(uploaded.id)
        } else {
            uploaded
        }
    }

    fun removeReference(personaId: UUID, referenceId: UUID) {
        val draft = requireDraftForPersona(personaId)
        val ref = referenceImageRepository.findById(referenceId)
            ?: throw IllegalArgumentException("Reference not found")
        require(ref.personaVisualVersionId == draft.id) {
            "Reference does not belong to the persona draft version"
        }
        referenceImageRepository.removeReference(referenceId)
    }

    fun updateReferenceNotes(personaId: UUID, referenceId: UUID, notes: String?): ReferenceImage {
        val draft = requireDraftForPersona(personaId)
        val ref = referenceImageRepository.findById(referenceId)
            ?: throw IllegalArgumentException("Reference not found")
        require(ref.personaVisualVersionId == draft.id) {
            "Reference does not belong to the persona draft version"
        }
        return referenceImageRepository.updateNotes(referenceId, notes)
    }

    /**
     * Resolve identity for generation: active published version (or fallback max version).
     * Private refs excluded unless [includePrivateReferences].
     */
    fun resolveForGeneration(
        personaId: UUID,
        includePrivateReferences: Boolean = false,
    ): GenerationIdentityContext {
        val persona = personaRepository.findById(personaId)
            ?: throw IllegalArgumentException("Persona not found: $personaId")
        val identityId = personaRepository.findPersonaIdentityId(personaId)
            ?: throw IllegalStateException("Persona has no visual identity: $personaId")
        val active = visualVersionRepository.findActiveForPersonaIdentity(identityId)
        val version = active
            ?: visualVersionRepository.findForPersonaIdentity(identityId).maxByOrNull { it.version }
            ?: throw IllegalStateException("Persona has no visual versions: $personaId")

        val refs = referenceImageRepository.findForVersion(version.id)
            .filter { it.status == ReferenceStatus.FINALIZED || it.status == ReferenceStatus.UPLOADED }
            .filter { includePrivateReferences || !it.role.isPrivate() }
            .let { selectPreferredReferences(it) }

        val privateGuide = try {
            json.decodeFromString(PrivateVisualGuide.serializer(), version.privateGuide)
        } catch (_: Exception) {
            PrivateVisualGuide()
        }

        return GenerationIdentityContext(
            personaId = personaId,
            personaDisplayName = persona.displayName,
            identityId = identityId,
            visualVersion = version,
            referenceImages = refs,
            privateGuide = privateGuide,
        )
    }

    private fun selectPreferredReferences(refs: List<ReferenceImage>): List<ReferenceImage> {
        val byRole = linkedMapOf<ReferenceRole, ReferenceImage>()
        // Prefer standard slots first (one each), then other non-private up to remaining budget
        for (slot in ReferenceRole.STANDARD_SLOTS) {
            refs.filter { it.role == slot }
                .sortedByDescending { it.status == ReferenceStatus.FINALIZED }
                .firstOrNull()
                ?.let { byRole[slot] = it }
        }
        val selected = byRole.values.toMutableList()
        if (selected.size < 3) {
            for (r in refs.sortedByDescending { it.status == ReferenceStatus.FINALIZED }) {
                if (r.role.isPrivate()) continue
                if (selected.any { it.id == r.id }) continue
                if (r.role.isStandardIdentitySlot() && byRole.containsKey(r.role)) continue
                selected.add(r)
                if (selected.size >= 5) break
            }
        }
        return selected.take(5)
    }

    private fun copyActiveReferences(fromVersionId: UUID, toVersionId: UUID, identityId: UUID) {
        val source = referenceImageRepository.findForVersion(fromVersionId)
            .filter { it.status == ReferenceStatus.FINALIZED || it.status == ReferenceStatus.UPLOADED }
        for (ref in source) {
            val bytes = referenceImageRepository.retrieveContent(ref.id) ?: continue
            try {
                val copied = referenceImageRepository.uploadReference(
                    personaVisualVersionId = toVersionId,
                    personaIdentityId = identityId,
                    content = bytes,
                    contentType = ref.contentType,
                    role = ref.role,
                    source = ref.source,
                    width = ref.width,
                    height = ref.height,
                    notes = ref.notes,
                )
                if (ref.status == ReferenceStatus.FINALIZED) {
                    referenceImageRepository.finalizeReference(copied.id)
                }
            } catch (_: Exception) {
                // Best-effort copy; draft remains editable
            }
        }
    }

    private fun defaultPhysicalGuideJson(persona: PersonaRepository.Persona): String {
        val guide = PhysicalGuide(
            agePresentation = AgePresentation(apparentAge = persona.apparentAge, adult = true),
        )
        return json.encodeToString(PhysicalGuide.serializer(), guide)
    }
}

data class GenerationIdentityContext(
    val personaId: UUID,
    val personaDisplayName: String,
    val identityId: UUID,
    val visualVersion: PersonaVisualVersionRepository.PersonaVisualVersion,
    val referenceImages: List<ReferenceImage>,
    val privateGuide: PrivateVisualGuide,
)
