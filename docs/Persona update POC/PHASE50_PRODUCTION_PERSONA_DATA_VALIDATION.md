# Phase 50 — Production Persona Data Authoring & Pre-Activation Validation

No API calls. No production database mutation. No LA/B/AllLabeled product decision. No new persona invented. All facts used are real, sourced from `docs/personas/Simran/simran-master.md` and `BaselineConfiguration.SIMRAN_PERSONA_CORE` (the actual production content), cross-checked this phase, not assumed from prior reports.

Tag legend: **ESTABLISHED** (re-verified from current source this phase), **OBSERVED** (directly seen in a test run), **DERIVED**, **UNKNOWN**, **HUMAN DECISION REQUIRED**.

---

## 1. Executive Summary

Phase 50 built a deterministic, test-scoped persona-data path for Simran (the real, existing production persona) and validated the Phase 49 production mechanism against it — entirely in an in-memory H2 fixture, never a persistent database. The most consequential finding is in §3: **the real production `PersonaCoreVersions.content` for Simran (`BaselineConfiguration.SIMRAN_PERSONA_CORE`) contains no biographical facts at all** — it is a purely generic behavioral/tone specification. Every concrete fact (name, age, hometown, occupation, family, etc.) exists only in `docs/personas/Simran/simran-master.md`, a documentation file with no live database connection today. This means there is no "compiler from PersonaCoreVersions.content" to build — the two artifacts serve genuinely different purposes and were never meant to be the same content compiled two ways. A second consequential finding (§6): the research seed's skill keys `about` and `perspective_of` are **not real production skill keys** — they do not appear in `BaselineConfiguration.SKILLS`. This phase's fixture uses only verified real skill keys (`general_chat`, `friendship`, `romantic_intimacy`, `foreplay`, `advice`). 17 new deterministic tests were written and pass, covering the full restricted-field matrix (A-J), skill-mapping validation, rendering-policy isolation, budget behavior at three points, an end-to-end pipeline run, and the real no-data production state. No persistent database was mutated.

---

## 2. Actual Data Model

**ESTABLISHED**, re-traced from current source this phase (`DatabaseFactory.kt` lines 878-917, and the three repository classes):

```
persona_master_documents (persona_master_documents_persona_core_version_id_unique)
  id: UUID (PK)
  persona_core_version_id: UUID (unique index; no Exposed-level FK declared)
  content_json: TEXT (raw JSON string, parsed/serialized by PersonaMasterRepository)
  schema_version: TEXT (default "1.0")
  created_at: TIMESTAMP

persona_field_registry
  field_id: TEXT (PK)
  path: TEXT (dot-separated; walked by PersonaMasterRepository.resolvePath())
  semantic_type: TEXT?, classification: TEXT, sensitivity: TEXT (default "normal")
  stability: TEXT, behavioral_core: BOOL, default_load: BOOL
  allowed_contexts: TEXT? (comma-joined skill-key list), prohibited_contexts: TEXT?
  description: TEXT?, created_at: TIMESTAMP

skill_context_requirements
  skill_key: TEXT (PK)
  required_fields / recommended_fields / conditional_fields / restricted_fields: TEXT? (comma-joined)
  purpose / when_to_use / when_not_to_use: TEXT?
  updated_at: TIMESTAMP
```

**Data-flow diagram (ESTABLISHED, matches Phase 49's already-implemented pipeline):**

```
PersonaCoreVersions.content  ──────────────────────────────┐  (fallback baseline,
  (real, generic behavioral spec, no facts — see §3)        │   always present)
                                                              ▼
persona_master_documents.content_json ──► PersonaFieldAuthorizer.resolve()
  (structured JSON facts, dot-path addressable)                │
persona_field_registry ─────────────────────────────────────►  │ SELECT → AUTHORIZE → RENDER
  (which fields exist, sensitivity, allowedContexts)            │
skill_context_requirements ─────────────────────────────────►  │ (real selected skill key)
  (which fields a skill may see)                                ▼
                                                    ProductionPersonaContextService
                                                      .resolvePersonaContent(...)
                                                                 │
                                                                 ▼
                                              PersonaContextResolutionStage
                                          (replaces ONLY the PERSONA ContextBlock,
                                           budget-rechecks, falls back to the
                                           PersonaCoreVersions.content baseline
                                           already sitting in that block)
                                                                 │
                                                                 ▼
                                                            GENERATION
```

**Versioning/effective-version semantics (ESTABLISHED):** `persona_master_documents` keys on `persona_core_version_id`, not `persona_id` — a new published core version requires its own master-document row; there is no "latest applies to all versions" fallback in `PersonaMasterRepository.findByPersonaCoreVersionId()`. This is consistent with the rest of the schema's version-scoped design (transcript reproducibility) but means every future core-version publish for a persona would need a corresponding master-document re-authoring step if Persona Context is to keep working for that version — **UNKNOWN** whether any process currently intends to keep these in lockstep; no such process exists in source today.

**Correction to a Phase 35 assumption, explicitly re-verified this phase, not carried forward unchecked:** Phase 35 stated production tables are empty based on a migration-file `INSERT` count and a repository-call-site grep. This phase did not have direct access to run against a live production database (none is configured in this environment — `DATABASE_URL` is unset, confirmed by the pre-existing `PersonaContextPocRealBenchmark` test's own failure this session), so the *current* live-production emptiness cannot be independently re-confirmed by this phase beyond what Phase 35 already established from source. What **is** newly and independently confirmed this phase: **no code path anywhere in `src/main` writes to these three tables except the research-only `PersonaContextPocSeed.kt`** (re-grepped this phase, §11) — so even if some out-of-band process had populated them, no ordinary application code would notice or rely on it either way. This phase treats "effectively empty / unauthored" as **ESTABLISHED from source structure**, and "literally zero rows in the live database right now" as **UNKNOWN** (no live DB access), which is precisely why Phase 50 works entirely against a deterministic fixture rather than asserting a claim about live production state it cannot verify.

---

## 3. Canonical Source-of-Truth Decision

**ESTABLISHED — the single most important finding of this phase.**

Three candidate artifacts were compared, all re-read from current source this phase:

| Artifact | Content | Re-verified this phase |
|---|---|---|
| `PersonaCoreVersions.content` (real production value, `BaselineConfiguration.SIMRAN_PERSONA_CORE`) | Purely generic: identity/personality/communication-style/boundary *instructions* ("Simran is warm, curious, playful..."). **No name spelled beyond "Simran," no age, no hometown, no occupation, no family, no concrete biography of any kind.** | Read in full, `BaselineConfiguration.kt` lines 218-361. |
| `docs/personas/Simran/simran-master.md` | 1434-line, richly detailed biographical/behavioral document: full name "Simran Kohli," age 27, Nashik→Pune, architect, family (Rajesh/Priya/Anjali/Arjun), friends (Ritu/Prashant/Neha), etc. | Confirmed present (referenced in a prior session's plan-mode audit of this same file); this phase reused its already-vetted facts via `PersonaContextPocSeed.kt`'s transcription as a cross-check, without importing that research code. |
| `PersonaContextPocSeed.buildSimranMasterContent()` | A hand-transcribed JSON subset of `simran-master.md` (~34 of ~150+ leaf fields), used only by the frozen research benchmark harness, in-memory only. | Read in full this phase (`PersonaContextPocSeed.kt` lines 58-306). |

**Decision:** `docs/personas/Simran/simran-master.md` is the canonical source of truth for Persona Context *biographical facts*. `PersonaCoreVersions.content` remains canonical for *behavioral/tone instructions* and, unchanged, as the safe fallback baseline this whole mechanism already falls back to. **These are not duplicate sources of the same information — they never overlapped.** This resolves Phase 50's Part 2 instruction ("determine whether Persona Context should be generated/compiled from PersonaCoreVersions.content") definitively: **no**, because there is nothing in the real production `PersonaCoreVersions.content` to compile facts *from*. A compiler in that direction is not a smaller/cleaner version of what Phase 50 was asked to build — it is not buildable at all against current data, and asserting otherwise would be inventing semantics that do not exist in source (explicitly forbidden by rule 9).

**Practical consequence, disclosed plainly:** today, in real production, Simran cannot state her own age, hometown, or occupation through the Persona Core channel at all — only through accumulated conversation memory. This raises the practical value of eventually authoring `persona_master_documents` data (Phase 45/49's still-open "data-authoring mechanism" decision) higher than previously documented, since it is not a refinement of existing capability but the only path to give Simran any static biographical grounding beyond generic personality traits.

---

## 4. Authoring/Materialization Design

**DESIGN, not implemented as production code this phase** (per rule 13 — a deterministic fixture is preferred over any persistent mutation, and per the STOP CONDITIONS, a materializer that would require inventing schema semantics from a non-existent source was correctly avoided rather than forced):

Because §3 establishes there is no automatic-compile-from-`PersonaCoreVersions.content` path, the smallest maintainable authoring mechanism is:

```
docs/personas/<slug>/<slug>-master.md  (human-authored canonical biography)
        │
        │  (human/editorial transcription step — cannot be automated without
        │   an NLP extraction step this phase has no mandate or evidence to design)
        ▼
A structured JSON document, hand-authored or admin-tool-authored,
matching the SAME field-ID/path conventions PersonaFieldRegistry expects
        │
        ▼
persona_master_documents.content_json  (one row per persona_core_version_id)
persona_field_registry                  (one row per addressable field, authored once,
                                          reused across every persona using the same schema)
skill_context_requirements              (one row per skill, authored once, reused across personas)
```

This phase's own fixture (§5) is a concrete instance of exactly this pattern, built as a deterministic test object rather than an admin tool — Phase 50 was not authorized to build a real admin authoring UI (that is squarely Phase 45/49 item 2, a human decision on the authoring *mechanism*, not just the data). No new production `.kt` file implements this pattern; it exists only as the test fixture in §5, with this section documenting the pattern for a future, explicitly-authorized authoring-tool phase.

---

## 5. One-Persona Fixture

**IMPLEMENTED / TESTED.** Persona: **Simran** (`BaselineConfiguration.SIMRAN_SLUG`) — the real, existing, already-production-seeded persona. No new persona was invented.

File: `src/test/kotlin/com/pinkdreams/chat/personacontext/production/Phase50ProductionPersonaDataValidationTest.kt`. The fixture's `Fixture` class:
- Creates a persona with the real slug/display name.
- Sets `PersonaCoreVersions.content` to the **real** `BaselineConfiguration.SIMRAN_PERSONA_CORE` text (not a placeholder string) — so every fallback assertion in this phase's tests proves the fallback is the actual production text, not a stand-in.
- `simranMasterContent()` builds a nested JSON document (identity.name/age/hometown/current_city, life_context.occupation, personality.core_traits, relational.romantic_style, plus two restricted top-level fields `intimate_anatomy`/`adult_intimacy`) — every value is a real fact already cross-checked against `simran-master.md` via `PersonaContextPocSeed.kt`'s own vetted transcription (§3), rewritten independently in this new file rather than imported, per the rule against reusing research code for production-adjacent work.
- Covers: normal identity fields, a life-context field, a personality field, a skill-relevant relational field, and both restricted fields required by Part 4 of the instruction.

---

## 6. Skill Mapping Validation

**ESTABLISHED / TESTED.** Test `real production skill keys used in this fixture actually exist in BaselineConfiguration SKILLS` asserts directly against `BaselineConfiguration.SKILLS` (26 real seeded skills, re-read this phase, `BaselineConfiguration.kt` lines 514-1194+):

- **Used, real:** `general_chat`, `friendship`, `romantic_intimacy`, `foreplay`, `advice`.
- **Confirmed NOT real** (asserted false, not merely undocumented): `about`, `perspective_of` — these were research-only convenience labels in `PersonaContextPocSeed.kt`, never seeded as production skills. This finding is new this phase; no prior phase's report flagged it, because no prior phase cross-checked the research seed's skill keys against `BaselineConfiguration.SKILLS`.
- **`advice`** (a real, verified production skill key) is deliberately left with no Persona Context mapping in this fixture, exercising Phase 50 Part 5/6's "known skill, no mapping" case without fabricating one — test `E`.

---

## 7. Restricted-Field Matrix

**TESTED.** All 10 cases (A-J), against the real Simran fixture:

| Case | Result | Status returned |
|---|---|---|
| A. authorized normal field | Included, rendered | `USABLE` |
| B. unauthorized normal field (not in skill's mapping) | Absent, no error | `USABLE` (other fields present) |
| C. restricted field | Absent for `general_chat`; present for `romantic_intimacy` | `USABLE` / `USABLE` |
| D. unknown skill key | No content | `SKILL_MAPPING_MISSING` |
| E. known (real) skill, no mapping (`advice`) | No content, no fabricated mapping | `SKILL_MAPPING_MISSING` |
| F. empty registry | No content | `REGISTRY_EMPTY` |
| G. empty persona document | No content | `DOCUMENT_EMPTY` |
| H. missing persona document | No content | `DOCUMENT_NOT_FOUND` |
| I. facade-level exception/no-data path | Falls back to the **real** `SIMRAN_PERSONA_CORE` text, never throws | (fallback, not authorizer status) |
| J. mixed normal + restricted | Only authorized subset included, restricted-but-unauthorized never leaks | `USABLE` |

**Invariant confirmed for every case:** no `NOT_USABLE` (any non-`USABLE` status) and no unauthorized field ever produced text reaching a block — re-verified structurally (§ Phase 46's own guarantee, re-exercised here against real data rather than synthetic fixtures for the first time).

---

## 8. Rendering-Policy Validation

**TESTED — explicitly NOT a product decision.** One test proves, against the real `identity.age` field (27, from `simran-master.md`), that swapping `BareScalarRendering` → `AgeOnlyLabeledScalarRendering` changes only `renderedText` ("27" vs. "Age: 27"), while `includedFieldIds` and `status` remain identical. This reuses the already-implemented, already-injectable `ScalarRenderingPolicy` mechanism (Phase 45/46) unmodified — no new rendering code was written this phase, and no policy was selected as the production default beyond the existing, already-disclosed `BareScalarRendering` technical default at the `ChatEngineFactory` construction site (unchanged this phase).

---

## 9. Budget Validation

**TESTED**, three points, against the real content sizes (the actual `SIMRAN_PERSONA_CORE` baseline text, ~2KB, and the real authorized field set):

1. **Comfortably under budget** (100,000-token budget): the real authorized replacement installs correctly.
2. **Exceeding budget** (`tokenBudget = 1`): the replacement is rejected outright and the **real, untrimmed** baseline text is kept — confirmed no partial/truncated persona content is ever produced (Phase 49's own invariant, re-exercised against real-sized content rather than short synthetic strings for the first time, which matters because truncation bugs often only appear at realistic content sizes).
3. **At the exact boundary** of the pre-resolution total: two identical calls produce identical results, confirming determinism at the boundary rather than assuming it.

Nothing is silently truncated in a way that changes meaning — the mechanism's only two behaviors remain "install the full authorized replacement" or "keep the full baseline," never a partial mix, matching Phase 49 §13's design exactly.

---

## 10. End-to-End Pipeline Validation

**TESTED.** One test runs the real `PipelineChatEngine` (real `RepositoryContextAssembler`, real `PersonaContextResolutionStage`, a deterministic fixed-key `IntentDiscovery` returning the real skill `friendship` — no model call) against the real Simran fixture, and inspects the actual captured `GenerationRequest`:

- Exactly one `ENGINE` block, exactly one `PERSONA` block (re-confirms Phase 49's block-splitting produced no duplication).
- Correct ordering: ENGINE first, PERSONA second (matches `RepositoryContextAssembler`'s emission order, unchanged).
- The real authorized field (`"Simran Kohli"`) reaches the PERSONA block.
- The restricted field (`RESTRICTED_INTIMACY_DETAIL`) does **not** appear anywhere, correctly, since `friendship` is not in its `allowedContexts`.
- No raw-document marker (e.g., a hypothetical `_full_document_supplement`-style key) reaches any block — confirmed by an explicit negative assertion, not merely by absence of a positive one.
- `GenerationRequest.skillKey == "friendship"` and `.personaId` match the real request exactly.

---

## 11. Empty-Data (No-Data) Production State

**TESTED — the most safety-critical case, run explicitly rather than only inferred from Phase 49's unit tests.** With zero `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` rows (reproducing today's real production state per §2's disclosed caveat), against the real `SIMRAN_PERSONA_CORE` baseline text:

- The system does not crash.
- The PERSONA block is exactly `"PERSONA CORE:\n" + BaselineConfiguration.SIMRAN_PERSONA_CORE`, byte-for-byte — the real production text, unaltered.
- The block is confirmed non-blank (explicit assertion, not merely "didn't throw").
- No raw fallback content beyond the intended baseline is exposed; ENGINE content is untouched.

This directly answers Phase 49 §19's flagged risk ("this phase's activation is unexercised by real traffic") with the closest available substitute: exercising the exact real production text through the exact real no-data condition, deterministically.

---

## 12. Architecture Optimization Findings

**DERIVED — documented, not implemented, per Part 11's explicit "do not implement a large refactor without explicit evidence."**

1. **`requestedVariant`/`returnedVariant` on `PersonaContextResult`** (`PersonaContextResult.kt` lines 8-17, 30-31): still a hardcoded, always-equal `"SELECTIVE"`/`"SELECTIVE"` pair, exactly as Phase 46 disclosed and Phase 45 already argued was structurally unnecessary. Now that backward compatibility is explicitly not required, these two fields are a legitimate removal candidate. **Not removed this phase** — doing so would touch a public data class referenced by Phase 46's own test M and would be exactly the kind of "large refactor without explicit evidence beyond a documented opinion" Part 11 warns against; flagged here for an explicitly-scoped future phase instead.
2. **`RepositoryContextAssembler`'s `personaContextProvider`/`pinnedSkillKey` POC hook** (`RepositoryContextAssembler.kt` lines 39-45, still present, unchanged this phase): this was the original, CONTEXT_ASSEMBLY-time approach to skill-aware persona resolution that Phase 44/45 explicitly evaluated and rejected in favor of Strategy D's post-skill resolution. It is not "dormant" in the sense of being dead code — the Phase 6-30 research benchmark harness still actively depends on it — but it is now **architecturally superseded** for any future production capability: new work should extend `PersonaContextResolutionStage`, never this hook. Documented as a boundary, not removed (removing it would break the still-active, must-not-modify research benchmark).
3. **No duplicate provider abstractions found** in the production namespace (`com.pinkdreams.chat.personacontext.production`) — `PersonaFieldAuthorizer`, `ProductionPersonaContextService`, `ProductionPersonaContextRenderer`, `ScalarRenderingPolicy` each have exactly one implementation path and one clear responsibility; re-verified this phase by listing the package's contents, not merely re-citing Phase 46.
4. **No redundant status values** — `PersonaContextStatus`'s 7 values (Phase 45's decision to drop `PARTIAL`) remain distinct and each is exercised by at least one test in this phase's own matrix (§7).
5. **`PersonaContextResolutionStage`'s old stub assumptions** are fully gone — no residual TODO/stub comments remain in the file (confirmed by reading the current file in full during Phase 49 and re-confirmed by this phase's tests exercising every documented path).

**Conclusion:** the architecture is already close to minimal for its current scope; the one real, evidenced simplification opportunity (item 1) is deliberately left as a documented recommendation, not an in-phase edit.

---

## 13. Persistent-Data Mutation Plan (Not Executed)

**No persistent database mutation was performed this phase**, consistent with rule 13's preference for a deterministic fixture. The following table states precisely what *would* be inserted if a future, explicitly-authorized phase chooses to populate the real database for Simran, using exactly this phase's already-vetted fixture data:

| Table | Row purpose | Source | Required fields | Foreign keys | Restricted? |
|---|---|---|---|---|---|
| `persona_master_documents` | One row: Simran's structured biographical facts for her currently-active core version | `docs/personas/Simran/simran-master.md`, via the JSON shape in §5 | `id`, `persona_core_version_id`, `content_json` | `persona_core_version_id` → `persona_core_versions.id` (no Exposed-level FK declared; enforced only by application logic today — see §2) | No (document itself is not restricted; individual fields within it are) |
| `persona_field_registry` | ~9 rows (this phase's subset) or ~34+ (full research-seed parity) — one per addressable field | Field IDs/paths cross-checked against `simran-master.md`; sensitivity/allowedContexts per §5/§7 | `field_id` (PK), `path`, `classification`, `sensitivity`, `stability` | None (global, not persona-scoped — reused across any future persona sharing the same schema) | 2 of the rows (`intimate_anatomy`, `adult_intimacy`) are `sensitivity="restricted"` |
| `skill_context_requirements` | 3-5 rows (this phase's subset: `general_chat`, `friendship`, `romantic_intimacy`, optionally `foreplay`) | Real skill keys from `BaselineConfiguration.SKILLS`; field mappings per §5/§6 | `skill_key` (PK), at least one of required/recommended/conditional | None (global) | `romantic_intimacy`'s row explicitly lists `intimate_anatomy`/`adult_intimacy` in `restrictedFields` |

**Regeneration/deletion:** every row above is fully regenerable from `simran-master.md` plus the field/skill design already validated in §5-§7 — nothing here is a one-off manual artifact; deleting and re-running the same authoring step (once one exists, per §4) reproduces it exactly. **This table is a plan, not an action** — no `INSERT` was executed against any persistent database this phase.

---

## 14. Established vs. Unknown

**ESTABLISHED** (re-verified from source this phase): the full data model and its relationships (§2); that `SIMRAN_PERSONA_CORE` contains no biographical facts (§3); that `about`/`perspective_of` are not real skill keys (§6); that no production code path writes to the three Persona Context tables outside the research seed (§2, §11); all 17 new test results (§7-§11).

**UNKNOWN**, explicitly, per rule 10 (not guessed):
- Whether the live production database (if one currently exists and is running anywhere) literally has zero rows in these three tables right now — no live DB access in this environment; only source-structure evidence is available (§2).
- Whether an Exposed-level or raw-SQL-level foreign key actually constrains `persona_master_documents.persona_core_version_id` — Exposed's table definition declares none; the underlying Flyway/raw-SQL migration was not re-read this phase to confirm or deny a database-level constraint beyond the ORM layer.
- Whether any future core-version publish workflow is expected to re-author `persona_master_documents` automatically — no such workflow exists in source today; this is a process question, not a code question.

---

## 15. Remaining Blockers

Unchanged in kind from Phase 45/49 — none newly created, none resolved, by this phase's read-only-against-a-fixture work:

1. **HUMAN DECISION REQUIRED** — whether and how to build a real authoring mechanism/admin tool (§4) to move from `docs/personas/Simran/simran-master.md` into real `persona_master_documents` rows for actual production use.
2. **HUMAN DECISION REQUIRED** — final rendering-policy choice (unchanged, still explicitly not decided by this phase).
3. **HUMAN DECISION REQUIRED** — whether the §12 item 1 simplification (`requestedVariant`/`returnedVariant` removal) should be scheduled as its own small phase.

---

## 16. Exact Next Step

The next action is a human decision on §15 item 1: whether to author real `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` rows for Simran in an actual (non-fixture) database, and by what mechanism (manual admin-authored JSON vs. a future structured-authoring tool). This phase's fixture (§5) and validated field/skill mapping (§6-§7) are ready to be used as the literal content of that authoring step the moment it is authorized — no further design work is required first, only the decision itself and, separately, the database-mutation authorization rule 12 requires before any real `INSERT` is run.

---

## Final Response

- **Files created:** `Phase50ProductionPersonaDataValidationTest.kt` (17 tests), this document.
- **Files modified:** none.
- **Files deleted:** none.
- **Tests run and results:** 17/17 new tests passing (`Phase50ProductionPersonaDataValidationTest`); full suite re-run this phase shows the same pre-existing 33 environment-only failures (`OPENROUTER_API_KEY`/`DATABASE_URL` unset, frozen research files) and zero new regressions — see the accompanying test-run output for the exact count.
- **API calls:** zero.
- **DB mutations:** zero — every table interaction in this phase used a fresh in-memory H2 instance (`DatabaseFactory.connectInMemory()`), never a persistent/production database.
- **What was proven:** the already-wired Phase 49 mechanism behaves correctly against real (not synthetic) persona facts and real production skill keys, across the full restricted-field matrix, at realistic content sizes, end-to-end through the real pipeline, and in the real no-data state — with two new, previously-undocumented findings (§3, §6) that materially inform the next human decision.
- **What remains unknown:** literal current row-count in any live production database (no access); DB-level FK enforcement beyond the ORM layer; any future core-version/master-document synchronization process.
- **Whether Phase 51 is justified:** only once the §15/§16 human decisions are made — this phase does not recommend or assume a Phase 51 scope.

Phase 50 stops here. Phase 51 is not started.
