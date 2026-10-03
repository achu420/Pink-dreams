# Phase 22 — Generalization Experiment: Results

**Exactly 20 real API calls made, as authorized (10×B, 10×LA), in the predetermined order fixed before execution. All 20 succeeded. All 20 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, or engine artifact was modified before, during, or after execution. No Phase 10–21 document or historical CSV was modified.

---

## 1. Objective

Test whether the Phase 20/21 B-vs-LA observation (age-only labeling associated with a higher rate of correct derived-duration answers on the Pune-timeline scenario) generalizes to a second, independent derived-numeric scenario with a different fact pair, different derived value, and an added entity-binding requirement.

## 2. Exact scenario and ground truth

**Scenario:** *"Is there a big age gap between you and your sister in your family?"* — skill `about`, **pure baseline mapping** (no Phase 10E/13 fix of any kind applied).
**Ground truth:** Simran (`identity.age`) = 27; Anjali (older sister, in `life_context.family`) = 29. Correct derived age gap = **2 years**.

## 3. Experimental conditions

- **B** — `ResolvedPersonaContextProvider` (variant "B"), normal structured rendering.
- **LA** — `AgeOnlyLabeledScalarDiagnosticProvider` (variant "LA", unchanged since Phase 20/21 — no code modification for this new scenario; labels only `identity.age`).

## 4. Stage 1 / pre-flight verification

All 11 pre-flight checks passed, live, before any call was made:

| # | Check | Result |
|---|---|---|
| 1 | B and LA select exactly the same fields | PASS |
| 2 | `identity.age` present | PASS |
| 3 | `life_context.family` present | PASS |
| 4 | No restricted fields selected | PASS |
| 5 | B and LA differ only in the top-level age scalar rendering | PASS |
| 6 | `life_context.family` byte-identical between B and LA | PASS |
| 7 | B contains bare "27" | PASS |
| 8 | LA contains "Age: 27" | PASS |
| 9a | Anjali's age (29) present in both | PASS |
| 9b | No restricted field text in either payload | PASS |
| 10 | Both providers deterministic on repeat | PASS |
| 11 | No Phase 10E/13 mapping fixes applied (confirmed by construction — `applyOriginalPhase10eFixes()` never called) | Confirmed |
| 12 | Scenario/skill match Phase 22 audit exactly | Confirmed |

No STOP condition was triggered.

## 5. Exact execution order

Fixed before any call: `List(10){"B"} + List(10){"LA"}` — calls 1–10 = B, 11–20 = LA. Not altered after seeing any response. No call was retried. No scenario was added or substituted.

## 6. Raw per-call results

| # | Variant | Stated gap | Entity referenced | Arithmetic classification | Entity-binding flag |
|---|---|---|---|---|---|
| 1 | B | "Two years" | Anjali | **CORRECT** | CORRECT |
| 2 | B | "two years apart" | Anjali | **CORRECT** | CORRECT |
| 3 | B | "two years older" | Anjali | **CORRECT** | CORRECT |
| 4 | B | "Two years" | Anjali | **CORRECT** | CORRECT |
| 5 | B | "Only three years" | Anjali | **WRONG** (states 3 despite citing 29/27) | CORRECT |
| 6 | B | "just two years" | Anjali | **CORRECT** | CORRECT |
| 7 | B | "two years apart" | Anjali | **CORRECT** | CORRECT |
| 8 | B | "two years apart" | Anjali | **CORRECT** | CORRECT |
| 9 | B | "two years apart" | Anjali | **CORRECT** | CORRECT |
| 10 | B | "Two and a half years" | Anjali | **WRONG** | CORRECT |
| 11 | LA | "barely two years" | Anjali | **CORRECT** | CORRECT |
| 12 | LA | "two years older" | Anjali | **CORRECT** | CORRECT |
| 13 | LA | "two years older" (also correctly mentions Arjun=24 as a separate, undistorted fact) | Anjali (Arjun mentioned but not conflated) | **CORRECT** | CORRECT |
| 14 | LA | "two years older" | Anjali | **CORRECT** | CORRECT |
| 15 | LA | "two years older" | Anjali | **CORRECT** | CORRECT |
| 16 | LA | "three years between us" | Anjali | **WRONG** (states 3 despite citing 27/29) | CORRECT |
| 17 | LA | "two years older" | Anjali | **CORRECT** | CORRECT |
| 18 | LA | "Two years, that's it" | Anjali | **CORRECT** | CORRECT |
| 19 | LA | "Two years, actually" | Anjali | **CORRECT** | CORRECT |
| 20 | LA | "two years older" | Anjali | **CORRECT** | CORRECT |

Full verbatim responses are in `PHASE22_EXECUTIONS.csv`.

## 7. B aggregate results

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---|---|---|---|
| B | 10 | 8 | 2 | 0 |

## 8. LA aggregate results

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---|---|---|---|
| LA | 10 | 9 | 1 | 0 |

## 9. Entity-binding analysis

**20/20 CORRECT — no entity-binding errors in either condition.** Every response, in both B and LA, correctly identifies Anjali as the older sister and never substitutes or conflates her with Arjun (the younger brother). Call 13 (LA) explicitly mentions Arjun's age (24) as an aside but keeps it clearly separate from the sister-gap answer, without distortion. **No response in either condition used Arjun's age (24) in place of Anjali's (29).** No response introduced any unsupported number beyond the two observed wrong answers ("three years" in calls 5 and 16; "two and a half years" in call 10) — both of which cite the correct underlying ages (27, 29) immediately alongside the wrong derived gap, indicating an arithmetic-output error rather than a wrong-fact or wrong-entity error.

## 10. Comparison with Phases 20–21

| Metric | Phase 20 (Pune, n=5 each) | Phase 21 (Pune, n=10 each) | Phase 22 (sister, n=10 each) |
|---|---|---|---|
| B CORRECT rate | 2/5 (40%) | 2/10 (20%) | 8/10 (80%) |
| LA CORRECT rate | 4/5 (80%) | 6/10 (60%) | 9/10 (90%) |
| Gap (LA − B) | +40 pts | +40 pts | **+10 pts** |

**The direction reproduces (LA ≥ B in all three samples), but the magnitude of the gap is far smaller on this second scenario.** B's own CORRECT rate on the sister-age-gap scenario (80%) is dramatically higher than B's rate on the Pune-timeline scenario in either prior phase (20–40%) — this scenario appears substantially easier for B at baseline, leaving much less headroom for LA to show an advantage, structurally similar to the ceiling effect already documented in Phase 17 for pure-recall scenarios (though this is a derivation task, not pure recall, so it is a distinct — if analogous — headroom limitation).

## 11. Interpretation against H1/H2/H3

- **H1 (age-specific labeling contributes to better derived-number performance)**: **weakly, not strongly, supported by this scenario.** The direction is consistent with Phase 20/21 (LA > B), but the 10-point gap here is much smaller than the 40-point gaps previously observed, and with n=10 per condition (8/10 vs 9/10, or 2/10 vs 1/10 WRONG), this difference is well within a single-call swing — not a decisive replication of the earlier magnitude.
- **H2 (a broader labeling effect is required)**: **not tested by this experiment** (no full-L condition was run here, per the design's explicit scope), and this scenario's small effect size does not provide new evidence either way on H2.
- **H3 (observed differences are primarily generation variance)**: **more plausible as a partial explanation on this scenario than on the Pune-timeline scenario.** Given B's own baseline rate is already high (80%), the entire observable gap between B and LA here (1 call) is easily consistent with ordinary call-to-call variance rather than a labeling effect specifically.
- **Taken together**: this experiment does not retrospectively change the Phase 20/21 findings, but it **tempers generalization** — the age-labeling advantage observed on the harder Pune-timeline derivation does not clearly reproduce at the same magnitude on this easier, structurally different derivation. This is consistent with a hypothesis (not tested or claimed here) that the effect, if real, may be more visible specifically when baseline B reliability is low, rather than being a general property of all derived-numeric questions — but this experiment alone cannot establish that; it can only report that the magnitude differs.

## 12. Limitations

- Single new scenario, n=10 per condition — informative for direction, not for effect size, and with a small observed gap, direction alone is weak evidence.
- The sister-age-gap scenario turned out to be substantially easier for B at baseline than the Pune-timeline scenario, which was not predicted in the Phase 22 design document and limits this experiment's power to detect a labeling effect even if one exists.
- Both "WRONG" responses in each condition (B: calls 5, 10; LA: call 16) state the correct source ages (27, 29) immediately before giving an incorrect derived gap — this is a clean, isolated arithmetic-output error, distinct in character from the earlier Pune-scenario's larger-magnitude errors ("three years" when the correct answer was six, a 3-year miscalculation) — here the miscalculation is smaller in absolute terms (off by 1 or 0.5 years) which may itself partly explain why it happens less often.
- Direct (non-blinded) scoring by the same evaluator who ran the calls, consistent with the established approach for small mechanistic checks in this program.
- No claim is made that this scenario's easier baseline difficulty is itself caused by anything about the fact pair, persona content, or skill — it is reported as an empirical observation only.

## 13. API-call count

**20.**

## 14. Files created and modified

**Created:**
- `docs/Persona update POC/PHASE22_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE22_GENERALIZATION_RESULTS.md` (this document)

**Modified:**
- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase22Stage2Execution()` only (additive, no changes to any prior test method).

**Not modified:** `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–21 document, any historical CSV, `PHASE22_INDEPENDENT_SCENARIO_AUDIT.md`, `PHASE22_GENERALIZATION_DESIGN.md`.

## 15. Explicit statement: no production change was made

**No production change was made.** No claim is made that scalar labeling should be adopted, that `PersonaContextRenderer.kt` should be changed, or that this finding (or the earlier Phase 20/21 findings) generalizes beyond the two specific scenarios now tested. This document does not retrospectively reinterpret or rewrite Phases 14–21 — their results stand as originally reported. This experiment adds one additional, honestly-reported data point that **tempers** rather than strengthens the generalization of the age-labeling observation.

---

## Summary (as requested)

- **Exact API calls made:** 20 (10×B, 10×LA).
- **Success/failure count:** 20 succeeded, 0 failed.
- **Payload mismatch count:** 0.
- **Restricted-field leakage count:** 0.
- **B score:** 8 CORRECT / 2 WRONG / 0 AMBIGUOUS.
- **LA score:** 9 CORRECT / 1 WRONG / 0 AMBIGUOUS.
- **Entity-binding results:** 20/20 CORRECT in both conditions; no Anjali/Arjun conflation; no use of Arjun's age (24) in place of Anjali's (29).
- **H1/H2/H3:** H1 receives weak, not strong, supporting evidence (direction reproduces, magnitude does not); H2 not tested here; H3 (variance) is a plausible partial explanation for this scenario's small gap, given B's already-high baseline rate.
- **STOP conditions triggered:** none.
- **Files created:** `PHASE22_EXECUTIONS.csv`, `PHASE22_GENERALIZATION_RESULTS.md`.
- **Files modified:** `PersonaContextRealBenchmarkTest.kt` (additive test method only).

Phase 23 is not started and no further experiment is proposed, per instruction.
