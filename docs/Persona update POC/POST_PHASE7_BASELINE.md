# Post-Phase-7 Baseline Check (Phase A0)

**Status: verification only. Nothing was modified to produce this document.** Performed immediately before TC-6/Candidate E execution, per the plan's Phase A0 requirement.

---

## 1. Source re-read and match against `POST_PHASE7_TUNING_PLAN.md`

| File | Claim in the plan | Re-verified this session | Match |
|---|---|---|---|
| `PersonaContextResolver.kt` | Unchanged since Phase 6/7; A raw, B/C selective, C adds behavioral core | Re-read (Phase 7 investigation); no edits since | ✓ |
| `PersonaFieldRelevanceScorer.kt` | Word-overlap scorer, threshold 0.25 | Re-read in full (Phase 7); unchanged | ✓ |
| `PersonaContextRenderer.kt` | Never reads `.description`, only renders values | Re-read in full (Phase 7); unchanged | ✓ |
| `PersonaContextPocSeed.kt` — `entry()` helper | `description = fieldId` for every entry, line 387 | Re-read lines 325-388 this turn — confirmed byte-identical | ✓ |
| `PersonaContextPocSeed.kt` — `life_context.family` registry row | Line 335, classification `life_context`, `core=false` | Confirmed at line 335 this turn | ✓ |
| `PersonaContextPocSeed.kt` — `about`/`friendship` skill mappings | `life_context.family` is `conditionalFields` for both, not required/recommended | Re-read lines 400-455 this turn — confirmed | ✓ |
| `PersonaContextPocSeed.kt` — `life_context.friends` value | Ritu/Prashant/Neha only, no childhood-friend statement either way | Re-read earlier this session (lines 80-97); no edits made since | ✓ |
| `BaselineConfiguration.kt` — `CONVERSATION_ENGINE` | Target insertion point after "Do not invent persona biography outside the supplied Persona Core." | Confirmed at line 122 this turn, text byte-identical | ✓ |

**No discrepancy found between the plan and the current source. Proceeding is authorized per the plan's own STOP-if-drifted condition.**

---

## 2. Recorded state

- **Git branch:** QA. **HEAD:** `03ee727` ("fix: Pass OpenRouter API key via JVM system property for test subprocess"). Working tree has the expected uncommitted changes from this session's Phase 6/7 work (POC seed/resolver-wiring files, new docs, CSVs) — no unexpected third-party changes present.
- **Engine version / persona-core version (test harness):** the H2-backed test `World` reseeds fresh per test run via `BaselineSeeder` + `PersonaContextPocSeed`; every prior Phase 6/7 log line confirms `conversationEngineVersion=1 personaCoreVersion=1 intentEngineVersion=1 memoryEngineVersion=1` for every execution so far. This is expected, reproducible seed behavior, not drift.
- **Resolver state:** `PersonaContextResolver.resolve()` — variant `"A"` → raw; variant `"C"` → selective + behavioral core; anything else (`"B"`, and Phase 7's diagnostic `"D"` when called directly, which is why Phase 7 had to special-case it in the test harness, not in the resolver) → generic selective path. Unchanged.
- **Field-registry state:** 34 entries (33 normal + `intimate_anatomy`/`adult_intimacy` restricted, `allowedContexts=[romantic_intimacy, foreplay]`). All descriptions currently equal their own field ID.
- **Relevant field descriptions (current, BASELINE, pre-TC-6):**
  - `life_context.family` → `"life_context.family"`
- **Skill mappings (current, BASELINE, relevant to TC-6):**
  - `about`: required=`[identity.name, identity.age, identity.hometown, identity.current_city]`; recommended=`[life_context.occupation, life_context.education, personality.core_traits]`; conditional=`[worldview.strong_opinions, aspirations.career_goals, life_context.career_history, life_context.family]`.
  - `friendship`: required=`[identity.name, personality.core_traits, behavioral_signature.attachment_style]`; recommended=`[communication.style, life_context.friends]`; conditional=`[preferences.hobbies, life_context.important_life_events, life_context.family]`.
- **Conversation Engine text/version (current, BASELINE, pre-Candidate-E):** `BaselineConfiguration.CONVERSATION_ENGINE`, 216-line constant, last relevant line before the planned insertion point: *"Do not invent persona biography outside the supplied Persona Core."* (line 122). No paragraph has been inserted yet.
- **Variant A/B/C semantics:** unchanged from Phase 6 (raw / selective / selective+behavioral-core). Variant `"D"` exists only as the Phase 7 diagnostic path (`StructuredFullDocumentContextProvider`), not touched by either TC-6 or Candidate E.
- **Test/harness state:** `PersonaContextRealBenchmarkTest.kt`'s `World`, `sendTurn`, `runSingleTurnBatch`/`runAvsDBatch`, `writeBlindingArtifacts`/`writePhase7BlindingArtifacts`, `saveCsvNamed` all present and last known-good (Phase 7's corrected run succeeded end-to-end with these).

---

## 3. Decision

No drift detected. **Cleared to proceed to TC-6 Stage 1 (deterministic check, zero API cost).** Candidate E remains untouched and unscheduled until TC-6 is fully analyzed and its experimental state is reverted, per the plan's sequencing rule.
