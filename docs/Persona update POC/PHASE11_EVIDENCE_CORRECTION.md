# Phase 11 — Evidence Correction After Phase 10E Re-Verification

**Zero API calls made.** This document corrects two misclassifications in `PHASE10E_MAPPING_CLOSURE.md` and one incompleteness in `PHASE10E_ZEROCOST_AUDIT.md`, using only already-collected CSV evidence (`PHASE10D_EXECUTIONS.csv`, `PHASE10E_EXECUTIONS.csv`) and a direct re-read of `PersonaContextPocSeed.kt`. **Neither prior document is edited, rewritten, or deleted** — both remain exactly as originally published; this document is an explicit addendum.

---

## 1. Phase 10E's original conclusion (as published)

`PHASE10E_MAPPING_CLOSURE.md` classified the two Stage-2 residuals as:
- **"Been in this city long?"** — an "arithmetic/reasoning residual": the required facts (age, move timeline) were said to be present, and the model was said to fail to derive "6 years" from them.
- **"Do you rent or own your place?"** — a "model-behavior residual": `life_context.friends` was said to be "confirmed present in context... verified at Stage 1," with the model contradicting it anyway.

## 2. What was rechecked

At the user's explicit instruction, both claims were re-verified against **primary evidence** (raw Stage 1 log text and the actual `selected_field_ids` column in `PHASE10D_EXECUTIONS.csv`/`PHASE10E_EXECUTIONS.csv`), not against the prior turn's summary.

## 3. The exact evidence that was misread

**Residual A — "Do you rent or own your place?"**: the actual Stage 1 log line is:
```
[TUNED] general_chat: "Do you rent or own your place?" -> [identity.name, personality.core_traits, identity.hometown, identity.current_city, communication.style, narrative_state.unresolved_threads, life_context.occupation] (gained a relevant field: true)
```
`life_context.friends` is **not** in this list. The prior write-up attributed a neighboring log line's content to this scenario. Cross-checked independently against `PHASE10E_EXECUTIONS.csv`: both SS-M and SR-M rows for this exact scenario list `selected_field_ids` = `identity.name;personality.core_traits;identity.hometown;identity.current_city;communication.style;narrative_state.unresolved_threads;life_context.occupation` — **`life_context.friends` is confirmed absent**, consistently, in both Stage 1 and the actual Stage 2 execution. There is no discrepancy between Stage 1 and Stage 2 — both agree the field was never selected; only the prior narrative was wrong.

**Root cause (verified, not inferred):** the `life_context.friends` description (`"friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"`) shares zero literal words with "Do you rent or own your place?" — no overlap, so `PersonaFieldRelevanceScorer` correctly scores it 0 and excludes it. The description was written around the wording of the scenarios already known to fail (live alone, who do you live with, living situation) and did not anticipate "rent or own your place" as a distinct phrasing of the same underlying topic.

**Residual B — "Been in this city long?"**: re-reading `PersonaContextPocSeed.kt` line 303, the specific fact needed to answer this correctly (moved to Pune at 21 / studio years since 2020, i.e., the ~6-year timeline) lives in the value of `life_context.career_history`, registered as a field (line 333) and mapped as a **conditional** field for `about` only (line 410). It was **never added to `general_chat`'s mapping** by any of Phase 10E's four edits, and confirmed absent from both the BASELINE and TUNED `selected_field_ids` for this exact scenario in both `PHASE10D_EXECUTIONS.csv` and `PHASE10E_EXECUTIONS.csv` (`general_chat`'s field list for this scenario, post-fix: `identity.name;personality.core_traits;identity.hometown;identity.current_city;communication.style;narrative_state.unresolved_threads;life_context.occupation` — no `life_context.career_history`, no `life_context.family`, no `life_context.friends`). **The specific timeline fact was never in context at all**, in either phase. The "arithmetic residual" framing is not supported — there was no arithmetic to fail at, because the operand (the move year) was never supplied.

## 4. Corrected interpretation

Both residuals are **Question A (retrieval completeness) failures**, not Question B (model grounding/reasoning) failures. There is currently **no confirmed instance in this dataset** of the model being given the correct fact and still contradicting it. The "rent or own" case, in particular, cannot be used as a "cleanest candidate" for a model-grounding investigation, because the premise (fact present) is false.

**A secondary finding, uncovered while re-auditing "rent or own":** this scenario actually had **two independent, overlapping problems from the start**, not one. Phase 10D's original failure for this scenario (`H9ME`: "a small one-bedroom in Bandra") was driven by the **wrong city** (LOCATION category) — the most salient error, and the one the original zero-cost audit recorded. Phase 10E's mapping fix (adding `identity.hometown`/`identity.current_city` to `general_chat`) **did fix the city error** — both Stage 2 responses (`9GXP`, `CCSW`) correctly state Kothrud/Pune. But a **second, previously un-flagged problem** — the response implying solo ownership, contradicting the established shared flat with Ritu — was already latent in this scenario and was never addressed, because the original audit only recorded the city error and did not separately flag the implicit-solo-living pattern as its own FRIENDS-category instance for this specific scenario.

## 5. Which `PHASE10E_ZEROCOST_AUDIT.md` statements are now superseded (in part)

- "All 23 SS/SR failures... classify cleanly into exactly one of the three proposed categories. Zero 'something else.'" — **superseded.** "Do you rent or own your place?" should have been double-counted under both LOCATION and FRIENDS; it was only recorded under LOCATION.
- The confirmed minimal 4-edit change set — **not superseded**; all four edits were correctly identified and correctly implemented for what they targeted. The audit's omission was in not recognizing that "rent or own your place" needed the FRIENDS fix in addition to the LOCATION fix, and that `life_context.career_history` (a fourth, distinct field) was needed for "Been in this city long?" specifically.
- The overlap calculation (90.9% / 83.3%) — **not superseded**; this was computed directly from the Phase 10D wrong-lists and remains numerically correct as a description of SS-vs-SR failure overlap. It does not depend on the category-labeling error above.

## 6. Which `PHASE10E_MAPPING_CLOSURE.md` statements are now superseded (in part)

- "`life_context.friends` was confirmed present in context for this scenario (verified at Stage 1)" — **superseded, factually incorrect.** Corrected above.
- "This is a residual model-behavior issue, not a mapping gap" (re: rent/own) — **superseded.** Corrected: this is a residual mapping gap (incomplete `life_context.friends` description coverage).
- "This is a partial improvement, not a full fix... the model doesn't reliably do the age-minus-21 arithmetic" (re: been-in-city) — **superseded.** Corrected: the required fact (`life_context.career_history`) was never mapped to `general_chat` at all; no arithmetic was attempted on present data because the data was absent.
- The headline results table (SS-M 26/30, SR-M 26/30, 86.7%) — **not superseded.** These are the actual blind-scored, unblinded counts and are unaffected by the mischaracterization of *why* the two failures occurred.
- The token-cost figures — **not superseded**; independent of this correction.
- "11 of 13 scenarios fully fixed" — **not superseded** as a count, though see §4's secondary finding: one of those 11 ("Is Pune home for you...") was fixed via an incidental word-overlap match (the word "home" in the question matched the family description's word "home"), which is correct behavior but was not the *intended* mechanism — noted for completeness, not treated as a problem.

## 7. Which Phase 10E conclusions remain valid

- The mechanism (targeted, minimal field-mapping edits can recover most of a selection-effect gap) — **valid**, and if anything strengthened: with the two residuals now correctly reclassified as retrieval gaps rather than model-behavior limits, **100% of Phase 10E's explainable failures are retrieval/mapping-caused**, not a mix of retrieval and model-behavior issues as previously stated.
- Stage 1's zero-cost gate correctly caught what it was designed to catch (the two false-positive control flags, both correctly resolved as intended-behavior in the original Phase 10E writeup) — **valid**, unaffected by this correction.
- The 60-call Stage 2 pilot's raw execution and scoring data — **valid and unchanged**; this correction is about *interpretation* of two already-correctly-recorded data points, not about the data itself.
- No historical CSV was found to be wrong in this recheck — only the prose interpretation layered on top of two of them.

## 8. Corrected mapping-gap inventory

| Category | Scenarios | Status after Phase 10E |
|---|---|---|
| FAMILY | 3 (siblings ×2, family) | Fully closed |
| FRIENDS | 6 (live-alone, who-live-with, flat-like, flatmate-before, living-situation, Ritu-these-days) **+ 1 incomplete instance** (rent-or-own, description doesn't cover this phrasing) | 6 of 7 instances closed; 1 open |
| LOCATION | 4 (been-in-city, pune-home, refresh-memory, rent-or-own) | 3 of 4 closed (rent-or-own's city error is fixed; its separate friends-category error remains) |
| **TIMELINE/CAREER_HISTORY (newly identified)** | 1 (been-in-city — specifically needs `life_context.career_history`, distinct from the hometown/current_city fields already added) | Not addressed by any Phase 10E edit |

Total unique scenarios remains 13 (no new scenario was discovered — "been in this city" and "rent or own" were already counted; what changed is the required-field attribution for each, revealing they need an additional/different field than the one Phase 10E supplied).

## 9. Corrected classification table

| Scenario | SS-M fact present? | SR-M fact present? | Required field | Mapping status | Correct classification |
|---|---|---|---|---|---|
| Do you rent or own your place? | No (`life_context.friends` absent, confirmed both conditions) | No (same) | `life_context.friends` | Mapped to `general_chat`, but description doesn't match this phrasing | Question A — retrieval/mapping (description-coverage gap) |
| Been in this city long? | No (`life_context.career_history` absent, confirmed both conditions) | No (same) | `life_context.career_history` | Never mapped to `general_chat` at all | Question A — retrieval/mapping (missing field mapping) |

| Previous Phase 10E classification | Corrected classification | Evidence |
|---|---|---|
| Rent/own = Question B (model contradicts present fact) | Question A (fact never present) | `selected_field_ids` in both Stage 1 log and `PHASE10E_EXECUTIONS.csv` confirm `life_context.friends` absent |
| Been-in-city = Question C-adjacent "arithmetic residual" (Question B-family) | Question A (fact never present) | `selected_field_ids` confirm `life_context.career_history` absent from both baseline and tuned `general_chat` selection |

## 10. Important distinctions maintained in this correction

Per the required discipline, this correction is careful to distinguish: the fact exists in the master document (true for both) → the field is registered (true for both — `life_context.friends` and `life_context.career_history` both exist in the registry) → the field is mapped to the *relevant skill* (true for `friends` on `general_chat` as of Phase 10E; **false** for `career_history` on `general_chat`, unchanged) → the field's description matches the question's wording (**false** for `friends` on "rent or own" — mapped but not matched) → the field is selected by the resolver (false in both cases, confirmed by `selected_field_ids`) → reaches the provider/request (moot — never selected) → the model uses it correctly (**cannot be evaluated — the fact was never given to the model to use**).

## 11. Remaining unknowns

- Whether any of the **other 11** "fully fixed" scenarios have a similarly-latent second issue that happened not to surface in this specific 2-response sample (n=2 per scenario is small) — not re-audited exhaustively in this pass; flagged as a residual uncertainty, not assumed clean.
- Whether extending `life_context.career_history` to `general_chat` (as conditional, with an appropriately-worded description) and extending `life_context.friends`'s description to cover "rent," "own," and "place" would close both remaining gaps — plausible given the pattern established by the other 11 fixes, but **not tested**; no such change has been made in this phase.
- Whether a genuine Question B (model-grounding) failure exists anywhere in the Phase 10 dataset at all — **currently unconfirmed**. No verified instance has survived scrutiny. This is itself a notable, if negative, finding: every factuality failure examined across Phases 10D and 10E so far traces to a retrieval/mapping cause, not a model-behavior cause, once checked against the actual selected-field evidence rather than assumed from response content alone.

## 12. Is another zero-cost audit necessary?

**Yes, narrowly** — before any further mapping change is proposed or tested, the 11 "fixed" scenarios should be checked the same way (compare `selected_field_ids` against what each response actually needed) to rule out other latent secondary issues, per §11's first bullet. This was not performed in this pass, which was scoped specifically to re-verifying the two named residuals per the instruction. **No such audit is performed here — this is a recommendation for a possible next step, not an action taken.**

## 13. What a future experiment would need to establish (not executed)

If pursued, a next zero-cost step would be: (a) map `life_context.career_history` to `general_chat` (conditional, with a timeline/duration-oriented description) and enrich `life_context.friends`'s description to cover "rent," "own," "place," "apartment"; (b) re-run Stage 1 only (zero cost) to confirm both target scenarios now select the intended field without new over-triggering; (c) only then consider whether a small, targeted real-API re-test of just these two scenarios is warranted. **None of this has been done.** No mapping file, registry, or resolver has been touched in this phase.

---

## Final report

- **Files read this phase:** `PHASE10A_A_BASELINE_CORRECTION.md`, `PHASE10B_CONTEXT_MATRIX.md`, `PHASE10B_EXPERIMENT_DESIGN.md`, `PHASE10B_HISTORICAL_DATA_RECONCILIATION.md`, `PHASE10B_TC6_STATUS.md`, `PHASE10E_ZEROCOST_AUDIT.md`, `PHASE10E_MAPPING_CLOSURE.md`, `PersonaContextPocSeed.kt` (re-read, lines 73/303/333/406-410).
- **CSVs inspected:** `PHASE10D_EXECUTIONS.csv`, `PHASE10E_EXECUTIONS.csv` (specifically the `selected_field_ids` column for all 13 previously-failing scenarios, cross-checked against the raw Stage 1 log text).
- **API calls made: 0.**
- **Corrected number of mapping-gap categories:** 4 (FAMILY, FRIENDS, LOCATION, and newly-identified TIMELINE/CAREER_HISTORY), where FRIENDS has one incomplete instance and LOCATION's rent/own scenario is now understood to have required both a LOCATION fix (applied, successful) and a separate FRIENDS fix (not applied).
- **Corrected scenario count:** still 13 unique scenarios — no new scenario discovered, two required-field attributions corrected.
- **Overlap (90.9%/83.3%):** unaffected by this correction, remains as originally computed.
- **Phase 10E claims superseded:** the specific causal explanation for both Stage-2 residuals (§5, §6). The headline recovery numbers (86.7%/86.7%, +3.5% token cost, 11/13 scenarios fixed) are **not** superseded.
- **Phase 10E claims that remain valid:** the mapping-closure mechanism itself, the Stage 1 gate's correctness, the raw execution/scoring data, the token-cost findings, the overlap calculation.
- **Internal consistency:** Phase 11's evidence base (this document plus the unedited Phase 10D/10E artifacts) is now internally consistent — every claim about field presence is traceable to a specific CSV cell or log line, not inferred from response content.
- **Remaining uncertainty:** whether other latent secondary issues exist among the 11 "fixed" scenarios (§11); whether the corrected fixes for the two residuals would work if implemented (untested).

**No mapping, registry, resolver, or production change was made. No new retrieval experiment was started. No architecture or production recommendation is given.**
