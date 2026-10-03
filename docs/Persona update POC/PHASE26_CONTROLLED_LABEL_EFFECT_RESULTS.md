# Phase 26 — Controlled Label-Effect Resolution: Results

**Exactly 40 real API calls made, as authorized (20×B, 20×LA), in the predetermined INTERLEAVED order fixed before execution. All 40 succeeded. All 40 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, engine, or historical Phase 10–25 artifact was modified before, during, or after execution.

---

## Objective

Determine whether the B-vs-LA difference on the Pune-timeline scenario is statistically distinguishable from ordinary generation variance, per the pre-registered design in `PHASE26_CONTROLLED_LABEL_EFFECT_DESIGN.md`, without deviating from its thresholds after seeing results.

## Pre-flight verification

Re-verified live, zero cost, immediately before the first call (all PASS): selected field IDs identical to Phase 14/16/19/20/21/24/26-Stage 1; no restricted field in B/LA; LA differs from B by exactly the single age-label line. **One infrastructure failure occurred and is disclosed here**: the first attempt to run this test failed during Kotlin compilation (a corrupted incremental-compiler cache from an earlier overlapping `gradle` invocation), before any test method executed and before any API call was made — confirmed by the absence of any `PHASE26_EXECUTIONS.csv` output from that attempt. Per the pre-registered failure-handling rule, this was documented rather than silently worked around: Gradle daemons were stopped (`gradle --stop`) and the identical, unmodified test was re-run as a clean, single sequential invocation, which then completed successfully. No code, scenario, threshold, or scoring rule was altered between the failed attempt and the successful one — the failure was purely a build-tooling infrastructure issue that occurred prior to any experimental data collection.

---

## OBSERVED

### Predetermined execution order

Fixed before any call, interleaved as authorized: `B, LA, B, LA, ... (20 pairs, 40 calls)`. Not altered after seeing any response.

### Raw per-call scoring

| # | Cond. | Stated duration | Hometown/city factuality | Other contradiction | Duration score |
|---|---|---|---|---|---|
| 1 | B | "about three years" | correct | — | WRONG |
| 2 | LA | "about six years" | correct | — | CORRECT |
| 3 | B | "about six years" | correct | — | CORRECT |
| 4 | LA | "just over six years" | correct | — | CORRECT |
| 5 | B | "six years, off and on" | correct | — | CORRECT |
| 6 | LA | "about six years" | correct | — | CORRECT |
| 7 | B | "about three years ago" | correct | — | WRONG |
| 8 | LA | "about six years" | correct | — | CORRECT |
| 9 | B | "just over three years" | correct | — | WRONG |
| 10 | LA | "just about six years" | correct | — | CORRECT |
| 11 | B | "just over three years" | correct | — | WRONG |
| 12 | LA | "about six years" | correct | — | CORRECT |
| 13 | B | "about six years" | correct | — | CORRECT |
| 14 | LA | "about six years" | correct | — | CORRECT |
| 15 | B | "about three years ago" | correct | — | WRONG |
| 16 | LA | "just about six years" | correct | — | CORRECT |
| 17 | B | "about three years back" | correct | — | WRONG |
| 18 | LA | "about six years" | correct | — | CORRECT |
| 19 | B | "a while" (no number; claims Nashik as "here") | **contradiction** (treats hometown Nashik as the city in question) | — | AMBIGUOUS |
| 20 | LA | "about six years" | correct | — | CORRECT |
| 21 | B | "little over three years" | correct | — | WRONG |
| 22 | LA | "a few years" (no number) | correct | — | AMBIGUOUS |
| 23 | B | "about three years" | correct | — | WRONG |
| 24 | LA | "almost seven years" | correct | — | WRONG |
| 25 | B | "just over three years" | correct | — | WRONG |
| 26 | LA | "a while" (no number) | correct | — | AMBIGUOUS |
| 27 | B | "about three years" | correct | — | WRONG |
| 28 | LA | "about two years" | correct | — | WRONG |
| 29 | B | "about six years back" | correct | — | CORRECT |
| 30 | LA | "just about six years" | correct | — | CORRECT |
| 31 | B | "about three years ago" | correct | — | WRONG |
| 32 | LA | "about six years" | correct | — | CORRECT |
| 33 | B | "about nine years" (also states moved at **eighteen**, not 21) | correct | **contradiction** (fabricated move-age, 18 vs. established 21) | WRONG |
| 34 | LA | "about six years ago" | correct | — | CORRECT |
| 35 | B | "about three years" | correct | — | WRONG |
| 36 | LA | "about six years" | correct | — | CORRECT |
| 37 | B | "little over three years" | correct | — | WRONG |
| 38 | LA | "coming up on six years" | correct | — | CORRECT |
| 39 | B | "almost three years" | correct | — | WRONG |
| 40 | LA | "about six years" | correct | — | CORRECT |

Full verbatim text for all 40 calls is in `PHASE26_EXECUTIONS.csv`, including the programmatically-computed observable flags (`has_27`, `has_21`, `has_24`, `has_six`, `has_three`, `other_duration_hint`).

**Note on the AMBIGUOUS reclassification convention used here**: unlike Phase 24 (which scored a similar Pittsburgh-fabrication call as an overall WRONG), Phase 26's pre-registered design (§13 of the design document) explicitly commits to scoring the duration axis and the factuality axis independently. Call 19's duration is scored AMBIGUOUS (no explicit number stated) and its factuality is scored as a contradiction, tracked on its own axis — the two are not merged into a single downgraded score.

### Aggregate counts (duration axis, primary metric)

| Condition | N | CORRECT | WRONG | AMBIGUOUS | CORRECT rate |
|---|---|---:|---:|---:|---:|
| B | 20 | 4 | 15 | 1 | 20% |
| LA | 20 | 16 | 2 | 2 | 80% |

**Observed gap: 60 percentage points (LA − B).**

### Secondary axis: hometown/current-city factuality

| Condition | Contradictions | Rate |
|---|---:|---:|
| B | 1 (call 19) | 5% |
| LA | 0 | 0% |

### Secondary axis: other factual contradictions (not hometown/city)

| Condition | Contradictions | Detail |
|---|---:|---|
| B | 1 (call 33) | States she moved to Pune at "eighteen," contradicting the established "moved at 21" fact used consistently in every other call and in the persona master document |
| LA | 0 | — |

### `24` observation

**"24" does not appear in any of the 40 responses**, and "27" does not appear in any of the 40 responses either. Per the pre-registered rule, this is reported as an observed text property only — it is **not** treated as evidence that 24 (or 27) was not used internally.

### Statistical analysis (pre-registered plan, applied without modification)

2×2 contingency table (CORRECT vs. NOT-CORRECT, where NOT-CORRECT = WRONG + AMBIGUOUS, per the pre-registered outcome definition):

| | CORRECT | NOT-CORRECT | Total |
|---|---:|---:|---:|
| B | 4 | 16 | 20 |
| LA | 16 | 4 | 20 |
| Total | 20 | 20 | 40 |

**Two-sided Fisher's exact test**: with both margins fixed at 20/20/20/20 and N=40, the hypergeometric probability of the observed table (and every table at least as extreme) sums to **p ≈ 0.00036** (computed via the exact hypergeometric formula `P(a) = C(20,a)² / C(40,20)`, summed for a=0..4 and doubled by the distribution's exact symmetry under equal margins). This is far below the pre-registered α=0.05 threshold.

**Observed percentage-point difference: 60 points (80% − 20%).**

---

## INTERPRETATION (applying the pre-registered Phase 26 decision rules, unmodified)

| Result | Classification |
|---|---|
| p < 0.05 and LA > B | **FOR H1** |

**p = 0.00036 < 0.05, and LA (80%) > B (20%). Per the pre-registered rule, this result is classified FOR H1.**

This is the first result in the entire Phase 14–26 evidence chain to cross the pre-registered statistical threshold at an adequately-sized, equal-N, interleaved sample. It differs from every prior B-vs-LA/L comparison (Phases 19–22, 24) in that those samples were either too small (n=5–10 per condition) or blocked rather than interleaved, and none had a pre-registered statistical test applied to them at the time.

**This result does not, by itself, resolve H3.** B's own rate in this sample (20%) sits at the low end of its historically observed range (20–70% across Phases 19, 20, 21, 24), and LA's rate (80%) sits at the high end of its own historical range (60–90% across Phases 20, 21, 22, 24). The fact that this particular paired, equal-N, interleaved sample drew B from its historical low end and LA from its historical high end simultaneously is itself compatible with either explanation under consideration: (a) a real effect whose magnitude this sample estimated accurately, or (b) two highly variable underlying processes that, on this occasion, drew from opposite ends of their respective ranges at the same time. **The statistical test answers a narrower question than "is there a real effect" — it answers "is this specific sample's gap larger than chance alone would produce under the null hypothesis that B and LA have equal true rates."** The test's significance means the null hypothesis (equal true rates) is not well supported by this data; it does not, on its own, rule out that both conditions have substantial, possibly overlapping variance around different central tendencies.

## NOT ESTABLISHED

- **Causality**: this result does not establish that scalar labeling *causes* the observed improvement, only that a statistical test rejects the equal-rates null hypothesis for this sample. No experiment in this program manipulates anything other than payload formatting, so a causal mechanism claim is not licensed by a significance test alone without a designed causal-inference structure this program has not built.
- **Internal model computation**: nothing about *why* B or LA produced the responses they did is established. The absence of "24"/"27" and presence of "three"/"six" remain observed text properties only.
- **Resolution of the 27−24 alternative**: unchanged — neither confirmed nor refuted. Call 33's fabricated "eighteen" (not 21, not 24) further illustrates that B's errors are not confined to a single numeric anchor.
- **Generalization beyond this scenario**: this experiment used only the Pune-timeline scenario, per instruction (H3/scenario-difficulty was explicitly out of scope for this design). Phase 22's sister-age-gap scenario (a 10-point gap) remains the only other tested scenario and is not re-litigated here.
- **Production suitability**: no claim is made that LA, or any labeling change, should be adopted in `PersonaContextRenderer.kt` or anywhere in production. This experiment answers a statistical question about this one scenario's B-vs-LA comparison, not a deployment question.
- **That B or LA's true rate equals this sample's observed rate** (20%/80%): both remain estimates from a single 20-call sample each; the historical range for each condition is wider than this single sample alone would suggest.

---

## Does this result cross the existing decision boundary?

**Partially, and specifically for the narrow statistical question this phase was designed to answer.** Per `PHASE23_DECISION_BOUNDARY.md`/`PHASE25_DECISION_AUDIT.md`, the broader production-decision boundary requires evidence of a stable, reproducible effect at a magnitude exceeding B's own demonstrated baseline variance, replicated across more than two scenario types. This phase **crosses the statistical-significance boundary for the Pune-timeline scenario specifically** (the first pre-registered, adequately-interleaved, hypothesis-tested sample in the program to do so) — this is a genuine advance over Phase 25's state, which found the evidence had not yet crossed a decision boundary. **It does not, by itself, cross the broader production-decision boundary**, because: (a) it is a single statistical test on a single scenario, not a replicated finding across scenario types (the requirement Phase 23/25 set for a production decision); (b) it does not address causality, only the equal-rates null hypothesis; (c) the sister-age-gap scenario (Phase 22) still shows only a 10-point gap, and this phase's design explicitly did not attempt to reconcile that difference (H3/scenario-difficulty was out of scope here). **The honest summary: the statistical evidence for a real B-vs-LA difference on this one scenario is now much stronger than before this phase; the evidence for a general, production-relevant labeling effect remains where Phase 23/25 left it.**

---

## Exact API-call count

**40.**

## Files created

- `docs/Persona update POC/PHASE26_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE26_CONTROLLED_LABEL_EFFECT_RESULTS.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase26Stage2Execution()` only (additive, no changes to any prior test method).

## Not modified

`PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–25 document, any historical CSV.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 25) was modified. Exactly 40 API calls were made, in the predetermined interleaved order, none retried for a semantic reason (the one retry was a pre-call infrastructure/compile failure, disclosed above, with zero API calls made in the failed attempt). No production recommendation is made. Phase 27 is not started, per instruction.
