package com.pinkdreams.chat.personacontext.production

/**
 * Production fallback policy (Phase 45 §3/§6/§10, Phase 46 §3).
 *
 * This is a pure function, deliberately NOT wired into
 * `RepositoryContextAssembler` in this phase (Phase 46: "do not wire this
 * into live production traffic yet") — it is fully testable in isolation
 * and ready to be called from that assembler once production activation
 * (Blocker C) is separately authorized.
 *
 * Strict substitution (Phase 45 D2, locked): a [PersonaContextResult] is
 * used ONLY if [PersonaContextResult.isUsable] is true. Every other case —
 * including a null result (provider unavailable) — falls back to
 * [coreContent] unconditionally. This function can never return blank
 * content, never returns raw/unfiltered content, and never substitutes
 * anything other than [coreContent] or the result's own already-authorized,
 * already-rendered text.
 */
fun resolvePersonaContent(result: PersonaContextResult?, coreContent: String): String =
    if (result != null && result.isUsable()) result.renderedText else coreContent
