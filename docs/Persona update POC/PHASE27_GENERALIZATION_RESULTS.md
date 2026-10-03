# Phase 27 — Independent Generalization Boundary: Results

**Exactly 40 real API calls made, as authorized (20×B, 20×LA), in the predetermined INTERLEAVED order fixed before execution. All 40 succeeded. All 40 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, engine, or historical Phase 10–26 artifact was modified before, during, or after execution. Phase 22's 10-call sample was kept entirely separate and is not merged into this analysis.

---

## OBSERVED

### Predetermined execution order

Fixed before any call, interleaved as authorized: `B, LA, B, LA, ... (20 pairs, 40 calls)`. Not altered after seeing any response.

### Pre-flight verification

Re-verified live, zero cost, immediately before the first call (all PASS): selected field IDs identical to Phase 22/27-Stage 1 (`{identity.name, identity.age, identity.hometown, identity.current_city, life_context.occupation, life_context.education, personality.core_traits, life_context.family}`); no restricted field in B/LA; LA differs from B by exactly the single age-label line. No infrastructure failure occurred in this run.

### Raw per-call scoring

| # | Cond. | Stated gap | Entity | Score |
|---|---|---|---|---|
| 1 | B | "two years" (29, 27) | Anjali | CORRECT |
| 2 | LA | "two years apart" (29, 27) | Anjali | CORRECT |
| 3 | B | "two years apart" (29, 27) | Anjali | CORRECT |
| 4 | LA | "two years between us" (29, 27) | Anjali | CORRECT |
| 5 | B | "only two years older" (29) | Anjali | CORRECT |
| 6 | LA | "Three years" (29, 27 stated, but wrong gap) | Anjali | WRONG |
| 7 | B | "two years older" (29, 27) | Anjali | CORRECT |
| 8 | LA | "just two years older" (29) | Anjali | CORRECT |
| 9 | B | "two years apart" (29, 27) | Anjali | CORRECT |
| 10 | LA | "two years apart" (29, 27) | Anjali | CORRECT |
| 11 | B | "two years older" (29) | Anjali | CORRECT |
| 12 | LA | "Just two years" (29, 27) | Anjali | CORRECT |
| 13 | B | "five years older" (no ages restated) | Anjali | WRONG |
| 14 | LA | "just two years older" (29) | Anjali | CORRECT |
| 15 | B | "Two years" (29) | Anjali | CORRECT |
| 16 | LA | "just two years older" (29; Arjun=24 mentioned separately, correctly) | Anjali | CORRECT |
| 17 | B | "four years older" (29 stated, wrong gap) | Anjali | WRONG |
| 18 | LA | "just about two years" (29, 27) | Anjali | CORRECT |
| 19 | B | "Three years" (29, 27 stated, but wrong gap) | Anjali | WRONG |
| 20 | LA | "two years older" (29) | Anjali | CORRECT |
| 21 | B | "two years apart" (29, 27; Arjun=24 mentioned separately, correctly) | Anjali | CORRECT |
| 22 | LA | "two years older" (29) | Anjali | CORRECT |
| 23 | B | "two years apart" (29, 27) | Anjali | CORRECT |
| 24 | LA | "two years between us" (29, 27) | Anjali | CORRECT |
| 25 | B | "two years apart" (29, 27) | Anjali | CORRECT |
| 26 | LA | "two years older" (29) | Anjali | CORRECT |
| 27 | B | "two years older" (29) | Anjali | CORRECT |
| 28 | LA | "just two years" (29, 27) | Anjali | CORRECT |
| 29 | B | "two years apart" (29, 27) | Anjali | CORRECT |
| 30 | LA | "two-year gap" (29, 27; Arjun mentioned separately, correctly) | Anjali | CORRECT |
| 31 | B | "just two years older" (29) | Anjali | CORRECT |
| 32 | LA | "about two years apart" (29) | Anjali | CORRECT |
| 33 | B | "four years older" (29 stated, wrong gap) | Anjali | WRONG |
| 34 | LA | "Three years" (29, 27 stated, but wrong gap) | Anjali | WRONG |
| 35 | B | "three years older" (29) | Anjali | WRONG |
| 36 | LA | "two years older" (no ages restated) | Anjali | CORRECT |
| 37 | B | "four years older" (29, 27 stated, but wrong gap) | Anjali | WRONG |
| 38 | LA | "two-year gap" (29, 27; Arjun=24 mentioned separately, correctly) | Anjali | CORRECT |
| 39 | B | "two years older" (29) | Anjali | CORRECT |
| 40 | LA | "two years older" (no ages restated) | Anjali | CORRECT |

Full verbatim text for all 40 calls is in `PHASE27_EXECUTIONS.csv`.

### Entity-binding (tracked independently, never merged into the arithmetic score)

**40/40 CORRECT in both conditions.** Every response correctly identifies Anjali as the sister in question. Four responses (calls 16, 21, 30, 38) additionally and correctly mention Arjun (the younger brother, age 24) without any conflation between the two siblings' ages. No response used Arjun's age in place of Anjali's, or vice versa.

### Aggregate counts (arithmetic axis, primary metric)

| Condition | N | CORRECT | WRONG | AMBIGUOUS | CORRECT rate |
|---|---|---:|---:|---:|---:|
| B | 20 | 14 | 6 | 0 | 70% |
| LA | 20 | 18 | 2 | 0 | 90% |

**Observed gap: 20 percentage points (LA − B).**

Note on B's WRONG responses: five of B's six wrong answers (calls 17, 19, 33, 35, 37) explicitly state Anjali's age as 29 (three of them, calls 19/33/37, also explicitly restate "27" for Simran) immediately alongside an incorrect gap ("three years," "four years," "five years") — an internally inconsistent pattern structurally identical to the Pune-timeline scenario's classic "three years despite 27 and 21 both present" error (Phase 14 onward). This is reported as an observed pattern, not attributed to any specific cause.

### `24` observation

Arjun's age (24) appears in four responses (16, 21, 30, 38), always correctly attributed to Arjun and never substituted for Anjali's age or used in the sister's gap calculation. Per the pre-registered rule, its presence or absence is not used to resolve the Phase 15 27−24 hypothesis, which concerns a different scenario (the Pune timeline) and a different pair of numbers (27 and 24, not 29 and 27) — this scenario's own numbers are unrelated to that specific alternative explanation, and no attempt is made here to connect them.

### Statistical analysis (pre-registered plan, applied without modification)

2×2 contingency table (CORRECT vs. NOT-CORRECT):

| | CORRECT | NOT-CORRECT | Total |
|---|---:|---:|---:|
| B | 14 | 6 | 20 |
| LA | 18 | 2 | 20 |
| Total | 32 | 8 | 40 |

**Two-sided Fisher's exact test**: using the exact hypergeometric distribution (N=40, K=32, n=20, observed a=14), the sum of probabilities no greater than the observed table's probability (across the valid support a=12..20, since C(8, 20−a)=0 for a<12) yields **p ≈ 0.235**. This is well above the pre-registered α=0.05 threshold.

**Observed percentage-point difference: 20 points.**

---

## INTERPRETATION (applying the pre-registered Phase 26/27 decision rules, unmodified)

| Result | Classification |
|---|---|
| p ≥ 0.05 with a material difference (>15 points, per the Phase 26 threshold) | **INCONCLUSIVE** |

**p = 0.235 ≥ 0.05, and the observed gap (20 points) exceeds the 15-point threshold Phase 26 established for distinguishing "small/insubstantial" from "material."** Per the pre-registered rule, this result is classified **INCONCLUSIVE** — not evidence for generalization (H1), and not evidence against a clear generalization signal (H2), because the gap is too large to dismiss as negligible but the sample is too small (relative to this gap size) to reach statistical significance.

**The pre-registered caveat (Phase 27 Stage 1, §5) is directly relevant here and is applied exactly as stated in advance**: this scenario's B baseline in this larger, interleaved sample (70%) is lower than Phase 22's original small-sample estimate (80%), leaving more headroom than previously observed, and the resulting 20-point gap is in fact larger than Phase 22's original 10-point estimate. This INCONCLUSIVE result does **not** by itself establish that labeling has no effect on this scenario — it establishes that this specific 40-call sample, at this effect size, did not reach the pre-registered significance threshold, which is a different and narrower statement.

This result is also **not** compared or combined with Phase 22's separate 10-call sample in any way that would change either sample's own analysis, per instruction — both stand as independent observations of the same scenario, at different sample sizes and orderings, yielding different point estimates (80/90% in Phase 22 vs. 70/90% here) that are broadly consistent with each other (LA ≥ B in both) without either resolving the underlying question.

## NOT ESTABLISHED

- **Universal labeling benefit**: not established. This experiment adds a second scenario's inconclusive result to the evidence base; it neither confirms nor rules out a benefit.
- **Causality across contexts**: not established. Fisher's exact test evaluates whether an equal-rates null hypothesis is rejected for this sample; it says nothing about mechanism.
- **Effect magnitude across personas/skills**: not established. Only two scenarios (Pune timeline, sister age-gap), one persona, and two skills (`general_chat`, `about`) have been tested in this program.
- **27−24 resolution**: unchanged. This scenario's numbers (29, 27, 24-for-Arjun) are structurally unrelated to the specific 27−24=3 hypothesis about the Pune-timeline scenario, and this experiment does not bear on it either way.
- **LA vs. L**: not tested here — L is excluded from this design, per instruction, exactly as in Phase 26. No comparison between LA and full labeling exists for this scenario.
- **Production suitability**: no claim is made that LA, or any labeling change, should be adopted in production. This experiment answers a narrow statistical question about one scenario's B-vs-LA comparison at a specific sample size.

---

## Does Phase 27 provide independent evidence of generalization?

**No — not in the affirmative sense, and not in the negative sense either.** Phase 27 provides an **independent, larger, statistically-tested sample** of the sister-age-gap scenario that shows the same *direction* as every other B-vs-LA comparison in this program (LA ≥ B, now five scenarios/sessions in a row without reversal: Phase 20, 21, 22, 24, 26, and now 27), with a moderate (20-point) gap that is **directionally consistent with generalization but not statistically confirmed at this sample size**. This is meaningfully different from a null result — it is an underpowered-but-suggestive result, correctly classified INCONCLUSIVE per the pre-registered framework rather than forced into either "generalizes" or "doesn't generalize."

## Has the broader Phase 23/25 production-decision boundary been crossed?

**No.** The broader boundary (per `PHASE23_DECISION_BOUNDARY.md` and `PHASE25_DECISION_AUDIT.md`) requires a stable, reproducible effect at a magnitude exceeding B's own demonstrated baseline variance, replicated across more than two scenario types. Phase 26 crossed the narrow statistical-significance boundary for the Pune-timeline scenario specifically. Phase 27 does **not** replicate that statistical crossing on the second scenario — it produces a directionally consistent but non-significant (INCONCLUSIVE) result instead. **The evidence base now stands as**: one scenario (Pune timeline) with a statistically significant B-vs-LA difference (Phase 26); one scenario (sister age-gap) with a directionally consistent but statistically inconclusive difference (Phase 27, superseding-in-precision but not contradicting Phase 22's smaller-sample estimate). This is a genuine step forward from Phase 25's state (where even the Pune-timeline question was unresolved), but it does not by itself satisfy the "replicated across more than two scenario types" requirement the broader boundary specifies, and no production recommendation is made.

---

## Exact API-call count

**40.**

## Files created

- `docs/Persona update POC/PHASE27_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE27_GENERALIZATION_RESULTS.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase27Stage2Execution()` only (additive, no changes to any prior test method).

## Not modified

`PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–26 document, any historical CSV (including `PHASE22_EXECUTIONS.csv`, which remains untouched and unmerged with this phase's data).

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 26) was modified. Exactly 40 API calls were made, in the predetermined interleaved order, none retried or replaced after observing results. No new scenario was introduced. No production recommendation is made. Phase 28 is not started, per instruction.
