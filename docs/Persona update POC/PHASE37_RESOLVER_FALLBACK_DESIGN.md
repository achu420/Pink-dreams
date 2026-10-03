# Phase 37 — Resolver Fallback Design (Blocker B: Resolver Safety)

**Zero API calls. Zero code changes. Zero database mutation.** This is an implementation-design and safety audit only — no fix is implemented, no table is populated, no resolver/renderer/provider file is modified, and Persona Context is not wired into production. No historical Phase 10–36 artifact was modified. No behavioral experiment is reopened.

---

## 1. Executive Summary

Phase 36 established that `PersonaContextResolver`'s fallback (`resolveVariantA()`) does not preserve today's actual production baseline (`PersonaCoreVersions.content`) — it reads a different, empty table and returns an empty string instead. This phase designs, but does not implement, the smallest safe fix. **The core finding: the safest fix boundary is not inside the resolver or the provider at all — it is one line inside `RepositoryContextAssembler.assemble()`, the one place that already holds both a candidate provider result and `core.content` in scope simultaneously.** This phase also identifies a **second, previously-unflagged safety risk**, distinct from the empty-string problem: if a persona master document ever exists but its matching `SkillContextRequirement` does not, `resolveVariantA()` serializes the **entire raw master document — including restricted fields — with no `canAccessField()` filtering at all**. This is not fixed by the primary proposed change and must be tracked as its own, separate required fix.

## 2. Phase 36 Input

Restated, not re-derived: three independent blockers (A: no data, B: resolver doesn't safely degrade, C: production not wired). Phase 37 addresses **only B**, per instruction. A and C are unchanged and out of scope here.

## 3. Current Resolver Control Flow

Traced directly from `PersonaContextResolver.kt`:

```kotlin
fun resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, variant): PersonaContextProjection {
    if (variant == "A") return resolveVariantA(personaCoreVersionId)

    val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
        ?: return resolveVariantA(personaCoreVersionId)                          // (1)

    val skillReq = skillContextReqRepo.findBySkillKey(skillKey)
        ?: return resolveVariantA(personaCoreVersionId)                          // (2)

    val fieldRegistry = fieldRegistryRepo.findAll().associateBy { it.fieldId }
    // ... required/recommended/conditional selection using canAccessField(fieldId, skillKey, fieldRegistry) ...
    val renderedText = renderer.render(fieldValueMap)                            // (3) — can be "" if fieldValueMap is empty
    return PersonaContextProjection(renderedText, ...)
}

private fun resolveVariantA(personaCoreVersionId): PersonaContextProjection {
    val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
    val rawText = masterDoc?.contentJson?.toString() ?: ""                        // (4)
    return PersonaContextProjection(renderedText = rawText, ..., variant = "A")
}
```

`ResolvedPersonaContextProvider.getPersonaContext()` returns `projection.renderedText` directly — whatever the above produces, unfiltered, becomes the caller's result.

**Three distinct exit points can yield an empty or unfiltered string**: (1) no master document, (2) no skill mapping, (3) a master document and mapping both exist but selection yields zero accessible/scored fields. Point (4) is `resolveVariantA()`'s own internal logic, reached from both the top-level `variant == "A"` request and as the fallback target of (1) and (2) — **and it returns the *raw, unfiltered* document whenever a master document *does* exist, regardless of skill or restricted-field status.**

## 4. Today's Production Baseline

Restated from Phase 33/36: `RepositoryContextAssembler.assemble()`:

```kotlin
val personaContent = if (personaContextProvider != null) {
    personaContextProvider.getPersonaContext(request.personaId, core.id, pinnedSkillKey ?: "general_chat", request.content)
} else {
    core.content
}
```

`core` (a `PersonaCoreVersion`) is already fetched earlier in the same method (`personaRepository.getActiveCoreVersion(request.personaId) ?: return StageResult.Failed(...)`), so `core.content` is **already in scope, unconditionally, at the exact point this ternary evaluates** — this is the load-bearing fact behind this phase's recommended fix location (§6-7).

## 5. Failure/Incomplete-State Matrix

| Persona Context state | Expected safe behavior | Actual current behavior (verified, Phase 36 + this phase) | Required change |
|---|---|---|---|
| Valid master document + valid registry + valid skill mapping, fields selected | Persona Context (structured, selective) | **Matches expectation** — this is the only state the system was designed for and the only one validated behaviorally (Phases 14-30) | None |
| Missing master document | Today's baseline (`core.content`) | **Empty string** — `resolveVariantA()`'s own lookup also misses (§3, point 4) | Fallback at the `RepositoryContextAssembler` boundary (§7) |
| Empty master document (`contentJson = {}`) | Today's baseline | **Empty string** — every `resolvePath()` call returns `null`, `fieldValueMap` stays empty, `renderer.render(emptyMap())` returns `""` (confirmed via `PersonaContextRenderer.render()`'s own `if (selectedFields.isEmpty()) return ""` guard) | Same fallback |
| Missing registry (zero `PersonaFieldRegistryEntry` rows) | Today's baseline, **or** a defined degraded behavior — **not established which, by source alone** (see §11) | **Empty string** — `canAccessField()` returns `false` universally | Same fallback closes this to baseline; whether that is the *intended* long-term behavior, as opposed to some registry-aware distinction, is a design choice this phase does not make (§13) |
| Missing skill mapping, **master document present** | **Not established as safe by source** — this is the critical case | **Raw, unfiltered master document** via `resolveVariantA()`, including any restricted field present in the document, with zero `canAccessField()` gating | **A second, distinct required fix** — see §8 |
| Invalid persona ID | Existing, unrelated `VALIDATION_FAILED` at `CONTEXT_ASSEMBLY`, before persona-context resolution is ever attempted | **Matches expectation** — confirmed in Phase 36 §7; not a Persona Context concern at all | None |
| Partially populated Persona Context data (e.g., registry has some but not all fields the document contains; skill mapping references some unregistered field IDs) | **Not established by source** — could mean "degrade gracefully to whatever is available" (arguably the system's designed intent, since `canAccessField()` and the required/recommended/conditional tiers are built to tolerate partial registration) or "treat as unsafe/incomplete and fall back entirely" | A **non-empty**, genuinely partial result — every registered/mapped field renders; every unregistered/unmapped one is silently omitted, with no distinguishing signal in the output between "field doesn't exist for this persona" and "field exists but isn't registered yet" | **Not established; flagged, not resolved, per this phase's own STOP discipline (§15)** |

## 6. Fallback Boundary Options

Three candidate boundaries were considered, per instruction ("resolver, provider, or another existing boundary"):

### Option 1 — Inside `PersonaContextResolver`/`resolveVariantA()`

Would require injecting `PersonaCoreVersionRepository` (or `PersonaCoreVersion.content` directly) into the resolver, which today only knows about `PersonaMasterRepository`/`PersonaFieldRegistryRepository`/`SkillContextRequirementRepository` — an entirely separate data source. This **couples two independently-evolving systems** (the POC's Persona Context tables and production's real `PersonaCoreVersions` table) inside a class whose entire design intent (per its own file-level comments, referenced throughout Phases 10-30) was to be an isolated, swappable research component. **Rejected as the primary fix location** — not because it is impossible, but because it is a larger, more invasive change than necessary for the specific problem (an empty *result*, not an empty *resolver*).

### Option 2 — Inside `ResolvedPersonaContextProvider`

Same coupling problem as Option 1 — the provider would need the same new dependency (`PersonaCoreVersionRepository`) it does not have today, and doing so would mean every other provider variant (`SelectiveRawContextProvider`, `FullRawContextProvider`, `StructuredFullDocumentContextProvider`, and both labeling diagnostics) would need the identical new dependency and identical fallback logic duplicated across six classes to achieve the same safety property consistently. **Rejected** for the same reason as Option 1, compounded by duplication risk across the provider family.

### Option 3 — Inside `RepositoryContextAssembler.assemble()`

The assembler **already holds `core.content` in scope** at the exact point it decides whether to call a provider at all (§4). Making the existing `if (personaContextProvider != null) {...} else { core.content }` ternary additionally treat a **blank** provider result as equivalent to a **null** provider requires no new dependency, touches no Persona-Context-specific file, and applies uniformly to every current and future provider implementation without per-provider duplication. **This is the recommended boundary.**

## 7. Recommended Implementation Shape (descriptive only, not an implementation)

**Not implemented in this phase.** Described for future authorization:

- At the single line in `RepositoryContextAssembler.assemble()` where `personaContent` is computed, change the null-check to also treat a blank (empty or whitespace-only) provider result as "no provider content available," falling back to `core.content` in that case as well as the already-handled null-provider case.
- This is a **single-method, single-line-scope change**, touching no other class, no resolver logic, no renderer logic, and no provider implementation.
- **Byte-for-byte vs. semantic preservation**: this fix would preserve today's production output **byte-for-byte** in every case where the provider result is blank — the exact same `core.content` string, in the exact same position in `engineAndPersonaContent()`'s concatenation, would be used. It is not merely "semantically similar," it is the identical code path production already executes today when `personaContextProvider` is null.
- This fix does **not** address the second risk identified in §8 below (raw, unfiltered master-document exposure when a document exists but a skill mapping does not) — that is a **different** failure mode (non-empty, unsafe content) than the one this fix targets (empty content), and requires its own, separate design.

## 8. Restricted-Field Safety

**Two distinct findings, not one:**

1. **The primary empty-content fallback (§7) cannot itself expose a restricted field** — an empty string contains no fields at all, restricted or otherwise. This is trivially safe.
2. **A second, previously-unflagged risk exists independently**: `resolveVariantA()`, whenever a master document *does* exist (a state that does not occur in production *today*, per Phase 36, but *would* occur the moment Blocker A is even partially resolved for a persona before its skill mappings are complete), serializes `masterDoc.contentJson.toString()` — the **entire raw JSON document** — with **no** `canAccessField()` filtering whatsoever. If that document contains `intimate_anatomy` or `adult_intimacy` content (as the POC's own Simran fixture does, by design, for its own restricted-field testing purposes), this fallback would expose it **regardless of skill**, the exact opposite of the `allowedContexts` gating this whole system is designed to enforce for every other code path. **This is confirmed by direct re-reading of `resolveVariantA()`'s implementation in this phase — it is not a new finding invalidated by anything in Phase 10A's original description of variant A's *intended* design (full, unfiltered, by design, for a controlled research comparison) — but it is a newly-flagged concern specifically in its role as an *unintended fallback target* for a *different*, partially-configured production state, which is a materially different context than its original, deliberate research use.**
3. This second risk is **not fixed by the §7 recommendation**, and is documented here as a distinct, separate required fix (§14), not folded into the primary one.

## 9. Skill/Memory/Profile/History Isolation

Re-confirmed unchanged from Phase 33/34/36 tracing, specifically re-verified against the proposed §7 change: a blank-check added at the `personaContent` computation line touches nothing else in `assemble()` — `memories`, `messages`, `profile`, `continuityBlock`, and the sensitive-preference block are all computed via entirely separate local variables, before and after this line, with no data or control-flow dependency on `personaContent`'s value. `SkillContextEnricher` (a later pipeline stage, per Phase 36 §9) has no code path into `RepositoryContextAssembler` at all. **The proposed fix cannot affect skill, memory, profile, or history behavior** — confirmed by the fix's textual locality (one ternary's right-hand branch) and the surrounding method's variable independence, not merely asserted.

## 10. Deterministic Verification Plan (for when implementation is authorized — not run in this phase)

Using the existing `interceptGeneration=true` no-network pattern, proven since Phase 10C and reused through Phase 30:

- Construct a `World()`-equivalent test fixture with **no** master document seeded for a given persona/version — verify the assembled system block's persona portion equals `core.content` exactly (string equality, not merely non-blank), both before and after the proposed fix (to first reproduce the current empty-string bug deterministically, then confirm the fix closes it).
- Repeat with a master document present but `contentJson = {}` — same assertion.
- Repeat with a populated registry but zero `SkillContextRequirement` rows for the test skill — same assertion, **and additionally** assert the raw-document-exposure risk (§8) does NOT occur, i.e., that no restricted-field marker string appears in the assembled block, once that separate fix (§14) is designed and implemented (not in this phase).
- Repeat with a fully valid, complete Persona Context configuration (mirroring the POC's own Simran fixture) — assert the **existing**, already-validated behavior (Phases 14-30) is unchanged by the new fallback check, i.e., that a non-blank provider result is used exactly as today, untouched by the new `takeIf { it.isNotBlank() }`-style guard.
- **None of this is executed in Phase 37.** This is a plan for the implementation phase, once separately authorized.

## 11. Risks and Unknowns

- Whether "missing registry" and "partially populated data" should both collapse to the same today's-baseline fallback, or whether a future, more sophisticated design might want to distinguish "this persona has no Persona Context data at all" (safe to fall back) from "this persona has some Persona Context data but it's incomplete" (arguably should not silently degrade, since a partial persona might be mid-authoring, not intentionally minimal) — **the current source does not establish an intended answer to this distinction**, and this phase does not invent one.
- Whether the §8 raw-document-exposure risk should be fixed by adding filtering to `resolveVariantA()` itself (which would then also affect the *deliberate*, already-validated research use of "variant A" in Phases 6, 7, 9, 10A — a change to a component this program has otherwise treated as a frozen, intentionally-unfiltered baseline), or by choosing a different, non-`resolveVariantA()` fallback target specifically for the missing-skill-mapping case (e.g., an empty result, treated the same as the missing-document case) — **this is a design choice with real trade-offs this phase surfaces but does not resolve.**
- Whether the recommended §7 fix should live in `RepositoryContextAssembler` permanently, or whether a future architectural change (e.g., finally wiring `PersonaContextResolutionStage`, per Phase 35's finding that it is currently dormant) would relocate this responsibility — out of scope for Phase 37, noted for awareness only.

## 12. Established Facts

- The resolver's control flow has exactly three distinct paths that can yield an empty or unsafe string, traced precisely in §3/§5.
- `core.content` is already in scope at the exact point in `RepositoryContextAssembler.assemble()` where the safest fix would apply — no new dependency is required for the primary fix.
- The primary recommended fix (§7) would preserve today's production behavior byte-for-byte, not merely semantically, for every case it addresses.
- The primary recommended fix cannot affect skill, memory, profile, or history behavior, confirmed by direct code-locality analysis (§9).
- A second, distinct, previously-unflagged safety risk exists (§8): `resolveVariantA()` exposes raw, unfiltered document content — including restricted fields, if present — whenever a master document exists without a matching skill mapping, a state not reachable in production today but reachable the moment Blocker A is partially resolved.
- This second risk is not addressed by the primary fix and requires its own, separate design and fix.

## 13. Not Established

- Whether "missing registry" and "partial data" states should be treated identically to "missing document" (fall back to baseline) or distinctly — the source does not establish an intended design here.
- Which of the two design options for fixing §8's raw-document-exposure risk (filtering inside `resolveVariantA()` vs. changing the missing-skill-mapping fallback target) is preferable — a genuine engineering trade-off, not a fact resolvable from source alone.
- Whether any additional, not-yet-identified edge case exists in the broader `PersonaContextResolver`/provider family beyond the ones this phase traced (e.g., the raw/full-document providers `SelectiveRawContextProvider`/`FullRawContextProvider`, which were not re-audited in this phase since they are not part of the currently-proposed B-vs-LA production path, per Phase 32-36's own scoping).

## 14. Implementation Preconditions

Before the §7 fix (or the separate §8 fix) could be implemented:

- Explicit authorization to modify `RepositoryContextAssembler.kt` (a shared, production-critical file used by every conversation, not only Persona-Context-related ones) — this is a higher-sensitivity change than editing an isolated diagnostic file, and should be scoped and reviewed accordingly.
- A decision on the §11/§13 open design questions (registry/partial-data handling; the §8 fix's design approach) — implementing §7 alone, without addressing §8, would leave a real, if not-currently-reachable, restricted-field exposure risk latent for the moment Blocker A work begins.
- The deterministic verification plan (§10) executed and passing, before any production wiring (Blocker C) is considered — not before this design itself, but before implementation is merged.

## 15. STOP Conditions Encountered

**One STOP-adjacent condition was encountered and is disclosed, per instruction, rather than resolved by invention**: the "missing registry" and "partial data" rows in the failure matrix (§5) do not have a source-established correct behavior — the current code's actual behavior (empty string, or a genuinely partial render) is documented as an *observation*, but this phase explicitly does not assert which behavior *should* occur, since no existing design document, code comment, or architectural decision in this program answers that question. This is recorded in §11/§13 rather than resolved by this phase inventing an answer.

No other STOP condition was encountered — every other question in this phase's scope was answerable directly from existing source code.

## 16. Conclusion

The smallest safe fix for Blocker B (resolver safety) is a single, additive, byte-for-byte-preserving change at the `RepositoryContextAssembler.assemble()` boundary — not inside the resolver or provider layer — treating a blank provider result the same as a null one. This closes the specific empty-content risk Phase 36 identified, for every currently-reachable production state. It does **not** close a second, distinct, newly-identified risk (unfiltered raw-document exposure via `resolveVariantA()`'s fallback role when a master document exists without a matching skill mapping) — that risk requires its own separate design, not undertaken in this phase, and is now explicitly tracked rather than left implicit. Neither fix is implemented here. Blockers A and C remain entirely out of this phase's scope, unchanged from Phase 36.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE37_RESOLVER_FALLBACK_DESIGN.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Tests/checks performed

Source-code tracing only, re-confirming Phase 36's findings and extending them with a full control-flow trace of `PersonaContextResolver.resolve()`/`resolveVariantA()` and `RepositoryContextAssembler.assemble()`'s exact variable scoping. No test was executed; no code was run.

## Exact fallback behavior currently present

Confirmed, precisely: an empty master document lookup, an empty/missing registry, or a missing skill mapping (with no master document present, today's actual production state) all converge on `resolveVariantA()` returning `masterDoc?.contentJson?.toString() ?: ""` — an empty string, not `core.content`. Separately, if a master document *does* exist without a matching skill mapping, `resolveVariantA()` instead returns that document's full, raw, unfiltered content — a different and separately risky behavior (§8).

## Proposed safe implementation boundary

`RepositoryContextAssembler.assemble()`'s existing `personaContent` computation — extend the null-check to also treat a blank provider result as equivalent to a null provider, falling back to `core.content`. No new dependency required; no resolver, renderer, or provider file needs modification for this specific fix. A second, separate fix is required for the raw-document-exposure risk (§8), not addressed by this boundary change alone.

## Unresolved questions

Whether missing-registry/partial-data states should fall back to baseline or degrade differently (§11/§13); which design approach to take for the §8 raw-document-exposure fix; whether `SelectiveRawContextProvider`/`FullRawContextProvider` have analogous unaudited risks (out of this phase's scope); all Blocker A/C items carried forward unchanged from Phase 36.

---

## Explicit confirmation

Zero API calls were made. Zero code was implemented or modified. Zero database mutation occurred. No historical artifact (Phase 10 through Phase 36) was modified. No behavioral experiment was run, designed, or reopened. The Phase 23/25 boundary was not reinterpreted. No production wiring was performed or recommended. Phase 38 is not started automatically.
