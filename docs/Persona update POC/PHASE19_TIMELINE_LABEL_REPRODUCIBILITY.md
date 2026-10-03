# Phase 19 — Timeline Label Reproducibility Study

**Exactly 10 real API calls made, as authorized (5×B, 5×L). All 10 succeeded. All 10 payloads verified (`payload_matches_request=true`) against the real captured request before being scored.** Same exact scenario as Phase 14/15/16 ("Been in this city long?", `general_chat`). No Phase 10–18 document modified. No new field mapping, provider, or renderer change introduced — `PersonaContextRenderer.kt` and `LabeledScalarDiagnosticProvider.kt` (Phase 16) reused unchanged.

---

## 1. Objective

Determine whether the Phase 16 result (n=1: labeled scalar payload → correct "six years" derivation, vs. Phase 14's unlabeled B → incorrect "three years") is **reproducible under controlled repetition of the identical scenario**, while separating a possible labeling effect from ordinary generation variance. This study does not test a new scenario, a new fix, or a new hypothesis — it repeats the exact Phase 14/16 comparison 5× per condition instead of 1×.

## 2. Preconditions verified (zero API cost, before any call)

All 5 pre-registered checks passed on the corrected harness (after fixing the missing `applyOriginalPhase10eFixes(world)` call — see §11):

| # | Check | Result |
|---|---|---|
| 1 | Selected field IDs identical to Phase 14/16 (age, important_life_events, occupation, hometown, current_city, name, core_traits, communication.style, unresolved_threads) | PASS |
| 2 | B payload contains "27" and "Moving to Pune at 21" | PASS |
| 3 | L payload contains "Age: 27" and "Moving to Pune at 21" (same underlying value, only labeled) | PASS |
| 4 | No restricted field present in either payload | PASS |
| 5 | Only difference between B and L is scalar labeling (no value changes, no added/removed lines beyond label prefixes) | PASS |

**A harness defect was found and fixed before this run** (see §11 for full disclosure) — this is exactly the "STOP before API calls if a defect is found" behavior the instruction required, and it worked as designed: the first attempt correctly failed check #1 rather than silently proceeding with a wrong field set.

## 3. Exact B/L differences

Identical to Phase 16: every top-level scalar field (`name`, `hometown`, `current_city`, `age`, `occupation`, `communication.style`) is rendered as `"Label: value"` in L and as bare `"value"` in B. All nested array/object content (`important_life_events` bullets, `unresolved_threads`, `core_traits`) is byte-for-byte identical between B and L. No other input (message text, engine version, persona core version, conversation history, memory) varies across any of the 10 calls.

## 4. Predetermined call order

Fixed in code before any response was seen: `List(5) { "B" } + List(5) { "L" }` — i.e., calls 1–5 = B, calls 6–10 = L. Order was not altered based on observed answers at any point.

## 5. Raw execution summary

| call_index | variant | status | payload_matches_request |
|---|---|---|---|
| 1 | B | SUCCESS | true |
| 2 | B | SUCCESS | true |
| 3 | B | SUCCESS | true |
| 4 | B | SUCCESS | true |
| 5 | B | SUCCESS | true |
| 6 | L | SUCCESS | true |
| 7 | L | SUCCESS | true |
| 8 | L | SUCCESS | true |
| 9 | L | SUCCESS | true |
| 10 | L | SUCCESS | true |

10/10 SUCCESS, 10/10 payload-verified. Full text saved in `PHASE19_EXECUTIONS.csv`.

## 6. Per-call classification

| # | Variant | Response (key excerpt) | "six years" present | "three years" present | References age 27 explicitly | References moving at 21 | Avoids calculation | Classification |
|---|---|---|---|---|---|---|---|---|
| 1 | B | "about six years now... Moved here at 21" | Yes | No | No | Yes | No | **CORRECT** |
| 2 | B | "a few years now... Moved here when I was 21" | No | No | No | Yes | Yes (vague, no number stated) | **AMBIGUOUS** |
| 3 | B | "about six years now... at 21, fresh out of Nashik" | Yes | No | No | Yes | No | **CORRECT** |
| 4 | B | "Just over three years now. Moved here at 21..." | No | Yes | No | Yes | No | **WRONG** |
| 5 | B | "about six years now. Moved here at 21..." | Yes | No | No | Yes | No | **CORRECT** |
| 6 | L | "about six years now—I moved here... when I was twenty-one" | Yes | No | No | Yes | No | **CORRECT** |
| 7 | L | "about six years now — moved here... when I was 21" | Yes | No | No | Yes | No | **CORRECT** |
| 8 | L | "about six years now. Moved here at twenty-one..." | Yes | No | No | Yes | No | **CORRECT** |
| 9 | L | "so about six years now" (moved at twenty-one) | Yes | No | No | Yes | No | **CORRECT** |
| 10 | L | "a little over six years ago... at the college" | Yes | No | No | Yes | No | **CORRECT** |

No response in either condition explicitly states "27" as a bare number; both conditions consistently reference "21" as the arrival age. No response references age 24 (the competing corporate-departure anchor) in this run.

## 7. Competing 27−24=3 explanation analysis

The Phase 15 competing hypothesis (B's original "three years" error came from anchoring to 24, the corporate-job-departure age, rather than a general arithmetic failure) is **neither confirmed nor further supported by this run**. Call 4 (the one WRONG response in this run, "just over three years") does not mention 24 anywhere in the response text — it states "Moved here at 21" and then states "three years," which is internally inconsistent with its own stated anchor (21) rather than visibly computed from 24. This is a *different* error shape than Phase 14's original B failure (which also said "three years" without explicitly stating 21). The persistence of a "three years" failure mode in B (1 of 5 this run, matching 1 of 1 in Phase 14) without an explicit 24-reference in the visible text means this analysis can say the error **recurs at a similar rate in B**, but cannot determine from the model's visible output whether 24-anchoring is the mechanism — the number 24 simply isn't surfaced in any response, correct or incorrect, in this dataset.

## 8. Comparison of B vs L

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---|---|---|---|
| B (unlabeled) | 5 | 3 | 1 | 1 |
| L (labeled) | 5 | 5 | 0 | 0 |

Raw distributions reported before interpretation, per instruction. L was correct in 5/5 calls; B was correct in 3/5, with 1 wrong ("three years," reproducing the original Phase 14 error) and 1 ambiguous (avoided stating any specific duration).

## 9. What this experiment establishes

- The Phase 16 "six years" correct-derivation result **is reproducible** under L across 5 independent calls (5/5 CORRECT), not a one-off.
- The Phase 14 "three years" error **also recurs under B** in this larger sample (1/5 WRONG, plus 1/5 AMBIGUOUS/avoided) — B is not reliably correct on this scenario even before any labeling change; it produces the correct "six years" answer in only 3 of 5 trials.
- Across this small sample, **L shows a higher CORRECT rate (5/5) than B (3/5)** on the identical scenario with identical facts, identical selection, and identical everything except scalar labeling.

## 10. What this experiment does NOT establish

- **Not proof of causation.** N=5 per condition is small; LLM generation is stochastic, and this result does not rule out that a larger sample of B would occasionally reach 5/5, or that a larger sample of L would occasionally produce a wrong answer.
- **Does not establish a specific effect size.** No claim is made about "how much" labeling helps — only that, in this sample, L's raw correct-count was higher than B's.
- **Does not isolate the causal mechanism.** It remains unknown whether labeling helps by making "27" identifiable as age specifically, or through some other change in how the model attends to the payload (e.g., overall structure salience) — this experiment was not designed to distinguish those.
- **Does not rule out the 27−24=3 competing explanation, nor confirm it** — as shown in §7, the number 24 does not appear in any response, correct or incorrect, in this dataset, so this study is silent on that specific mechanism.
- **Does not constitute a production recommendation.** This is a single-scenario, single-model, small-N reproducibility check, explicitly scoped to answer only the reproducibility question stated in §1.

## 11. Limitations

- Small sample (n=5 per condition) — standard for a scoped reproducibility check, not for an effect-size claim.
- Single scenario, single skill (`general_chat`), single model/engine/persona-core version — no generalization across scenarios or models is claimed.
- Scoring performed directly (not blinded) by the same evaluator who ran the calls, consistent with the direct-scoring approach used in Phases 14/16/17 for small, mechanistic checks (as opposed to the full blind/unblind cycle used for the larger Phase 6/10D quality passes).
- **Harness defect disclosure**: the test method as first written omitted the `applyOriginalPhase10eFixes(world)` call (present in Phase 14/16, needed to add `identity.hometown`/`identity.current_city` to `general_chat`'s required fields before the Phase-19-specific timeline mapping is layered on top). This caused the Stage-1 precondition check "Selected field IDs identical to Phase 14/16" to correctly FAIL on the first attempt — no API call was made before this was caught, exactly as the instruction's STOP-before-calls requirement demands. The fix was a single added line (`applyOriginalPhase10eFixes(world)` as the first statement in the method body, with an inline comment disclosing the omission), the harness was recompiled (`gradle compileTestKotlin -q`, empty output = success), and the corrected preconditions were re-verified (all 5 checks PASS, §2) before the 10-call sequence proceeded. No production code, mapping, or historical document was touched by this fix — only the test harness method itself.

## 12. Next-question statement only

Whether the observed CORRECT-rate difference (5/5 vs 3/5) reflects a genuine labeling effect or is within the range of ordinary sampling variance for this scenario is the open question a larger, pre-registered sample (or a different scenario testing the same mechanism) would need to address. No such follow-up is undertaken here.

---

## Exact API-call count

**10.**

## Files modified

- **Created:** `docs/Persona update POC/PHASE19_EXECUTIONS.csv`, `docs/Persona update POC/PHASE19_TIMELINE_LABEL_REPRODUCIBILITY.md` (this document).
- **Modified:** `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase19TimelineLabelReproducibility()` only (additive), including the one-line harness-defect fix disclosed in §11.
- **Not modified:** `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, any field mapping, any registry seed, any Phase 10–18 document.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 18) was modified. Exactly 10 API calls were made, as authorized, in the predetermined fixed order, none retried. No additional experiment or production recommendation is made beyond what is stated in §12. This phase stops here, per instruction.
