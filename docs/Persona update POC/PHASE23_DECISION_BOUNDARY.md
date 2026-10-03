# Phase 23 — Decision Boundary

**Zero API calls made to produce this document.** This document answers: *what can we responsibly decide from the current evidence, and what cannot yet be decided?* It does not make a production decision, and does not recommend any of the options it discusses. No historical artifact, production file, mapping, resolver, renderer, persona data, or engine logic was modified.

This document should be read alongside `PHASE23_EVIDENCE_BASE.md`, which contains the full chronological evidence map and the detailed H1/H2/H3 analysis this document draws on without repeating.

---

## A. Evidence-supported facts

Claims below are directly supported by specific, cited experiments — not interpretation.

1. Historical variant "A" (Experiments 1/3, Phases 6/7) never sent the full master document to any real generation call (Phase 10A, source-code reconstruction).
2. On the Phase 10D corpus, selection (full vs. selective content) produced a ~50-point factuality difference; format (raw vs. structured) produced only a ~3-point difference, at both selection levels (Phase 10D, 90 real calls).
3. Every one of Phase 10D's 23 SS/SR failures traced to one of three specific, nameable field-mapping gaps (Phase 10D failure analysis; Phase 10E zero-cost audit).
4. Closing those three gaps with four narrowly-scoped, additive field-mapping edits recovered SS-M/SR-M from 46.7%/43.3% to 86.7%/86.7%, at +3.4%/+3.6% token cost (Phase 10E, 60 real calls).
5. Both of Phase 10E's originally-reported residual failures were, on primary-evidence re-verification, retrieval (Question-A) failures, not model-behavior (Question-B) failures (Phase 11, corrected further in Phase 12).
6. After closing the friends-mapping gap for real, the model correctly used the newly-available fact in both tested conditions (Phase 14, friends/B and friends/X, 2/2 CORRECT).
7. After closing the timeline-mapping gap for real, `timeline/B` produced an incorrect duration ("three years") despite both required facts (`identity.age`=27, "moved at 21") being confirmed present in that exact call's `selected_field_ids` (Phase 14) — the first confirmed present-fact/model-use discrepancy in the program.
8. `PersonaContextRenderer.renderValue()`'s `JsonPrimitive` branch does not use its `fieldId` parameter — top-level scalar fields render as bare, unlabeled values in B; nested objects/arrays already carry `**Key:**` labels (Phase 15, direct source-code inspection).
9. A single controlled call using a fully-labeled rendering (L) produced a correct duration ("six years") on the identical scenario, with every other variable held constant (Phase 16, n=1).
10. On four independent pure-recall scenarios (age, hometown, current city, education), B was already correct in all four cases before any labeling was applied, and labeling produced no additional effect (Phase 17, 8 calls, 4 R2 pairs).
11. Repeating the identical B-vs-L comparison 5 times per condition reproduced the direction: B 3/5 CORRECT, L 5/5 CORRECT (Phase 19, 10 calls).
12. An age-only labeled condition (LA), isolated from L's full-scalar labeling, matched L's magnitude when both were run on the same calls: B 2/5, LA 4/5, L 4/5 (Phase 20, 15 calls).
13. An independent 20-call repeat of B-vs-LA on the same scenario reproduced the direction: B 2/10 CORRECT, LA 6/10 CORRECT (Phase 21).
14. A genuinely independent scenario (sister age-gap: different fact pair, different derived value, different semantic domain, added entity-binding requirement) reproduced the same direction but at a much smaller magnitude: B 8/10 CORRECT, LA 9/10 CORRECT (Phase 22).
15. Entity binding (correctly identifying Anjali, not Arjun, as the sister in question) was 20/20 correct in both B and LA throughout Phase 22.
16. No response across Phases 19–22 (45 total timeline/sister-scenario calls) explicitly references age 24 — the competing 27−24=3 explanation remains neither confirmed nor refuted by response text (Phase 15's original caveat, preserved).

## B. Plausible but unproven interpretations

These are reasonable readings of the evidence in §A, but none is itself directly proven by a single experiment — they are syntheses, offered as interpretation, not fact.

- **Age-specific labeling may improve some derived-number tasks.** Consistent with Phase 20/21/22's reproduced direction, but not proven causally (no experiment has varied labeling while holding everything else constant across a large enough sample to rule out variance as the sole explanation).
- **The effect may depend on the structure of the question.** Suggested by the contrast between Phase 17 (no effect, pure recall, ceiling) and Phases 20/21/22 (some effect, derivation tasks) — but this specific "recall vs. derivation" distinction was never itself tested as an isolated variable; it is inferred from comparing across different phases' different scenario types.
- **Labeling may matter more when a scalar must be related to another fact.** A restatement of the same inference above, extended to note that Phase 22's smaller effect coincided with an easier relate-two-facts task (small numbers, close together) than Phase 20/21's harder one (larger age gap, less obviously bounded) — plausible, not tested directly.
- **Generation variance remains important.** Supported by B's own baseline instability across runs (40% → 20% → 80%, across three different scenario/phase combinations) — but the relative contribution of variance versus a real labeling effect cannot be decomposed from the current sample sizes.
- **The timeline scenario may have properties not shared by the sister scenario** that make B less reliable on it specifically (e.g., a less obviously-signaled arithmetic relationship, or the competing 27-24-21 triad of numbers all present in the same list) — plausible given the two scenarios' different B baseline rates (20-40% vs. 80%), but no experiment has isolated which specific property (if any) is responsible.

## C. Decisions that require additional evidence

**No option below is recommended.** For each, this section states only what additional evidence would be needed before the decision could responsibly be made.

### 1. Adopting LA-like (age-only) labeling in production

**Would require:** evidence across a materially larger and more diverse scenario set than the two tested (Pune timeline, sister age-gap) — including scenarios of varying difficulty, varying fact-pair structure, and ideally a formal power analysis or pre-registered larger-N replication capable of statistically separating a real effect from the variance already observed in B's own baseline (40%/20%/80% across three samples). It would also require testing interaction effects (e.g., does labeling ever *hurt* on some scenario type?) which no phase has checked, since every phase to date measured only CORRECT/WRONG/AMBIGUOUS shifts on scenarios chosen because B struggled with them or as a deliberate contrast.

### 2. Changing the global renderer (`PersonaContextRenderer.kt`)

**Would require:** everything in (1), plus evidence that the change does not regress the many other scenario types this renderer already serves at or near ceiling (per Phase 17's finding that recall scenarios show no benefit and could, in principle, be disrupted by a rendering change that was never tested against them for regressions). No phase in this program has run a broad regression pass of a labeling change against the full Phase 10D/10E corpus or any comparably broad corpus.

### 3. Labeling all scalar fields (the L approach)

**Would require:** a direct L-vs-LA comparison at a sample size larger than Phase 20's n=5 each (the only phase that ran both conditions side by side), since the current evidence shows L and LA performing identically in that one small sample — not enough data to decide whether the additional five labels (beyond age) contribute anything, cost anything (e.g., token overhead, verbosity effects on other scenario types), or introduce any risk.

### 4. Labeling only age (the LA approach)

**Would require:** the same generalization evidence as (1) — LA has more supporting samples than L (Phases 20, 21, 22 vs. Phase 20 only for L), but still only two distinct scenarios, one of which showed a much smaller effect than the other. Deciding to specifically label only age (as opposed to some other scalar, or none) would also require evidence that age is the specific scalar that matters, as opposed to whichever scalar happens to be the operand in a derivation task — a hypothesis not yet directly tested (e.g., a scenario requiring a non-age scalar to be labeled for a correct derivation has not been run).

### 5. Doing nothing (retaining B as-is)

**Would require:** no additional evidence to *remain* the current state (it is the status quo), but a decision to *deliberately conclude* the investigation and retain B as-is would benefit from an explicit acknowledgment (which this document provides) that a reproducible, if modest and scenario-dependent, pattern exists and has not been explained away — choosing to do nothing is not evidentially free; it is a decision to accept the currently-observed failure rate on tasks like the Pune-timeline scenario (B: 20-40% CORRECT across two independent samples) without further investigation or mitigation.

### 6. Running a larger validation corpus

**Would require:** a design decision (not evidence) about scope — how many scenarios, what mix of difficulty levels, whether to include non-derivation scenario types as regression controls, and what sample size per condition would be needed to detect an effect of the magnitude actually observed (10–40 points, quite variable across the two scenarios tested) with reasonable confidence. No such design has been produced in this program; Phase 18/22's inventories establish that the *existing* corpus does not contain many more clean candidates without either new mapping work (out of scope for the Question-B investigation) or accepting ground-truth ambiguity — so a larger validation corpus would likely require deliberately constructing new scenarios with unambiguous, fully-mapped ground truth, which is itself a new engineering task, not a decision available from current data alone.

---

## Explicit boundary statement

**Established** (§A): the retrieval side of this program is closed for the tested scenarios; a real, reproducible present-fact/model-use pattern exists on at least one scenario (Pune timeline) and partially generalizes, at reduced magnitude, to at least one other (sister age-gap); a real, source-verified rendering difference (unlabeled top-level scalars) exists and is a plausible, evidence-consistent mechanism.

**Not established** (§8 of the companion evidence document, restated here as the boundary): causation, universality, magnitude at scale, resolution of the competing 27−24 explanation, or any production-readiness claim for LA, L, or any renderer change.

**The boundary between evidence and interpretation is therefore**: everything in §A of this document is evidence; everything in §B is interpretation consistent with that evidence but not itself proven; everything in §C is a decision that the current evidence base does not yet support making, in either direction.

---

## Exact API-call count (this document)

**0.**

## Files created

- `docs/Persona update POC/PHASE23_DECISION_BOUNDARY.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10A–22 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.
