# Phase 9 — Next Experiments

Two experiments are scientifically justified by the evidence in `PHASE9_EVIDENCE_MATRIX.md` and `PHASE9_POST_PHASE8_ANALYSIS.md`. A third category (a mechanistically distinct fix for personal-narrative fabrication) was considered and explicitly **not** proposed — see the closing section. No production code, prompt, mapping, or persona data is changed by this document; both experiments are designs only, pending separate authorization to execute.

They are independent of each other (no experimental dependency) and are ordered by how foundational the question they answer is, not by ease or product preference.

---

## Experiment 1 — Isolating selection from structure (the missing 2×2 cell)

**Hypothesis:** B/C's factuality advantage over A is explained primarily by *structure/rendering format*, with *selection* contributing efficiency but little or no additional quality beyond what structure alone provides.

**Exact mechanism to be tested:** render the SAME fields that variant B would select, using RAW (unstructured, JSON-string) formatting instead of `PersonaContextRenderer`'s headed markdown. Compare this new condition against the three already-established points on the 2×2 grid.

**Why previous experiments do not already answer this:** Phase 6 compared A (full+raw) against B/C (selected+structured) — two variables changed at once. Phase 7 compared A (full+raw) against D (full+structured) — isolates structure, holding volume constant, but says nothing about selection. No experiment has ever held structure constant while varying selection. This is the one cell of the 2×2 matrix in `PHASE9_POST_PHASE8_ANALYSIS.md` §1 that has never been run.

**Exact source/code location:** a new class alongside `StructuredFullDocumentContextProvider.kt` (same package, same isolation pattern) — e.g. `SelectiveRawContextProvider.kt` — that calls `PersonaContextResolver.resolve(..., variant="B")` to get the SAME selected `fieldValueMap`-equivalent field set, but instead of passing it to `PersonaContextRenderer.render()`, serializes it directly to a compact JSON string (mirroring exactly how `resolveVariantA()` serializes the full document, for a true apples-to-apples formatting comparison). Wired via the same `TestChatService.buildEngineFor()` pattern used for variant "D" (an additive `variant == "E-selective-raw"` branch, or similar label — never reusing "A"/"B"/"C"/"D").

**Minimum isolated change:** one new provider file; one new branch in `TestChatService.buildEngineFor()` (additive, default-null, does not touch A/B/C/D). `PersonaContextResolver.kt`, `PersonaContextRenderer.kt`, all field mappings, and the Conversation Engine remain untouched.

**Control condition:** variant A (full+raw) and variant D (full+structured) — **reused from Phase 7's existing frozen data, zero new calls** for these two.

**Treatment conditions:**
1. **New: selected+raw** (the missing cell) — real calls required.
2. **B, re-run on the identical 30-scenario corpus Phase 7 used for A/D** — real calls required, because Phase 6's existing B data was collected on a different, broader corpus (149+79 scenarios across all categories) and mixing corpora would undermine a clean 4-way comparison. This is not a redundant re-run of Phase 6 — it is the same treatment on a corpus that makes it directly comparable to A/D/selected-raw for the first time.

**Scenario corpus:** the exact same 30 fact-heavy scenarios from Phase 7's `phase7StructuredFullDocumentProbe()` (`identityAbout()` + the 10 fact-heavy `lifeContext()` scenarios), for direct numerical comparability with the already-recorded A=0%/D=96.7% results.

**Scoring method:** identical factuality judgment scheme used in Phase 7 (CORRECT / WRONG / AMBIGUOUS against the same ground-truth facts), blinded (opaque response IDs, condition mapping separate, unblind only after scoring), reusing the Phase 6/7 blinding harness.

**Expected API calls:** 30 (selective+raw) + 30 (B re-run on this corpus) = **60 real calls.** A and D contribute 0 new calls (existing frozen data).

**STOP conditions:** any restricted-field leakage (not expected — no restricted fields are in this scenario set, but checked); resolver non-determinism on repeat; the new provider's `includedFieldIds` failing to exactly match variant B's for the same scenario (would mean the "selective" part of the treatment isn't actually isolated correctly — a wiring bug, not a finding).

**Regression conditions:** N/A in the usual sense (this experiment doesn't modify anything adopted) — the only "regression" concept here is a wiring defect that would invalidate the comparison, covered by the STOP condition above.

**Rollback:** delete the one new provider file and the one new `TestChatService` branch — no persisted state, no production impact, identical rollback profile to Phase 7's variant D addition.

**What decision this enables:** directly answers whether "use selective retrieval" and "use structured rendering" are actually two separate, independently-valuable design choices (in which case a future architecture could in principle combine either alone) or one inseparable effect (in which case B/C's advantage should continue to be attributed to the combination, not to selection specifically). This is the highest-value open question identified across Phases 6–9, because every existing quality claim in favor of B/C over A currently rests on a confound this experiment would resolve.

---

## Experiment 2 — TC-6 refinement: complete the field-description fix

**Hypothesis:** TC-6's mechanism (enriched conditional-field descriptions fix indirect-phrasing exclusion) is correct in principle — 6 of 8 flip probes worked on the first attempt — and the 2 failures are fully explained by one missing colloquial synonym ("mom"). A description including common colloquial address terms will pass Stage 1 cleanly (8/8), with the same zero-over-triggering and determinism properties already demonstrated.

**Why this is not "retrying a failed hypothesis with different wording":** TC-6's hypothesis was about a *mechanism* (word-overlap scoring responds to description content), not about a specific string. That mechanism did not fail — it succeeded on 75% of direct tests and its one gap has a fully diagnosed, narrow cause (missing lexical coverage, not a wrong theory). This is categorically different from Candidate E, where the *underlying idea* (an abstract instruction reduces model-behavioral fabrication) itself produced a null result across all 6 tested instances — there is no comparable "just missing one word" explanation for Candidate E's failure. Re-running TC-6 with corrected lexical coverage tests the same confirmed-partially-working mechanism to completion; re-running Candidate E with different wording would test the same rejected mechanism again.

**Exact source/code location:** identical to TC-6 — `PersonaFieldRegistryRepository.update()` applied in-memory to the single `life_context.family` row, never touching `PersonaContextPocSeed.kt`.

**Minimum isolated change:** the description string only. Proposed revision (to be locked immediately before running, not before, per the same discipline as the original): add colloquial terms — `"family father mother mom mum dad papa sister brother parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"`. Nothing else changes.

**Control condition:** BASELINE (current `description = "life_context.family"`), variants B and C — identical to TC-6's original design.

**Treatment condition:** the revised TUNED description, variants B and C.

**Scenario corpus:** identical to TC-6's original 11-probe set (8 flip + 3 control), unchanged, so this is a true continuation of the same experiment rather than a new one with cherry-picked easier scenarios.

**Scoring method:**
- **Stage 1 (0 API cost, mandatory gate):** re-run the exact deterministic check already built (`tc6Stage1DeterministicCheck()`, updated only with the new description string) — must achieve 8/8 flips fixed, 0/3 controls over-triggered, deterministic on repeat, before any API call.
- **Stage 2 (only if Stage 1 passes):** the original, already-designed 44-call pilot (8 flip scenarios × 2 variants × 2 conditions = 32, + 3 controls × 2 variants × 2 conditions = 12), blinded per the Phase 6/7/8 discipline.

**Expected API calls:** 0 if Stage 1 fails again (would itself be a new, reportable finding); 44 if Stage 1 passes.

**STOP conditions:** identical to the original TC-6 design — any control scenario gains the family field under TUNED (over-triggering); any restricted-field/determinism issue (structurally not expected, checked anyway); any Stage 1 flip probe still failing (would mean a further, still-undiagnosed gap, to be reported rather than iterated on again within the same experiment).

**Regression conditions:** any new PERSONA_FACT_OMISSION/FACTUAL_CONTRADICTION tag on a control scenario at Stage 2; any token-usage increase on a scenario outside the flip set.

**Rollback:** identical to the original — the change exists only in one test's in-memory database; nothing persists to roll back.

**What decision this enables:** whether the field-description mechanism (a narrow, well-isolated, already-mostly-proven fix) is ready to be considered for a real tuning pass, independent of and unaffected by whatever Experiment 1 finds about the larger structure-vs-selection question. This is a self-contained, low-cost, high-confidence experiment that does not need to wait on anything else.

---

## Category considered and not proposed: a third personal-narrative-fabrication experiment

Per `PHASE9_POST_PHASE8_ANALYSIS.md` §4, Candidate E's failure does not prove all engine-level interventions are impossible — but every alternative considered (a differently-worded instruction; a more forceful version of the same instruction) is a **same-mechanism retry**, explicitly barred. The one genuinely different mechanism identified (in-context worked examples instead of an abstract rule) is still, at bottom, another Conversation-Engine prompt edit targeting the same failure class, and there is no existing evidence — from this program or cited elsewhere — establishing that examples would succeed where an explicit rule did not; proposing it now would be a plausible guess dressed as a justified experiment, not a hypothesis backed by a "why previous experiments do not already answer it" argument as strong as Experiments 1 and 2's. Per Step 7's explicit allowance, this is reported as: **no additional experiment is currently justified for personal-narrative fabrication.**
