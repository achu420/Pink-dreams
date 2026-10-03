# Phase 20 — Label Isolation: Stage 2 Results

**Exactly 15 real API calls made, as authorized (5×B, 5×LA, 5×L), in the predetermined order fixed in `PHASE20_LABEL_ISOLATION_DESIGN.md`. All 15 succeeded. All 15 payloads verified (`payload_matches_request=true`). No restricted field appeared in any request.** No provider, renderer, mapping, resolver, persona, engine, or historical experiment artifact was modified before or during execution. No Phase 10–19 document modified.

---

## 1. Objective

Test whether the Phase 16/19 B-vs-L behavioral difference is specifically associated with scalar labeling — and if so, whether it is driven by labeling `identity.age` alone (LA) or requires L's broader labeling of all six top-level scalars — using the exact same timeline scenario as Phase 14/16/19 ("Been in this city long?", `general_chat`). This experiment tests Question B (model use of present facts) only.

## 2. Experimental conditions

- **B** — normal structured B (`ResolvedPersonaContextProvider`, variant "B").
- **LA** — B with ONLY `identity.age` labeled (`AgeOnlyLabeledScalarDiagnosticProvider`, new variant "LA", introduced in Phase 20 Stage 1).
- **L** — existing Phase 16 fully-labeled diagnostic, all six top-level scalars labeled (`LabeledScalarDiagnosticProvider`, variant "L").

## 3. Stage 1 verification summary

Re-verified live, zero-cost, immediately before spending any call (all PASS): selected field IDs identical across B/LA/L to Phase 14/16/19 (`{identity.name, personality.core_traits, identity.hometown, identity.current_city, communication.style, narrative_state.unresolved_threads, life_context.occupation, identity.age, life_context.important_life_events}`); B and L/LA payloads contain the same underlying facts ("27", "Moving to Pune at 21 to study architecture."); no restricted field in any of the three payloads; LA differs from B by exactly the single age-label line (`27` → `Age: 27`); LA differs from L by exactly the five non-age scalar labels (name, hometown, current_city, occupation, communication style), with the age line identical in both. Full Stage 1 audit (including provider→request equivalence for all three conditions, via zero-cost `interceptGeneration`) is documented separately in `PHASE20_LABEL_ISOLATION_DESIGN.md` and was not re-litigated here beyond the precondition re-check above.

## 4. Exact execution order

Fixed before any call, per the design document: `List(5){"B"} + List(5){"LA"} + List(5){"L"}` — calls 1–5 = B, 6–10 = LA, 11–15 = L. Order was not altered based on observed answers.

## 5. Raw per-call results

| # | Variant | Response (key excerpt) | "six years" | "three years" (or other wrong #) | Age 21 referenced | Avoids a specific number | Classification |
|---|---|---|---|---|---|---|---|
| 1 | B | "...so going on six years now" (moved at 21) | Yes | No | Yes | No | **CORRECT** |
| 2 | B | "Just about four years... Landed in Nashik by accident and stayed" | No | "four years" (wrong) | No | No | **WRONG** — wrong duration AND a contradiction (states she "landed in and stayed" in Nashik, her actual hometown, reversing it with Pune, her actual current city) |
| 3 | B | "Moved here about three years ago, for architecture" | No | "three years" (wrong) | No | No | **WRONG** |
| 4 | B | "Just over three years now. I moved to Pune when I was twenty-one" | No | "three years" (wrong) | Yes | No | **WRONG** — classic Phase 14/19 error pattern reproduced |
| 5 | B | "...that's about six years now" (moved at 21) | Yes | No | Yes | No | **CORRECT** |
| 6 | LA | "Moved here about six years back" | Yes | No | No (says "six years back", no explicit 21) | No | **CORRECT** |
| 7 | LA | "Just over six years now. Moved here at twenty-one" | Yes | No | Yes | No | **CORRECT** |
| 8 | LA | "so about six years now?" (moved at twenty-one) | Yes | No | Yes | No | **CORRECT** |
| 9 | LA | "I've been in Pune for a few years now — since I was 21" | No | No | Yes | Yes (vague "a few years") | **AMBIGUOUS** |
| 10 | LA | "about six years now. Moved here at 21" | Yes | No | Yes | No | **CORRECT** |
| 11 | L | "about six years now. Moved here at 21" | Yes | No | Yes | No | **CORRECT** |
| 12 | L | "Not really, no. I moved here when I was twenty-one... Feels like home now" | No | No | Yes | Yes (no duration stated at all) | **AMBIGUOUS** |
| 13 | L | "A little over six years now. Moved here... at 21" | Yes | No | Yes | No | **CORRECT** |
| 14 | L | "coming up on... six years now, weirdly. Moved to Pune when I was 21" | Yes | No | Yes | No | **CORRECT** |
| 15 | L | "coming up on six years now. Moved here at twenty-one" | Yes | No | Yes | No | **CORRECT** |

## 6. Aggregate B / LA / L counts

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---|---|---|---|
| B | 5 | 2 | 3 | 0 |
| LA | 5 | 4 | 0 | 1 |
| L | 5 | 4 | 0 | 1 |

Raw distributions reported before interpretation, per instruction. **LA's distribution (4 CORRECT / 0 WRONG / 1 AMBIGUOUS) is identical in shape to L's**, and both diverge sharply from B's (2 CORRECT / 3 WRONG / 0 AMBIGUOUS) in this run.

## 7. Payload-verification results

All 15 calls: `payload_matches_request = true` (the exact provider output for that call's condition was confirmed present verbatim in the real captured system block). All 15 calls: `selected_field_ids` identical to the pre-verified set for all three conditions. All 15 calls: `restricted_field_present = false`. 0 technical failures, 0 retries, 0 substitutions.

## 8. Interpretation against H1/H2/H3

- **H1 (age-specific labeling)**: **Supported by this run.** LA reproduces L's improvement over B almost exactly (4/5 CORRECT vs. L's 4/5 CORRECT, vs. B's 2/5 CORRECT) — the single age-label change alone was associated with the same qualitative shift as labeling all six scalars. Under H1's stated support condition ("LA reproduces the improvement seen with L"), this run meets it.
- **H2 (broader scalar labeling required)**: **Not supported by this run.** H2 would require L to improve while LA does not; instead LA and L performed identically in this sample (4/5 each). There is no evidence in this run that labeling the other five scalars (name, hometown, current_city, occupation, communication style) contributed anything beyond what the age label alone produced.
- **H3 (generation variance / insufficient evidence)**: **Not the best-supported reading of this run**, but not fully excluded either — n=5 per condition is small, and B's own within-condition variance (2 clean CORRECT, 2 classic "three years" WRONG, 1 novel "four years" + contradiction WRONG) shows real call-to-call variability even within B alone. The LA/L vs. B separation (4/5 and 4/5 vs. 2/5) is larger than what B's own internal variance alone would predict, which favors H1 over H3, but this single 15-call run does not by itself rule out H3 as a contributing factor.

**Overall reading**: this run's evidence favors H1 over H2, and favors H1 over H3 by margin but not decisively, consistent with the standing instruction that a 5-call-per-condition sample is an isolation experiment, not proof of a general model behavior.

## 9. Treatment of the 27−24=3 alternative

The competing hypothesis from Phase 15 (B's "three years" error stems from anchoring to 24, the corporate-departure age, rather than a general labeling-sensitive mechanism) is **neither confirmed nor refuted by this run**. The number "24" does not appear in any of the 15 responses. Two of B's three WRONG responses in this run (calls 3 and 4) do state "three years" — reproducing the exact numeric error seen in Phase 14 and Phase 19 — but neither response's visible text cites 24 as its basis. The third WRONG response (call 2) produced a different wrong number ("four years") together with a hometown/current-city contradiction, which is not explained by the 27−24=3 hypothesis at all and is recorded here as a distinct, previously-unobserved failure mode in B. This finding is preserved without being folded into either the label-effect or the 27−24 explanation — it is reported as a third, separate observation.

## 10. Limitations

- Small sample (n=5 per condition, 15 total) — an isolation/pattern check, not a powered causal experiment.
- Single scenario, single skill, single persona/engine version, single model — no generalization across scenarios or models is claimed.
- Direct (non-blinded) scoring by the same evaluator who ran the calls, consistent with the direct-scoring approach used in Phases 14/16/17/19 for small, mechanistic checks.
- B's absolute CORRECT rate in this run (2/5) is lower than Phase 19's B (3/5) on the identical scenario/mapping — both are small-N draws from the same underlying (apparently unreliable) B behavior on this scenario, and neither rate should be treated as B's "true" rate. This cross-run difference is itself evidence of real sampling variance in B, which tempers (without negating) the H1 reading in §8.
- Call 2's hometown/current-city contradiction is a new failure mode not previously catalogued in Phase 14/15/19 for this scenario; it was not investigated further here (out of scope — Phase 20 is scoped to the label-isolation question only), consistent with the instruction not to expand into another investigation after seeing results.
- No response in any condition explicitly references age 24, so the 27−24=3 mechanism remains untestable from this dataset, exactly as in Phase 19.

## 11. Explicit statement: not a production recommendation

**This is not a production recommendation.** No claim is made that scalar labeling should be adopted, that `PersonaContextRenderer.kt` should be changed, or that any specific renderer/formatting decision follows from this result. This experiment establishes only a pattern within a single, narrow, small-sample isolation test on one scenario.

## 12. Whether the evidence justifies another experiment

The H1-favoring result in this run (LA ≈ L ≫ B) is the first evidence in this program that narrows the mechanism from "labeling in general" (Phase 16) to "labeling the specific relevant scalar" (this phase) — a more precise finding than Phase 16/19 alone established. Whether this justifies a further experiment (e.g., a larger-N replication to firm up the H1-vs-H3 margin noted in §8, or testing whether an LA-style single-field label generalizes to a different scalar/scenario pair) is a legitimate open question, but **no such follow-up is undertaken here**, per the standing instruction not to expand the experiment after seeing results, not to run additional scenarios, and not to perform another formatting experiment within this phase.

---

## Exact API-call count

**15.**

## Files created

- `docs/Persona update POC/PHASE20_EXECUTIONS.csv`
- `docs/Persona update POC/PHASE20_LABEL_ISOLATION_RESULTS.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase20Stage2Execution()` only (additive, no changes to any prior test method).

## Not modified

`PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping/registry seed, any resolver or engine logic, any Phase 10–19 document, any historical CSV.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 19) was modified. Exactly 15 API calls were made, in the predetermined fixed order, none retried, none substituted. No additional scenario, formatting experiment, or production recommendation follows this document.
