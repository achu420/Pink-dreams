# Phase 44 — Production Architecture Refactor Plan

**Zero API calls. Zero production code, database, schema, or mapping changes. Zero Phase 10–43 historical artifact modifications.** This is an architecture/design document only. Per the context change in this instruction, the application is not yet in production and has no backward-compatibility requirement — production code may be refactored substantially; historical experiment artifacts (documents, CSVs, and the existing research classes Phases 6–30 depend on for reproducibility) remain untouched regardless.

Every conclusion below is tagged **ESTABLISHED BY SOURCE**, **DERIVED FROM SOURCE**, **DESIGN DECISION**, **HUMAN DECISION REQUIRED**, or **NOT YET VERIFIED**.

---

## 1. Executive Summary

This phase re-traces the full current pipeline from source (§2), confirming no discrepancy from Phase 33–43's own tracing. It then designs a clean production architecture built around treating D1–D6 as locked: no disguised raw-document fallback inside the resolver (D1), strict substitution at the application layer (D2), a plain data class result contract (D3), one unified `PersonaContextProvider` contract across all six providers (D4), a binary `USABLE`/`NOT_USABLE` result with no `PARTIAL` (D5), and a hard separation between *resolution* (can Persona Context be produced) and *fallback policy* (what to do if not) (D6). **The central architectural move**: rather than editing the existing, benchmark-proven `PersonaContextResolver.kt`/`resolveVariantA()` in place, this plan proposes a **new, parallel production resolver** that has no concept of a raw/unfiltered "variant A" fallback at all — the historical class continues to serve Phases 14–30's own reproducibility needs completely untouched. This resolves D1 and the "no backward-compatibility requirement" instruction simultaneously: production code is free to be clean because it is new code, not a retrofit of research code.

## 2. Current Production Architecture (re-traced from source this phase)

```text
Application.kt
    — chatEngineDependencies constructed once at startup; NO reference to personaContextProvider
      anywhere in the file — confirmed by direct grep in this phase (zero matches), not merely
      inherited from Phase 33/34's own finding
    ↓
ChatEngineFactory.kt
    — Dependencies.personaContextProvider: PersonaContextProvider? = null (default, never overridden)
    ↓
RepositoryContextAssembler.assemble(request: ChatRequest)
    — core = personaRepository.getActiveCoreVersion(request.personaId)  [always fetched]
    — personaContent = if (personaContextProvider != null) { provider.getPersonaContext(...) } else { core.content }
    ↓
[personaContextProvider is null in production today, so the provider branch is dead code in practice]
    ↓
PersonaContextResolutionStage.kt
    — re-confirmed in this phase: still never instantiated anywhere outside its own file
      (grep for "PersonaContextResolutionStage(" outside the class declaration returns zero matches)
    — PipelineChatEngine's personaContextResolution parameter still defaults to null and is never
      overridden — this dormant, post-skill-selection refinement stage remains a guaranteed no-op
    ↓
engineAndPersonaContent(engine.content, personaContent) → model request
```

**No discrepancy found** between this re-trace and Phase 33–43's own descriptions — both `Application.kt`'s omission of `personaContextProvider` and `PersonaContextResolutionStage`'s dormancy are re-confirmed unchanged. — **ESTABLISHED BY SOURCE**

## 3. Current Architecture Problems

- `PersonaContextResolver.resolveVariantA()` serves three conflated roles: (a) the deliberate, historical "variant A" research request; (b) the fallback target when a master document is missing; (c) the fallback target when a skill mapping is missing. Roles (b) and (c) silently reuse (a)'s unfiltered, unlabeled behavior. — **ESTABLISHED BY SOURCE** (Phases 37–40, re-confirmed §2 above)
- `PersonaContextProvider.getPersonaContext(...): String` discards every signal (`variant`, `includedFieldIds`) needed to distinguish a genuine selective result from a disguised fallback. — **ESTABLISHED BY SOURCE**
- `RepositoryContextAssembler` can only branch on provider *nullity*, not on result *usability* — the one signal it needs (`core.content`) is in scope but nothing it can compare it against exists. — **ESTABLISHED BY SOURCE**
- No production authoring path exists for any Persona Context table (Phase 36, re-confirmed unchanged by this phase's own grep in §2). — **ESTABLISHED BY SOURCE**
- The resolver's own naming (`resolveVariantA`) actively misleads a reader into believing its fallback role is equivalent to today's real baseline, when it reads an entirely different, always-empty table. — **DERIVED FROM SOURCE**

## 4. Locked Architectural Decisions D1–D6

Restated as design inputs, not re-litigated (per instruction, "unless source inspection proves one technically impossible" — none was found to be impossible):

| Decision | Locked position | Source-inspection check |
|---|---|---|
| D1 | No disguised raw fallback in production resolver | Technically achievable — confirmed no code path requires `resolveVariantA()`'s specific shape; a new resolver can simply omit that concept |
| D2 | Strict substitution: unusable → `core.content`, no exceptions | Technically achievable — `core.content` is already in scope at the one required call site (§2) |
| D3 | Plain data class for the result contract | Technically achievable — the four fields (`renderedText`, `includedFieldIds`, `returnedVariant`, `requestedVariant`) are simple values, no polymorphic behavior needed |
| D4 | One contract across all six providers | Technically achievable, **with a documented nuance** for the three non-selective providers (§11) — none of the six is structurally incapable of returning the new contract type |
| D5 | Binary `USABLE`/`NOT_USABLE`, no `PARTIAL` | Technically achievable — confirmed no existing source *requires* `PARTIAL` (Phase 40/41, re-confirmed) |
| D6 | Resolution and fallback policy are separate concerns | Technically achievable — this is the direct consequence of removing D1's disguised fallback: once the resolver no longer "decides" to fall back, that decision naturally belongs entirely to the caller |

## 5. Proposed Target Architecture

```text
Application.kt
    ↓ (new: constructs a production PersonaContextProvider, once Blocker A/C work is separately done)
ChatEngineFactory.kt
    ↓
RepositoryContextAssembler.assemble()
    — personaContent = resolvePersonaContent(provider, request, core)   [new helper, D6's fallback policy owner]
    ↓
resolvePersonaContent(...):
    val result = provider?.getPersonaContext(...)   // PersonaContextResult?, D3's data class
    return if (result != null && result.isUsable()) result.renderedText else core.content
    ↓
[new] PersonaContextProvider.getPersonaContext(...): PersonaContextResult   [D3/D4's unified contract]
    ↓
[new] ProductionPersonaContextResolver.resolve(...): PersonaContextResult?
    — returns null (or a NOT_USABLE-classified result — see §7) if resolution cannot succeed;
      NEVER returns a raw, unfiltered document dressed as a normal result (D1/D6)
    ↓
PersonaContextRenderer  [unchanged, reused as-is — no reason to duplicate a working, pure function]
```

**The existing `PersonaContextResolver.kt` (with `resolveVariantA()`) is NOT modified.** It continues to back the benchmark harness's A/B/C/D/X/FR variants for Phases 14–30's own reproducibility, exactly as today. — **DESIGN DECISION**

## 6. Resolver Responsibility

**Precisely**: the new production resolver answers *only* "what Persona Context can be safely resolved for this request?" — it must **not** also answer "what should the application use instead if resolution fails?" (D6). — **DESIGN DECISION**, directly implementing D1/D6.

**Cleanest API, evaluated**: `fun resolve(...): PersonaContextResult?` — nullable return, no fallback-flavored result. This is preferred over a non-null result that self-classifies as `NOT_USABLE`, because a `null` return makes "resolution failed" impossible to accidentally treat as content at the type level (a non-null `PersonaContextResult` could in principle be read for its `renderedText` even by a caller that forgot to check usability; a `null` cannot). **This is not an invented abstraction** — it uses Kotlin's existing nullable-type mechanism, the same pattern already used elsewhere in this codebase (`personaMasterRepo.findByPersonaCoreVersionId(...): PersonaMasterDocument?`, `skillContextReqRepo.findBySkillKey(...): SkillContextRequirement?`). — **DESIGN DECISION**, with the nullable-return convention itself **DERIVED FROM SOURCE** (matches existing repository-layer idiom in this codebase).

**What the resolver should NOT do**: know about `core.content`, `PersonaCoreVersionRepository`, or any concept of "baseline" — confirmed no existing resolver logic needs this, and introducing it would recouple two independently-evolving data sources (Phase 37 §6's Option-1 rejection reasoning, still valid here).

## 7. Result Contract

```kotlin
data class PersonaContextResult(
    val renderedText: String,
    val includedFieldIds: List<String>,
    val returnedVariant: String,
    val requestedVariant: String,
)
```

**Evaluated against "should more fields be added?"**: no additional field is derivable as necessary from any traced source requirement (Phase 39 §4's finding, re-confirmed) — `excludedFieldIds` and `personaTokenEstimate` exist on the *old* `PersonaContextProjection` but nothing in the new architecture's traced requirements needs them; they are **not** added merely because they might be useful someday, per instruction. — **DESIGN DECISION**, field set **DERIVED FROM SOURCE** (Phase 39 §4's necessity analysis, unchanged).

**No sealed hierarchy or state machine**: confirmed unnecessary — the usability predicate (§8) is a pure function of the four fields above; no additional state transitions or polymorphic behavior is required anywhere in the traced flow. — **DESIGN DECISION**, per D3.

## 8. USABLE / NOT_USABLE Contract

```kotlin
fun PersonaContextResult.isUsable(): Boolean =
    requestedVariant == returnedVariant && includedFieldIds.isNotEmpty() && renderedText.isNotBlank()
```

Where this should live: as an extension function (or method) on `PersonaContextResult` itself, in the same file as the data class — a single, authoritative definition, not duplicated at each call site. — **DESIGN DECISION**.

**Explicitly confirmed, not assumed**: `renderedText.isBlank()` alone is never used as the sole test — the `includedFieldIds.isEmpty()` check independently catches the dangerous case where `renderedText` is non-blank (a raw, unfiltered document) but no fields were genuinely selected. This is the exact predicate derived and re-verified across Phases 39, 40, and 41 — unchanged here, only relocated into the new architecture's home. — **ESTABLISHED BY SOURCE** (the predicate logic itself); **DESIGN DECISION** (its placement as a method on the new type).

In the new architecture, since the resolver never returns a disguised-fallback result at all (§6), a *production* caller receiving a non-null `PersonaContextResult` should, in the overwhelming majority of correctly-functioning cases, always find `isUsable() == true` — the predicate remains as a **defense-in-depth check**, not the primary mechanism (which is now the resolver returning `null` outright for any state it cannot genuinely resolve). This is a meaningful architectural improvement over the old design, where the *only* way to detect a disguised fallback was this predicate. — **DERIVED FROM SOURCE**.

## 9. Restricted-Field Safety

**Where filtering belongs**: inside the new production resolver itself, at the single point selection occurs — the same `canAccessField()` gate the existing research `PersonaContextResolver.kt` already uses correctly for its own B/C path (0 leakage, Phases 10C–30). The new resolver's *only* code path is this selective one; there is no separate raw-dump branch to forget to filter (D1's direct consequence). — **DESIGN DECISION**, gate mechanism **ESTABLISHED BY SOURCE** (reused, not reinvented).

**Confirms all three required guarantees**:
- Restricted fields cannot escape through a missing/unknown skill mapping — because there is no longer any "return the whole document anyway" branch for that case; a missing skill mapping simply means resolution returns `null`. — **DERIVED FROM SOURCE**
- Raw master documents cannot bypass `canAccessField()` — because the new resolver has no raw-dump code path at all. — **DERIVED FROM SOURCE**
- Fallback behavior cannot accidentally serialize an entire unfiltered document — because fallback (§10) only ever substitutes `core.content`, never any Persona-Context-sourced text. — **DERIVED FROM SOURCE**

**Smallest coherent boundary, confirmed**: one filtering point (the new resolver's selection loop), not duplicated in the renderer, repository layer, or assembler — consistent with the "prefer the smallest coherent boundary" instruction, and consistent with how the existing research code already does it correctly for its own B/C path.

**Historical, deliberately-unfiltered research providers** (`FullRawContextProvider`, `StructuredFullDocumentContextProvider`) intentionally serialize the complete, unfiltered document **by design**, confirmed by direct re-reading of both files in this phase (§11) — this is not a bug to fix; it is these two classes' entire documented purpose (Phase 7/10C's structure/selection isolation research). They remain untouched and are never part of the new production resolver's code path.

## 10. Application-Level Fallback

```text
Persona Context usable          → use Persona Context (result.renderedText)
Persona Context not usable      → use PersonaCoreVersions.content
Persona Context unavailable     → use PersonaCoreVersions.content   (provider itself null, resolver returned null)
Persona ID invalid/unavailable  → ALREADY HANDLED, upstream of persona-context logic entirely —
                                   RepositoryContextAssembler.assemble()'s pre-existing
                                   personaRepository.getActiveCoreVersion(request.personaId)
                                   ?: return StageResult.Failed(VALIDATION_FAILED) check fires first;
                                   persona-context resolution is never attempted — confirmed unchanged,
                                   re-verified against current source in this phase
```

All four rows are **ESTABLISHED BY SOURCE** or direct **DESIGN DECISION** consequences of D2/D6 — no row required inventing behavior the source does not define.

## 11. Six-Provider Migration Plan

Each provider re-inspected directly from its current full source in this phase:

| Provider | Currently returns | Selects fields? | Renders fields? | Is `variant` meaningful today? | Is `includedFieldIds` meaningful today? | Change required under the new contract |
|---|---|---|---|---|---|---|
| `ResolvedPersonaContextProvider` | `projection.renderedText` (B or C) | Yes, via `resolve()` | Yes, via `PersonaContextRenderer` | Yes — echoes requested B/C, or becomes "A" on fallback | Yes — the real selection result | Trivial: return the full `PersonaContextResult` instead of just `renderedText`; every needed field already exists on `PersonaContextProjection` |
| `AgeOnlyLabeledScalarDiagnosticProvider` | Custom-rendered string (age-labeled) | Yes, reuses `resolve(..., "B")` verbatim | Yes, own copy of rendering logic | Yes, same as above (always "B" requested) | Yes, same as above | Trivial, same reasoning |
| `LabeledScalarDiagnosticProvider` | Custom-rendered string (all-scalar-labeled) | Yes, reuses `resolve(..., "B")` verbatim | Yes, own copy of rendering logic | Yes | Yes | Trivial, same reasoning |
| `SelectiveRawContextProvider` (X) | Raw JSON of B's selected fields | Yes, reuses `resolve(..., "B")`'s `includedFieldIds` verbatim | No — raw JSON, no `PersonaContextRenderer` | Meaningful in the same sense as the above three (always requests "B" internally) | Yes — genuinely reflects B's real selection | Straightforward: `includedFieldIds` is already available; `requestedVariant`/`returnedVariant` can both report `"B"` (this provider never diverges from its own internal request) |
| `FullRawContextProvider` (FR) | Raw JSON of the **entire** document | **No** — no selection concept, always everything | No | **Not meaningful** — no variant request occurs at all internally | **Not meaningful** — no selection occurs | **Requires a documented convention, not a mechanical mapping**: `includedFieldIds` would need to report either an empty list (technically accurate — nothing was "selected," everything was included unconditionally) or the full list of top-level document keys (more informative but a new computation this provider doesn't currently perform). `requestedVariant`/`returnedVariant` would need placeholder values (e.g. both `"FR"`) since no B/C request concept applies. **This is a genuine design choice, not a fact** |
| `StructuredFullDocumentContextProvider` (D) | Structured markdown of the **entire** document | **No**, same as FR | Yes, via `PersonaContextRenderer` (on the whole document as one field) | **Not meaningful**, same as FR | **Not meaningful**, same as FR | Same open convention question as FR |

**Nuance flagged, not glossed over**: D4's "one contract across all six providers" is achievable for all six *mechanically* (every one can be made to return a `PersonaContextResult`), but for the two full-document providers (FR, D), the `variant`/`includedFieldIds` fields carry **no genuine selection-safety meaning** — they never had a "requested vs. returned" divergence risk to begin with, since they have no selective/fallback behavior at all. Applying the `isUsable()` predicate to their output would need those fields populated with placeholder-but-consistent values (so `requestedVariant == returnedVariant` trivially holds) purely for interface uniformity, not because the predicate is doing meaningful safety work for these two classes. — **HUMAN DECISION REQUIRED**: exact placeholder convention for FR/D's `includedFieldIds`/`variant` fields (empty list vs. full-key list; which literal variant string to use).

## 12. Production Wiring Plan

Re-verified against current source in this phase, not assumed unchanged:

- `personaContextProvider` is still not constructed anywhere in `Application.kt` — confirmed, §2.
- Persona Context tables are still empty in production — **NOT VERIFIED IN THIS PHASE** (this would require a live database query, out of scope for a zero-API/zero-mutation design phase; Phase 36's finding — no production code path ever writes to these tables — remains source-confirmed and is the basis for continuing to treat them as empty, but this phase does not re-query a live database to confirm zero rows). — **NOT YET VERIFIED** (this specific claim, distinguished from the code-path finding which IS re-verified).
- Production still uses `PersonaCoreVersions.content` exclusively — confirmed, §2.
- Persona Context seeding/authoring still has no production path — confirmed, §2 (re-grepped, same finding as Phase 36).
- `personaId` availability: confirmed unchanged from Phase 35's refined finding — available at `RepositoryContextAssembler.assemble()` (via `ChatRequest.personaId`), not available at the still-dormant `PersonaContextResolutionStage` (via `ChatContext`, which still lacks a `personaId` field — re-confirmed by re-reading `ChatContext`'s definition in this phase, unchanged since Phase 35/38).

**What must change for production to eventually use Persona Context** (design only, not implemented): `Application.kt` would need to construct the new production resolver/provider and pass it into `chatEngineDependencies`, once Blocker A (data population, separate track) and this phase's Changes (new resolver, new contract, new fallback logic) all exist. This remains gated exactly as Phase 34/36 described — this phase does not change that gating, only the internal shape of what gets wired once it is.

## 13. Persona Data Population Plan (designed separately from the resolver, per instruction)

- **Which tables need population**: `persona_field_registry`, `skill_context_requirement`, and the persona-master-document table, for at least one real production persona — unchanged from Phase 36.
- **How field-registry data gets created**: **no existing production mechanism does this today** (Phase 36 §10, re-confirmed §2/§12 above) — this would need new tooling.
- **How skill mappings get created**: same — no existing mechanism.
- **How master documents get authored**: same — no existing mechanism; note this is a fundamentally different kind of content (structured, field-level biographical data) than what `AdminPersonaRoutes.kt` currently authors (`PersonaCoreVersion.content`, a single free-text block) — the two are not interchangeable without new authoring UX.
- **Should this be migration data, admin tooling, seed data, or another mechanism?** **HUMAN DECISION REQUIRED** — no existing appropriate production mechanism was found (confirmed, not assumed); this phase does not invent one, per instruction ("do not invent an authoring system if an existing appropriate production mechanism exists" — none exists, so the choice of what to build is left open, not defaulted).
- **What validation is required before a persona becomes eligible?** At minimum, per §7/§8's contract: enough registered fields and skill mappings that at least one real skill's request would classify `USABLE` — the exact minimum is the deferred `PARTIAL` question (D5), which this phase does not reopen.

**No database is populated in this phase.**

## 14. Empty/Missing/Invalid Data Behavior

No `PARTIAL` state is introduced (D5). Every condition below resolves to the resolver returning `null` (or an equivalent non-usable signal), triggering fallback per §10:

| Condition | New architecture's behavior | Basis |
|---|---|---|
| Master document absent | Resolver returns `null` | New resolver design — no fallback content generated at all |
| Master document is `{}` | Resolver returns `null` (or a `PersonaContextResult` with empty `includedFieldIds`, itself `isUsable() == false`) | Same predicate as §8 |
| Registry empty | Resolver returns `null` — no field can pass the access gate | Same |
| Skill mapping absent | Resolver returns `null` | Same — no raw-dump fallback exists to accidentally trigger |
| Skill key unknown | Identical to "skill mapping absent" — the new resolver has no way to distinguish them either, and does not need to, since both simply fail to resolve | Same reasoning as Phase 38 §4 states 11–12, structurally still true in the new design |
| Only some fields registered | Resolver returns a `PersonaContextResult` with a genuinely partial (but non-empty) `includedFieldIds` — classified `USABLE` by the binary predicate | **Explicitly not reclassified** — this is exactly D5's locked position; a persona with 1 of 10 intended fields is treated as usable, since no minimum-viability concept is introduced |
| Selected field set empty (registry/mapping technically present but nothing scored/required) | Resolver returns `null` (or a result with empty `includedFieldIds`, `isUsable() == false`) | Same as "registry empty" |

**Where source does not establish whether a condition should be `USABLE`** (specifically: whether "only some fields registered" is a state that *should* be usable, as opposed to merely *is classified as* usable by the mechanical predicate) — this remains **HUMAN DECISION REQUIRED**, unchanged from Phase 39/40/41/42/43's own repeated, deliberate non-resolution of this exact question. This phase does not guess.

## 15. Testing Strategy

### Unit tests
- Usability predicate: all cases from Phase 41 §4's ten-row table, re-targeted at the new `PersonaContextResult`/`isUsable()`.
- Variant mismatch detection.
- Zero-`includedFieldIds` detection.
- The critical case: non-empty `renderedText` with empty `includedFieldIds` (must classify `NOT_USABLE`).
- Restricted-field filtering: confirmed absent from output under every unmapped/unknown-skill condition.
- Empty master document, missing master document, unknown skill, missing mapping — each asserting the new resolver returns `null`.
- Strict baseline fallback: `resolvePersonaContent(...)` returns exactly `core.content` for every `null`/`NOT_USABLE` input.

### Provider contract tests
All six providers (§11) tested against the unified `PersonaContextResult` contract, including explicit tests for the FR/D placeholder-convention decision once made (§11's flagged human decision).

### Integration tests
Full assembler → resolver → provider → renderer chain, using the existing `interceptGeneration=true` zero-network pattern proven since Phase 10C.

### Production-path tests
**New requirement, not previously performed by any prior phase**: tests against the *actual* `Application.kt`/`ChatEngineFactory` construction path, not only the `TestChatService` benchmark harness — closing the gap Phase 34/36/38 repeatedly flagged ("every prior equivalence check used the test harness, never real production wiring").

### Regression tests
`core.content` remains available and byte-identical as the fallback in every applicable case; existing State-A production behavior (provider still null, pending Blocker A/C) remains completely unchanged by any of this phase's design, since none of it is wired into `Application.kt` yet.

**No test is run in this phase** — this is a design of the test layers only, per instruction.

## 16. Migration/Implementation Sequence (design only)

1. Define `PersonaContextResult` (§7) and `isUsable()` (§8) — no dependencies.
2. Build the new production resolver (§6), with restricted-field filtering built in from the start (§9) — depends on step 1.
3. Update `RepositoryContextAssembler`'s fallback logic (§10) — depends on steps 1–2.
4. Migrate the six existing providers (§11) to the new contract — depends on step 1; independent of steps 2–3, since these providers do not call the new resolver.
5. Resolve the FR/D placeholder-convention decision (§11) — blocks step 4's completion for those two classes specifically, not the other four.
6. Write the full test suite (§15) — depends on steps 1–4.
7. **Separately, on an independent track**: design and build the data-population mechanism (§13) — has no code dependency on steps 1–6, but production activation (step 8) needs both tracks complete.
8. Wire `Application.kt` to construct and pass the new provider — depends on steps 1–6 AND step 7 (Blocker A) being complete; this step is **not** part of this phase's scope and is not designed further here (Blocker C, per Phase 34/36).

## 17. Token/Latency Measurement Plan

**No claim is made that token cost or latency is acceptable** — confirmed, this phase makes no such claim, per instruction. Phase 26/30's behavioral experiments never captured real token/usage figures for B/LA calls (Phase 33 §3, unchanged). **How this should be measured after implementation**: once the new resolver/provider/fallback logic exists and is exercised (even only in a staging or test environment against real API calls, separately authorized), capture `usage.prompt_tokens`/`usage.completion_tokens` from the real OpenRouter response for a representative sample of requests under each of: (a) baseline `core.content`, (b) new Persona Context `USABLE` path, (c) the strict-fallback path — comparing real, provider-reported figures, not the character-based `length/4` heuristic this program has used throughout for informal estimates. **No speculative optimization is added** — nothing in this design changes rendering to reduce token count pre-emptively, since no measurement yet justifies doing so.

## 18. Risks and Unresolved Decisions

- FR/D's placeholder `variant`/`includedFieldIds` convention (§11) — **HUMAN DECISION REQUIRED**.
- The data-population mechanism's form (migration/admin tooling/other, §13) — **HUMAN DECISION REQUIRED**.
- Whether "some fields registered, not all" should ever be reclassified away from `USABLE` — **HUMAN DECISION REQUIRED**, deliberately not resolved (D5, restated).
- Whether production Persona Context tables are genuinely still empty **right now** — **NOT YET VERIFIED** in this phase specifically (no live database query was performed); the *code-path* finding (nothing writes to them) is re-confirmed, but the *current row count* is not independently re-checked here.
- Whether the new production resolver should live under a new package/class name distinct from `PersonaContextResolver` to avoid any naming confusion with the frozen research class — **DESIGN DECISION**, recommended (avoid name collision) but not mandated by source; the exact naming is left open.

## 19. Exact Files/Classes Expected to Change (none touched in this phase)

**New files** (all additive, zero existing-file edits required for these): a new production resolver class (name TBD, §18); the `PersonaContextResult` data class + `isUsable()` (§7/§8); new production-path test files.

**Modified files**: `RepositoryContextAssembler.kt` (fallback logic, §10); `PersonaContextProvider.kt` (interface return type, §7); all six existing provider classes (§11, mechanical return-type updates); `Application.kt` (only at the final wiring step, §16 step 8 — explicitly out of this phase's design scope, deferred to Blocker C).

**Explicitly NOT modified, ever, by this plan**: `PersonaContextResolver.kt` (the existing research class, including `resolveVariantA()`) — remains exactly as-is, serving Phases 14–30's benchmark harness unchanged; every Phase 10–43 document and CSV.

## 20. Explicit "NOT IMPLEMENTED" Section

Nothing in this phase was implemented. Specifically **not done**: no new class was created; no existing file was edited; no test was written or run; no database table was populated or queried; no `Application.kt` wiring occurred; no API call was made; no Phase 10–43 artifact was modified.

---

## IMPLEMENTATION READY

**Not fully** — three items remain `HUMAN DECISION REQUIRED` (§18) before implementation of the six-provider migration (specifically the FR/D convention) and the data-population track can be fully specified. **The resolver/contract/fallback core (§6–§10, §14) has concrete, source-supported answers and could begin implementation independently of those three open items**, since none of them blocks steps 1–3 or 6 of §16's sequence.

## IMPLEMENTATION BLOCKERS

1. FR/D placeholder-convention decision (§11, §18).
2. Data-population mechanism decision (§13, §18).
3. Whether to reclassify "some fields registered, not all" away from `USABLE` (§14, §18) — though note this can remain deferred indefinitely without blocking implementation, per D5's own locked position.
4. `Application.kt` production wiring itself (§12, §16 step 8) — explicitly out of scope for this plan, a separate, later decision (Blocker C).
5. Live confirmation that Persona Context tables are currently empty (§18) — not verified in this phase; assumed unchanged from Phase 36's code-path finding but not independently re-queried.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE44_PRODUCTION_ARCHITECTURE_PLAN.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

---

Phase 44 status: PRODUCTION ARCHITECTURE PLAN COMPLETE — AWAITING REVIEW BEFORE IMPLEMENTATION.
