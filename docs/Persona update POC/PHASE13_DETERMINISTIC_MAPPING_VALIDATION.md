# Phase 13 — Deterministic Validation of Remaining Mapping Gaps

**Zero API/LLM calls.** This test method (`phase13DeterministicMappingValidation`) never invokes `service.sendMessage()` or any generation path — it calls `PersonaContextResolver.resolve()` directly, which has no network dependency. No historical file (Phase 10–12) was modified.

---

## 1. Objective

Validate, deterministically, whether the two Phase 12 proposed fixes (friends lexical-coverage expansion; timeline field mapping) produce the intended resolver selection without unintended side effects, before any real-generation test is considered.

## 2. Source/configuration inspected

`PersonaContextPocSeed.kt`, `PersonaFieldRelevanceScorer.kt`, `PersonaFieldRegistryRepository`/`SkillContextRequirementRepository` (both expose `update()`, reused from the TC-6/Phase 10E isolation pattern — no seed file edited). Both fixes were applied in two independent, fresh, in-memory `World()` instances via `repository.update()` calls only.

**One test-design error was found and corrected before drawing any conclusion, disclosed here rather than silently fixed:** the first run of this check compared each fix's "tuned" state directly against the *fully-unfixed, pre-Phase-10E* baseline. This caused the already-validated Phase 10E gains (family, friends-original-wording, hometown/current_city) to be counted as "unintended new selections" of the Phase 13 fixes — Fix #1's check reported 7 false-positive "unintended" scenarios on the first run. Corrected by taking each fix's regression baseline at the **post-Phase-10E** state (all original Phase 10E edits applied, before the new Phase 13 wording/mapping is added), isolating exactly the marginal effect of each new change. Re-run after the correction; results below are from the corrected run.

## 3. Friends fix — test definition

**Change tested:** extend `life_context.friends`'s description from `"friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"` to add `"rent own place apartment buy bought lease"`.
**Baseline:** post-Phase-10E state (friends mapped to `about`+`general_chat`, original wording).
**Tuned:** same, with the expanded wording.
**Assertions:** intended positive; full 30-scenario corpus diff; no unintended `life_context.friends` gain elsewhere; no restricted-field change; determinism on repeat.

## 4. Friends fix — results

- **Intended positive: TRUE.** "Do you rent or own your place?" (general_chat) now selects `life_context.friends`.
- **Corpus-wide diff: exactly 1 scenario affected** (the intended one). `new: [life_context.friends]`.
- **Unintended selections: 0.**
- **Restricted-field change: none** (`intimate_anatomy`/`adult_intimacy` never selected).
- **Determinism: confirmed** (identical output on repeat).

## 5. Timeline fix — test definition

**Change tested:** map `identity.age` and `life_context.important_life_events` to `general_chat` as conditional fields; give both a shared description `"years long since moved arrived came time"` (plus each field's own literal ID prefix, matching the existing convention).
**Baseline:** post-Phase-10E state (same as above).
**Tuned:** same, plus the two new conditional mappings/descriptions.
**Assertions:** intended positive (both fields together); full corpus diff; no unintended gain; no restricted-field change; determinism.

**Explicit scope limitation stated up front, per instruction:** this check establishes only that the fields become *selectable* — it makes no claim about whether a model would correctly derive "~6 years" from `identity.age` (27) and the "moved to Pune at 21" line inside `life_context.important_life_events`'s value. That is a separate, untested question (Question B territory), not addressed here.

## 6. Timeline fix — results

- **Intended positive: TRUE.** "Been in this city long?" (general_chat) now selects both `identity.age` and `life_context.important_life_events`. Full field set for this scenario post-fix: `[identity.name, personality.core_traits, identity.hometown, identity.current_city, communication.style, narrative_state.unresolved_threads, life_context.occupation, identity.age, life_context.important_life_events]`.
- **Corpus-wide diff: exactly 1 scenario affected** (the intended one). `new: [identity.age, life_context.important_life_events]`.
- **Unintended selections: 0** — despite using more generic trigger words (`years, long, since, moved, arrived, came, time`) than Fix #1's, none of the other 29 corpus scenarios contain any of these words, so none newly matched.
- **Restricted-field change: none.**
- **Determinism: confirmed.**

## 7. Corpus-wide regression analysis

Both fixes: **1 scenario affected, 0 unintended, out of 30.** Neither fix altered the selection for any of the 28 previously-validated scenarios (the 20 already affected by the original Phase 10E fix set retained exactly their post-10E field sets; the fixed baseline for this check was taken at that post-10E state specifically to make this comparison clean).

## 8. Restricted-field safety

Structurally expected to be unaffected (neither fix touches `canAccessField`, `allowedContexts`, or any restricted field's registry entry) and empirically confirmed: 0 restricted-field selections across all 30 scenarios under either tuned state.

## 9. Determinism

Both fixes' full 30-scenario resolver output was computed twice in immediate succession; identical both times, for both fixes.

## 10. Before/after selection matrix (only the 2 affected scenarios; all other 28 unchanged)

| Scenario | Skill | Post-10E (baseline for this phase) | Fix #1 tuned | Fix #2 tuned |
|---|---|---|---|---|
| Do you rent or own your place? | general_chat | `[..., life_context.occupation]` (no friends) | `[..., life_context.occupation, life_context.friends]` | unchanged from baseline |
| Been in this city long? | general_chat | `[..., life_context.occupation]` (no age/events) | unchanged from baseline | `[..., life_context.occupation, identity.age, life_context.important_life_events]` |

## 11. What this proves

**A only:** the resolver now retrieves the intended fields for both target scenarios, with zero measured regression across the full 30-scenario corpus, zero restricted-field impact, and full determinism. Both fixes are narrowly scoped in their observed effect (1 scenario each) within this corpus.

## 12. What this does NOT prove

- **Not proven:** that a real model generation would now answer either question correctly. Retrieval availability (Question A) is established; model use of the retrieved fact (Question B) is untested here and remains open, per the standing distinction from Phase 11/12.
- **Not proven:** that `identity.age` + `life_context.important_life_events` together are *sufficient* for a model to derive "~6 years" — the value of `important_life_events` states "Moving to Pune at 21 to study architecture" as a bare fact among a list of five other unrelated life events, not as an explicit "years ago" figure; deriving a duration still requires the model to combine it with the current age itself. This is a compound-reasoning demand on top of retrieval, not resolved by this fix.
- **Not proven:** that Fix #2's generic vocabulary (`years, long, since, moved, arrived, came, time`) is safe outside this specific 30-scenario corpus — only checked against the scenarios that exist here; a broader corpus could contain other temporal phrasings this vocabulary would also match, for better or worse.
- **Not tested:** any interaction between the two fixes applied simultaneously (each was tested in an independent `World()`, never combined in the same run), per the standing one-hypothesis-per-experiment discipline.

## 13. STOP/next-experiment status

No STOP condition was triggered in the corrected run (the original miscounted "7 unintended" result was a test-harness bug, disclosed and fixed within this same phase per the "harness bugs may be fixed, production behavior may not" rule — no production/mapping file was touched to fix it, only the Phase 13 test method's own baseline-snapshot logic). Both fixes are **ready, on this evidence, for a small, targeted real-API Stage 2** — per Phase 12 §13's stated minimal-change discipline, that would mean 4 calls (2 scenarios × 2 conditions: SS/general_chat with the friends fix, SS/general_chat with the timeline fix), not a full 30-scenario re-run, since no other scenario's selection changed. **No such Stage 2 has been run or is authorized by this document** — this phase stops at deterministic validation only.

---

## Final report

- `PHASE13_DETERMINISTIC_MAPPING_VALIDATION.md` created.
- **Deterministic scenarios checked:** 30 (full corpus) × 2 fixes × 2 snapshots (baseline + tuned) + 2 repeat-runs for determinism = 120 resolver calls, 0 LLM calls.
- **Friends-fix result:** intended positive true, 0 unintended, 0 restricted-field change, deterministic.
- **Timeline-fix result:** intended positive true, 0 unintended, 0 restricted-field change, deterministic.
- **Unintended selections:** 0 for both fixes (after correcting the baseline-snapshot bug found and fixed within this phase).
- **Restricted-field result:** clean for both.
- **Determinism result:** confirmed for both.
- **Files modified:** `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` (added `phase13DeterministicMappingValidation()` only — additive, isolated to this one test method). No seed, registry, resolver, renderer, or historical document modified.
- **API calls: 0.**
- **Ready for a future real-generation test?** Yes, both, on the narrow terms described in §13 — a 4-call targeted Stage 2, not a broad re-run, and explicitly not yet authorized by this document.
- **Remaining uncertainty:** whether either fix actually changes the model's real answer (Question B, untested); whether Fix #2's fields are sufficient for the model to compute a duration even when present; whether Fix #2's generic vocabulary would over-trigger outside this specific corpus.
