# Phase 26 — Controlled Label-Effect Resolution: Stage 1 Design

**Zero API calls made to produce this document.** This is a Stage 1 design/audit only. No historical Phase 10–25 artifact, production file, mapping, resolver, renderer, persona data, or engine logic was modified. Stage 2 is **not executed** by this document and requires separate, explicit authorization.

---

## 1. Purpose

Determine whether the observed B-vs-LA difference on the Pune-timeline scenario is statistically distinguishable from ordinary generation variance, using a pre-registered design capable of yielding **for**, **against**, or **inconclusive** as its outcome — not merely re-observing a direction, which the program has already established five times (Phases 19–22, 24) without resolving whether that direction reflects a real effect or falls within B's own demonstrated variance.

## 2. Decision Question

*"Is the B-vs-LA difference on the Pune-timeline scenario larger than what B's own already-measured variance would produce by chance, at a pre-registered significance threshold?"* This is a narrower, statistically operationalized version of the question Phase 25 left open.

## 3. Existing Evidence

Pooled from Phases 19, 20, 21, 24 (identical scenario, identical mapping, identical providers, four independent sessions):

| Sample | Condition | Correct | N | Rate |
|---|---|---:|---:|---:|
| 19 | B | 3 | 5 | 60% |
| 20 | B | 2 | 5 | 40% |
| 21 | B | 2 | 10 | 20% |
| 24 | B | 7 | 10 | 70% |
| **Pooled B** | | **14** | **30** | **46.7%** |
| 20 | LA | 4 | 5 | 80% |
| 21 | LA | 6 | 10 | 60% |
| 24 | LA | 8 | 10 | 80% |
| **Pooled LA** | | **18** | **25** | **72.0%** |

**These pooled figures are historical reference data only — they inform the sample-size calculation in §8, they are not treated as part of the new experiment's own result, and they are not re-litigated or altered here** (per the standing prohibition on modifying Phase 19–24 artifacts). The pooled B rate's own range (20–70%) is itself the clearest existing evidence of substantial within-condition variance.

## 4. Hypotheses

- **H1** — age labeling contributes a real improvement (LA's true rate exceeds B's true rate on this scenario).
- **H2** — the observed difference is primarily ordinary generation variance (B and LA have similar or identical true rates; the pooled gap reflects sampling noise).
- **H3** — the effect depends strongly on scenario difficulty. **Not directly testable by this single-scenario design** (per instruction, no new scenario is introduced) — this experiment can only note whether its own B rate falls inside or outside the already-observed 20–70% historical range, which bears on H2/H4 but cannot itself confirm or refute a difficulty-dependence claim, since that requires comparing across scenarios, which Phase 22 already did and this phase does not repeat.
- **H4** — the evidence is insufficient to distinguish H1 from H2. This is the pre-registered default outcome when the statistical test in §10 does not cross its threshold.

## 5. Experimental Conditions

**B vs LA only.** L is explicitly excluded, per instruction — Phase 25 §7 established that the existing LA-vs-L evidence (two direct comparisons, both showing identical scores) is too limited to be informative, and resolving that question is not necessary to answer this phase's narrower causality-vs-variance question.

- **B** — `ResolvedPersonaContextProvider` (variant "B"), unchanged since Phase 14.
- **LA** — `AgeOnlyLabeledScalarDiagnosticProvider` (variant "LA"), unchanged since Phase 20.

## 6. Controlled Variables

Pre-registered, to be re-verified live (zero cost) immediately before any Stage 2 call, exactly as in every prior phase's precondition gate:

- Fixed user message: **"Been in this city long?"** (unaltered — no new scenario).
- Identical persona (Simran), identical persona core version, identical conversation engine version.
- Identical skill: `general_chat`.
- Identical selected field IDs across B and LA: `{identity.name, personality.core_traits, identity.hometown, identity.current_city, communication.style, narrative_state.unresolved_threads, life_context.occupation, identity.age, life_context.important_life_events}` (the same set verified in Phases 14/16/19/20/21/24).
- Identical mapping fix applied (`identity.age` + `life_context.important_life_events` added to `general_chat`'s conditional fields — the same fix used in every prior phase testing this scenario).
- Identical absence of conversation history, memory, and user profile (fresh conversation + fresh `testUserId` per call, established pattern).
- **B payload definition**: bare, unlabeled top-level scalars (e.g., `27`), exactly as `ResolvedPersonaContextProvider` renders today.
- **LA payload definition**: identical to B except `identity.age` renders as `Age: 27`; every other line byte-identical to B.
- No restricted field (`intimate_anatomy`, `adult_intimacy`) present in either payload.
- Deterministic provider output, verified on repeat.
- Provider output verified verbatim in the captured request (provider-to-request equivalence), for both conditions.

## 7. Outcome Definition

**Primary metric**: proportion CORRECT (binary: CORRECT vs. NOT-CORRECT), where NOT-CORRECT includes both WRONG and AMBIGUOUS. This binary collapse is necessary for the statistical test in §10, but per instruction, **WRONG and AMBIGUOUS are recorded and reported separately in the raw results table regardless**, and are never silently merged in the qualitative write-up — only in the single summary statistic feeding the significance test.

**Secondary, separately tracked, non-collapsed dimensions** (never folded into the CORRECT/NOT-CORRECT score):
- **Hometown/current-city contradiction** (the failure mode first seen in Phase 20 call 2, recurring in Phase 24 call 20) — tracked as its own binary flag per call, reported as its own rate, not counted toward or against duration-correctness.
- **Unrelated-entity/location fabrication** (the Phase 24 "Pittsburgh" pattern) — tracked as its own binary flag per call, same treatment.
- **Presence of "24," "27," "21," "six," "three," or any other numeric/duration substring** — recorded per call as observable text properties only (per the standing rule: absence of "24" is never treated as evidence against the 27−24 hypothesis, and presence of "three" is never treated as evidence for it).

## 8. Sample-Size Justification

Established **before execution**, from the pooled historical rates in §3 (B=46.7%, LA=72.0%), using a standard two-proportion power calculation (two-sided α=0.05, target power=0.80):

```
n per group ≈ (z_α/2 + z_β)² × [p1(1−p1) + p2(1−p2)] / (p1−p2)²
            = (1.96 + 0.84)² × [0.467×0.533 + 0.720×0.280] / (0.253)²
            = 7.84 × 0.4506 / 0.0640
            ≈ 55.2 → rounds up to 56 per group (112 total)
```

**This is the statistically-justified minimum sample size for conventional 80% power against the effect size implied by pooled historical data.** It substantially exceeds every previous single-phase API budget in this program (the largest to date was Phase 10D's 90 calls, and no single B-vs-LA phase has exceeded 20 calls). This is disclosed plainly, not minimized: **a properly powered resolution of this question is expensive relative to this program's established increments.**

**A smaller, explicitly underpowered alternative** is also computed for transparency, since a full 112-call authorization may not be granted: at n=20 per group (40 total, matching this program's largest single B-vs-LA block to date), the same calculation yields:

```
SE = sqrt(0.467×0.533/20 + 0.720×0.280/20) = sqrt(0.01245 + 0.01008) = 0.1501
z  = 0.253 / 0.1501 = 1.686
power ≈ Φ(1.686 − 1.96) = Φ(−0.274) ≈ 0.39
```

**At n=20 per group, statistical power is approximately 39%** against the pooled-historical effect size — meaning a non-significant result at this sample size would be **expected to occur more often than not even if a real effect of this magnitude exists**, and must be classified INCONCLUSIVE rather than treated as evidence for H2. This is precisely the design property required by the "critical discipline" instruction: a null result at this smaller N does not automatically become evidence against H1.

**No sample size is chosen or authorized by this document.** Two figures are presented (56/group for adequately-powered resolution; 20/group as a smaller, explicitly underpowered interim step consistent with this program's incremental-authorization culture) so that whoever authorizes Stage 2 can select a sample size with full knowledge of its statistical consequences, rather than this document silently picking one.

## 9. Randomization / Execution Order

Per instruction, order is **predetermined**, not randomized in the statistical sense (no random-number generator is invoked; this program has never used one). Consistent with every prior phase, the order will be fixed and disclosed before Stage 2 begins, and not altered after any response is seen. Two candidate orderings are noted for whichever sample size is authorized:

- **Blocked** (matching Phase 19–24 convention): all B calls first, then all LA calls — simplest to audit, consistent with prior phases' pattern, but cannot rule out a time-varying confound (e.g., API/model drift within a session) affecting one block more than the other.
- **Interleaved** (B, LA, B, LA, ...): reduces exposure to any such time-varying confound, at the cost of deviating from the blocked convention used in every prior phase of this program.

**No ordering is selected here.** Whichever is chosen must be fixed in writing before Stage 2's first call and not altered afterward, per the standing discipline.

## 10. Statistical Analysis Plan

- **Test**: two-sided Fisher's exact test on the 2×2 table (CORRECT vs. NOT-CORRECT, by condition), chosen over a chi-square test because it is exact and valid at the small-to-moderate sample sizes under consideration here, rather than relying on a large-sample approximation.
- **Pre-registered significance threshold**: α = 0.05, two-sided.
- **Decision thresholds** (fixed before Stage 2, not adjustable afterward):
  - **Evidence FOR H1**: Fisher's exact p < 0.05, AND the observed LA rate exceeds the observed B rate (direction consistent with H1, not merely a significant difference in either direction).
  - **Evidence FOR H2 (variance-dominant)**: p ≥ 0.05, AND the observed gap between LA and B is **≤ 15 percentage points** — chosen because Phase 22 and Phase 24 both showed gaps in this range on scenarios/samples that could plausibly reflect ordinary variance, giving this threshold a direct evidentiary anchor rather than an arbitrary cutoff.
  - **INCONCLUSIVE (H4)**: p ≥ 0.05, AND the observed gap exceeds 15 percentage points — the test failed to reach significance, but the gap is still large enough that a real effect cannot be ruled out at this sample size (this is the expected outcome region at n=20/group, per §8's power calculation).
  - A significant result with **LA scoring below B** would also be classified as evidence AGAINST H1 specifically (not merely "inconclusive"), since it would directly contradict the direction observed in all five prior samples.
- **These thresholds are fixed now, before any Stage 2 call, and will not be changed after Stage 2 begins**, per instruction.

## 11. Interpretation Rules

- A result classified FOR H1 does **not** establish causation or production suitability — it establishes that, at the pre-registered threshold, this specific sample's gap exceeds what would be expected from B's own historical variance alone. This is a narrower, more defensible claim than "labeling works."
- A result classified FOR H2 does **not** establish that no effect exists anywhere — only that this sample did not detect one distinguishable from variance, on this one scenario, at this sample size.
- An INCONCLUSIVE result is a valid, expected, and honestly-reportable outcome — not a failure of the experiment. Given §8's power analysis, INCONCLUSIVE is the single most likely outcome at any sample size smaller than ~56/group.
- **No result, of any kind, will be used to make a production recommendation** — this experiment answers a narrower statistical question (§2), not a deployment question.
- H3 (scenario-difficulty dependence) cannot be confirmed or refuted by this single-scenario design; any discussion of it in the eventual results document will be explicitly labeled as qualitative and out of this experiment's scope to test directly.

## 12. 27−24 Alternative

**Preserved exactly as in every prior phase.** This experiment will record whether "24" appears in any response, purely as an observed text property. **The absence of "24" will not be treated as evidence that 24 was not used internally**, and the presence of "three years" (the value 27−24 would produce) will not be treated as evidence that it was — consistent with Phase 24's finding that wrong answers in this scenario have also included values ("nine years," "five years") with no apparent connection to 24 at all, which weakens (without disproving) the specific 27−24 mechanism as the primary source of "three years" errors, while leaving the underlying hypothesis about internal computation exactly as untestable by output-text analysis as it has always been.

## 13. Failure-Mode Handling

Per instruction, the hometown/current-city contradiction and any unrelated-entity/location fabrication are tracked as **separate factuality dimensions**, per call, in both conditions:

- A call exhibiting either failure mode is still scored CORRECT/WRONG/AMBIGUOUS on the duration question **independently** — e.g., a response stating "about six years" while also fabricating an unrelated city name is recorded as duration-CORRECT AND fabrication-flagged=true, not silently downgraded to WRONG on the duration axis (this differs from the ad hoc treatment in Phase 24, where the Pittsburgh call was scored WRONG overall; Phase 26 pre-registers the cleaner, two-axis treatment instead, disclosed here as a deliberate methodological improvement over Phase 24's approach, not a silent change).
- Neither failure mode is attributed to labeling, to either condition specifically, or to any mechanism — both are reported as raw per-condition rates only.

## 14. STOP Conditions

Stop immediately, before or during Stage 2, if:
- Selected fields differ between B and LA at precondition-check time.
- The B-vs-LA payload diff is anything other than the single age-label line.
- A restricted field appears in either payload.
- Provider output fails to match the captured request for any call.
- The scenario wording is found to differ from "Been in this city long?" for any reason.
- The predetermined execution order cannot be maintained.
- Any post-hoc modification (to scoring rubric, thresholds, mapping, or providers) becomes necessary to obtain or interpret a result.
- Infrastructure/API failures leave the predetermined sample incomplete in a way that cannot be resolved by the pre-declared failure-handling rule (not itself designed in this document, since instruction scopes Stage 1 to design/audit only — a Stage 2 authorization would need to state this rule explicitly, following the pattern of every prior phase).

## 15. Stage 2 Authorization Boundary

**Stage 2 is not executed and not authorized by this document.** Before Stage 2 can proceed, a separate authorization must specify: (a) which sample size (56/group for adequate power, 20/group as a smaller disclosed-underpowered step, or another explicitly justified figure), and (b) which execution order (blocked or interleaved, per §9). Both decision thresholds (§10) and the failure-mode handling (§13) are otherwise fully pre-registered and ready to apply once a sample size and order are authorized.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE26_CONTROLLED_LABEL_EFFECT_DESIGN.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10–25 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.

---

## Summary (as requested)

- **Predetermined sample size**: not chosen by this document — two figures computed and disclosed: **56 per group (112 total)** for conventional 80% power against the pooled-historical effect size, or **20 per group (40 total)** as an explicitly underpowered (~39% power) but cost-consistent interim step. Selection is deferred to Stage 2 authorization.
- **Exact execution plan**: B vs LA only (no L), on the unchanged Pune-timeline scenario, fixed order (blocked or interleaved — not yet chosen), two-axis scoring (duration CORRECT/WRONG/AMBIGUOUS, kept separate from hometown/city-contradiction and fabrication flags).
- **Exact decision thresholds**: Fisher's exact test, α=0.05 two-sided; FOR H1 if p<0.05 and LA>B; FOR H2 if p≥0.05 and gap≤15 points; INCONCLUSIVE if p≥0.05 and gap>15 points; AGAINST H1 if significant with LA<B.
- **Stage 2 readiness**: design is complete and internally consistent; **Stage 2 is ready for separate authorization**, pending only a sample-size and execution-order decision by the authorizing instruction.
