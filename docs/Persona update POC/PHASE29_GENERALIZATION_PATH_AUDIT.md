# Phase 29 — Decision-Critical Generalization Planning

**Zero API calls made to produce this document.** This is a planning/audit document only. No historical Phase 10–28 artifact, production file, mapping, resolver, renderer, persona data, or engine logic was modified. No experiment is created or authorized here.

---

## 1. Reconstruct the Current Boundary

### Established (per `PHASE23_DECISION_BOUNDARY.md`, `PHASE25_DECISION_AUDIT.md`, `PHASE28_FINAL_EVIDENCE_BOUNDARY.md`)

- B and LA behave differently on the Pune-timeline scenario, to a high degree of statistical confidence (Phase 26, Fisher's exact p≈0.00036, n=20/group, interleaved).
- The LA/L-over-B direction has held in all seven independent B-vs-(LA or L) samples run across two scenarios, with zero reversals (Phases 19–22, 24, 26, 27).
- Generalization evidence to a second scenario (sister age-gap) is directionally consistent (LA≥B in both Phase 22 and Phase 27) but did not reach statistical significance at n=20/group (Phase 27, p≈0.235).
- Retrieval (Question A) is fully resolved for every tested scenario — not re-litigated here.

### Unestablished

- Whether the Phase 26 result reflects a stable, generalizable mechanism or a favorable draw from two overlapping, highly variable distributions (B's own Pune-scenario rate has ranged 20–70% across five samples).
- Whether the sister-scenario gap would reach significance at a larger sample.
- Whether any effect generalizes to a third, structurally distinct scenario, persona, or skill.
- LA vs. L, the 27−24 mechanism, and the origin of the newly observed factuality failure modes (hometown/city contradiction, fabrication) — all previously classified as scientifically interesting but not decision-critical (Phase 28 §12), unchanged here.

### The exact condition(s) required to cross the existing boundary

Per `PHASE23_DECISION_BOUNDARY.md` §C, restated without modification: **a stable, reproducible effect at a magnitude exceeding B's own demonstrated baseline variance, replicated across more than two scenario types.**

### Does Phase 26 change the boundary itself?

**No — and it is not redefined here.** Phase 26 supplied the first statistically significant single-scenario result the boundary's "stable, reproducible effect" language anticipated, but it did not, by itself, satisfy the separate "more than two scenario types" clause — that clause is about breadth of replication, which one significant result on one scenario cannot provide regardless of how strong that single result is. `PHASE28_FINAL_EVIDENCE_BOUNDARY.md` §14 already reached this conclusion and explicitly declined to revise the boundary; this document adopts that finding unchanged rather than re-deriving it.

## 2. Compare the Two Available Paths

### Path A — Confirmatory sister-scenario run (≈59/group, ≈118 total)

- **What it answers**: whether the Phase 27 sister-scenario gap (20 points, p≈0.235) reaches statistical significance at a sample size powered against its own observed effect size. If significant, it would give the program **two** independently-confirmed scenario types (Pune timeline, sister age-gap), directly narrowing the gap to the boundary's "more than two" requirement to needing just one more.
- **What it cannot answer**: generalization beyond these same two scenarios; whether the effect holds under a genuinely different semantic domain, skill, or persona; the LA-vs-L question; the 27−24 mechanism.
- **Main confounds**: this path re-uses a scenario already tested twice (Phase 22, Phase 27) — a third result on the identical scenario, even at higher N, narrows uncertainty about *that scenario* but does not by itself demonstrate breadth across scenario *types*, which is the boundary's explicit concern. There is also a risk of "trying until significant" if not interpreted carefully — the Phase 26 statistical framework's own discipline (fixed thresholds, no post-hoc adjustment) mitigates but does not eliminate this concern when the same scenario is retested a third time.
- **Sample-size implications**: ≈118 total calls (calculated in `PHASE28_FINAL_EVIDENCE_BOUNDARY.md` §15 from Phase 27's own observed rates, B=70%/LA=90%) — by far the largest single-scenario B-vs-LA batch this program would have run, but still a single scenario type.
- **What result would cross the existing boundary**: a significant result here, combined with Phase 26's existing significant result, would give **two** confirmed scenario types — still short of "more than two" on a strict reading, but would leave only one additional scenario needed rather than two, and would substantially strengthen the "stable, reproducible" half of the boundary's language for the sister scenario specifically.
- **What result would remain inconclusive**: a second non-significant result at higher N would be much stronger evidence that the sister-scenario gap, whatever its cause, is smaller than adequate power for a program of this scale can economically resolve — this would be informative in its own right (see §15 of Phase 28), but would not advance the boundary.

### Path B — Third independent clean scenario

- **What it answers**: whether the effect generalizes to a structurally distinct fact pair, semantic domain, or skill — directly addressing the boundary's "more than two scenario types" requirement in the way it was originally intended (breadth, not repeated depth on the same two).
- **What it cannot answer**: anything, if no such scenario exists without violating the "no new retrieval/mapping experiment" constraint — see the inventory in §3 below and `PHASE29_THIRD_SCENARIO_INVENTORY.md`.
- **Main confounds**: any newly-constructed scenario risks smuggling in exactly the kind of retrieval-mapping question this Question-B investigation has deliberately kept separate since Phase 14 — the inventory in §3 was designed specifically to catch this.
- **Sample-size implications**: would need its own power calculation once (if) a candidate exists and a pilot/reference rate is established — no such calculation is possible without a candidate, and none is proposed here.
- **What result would cross the existing boundary**: a significant result on a genuinely third, structurally independent scenario would satisfy "more than two scenario types" outright, regardless of Path A's outcome.
- **What result would remain inconclusive**: a non-significant result would add a third data point without resolving the underlying causality-vs-variance question, similpostponing to Phase 25/28's finding but on a wider evidence base.

## 3. Inventory the Persona Corpus for Path B

**Zero API calls.** Full inventory in `PHASE29_THIRD_SCENARIO_INVENTORY.md`. Summary: all 27 registered fields and their known numeric content were traced individually. **No Classification-A (clean) candidate was found.** Every number-adjacent value in the corpus resolves to one of: an already-used scenario, a confound with the existing unresolved 27−24 mechanism, a retrieval block that would require a new mapping/registration change (explicitly out of scope for Path B), an ambiguous or current-date-dependent ground truth, or a structural incompatibility with the scalar-labeling mechanism (no top-level scalar operand involved).

## 4. No Production Recommendation

**No recommendation is made that B or LA should replace the other. Neither path is ranked as "better."** Both paths are evaluated strictly on what question they answer, what they cannot answer, and what evidence would result — not on which is preferable to pursue.

---

## Phase 29 conclusion

**Whether the corpus contains a valid third scenario: NO.** The inventory in `PHASE29_THIRD_SCENARIO_INVENTORY.md` found no Classification-A candidate anywhere in the current field registry or seeded master document content, extending (and confirming, without contradiction) the independent findings of Phase 18 and Phase 22.

**Therefore: the confirmatory sister-scenario run (Path A) is the remaining experimentally defined route**, per instruction's own stated conclusion structure for a "no" inventory result. This is stated as the remaining *defined* route, not as a recommendation to pursue it — whether to authorize Path A, to accept the current evidence state without further experimentation (as Phase 28 explicitly allowed as a valid conclusion), or to pursue some other option entirely (e.g., relaxing the "no new mapping experiment" constraint to open up Path B candidates like the studio-tenure or graduation-year scenarios, which would then require their own separate retrieval-fix investigation before being usable) remains a decision this document does not make.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE29_GENERALIZATION_PATH_AUDIT.md` (this document)
- `docs/Persona update POC/PHASE29_THIRD_SCENARIO_INVENTORY.md` (companion inventory document)

## Files modified

**None.**

## Not modified

Any Phase 10–28 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 28) was modified. Zero API calls were made. No experiment is designed, authorized, or executed by this document. Phase 30 is not started, per instruction.
