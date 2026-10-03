# Phase 35 — Production Transition Deterministic Validation

**Zero API calls. Zero production, mapping, resolver, renderer, or registry code changes.** This is a source-code and migration-file audit only, closing the deterministic questions Phase 34 surfaced. No historical Phase 10–34 artifact was modified — where this phase's deeper source tracing refines or corrects a Phase 34 statement, that correction is disclosed explicitly below (§0), not silently substituted into the Phase 34 document. No behavioral experiment is run. The Phase 23/25 boundary is not reinterpreted. No transition (A, B, LA, or L) is recommended for production.

---

## §0 — Correction to Phase 34, Disclosed Explicitly

Phase 34 (§ESTABLISHED, Step 1 item 7) stated the `personaId`-availability blocker as a single, undifferentiated fact, quoting `PersonaContextResolutionStage.kt`'s own comment. Deeper tracing in this phase (following the actual pipeline stage order, not just the one file's comment) shows the picture has **two distinct parts**, only one of which is the `personaId` blocker as literally stated:

1. **`RepositoryContextAssembler.assemble(request: ChatRequest)`** — the *first* possible persona-context resolution point, running during the `CONTEXT_ASSEMBLY` pipeline stage — **does** have `personaId` available, because it receives the full `ChatRequest` object, and `ChatRequest.personaId: UUID` (`ChatEngine.kt` line 19) is a required, always-populated field. If `personaContextProvider` were non-null here, its call `personaContextProvider.getPersonaContext(request.personaId, core.id, pinnedSkillKey ?: "general_chat", request.content)` would execute with a valid `personaId` today. **`personaId` is not a blocker at this call site.**
2. **`PersonaContextResolutionStage.resolve(context: ChatContext)`** — the *second*, skill-aware refinement point, invoked later in the pipeline (`PipelineChatEngine.kt` line 408: `personaContextResolution?.resolve(skillEnrichedContext) ?: skillEnrichedContext`, running after the `SKILL_SELECTION` stage) — **is** blocked exactly as the file's own comment states, because it operates on `ChatContext`, and `ChatContext` (`ChatEngine.kt` line 63-66) carries `personaCoreVersionId: UUID? = null` but **no `personaId` field at all**. This stage genuinely cannot resolve persona fields today, confirmed by the absence of the field on the type it operates on, not merely by the comment's own assertion.

**Net effect of this correction**: the real architectural constraint is not "personaId is unavailable anywhere" (Phase 34's wording could be read that way) — it is "personaId is available at context-assembly time (before the skill is known) but not at the later, skill-aware refinement stage (after the skill is known)." A naive activation of B at the first call site would run with a **known personaId but a placeholder skill key** (always `"general_chat"`, since production never sets `pinnedSkillKey`), not with an unknown persona. This refines, but does not soften, the underlying risk finding — see §1-2 below for why this ordering has a different, more serious practical consequence than a mere `personaId` gap.

---

## §1 — Objective 1: Can production `personaId` reach the persona-context resolution path?

**Answer: partially, and it depends on which of the two resolution points is meant** (per §0):

- **Context-assembly-time resolution** (`RepositoryContextAssembler` → a hypothetical non-null `personaContextProvider`): **Yes** — `personaId` reaches this path today, confirmed via `ChatRequest.personaId`.
- **Post-skill-selection refinement** (`PersonaContextResolutionStage`): **No** — confirmed blocked by `ChatContext`'s missing `personaId` field, and independently, this stage is never even instantiated anywhere in the codebase (`grep` for `PersonaContextResolutionStage(` outside its own file returns zero matches) — `PipelineChatEngine`'s `personaContextResolution` parameter defaults to `null` and nothing overrides that default, so `personaContextResolution?.resolve(...)` always takes the `?: skillEnrichedContext` branch, a guaranteed no-op in production today.

## §2 — Objective 2 & 5: What happens with skill keys that don't match the POC's hardcoded mappings — and do the POC's 7 keys correspond to real production skill keys at all?

**A more fundamental finding supersedes the original question.** Searching the full `src/main/kotlin` tree for every call site of `PersonaContextPocSeed`'s seeding functions (`seedSkillContextRequirements()`, `seedPersonaFieldRegistry()`, `seedSimranMasterDocument()`) finds **exactly one file referencing them: `PersonaContextPocSeed.kt` itself.** No production startup code (`Application.kt`, `BaselineSeeder.kt`, or any migration) ever calls these functions. They are invoked **only** from the test harness's `World()` constructor in `PersonaContextRealBenchmarkTest.kt`, against an in-memory H2 database created fresh for each test.

**Confirmed via the real migration file** (`db/migration/V019__persona_context_poc.sql`, 106 lines, `grep -ic "INSERT INTO"` = 0): the real production database **has** the `persona_field_registry`, `skill_context_requirement`, and persona-master-document tables (the schema/DDL was migrated), but they contain **zero rows** — no `SkillContextRequirement` entries for any skill key (not `"general_chat"`, not any of the other 6 POC keys, not any real production skill key), no `PersonaFieldRegistry` entries (not even `identity.age`), and no `PersonaMasterDocument` row for the real Simran persona (or any persona).

**Therefore, the answer to "what happens with an unmapped skill key" is not a special/edge case — it is the universal case**: `skillContextReqRepo.findBySkillKey(skillKey)` would return `null` for **every** skill key without exception, because the table is empty, not because of a key-matching mismatch specifically. And to Objective 5 directly: **the POC's 7 hardcoded skill keys do not correspond to anything in the real production database today — not because the strings differ, but because no `SkillContextRequirement` row exists for any key at all.**

## §3 — Objective 3: Is the resolver's unmapped-skill-key fallback deterministic and safe?

Tracing `PersonaContextResolver.resolve()` precisely for this exact real-world condition:

```kotlin
val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
    ?: return resolveVariantA(personaCoreVersionId)   // (a)

val skillReq = skillContextReqRepo.findBySkillKey(skillKey)
    ?: return resolveVariantA(personaCoreVersionId)   // (b)
```

Given the real production database has no seeded master document for any persona, **branch (a) fires first and unconditionally**, before branch (b) is ever reached — the empty `SkillContextRequirement` table (§2) is not even the proximate cause; the missing master document is. Both branches call `resolveVariantA(personaCoreVersionId)`, which itself does:

```kotlin
val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
val rawText = masterDoc?.contentJson?.toString() ?: ""
```

Since `masterDoc` is `null` in real production (confirmed §2), `rawText = ""`. `ResolvedPersonaContextProvider.getPersonaContext()` returns `projection.renderedText`, which equals this empty string.

**Is the fallback deterministic?** **Yes** — given the same (empty) database state, this sequence produces the identical empty-string result every time; there is no non-determinism in the control flow itself.

**Is the fallback safe?** **This is the central, previously-unstated risk finding of this phase: no.** The fallback's *name* ("variant A") implies parity with the historical/current production baseline, but it is **not** equivalent to actual production State A. State A (per Phase 33/34) sends `core.content` — `BaselineConfiguration.SIMRAN_PERSONA_CORE`, a real, non-empty, ~988-token generic personality sketch. The resolver's "variant A" fallback, under real production's actual (unseeded) database state, sends an **empty string** — because it reads from `PersonaMasterDocuments` (always empty in production) rather than `PersonaCoreVersions.content` (the table State A actually reads, which does have real seeded content). **These are two different tables with two different real-world fill states, and the fallback silently produces a strictly worse outcome (zero persona content) than doing nothing at all (State A's real content).**

## §4 — Objective 4: Could the production skill set cause any unexpected persona-field selection?

**No — and the reason is more fundamental than a skill-set mismatch.** Because `PersonaFieldRegistry` is empty in the real production database (§2), `PersonaContextResolver.resolve()`'s field-selection logic (`skillReq.requiredFields?.forEach { ... }`, etc.) would never even be reached for any real skill key, since the master-document check (§3) short-circuits first. Even hypothetically bypassing that (e.g., if a master document did exist but the registry remained empty), `fieldRegistryRepo.findAll()` would return an empty map, `canAccessField()` would return `false` for every field ID (`fieldRegistry[fieldId] ?: return false`), and `selectedFieldIds` would end up empty regardless of what the production skill system emits. **There is no code path by which the real (unseeded) production skill set could cause an unexpected or unintended field to be selected — the more accurate risk is the opposite: total absence of content, not incorrect content.**

---

## Summary Table

| Objective | Finding | Deterministic? | Safe? |
|---|---|---|---|
| 1. Can `personaId` reach the resolution path? | Yes, at context-assembly time (via `ChatRequest`); No, at the later skill-aware refinement stage (via `ChatContext`, which lacks the field) — and that stage is unused/dormant regardless | Yes — both facts are fixed by current type definitions, not runtime-variable | N/A — this is a capability question, not a safety question |
| 2. What happens with unmapped skill keys? | Every real skill key is effectively "unmapped," because the production database's `SkillContextRequirement` table has zero rows | Yes | The resulting fallback path is deterministic but not safe (see 3) |
| 3. Is the fallback deterministic and safe? | Deterministic: yes. Safe: **no** — it silently produces empty persona content, worse than doing nothing, because it reads an empty `PersonaMasterDocuments` table rather than the populated `PersonaCoreVersions.content` field State A actually uses | Yes | **No** |
| 4. Could the production skill set cause unexpected field selection? | No — the empty registry means no field can ever be selected, not that the wrong field could be | Yes | Yes, in the narrow sense that no *unintended exposure* risk exists — the risk is total content loss, not incorrect content |
| 5. Do the POC's 7 skill keys correspond to real production skill keys? | Not established by string-matching, because no `SkillContextRequirement` row exists for *any* key, POC or otherwise — the question of string correspondence is moot until the table is seeded | N/A | N/A |

---

## What This Phase Establishes

- The `personaId` blocker described in Phase 34 (quoting `PersonaContextResolutionStage.kt`'s comment) applies specifically and only to the dormant, never-instantiated post-skill-selection refinement stage — not to a hypothetical activation of `personaContextProvider` at context-assembly time, where `personaId` is already available today.
- **A more serious, previously-unstated blocker exists independent of the `personaId` question**: the real production database contains zero rows in `PersonaFieldRegistry`, `SkillContextRequirement`, and (for any real persona) `PersonaMasterDocuments` — none of the POC's seeding functions are ever invoked outside the test harness's in-memory database.
- Because of this, naively activating `personaContextProvider` in production today (even after resolving the `personaId`/skill-timing question) would not produce "variant A"-equivalent behavior as the resolver's internal fallback naming suggests — it would produce **empty persona content**, a regression from current State A, not a neutral or safe fallback.
- The unmapped-skill-key question (Objective 2/5) is subsumed by this larger finding: it does not matter whether production's real skill keys match the POC's 7 hardcoded strings, because the mapping table itself is empty regardless of key spelling.
- No unintended-field-exposure risk exists from this specific gap — the risk this phase identifies is total content loss, not incorrect or excessive content.

## What Remains Unresolved

- Whether production's real, currently-configured skill keys (a `SkillRepository` runtime/database question, not resolvable by static source reading, per Phase 34's own note) would, if the mapping tables were properly seeded, correspond usefully to the POC's 7-skill design — genuinely unknown until that seeding work is done.
- Whether resolving the `personaId`/`ChatContext` gap for the second-stage refinement is worth pursuing at all, given the more fundamental data-seeding gap identified here — this is now clearly a prerequisite question, not a parallel one.
- Everything already unresolved per Phase 32/33/34 (causal mechanism, LA-vs-L, generalization, real token/latency measurement) — unchanged by this phase.

## Required Before Any A→B Activation (revised, superseding Phase 34's Step 8 "A→B" list in emphasis, not in substance — Phase 34's list remains valid and is not repeated in full here)

- **New, highest-priority item surfaced by this phase**: a decision on how `PersonaFieldRegistry`, `SkillContextRequirement`, and `PersonaMasterDocument` rows would be populated for any real production persona — via a production-appropriate seeding/authoring process, not the POC's hardcoded `PersonaContextPocSeed.kt` (which is scoped to the fictional "Simran" benchmark persona and was never intended for production use, per its own class-level comments throughout this program's history).
- A safe-fallback fix: `resolveVariantA()`'s empty-string result when no master document exists should not silently reach the model as an empty persona block — at minimum, a null/empty check that preserves State A's actual `core.content` behavior instead of substituting an empty string, if `personaContextProvider` is ever activated before the data-seeding gap above is closed.
- All items already listed in `PHASE34_PRODUCTION_PIPELINE_SEPARATION_AUDIT.md`'s "Required before A→B" section remain valid and are not superseded by this phase — this phase adds to that list, it does not replace it.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE35_PRODUCTION_TRANSITION_DETERMINISTIC_VALIDATION.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Source paths inspected (new in this phase, beyond Phase 34's list)

`src/main/kotlin/com/pinkdreams/chat/ChatEngine.kt` (full `ChatRequest`/`ChatContext`/`PipelineStage`/`PipelineChatEngine` definitions and the `process()` method's stage-ordering and `personaContextResolution?.resolve(...)` call site), `db/migration/V019__persona_context_poc.sql` (confirmed schema-only, zero seed `INSERT` statements).

## Deterministic checks performed

Source-code and migration-file tracing only (no code execution, no database query against any live instance, no test run). The "zero seed rows" finding is inferred from the complete absence of any production code path that calls the POC's seeding functions, cross-checked against the migration file containing no `INSERT` statements — this is as close to a deterministic proof as static analysis permits without an actual database inspection, which this phase does not perform.

## Unresolved items

Whether production's real skill keys would meaningfully map to the POC's 7-skill design once the data-seeding gap is closed; whether to pursue the `personaId`/`ChatContext` plumbing fix for the dormant second-stage refinement at all, given the more fundamental seeding gap; all items carried forward from Phase 32/33/34.

## Explicit confirmation

Zero API calls were made. Zero production, mapping, resolver, renderer, provider, or registry changes were made. No historical artifact (Phase 10 through Phase 34) was modified — this phase's refinement of Phase 34's `personaId` characterization is recorded here as an explicit addendum, per §0, not as a silent edit to the Phase 34 document. No behavioral experiment was designed or executed. The Phase 23/25 boundary was not reinterpreted. No transition (A, B, LA, or L) was recommended for production. Phase 36 is not started automatically.
