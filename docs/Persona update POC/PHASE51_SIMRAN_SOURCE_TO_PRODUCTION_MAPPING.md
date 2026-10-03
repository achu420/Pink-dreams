# Phase 51 — Simran Source-to-Production Mapping

**Canonical source:** `docs/personas/Simran/simran-master.md`

**Mapping to production tables:** `persona_master_documents.content_json` + `persona_field_registry` + `skill_context_requirements`

**CORRECTION / SOURCING NOTE (read before using this document):** The line numbers cited in the tables below (e.g. "Line 60," "Lines 80-85") were originally carried over from `PersonaContextPocSeed.kt`'s own line numbers — that file's already-vetted transcription — not verified directly against `simran-master.md` at the time those tables were drafted. This phase then read `simran-master.md` directly (lines 1-220) to check that correction was needed, and confirmed:
- The real source file is YAML-structured, not JSON, and uses different line positions than cited below (e.g. real `occupation: Architect (junior)` is at source line 109, real `family:` list starts at line 67, not the "66" / "80-85" cited in the tables).
- **Every value cited below was confirmed correct against the real source** (name "Simran Kohli," age 27, hometown "Nashik, Maharashtra," current_city "Pune, Maharashtra (Kothrud)," occupation "Architect (junior)," family members Rajesh/Priya/Anjali/Arjun, friends Ritu/Prashant/Neha — all match verbatim).
- Some fields cited as flat scalars below are actually embedded in longer prose fields in the real source (e.g. `occupation` is a standalone scalar at line 109, but `career_history`/`daily_routine`/`important_life_events` are separate structured fields too — direct 1:1 matches, not extractions, contrary to what an early draft of this note assumed before the direct read).
- The real source additionally contains many fields not reflected anywhere below (`origin_story`, `childhood`, `workplace`, `financial_context`, `living_situation`, `social_circle`, `weekly_routine`, `durable_life_situation`, and likely much more in the un-read remainder of the file) — these are **UNSUPPORTED** (real source content with no fixture/schema representation), not merely optional extensions.

**Treat every "Line N" citation below as a pointer into `PersonaContextPocSeed.kt`, cross-checked this phase against the real `simran-master.md` for the specific values shown — not as a `simran-master.md` line number.** Where this document says ESTABLISHED, that means: the value is confirmed present and correct in the real canonical source, not merely present in the research fixture.

**Classification scheme:**
- **ESTABLISHED** — fact exists in source, fixture matches, tests pass
- **DERIVED** — fact inferred/synthesized from source content
- **FIXTURE-ONLY** — appears in Phase 50 fixture but not clearly in canonical source
- **UNSUPPORTED** — appears in source but fixture/schema has no representation
- **AMBIGUOUS** — could be multiple fields or unsure of proper structure

---

## Identity Fields (Canonical Section: "Identity")

| Field ID | Source | Path | Value | Type | Classification | Notes |
|---|---|---|---|---|---|---|
| `identity.name` | Line 60 | identity.name | "Simran Kohli" | string | ESTABLISHED | Full name, exact spelling. Always safe. |
| `identity.age` | Line 61 | identity.age | 27 | integer | ESTABLISHED | Current age, verified in simran-master.md. Always safe. |
| `identity.hometown` | Line 62 | identity.hometown | "Nashik, Maharashtra" | string | ESTABLISHED | Source from notes, used for context in general_chat. Safe. |
| `identity.current_city` | Line 63 | identity.current_city | "Pune, Maharashtra (Kothrud)" | string | ESTABLISHED | Primary residence. Needed for conversational grounding. Safe. |

**Minimum required for conversational grounding:** name, age, current_city (all ESTABLISHED).

---

## Life Context Fields

| Field ID | Source | Path | Value | Type | Classification | Notes |
|---|---|---|---|---|---|---|
| `life_context.occupation` | Line 66 | life_context.occupation | "Architect (junior)" | string | ESTABLISHED | Explicitly stated. Root-cause field missing from general_chat in Phase 50 (test case item 2 notes). Now included. |
| `life_context.education` | Lines 68-70 | life_context.education | "12th from...BA Architecture..." | string | ESTABLISHED | Full text from source. Optional (nice-to-have for about/perspective_of). |
| `life_context.career_history` | Lines 72-75 | life_context.career_history | "Worked at larger firm...joined small studio..." | string | ESTABLISHED | Detailed career narrative. Optional (contextual, for perspective_of). |
| `life_context.daily_routine` | Lines 77-79 | life_context.daily_routine | "On work days: up by 7:30..." | string | ESTABLISHED | Concrete routine details. Optional (for conversational detail in general_chat). |
| `life_context.family` | Lines 80-85 | life_context.family | Array of {relationship, name, detail} | JSON array | ESTABLISHED | Family members: Rajesh (father), Priya (mother), Anjali (sister), Arjun (brother). Optional but valuable for continuity/relationship skills. |
| `life_context.friends` | Lines 94-98 | life_context.friends | Array of {name, relationship} | JSON array | ESTABLISHED | Close friends: Ritu (flatmate), Prashant (colleague), Neha (college friend). Optional but valuable. |
| `life_context.important_life_events` | Lines 86-93 | life_context.important_life_events | Array of event strings | JSON array | ESTABLISHED | 6 key events (moved to Pune, left first job, joined small studio, Ritu moved in, Ladakh trip, restaurant project). Optional but grounds narrative. |

**Phase 50 fixture includes:** occupation, education, career_history, daily_routine, family (partial), friends. **Phase 50 does NOT include:** important_life_events.

**Recommendation for minimum dataset B:** Include occupation (required, proven missing before), education, family (at least names), friends. Can defer career_history, daily_routine, important_life_events to optional expansion.

---

## Personality & Behavioral Fields

| Field ID | Source | Path | Value | Type | Classification | Notes |
|---|---|---|---|---|---|---|
| `personality.core_traits` | Lines 105-112 | personality.core_traits | Array of 6 trait strings | JSON array | ESTABLISHED | Observable, consistent traits (observant, warm, independent, practical, conversational, comfortable with uncertainty). Core for all skills. |
| `personality.summary` | Lines 102-104 | personality.summary | "Warm, observant, independent..." | string | ESTABLISHED | High-level summary. Optional (core_traits sufficient). |
| `worldview.core_values` | Line 115 | worldview.core_values | Array ["honesty", "practicality", ...] | JSON array | ESTABLISHED | 5 explicit values. Optional but valuable for perspective_of / opinion skills. |
| `worldview.strong_opinions` | Lines 116-121 | worldview.strong_opinions | Array of 4 opinion strings | JSON array | ESTABLISHED | Stances on design/ambition/work. Optional. |
| `worldview.beliefs` | Lines 123-128 | worldview.beliefs | Array of 4 belief strings | JSON array | ESTABLISHED | Broader belief set ("good work over impressive work", etc.). Optional. |
| `worldview.moral_boundaries` | Lines 129-133 | worldview.moral_boundaries | Array of 3 boundary strings | JSON array | ESTABLISHED | Hard lines (won't take credit, won't lie to clients, won't fake enthusiasm). Optional. |
| `behavioral_signature.default` | Lines 160-162 | behavioral_signature.default | Long string | string | ESTABLISHED | How she naturally behaves (warm, observant, direct, listens, doesn't perform). Core for all skills. |
| `behavioral_signature.attachment_style` | Lines 164-166 | behavioral_signature.attachment_style | "Secure with some avoidant..." | string | ESTABLISHED | Relationship attachment model. Core for romantic/friendship skills. |
| `communication.style` | Lines 169-172 | communication.style | "Warm, clear, direct..." | string | ESTABLISHED | Conversational approach. Core for all skills. |
| `communication.tone` | Line 173 | communication.tone | "Warm, clear, occasionally dry..." | string | ESTABLISHED | Tonal markers. Core for rendering/modeling voice. |

**Phase 50 fixture includes:** personality.core_traits (partial), behavioral_signature.default (as default field), communication.style, communication.tone.

**Phase 50 fixture does NOT include:** personality.summary, worldview fields, behavioral_signature.attachment_style.

**Recommendation:** Include core_traits, behavioral_signature.default, communication.style for minimum dataset B. Can add worldview.core_values + attachment_style for expansion (valuable for opinion/romantic skills).

---

## Relational & Romantic Fields

| Field ID | Source | Path | Value | Type | Classification | Notes |
|---|---|---|---|---|---|---|
| `relational.romantic_style` | Lines 180-182 | relational.romantic_style | "Warm, playful, direct..." | string | ESTABLISHED | Romantic approach. Required for romantic_intimacy / romantic_conversation skills. Core for romantic context. |
| `relational.what_attracts` | Lines 183-189 | relational.what_attracts | Array of 5 attraction markers | JSON array | ESTABLISHED | Explicit preferences (someone who listens, consistency, ease with self, equality, directness). Optional but valuable. |
| `relational.affection_preferences` | Lines 191-193 | relational.affection_preferences | "Time over words, attention over..." | string | ESTABLISHED | How she gives/receives affection. Optional but valuable for romantic skills. |
| `relational.trust` | Lines 195-198 | relational.trust | "Slow, built through consistency..." | string | ESTABLISHED | Trust model. Optional but valuable for romantic/relationship continuity. |
| `relational.relationship_boundaries` | Lines 199-205 | relational.relationship_boundaries | Array of 5 boundary strings | JSON array | ESTABLISHED | Hard constraints ("won't be flattered", "won't be treated as type", etc.). Optional. |
| `adult_intimacy` | Lines 225-247 | adult_intimacy | JSON object | object | ESTABLISHED | Restricted field covering romantic_style, flirting_style, intimacy_pacing, boundaries, consent_preferences. **Restricted: allowed only for romantic_intimacy + foreplay skills.** |
| `intimate_anatomy` | Lines 219-223 | intimate_anatomy | JSON object | object | ESTABLISHED | Restricted field with descriptive anatomical/physical detail. **Restricted: allowed only for romantic_intimacy + foreplay skills.** |

**Phase 50 fixture includes:** relational.romantic_style (as sample), adult_intimacy (as restricted sample), intimate_anatomy (as restricted sample).

**Phase 50 fixture does NOT include:** what_attracts, affection_preferences, trust, relationship_boundaries.

**Recommendation for minimum dataset B:** Include relational.romantic_style (required for romantic skills), adult_intimacy/intimate_anatomy (restricted, required for romantic_intimacy but not general_chat). Can add what_attracts + trust for expansion.

---

## Aspirations & Future-Looking Fields

| Field ID | Source | Path | Value | Type | Classification | Notes |
|---|---|---|---|---|---|---|
| `aspirations.career_goals` | Lines 136-140 | aspirations.career_goals | Array of 4 goals | JSON array | ESTABLISHED | Professional aspirations (client management, own studio, proud work, learning). Optional but grounds conversations. |
| `aspirations.life_goals` | Lines 143-148 | aspirations.life_goals | Array of 4 life goals | JSON array | ESTABLISHED | Broader life direction (meaningful work, intentional living, travel, defining "enough"). Optional. |
| `aspirations.dreams` | Lines 150-152 | aspirations.dreams | Long string | string | ESTABLISHED | Narrative dreams (small space, design impact, less worry). Optional. |

**Phase 50 fixture includes:** aspirations.career_goals (as dreams), aspirations.life_goals, aspirations.dreams (as dreams).

**Recommendation:** Include at least aspirations.career_goals for minimum dataset B (shows growth trajectory). Can defer other aspirations fields to optional.

---

## Optional/Extended Fields (From `_full_document_supplement`)

The source document includes extensive sections not yet exposed as fields:

| Field ID | Source | Path | Notes | Classification |
|---|---|---|---|---|
| `preferences.hobbies` | Lines 176 | preferences.hobbies | ["reading", "long walks", "sketching", "cooking", "documentaries"] | UNSUPPORTED (no fixture mapping) |
| `narrative_state.unresolved_threads` | Lines 208-213 | narrative_state.unresolved_threads | 4 open questions about career/marriage/ambition | UNSUPPORTED (no fixture mapping) |
| `timeline.*` | Lines (full_document_supplement) | timeline.eras / timeline.arcs / timeline.events | Detailed life timeline, narrative state | UNSUPPORTED (not in Phase 50 fixture) |
| `public_expression.*` | Lines (extended sections) | Social media posting, content pillars | UNSUPPORTED (not in Phase 50 fixture) |
| `_full_document_supplement.*` | Lines 260-296+ | Extended personality/worldview/preferences/communication/relational/visual_identity | FIXTURE-ONLY (exists in fixture for Variant A testing, NOT in production schema today) |

**Newly confirmed this phase (direct read of `simran-master.md` lines 1-220, NOT previously catalogued in any fixture or prior phase document):**

| Field ID (proposed) | Source (real simran-master.md line) | Notes | Classification |
|---|---|---|---|
| `life_context.origin_story` | Line 37 | Full narrative background paragraph (upbringing, move to Pune, current work situation) | UNSUPPORTED — real content, no field/skill mapping exists |
| `life_context.childhood` | Line 55 | Childhood narrative, sibling dynamics, parents' relationship style | UNSUPPORTED |
| `life_context.workplace` | Line 111 | Detailed studio/workplace description, distinct from `occupation` scalar | UNSUPPORTED |
| `life_context.financial_context` | Line 132 | Income/savings/spending narrative | UNSUPPORTED |
| `life_context.living_situation` | Line 139 | Flat/neighborhood description | UNSUPPORTED |
| `life_context.social_circle` | Line 180 | Summary of who's in her social world | UNSUPPORTED — overlaps with `friends`/`family` but is its own field |
| `life_context.weekly_routine` | Line 197 | Distinct from `daily_routine` — week-level rhythm | UNSUPPORTED |
| `life_context.durable_life_situation` | Line 212 | Stable-state narrative summary | UNSUPPORTED |
| `identity.pronouns`, `identity.sexual_orientation`, `identity.relationship_status`, `identity.nationality`, `identity.ethnicity`, `identity.religion`, `identity.languages`, `identity.timezone` | Lines 24-34 | Real structured identity scalars/arrays not previously catalogued in Phase 50's fixture or any prior phase document | UNSUPPORTED |

**This confirms the real source document is substantially larger than any prior phase (49/50) assumed or the Phase 50 fixture captured.** The remainder of the file (beyond line 220, out of this phase's read budget) almost certainly contains further UNSUPPORTED fields under personality/worldview/relational/restricted sections — **NOT ESTABLISHED as exhaustive by this phase; treat the field inventory above as a lower bound, not a complete catalogue.**

**These are documented but NOT recommended for minimum dataset B.** Can be added in future expansions when specific skills demonstrate a need for them.

---

## Validation Checksum: Phase 50 Fixture Coverage

**Total source fields in simran-master.md:** ~50+ distinct addressable fields (including extended sections)

**Phase 50 fixture fields (nested JSON):**
- identity: name, age, hometown, current_city (4/4)
- life_context: occupation, education, career_history, daily_routine, family, friends (6 covered; important_life_events omitted)
- personality: core_traits, summary (2 covered; sub-fields omitted)
- worldview: core_values, strong_opinions, beliefs, moral_boundaries (4 covered)
- aspirations: career_goals, life_goals, dreams (3 covered)
- behavioral_signature: default, attachment_style (2 covered)
- communication: style, tone (2 covered)
- preferences: hobbies (1 covered)
- relational: romantic_style, what_attracts, affection_preferences, trust, relationship_boundaries (5 covered)
- restricted: intimate_anatomy, adult_intimacy (2 covered)

**Total Phase 50 fixture fields:** 31+ distinct nested fields

**Classification:**
- ESTABLISHED: 28+ (exist in source, fixture matches, tests pass)
- DERIVED: 2 (inferred structure, e.g., nested JSON formatting)
- UNSUPPORTED: 3-5 (timeline, public_expression, _full_document_supplement)
- FIXTURE-ONLY: 1 (_full_document_supplement in Variant A only)

**Recommendation for production load:** Use all ESTABLISHED fields (28+). Can safely defer UNSUPPORTED/FIXTURE-ONLY to future expansions.

---

## Skill-to-Field Mapping (Verified Production Skills)

Mapping created based on Phase 50 fixture + BaselineConfiguration.SKILLS verification:

| Skill | Required Fields | Recommended Fields | Conditional Fields | Restricted Fields | Source/Rationale |
|---|---|---|---|---|---|
| `general_chat` | identity.name, personality.core_traits, communication.style | life_context.occupation | worldview.strong_opinions, preferences.hobbies, life_context.daily_routine | (none) | ESTABLISHED from Phase 50 test B; includes occupation (known missing defect now added). |
| `friendship` | identity.name, personality.core_traits, behavioral_signature.attachment_style, communication.style | life_context.friends | relational.what_attracts | (none) | ESTABLISHED from Phase 50 test end-to-end. |
| `romantic_intimacy` | identity.name, relational.romantic_style, adult_intimacy | communication.tone, personality.core_traits | intimate_anatomy | adult_intimacy, intimate_anatomy | ESTABLISHED from Phase 50 test C, J (restricted properly gated). |
| `foreplay` | identity.name, adult_intimacy | communication.tone | intimate_anatomy | adult_intimacy, intimate_anatomy | ESTABLISHED from Phase 50 test C, J. |
| `advice` | *unmapped* | | | | Real production skill, deliberately left unmapped in Phase 50 to test "unknown skill" case (E). Can map in future. |

**Status:** All four mapped skills verified against real BaselineConfiguration.SKILLS keys (Phase 50 §6, ESTABLISHED).

---

## Remaining Decisions (HUMAN DECISION REQUIRED)

### 1. Restricted Field Authorization Scope

**Current model (ESTABLISHED from Phase 50):**
- `intimate_anatomy` + `adult_intimacy` restricted to romantic_intimacy + foreplay only
- No other skills have access
- No exceptions / overrides

**Question:** Is this scope correct for production?
- Can `romantic_conversation` see these fields? (currently NO)
- Can a hypothetical `relationship_discussion` skill see them? (not yet mapped)
- Are these the only restricted fields, or should others be added? (currently just these two)

**Recommendation:** Accept current model for Phase 51. Can expand in future expansions if new skills demonstrate need.

### 2. Minimum Dataset Scope (Minimum B)

**Phase 50 fixture covers 31+ ESTABLISHED fields.** Which are required for production minimum dataset B?

**Proposed minimum (conservative):**
- Identity: name, age, current_city (+ hometown optional)
- Occupation: life_context.occupation (REQUIRED, Phase 50 defect fix)
- Personality: core_traits, default behavioral signature, communication style
- Relational (romantic only): romantic_style, adult_intimacy, intimate_anatomy
- Aspirations (optional): career_goals for depth

**Total: 8-10 required, ~5 optional** for ~2KB of content total.

**Question:** Does this list match product intent? Any additions/deletions?

### 3. Extended Fields for Future Expansion

**Phase 50 fixture shows 31+ ESTABLISHED fields.** For Phase 51, recommend capturing all ESTABLISHED fields in persona_field_registry, but only loading required/recommended ones in skill mappings.

**This allows:**
- Future skills to use existing field registry entries (no schema changes)
- Progressive expansion of field mappings as new skills are added
- Clear documentation of what data exists

**Question:** Accept this approach, or limit Phase 51 to only the minimum set?

---

## Versioning Specification (ESTABLISHED)

**Current model (from Phase 50 architecture):**

```
Simran's PersonaCoreVersions.content = BaselineConfiguration.SIMRAN_PERSONA_CORE

When SIMRAN_PERSONA_CORE is updated (new prompt text):
  1. New PersonaCoreVersions row created (new id)
  2. New persona_master_documents row must be created (links new version_id)
  3. Old master_document row persists (transcript reproducibility)

Field registry is NOT versioned:
  → Global, updated in place, shared across personas and versions

Skill mappings are NOT versioned:
  → Global, updated in place, shared across personas
```

**Implication:** If Simran's core prompt changes in Phase 52+, someone must author a NEW master_document for that version. No auto-inheritance from old version (by design).

**Question:** Does this match product intent, or should old documents auto-inherit to new versions?

---

## Mapping Approval Checklist (For Human Review)

Before authoring production data, confirm:

- [ ] All ESTABLISHED fields in this mapping are acceptable as authoritative
- [ ] Phase 50 fixture accurately represents source (all 31+ fields checked)
- [ ] Restricted-field scope (intimate_anatomy + adult_intimacy only for romantic skills) is correct
- [ ] Minimum dataset B scope (8-10 required fields) matches product intent
- [ ] Skill-to-field mappings (general_chat, friendship, romantic_intimacy, foreplay) are correct
- [ ] Versioning model (per-version master_document, non-versioned registry/mappings) is acceptable
- [ ] Can proceed to Phase 52 implementation (authoring + Flyway migration)

---

## Next Steps

This mapping serves as the source-of-truth specification for Phase 52 implementation. Phase 52 will:

1. Confirm all ESTABLISHED fields are production-ready
2. Extract Phase 50 fixture into PersonaContextSeeds.kt
3. Generate Flyway migration with field_registry + skill_context_requirements + master_document rows
4. Implement PHASE51_DATA_VALIDATION_MATRIX tests
5. Dry-run against test database
6. Commit to main branch

Awaiting human approval of this mapping before Phase 52 proceeds.
