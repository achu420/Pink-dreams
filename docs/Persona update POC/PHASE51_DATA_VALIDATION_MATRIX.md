# Phase 51 — Data Validation Matrix

**Purpose:** Deterministic test specifications for validating a real Simran production-data load, before and after authoring. Zero API calls. Ready to implement in Phase 52 as a JUnit test class, following the exact `Fixture`-class pattern already proven in `Phase50ProductionPersonaDataValidationTest.kt`.

**Status:** Specification only — no test code written this phase (Phase 51 is design-only, per the phase instruction's constraint against implementation ahead of the human decisions in `PHASE51_PERSONA_DATA_AUTHORING_PLAN.md` §11).

---

## Test Harness Design (Reused from Phase 50)

Every test below assumes:
- `DatabaseFactory.connectInMemory()` — in-memory H2, never persistent/production
- `PersonaFieldAuthorizer` + `ProductionPersonaContextService` — real production classes, unmodified
- A `Fixture` inner class that seeds exactly the rows each test case needs (not a shared global seed) — isolates each test's precondition explicitly
- Assertions against `PersonaContextResult` (status, renderedText, includedFieldIds) and, where relevant, the full `PersonaContextResolutionStage.resolve()` block-replacement behavior

---

## A. No-Data Baseline

**Precondition:** No `persona_master_documents`, no `persona_field_registry`, no `skill_context_requirements` rows.

**Action:** Call `PersonaContextResolutionStage.resolve()` with a real `ChatContext` containing a PERSONA block set to `BaselineConfiguration.SIMRAN_PERSONA_CORE`.

**Expected:**
- No crash, no exception escapes
- Returned context's PERSONA block content is byte-identical to the input (unchanged)
- Content is non-blank

**Why it matters:** This is today's real production state (per Phase 49/50). Must remain safe indefinitely, independent of whether real data is ever loaded.

---

## B. Normal `general_chat`

**Precondition:** Master document with `identity.name`, `personality.core_traits`, `communication.style`, `life_context.occupation`. Field registry entries for all four. Skill mapping: `general_chat` requires `identity.name`, `personality.core_traits`, `communication.style`; recommends `life_context.occupation`.

**Action:** Resolve for skill key `general_chat`.

**Expected:**
- Status `USABLE`
- `includedFieldIds` contains all 4 fields
- `renderedText` contains "Simran Kohli" and "Architect (junior)"
- No restricted-field content present

**Why it matters:** This is the most common real-world skill; also re-validates the Phase 50-identified defect (occupation missing from `general_chat`) stays fixed.

---

## C. `friendship`

**Precondition:** Same document, field registry additionally includes `behavioral_signature.attachment_style`. Skill mapping: `friendship` requires `identity.name`, `personality.core_traits`, `behavioral_signature.attachment_style`.

**Action:** Resolve for skill key `friendship`.

**Expected:**
- Status `USABLE`
- `includedFieldIds` contains attachment_style
- No restricted-field content
- Fields not relevant to friendship (e.g., `adult_intimacy`) absent even if present in the document

---

## D. `advice`

**Precondition:** Same document. No skill mapping row exists for `advice` (deliberately, per Phase 50 §6 finding — `advice` is a real production skill with no Persona Context mapping).

**Action:** Resolve for skill key `advice`.

**Expected:**
- Status `SKILL_MAPPING_MISSING`
- `renderedText` empty, `includedFieldIds` empty
- `PersonaContextResolutionStage` falls back to the baseline PERSONA block unchanged

**Why it matters:** Confirms the system does NOT fabricate a mapping for a real skill just because it exists in `BaselineConfiguration.SKILLS` — absence of a mapping row is itself the correct, safe signal.

---

## E. `romantic_intimacy`

**Precondition:** Document includes `relational.romantic_style`, `adult_intimacy`, `intimate_anatomy`. Field registry: `adult_intimacy`/`intimate_anatomy` marked `sensitivity="restricted"`, `allowedContexts=["romantic_intimacy","foreplay"]`. Skill mapping: `romantic_intimacy` requires `identity.name`, `relational.romantic_style`, `adult_intimacy`; conditionally includes `intimate_anatomy`.

**Action:** Resolve for skill key `romantic_intimacy`.

**Expected:**
- Status `USABLE`
- `adult_intimacy` content present in `renderedText`
- `intimate_anatomy` present if the relevance scorer includes it (conditional — assert presence only if `includedFieldIds` contains it, not unconditionally)

---

## F. `foreplay`

**Precondition:** Same as E. Skill mapping: `foreplay` requires `identity.name`, `adult_intimacy`; conditionally includes `intimate_anatomy`.

**Action:** Resolve for skill key `foreplay`.

**Expected:** Same shape as E — restricted fields correctly authorized for this second allowed skill.

---

## G. Unauthorized Restricted Field

**Precondition:** Same document/registry as E/F. 

**Action:** Resolve for skill key `general_chat` (NOT in `adult_intimacy`'s `allowedContexts`).

**Expected:**
- Status `USABLE` (other general_chat fields still resolve)
- `includedFieldIds` does NOT contain `adult_intimacy` or `intimate_anatomy`
- `renderedText` does NOT contain any restricted-field content, even substring-checked

**Why it matters:** This is the single most safety-critical test in the entire matrix — direct proof the authorization gate cannot be bypassed by requesting a field via an unauthorized skill.

---

## H. Unknown Skill Key

**Precondition:** Valid document + registry. No mapping for the literal string `"totally_unknown_skill_xyz"`.

**Action:** Resolve for that skill key.

**Expected:**
- Status `SKILL_MAPPING_MISSING`
- Fallback to baseline PERSONA block

**Why it matters:** Distinguishes "unknown to the system" from case D ("known production skill, deliberately unmapped") — both must fail closed, but this proves the unknown case specifically, not just the unmapped-but-real case.

---

## I. Unknown Field ID

**Precondition:** Skill mapping references a field ID (e.g., `life_context.hobbies_typo`) that does NOT exist in `persona_field_registry`.

**Action:** Resolve for that skill.

**Expected:**
- Status is `USABLE` if other valid required fields exist (the missing field is simply skipped), or a defined non-`USABLE` status if ALL required fields are missing — must document which behavior `PersonaFieldAuthorizer` actually implements (re-verify against source in Phase 52, do not assume)
- Under no circumstance does the missing field ID cause an exception or a corrupted render

**Why it matters:** This tests resilience to a typo/drift between skill-mapping data and field-registry data — a realistic operational failure mode once data is hand-authored.

---

## J. Malformed / Partial Data

**Precondition:** `persona_master_documents.content_json` contains syntactically invalid JSON (e.g., truncated string).

**Action:** Attempt to resolve for any skill.

**Expected:**
- `PersonaMasterRepository`'s JSON parse failure is caught (existing `try/catch` in `PersonaMasterRepository.rowToModel`, confirmed present in Phase 50 source review — falls back to `JsonObject(emptyMap())`)
- Resulting empty document leads to `DOCUMENT_EMPTY` status, which the facade treats as non-usable
- `PersonaContextResolutionStage` falls back to baseline, no crash

**Why it matters:** Malformed data is a realistic risk of hand-authored JSON; must degrade safely, never propagate a parse exception to the chat pipeline.

---

## K. Repeated Import (Idempotency)

**Precondition:** N/A at the resolution layer — this is an AUTHORING-mechanism test, not a resolution test. Cannot be fully specified until Phase 52 selects the authoring mechanism (Option D: Flyway + extracted fixture, per `PHASE51_PERSONA_DATA_AUTHORING_PLAN.md` §3).

**Action (once mechanism exists):** Run the import/migration twice against the same target database.

**Expected:**
- Second run either no-ops cleanly (row already exists, detected and skipped) or fails loudly with a clear "already exists" error — NEVER silently creates a duplicate row
- `persona_master_documents.persona_core_version_id` has a unique index (confirmed in Phase 50 §2 schema trace) — a naive re-insert will hit a constraint violation, which is arguably correct fail-closed behavior already, but the migration script itself should handle this gracefully (e.g., `ON CONFLICT DO NOTHING` semantics or an explicit existence check) rather than crashing the deployment

**Status:** Cannot be marked TESTED until Phase 52 defines the actual migration script. Documented here as a required test category, not yet implementable.

---

## L. Changed Persona Version

**Precondition:** Master document exists for `persona_core_version_id = V1`. A new `PersonaCoreVersions` row `V2` is published and activated for Simran, but no corresponding master document exists for `V2`.

**Action:** Resolve using `V2`'s version ID.

**Expected:**
- Status `DOCUMENT_NOT_FOUND` (V2 has no master document row — `V1`'s document does not "carry forward")
- Falls back to `V2`'s own `PersonaCoreVersions.content` baseline (not `V1`'s content, not `V1`'s master document)

**Why it matters:** Directly verifies the versioning model documented in `PHASE51_PERSONA_DATA_AUTHORING_PLAN.md` §6 — confirms there is no accidental cross-version leakage and no silent staleness.

---

## M. Budget Overflow

**Precondition:** Master document with the full ~31-field Phase 50 fixture content (realistic size, ~2KB+). 

**Action:** Resolve with `tokenBudget` set deliberately below the resolved replacement's size but above the baseline's size (a value that would fit the OLD baseline but not the NEW authorized replacement).

**Expected:**
- Replacement is rejected
- Original baseline PERSONA block is kept, complete and untrimmed (no partial/truncated content)
- Deterministic: repeated identical calls produce identical results

**Reused directly from Phase 50 §9 (tests already exist and pass)** — Phase 52 re-runs these against the actual authored dataset rather than the fixture, to catch any size differences between the fixture and the final authored content.

---

## N. Rendering-Policy Substitution

**Precondition:** Master document with at least one scalar field addressed by `AgeOnlyLabeledScalarRendering`'s special-case field ID (`identity.age`, confirmed in Phase 49 source — the policy matches on exact fieldId string `"identity.age"`, not any field literally named "age").

**Action:** Resolve the same request twice, once with `BareScalarRendering`, once with `AgeOnlyLabeledScalarRendering`.

**Expected:**
- `includedFieldIds` identical between both calls
- `status` identical between both calls
- `renderedText` differs only in the age field's representation ("27" vs. "Age: 27")
- No other field's rendering differs

**Reused directly from Phase 50 §8** (test already exists and passes against the fixture) — re-run against final authored data in Phase 52 to confirm the exact field ID used in production matches `"identity.age"` literally (a naming mismatch here would silently make the labeled-rendering test pass while doing nothing in production — worth an explicit assertion, not just an implicit pass).

---

## Test Implementation Readiness

| Test | Ready to implement in Phase 52? | Blocker |
|---|---|---|
| A. No-data baseline | Yes — reuses Phase 50 pattern exactly | None |
| B. general_chat | Yes | Requires final field list from human review of mapping doc |
| C. friendship | Yes | Same |
| D. advice (unmapped) | Yes | None |
| E. romantic_intimacy | Yes | Requires human confirmation restricted-field scope is correct |
| F. foreplay | Yes | Same |
| G. unauthorized restricted | Yes | None — highest priority to implement first |
| H. unknown skill | Yes | None |
| I. unknown field ID | Partially — behavior needs re-verification against current `PersonaFieldAuthorizer` source before asserting a specific expected status | Source re-check in Phase 52 |
| J. malformed data | Yes | None |
| K. repeated import | No | Requires Phase 52's authoring mechanism to exist first |
| L. changed persona version | Yes | None — can build directly, doesn't require real authored data |
| M. budget overflow | Yes | Reuse Phase 50 tests, re-run against final content size |
| N. rendering policy | Yes | Reuse Phase 50 tests, verify exact field ID match |

**11 of 14 test categories are immediately implementable in Phase 52 without further human input. Tests I (needs source re-check) and K (needs the authoring mechanism built first) have explicit, narrow blockers. Test L needs no real data at all and can be built independently.**

---

## Coverage Cross-Check Against Phase 50

Every test in this matrix maps to an existing, passing Phase 50 test OR is a genuinely new case:

- A, B, C, E, F, G, H, J, M, N → direct analogues of Phase 50's tests (A, B/C, C, E/J, F, G, D/H, I, budget tests, rendering test) — Phase 52 should re-run these against the REAL authored dataset, not just the fixture, to catch any divergence
- D, I, K, L → new to Phase 51, not covered by Phase 50's fixture-only scope (D tests a real unmapped skill specifically; I tests data-integrity drift; K tests the authoring mechanism itself; L tests the versioning model explicitly, which Phase 50 did not exercise)

**This matrix is a strict superset of Phase 50's validation, extended to cover authoring-specific and versioning-specific risks that only apply once real (not fixture) data authoring begins.**
