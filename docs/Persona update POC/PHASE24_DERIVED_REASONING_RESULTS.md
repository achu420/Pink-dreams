# Phase 24 — Derived-Reasoning Results (B / LA / L, 30 Calls)

**Exactly 30 real API calls made, as authorized (10×B, 10×LA, 10×L), in the predetermined order fixed before execution. All 30 succeeded. All 30 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, engine, or historical Phase 10–23 artifact was modified before, during, or after execution.

---

## 1. Objective

Determine whether the LA-over-B improvement on the Pune-timeline derivation task is reproducible in a three-way comparison against both B and the full-labeling condition L, while keeping the unresolved `27−24=3` explanation explicitly observable rather than assumed.

## 2. Hypotheses (pre-registered, per Phase 24 Stage 1)

- **H1 — age-label effect**: LA shows a repeatable improvement over B while payload-equivalent except for the intended age-label difference.
- **H2 — full-labeling benefit**: L outperforms LA in a meaningful, reproducible way. If `LA ≈ L > B`, this supports the narrower reading that age labeling alone may be sufficient.
- **H3 — generation variance**: if the three conditions converge substantially, or differences fluctuate without a consistent condition-level pattern, variance remains a plausible explanation.

## 3. Experimental conditions

- **B** — normal structured B (`ResolvedPersonaContextProvider`, variant "B").
- **LA** — B with only `identity.age` labeled (`AgeOnlyLabeledScalarDiagnosticProvider`, unchanged since Phase 20).
- **L** — existing Phase 16 fully-labeled diagnostic, all six top-level scalars labeled (`LabeledScalarDiagnosticProvider`, unchanged since Phase 16).

Scenario, unaltered: **"Been in this city long?"**, skill `general_chat`, with the same Phase 14/16/19/20/21 mapping fix applied (`identity.age` + `life_context.important_life_events` added to `general_chat`'s conditional fields).

## 4. Pre-flight verification

**Stage 1** (documented separately, zero API cost, all 12 checks PASS): identical persona/version/engine/skill/message; identical selected field IDs across B/LA/L; no restricted fields; LA differs from B by exactly the age-label line; L differs from LA by exactly the remaining five scalar labels; deterministic provider output; provider output verified verbatim in the captured request for all three conditions via `interceptGeneration`.

**Re-verified live immediately before Stage 2's first call** (all PASS): selected field IDs identical; no restricted field in B/LA/L; LA-vs-B and L-vs-LA diffs unchanged from Stage 1.

No STOP condition was triggered at any point.

## 5. Predetermined execution order

Fixed before any call: `List(10){"B"} + List(10){"LA"} + List(10){"L"}` — calls 1–10 = B, 11–20 = LA, 21–30 = L. Not altered after seeing any response. No call was retried. No scenario was added.

## 6. Raw results

### Observed (response text, objective/verifiable flags only — no reasoning inferred)

| # | Cond. | Stated duration (as written) | 27 present | 21 present | 24 present | Other numeric duration hint | Score |
|---|---|---|---|---|---|---|---|
| 1 | B | "Just about three years" | No | Yes | No | No | **WRONG** |
| 2 | B | "Just about nine years" (mentions "27" separately, unconnected to the duration claim) | Yes | No | No | Yes | **WRONG** |
| 3 | B | "a few years now" (no specific number) | No | Yes | No | Yes ("few") | **AMBIGUOUS** |
| 4 | B | "about six years now" | No | Yes | No | No | **CORRECT** |
| 5 | B | "about six years ago" | No | Yes | No | No | **CORRECT** |
| 6 | B | "about six years now" | No | Yes | No | No | **CORRECT** |
| 7 | B | "about six years now" | No | Yes | No | No | **CORRECT** |
| 8 | B | "about six years now" | No | Yes | No | No | **CORRECT** |
| 9 | B | "about six years now" | No | Yes | No | No | **CORRECT** |
| 10 | B | "about six years now" | No | Yes | No | No | **CORRECT** |
| 11 | LA | "about six years now" | No | Yes | No | No | **CORRECT** |
| 12 | LA | "Maybe five years now?" | No | Yes | No | Yes | **WRONG** |
| 13 | LA | "about six years now" | No | Yes | No | No | **CORRECT** |
| 14 | LA | "About six years now" | No | Yes | No | No | **CORRECT** |
| 15 | LA | "about six years now" | No | Yes | No | No | **CORRECT** |
| 16 | LA | "Just about six years now" | No | Yes | No | No | **CORRECT** |
| 17 | LA | "about six years" | No | Yes | No | No | **CORRECT** |
| 18 | LA | "about six years now" | No | Yes | No | No | **CORRECT** |
| 19 | LA | "about six years now" | No | Yes | No | No | **CORRECT** |
| 20 | LA | *no duration stated* — instead claims Nashik ("here") is home, "born and raised here" | No | No | No | No | **WRONG** (location/identity contradiction — see §11) |
| 21 | L | "about six years now" — but names **"Pittsburgh"** as the city, not Pune/Nashik | No | Yes | No | No | **WRONG** (fabrication — see §11) |
| 22 | L | "about six years now" | No | Yes | No | No | **CORRECT** |
| 23 | L | "about six years now" | No | Yes | No | No | **CORRECT** |
| 24 | L | "Three years now" | No | Yes | No | No | **WRONG** |
| 25 | L | "about six years now" | No | No | No | No | **CORRECT** |
| 26 | L | "about six years now" | No | Yes | No | No | **CORRECT** |
| 27 | L | "about six years now" | No | Yes | No | No | **CORRECT** |
| 28 | L | "about six years" | No | Yes | No | No | **CORRECT** |
| 29 | L | "about six years now" | No | Yes | No | No | **CORRECT** |
| 30 | L | "about six years now" | No | Yes | No | No | **CORRECT** |

**"24" appears in zero of the 30 responses.** Full verbatim text for every call is in `PHASE24_EXECUTIONS.csv`, including the programmatically-computed observable flags (`has_27`, `has_21`, `has_24`, `has_six`, `has_three`, `other_duration_hint`).

### C. Relationship usage (observed presentation only, not internal reasoning)

Across all 30 responses, **only one** (call 2, B) mentions "27" at all, and it does so as an unconnected aside about her current age/career, not as a visibly-shown step in a 27−21 subtraction. **No response in any of the 30 calls visibly shows the arithmetic step "27 minus 21 equals six."** Every CORRECT response instead simply asserts "moved at 21" and "about six years" side by side, without displaying a calculation. This is recorded as an observed presentation pattern only — it is not evidence about what the model computed internally, only about what it chose to display.

## 7. Accuracy by condition

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---|---|---|---|
| B | 10 | 7 | 2 | 1 |
| LA | 10 | 8 | 2 | 0 |
| L | 10 | 8 | 2 | 0 |

Raw distributions reported before interpretation, per instruction.

## 8. Duration/error-pattern analysis

- **B's errors**: "three years" (call 1, the recurring classic error), "nine years" (call 2, a novel wrong value not seen in any prior phase), "a few years" (call 3, avoidant/non-committal).
- **LA's errors**: "five years" (call 12, a novel wrong value), and a location/identity contradiction with no duration stated at all (call 20).
- **L's errors**: "three years" (call 24, the recurring classic error), and a severe fabrication naming an unrelated real city, "Pittsburgh" (call 21), while still stating a correct duration ("six years").
- **Across all three conditions, WRONG responses take a wider variety of forms in this run than in any prior phase**: two "novel" wrong durations (nine, five) never previously observed in this scenario, plus two qualitatively different failure types entirely unrelated to arithmetic (a hometown/city contradiction under LA, and a fabricated-city hallucination under L). Only 2 of the 6 total WRONG/AMBIGUOUS-adjacent responses across all three conditions reproduce the previously-catalogued "three years" pattern (calls 1 and 24).

## 9. `24` observation

**"24" does not appear in any of the 30 responses.** Per the pre-registered interpretation rule, **this absence is not treated as evidence that 24 was not used internally** — the experiment can observe output text only, not hidden computation. Two WRONG responses stated "three years" (calls 1, 24) — the exact value that would result from 27−24. Per the same rule, **this is not treated as evidence that 24 was used internally** either; "three years" is also just as consistent with an unanchored guess or a different miscalculation. The `27−24=3` hypothesis remains exactly as unresolved as it was after Phase 15 — this experiment neither advances nor retreats it, by design (no response in this run contains any visible trace, for or against).

## 10. Comparison with Phases 19–22

| Phase | Conditions | B CORRECT rate | LA/L CORRECT rate | Gap |
|---|---|---|---|---|
| 19 | B vs L | 3/5 (60%) | L: 5/5 (100%) | +40 pts |
| 20 | B vs LA vs L | 2/5 (40%) | LA: 4/5 (80%); L: 4/5 (80%) | +40 pts each |
| 21 | B vs LA | 2/10 (20%) | LA: 6/10 (60%) | +40 pts |
| 22 | B vs LA (sister scenario) | 8/10 (80%) | LA: 9/10 (90%) | +10 pts |
| **24** | **B vs LA vs L** | **7/10 (70%)** | **LA: 8/10 (80%); L: 8/10 (80%)** | **+10 pts each** |

**B's own baseline rate on the identical Pune-timeline scenario has now varied substantially across four independent samples: 60% (Phase 19), 40% (Phase 20), 20% (Phase 21), 70% (this phase)** — a range spanning 20 to 70 percentage points on the exact same scenario, mapping, and providers. This run's 10-point LA/L-over-B gap is much closer to Phase 22's tempered result than to Phase 20/21's larger gaps, even though this run uses the *same* scenario as Phase 20/21, not a different one. This is a materially new data point: **the gap size is not stable even on the identical scenario**, which was not previously demonstrated (Phase 22's smaller gap could previously be attributed to using an easier, different scenario; this phase shows a small gap can also occur on the harder, original scenario).

## 11. Alternative explanations

- **B's own instability (H3-consistent)**: with B ranging from 20% to 70% correct across four samples of the identical scenario, a substantial portion of any single run's LA/L-over-B gap could reflect which particular draw of B's own variance that run happened to sample, rather than a stable labeling effect.
- **The LA call-20 hometown/current-city contradiction**: this is a *new instance*, under LA this time (previously this error type appeared under B in Phase 20's call 2), of a location/identity confusion unrelated to arithmetic. It is recorded as its own category, not merged into the age-labeling hypothesis and not merged into the duration-error analysis — this suggests this specific failure mode is not tied to any one condition (it has now appeared under both B and LA in different runs), which is more consistent with a general, condition-independent generation risk than with anything caused by scalar labeling specifically.
- **The L call-21 "Pittsburgh" fabrication**: this is an entirely new failure type, never before observed in this program — the model correctly computed "six years" (the arithmetic-derivation task the whole Phase 14–24 chain investigates) while simultaneously fabricating an unrelated, real U.S. city name in place of Pune. This demonstrates that a condition (L) associated with generally higher CORRECT rates on the narrow duration question is **not immune to unrelated, severe fabrication** — the duration-correctness metric and overall factual reliability are not the same thing, and this response is correctly counted as WRONG despite its duration portion being numerically right, because scoring in this program has consistently treated any factual contradiction as disqualifying (matching the convention established for the Phase 20 hometown/city contradiction).
- **The 27−24=3 alternative**: remains an open, untested-by-this-experiment hypothesis, per §9.

## 12. What the experiment establishes

- The LA/L-over-B direction reproduced again in a third and fourth independent sample (this phase's LA arm and L arm both showed 8/10 vs. B's 7/10) — a fifth data point (counting Phase 19's L-only run) consistently in the same direction, with no reversal across any of the five samples run to date.
- The **magnitude** of that gap is not stable even on the identical scenario: this run's 10-point gap is far smaller than Phase 20/21's 40-point gaps, despite using the exact same scenario, mapping, and providers — demonstrating that gap size varies run-to-run on this one scenario, not only across different scenarios (as Phase 22 alone might have suggested).
- Two previously-uncatalogued failure modes were observed and are recorded as their own findings: a "nine years"/"five years" pair of novel wrong-duration values (neither matching the "three years" pattern nor any previously-seen value), a hometown/current-city contradiction recurring under a different condition (LA) than where it was first seen (B, Phase 20), and a severe, unrelated-city fabrication under L that coexisted with a numerically correct duration.
- No response in any of the 30 calls contains "24," and no response visibly displays the 27−21 subtraction step — both are reported as observed facts about the output text, not as evidence toward or against any hidden-computation hypothesis.

## 13. What remains unresolved

- Whether LA/L's reproduced direction reflects a real, stable age-labeling effect or is better explained by B's own demonstrated wide baseline variance (20–70% across four samples) — this experiment adds data consistent with either reading and does not decide between them.
- The `27−24=3` hypothesis — untouched by this experiment, exactly as before.
- Whether the hometown/current-city contradiction and the Pittsburgh fabrication are connected to any property of this scenario, this persona document, or are generic model-reliability risks independent of the labeling manipulation entirely — not investigated further here, per the instruction not to design another experiment in this same response.
- Whether a much larger sample (well beyond 10 per condition) would show H1, H2, or H3 as the better-supported reading — not addressed; this remains a small-sample, non-powered reproducibility check, as every phase in this chain has been.

## 14. Explicit production-decision boundary

**No production decision is made or implied by this document.** This experiment does not establish that LA, L, or any labeling change should be adopted in `PersonaContextRenderer.kt` or anywhere in production. It does not establish causation for the reproduced LA/L-over-B direction, and it does not resolve whether that direction reflects a real, exploitable effect or falls within the range of variance already demonstrated for B alone on this exact scenario. The two newly-observed failure modes (hometown/city contradiction, unrelated-city fabrication) are documented as open reliability observations, not attributed to any specific mechanism, and are not the basis for any recommendation.

---

## Exact API-call count

**30.**

## Files created

- `docs/Persona update POC/PHASE24_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE24_DERIVED_REASONING_RESULTS.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase24Stage1Verification()` and `phase24Stage2Execution()` only (additive, no changes to any prior test method).

## Not modified

`PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–23 document, any historical CSV.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 23) was modified. Exactly 30 API calls were made, in the predetermined fixed order, none retried, none substituted. Phase 25 is not started and no further experiment is designed in this document, per instruction.
