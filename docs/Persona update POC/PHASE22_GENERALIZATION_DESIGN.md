# Phase 22 — Generalization Experiment Design (NOT executed)

**Zero API calls.** This document designs, but does not run, a minimal B-vs-LA generalization experiment on the clean independent scenario identified in `PHASE22_INDEPENDENT_SCENARIO_AUDIT.md`. Execution requires separate, explicit authorization, per the standing protocol.

---

## Candidate inventory and classification (summary; full detail in the audit document)

| # | Candidate | Classification |
|---|---|---|
| 1 | Sister age-gap ("Is there a big age gap between you and your sister in your family?") | **A — CLEAN** |
| 2 | Brother age-gap (27−24=3) | E — confounds with the already-tracked 27−24=3 alternative explanation |
| 3 | Studio tenure (career_history) | B — retrieval-confounded (weak default description; also needs an unstated current-year assumption) |
| 4 | Friendship duration (Ritu/Neha/Prashant) | C — ground-truth-confounded (unchanged from Phase 18) |
| 5 | Daily-routine hours (7:30–6:30) | E — otherwise unsuitable (no top-level scalar involved; LA has zero effect on this fact pair) |
| 6 | Graduation-year/age (2019 + 27) | C — ground-truth-confounded (needs unstated current year) |
| 7 | Any re-phrasing of the Pune-timeline question | D — not independent |

## Selected independent scenario

**"Is there a big age gap between you and your sister in your family?"** — skill `about`, using the **native baseline mapping** (no Phase 10E/13/19/20/21-style fix required). Ground truth: Anjali (older sister) = 29, Simran = 27 (`identity.age`), derived age gap = 2 years.

## Exact B/LA conditions

Identical mechanism to Phase 20/21, applied to the new scenario:
- **B** — `ResolvedPersonaContextProvider` (variant "B"), normal structured rendering.
- **LA** — `AgeOnlyLabeledScalarDiagnosticProvider` (variant "LA", unchanged since Phase 20 — no code modification needed for this new scenario, since LA's rule is scenario-agnostic: label `identity.age` only, leave every other top-level scalar bare).

This scenario **does** involve `identity.age` as a genuine operand (Simran's own age is required to compute the gap), so the existing LA mechanism directly and meaningfully tests the same hypothesis (age-label-specific improvement) — no artificial labeling of an unrelated scalar is needed, and none is proposed.

## Zero-cost control checks (already run; to be re-verified live immediately before any Stage 2 call, per the established Phase 19/20/21 pattern)

Already confirmed in the audit document, to be re-confirmed live at execution time:
1. `identity.age` selected (required, baseline, `about` skill).
2. `life_context.family` selected (native conditional, baseline description, no fix).
3. Anjali's age (29) present in the payload.
4. Own age (27) present in the payload.
5. Arjun's age (24) also present (disclosed nuance — both siblings render together; not a ground-truth ambiguity, since each entry is explicitly labeled by relationship).
6. No restricted field present.
7. B-vs-LA diff is exactly the single age-label line (`27` → `Age: 27`), nothing in the family array changes.
8. Provider-to-request equivalence for both B and LA (via `interceptGeneration`, zero network calls).

## Constancy across conditions

Persona, skill (`about`), engine/persona version, user message, history (absent, fresh conversation), memory (absent, fresh `testUserId`), user profile (absent), selected facts, and provider-to-request path are all held constant, identical in structure to Phase 19/20/21's own constancy verification — the only proposed variable is the age-label formatting difference (B vs LA).

## Exact proposed API-call count

**20** (10×B, 10×LA) — matching Phase 21's sample size for direct comparability. Not yet authorized.

## Predetermined order (to be fixed before any call, if authorized)

`List(10){"B"} + List(10){"LA"}` — B block first, then LA block, matching Phase 21's exact ordering convention for cross-phase comparability. Not to be altered after observing responses.

## Scoring rules

Adapted from the Phase 19/20/21 rubric to this scenario's derivation:
- **CORRECT**: response states or clearly implies a duration/gap consistent with 2 years (e.g., "she's two years older," "a couple of years").
- **WRONG**: response states a specific, incorrect numeric gap (e.g., "five years older," "she's older by a decade").
- **AMBIGUOUS**: response acknowledges an age gap exists (or that the sister is older) without committing to a specific number, or is otherwise non-committal.
- Sub-flags to record per call: whether "29" is referenced explicitly; whether "27" is referenced explicitly; whether "2" (or "two years"/"couple of years") appears; whether the response instead discusses Arjun (younger brother) or conflates the two siblings — recorded as its own note, not silently folded into WRONG, since it would indicate an entity-binding failure distinct from an arithmetic failure.

## Interpretation rules

- Report raw B vs. LA CORRECT/WRONG/AMBIGUOUS counts before any interpretation, exactly as Phase 19/20/21 did.
- **What would support generalization of H1**: LA's CORRECT rate is materially higher than B's on this new fact pair, in the same direction as Phase 20/21.
- **What would weaken generalization of H1**: LA and B perform similarly on this scenario, suggesting the Phase 20/21 effect may be specific to the Pune-timeline fact pair/scenario rather than a general age-labeling mechanism.
- **What would remain inconclusive**: mixed results, an entity-binding confusion (sister/brother conflation) confounding the score, or any STOP condition firing mid-run.
- No causal claim would be made regardless of outcome — this remains an n=10-per-condition isolation/reproducibility check on a second scenario, not a powered causal experiment.
- The competing 27−24=3-style explanation is not applicable to this scenario's own arithmetic (27 and 29, not 27 and 24) and would not be re-litigated here; if a response nonetheless references Arjun/24, that is recorded as the entity-binding note above, not merged into the 27−24 hypothesis.

## STOP conditions (if Stage 2 is later authorized)

- A live precondition-verification check (field-set equality, payload-content checks, restricted-field check, line-diff check) fails before any call is made.
- A harness defect is discovered — disclose it, fix only the harness, re-run zero-cost verification, do not silently continue.
- Any call's `payload_matches_request` is false.
- An unexpected restricted field appears.
- The wrong scenario or skill is executed.
- The predetermined order cannot be maintained.
- Any post-result code, mapping, or configuration change would be required to continue.

## Limitations (of this design, disclosed in advance)

- This is a single new scenario on a single new fact pair — even if executed and showing the same direction as Phase 20/21, it would still be a small-N, single-persona, single-model, single-skill finding.
- The family array's dual-sibling content means a response could, in principle, correctly derive "2 years" while incidentally also misdescribing Arjun's role, or vice versa — the scoring rules above separate these failure modes explicitly to avoid conflating them.
- No claim of causation, effect size, or production recommendation would follow from running this design, consistent with every prior phase in this chain.

## Explicit statement: not executed

**Stage 2 has NOT been executed.** This document is a design only. No API call has been made under Phase 22. Execution requires separate, explicit authorization.

---

## Exact API-call count

**0.**

## Files modified

**None** beyond this document and its companion audit document. No Phase 10–21 document, historical CSV, production mapping, resolver, renderer, persona data, or engine logic was modified.
