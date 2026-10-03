# Phase 21 — Confirmatory Age-Label Experiment: Results

**Exactly 20 real API calls made, as authorized (10×B, 10×LA), in the predetermined order fixed before execution. All 20 succeeded. All 20 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, or engine artifact was modified before, during, or after execution. No Phase 10–20 document or historical CSV was modified.

---

## 1. Objective

Determine whether the Phase 20 finding — that age-only labeling (LA) produces a higher rate of correct derived-duration answers than normal B on the timeline scenario — reproduces in a second, independent 20-call sample. This is a narrow reproducibility check, not a new formatting, retrieval, or architecture experiment.

## 2. Stage 1 audit

Documented in full in `PHASE21_CONFIRMATORY_DESIGN.md`. Summary: `git status` confirmed no source file relevant to B or LA (`AgeOnlyLabeledScalarDiagnosticProvider.kt`, `PersonaContextRenderer.kt`, `TestChatService.kt`'s `"LA"` branch) had changed since Phase 20; direct re-read confirmed LA's only branch point vs. B remains the single `identity.age` label condition; all constancy variables (fields, facts, engine/persona version, skill, message, history, memory, profile, provider-to-request path, restricted-field exposure) were re-verified unchanged. The live zero-cost precondition re-check, run immediately before the first call, passed all 5 checks:

| Check | Result |
|---|---|
| Selected field IDs identical to Phase 14/16/19/20 | PASS |
| B payload contains "27" and "Moving to Pune at 21" | PASS |
| LA payload contains "Age: 27" | PASS |
| No restricted field in B/LA | PASS |
| LA differs from B by exactly the age-label line (same as Phase 20) | PASS |

No STOP condition was triggered. Stage 1 was clean.

## 3. Exact B/LA condition definitions

Identical to Phase 20: **B** = `ResolvedPersonaContextProvider` (variant "B"), normal structured rendering. **LA** = `AgeOnlyLabeledScalarDiagnosticProvider` (variant "LA"), identical to B's own resolver selection and rendering except `identity.age` alone is rendered as `"Age: 27"` instead of a bare `"27"`; every other top-level scalar (name, hometown, current_city, occupation, communication style) remains bare, exactly as in B.

## 4. Execution order

Fixed before any call: `List(10){"B"} + List(10){"LA"}` — calls 1–10 = B, 11–20 = LA. Not altered after seeing any response. No call was retried for a semantic (as opposed to technical) reason. No L calls were added (no technical STOP condition required them). No additional scenario was introduced.

## 5. Per-call results

| # | Variant | Stated duration | Age 21 referenced | Classification |
|---|---|---|---|---|
| 1 | B | "about six years" | Yes | **CORRECT** |
| 2 | B | "about five years" | Yes | **WRONG** |
| 3 | B | "just over three years" | No | **WRONG** |
| 4 | B | "just over two years" | No | **WRONG** |
| 5 | B | "about three years" | Yes | **WRONG** |
| 6 | B | "five years" | Yes | **WRONG** |
| 7 | B | "about six years back" | No | **CORRECT** |
| 8 | B | "a little over two years" | No | **WRONG** |
| 9 | B | "just about three years" | No | **WRONG** |
| 10 | B | "a little over two years" | No | **WRONG** |
| 11 | LA | "just over four years" | Yes | **WRONG** |
| 12 | LA | "about six years" | Yes | **CORRECT** |
| 13 | LA | "a few years now" (no specific number) | Yes | **AMBIGUOUS** |
| 14 | LA | "coming up on six years" | Yes | **CORRECT** |
| 15 | LA | "about six years" | Yes | **CORRECT** |
| 16 | LA | "about six years" | No (age not restated, but derivation matches) | **CORRECT** |
| 17 | LA | "about three years" | Yes | **WRONG** |
| 18 | LA | "about six years" | Yes | **CORRECT** |
| 19 | LA | "about six years" | Yes | **CORRECT** |
| 20 | LA | "four years, give or take" | Yes | **WRONG** |

Full verbatim responses are in `PHASE21_EXECUTIONS.csv`.

## 6. Aggregate results

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---|---|---|---|
| B | 10 | 2 | 8 | 0 |
| LA | 10 | 6 | 3 | 1 |

Raw distributions reported before interpretation, per instruction.

## 7. Payload-verification results

All 20 calls: `payload_matches_request = true`. All 20 calls: `selected_field_ids` identical to the pre-verified 9-field set for both conditions. All 20 calls: `restricted_field_present = false`. 0 technical failures, 0 retries, 0 substitutions.

## 8. Comparison with Phase 20

| Metric | Phase 20 (n=5 each) | Phase 21 (n=10 each) |
|---|---|---|
| B CORRECT rate | 2/5 (40%) | 2/10 (20%) |
| LA CORRECT rate | 4/5 (80%) | 6/10 (60%) |
| B WRONG rate | 3/5 (60%) | 8/10 (80%) |
| LA WRONG rate | 0/5 (0%) | 3/10 (30%) |

The direction is consistent across both runs (LA outperforms B), but the absolute rates moved in both conditions: B performed worse in this run (2/10 vs. 2/5) and LA also performed somewhat worse (6/10 vs. 4/5), while the **gap between LA and B widened in absolute call count** (this run: 4-call gap in a 10-call sample; Phase 20: 2-call gap in a 5-call sample) even as LA's own rate dropped. This is reported as observed, without smoothing the two runs into a single combined rate — each remains its own sample.

## 9. Treatment of H1 and H3

- **H1 (age-only labeling continues to show a higher CORRECT rate than B)**: **the direction reproduces.** LA's CORRECT rate (6/10) is three times B's (2/10) in this independent sample, matching Phase 20's qualitative direction (LA > B). This is evidence consistent with H1, but per instruction, **this does not prove H1** — no probability of causation is calculated, and no claim beyond "the direction reproduced in a second independent sample" is made.
- **H3 (Phase 20 difference was primarily generation variance, does not reproduce reliably)**: **not supported as the primary explanation**, since the LA > B direction held again with a comparable or larger gap. However, H3 is not fully excluded: both conditions' individual rates shifted between runs (B dropped, LA dropped less), showing real within-condition variance exists — meaning some of the observed gap in either run could still partly reflect sampling noise even though the direction has now held twice.
- **Neither hypothesis is declared "confirmed."** The evidence across two independent small samples (Phase 20 n=5/5, Phase 21 n=10/10) consistently favors LA over B in this scenario, which is the specific, narrow claim this phase set out to test — no broader causal or architectural claim is made.

## 10. Treatment of the 27−24=3 alternative

**Neither confirmed nor refuted.** No response in any of the 20 calls references "24" explicitly. A new observation from this run, reported without interpretation as proof either way: B's WRONG answers in this run used a wider variety of incorrect numbers than previously seen — "five years" (calls 2, 6), "three years" (calls 3, 5, 9), and "two years" (calls 4, 8, 10) — rather than clustering on "three years" (the exact 27−24 result) as the dominant error. LA's WRONG answers likewise varied ("four years" ×2, "three years" ×1). This diversity of wrong numbers is not what a strict 27−24=3-anchoring-only explanation would predict if it were the sole mechanism (it would predict "three" as the dominant or exclusive wrong answer); it is more consistent with B (and, to a lesser extent, LA) producing an unreliable/guessed duration in general when the derivation is not clearly grounded — but this is offered as an observation, not a conclusion, since the specific cognitive mechanism behind any given wrong number cannot be determined from response text alone.

## 11. Treatment of the Phase 20 B failure mode

The Phase 20 call-2 failure mode (a wrong duration compounded by a hometown/current-city contradiction — describing herself as having "landed in Nashik" rather than Pune) **did not recur in any of the 10 B calls in this run.** All 10 B responses in this run correctly identify Pune/Kothrud as where she currently lives and Nashik as her hometown when either is mentioned (calls 1, 5, 7 explicitly reference Kothrud/Pune correctly; no call reverses the two cities). This failure mode is not merged into the age-labeling hypothesis — it remains recorded as a separate, apparently non-recurring (n=1 of 15 total B calls across Phase 20+21) observation, consistent with the instruction not to connect it to the labeling hypothesis absent direct evidence.

## 12. Limitations

- Two independent small samples (n=5 and n=10 per condition) — informative for direction, not for a precise effect size.
- Single scenario, single skill, single persona/engine version, single model.
- Direct (non-blinded) scoring by the same evaluator who ran the calls, consistent with Phases 14/16/17/19/20's established approach for small mechanistic checks.
- B's own CORRECT rate varies substantially across runs (40% in Phase 20, 20% in Phase 21) — this scenario's B behavior appears to have meaningfully high variance in absolute terms, which should temper any expectation of a fixed "true" B rate.
- The wide variety of specific wrong numbers B produces (two/three/four/five years, in addition to the previously-seen "three") is a new observation not previously catalogued at this resolution; it is reported here (§10) but not further investigated, consistent with the instruction not to broaden this phase.

## 13. Whether the evidence supports a further experiment

The direction of the LA-over-B effect has now reproduced across two independent samples (Phase 20 and Phase 21), which is a meaningfully stronger evidentiary position than either sample alone. A larger-N replication, or a test of whether an age-only-style single-field label generalizes to a different scalar/scenario pair, would be reasonable next questions if this line of investigation continues — but per the standing instruction, **no such follow-up is undertaken here**, and Phase 22 is not initiated automatically.

## 14. Explicit statement: no production recommendation

**No production recommendation is being made.** This document does not claim that scalar labeling should be adopted in `PersonaContextRenderer.kt`, that any renderer or formatting change should be made, or that this finding generalizes beyond the single scenario tested across Phases 14–21. It establishes only that a specific, narrow, twice-observed pattern (LA > B on this one scenario) has now been seen in two independent small samples.

---

## Exact API-call count

**20.**

## Files created

- `docs/Persona update POC/PHASE21_CONFIRMATORY_DESIGN.md`
- `docs/Persona update POC/PHASE21_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE21_CONFIRMATORY_RESULTS.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase21ConfirmatoryExecution()` only (additive, no changes to any prior test method).

## Not modified

`PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–20 document, any historical CSV.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 20) was modified. Exactly 20 API calls were made, in the predetermined fixed order, none retried, none substituted. Phase 22 is not initiated by this document.
