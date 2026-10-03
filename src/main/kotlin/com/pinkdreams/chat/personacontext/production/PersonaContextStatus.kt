package com.pinkdreams.chat.personacontext.production

/**
 * Production Persona Context resolution outcome.
 *
 * This is a NEW, production-only status model (Phase 45 §5, Phase 46 §1).
 * It has no relationship to the historical/research "variant A/B/C" concept —
 * there is exactly one production resolution strategy, and it either
 * succeeds (USABLE) or fails in one of these specific, distinguishable ways.
 *
 * Deliberately excludes a PARTIAL state — Phase 45 §6 established that no
 * production behavior currently distinguishes "some fields selected" from
 * "all fields selected," so no such status is represented here. Do not
 * reintroduce one without a corresponding behavioral requirement.
 */
enum class PersonaContextStatus {
    /** Resolution succeeded: at least one field was selected, authorized, and rendered. */
    USABLE,

    /** No persona master document exists for the requested persona core version. */
    DOCUMENT_NOT_FOUND,

    /** A persona master document exists but its content is an empty JSON object. */
    DOCUMENT_EMPTY,

    /** No field-registry entries exist at all, so no field could ever be authorized. */
    REGISTRY_EMPTY,

    /** No SkillContextRequirement row exists for the requested skill key.
     *  Identical outcome for an "unknown" skill key and a "known but unmapped" one —
     *  the authorizer cannot distinguish these and does not need to (Phase 38 §4,
     *  Phase 45 §3, re-confirmed unchanged). */
    SKILL_MAPPING_MISSING,

    /** A document, registry, and skill mapping all exist, but after authorization
     *  zero fields survived (e.g. every candidate field was restricted-and-unauthorized,
     *  or none passed conditional relevance scoring). */
    ZERO_FIELDS_SELECTED,

    /** Resolution threw an exception. The exception is never propagated to the
     *  caller as a chat-request failure — it is caught and reported via this
     *  status instead (Phase 45 §3's "provider/resolver failure" row). */
    RESOLVER_ERROR,
}
