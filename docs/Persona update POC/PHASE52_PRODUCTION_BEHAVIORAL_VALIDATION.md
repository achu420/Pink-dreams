# Phase 52 — Controlled Production Behavioral Validation

**158 real API calls made** (40 Pune-timeline, corrected run + 118 sister age-gap), plus 40 additional real calls from a disclosed, invalidated first Pune attempt (§3) — **198 total real API calls this phase**, model `deepseek/deepseek-v4-flash-0731` throughout, matching the historical Phase 26/30 configuration exactly.

---

## 1. Objective

Determine whether the B-vs-LA behavioral difference established by the research/diagnostic harness (Phase 26 Pune-timeline, p≈0.00036; Phase 30 sister age-gap, p≈0.0336) survives when B and LA are delivered through the actual production Strategy-D pipeline (`PersonaFieldAuthorizer` → `ProductionPersonaContextService` → `PersonaContextResolutionStage`) rather than the old research-only `RepositoryContextAssembler.personaContextProvider` hook those phases used.

Per the phase's own discipline, two questions were kept separate throughout:
- **Track A** — does the production pipeline deliver the intended B/LA condition to the model?
- **Track B** — does LA produce the expected behavioral difference versus B?

## 2. Production Pipeline Tested

**A critical Step 1 finding changed the entire experimental design before any real API call was made.** Source tracing (`RepositoryContextAssembler.assemble()`, `PersonaContextResolutionStage.resolve()`) revealed that `ChatEngineFactory.build()` unconditionally wires the Phase 49 Strategy-D `PersonaContextResolutionStage` *in addition to* the old research `personaContextProvider` hook. Both write to the same `ContextBlockType.PERSONA` block; Strategy-D runs second (post-skill-selection) and replaces it if `PersonaFieldAuthorizer` returns a different, usable result. A zero-cost verification test (`Phase52PipelineControlVerificationTest`, no network call) confirmed this empirically: pinning the old-mechanism `AgeOnlyLabeledScalarDiagnosticProvider` (LA) produced a final captured request containing **bare `27`, not `Age: 27`** — the LA condition was being silently destroyed by Strategy-D's own `BareScalarRendering`-hardcoded resolution.

**This means `TestChatService.personaContextVariant` — the mechanism Phase 26/30 used — can no longer control what the model receives, on the current codebase.** This is not a defect introduced by this phase; it is a real, previously-undocumented interaction between two features shipped in different phases (the Phase 6-44 research hook and the Phase 49 production stage), discovered here for the first time.

**Resolution, per this phase's own explicit instruction** ("If B and LA cannot currently be selected cleanly through production code, implement the smallest experiment-only configuration mechanism necessary. Do NOT create a second production architecture."): this experiment controls B vs LA at the one point that genuinely determines final output today — which `ScalarRenderingPolicy` is injected into `ProductionPersonaContextService`, which the real, unmodified `PersonaContextResolutionStage` then uses. Every other component is the real production class: `PersonaFieldAuthorizer`, `PersonaContextResolutionStage`, `PipelineChatEngine`, `RepositoryContextAssembler`, `OpenRouterLlmClient`. `ChatEngineFactory.build()` itself was bypassed only insofar as its one hardcoded rendering-policy choice was made swappable — no new architecture, interface, or production file was created.

**Pipeline actually exercised, per call:**
```
ChatRequest → PipelineChatEngine.process()
  → RepositoryContextAssembler.assemble()   (real; personaContextProvider = null, plain baseline)
  → deterministic IntentDiscovery            (pins the real historical skill key -- no LLM call spent on this)
  → PersonaContextResolutionStage.resolve()  (real)
    → ProductionPersonaContextService         (real; policy = BareScalarRendering (B) or AgeOnlyLabeledScalarRendering (LA))
      → PersonaFieldAuthorizer.resolve()      (real)
  → LlmGenerator → OpenRouterLlmClient        (real, deepseek/deepseek-v4-flash-0731, maxOutputTokens=1024)
```

## 3. Exact B Definition

`ProductionPersonaContextService` constructed with `BareScalarRendering` (the real, shipped production rendering policy, unmodified since Phase 45). Field selection via the real `PersonaFieldAuthorizer`, using experiment-scoped field-registry/skill-mapping data (§5) isolated in a fresh in-memory database — never the real committed `PersonaContextSeeder` data, never a persistent database.

## 4. Exact LA Definition

Identical to B in every respect except `ProductionPersonaContextService` is constructed with `AgeOnlyLabeledScalarRendering` instead — the real, shipped diagnostic rendering policy (unchanged since Phase 45/49), which renders only `identity.age` as `Age: <value>` and every other scalar exactly as B does. Verified per-scenario, pre-call: `bResult.includedFieldIds.toSet() == laResult.includedFieldIds.toSet()` (identical field selection) and `laResult.renderedText.contains("Age:")` while `!bResult.renderedText.contains("Age:")`.

## 5. Scenario Definitions

**Pune timeline** (Phase 26): skill `general_chat`, message `"Been in this city long?"`. Field set required: the exact historical Phase 26 fix set — `applyOriginalPhase10eFixes` (adds `identity.hometown`/`identity.current_city` as required, `life_context.family`/`life_context.friends` as conditional to `general_chat`) **plus** Phase 26's own inline fix (adds `identity.age`/`life_context.important_life_events` as conditional). **This phase's first execution attempt omitted the `applyOriginalPhase10eFixes` component** — disclosed fully in §3/§10.

**Sister age-gap** (Phase 30): skill `about`, message `"Is there a big age gap between you and your sister in your family?"`. No mapping fix (pure baseline research seed, matching Phase 22/27/30's own methodology).

**Deviation disclosed, per Step 3's explicit instruction:** the skill key `about` used by the sister scenario is **not a real production skill** (confirmed against `BaselineConfiguration.SKILLS` in Phase 51 — `about` does not exist there; it is a research-only skill definition, created fresh in this experiment's isolated database exactly as the historical `World()` fixture always did). This does not weaken the pipeline-correctness claim this phase tests: `PersonaFieldAuthorizer`/`PersonaContextResolutionStage`'s authorization and rendering logic treats `skillKey` as an opaque lookup string into `skill_context_requirements` — the code path exercised is identical regardless of whether that string corresponds to a currently-real end-user-routable skill. It does mean this experiment cannot claim anything about how a real user would ever reach this skill via actual intent discovery (a separate concern this phase did not test, since skill selection was deterministically pinned, matching the historical harness's own approach exactly).

## 6. Sample-Size Justification

**Reused directly from history, not recalculated**, per Step 5's explicit permission: Pune n=20/group (40 calls, matching Phase 26's exact executed sample, itself disclosed there as ~39% powered against the pooled-historical effect size — an explicitly underpowered but cost-consistent choice); sister n=59/group (118 calls, matching Phase 30's exact power-calculated sample, ~80% power against that scenario's pooled effect size).

**Why reuse is appropriate here specifically:** this phase's question is "does the *same* effect, at the *same* scenario, replicate through a *different delivery mechanism*" — not a fresh effect-size discovery. The relevant power calculation is unchanged by which code path delivers the payload; only *whether an effect exists to detect* is in question, which is precisely what this phase measures. Reusing the exact historical N also makes the raw call counts directly comparable to the historical CSVs call-for-call, which a freshly-calculated N would not.

## 7. Randomization Methodology

**Interleaved**, `B, LA, B, LA, ...`, fixed before execution, not altered after any response — identical convention to Phase 26/30. No random-number generator used (consistent with this entire program's precedent).

## 8. Frozen Scoring Methodology

**Pune (duration axis)**: CORRECT if the response states or clearly implies "six years" (or an equivalent phrasing evidently referring to the correct 2019→2025 span); WRONG if it states a different, incorrect duration (historically, "three years" — the suspected 27−24 pattern); AMBIGUOUS if no specific duration is stated. Identical to Phase 26's own rubric.

**Sister (arithmetic axis)**: CORRECT if the response states or clearly implies a 2-year gap between Simran (27) and Anjali (29); WRONG if it states an incorrect gap; AMBIGUOUS if ages are stated but no gap figure is committed to. Identical to Phase 30's own rubric.

**No new response category was encountered in either scenario** — every one of the 198 responses fit cleanly into CORRECT, WRONG, or the pre-defined FAILED/pipeline-failure category (§9); the "stop scoring, define a new category" contingency (Step 7) was never triggered.

## 9. Pipeline Invariants

Verified automatically, per call, for all 198 calls: correct `personaId` propagated (constructor-level, not per-call variable — same persona used throughout); correct skill key (deterministic `IntentDiscovery`, zero LLM calls spent on skill selection, 100% match by construction); correct B/LA condition (`payload_contains_label` computed from the actual captured `PERSONA` block content on every call, not assumed); no restricted-field leakage (`adult_intimacy`/`intimate_anatomy` substring-checked on every captured block, zero occurrences across all 198 calls); no persona/user-profile/memory cross-contamination (fresh conversation + fresh in-memory database per scenario run, no shared state between B and LA engines beyond the same seed); no accidental use of the research-only resolver (confirmed — `PersonaFieldAuthorizer` is the only selection/authorization class in this harness, `PersonaContextResolver`/`resolveVariantA` never imported or called); no unexpected fallback to baseline (every call's precondition check confirmed `USABLE` status before the real-call loop began); no malformed persona context (every precondition check passed before its scenario's loop started).

**One pipeline failure occurred**: Pune call 27 (B condition, corrected run) returned an empty response with `status=FAILED` — an infrastructure-level generation failure (not a scoring category), excluded from the CORRECT/WRONG tally per Step 8's explicit rule ("If any invariant fails: DO NOT count that response as behavioral evidence"). B's corrected-run N is therefore 19 valid + 1 pipeline failure, not 20.

## 10. Raw Results

### 10a. Invalidated first Pune attempt (disclosed, NOT primary data)

The first execution of the Pune scenario (40 calls, `PHASE52_PUNE_PREFIX_INCOMPLETE_FIX_INVALID.csv`) used an incomplete field-mapping fix — `applyOriginalPhase10eFixes` was omitted from the harness by construction error, discovered only after all 40 calls completed (selected field set was 7 fields, missing `identity.hometown`/`identity.current_city`, versus history's 9-field set). Per the same discipline Phase 26 itself applied to its own pre-call infrastructure failure, this run's data is disclosed in full but **excluded from primary analysis**: it does not reproduce the frozen historical design. **For transparency, its own result**: B 20/20 CORRECT, LA 20/20 CORRECT — the same ceiling pattern as the corrected run (§10b), suggesting the missing fields were not the reason for B's ceiling performance, though this is not asserted as established given the run's disclosed invalidity.

### 10b. Pune timeline, corrected run (PRIMARY, n=20/group, 40 calls, 1 pipeline failure)

| Condition | N (valid) | CORRECT | WRONG | AMBIGUOUS | Pipeline failures |
|---|---:|---:|---:|---:|---:|
| B | 19 | 19 | 0 | 0 | 1 (call 27) |
| LA | 20 | 20 | 0 | 0 | 0 |

**Every valid response in both conditions states "about six years" or an equivalent correct phrasing.** Zero WRONG responses in either condition. Full text in `PHASE52_PUNE_EXECUTIONS.csv`.

### 10c. Sister age-gap (n=59/group, 118 calls, 0 pipeline failures)

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---:|---:|---:|---:|
| B | 59 | 59 | 0 | 0 |
| LA | 59 | 59 | 0 | 0 |

**Every one of the 118 responses states "two years" or an equivalent correct phrasing.** Zero WRONG or AMBIGUOUS responses in either condition. Full text in `PHASE52_SISTER_EXECUTIONS.csv`.

## 11. Pipeline Failures

**One**, Pune call 27 (B condition), an empty-response generation failure. Not attributable to the B/LA rendering-policy manipulation (a B-side infrastructure hiccup, not a systematic condition-linked failure — no comparable failure occurred among the other 197 calls, including 19 other B calls in the same run). Classified per Step 11 category 1 (PIPELINE FAILURE), excluded from behavioral scoring, not merged into the WRONG count.

## 12. Behavioral Results

**Both scenarios: ceiling performance in both conditions, no detectable B-vs-LA gap.**

| Scenario | B rate | LA rate | Gap |
|---|---:|---:|---:|
| Pune (corrected) | 19/19 = 100% | 20/20 = 100% | 0 points |
| Sister | 59/59 = 100% | 59/59 = 100% | 0 points |

This is a **materially different result from the historical research-harness findings** (Pune: B=20%, LA=80%, 60-point gap; Sister: B=78.0%, LA=93.2%, 15.2-point gap). The direction has not reversed — LA is never worse than B in either scenario — but the *magnitude* collapsed to zero because **B itself now performs at ceiling**, not because LA declined.

## 13. Statistical Analysis

Pre-registered method: two-sided Fisher's exact test, α=0.05, on the CORRECT vs. NOT-CORRECT 2×2 table.

**Pune (corrected)**: table is `[[19,0],[20,0]]` (B: 19 correct/0 not-correct; LA: 20 correct/0 not-correct). With a zero cell on both margins, Fisher's exact test yields **p = 1.0** — no evidence whatsoever of a difference between conditions, by construction (there is no variance in either group to test).

**Sister**: table is `[[59,0],[59,0]]`. Identically, **p = 1.0**.

**Confidence intervals**: not meaningfully computable for a 100%/100% (or 100%/95%-with-zero-events-observed) comparison beyond noting both point estimates are 100% with the true rate's lower bound, by a standard Wilson-interval approximation, still comfortably above 80% for both N sizes used here.

**Absolute percentage-point gap**: 0 in both scenarios (§12).

**Ambiguous count**: 0 in both scenarios. **Pipeline-failure count**: 1 (Pune, §11).

## 14. Comparison with Phase 26

| | Phase 26 (research harness) | Phase 52 (production pipeline) |
|---|---|---|
| B rate | 20% (4/20) | 100% (19/19 valid) |
| LA rate | 80% (16/20) | 100% (20/20) |
| Gap | 60 points | 0 points |
| p-value | 0.00036 | 1.0 |
| Direction | LA > B | No difference (both ceiling) |

**Does the production pipeline reproduce the Pune result? No — not in the sense of reproducing the same gap.** It does not contradict the *direction* (LA is never below B), but the magnitude and statistical significance both vanish, because the delivery mechanism this phase uses does not reproduce B's historical failure rate.

## 15. Comparison with Phase 30

| | Phase 30 (research harness) | Phase 52 (production pipeline) |
|---|---|---|
| B rate | 78.0% (46/59) | 100% (59/59) |
| LA rate | 93.2% (55/59) | 100% (59/59) |
| Gap | 15.2 points | 0 points |
| p-value | 0.0336 | 1.0 |
| Direction | LA > B | No difference (both ceiling) |

**Does the production pipeline reproduce the sister-scenario result? No, for the same structural reason** — B's error rate, not LA's, is what changed.

**Are the directions consistent? Yes — trivially, since neither condition ever underperforms the other; there is no reversal in either scenario.** **Are the effect sizes comparable? No — both collapsed to zero from substantial historical gaps (60 and 15.2 points respectively).** No causal claim is made about why (§16 offers an observed, disclosed, non-causal hypothesis only).

## 16. What the Production Experiment Establishes

- **Track A (pipeline correctness): the production Strategy-D pipeline correctly delivers the intended, distinct B/LA condition to the model** on both scenarios, verified per-call via the actual captured request content, not assumed — this is a genuine, positive engineering finding, independent of the behavioral result.
- **A real, previously undocumented architectural interaction exists**: the old `personaContextProvider` research hook is silently overwritten by the Phase 49 Strategy-D stage once a real skill key exists (§2). This was unknown before this phase and is a genuine new finding about the current codebase, not a re-litigation of prior research.
- **Track B (behavioral effect): on this run, at these sample sizes, through this pipeline, the historically observed B-vs-LA gap does not appear — because B itself performs at ceiling**, not because LA failed to help. This is a measured, reproducible-in-direction-if-not-magnitude result across two independent scenarios, both showing the identical pattern (B rises to ceiling, gap vanishes).
- **A plausible, disclosed, non-causal observation**: the real `PersonaFieldAuthorizer`/`ProductionPersonaContextRenderer` (Phase 46, an independent reimplementation, not a reuse of the research `PersonaContextResolver`) may format or select fields in a way that happens to help the model succeed on these two specific scenarios even without the age label — this is offered as a candidate explanation worth future investigation, not as an established mechanism; this phase did not isolate which specific difference (selection, formatting, field ordering, or something else) is responsible.

## 17. What It Does NOT Establish

- **That LA has no effect in general** — only that no gap was detectable in these two specific scenarios, at these specific sample sizes, given that B itself reached ceiling here. A scenario where the production pipeline's B does *not* reach ceiling remains untested.
- **Why B performs differently through this pipeline than through the research harness** — no causal mechanism is established (§16's hypothesis is explicitly disclosed as unconfirmed).
- **Any production deployment recommendation** — this phase measures, per its own explicit charge, and makes no claim about whether LA, Bare, or any other policy should ship.
- **Generalization beyond these two scenarios, this one persona, or these specific (partly research-only) skill keys.**
- **That the historical Phase 26/30 research findings were wrong** — those results, from their own harness, at their own sample sizes, stand unmodified and unquestioned; this phase only establishes that the *same effect does not reproduce through a different delivery mechanism*, which is a fact about the mechanism's interaction with these scenarios, not a retraction of the earlier findings.

## 18. New Production Defects Discovered

**One, disclosed in full in §2**: `ChatEngineFactory.build()`'s unconditional Strategy-D wiring silently overwrites the old `personaContextProvider` research hook whenever a real skill key resolves and `PersonaFieldAuthorizer` returns a different, usable result. This is not a defect in Strategy-D itself (which behaves exactly as documented and tested in Phases 49-51) — it is an *interaction* between two features that were never designed to coexist, now that Strategy-D is unconditionally active. **No fix was applied this phase**, per the explicit instruction against redesigning the Persona Context system or modifying historical research methodology within this phase's scope. This is reported as a finding for a future, explicitly-scoped phase to address (e.g., by making the old hook inert once Strategy-D is active, or by removing the old hook now that Strategy-D supersedes its original purpose — either is a real design decision, not made here).

**A harness construction error** (§3, §10a) — the first Pune execution's incomplete field-mapping reproduction — is disclosed as a methodology error in this phase's own work, not a production code defect, and was corrected before the primary data was collected.

## 19. Whether Further Behavioral Testing Is Justified

This phase does not rank or recommend an outcome (per its own explicit instruction). Observationally: the ceiling result on both tested scenarios means these two specific scenarios can no longer usefully distinguish B from LA through this pipeline — a scenario where production's B does not already succeed near-ceiling would be needed to test the behavioral question further. Whether such a scenario exists, and whether it's worth finding, is a decision this phase does not make.

## 20. Final Conclusion

**Evidence supports outcome A: production pipeline behaves as intended, but the behavioral effect is not replicated** — with the specific, disclosed nuance that non-replication occurred because B's error rate itself did not reproduce (B reached ceiling), not because LA failed to help or underperformed. Track A (pipeline correctness) is unambiguously confirmed: the mechanism delivers exactly the intended B/LA distinction, verified per-call, with zero restricted-field leakage and zero misrouted skill/persona context across 198 real calls. Track B (behavioral effect) shows no detectable gap in either scenario at the sample sizes used, with both scenarios' contingency tables producing p=1.0 due to zero-variance ceiling performance in both conditions.

No product recommendation is made. Phase 53 is not started.

---

## Files created

- `docs/Persona update POC/PHASE52_PRODUCTION_BEHAVIORAL_VALIDATION.md` (this document)
- `docs/Persona update POC/PHASE52_PUNE_EXECUTIONS.csv` (primary, corrected run, 40 calls)
- `docs/Persona update POC/PHASE52_PUNE_PREFIX_INCOMPLETE_FIX_INVALID.csv` (disclosed, invalidated first attempt, 40 calls)
- `docs/Persona update POC/PHASE52_SISTER_EXECUTIONS.csv` (118 calls)
- `src/test/kotlin/com/pinkdreams/chat/Phase52PipelineControlVerificationTest.kt` (zero-cost Step 1 verification)
- `src/test/kotlin/com/pinkdreams/chat/Phase52ProductionBehavioralValidationTest.kt` (the experiment harness)

## Files modified

None. No production code, historical research artifact, or Phase 10-51 document was modified.

## Exact API-call count

**198** (40 invalidated Pune + 40 corrected Pune + 118 sister). All real, all recorded, none selectively discarded.
