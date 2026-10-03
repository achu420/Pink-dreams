# Phase 31 — Production Decision Boundary Re-Audit

**Zero API calls. Zero production, mapping, resolver, or renderer changes.** This is a synthesis of already-published evidence through Phase 30, evaluated against the pre-existing decision boundary exactly as originally written. No historical Phase 10–30 artifact was modified. No new experiment is designed or authorized. No production recommendation is made.

---

## 1. Purpose

Determine whether the evidence accumulated through Phase 30 is now sufficient for an engineering decision under the boundary defined in `PHASE23_DECISION_BOUNDARY.md`, without modifying that boundary in light of Phase 30's favorable result, and without conflating statistical significance with a causal or production-readiness claim.

## 2. Source Evidence

`PHASE23_EVIDENCE_BASE.md`, `PHASE23_DECISION_BOUNDARY.md`, `PHASE25_DECISION_AUDIT.md`, `PHASE28_FINAL_EVIDENCE_BOUNDARY.md`, `PHASE29_GENERALIZATION_PATH_AUDIT.md`, `PHASE29_THIRD_SCENARIO_INVENTORY.md`, `PHASE30_CONFIRMATORY_RESULTS.md`, and the underlying Phase 19–30 execution data.

## 3. Original Decision Boundary

Reproduced **literally**, from `PHASE23_DECISION_BOUNDARY.md` §C, unchanged through every subsequent phase (Phase 25 §10, Phase 28 §13):

> "A stable, reproducible effect at a magnitude exceeding B's own demonstrated baseline variance, replicated across more than two scenario types."

This has three distinct clauses, each independently checkable: (1) stable and reproducible, (2) magnitude exceeds B's own baseline variance, (3) replicated across **more than two** scenario types.

## 4. Phase 30 Update

Phase 30 (118 calls, 59×B/59×LA, interleaved, pre-registered) found B=46/59 (78.0%), LA=55/59 (93.2%), a 15.2-point gap, Fisher's exact p≈0.0336 — significant at the pre-registered α=0.05. This is the **second** scenario type (after Phase 26's Pune-timeline result, p≈0.00036) to independently cross the statistical-significance threshold. The direction (LA≥B) has now held across all eight independent B-vs-(LA or L) samples run in this program, with zero reversals.

## 5. Requirement-by-Requirement Boundary Check

| Requirement | Status | Evidence |
|---|---|---|
| (1) Stable, reproducible effect | **Partially met, with a caveat** | The *direction* is reproducible (8/8 samples, no reversal). The *magnitude* is not stable: on the Pune-timeline scenario alone, the observed gap has ranged from 10 to 60 percentage points across five samples (Phases 19–21, 24, 26); on the sister scenario, it has ranged from 10 to 20.6 points across three samples (Phase 22, 27, 30, computing Phase 22's 10 points, Phase 27's 20 points, Phase 30's 15.2 points). "Reproducible direction, unstable magnitude" is the precise, literal characterization — the boundary's language ("stable... effect") is not unambiguously satisfied by a direction-only reproducibility. |
| (2) Magnitude exceeds B's own baseline variance | **Met for Phase 26, arguably met for Phase 30** | Phase 26's 60-point gap clearly exceeds B's own demonstrated Pune-scenario variance (a 50-point range, 20–70%). Phase 30's 15.2-point gap is smaller than B's Pune-scenario variance range but larger than B's own narrower sister-scenario variance range (70–80%, a 10-point range across three samples) — so this requirement is met on its own terms for the sister scenario specifically, though by a narrower margin than Phase 26's. |
| (3) Replicated across **more than two** scenario types | **NOT met** | Exactly two scenario types have been tested in this entire program (Pune timeline, sister age-gap). The boundary's own wording is unambiguous: "more than two." Two, however strongly each is now individually evidenced, is not more than two. Per `PHASE29_THIRD_SCENARIO_INVENTORY.md`, no third clean (Classification-A) scenario currently exists in the corpus without introducing a new retrieval/mapping change explicitly outside this investigation's scope. |

**Because requirement (3) is a literal, unambiguous numeric threshold and is unmet regardless of how strongly (1) and (2) are satisfied, the boundary as a whole is not crossed.** This is a clean, single-clause failure, not a matter of overall weight-of-evidence judgment.

## 6. Established Evidence

- Phase 26's statistically significant result on the Pune-timeline scenario (p≈0.00036, 60-point gap, n=20/group).
- Phase 30's statistically significant result on the sister age-gap scenario (p≈0.0336, 15.2-point gap, n=59/group).
- The direction of the effect (LA/L ≥ B) across all eight accumulated independent samples, with zero reversals.
- Both tested scenario types have now independently crossed the pre-registered α=0.05 threshold — a fact true regardless of what it does or does not imply about the underlying mechanism.

## 7. Supported Interpretations (not established as fact)

- That a genuine labeling-related effect exists, distinct from ordinary generation variance — supported by the consistency and significance of the pattern, but not proven; a significant Fisher's exact test rejects an equal-rates null hypothesis for a given sample, it does not identify or confirm a causal mechanism.
- That the effect is causal — not established by any experiment in this program; no design here isolates causal mechanism from correlation beyond controlling the single formatting variable.
- That the effect is specifically attributable to *age* labeling rather than labeling in general — the LA-vs-L evidence remains exactly as thin as documented in Phase 25/28 (two direct comparisons, both showing identical scores); nothing in Phase 30 adds to this question, since Phase 30 (like Phase 26/27) excluded L by design.
- That the effect generalizes beyond these two scenario types, this one persona, or these two skills (`general_chat`, `about`) — not established; this is precisely the boundary's unmet third clause.
- That the magnitude is stable — explicitly not supported; see §5's discussion of the 10–60-point range on Pune and the 10–20.6-point range on the sister scenario.

## 8. Unresolved Questions

- The 27−24 alternative explanation (Phase 15) — untouched by Phase 26 or Phase 30; absence of "24" in output text is not treated as evidence against internal use, per the standing rule preserved without exception since Phase 15.
- LA vs. L — no new evidence since Phase 24; remains genuinely untested at a resolution capable of answering it.
- The exact mechanism behind the observed effect (label-salience, position-in-payload, some other correlated property of the LA/L rendering) — not isolated by any experiment in this program.
- Broader production behavior outside the two tested scenarios, this persona, and these two skills — entirely unaddressed.

## 9. Engineering-Relevant Considerations (documented, not decided)

If engineering were to evaluate B vs. LA for any purpose, the following would need consideration — none of these is assessed, ranked, or resolved by this document:

- **Behavioral benefit demonstrated in the tested scenarios**: a statistically significant improvement exists on two derived-numeric-reasoning scenarios specifically; no evidence exists for ordinary scalar recall (Phase 17 found no effect there, attributed to a ceiling effect) or for any other task category.
- **Stability of the observed effect**: magnitude has varied substantially (10–60 points) even within the single Pune-timeline scenario across five samples — any engineering estimate of expected benefit would need to account for this variance, not treat any single sample's gap as the expected effect size.
- **Token/context cost**: not measured in this program for LA specifically (Phase 26/27/30 did not capture token counts) — the original Phase 10D/10E token analysis concerned selection/format, not scalar labeling, and does not directly transfer.
- **Rendering complexity**: `AgeOnlyLabeledScalarDiagnosticProvider` is a small, isolated diagnostic class; a production implementation would require different engineering (e.g., a configurable labeling rule inside `PersonaContextRenderer.kt` itself) not designed or evaluated in this program.
- **Regression risk**: no experiment in this program tested LA-style labeling against the full Phase 10D/10E corpus or any broader regression set — only two narrow, hand-selected scenarios.
- **Effects on non-numeric scalar facts**: entirely untested — every scenario in this program specifically targeted a derived-numeric-reasoning task; labeling's effect (if any) on non-numeric scalar recall or presentation is unknown.
- **Interaction with other persona/context fields**: not tested — every experiment held the field set constant per scenario; no experiment varied which fields were present alongside a labeled age.
- **Whether the tested scenarios represent the intended production workload**: not assessed in this program at all — this is a question about real user query distributions, which this Question-B investigation never touched.

## 10. Is Further Experimentation Decision-Critical?

- **What specific uncertainty would a third scenario resolve?** It would directly address the boundary's unmet third clause ("more than two scenario types") — this is the *only* uncertainty a third scenario type would resolve that the existing two cannot. It would not meaningfully add to the causality, mechanism, LA-vs-L, or 27−24 questions, all of which are separately tracked and none of which a third derived-numeric scenario would resolve any more than the existing two already have or have not.
- **Would it answer a decision-critical question or merely strengthen an already-established observation?** For the specific literal boundary requirement, it would answer a decision-critical question (satisfying "more than two"). For the broader causality/mechanism questions, it would only strengthen an already-established directional observation, not resolve them.
- **Is the absence of a third clean scenario a limitation of the corpus rather than an unresolved retrieval problem?** **Yes, explicitly.** Per `PHASE29_THIRD_SCENARIO_INVENTORY.md`, retrieval itself is not the obstacle — every candidate that failed did so for reasons unrelated to whether facts are selectable (confound with an existing mechanism, ambiguous ground truth, or structural incompatibility with the labeling mechanism), except for a small number blocked by a weak field description or an unregistered field, both of which would require *new* mapping/registration work — a different kind of task than anything this Question-B investigation has done, and one that would reopen exactly the retrieval-completeness work this chain has deliberately kept separate since Phase 14.
- **Would another Pune/sister replication materially answer a question that Phase 26 + Phase 30 have not already addressed?** **No, not for the boundary's third clause.** A third replication of either already-tested scenario type would not change the "two scenario types tested" count — replications of the same *type* do not become a new type by being repeated. Such a replication could narrow uncertainty about magnitude stability (a real, unresolved question, per §7) but would not move the literal boundary requirement at all.

**Conclusion of this section**: this document does not recommend a third experiment merely because more data could always be collected, and does not recommend stopping merely because two results are now significant. The honest position is narrower than either: **the specific evidence gap remaining is not an experimental-design gap that more runs of the existing scenarios can close — it is a corpus-availability gap that only a genuinely new, independently-constructed scenario type (requiring new engineering work outside this investigation's scope) could close, or a boundary-interpretation question that only a decision-maker, not another experiment, can resolve.**

## 11. Boundary Conclusion

**NO.** The original Phase 23/25/28 boundary, applied literally and without modification, has **not** been crossed. Requirement (3) — replication across more than two scenario types — is unambiguously unmet, and this alone is sufficient to answer NO regardless of how strongly requirements (1) and (2) are satisfied. **What remains missing, precisely**: a third, structurally independent, clean (Classification-A) scenario type, statistically tested at an adequately powered sample size, showing the same direction. Per Phase 29's own corpus inventory, no such scenario currently exists without new mapping/registration engineering work.

## 12. Evidence Conclusion

Separately from the boundary question: **the accumulated experimental evidence now supports the existence of a real, statistically detectable, directionally consistent B-vs-LA (and B-vs-L, where tested) difference on two independent scenario types, both crossing the pre-registered α=0.05 threshold, with a magnitude that varies substantially and unpredictably between (and even within) those two scenario types.** This is a materially stronger evidentiary position than existed after Phase 25 (no scenario yet statistically confirmed) or Phase 28/29 (one scenario confirmed, one inconclusive). It does not, on its own, establish causation, mechanism, or generalization beyond the tested scope. **This document explicitly does not treat the strength of this evidence conclusion as license to revise the boundary conclusion** — the two are kept separate, per instruction.

## 13. What This Phase Does Not Decide

- Whether B or LA should ship, or whether either should replace the other in any capacity.
- Whether the original boundary's "more than two scenario types" requirement is the correct threshold for this or any future decision — noted as possibly conservative in light of two-for-two significant results, but **this observation is not treated as permission to lower or reinterpret the requirement**.
- Whether to pursue a third scenario type via new mapping/registration work (a different kind of engineering task than anything performed in this Question-B chain).
- Whether to accept the current two-scenario evidence as sufficient for some purpose narrower than the original boundary's stated scope (that would itself be a boundary revision, which this document does not perform).
- The causal mechanism, the LA-vs-L question, or the 27−24 alternative — all remain exactly as unresolved as stated in §8.

---

## Final answer to the phase's stated objective

**After Phase 30, this program is no longer primarily solving an evidence problem for the two scenario types it has tested — that evidence is now about as strong and consistent as two independently significant, directionally-aligned results can make it, given the sample sizes used.** The remaining issue has become an explicit **engineering/policy decision under a boundary whose literal wording is not met**: whether to treat the current two-for-two significant result as sufficient despite the boundary's "more than two" requirement, to commission new engineering work to construct a genuinely independent third scenario, or to hold the boundary as originally written and await further evidence that this document does not itself propose collecting. **This document answers that the boundary, as written, says NO — and stops there, without choosing among the paths that answer opens up.**

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE31_PRODUCTION_BOUNDARY_REAUDIT.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10–30 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.

## Explicit confirmation

Zero API calls were made. Zero production, mapping, resolver, or renderer changes were made. No historical artifact (Phase 10 through Phase 30) was modified. No new experiment is designed or authorized. No production recommendation is made. Phase 32 is not started, per instruction.
