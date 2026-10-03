# Phase 28 — Final Evidence & Decision Boundary Audit

**Zero API calls made to produce this document.** This is a Stage-1-only audit of already-published Phase 10A–27 evidence. No historical artifact, production file, mapping, resolver, renderer, persona data, or engine logic was modified. No new experiment is created or authorized by this document.

---

## 1. Purpose

Answer, from the evidence already collected through Phase 27: *does scalar labeling — specifically labeling `identity.age` — provide sufficiently reliable evidence of improved model use of persona facts to justify moving toward an engineering decision?* This document determines whether the existing Phase 23/25 decision boundary has been crossed, using that boundary as originally defined rather than a boundary redefined after seeing Phase 26/27's results.

## 2. Evidence Scope

This audit draws on `PHASE23_EVIDENCE_BASE.md`, `PHASE23_DECISION_BOUNDARY.md`, `PHASE25_DECISION_AUDIT.md`, `PHASE26_CONTROLLED_LABEL_EFFECT_DESIGN.md`, `PHASE26_CONTROLLED_LABEL_EFFECT_RESULTS.md`, `PHASE27_GENERALIZATION_AUDIT.md`, `PHASE27_GENERALIZATION_RESULTS.md`, and the underlying Phase 19–27 execution CSVs. Phases 10A–18 (the retrieval/mapping-closure chain) are treated as already-settled background, per `PHASE23_EVIDENCE_BASE.md` §1, and are not re-litigated here.

## 3. Consolidated Phase 19–27 Results

**Each sample is reported on its own terms. Samples are not combined or pooled merely because they point in the same direction, per instruction.**

| Phase | Scenario | Condition | N | CORRECT | WRONG | AMBIGUOUS | Rate | Observed gap (vs. B) | Statistical result | What it actually establishes |
|---|---|---|---:|---:|---:|---:|---:|---:|---|---|
| 19 | Pune timeline | B | 5 | 3 | 1 | 1 | 60% | — | none run | Reproduces Phase 16's n=1 direction under controlled repetition |
| 19 | Pune timeline | L | 5 | 5 | 0 | 0 | 100% | +40 pts | none run | Same as above |
| 20 | Pune timeline | B | 5 | 2 | 3 | 0 | 40% | — | none run | Baseline for the LA/L isolation |
| 20 | Pune timeline | LA | 5 | 4 | 0 | 1 | 80% | +40 pts | none run | First LA-vs-L side-by-side comparison; LA matched L |
| 20 | Pune timeline | L | 5 | 4 | 0 | 1 | 80% | +40 pts | none run | Matched LA exactly on the same calls |
| 21 | Pune timeline | B | 10 | 2 | 8 | 0 | 20% | — | none run | Confirms B's instability (lowest rate observed to that point) |
| 21 | Pune timeline | LA | 10 | 6 | 3 | 1 | 60% | +40 pts | none run | Independent repeat of the LA-over-B direction |
| 22 | Sister age-gap | B | 10 | 8 | 2 | 0 | 80% | — | none run | First independent scenario; B baseline much higher than Pune |
| 22 | Sister age-gap | LA | 10 | 9 | 1 | 0 | 90% | +10 pts | none run | Smaller gap; entity binding 20/20 correct in both conditions |
| 24 | Pune timeline | B | 10 | 7 | 2 | 1 | 70% | — | none run | B's highest observed Pune rate; wide within-scenario variance confirmed |
| 24 | Pune timeline | LA | 10 | 8 | 2 | 0 | 80% | +10 pts | none run | LA-vs-L both 8/10 (identical); hometown/city contradiction (LA) and unrelated-city fabrication (L) newly observed |
| 24 | Pune timeline | L | 10 | 8 | 2 | 0 | 80% | +10 pts | none run | Same as above |
| 26 | Pune timeline | B | 20 | 4 | 15 | 1 | 20% | — | Fisher's exact, p≈0.00036 | **First statistically significant B-vs-LA result in the program** |
| 26 | Pune timeline | LA | 20 | 16 | 2 | 2 | 80% | +60 pts | p<0.05, LA>B → **FOR H1** | Same as above; hometown/city contradiction (B) and fabricated move-age (B) newly observed |
| 27 | Sister age-gap | B | 20 | 14 | 6 | 0 | 70% | — | Fisher's exact, p≈0.235 | Independent, larger, statistically-tested repeat of Phase 22's scenario |
| 27 | Sister age-gap | LA | 20 | 18 | 2 | 0 | 90% | +20 pts | p≥0.05, gap>15pts → **INCONCLUSIVE** | Entity binding 40/40 correct in both; direction consistent but not significant |

**Direction summary**: across all seven independent B-vs-(LA or L) comparisons run in this program (Phases 19, 20×2, 21, 22, 24×2, 26, 27), LA or L has never scored below B. This is the single most consistent pattern in the entire evidence base.

## 4. Phenomenon: Does B vs LA Differ?

**Yes, on the Pune-timeline scenario specifically, with strong statistical support.** Phase 26's pre-registered, adequately-sized (n=20/group), interleaved, hypothesis-tested sample produced p≈0.00036 — the phenomenon (B and LA producing measurably different response distributions on this exact scenario) is established to a high degree of confidence for that scenario. This is a narrower claim than "labeling improves model behavior in general," but it is a real, statistically supported finding: **B and LA are not behaviorally interchangeable on the Pune-timeline scenario.**

## 5. Generalization Evidence

**Present but inconclusive.** The only other scenario tested is the sister age-gap scenario (Phase 22, Phase 27). Phase 22 (n=10, untested statistically) showed a 10-point gap; Phase 27 (n=20, interleaved, pre-registered Fisher's test) showed a 20-point gap that did not reach significance (p≈0.235). **No scenario beyond these two has been tested.** The directional consistency (LA≥B in both samples of both scenarios) is suggestive but explicitly falls short of the statistical confirmation Phase 26 achieved for the Pune scenario. Per `PHASE23_DECISION_BOUNDARY.md`'s own stated requirement ("replicated across more than two scenario types"), **generalization evidence currently spans exactly two scenario types, and only one of the two has crossed statistical significance.**

## 6. Variance vs. Label Effect

B's own CORRECT rate on the Pune-timeline scenario across five independent samples: 60% (19), 40% (20), 20% (21), 70% (24), 20% (26) — range 20–70%. LA's rate across four samples on the same scenario: 80% (20), 60% (21), 80% (24), 80% (26) — range 60–80%, narrower than B's own range. This asymmetry (B varies more than LA) is an observed pattern worth naming explicitly, though it is not itself proof of a labeling-caused variance reduction — it could equally reflect LA's samples happening not to include as unlucky a draw as Phase 21's B sample, given only four LA samples exist. **Ordinary generation variance is real and substantial** (demonstrated by B's own 50-point range on an unchanged scenario) and remains a live, evidence-grounded competing explanation for at least part of every observed gap, even where Phase 26 achieved statistical significance — a significant result rejects the *equal-rates* null hypothesis for that specific sample, but does not by itself quantify how much of the gap, if any, is a stable, exploitable effect versus a favorable-but-real draw from two overlapping but differently-centered distributions.

## 7. Scenario-Difficulty Analysis

B's mean CORRECT rate across five Pune-timeline samples ≈ 42% (average of 60, 40, 20, 70, 20). B's mean CORRECT rate across two sister-age-gap samples ≈ 75% (average of 80, 70). This is consistent with the sister scenario being intrinsically easier for B — plausibly because the required arithmetic (29−27=2) involves closer, smaller numbers than the Pune scenario's (27−21=6), and/or because the sister scenario's skill (`about`) and phrasing differ from the Pune scenario's (`general_chat`). **This remains a plausible, unproven interpretation** (as stated in `PHASE23_DECISION_BOUNDARY.md` §B and reaffirmed in `PHASE27_GENERALIZATION_AUDIT.md` §5) — no experiment in this program has isolated scenario difficulty as its own independent variable while holding the labeling manipulation constant across a controlled difficulty gradient. The two scenarios differ in more ways than difficulty alone (skill, field source, semantic domain), so difficulty cannot be cleanly separated from those other differences with the current evidence.

## 8. LA vs L

**Exactly two direct comparisons exist** (Phase 20: 4/5 vs 4/5; Phase 24: 8/10 vs 8/10), both showing **identical** scores. No sample has ever shown a measured difference between LA and L in either direction. Per instruction: **it is not inferred that full labeling (L) is necessary merely because LA and L were both tested** — the evidence provides no support for that. **It is equally not inferred that LA is universally sufficient merely because their observed scores matched** — two small, identical-scoring samples cannot rule out a difference too small to detect at this scale, in either direction. The honest position, unchanged from Phase 25 §7: **this question remains genuinely untested at a resolution capable of answering it**, and no phase since has added new LA-vs-L evidence (Phase 21, 22, 26, 27 all excluded L by design).

## 9. 27−24 Alternative

**Unchanged and unresolved.** Across all Pune-timeline responses collected in Phases 14–27 (approximately 95 responses at this point, per the running total in `PHASE25_DECISION_AUDIT.md` extended by Phase 26's 40 and Phase 27's 40 sister-scenario responses, which are not relevant to this specific alternative since they involve a different number pair), the substring "24" essentially never appears as the corporate-departure-age referent in a Pune-timeline response. The sister-scenario responses that mention "24" (Phase 27, calls 16/21/30/38) refer unambiguously to Arjun's age in an unrelated context and are not evidence for or against the Pune-scenario-specific 27−24=3 hypothesis. **The absence of "24" in visible output text is not treated as evidence that 24 was not used internally, in Phase 26/27 or anywhere else in this program** — this discipline is maintained without exception through every phase since Phase 15. No experiment run to date has been designed in a way capable of distinguishing this hypothesis from ordinary miscalculation (a point reinforced by Phase 24's discovery of "nine years" and "five years" wrong answers with no apparent numeric anchor at all, and Phase 26's discovery of a fabricated "eighteen" move-age).

## 10. Established Findings

- Retrieval (Question A) is fully resolved for every scenario tested through Phase 14 — restated from `PHASE23_EVIDENCE_BASE.md`, not re-audited here.
- A genuine present-fact/model-use (Question B) discrepancy exists and was first confirmed in Phase 14, with the required facts verified present via `selected_field_ids`, not inferred.
- **B and LA behave differently on the Pune-timeline scenario, to a high degree of statistical confidence** (Phase 26, p≈0.00036).
- The LA/L-over-B direction has held in all seven independent samples across two scenarios, with zero reversals.
- Entity-binding (correctly distinguishing Anjali from Arjun) has been flawless — 60/60 correct across Phase 22 and Phase 27 combined (20 + 40 calls, each counted separately, not pooled into a single rate here since they are different samples reported individually in §3).
- Two new factuality failure modes (hometown/current-city contradiction; unrelated-entity/location fabrication; fabricated numeric facts) have appeared across Phase 20, 24, and 26, in both B and LA/L conditions, at low, roughly comparable rates — none traced to a labeling-specific cause.

## 11. Unresolved Findings

- Whether the Phase 26 statistical result reflects a stable, generalizable effect or a favorable draw from two highly variable, overlapping distributions.
- Whether the sister-scenario gap (Phase 27, 20 points, p≈0.235) would reach significance at a larger, adequately-powered sample.
- Whether scenario difficulty, skill, or semantic domain (or some combination) drives the difference in effect magnitude between the two tested scenarios.
- Whether L provides anything beyond LA, or vice versa.
- The mechanism behind the Phase 26 "three years"/"four years"/"five years" family of B errors, and whether the 27−24 hypothesis explains any of them.
- The origin and connection (if any) of the newly observed hometown/city-contradiction and fabrication failure modes to labeling, condition, or scenario.

## 12. Decision-Critical vs. Non-Critical Uncertainty

| Uncertainty | Classification |
|---|---|
| Whether B and LA differ at all on the Pune-timeline scenario | **Already sufficiently answered** (yes, p≈0.00036) |
| Whether this difference generalizes beyond one scenario | **Material to an engineering decision** — this is the single most decision-relevant open question, and it is currently unresolved (INCONCLUSIVE) |
| Whether the effect reflects a stable, exploitable mechanism vs. B's own variance | **Material to an engineering decision** — directly determines whether any engineering investment would reliably pay off |
| Whether L adds anything beyond LA | **Scientifically interesting, not decision-critical** — LA alone is the simpler, already-positively-tested intervention; resolving L-vs-LA does not change whether *any* labeling change is worth pursuing, only which specific variant |
| The 27−24 mechanism | **Scientifically interesting, not decision-critical** — does not change whether labeling helps in observed practice, only why |
| Scenario-difficulty as an isolated variable | **Scientifically interesting, not decision-critical in isolation** — relevant context for interpreting generalization evidence, but resolving it precisely is not required to make a go/no-go call, since the generalization question (§5) can be answered empirically without first explaining *why* scenarios differ |
| The two new factuality failure modes (hometown/city, fabrication) | **Scientifically interesting and product-quality-relevant, but a separate track from the labeling hypothesis** — not decision-critical to the scalar-labeling question specifically, since they appear across conditions at comparable rates and are not attributed to labeling |
| Whether entity-binding could fail under harder conditions | **Already sufficiently answered for the scenarios tested** (60/60 correct) — not flagged as a live concern absent new evidence suggesting otherwise |

## 13. Existing Decision Boundary

Restated from `PHASE23_DECISION_BOUNDARY.md` §C and reaffirmed in `PHASE25_DECISION_AUDIT.md` §10, **without modification**: an engineering/production decision requires evidence of a stable, reproducible effect at a magnitude that exceeds B's own demonstrated baseline variance, **replicated across more than two scenario types**.

## 14. Boundary Assessment

**NO — the evidence has not crossed the existing decision boundary.**

The boundary explicitly requires replication "across more than two scenario types." Exactly two scenario types have been tested in this entire program (Pune timeline, sister age-gap). Even setting that count aside, the second scenario's result (Phase 27) did not itself reach statistical significance — it is directionally consistent but inconclusive. **The boundary, as originally written, is functioning exactly as intended here**: it was set at "more than two" specifically to prevent a single successful replication (or, as in this case, a single significant result plus one suggestive-but-inconclusive one) from being read as sufficient generalization evidence. This document does not find reason to revise that boundary — the "BOUNDARY ITSELF REQUIRES REVISION" option is explicitly not selected. The one place judgment was required that the original boundary did not explicitly anticipate is how to treat a "one significant + one inconclusive" evidence pattern; this document resolves that by treating it as **not yet meeting the boundary**, consistent with the boundary's evident purpose (requiring confirmed, not merely suggestive, replication).

## 15. Minimum Evidence Still Required

Two paths are stated, without designing, authorizing, or preferring either as a recommendation:

1. **A larger, adequately-powered confirmatory run on the sister-age-gap scenario itself**, to determine whether Phase 27's 20-point gap reaches significance at higher N. Using the same power-calculation method Phase 26's design used, and Phase 27's own observed rates (B=70%, LA=90%) as the effect-size estimate: `n ≈ (1.96+0.84)² × [0.7×0.3 + 0.9×0.1] / (0.2)² ≈ 7.84 × 0.30 / 0.04 ≈ 59` per group (≈118 total) for conventional 80% power — a figure presented for transparency only, exactly as Phase 26's design presented its own power calculation, not as an authorization.
2. **A third, independent scenario type**, meeting the same cleanliness criteria established in Phase 18/22 (unambiguous ground truth, no new mapping required, genuinely different fact pair), to satisfy the boundary's "more than two scenario types" requirement even if the sister scenario were later confirmed.

**Either path, if pursued, would need to be separately designed and authorized — this document does not select or initiate one.** Given the instruction's explicit permission to conclude that continued experimentation is unnecessary, this document notes: the case for stopping here is reasonable if the goal is limited to "has one scenario shown a real effect" (already answered, yes) — but the case for one more narrowly-scoped confirmatory experiment is also reasonable if the goal is the broader engineering decision the Phase 23/25 boundary was written for, since that boundary has not been met and the minimum gap to meeting it (a properly-powered version of an experiment already designed once, Phase 26's own methodology, applied to the existing second scenario) is well-defined and not open-ended.

## 16. STOP / Next Authorization Boundary

**No STOP condition was triggered.** This document required no API call, no code change, and no historical artifact modification, and none was made. No experiment is created or authorized here. Any further evidence collection described in §15 requires a separate, explicit authorization, exactly as every phase since Phase 10A has required.

---

## Final statement

**Has the evidence crossed the existing production/engineering decision boundary? NO.**

The phenomenon (B vs. LA differ) is established with high confidence on one scenario (Phase 26, p≈0.00036). Generalization evidence exists for exactly one additional scenario and is directionally consistent but statistically inconclusive (Phase 27, p≈0.235). The boundary's explicit "more than two scenario types" requirement is not met, and this document does not find the boundary itself in need of revision — it is functioning as designed. The minimum additional evidence needed is either a properly-powered confirmatory run on the already-tested sister scenario (≈59/group, ≈118 total calls, by the same method Phase 26 used) or a third independent scenario type — neither of which is designed, authorized, or recommended by this document.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE28_FINAL_EVIDENCE_BOUNDARY.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10–27 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.
