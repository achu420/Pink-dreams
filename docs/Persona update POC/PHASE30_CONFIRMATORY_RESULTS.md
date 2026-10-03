# Phase 30 — Confirmatory Sister-Scenario Execution: Results

**Exactly 118 real API calls made, as authorized (59×B, 59×LA), in the predetermined INTERLEAVED order fixed before execution. All 118 succeeded. All 118 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, engine, or historical Phase 10–29 artifact was modified before, during, or after execution. Phase 22's original 10-call sample was kept entirely separate and is not pooled into this analysis.

---

## OBSERVED

### Pre-flight audit (zero-cost, all PASS)

1. `identity.age` selected. 2. `life_context.family` selected. 3. Selected field IDs identical to Phase 22/27 (`{identity.name, identity.age, identity.hometown, identity.current_city, life_context.occupation, life_context.education, personality.core_traits, life_context.family}`). 4. Ground truth (Anjali=29, own age=27) present in B payload. 5. No restricted field in B/LA. 6. LA differs from B by exactly `27` → `Age: 27`. 7. Deterministic provider output on repeat.

No STOP condition was triggered anywhere in the run.

### Predetermined execution order

Fixed before any call: `B, LA, B, LA, ... (59 pairs, 118 calls)`. Not altered after seeing any response. No call was retried.

### Aggregate scoring (arithmetic axis, primary metric)

| Condition | N | CORRECT | WRONG | AMBIGUOUS | CORRECT rate |
|---|---|---:|---:|---:|---:|
| B | 59 | 46 | 12 | 1 | 78.0% |
| LA | 59 | 55 | 4 | 0 | 93.2% |

**Observed gap: 15.2 percentage points (LA − B).**

### Non-CORRECT responses, listed individually (the remaining 46/59 and 55/59 CORRECT calls are summarized above; full verbatim text for all 118 calls is in `PHASE30_EXECUTIONS.csv`)

**B — WRONG (12 of 59)**: call 1 ("five years"), call 9 ("Three years," despite 29/27 both stated), call 11 ("four years," despite 29/27 both stated), call 15 ("four years older... she's 29"), call 17 ("four years older," no ages restated), call 21 ("four years older"), call 35 ("Six years between Anjali and me"), call 39 ("nine years older... she's twenty-nine" — internally inconsistent with the established age 27), call 73 ("Three years — Anjali's 29, I'm 27"), call 93 ("four years older than me — she's 29"), call 101 ("Three years — Anjali is 29, I'm 27"), call 105 ("Three years — Anjali's 29, I'm 27").

**B — AMBIGUOUS (1 of 59)**: call 19 — correctly states all three ages (29, 27, 24) but never commits to a specific gap figure for Anjali specifically ("we're all within a few years of each other").

**LA — WRONG (4 of 59)**: call 24 ("Three years — Anjali is 29 and I'm 27"), call 42 ("Anjali's four years older than me"), call 58 ("Three years — Anjali's twenty-nine"), call 116 ("Three years — she's twenty-nine").

### Entity-binding (tracked independently, never merged into the arithmetic score)

**118/118 CORRECT in both conditions.** Every response correctly identifies Anjali as the sister in question. Sixteen responses (8, 10, 12, 19, 22, 25, 26, 46, 62, 72, 75, 78, 82, 113, 118, plus B calls 17/19/25/75/113 already counted) additionally and correctly mention Arjun (age 24) as the separate younger brother — several (calls 26, 72) even correctly compute the *separate, different* Simran-Arjun gap (3 years) alongside the Simran-Anjali gap (2 years), without confusing the two. No response used Arjun's age in place of Anjali's, or vice versa, anywhere in the 118-call sample.

### Independent failure modes (tracked separately, per instruction)

**None observed in this run.** Unlike Phase 24 (Pittsburgh fabrication) or Phase 26 (hometown/current-city contradiction; fabricated move-age), no response in this 118-call sample introduced a factual contradiction, fabricated an unsupported fact, or confused any established persona detail. Every WRONG response's error is confined to the single derived-duration figure — the ages themselves (29, 27, and where mentioned, 24) are stated correctly even in WRONG responses (11 of 12 B-WRONG calls and all 4 LA-WRONG calls explicitly restate at least one correct age alongside the incorrect gap).

### `24` observation

Arjun's age (24) appears in numerous responses, always correctly attributed and never substituted into the Anjali-gap calculation. Per the pre-registered rule, this is reported as an observed text property only and bears no relationship to the separate, unresolved Phase 15 27−24=3 hypothesis (which concerns a different scenario and a different number pair, 27 and 24 in the Pune-timeline context, not 29 and 27 here).

### Statistical analysis (pre-registered plan, applied without modification)

2×2 contingency table (CORRECT vs. NOT-CORRECT, where NOT-CORRECT = WRONG + AMBIGUOUS):

| | CORRECT | NOT-CORRECT | Total |
|---|---:|---:|---:|
| B | 46 | 13 | 59 |
| LA | 55 | 4 | 59 |
| Total | 101 | 17 | 118 |

**Two-sided Fisher's exact test**, computed via exact hypergeometric arithmetic (arbitrary-precision integers, not a normal approximation): with N=118, K=101 (total correct), n=59 (B group size), observed a=46, the sum of all hypergeometric probabilities no greater than the observed table's probability yields:

**p ≈ 0.0336**

This is below the pre-registered α=0.05 threshold.

**Observed percentage-point difference: 15.2 points (93.2% − 78.0%).**

---

## STATISTICALLY SUPPORTED

Per the pre-registered Phase 26/27 decision framework, applied here without modification:

| Result | Classification |
|---|---|
| p < 0.05 and LA > B | **FOR H1** |

**p ≈ 0.0336 < 0.05, and LA (93.2%) > B (78.0%). This result is classified FOR H1.**

This is the **second** independent scenario in this program to cross the pre-registered statistical-significance threshold for a B-vs-LA difference — the Pune-timeline scenario (Phase 26, p≈0.00036) and now the sister age-gap scenario (Phase 30, p≈0.0336). Phase 27's smaller sample (n=20/group) on this same scenario had not reached significance (p≈0.235); the larger, power-calculated sample (n=59/group) used here did.

## INTERPRETATION

- The direction (LA≥B) has now held in **eight** independent B-vs-(LA or L) comparisons across two scenario types (Phases 19, 20×2, 21, 22, 24×2, 26, 27, and now 30), with zero reversals.
- **Two of these comparisons (Phase 26, Phase 30) are now statistically significant**, each on a different scenario. This is a materially stronger evidentiary position than existed after Phase 28/29, where only one scenario had crossed significance and the second (Phase 27) was inconclusive.
- This does **not**, by itself, establish that the effect is universal or that it will replicate on every future scenario — it establishes that, at adequately powered sample sizes, both of the two scenario types tested in this program have now shown a statistically detectable B-vs-LA difference in the same direction.
- B's own baseline rate on the sister scenario across three samples (Phase 22: 80%, Phase 27: 70%, Phase 30: 78.0%) is now considerably more stable than its Pune-timeline counterpart (20–70% range across five samples) — this is an observed pattern, not a claim about why it differs, and is consistent with (though does not prove) the sister scenario being an intrinsically easier, lower-variance task for B.

## STILL NOT ESTABLISHED

- **Causality**: a significant Fisher's exact test rejects the equal-rates null hypothesis for this specific sample; it does not identify labeling as the causal mechanism, as distinct from some other unidentified property correlated with the LA condition. No experiment in this program has isolated causal mechanism from correlation beyond controlling the one formatting variable itself.
- **Whether the boundary's "more than two scenario types" requirement is met**: it is not. Two scenario types have now both crossed statistical significance, which is a stronger position than the boundary anticipated failing to meet, but the literal count of scenario types tested remains two, not more than two. This document does not redefine the boundary to call two "enough" — see the explicit boundary comparison below.
- **Generalization beyond these two scenarios, this one persona, or these two skills** (`general_chat`, `about`).
- **Resolution of the 27−24 alternative**: unchanged and unrelated to this scenario's own numbers.
- **LA vs. L**: not tested in this design (L was excluded per Phase 26/27/29's methodology); no new evidence on this question.
- **Production suitability**: no claim is made that LA, or any labeling change, should be adopted in `PersonaContextRenderer.kt` or anywhere in production.

---

## Comparison against the existing Phase 23/25/28 decision boundary (applied exactly as written)

Per `PHASE23_DECISION_BOUNDARY.md` §C and reaffirmed unchanged in `PHASE25_DECISION_AUDIT.md` and `PHASE28_FINAL_EVIDENCE_BOUNDARY.md` §13: a production/engineering decision requires **a stable, reproducible effect at a magnitude exceeding B's own demonstrated baseline variance, replicated across more than two scenario types.**

**This result does not, on its own literal terms, cross that boundary** — exactly two scenario types have been tested in this entire program (Pune timeline, sister age-gap), and the boundary explicitly requires *more than two*. **It substantially strengthens the case relative to Phase 28's assessment**, because both of the two tested scenario types have now independently reached statistical significance (Phase 26: p≈0.00036; Phase 30: p≈0.0336) — a materially stronger position than the "one significant, one inconclusive" state Phase 28/29 evaluated. **This document does not redefine the boundary to treat "two significant results" as satisfying "more than two scenario types."** The boundary's literal wording is applied as-is, without loosening it in light of this favorable result, consistent with the discipline established in Phase 28 §14 (the boundary is not to be revised merely because new evidence arrives) and the explicit instruction not to automatically call a significant result a production decision.

---

## Exact API-call count

**118.**

## Files created

- `docs/Persona update POC/PHASE30_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE30_CONFIRMATORY_RESULTS.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase30ConfirmatoryExecution()` only (additive, no changes to any prior test method).

## Not modified

`PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–29 document, any historical CSV (including `PHASE22_EXECUTIONS.csv` and `PHASE27_EXECUTIONS.csv`, which remain untouched and unmerged with this phase's data).

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 29) was modified. Exactly 118 API calls were made, in the predetermined interleaved order, none retried or replaced after observing results. No new scenario was introduced. No production recommendation is made. Phase 31 is not started, per instruction.
