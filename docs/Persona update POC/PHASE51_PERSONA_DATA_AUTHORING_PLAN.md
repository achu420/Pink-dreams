# Phase 51 — Production Persona Data Authoring & Activation Plan

**Objective:** Determine exactly how Simran's real persona data should be authored into the production Persona Context system, and make the system ready for that data without guessing.

**Status:** Design phase only. No API calls, no DB mutations, no production changes.

---

## 1. Complete Production Data Model (Traced)

**ESTABLISHED from Phases 49-50 source review:**

```
Authoritative canonical source:
  docs/personas/Simran/simran-master.md
         ↓ (human transcription/authoring step)
  
Production tables (PostgreSQL):
  ├─ persona_master_documents
  │    id: UUID
  │    persona_core_version_id: UUID (FK, uniq index)
  │    content_json: TEXT (raw JSON string)
  │    schema_version: TEXT
  │    created_at: TIMESTAMP
  │
  ├─ persona_field_registry
  │    field_id: TEXT (PK)
  │    path: TEXT (dot-path for JSON walks)
  │    classification: TEXT
  │    sensitivity: TEXT ("normal" | "restricted")
  │    stability: TEXT
  │    behavioral_core: BOOL
  │    default_load: BOOL
  │    allowed_contexts: TEXT (comma-joined skill keys)
  │    prohibited_contexts: TEXT
  │    created_at: TIMESTAMP
  │
  └─ skill_context_requirements
       skill_key: TEXT (PK)
       required_fields: TEXT (comma-joined field IDs)
       recommended_fields: TEXT
       conditional_fields: TEXT
       restricted_fields: TEXT
       purpose: TEXT
       when_to_use: TEXT
       when_not_to_use: TEXT
       updated_at: TIMESTAMP

Production services:
  persona_master_documents.content_json
    ↓ (PersonaFieldAuthorizer.resolve)
    ├─ SELECT by path (dot-walk via PersonaMasterRepository.resolvePath)
    ├─ AUTHORIZE by field_id, skill_key, allowed_contexts
    └─ RENDER by ScalarRenderingPolicy
    ↓
  PersonaContextService.resolvePersonaContent
    ↓ (PersonaContextResolutionStage)
    ├─ Replace PERSONA ContextBlock only
    ├─ Re-check token budget
    └─ Fall back to PersonaCoreVersions.content if invalid
    ↓
  LlmClient generation boundary
```

**Critical invariants (ESTABLISHED from Phase 50 tests):**

1. No `persona_master_documents` row → safe fallback to `PersonaCoreVersions.content`
2. Empty field registry → no fields resolved, fallback applies
3. Restricted fields only resolve if skill_key is in allowed_contexts AND field is required/recommended/conditional for that skill
4. Zero API calls during resolution — purely deterministic database lookups + string operations
5. Budget re-check after replacement — if oversized, keep original baseline unchanged
6. Rendering policy injectable — different policies change only representation, not field inclusion

---

## 2. Minimum Complete Production Dataset (Separation A/B/C)

**A. MINIMUM DATASET FOR PIPELINE FUNCTIONALITY**

For the PersonaContextResolutionStage to function at all (not crash, return USABLE):

- `persona_master_documents` row with: id, persona_core_version_id (FK to actual version), content_json (valid JSON), schema_version
- `persona_field_registry` rows: at least 1 field_id/path/classification/sensitivity
- `skill_context_requirements` rows: at least 1 skill_key/required_fields (can be empty list or null)

**Minimum viable example:**
- One field: `identity.name` → "Simran Kohli"
- One skill: `general_chat` → requires [`identity.name`]
- No restricted fields
- Budget: realistic (~1-2KB of content)

Result: PersonaContextResolutionStage will return `USABLE` with one field, NOT crash.

**B. MINIMUM DATASET FOR A USEFUL SIMRAN PERSONA**

For users to have a meaningful conversation with a grounded persona:

Required:
- Identity core: name, age, location (current + hometown), occupation
- Personality core: core traits, communication style, behavioral default
- At least one relational context: romantic style (for romantic skills) OR friendly approach (for friendship)
- Narrative hints: key life events, aspirations, current situation

Optional but strongly recommended:
- Family / important relationships (adds depth to conversational continuity)
- Worldview / values (enables perspective_of / opinion skills)
- Preferences (hobbies, interests)
- Decision patterns (helps consistency)

This maps to roughly 12-18 fields covering identity/life_context/personality/relational/aspirations.

**C. OPTIONAL FUTURE DATA**

- Restricted fields (`intimate_anatomy`, `adult_intimacy`) — safe to leave unmapped until needed
- Behavioral sub-fields (personality strengths/weaknesses, communication quirks)
- Timeline/narrative state (detailed life story)
- Public expression / social media persona
- Extended preferences (foods, music, etc.)

**Rationale:** Phases 49-50 proved the system works correctly with zero data (safe fallback). Minimum B (12-18 fields) provides useful grounding without over-committing maintenance burden. Minimum A is just proof-of-concept.

---

## 3. Authoring Mechanism Options (Design Analysis)

**OPTION A: SQL Migration + Seed File**

Mechanism:
```
docs/personas/Simran/simran-master.md
  ↓ (manual transcription)
src/main/kotlin/com/pinkdreams/baseline/PersonaContextPocSeed.kt
  (already exists, proven in Phases 49-50)
  ↓ (Flyway migration V###_seed_simran_persona_context.sql)
database: persona_master_documents / persona_field_registry / skill_context_requirements
```

**Pros:**
- Works exactly like existing seed infrastructure (proven)
- Version-controlled, auditable
- Can be run repeatedly (idempotent if designed correctly)
- No runtime dependencies

**Cons:**
- Manual transcription (error-prone, maintenance burden)
- SQL vs. Kotlin inconsistency (two sources of truth)
- Hard to keep in sync with simran-master.md changes
- Doesn't scale to multiple personas

**Suitability:** Low-to-medium for a single persona POC; not suitable for production if other personas follow.

**Developer effort:** 2-4 hours (transcription + SQL + validation)

---

**OPTION B: Admin API**

Mechanism:
```
Manual JSON POST to /admin/personas/{personaId}/master-document
  + separate POSTs for field registry / skill mappings
```

**Pros:**
- Runtime authoring (no deploy needed)
- Can update without migration
- Enables future UI tooling

**Cons:**
- No version control of the data itself
- Requires auth/authorization layer
- Easy to make mistakes without validation
- No audit trail of who/when changed what
- Requires designing and implementing the API

**Suitability:** Medium for single persona; enables future tooling but adds API surface.

**Developer effort:** 6-10 hours (API design + implementation + validation endpoint)

---

**OPTION C: Importer from simran-master.md**

Mechanism:
```
docs/personas/Simran/simran-master.md
  ↓ (structured NLP extraction or hand-authored JSON block)
import script (reads markdown or JSON, validates, loads to DB)
  ↓
database
```

**Pros:**
- Single source of truth (the .md file)
- Can re-run on changes
- Closer to natural authoring format
- Scalable to multiple personas

**Cons:**
- Requires designing the extraction mechanism (NLP or structured block)
- Validation/error handling complexity
- Still a one-time authoring effort per persona
- Depends on markdown remaining well-formed

**Suitability:** High for long-term, multiple personas; requires upfront design.

**Developer effort:** 8-15 hours (extraction design + validation + test + documentation)

---

**OPTION D: Hybrid (Recommended)**

Phase 51 design point:

```
Step 1: Build PHASE51_SIMRAN_SOURCE_TO_PRODUCTION_MAPPING.md
        (human-authored, validated mapping from .md → fields)

Step 2: Use existing PersonaContextPocSeed.kt as the seed mechanism
        (proven, works, known cost)
        ↓
Step 3: Auto-generate Flyway migration from the mapping
        (deterministic, auditable, version-controlled)

Step 4: Design the importer for future personas
        (separate from Phase 51; can be Phase 52/53)
```

**Rationale:** Phase 51 can finish with a working seed using existing, tested infrastructure (PersonaContextPocSeed.kt). Documenting the mapping explicitly separates "what data exists" from "how to load it," allowing future work to build an importer without re-discovering the data structure.

**Developer effort for Phase 51:** 2-4 hours (mapping + Flyway generation, reuses existing seed)

---

## 4. Import/Validation Contract (Fail-Closed Design)

If an importer is built (Phase 51 or later), it must validate:

```
PRE-IMPORT VALIDATION:
  ✓ Unknown field_id not in persona_field_registry → REJECT
  ✓ Unknown skill_key not in BaselineConfiguration.SKILLS → REJECT
  ✓ Restricted field in skill mapping but not allowed_contexts → REJECT
  ✓ Malformed JSON → REJECT
  ✓ Duplicate field_id in input → REJECT
  ✓ Missing required identity fields → REJECT
    (identity.name, identity.age, identity.current_city at minimum)
  ✓ Field path inconsistent with registry path → REJECT
  
DURING-IMPORT SAFETY:
  ✓ All writes in single transaction → all-or-nothing (partial writes impossible)
  ✓ Foreign key checks enforced
  ✓ Idempotency key: (personaId, versionId) → re-import same version is safe
  
POST-IMPORT VALIDATION:
  ✓ Every skill_key in skill_context_requirements has non-null skillKey
  ✓ Every field_id in field registry has valid path
  ✓ No orphaned field_ids (referenced but not in registry)
  ✓ PersonaContextResolutionStage.resolve() on test persona returns USABLE for at least one skill
```

**Rationale:** Validation happens BEFORE any writes. Errors are reported with exact field/skill/reason. Importer can be safely re-run (idempotent). No partially-loaded data persists.

---

## 5. Restricted-Field Model (Verified Architecture)

**intimate_anatomy, adult_intimacy (ESTABLISHED from Phase 50)**

```
persona_field_registry rows:
  ├─ intimate_anatomy
  │    sensitivity: "restricted"
  │    allowed_contexts: ["romantic_intimacy", "foreplay"]
  │    
  └─ adult_intimacy
       sensitivity: "restricted"
       allowed_contexts: ["romantic_intimacy", "foreplay"]

skill_context_requirements:
  ├─ romantic_intimacy
  │    requiredFields: [..., "adult_intimacy"]
  │    restrictedFields: ["adult_intimacy", "intimate_anatomy"]
  │    
  └─ foreplay
       requiredFields: [..., "adult_intimacy"]
       restrictedFields: ["adult_intimacy", "intimate_anatomy"]

Authorization path (PersonaFieldAuthorizer.resolve):
  1. Check skill_key against field's allowed_contexts
     → if skill not in allowed_contexts: REJECT
  2. Check if field is required/recommended/conditional for this skill
     → if not: REJECT
  3. Check sensitivity:
     → if "restricted" and skill not in allowed_contexts: REJECT (double-check)
  4. Render and include in result

Security property: No unauthorized skill can EVER receive these fields.
Verified by: Phase 50 tests (C, J).
```

**Other sensitive fields (DERIVED from simran-master.md):**

None currently mapped as restricted. The fixture treats `intimate_anatomy` and `adult_intimacy` as the only restricted fields — confirmed by explicit test coverage in Phase 50 (C, J, V).

---

## 6. Versioning Model (ESTABLISHED)

**What identifies a version:**

```
persona_core_version_id (UUID)
  ↓ identifies one specific version of PersonaCoreVersions
  ↓ 1:1 relationship to one persona_master_documents row
  
If Simran's PersonaCoreVersions.content changes (e.g., updated SIMRAN_PERSONA_CORE prompt):
  1. New PersonaCoreVersions row is created with new id
  2. New persona_master_documents row must be created for that version_id
  3. Old master_documents row still exists (transcript reproducibility)
  
field_registry rows (NOT versioned):
  → Global, shared across all personas
  → Updated in place (UPDATE, not INSERT new row)
  
skill_context_requirements rows (NOT versioned):
  → Global, shared across all personas
  → Updated in place
```

**Implication:** If Simran's core version changes later, the new version's master_document must be authored separately. No automatic "reuse old document for new version" logic exists — this is deliberate (ensures intentional review of each version).

**What happens if not done:** New version uses old baseline `PersonaCoreVersions.content` (safe fallback), no Persona Context resolution (no master_document row).

---

## 7. Validation Tests (Zero API Calls, Deterministic)

**Test matrix (to be implemented after authoring, per Part 9 instruction):**

```
A. No-data baseline
   → load no master_document
   → call PersonaContextResolutionStage.resolve()
   → expect: USABLE, content = SIMRAN_PERSONA_CORE baseline, no crash

B. General chat (normal fields)
   → load master_document with identity/occupation/personality
   → skill = general_chat
   → expect: USABLE, includes name/occupation/traits, no restricted fields

C. Friendship (relational fields)
   → skill = friendship
   → expect: USABLE, includes romantic_style (authorized for friendship)

D. Advice (unmapped skill)
   → skill = advice (no mapping exists)
   → expect: SKILL_MAPPING_MISSING, fallback applies

E. Romantic intimacy (restricted allowed)
   → skill = romantic_intimacy
   → expect: USABLE, includes adult_intimacy (authorized)

F. Foreplay (restricted allowed)
   → skill = foreplay
   → expect: USABLE, includes adult_intimacy (authorized)

G. General chat vs intimate (restricted blocked)
   → skill = general_chat
   → expect: USABLE but adult_intimacy/intimate_anatomy NOT included

H. Unknown skill key
   → skill = "totally_unknown"
   → expect: SKILL_MAPPING_MISSING, fallback

I. Unknown field ID
   → registry missing a field the mapping references
   → expect: REGISTRY_EMPTY or field not included

J. Malformed JSON
   → content_json is invalid JSON
   → expect: exception handled by PersonaContextResolutionStage's try/catch, fallback

K. Repeated import
   → load same master_document twice
   → expect: idempotent, no duplicates, same result

L. Changed persona version
   → load master_document for version A
   → call PersonaContextResolutionStage with version B
   → expect: DOCUMENT_NOT_FOUND, fallback (documented mismatch)

M. Budget overflow
   → load master_document with very large JSON
   → PersonaContextResolutionStage with tokenBudget = 1
   → expect: replacement rejected, baseline kept unchanged

N. Rendering policy substitution
   → load master_document
   → swap BareScalarRendering ↔ AgeOnlyLabeledScalarRendering
   → expect: included fields same, renderedText differs

Each test:
  - Uses in-memory H2 database
  - Calls PersonaContextResolutionStage.resolve directly (no HTTP, no LLM)
  - Asserts exact expected status/content
  - Documents the invariant being tested
```

---

## 8. Phase 50 Fixture as Authoritative Seed (Analysis)

**Can the Phase50ProductionPersonaDataValidationTest.kt Fixture become the production seed?**

**YES, with caveats:**

✓ Fixture data is sourced from simran-master.md (traced in Phase 50)
✓ Fixture covers minimum dataset B (12-18 fields, identity/personality/relational/aspirations)
✓ Fixture skill mappings are real production skills (verified Phase 50 §6)
✓ Fixture restricted-field model is correct (tested Phase 50 §7, J, V)
✓ Fixture passes all Phase 50 validation tests (17/17 passing)

**What must change before using as seed:**

1. Fixture is standalone test code, not shared with production bootstrap
   → Extract simranMasterContent() into a shareable location (e.g., PersonaContextSeeds.kt or simran-seed.json)
   
2. Field registry entries are hardcoded in test Fixture class
   → Extract into a data file or seed migration
   
3. Skill mappings are in test code
   → Extract to seed migration or shared data
   
4. No explicit documentation of which fields are "minimum viable" vs. "optional"
   → Phase51_SIMRAN_SOURCE_TO_PRODUCTION_MAPPING.md will clarify this

5. Fixture uses nested JSON (identity.name, etc.)
   → Must match schema's dot-path expectations (PersonaMasterRepository.resolvePath)
   → Verify path traversal works correctly (existing code tested in Phase 50, but document explicitly)

**Recommendation:** Use the Phase 50 fixture as the seed DATA SOURCE, but extract it into a separate PersonaContextSeeds.kt or seed migration before production load. Do not use test code as the source-of-truth for production data.

---

## 9. Activation Checklist (Sequential Steps)

```
PRE-AUTHORING (Phase 51 complete)
  ☐ PHASE51_SIMRAN_SOURCE_TO_PRODUCTION_MAPPING.md finalized and reviewed
  ☐ PHASE51_DATA_VALIDATION_MATRIX.md tests designed and reviewed
  ☐ No missing required identity fields (name, age, current_city, occupation minimum)
  ☐ All skill mappings against real BaselineConfiguration.SKILLS keys
  ☐ Restricted fields (intimate_anatomy, adult_intimacy) documented and authorized
  ☐ Budget estimate: persona_master_documents.content_json size acceptable
  ☐ **HUMAN DECISION: Approve authoring mechanism (option D: use PersonaContextPocSeed + Flyway)**
  ☐ **HUMAN DECISION: Approve minimum dataset scope (option B: 12-18 fields)**

AUTHOR (Phase 51 output)
  ☐ Extract Phase 50 fixture into PersonaContextSeeds.kt
  ☐ Generate Flyway migration V###_seed_simran_persona_context.sql
  ☐ Populate: persona_master_documents row
  ☐ Populate: persona_field_registry rows (field_id, path, classification, sensitivity, allowed_contexts)
  ☐ Populate: skill_context_requirements rows (general_chat, friendship, romantic_intimacy, foreplay)
  ☐ Add versioning notes (links persona_core_version_id to published core)

VALIDATE (Phase 51 continues)
  ☐ Run all tests from PHASE51_DATA_VALIDATION_MATRIX (A-N)
  ☐ Verify: PersonaContextResolutionStage returns USABLE for general_chat
  ☐ Verify: No restricted fields leak to unauthorized skills
  ☐ Verify: Budget check passes (total tokens < DEFAULT_TOKEN_BUDGET)
  ☐ Verify: Rendering policies produce different output (same fields, different representation)
  ☐ Verify: No-data baseline still works (fallback still returns SIMRAN_PERSONA_CORE)

DRY RUN (against non-prod environment if available)
  ☐ Deploy Flyway migration to test database
  ☐ Run full test suite including PHASE51 validation tests
  ☐ Run one live conversation (real PipelineChatEngine, deterministic IntentDiscovery) for each skill
  ☐ Capture: actual resolved content, tokens used, latency
  ☐ Verify: budget, no crashes, no unauthorized content

COMMIT (Phase 51 complete)
  ☐ Commit PersonaContextSeeds.kt + Flyway migration to main branch
  ☐ All PHASE51_DATA_VALIDATION_MATRIX tests passing
  ☐ No code changes to production pipeline (only seed data)

POST-LOAD VALIDATION (Phase 52, after production deployment)
  ☐ Confirm: master_document row exists with correct persona_core_version_id
  ☐ Confirm: field_registry rows match PHASE51 mapping
  ☐ Confirm: skill_context_requirements rows exist for 4+ skills
  ☐ Confirm: PersonaContextResolutionStage resolves real Simran facts (no API calls)
  ☐ Spot-check: one live conversation per skill (general_chat, friendship, romantic_intimacy, foreplay)
  ☐ Monitor: no DOCUMENT_NOT_FOUND, REGISTRY_EMPTY, or SKILL_MAPPING_MISSING in production logs

ACTIVATION (Phase 52+, human decision)
  ☐ **HUMAN DECISION: Is production data load confirmed safe?**
  ☐ Enable: Simran's Persona Context resolution in production (already wired Phase 49, just need data)
  ☐ No code changes needed — data is all that's required
  ☐ Fallback still works if data is deleted (safe to remove)
```

---

## 10. Blocker Analysis: What Could Still Prevent Safe Loading?

**After Phase 51 is complete, the following items MUST be resolved before production load:**

1. **HUMAN DECISION: Is the authoring mechanism approved?**
   → Option D (Flyway seed using extracted fixture) is recommended
   → Alternative options (API, importer) defer to Phase 52+

2. **HUMAN DECISION: Is the minimum dataset scope (12-18 fields) approved?**
   → Affects field_registry size, maintenance burden
   → Can be expanded later without breaking existing

3. **HUMAN DECISION: Are the restricted fields (intimate_anatomy, adult_intimacy) approved for production?**
   → Currently mapped only to romantic_intimacy + foreplay
   → Must confirm business/product alignment

4. **HUMAN DECISION: Is the versioning model acceptable?**
   → Current model: each core version needs own master_document
   → Alternative: auto-reuse old master for new version (NOT implemented, would require code change)

5. **Database access for production load**
   → Phase 51 uses no DB mutations (design only)
   → Phase 52 will need write access to production or test database
   → Must confirm credentials, deployment path

6. **Backward compatibility with no-data state**
   → Current architecture falls back to SIMRAN_PERSONA_CORE baseline if no master_document
   → Loading data should NOT break this fallback
   → Confirmed by Phase 50 tests (no changes needed)

7. **Token budget confirmation**
   → Phase 50 validated ~2KB content fits within DEFAULT_TOKEN_BUDGET
   → If expanding to full fixture, must re-check budget
   → No code changes needed if fits

8. **Rendering policy finalization (unchanged from Phase 49)**
   → BareScalarRendering is technical default, not product decision
   → Phase 51 does not choose; Phase 52+ can choose and update ChatEngineFactory.build()

---

## 11. Phase 51 Exact Next Step

After Phase 51 completes all three deliverables:

```
✓ PHASE51_PERSONA_DATA_AUTHORING_PLAN.md (this document)
✓ PHASE51_SIMRAN_SOURCE_TO_PRODUCTION_MAPPING.md (detailed field-by-field mapping)
✓ PHASE51_DATA_VALIDATION_MATRIX.md (test specifications, ready to implement)
```

**The exact next step is:**

1. **Human review & approval** of all three documents
   - Confirm authoring mechanism choice (Option D: Flyway + extracted fixture)
   - Confirm minimum dataset scope (12-18 fields)
   - Confirm restricted fields are acceptable
   - Confirm versioning model meets requirements

2. **If approved:** Phase 52 implements the authoring and validation
   - Extract Phase 50 fixture into PersonaContextSeeds.kt
   - Generate Flyway migration
   - Implement PHASE51_DATA_VALIDATION_MATRIX tests
   - Run dry-run against test database
   - Commit to main branch

3. **If blocked on a human decision:** Clarify decision, return to review step

**Phase 51 stops here. Phase 52 does not start until all approvals are obtained.**
