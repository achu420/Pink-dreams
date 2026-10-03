# Phase 25 — Decision Audit (Stage 1 Only)

**Zero API calls made to produce this document.** This is a synthesis and audit of already-published Phase 19–24 evidence, read against `PHASE23_DECISION_BOUNDARY.md`. No historical artifact, production file, mapping, resolver, renderer, persona data, or engine logic was modified. No new experiment is run; Stage 2 is not executed.

---

## 1. Purpose

Determine, from the evidence already collected through Phase 24, whether the scalar-label hypothesis (age-specific labeling improving derived-number performance) has accumulated enough evidence to cross a decision boundary — and if not, state precisely what additional evidence would be needed, without designing or authorizing that evidence collection here.

## 2. Established Evidence

Restated from `PHASE23_EVIDENCE_BASE.md`/`PHASE23_DECISION_BOUNDARY.md` §A and extended with Phase 24's results. These are observed facts, directly supported by specific cited experiments.

| Sample | Scenario | B | LA | L | LA/L − B |
|---|---|---:|---:|---:|---:|
| Phase 19 | Pune timeline | 3/5 (60%) | — | 5/5 (100%) | L: +40 pts |
| Phase 20 | Pune timeline | 2/5 (40%) | 4/5 (80%) | 4/5 (80%) | LA: +40 pts; L: +40 pts |
| Phase 21 | Pune timeline | 2/10 (20%) | 6/10 (60%) | — | LA: +40 pts |
| Phase 22 | Sister age-gap | 8/10 (80%) | 9/10 (90%) | — | LA: +10 pts |
| Phase 24 | Pune timeline | 7/10 (70%) | 8/10 (80%) | 8/10 (80%) | LA: +10 pts; L: +10 pts |

- **B's own CORRECT rate on the identical Pune-timeline scenario has ranged from 20% to 70% across four independent samples** (Phase 19, 20, 21, 24), using the same mapping, providers, message, and skill each time.
- **LA has now been run four times** (Phase 20, 21, 22, 24) and **has never scored below B** in any of those four samples.
- **L has been run twice** (Phase 19, Phase 20, Phase 24 — three times total) and has likewise never scored below B.
- **LA and L have been compared directly on the same calls exactly twice** (Phase 20: 4/5 vs 4/5; Phase 24: 8/10 vs 8/10) — in both cases, identical scores.
- No response across 75 total timeline/sister-scenario calls (Phases 19–22, 24) has ever contained the substring "24."
- Two new, mutually distinct failure modes were observed in Phase 24 only: a hometown/current-city contradiction (under LA, call 20) and an unrelated-city fabrication ("Pittsburgh," under L, call 21) — both occurring alongside otherwise-normal or even numerically-correct responses.

## 3. Unresolved Questions

- Whether the LA/L-over-B direction reflects a real, exploitable labeling effect or is fully explained by B's own demonstrated variance (20–70%).
- Whether the gap magnitude (40 points in Phase 20/21, 10 points in Phase 22 and Phase 24) is driven by scenario difficulty, by which run happened to sample B's variance, or some interaction of both.
- Whether L provides anything beyond what LA alone provides.
- Whether the `27−24=3` mechanism played any role in any observed "three years" response.
- Whether the two Phase-24 failure modes (hometown/city contradiction, unrelated-city fabrication) are connected to labeling at all, or are independent, condition-agnostic reliability risks.

## 4. Causality Assessment

**What evidence would distinguish a genuine LA-over-B effect from B's within-condition variance?** At minimum: (a) a sample size per condition large enough that B's own measured variance (currently spanning 50 percentage points across n=5–10 samples) narrows to a confidence interval that does not overlap LA/L's observed range, and (b) ideally, a design that holds B's own draw fixed while comparing labeling within the same batch of underlying "difficulty" (something no phase to date has attempted — every LA/L sample has been compared against a *separately drawn* B sample, never a shared/paired baseline beyond the single-batch comparisons in Phase 20 and Phase 24, where B, LA, and L were drawn in the same session but still as independent 10-call sequences, not paired per-call).

**Using the four Pune-timeline samples plus the sister-age-gap result**: LA/L has out-scored B in all five samples where a direct comparison exists (19, 20, 21, 22, 24), with the gap ranging from 10 to 40 points. A pattern this consistent in direction, across five independent samples, is more than would typically be expected under a pure-noise (zero true effect) explanation *if* B's own variance were narrow — but B's variance is not narrow (20–70%), which weakens how much can be inferred from direction-consistency alone. **No claim of causality is made from the observed direction alone**, per instruction — direction-consistency across five samples is evidence worth weighing, not proof.

## 5. Baseline-Variance Assessment

B's measured CORRECT rate on the single, unchanged Pune-timeline scenario: 60% (n=5, Phase 19), 40% (n=5, Phase 20), 20% (n=10, Phase 21), 70% (n=10, Phase 24). This is a 50-percentage-point range on **the exact same scenario, mapping, message, and provider**, across sample sizes too small individually to pin down a stable rate. This variance is itself an established fact (§2), not an inference. It directly limits how confidently any single sample's LA/L-over-B gap can be attributed to labeling specifically, because the same underlying process (B alone, unlabeled) has demonstrably produced results across nearly the entire 20–70% range without any manipulation at all.

**No quantitative model (e.g., a formal variance estimate, a confidence interval, or a probability) is constructed here** — the sample sizes (n=5 to n=10 per condition) are too small to support one responsibly, and the instruction explicitly prohibits manufacturing a quantitative relationship from the existing small sample. The qualitative conclusion stands: B's variance is large enough, relative to the observed gaps, that variance cannot be ruled out as a substantial contributor.

## 6. Scenario-Difficulty Assessment

Two scenarios have been tested with LA-vs-B: Pune timeline (B: 20–70% across four samples) and sister age-gap (B: 80%, one sample). The sister scenario's single B sample (80%) sits above the *entire* observed range of the Pune scenario's B samples in three of four cases (60%, 40%, 20%) and matches only the highest Pune sample (70%, Phase 24). This is consistent with — but does not prove — the sister scenario being intrinsically easier for B (smaller number pair, 27 vs. 29, closer in magnitude, than 27 vs. 21; a more socially routine question type). **This is stated as a plausible, unproven interpretation** (already recorded in `PHASE23_DECISION_BOUNDARY.md` §B), not a quantitative finding — with only one B sample on the sister scenario, no difficulty-vs-gap relationship can be fit or claimed. Phase 24's result (a 10-point gap on the *same, harder* Pune scenario) further undercuts a clean "harder scenario → bigger gap" reading, since the same scenario produced both a 40-point gap (Phase 20/21) and a 10-point gap (Phase 24) without any change to difficulty at all. **The evidence is consistent with scenario difficulty being one contributing factor among several (including plain variance), not with it being an established, quantified driver.**

## 7. LA vs L Evidence

**Exactly two samples exist where LA and L were both run against the same B batch**: Phase 20 (LA 4/5, L 4/5 — identical) and Phase 24 (LA 8/10, L 8/10 — identical). In both cases, **LA and L produced identical CORRECT counts**. No sample has ever shown L outperforming LA, and no sample has ever shown LA outperforming L. This is the entirety of the direct LA-vs-L evidence in the program. **It does not establish that L adds value beyond LA** — the two available comparisons show no distinguishable difference between them at all, in either direction, at this sample size. It also does not establish that L and LA are *equivalent* in a strong sense — two samples of n=5 and n=10 are not enough to rule out a difference too small to detect at this scale. The honest statement is: **the existing evidence provides no support for L over LA, and no support for LA over L — it supports neither H2 (full-labeling benefit) nor a claim that L is unnecessary, beyond noting the two samples available show no measured difference.**

## 8. 27−24 Alternative

**Precisely established**: the substring "24" has not appeared in any of the 75 relevant responses collected across Phases 19, 20, 21, 22, and 24. Two WRONG responses across this dataset stated "three years" (Phase 14's original B call, plus Phase 24 call 1 and call 24) — the value that would result from 27−24.

**Precisely NOT established**: whether 24 was used in the model's internal computation for any "three years" response. The absence of "24" in visible output text is not evidence of its absence from internal computation — this has been stated in every phase since Phase 15 and is restated here unchanged. **Equally, the presence of "three years" as an output is not evidence that 24 was used** — it is also consistent with an unanchored guess, or a different, coincidentally-matching miscalculation (a hypothesis strengthened, if anything, by Phase 24's discovery of two entirely different wrong values — "nine years," "five years" — that don't correspond to any nameable arithmetic combination of the numbers in context, showing that B/LA/L's wrong answers are not confined to the "three years"/27−24 pattern alone). **No experiment run to date has been designed in a way capable of distinguishing these possibilities**, and none is proposed here.

## 9. What Additional Evidence Would Actually Change the Decision

Stated to satisfy the instruction's allowance ("unless necessary to state what evidence would resolve a specific open question") — **no such evidence collection is designed, authorized, or recommended here.**

- **For causality vs. variance**: a substantially larger paired sample (the same underlying difficulty/scenario, B and LA/L compared within tightly matched batches, ideally alternating or randomized per-call order rather than blocked by condition) would be needed to shrink B's own confidence interval below the observed gap size.
- **For scenario difficulty**: multiple additional scenarios spanning a deliberately varied difficulty range (not just two data points) would be needed to even begin fitting a difficulty-vs-gap relationship, which does not currently exist in any usable form.
- **For LA vs. L**: more paired LA/L samples across more than the two scenarios/sessions already run — the current two comparisons (Phase 20, Phase 24) are too few and too small to detect a difference even if one exists.
- **For the 27−24 alternative**: this would likely require a fundamentally different kind of evidence than output-text analysis — e.g., a scenario deliberately designed so that a 27−24-anchored answer and a 27−21-anchored answer are numerically distinguishable in a way that doesn't just produce "three" vs. "six" (which conflates with generic wrong-answer noise, as Phase 24 demonstrated) — no such design currently exists in this program.

## 10. Decision Boundary

Per `PHASE23_DECISION_BOUNDARY.md` §C, the decision boundary for any production action (adopting LA-like labeling, changing the global renderer, labeling all scalars, labeling only age, or deliberately doing nothing) requires evidence of a stable, reproducible effect at a magnitude that exceeds B's own demonstrated baseline variance, ideally replicated across more than two scenario types.

**Current state against that boundary**: five samples show a consistent direction (LA/L ≥ B, never reversed), but the magnitude of that direction varies as much as B's own baseline does (10 to 40 points), and the two scenarios tested differ substantially in B's baseline reliability (20–70% vs. 80%). This is a *meaningfully firmer* evidentiary position than existed after Phase 21 (direction reproduced once) or Phase 22 (direction reproduced with tempered magnitude) — Phase 24 adds a third context (a fresh Pune-timeline sample, with an L arm) that again shows the same direction, but at the smaller Phase-22-like magnitude rather than the larger Phase-20/21-like magnitude, on the *same* scenario that previously produced the larger magnitude.

## 11. Not Established

- Causal proof that scalar labeling (age-only or full) produces the observed improvement, as distinct from B's own baseline variance.
- A quantified relationship between scenario difficulty and gap magnitude.
- Any measured advantage of L over LA, or of LA over L.
- Resolution of the `27−24=3` alternative, in either direction.
- That the Phase 24 hometown/city contradiction or the Pittsburgh fabrication are connected to labeling, to each other, or to any specific mechanism — both remain recorded as separate, unexplained failure modes, not folded into the labeling hypothesis.
- Any production suitability claim for LA, L, or any renderer change.

## 12. STOP / Authorization Boundary

**No STOP condition was triggered** — this audit required no API call, no code change, and no historical artifact modification, and none was made. Stage 2 (any further real-generation experiment) is **not executed and not authorized by this document**. Any further evidence collection described in §9 would require a separate, explicit authorization, exactly as every phase since Phase 10A has required.

---

## Final statement: has the evidence crossed the decision boundary?

**No — the existing evidence has not crossed the decision boundary defined in `PHASE23_DECISION_BOUNDARY.md`.** The direction of the LA/L-over-B pattern has now reproduced in five independent samples without reversal, which is the strongest part of the current evidence base. But the magnitude of that pattern is unstable even on the identical scenario (10 to 40 points across four Pune-timeline samples), and this instability is on the same order as B's own baseline variance (20–70%) — meaning the current evidence cannot yet distinguish "labeling produces a real, exploitable effect whose size depends on scenario/context" from "no reliable effect exists beyond ordinary generation variance, and the consistent direction across five small samples is compatible with, though not conclusively explained by, chance." **This document does not choose between those readings and does not select a production action.**

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE25_DECISION_AUDIT.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10–24 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.
