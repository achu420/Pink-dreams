# Phase 10B — TC-6 Status

Design/assessment only. No wording change is applied, no Stage 1 check is run, no API call is made.

## Is TC-6 scientifically independent of the RAW/STRUCTURED experiment?

**Yes.** TC-6 concerns `PersonaFieldRelevanceScorer`'s conditional-field inclusion logic for variants B and C only — it has no dependency on variant A, D, X, FR, or the structure/selection question at all. The variant-A discrepancy documented in `PHASE10A_A_BASELINE_CORRECTION.md` does not touch B/C's real generation path in any way (confirmed there: "TC-6 — NOT AFFECTED"). Nothing in Phase 10B's findings changes TC-6's status.

## Is completing it justified independently?

**Yes**, for the same reasons given in `PHASE9_NEXT_EXPERIMENTS.md`'s Experiment 2 design: the mechanism is already 75% empirically confirmed (6 of 8 flip probes worked exactly as predicted, zero over-triggering, full determinism), and the one gap has a fully understood, narrow cause — one missing colloquial synonym. This is not a new hypothesis requiring re-justification; it is an already-justified experiment that was paused, not rejected.

## Exact minimal wording change required (described, not applied)

Current (BASELINE) description for `life_context.family`:
```
"life_context.family"
```

Proposed (TUNED) description, adding only the missing colloquial term identified by Stage 1's own failure output ("Are you close with your mom?" failed to match):
```
"family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"
```

This adds exactly one missing token class (colloquial parent address terms — "mom" alongside the already-present "mother"; "dad" added alongside "father" for symmetry, since the same gap would otherwise reappear for a hypothetical "are you close with your dad" probe that Stage 1 happened not to include) to the same word list already 75% validated. No other field, no scorer logic, no threshold, no mapping, and no other skill's requirements are touched.

## What is explicitly NOT done in this document

- The wording above is not applied to `PersonaFieldRegistryRepository` or any seed file.
- `tc6Stage1DeterministicCheck()` is not re-run.
- No API call is made or scheduled.

## Recommendation on sequencing relative to the RAW/STRUCTURED experiment

Since the two are independent, either could run first without affecting the other. Given TC-6's Stage 1 costs **zero API calls** and only needs the one-line change above to re-attempt, it remains the cheaper, faster-to-resolve item of the two if a next authorized step is being chosen — but this document does not recommend an execution order, only confirms that no scientific dependency forces one.
