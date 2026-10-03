# Phase 32 — Engineering Decision Package

**Zero API calls. Zero production, mapping, resolver, or renderer changes.** This document packages the evidence accumulated through Phase 31 for an engineering/product decision. It does not make that decision, does not select among the paths it documents, and does not rank, score, or recommend any path. No historical Phase 10–31 artifact was modified. No new experiment is designed or authorized.

---

## 1. Executive Summary

Across 31 phases of investigation, this program established that selective persona-field retrieval (the "B" rendering path) has a real, present-fact/model-use gap on **derived-numeric-reasoning questions** — questions requiring the model to combine two stored facts into a new number (e.g., "how long have you lived here," "how big is the age gap with your sister"). A candidate fix — explicitly labeling the `identity.age` field (`"Age: 27"` instead of a bare `"27"`) — was tested against the unmodified baseline ("B") across two independent scenario types. Both showed a statistically significant improvement (Fisher's exact test, α=0.05): the Pune-timeline scenario (Phase 26, p≈0.00036, 60-point gap) and the sister age-gap scenario (Phase 30, p≈0.0336, 15.2-point gap). The direction of this effect has held across all eight independent samples run in this program, with zero reversals.

However, the program's own pre-registered decision boundary (`PHASE23_DECISION_BOUNDARY.md`) requires replication across **more than two** scenario types before an engineering decision is warranted. Exactly two scenario types have been tested, and no third clean scenario currently exists in the corpus without new engineering work (`PHASE29_THIRD_SCENARIO_INVENTORY.md`). **The boundary, applied literally, is not crossed** (`PHASE31_PRODUCTION_BOUNDARY_REAUDIT.md`). This document lays out three paths forward — accepting the current evidence as sufficient, building a third scenario, or holding the boundary as written — without selecting among them, since that selection is an engineering/product decision, not a conclusion the experimental evidence itself can supply.

## 2. Evidence Baseline

| Question | Current evidence | Status |
|---|---|---|
| Does B vs. LA differ on the Pune-timeline scenario? | Phase 26 (n=20/group, interleaved, Fisher's exact p≈0.00036) | **Established statistical result** |
| Does B vs. LA differ on the sister age-gap scenario? | Phase 30 (n=59/group, interleaved, Fisher's exact p≈0.0336) | **Established statistical result** |
| Does the direction remain consistent? | All 8 accumulated B-vs-(LA or L) samples (Phases 19, 20×2, 21, 22, 24×2, 26, 27, 30), zero reversals | **Observed** |
| Has a third independent scenario type been tested? | None run | **Not established** |
| Does the original boundary permit a decision now? | Requirement "more than two scenario types" unmet | **No** |
| Is there a clean third scenario in the current corpus? | Phase 29 inventory: 12 candidates checked, none Classification-A | **Established corpus limitation** |
| Is causality proven? | No causal-inference design has been run; only correlational (formatting-variable-controlled) comparisons exist | **Not established** |
| Is LA universally better than B? | Positive on 2 of 2 tested derived-numeric scenarios; no effect on 4 pure-recall scenarios (Phase 17, explained by a ceiling effect); untested on every other question category | **Not established** |
| Is LA production-ready? | No token-cost, latency, regression, or broader-corpus testing has been performed for LA specifically | **Not decided / not established** |

**Observed evidence, interpretation, and engineering judgment are kept separate throughout this document — a statistically significant p-value (observed) is not the same as "labeling causes the improvement" (interpretation), which is not the same as "LA should ship" (engineering judgment, not addressed here).**

## 3. What Is Established

- A genuine present-fact/model-use discrepancy exists under B on derived-numeric-reasoning tasks (first confirmed Phase 14, with required facts verified present via `selected_field_ids`, not inferred).
- B and LA produce measurably, statistically different response distributions on two independent scenario types (Phase 26, Phase 30).
- The LA/L-over-B direction has never reversed across 8 independent samples.
- Entity-binding (correctly identifying the right sibling in the sister scenario) has been flawless across every sample tested (60/60 in Phase 22+27, 118/118 in Phase 30).
- No third clean (Classification-A) scenario exists in the current persona corpus without new mapping/registration engineering (Phase 29).
- The pre-existing decision boundary's third clause ("more than two scenario types") is unambiguously unmet by the current evidence (Phase 31).

## 4. What Is Not Established

- **Causal mechanism**: no experiment isolates *why* LA outperforms B — only that it does, on the two scenarios tested, at the sample sizes used.
- **The specific role of age-labeling versus labeling in general**: only two direct LA-vs-L comparisons exist (Phase 20, Phase 24), both showing identical scores — too little evidence to conclude either "age-only labeling is sufficient" or "full labeling would do better."
- **Magnitude stability**: the observed gap has ranged from 10 to 60 percentage points on the Pune-timeline scenario alone (across five samples) and 10 to 20.6 points on the sister scenario (across three samples) — no experiment has determined why, or what a "typical" gap would be in production.
- **Generalization beyond the two tested scenario types**, this one persona, or the two skills used (`general_chat`, `about`).
- **Behavior on non-numeric scalar facts or other question classes** — every scenario in this program specifically targeted derived-numeric reasoning; labeling's effect (if any) elsewhere is unknown.
- **The 27−24 alternative explanation** (Phase 15) — untouched by any phase since; absence of "24" in output text is never treated as evidence against internal use.
- **Production suitability** in any dimension — token cost, latency, rendering complexity, regression risk, or interaction with other context fields have not been measured for LA specifically anywhere in this program.

## 5. Path A — Accept the Two-Scenario Evidence

**What this means**: explicitly changing the engineering decision boundary so that two independently significant scenario types (rather than "more than two") are treated as sufficient grounds to proceed toward a production decision.

- **What boundary would need to change**: `PHASE23_DECISION_BOUNDARY.md` §C's third clause, from "replicated across more than two scenario types" to some lower bar (e.g., "at least two," or "two, provided both are independently significant").
- **What evidence would support such a change**: both tested scenario types independently crossed the pre-registered α=0.05 threshold (Phase 26, Phase 30); the direction has never reversed across 8 samples; the two scenarios differ meaningfully in fact pair, derived value, and semantic domain (residence duration vs. family relationship), which is more independence than a single scenario retested repeatedly would provide.
- **What uncertainty would remain**: everything in §4 — causal mechanism, LA-vs-L, magnitude stability, generalization to other question types, and all production-validation dimensions (§9) would remain exactly as unresolved as they are today. Lowering the scenario-count requirement does not manufacture evidence on any of these axes.
- **What would still need validation before production adoption**: every item in §9 below, none of which this program has measured.
- **What risks would remain**: adopting a change on the strength of two scenario types (both narrowly derived-numeric reasoning tasks) risks over-generalizing a pattern that may be specific to that question class, or to this persona's specific fact structure, given that Phase 17 already showed no effect on ordinary recall tasks.

**This document does not state that the boundary should be changed** — only what changing it would require and what would remain true afterward.

## 6. Path B — Build and Test a Third Scenario

**What Phase 29 already established**: the current corpus contains no Classification-A (clean) third scenario. Every candidate examined failed for a specific, documented reason — confound with an existing mechanism, ambiguous ground truth, a retrieval block requiring new mapping work, or structural incompatibility with the labeling mechanism (no top-level scalar operand).

**New engineering required** (before any experiment could even be designed):

- New or expanded persona master-document content containing a genuinely independent, unambiguous, derivable numeric fact pair — distinct in fact pair, semantic domain, and derived value from both the Pune-timeline and sister-age-gap scenarios.
- Potentially new registered fields (if the needed fact does not already exist in a selectable field) — a field-registration change, which this Question-B investigation has deliberately avoided touching since Phase 14, when the retrieval/mapping work was declared closed.
- Mapping/selection validation — confirming the new fact is selectable under baseline retrieval (or, if a fix is needed, that the fix is narrowly scoped and validated the way Phase 13 validated the original timeline fix), without reopening the broader retrieval-completeness investigation.
- Validation that the new scenario genuinely isolates the same phenomenon — i.e., that it involves a top-level scalar operand (so an LA-style manipulation is possible), that B is not already near ceiling on it (so there is headroom to observe a difference), and that it shares no numeric or entity overlap with the existing 27/21/24/29 fact cluster (to avoid the confound class that ruled out the brother age-gap and Ritu-move-in candidates).

**Subsequent experimental requirement** (only after the above engineering work exists, not designed or executed here):

- An independent B-vs-LA execution, following the same pattern as Phases 26/30 — predetermined interleaved order, pre-registered scoring rubric, the same two-sided Fisher's exact test at α=0.05, the same separation of the arithmetic-correctness axis from any entity-binding or factuality-contradiction axis.
- A pre-registered sample size, following the same power-calculation method used in Phase 26/30's design (informed by a pilot estimate of the new scenario's expected effect size, which does not yet exist).

**This document does not design or execute this experiment.**

## 7. Path C — Preserve the Existing Boundary

**What this means**:

- No production decision is made under the current rule — the boundary's third clause remains unmet, and the program does not proceed toward adoption on the basis of the current two-scenario evidence.
- The current evidence remains scientifically informative and stands as recorded — Phase 26 and Phase 30's statistically significant results are not discarded or devalued by holding the boundary; they simply do not, on their own, satisfy it.
- No further experimentation is automatically required — Phase 28 already established that "we have enough evidence to stop experimenting" is a legitimate conclusion for a given scope, and Phase 31 confirmed that further replication of the same two scenarios would not move the literal boundary requirement.
- The boundary can remain pending indefinitely, or until an independently valid third scenario exists (via Path B) or the boundary itself is revisited (via Path A) — whichever comes first, if either does.

**This document does not recommend this path.**

## 8. Neutral Path Comparison

| Dimension | Path A | Path B | Path C |
|---|---|---|---|
| Changes existing boundary | Yes — lowers or reinterprets the "more than two scenario types" clause | No — boundary stays as written; a third scenario type would satisfy it as-is | No — boundary stays as written and unmet |
| Requires new persona/data engineering | No | Yes — new/expanded persona content, possibly new registered fields | No |
| Requires a new API experiment | No | Yes — a full B-vs-LA statistical test on the new scenario, once built | No |
| Resolves the third-scenario requirement | No — it removes the requirement rather than satisfying it | Yes, if the new scenario and its test succeed | No — leaves it explicitly unresolved |
| Leaves causal uncertainty | Yes | Yes | Yes |
| Engineering effort | Low (a policy/documentation change to the boundary itself) | High (new content authoring, field registration/mapping validation, a full experimental cycle) | None (status quo) |
| Main unresolved risk | Acting on a pattern confirmed only on one narrow question class (derived-numeric reasoning), extrapolated by lowering the evidentiary bar rather than by new evidence | The new scenario might itself surface a new confound (as happened repeatedly in Phase 18/22/29's own candidate searches), consuming effort without yielding a clean result | The underlying question (does labeling help, and how much) remains unanswered for engineering purposes indefinitely, even though it is now well-evidenced for two scenario types |

**No ranking, scoring, or preference is expressed above or elsewhere in this document.**

## 9. Production Validation Considerations

What an eventual B/LA engineering decision would need to evaluate, **independently of which path is selected**, and **none of which has been validated by the existing evidence**:

- **Factuality impact**: measured only on two narrow derived-numeric scenarios; not measured on the broader Phase 10D/10E-style corpus or any general-purpose benchmark.
- **Context/token cost**: not measured for LA anywhere in this program (Phase 26/27/30 did not capture token counts; the earlier Phase 10D/10E token analysis concerned selection/format, not scalar labeling, and does not transfer directly).
- **Latency**: not measured — `AgeOnlyLabeledScalarDiagnosticProvider` is a lightweight formatting change and latency impact is presumed negligible, but this presumption has not been tested.
- **Renderer complexity**: the tested implementation is an isolated diagnostic class, not a production-integrated change to `PersonaContextRenderer.kt` — a real implementation would require its own design and review, not evaluated here.
- **Deterministic behavior**: confirmed for the diagnostic providers themselves (verified repeatedly, e.g. Phase 20/24/26/27/30 pre-flight checks) — this is established, not an open question.
- **Regression risk**: not tested against any broader scenario corpus; only the two narrow scenarios were ever run under LA.
- **Interaction with other context fields**: not tested — every experiment held the field set constant per scenario; no experiment varied which other fields were present alongside a labeled age.
- **Restricted-field behavior**: confirmed unaffected in every phase that checked it (no restricted field ever appeared in any B/LA/L payload) — this is established, not an open question.
- **Behavior outside numeric/derived questions**: not established — Phase 17 found no effect on 4 pure-recall scenarios, attributed to a ceiling effect (B already correct), which is itself an unconfirmed explanation, not a tested one.
- **Failure modes already observed**: a hometown/current-city contradiction (Phase 20 call 2, under B; Phase 24 call 20, under LA) and an unrelated-city fabrication (Phase 24 call 21, under L) have appeared at low, roughly comparable rates across conditions — neither has been traced to labeling as a cause, and neither recurred in Phase 26 or Phase 30.
- **Monitoring and rollback requirements**: not addressed by any phase in this program — this is a deployment/operations concern outside the scope of the Question-B investigation entirely.

## 10. Remaining Scientific Uncertainties

Carried forward unchanged from Phase 28/31:

- The causal mechanism behind the observed B-vs-LA difference.
- The 27−24 alternative explanation (Phase 15) — unresolved; absence of "24" in output is never treated as disproof.
- LA vs. L — genuinely untested beyond two identical-scoring small comparisons.
- Magnitude stability — the effect size varies substantially even within a single, unchanged scenario.
- Generalization beyond the two tested scenario types, this persona, and these two skills.
- Behavior across other scalar fields and other question classes (non-derived-numeric).
- Whether the observed effect is reliably exploitable in a production setting, as opposed to being an artifact of the specific narrow scenarios and sample sizes tested.

## 11. Decision Ownership

> **Investigation conclusion**: the evidence now establishes statistically detectable B-vs-LA differences on two independent scenario types, but the pre-existing production boundary remains unmet because no third scenario type exists in the current corpus without new engineering work.

> **Engineering decision**: selecting among Path A, Path B, and Path C is outside the experimental evidence itself and must be made explicitly by the engineering/product owner, rather than inferred from the experiment or defaulted to by this document.

## 12. Final Investigation Boundary

This document, and the 31 phases preceding it, complete the Question-B (model-use) investigation to the extent the current corpus and pre-registered boundary allow. **No path is selected. No production change is made or recommended. No new experiment is designed or authorized.** The decision of how to proceed — accept, expand, or hold — belongs to whoever owns the engineering/product roadmap this investigation was commissioned to inform, using the evidence base this document summarizes without requiring them to reconstruct Phases 10–31 themselves.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE32_ENGINEERING_DECISION_PACKAGE.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10–31 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.

## Explicit confirmation

Zero API calls were made. Zero production, mapping, resolver, or renderer changes were made. No historical artifact (Phase 10 through Phase 31) was modified. No new experiment is designed or authorized. No path is selected, ranked, or recommended. Phase 33 is not started, per instruction.
