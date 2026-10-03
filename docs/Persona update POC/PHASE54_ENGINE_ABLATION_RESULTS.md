# Phase 54 — Controlled Conversation-Engine Ablation

**96 real API calls made** (48 Pune-timeline + 48 sister age-gap), model `deepseek/deepseek-v4-flash-0731`, `maxOutputTokens=1024`, no explicit temperature — identical generation configuration to Phase 52.

---

## 1. Objective

Determine whether the current `BaselineConfiguration.CONVERSATION_ENGINE`'s grounding/anti-fabrication directives — identified in Phase 53 as the strongest candidate explanation for why current production B reaches ceiling — materially affect B's behavioral error rate on the two established scenarios, by directly ablating those specific directives and comparing against the current, unmodified engine. This is an attribution experiment against the *current* pipeline, not an attempt to reconstruct historical Phase 26/30 conditions.

## 2. Phase 53 Findings Carried Forward

- The persona-context (`PERSONA` block) payload is byte-identical between the old research mechanism and the current production mechanism (Phase 53 §7) — the delivery mechanism itself is exonerated.
- The current Conversation Engine text was substantially rewritten ("Integrate AMIA canonical AI behavior v3") shortly before this session's work, and contains explicit anti-fabrication/grounding language.
- **Correction applied this phase**: Phase 53 also cited "Do not present inference or fiction as established fact" as a candidate directive. Re-verified by exact line-range lookup (`BaselineConfiguration.kt`): that sentence belongs to `MEMORY_ENGINE` (lines ~363-450+, a separate side-channel prompt for memory extraction that never reaches the model during a Pune/sister chat-generation call), not `CONVERSATION_ENGINE` (lines 27-216, the block that actually reaches every chat request). It is correctly excluded from this phase's ablation set.
- Model-version/routing drift on OpenRouter's side (Phase 53 hypothesis #8) remains unverifiable from source and is not addressed by this phase.

## 3. Exact Current Engine Candidate Directives

Read directly from `BaselineConfiguration.CONVERSATION_ENGINE` (lines 27-216) this phase, not paraphrased from Phase 53. Classified per Part 1's A/B/C/D scheme:

| Line(s) | Text | Class |
|---|---|---|
| 57-58 | "Do not assume that information exists when it is not present in the supplied context." | A (grounding) |
| 76 | "- Do not manufacture familiarity, memories, experiences, or events." | B (anti-fabrication) |
| 97 | "Do not invent memories." | B |
| 99-100 | "Do not treat recent conversation as long-term memory merely because it is visible." | C (uncertainty/inference) |
| 102-103 | "Do not claim to remember information that is not supplied by memory or recent conversation." | A |
| 120 | "Do not contradict it merely to satisfy a conversational direction." | A |
| 122 | "Do not invent persona biography outside the supplied Persona Core." | B — the single most directly relevant line (explicitly about persona *biography*, which is exactly what the Pune/sister scenarios test) |

All other ~180 lines of the engine (Purpose, Context, Core behavior's non-fabrication bullets, Context priority, Skill, Natural conversation, Boundaries, Final response, Response length, Conversational turn balance, User-goal orientation, Emotional availability) are D (unrelated) and were **not touched**.

## 4. Ablated Engine Construction

`ENGINE_ABLATED` (test-only, `Phase54EngineAblationTest.kt`, never written to `BaselineConfiguration.kt`): the exact current engine text with only the seven lines in §3 removed, via line-based filtering (not fragile whitespace-sensitive string replacement — an initial attempt using literal indented string replacement failed silently against `trimIndent()`'s runtime-computed indentation and was corrected before any real call). Resulting doubled blank lines collapsed to single blank lines; no other line touched.

## 5. Byte-Level Engine Diff

**Zero-cost test, run first, passing**: `ablated engine differs from current engine ONLY in the seven identified directive sentences`. Asserts (a) each of the seven target lines exists verbatim in the current engine, (b) is fully absent from the ablated engine, and (c) every remaining non-blank line, in order, is identical between the two. All assertions passed.

```
Current engine: 6276 chars, 188 lines
Ablated engine: 5783 chars, 172 lines
Chars removed: 493
```

## 6. Experimental Conditions

**Primary comparison only** (per Part 3's explicit instruction not to introduce LA): `CURRENT` (unmodified `BaselineConfiguration.CONVERSATION_ENGINE`) vs. `ABLATED` (§4). Both conditions use `BareScalarRendering` (B) exclusively for the persona payload — held fixed, not varied.

## 7. Controls

Held identical between conditions, verified per-scenario before any real call (precondition check) and per-call (invariant check, §11): persona ID, skill key, user message, persona payload (byte-identical `renderedText`, asserted equal before execution), field-registry/skill-mapping data (identical seed + identical Pune fix where applicable), model string, `maxOutputTokens`, `timeoutSeconds`, temperature (unset, provider default), provider (OpenRouter). Only the Conversation Engine block's text differs between conditions, by design.

## 8. Sample-Size Justification

**Freshly calculated for this phase, not reused from Phase 26/30** (per Part 8's explicit instruction). Using a two-proportion power calculation, two-sided α=0.05, target power=0.80, against a substantial-but-plausible degradation effect size (p1=0.95 for CURRENT, allowing the true ceiling rate to be slightly below the 100% observed in small samples; p2=0.65 for ABLATED, a large, practically meaningful drop):

```
n ≈ (1.96 + 0.84)² × [0.95×0.05 + 0.65×0.35] / (0.30)²
  = 7.84 × [0.0475 + 0.2275] / 0.09
  = 7.84 × 0.275 / 0.09
  ≈ 23.96 → rounded up to 24 per group
```

**n=24 per condition per scenario (48 calls/scenario, 96 total)** — larger than Phase 26's n=20 (matching precedent scale for a similarly-costly experiment) but derived fresh against a different, explicitly-stated effect-size assumption, not copied from history's own justification.

## 9. Randomization

Interleaved, `CURRENT, ABLATED, CURRENT, ABLATED, ...`, fixed before execution for each scenario independently, not altered after any response. Scenario order (Pune run first, sister run second) was also fixed before either scenario's calls began.

## 10. Scoring Rubric

Retained verbatim from Phase 26/30/52, unmodified: Pune CORRECT = "six years" or equivalent; WRONG = an incorrect concrete duration; AMBIGUOUS = no committed duration. Sister CORRECT = two-year gap or equivalent; WRONG = incorrect gap; AMBIGUOUS = no committed gap. No novel response category appeared in either scenario; the rubric required no amendment.

**Secondary error flags** (unsupported biography, unsupported location, contradiction with known persona fact, hallucinated number, fabricated relationship detail): reviewed across all 96 responses. **None observed in either condition.** Every response correctly and consistently used only established facts (age 27, moved at 21, hometown Nashik, current city Pune/Kothrud, sister Anjali 29, brother Arjun 24 where mentioned) — no fabricated detail, no contradiction, in either CURRENT or ABLATED.

## 11. Pipeline Invariants

Verified automatically per call (in-code assertion, not post-hoc review): the captured request's `PERSONA` block matched the expected pre-verified payload exactly; the `ENGINE` block for `CURRENT` calls contained the string `"Do not invent persona biography"`; the `ENGINE` block for `ABLATED` calls did **not** contain that string. **Zero invariant violations detected across all 96 calls** (`assertTrue(!invariantViolation, ...)` passed in both scenario tests) — the experiment cleanly delivered exactly the intended, single-variable manipulation on every call, verified against the actual captured request content, not assumed.

## 12. Raw Results

### Pune timeline (n=24/group, 48 calls, 0 pipeline failures)

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---:|---:|---:|---:|
| CURRENT | 24 | 24 | 0 | 0 |
| ABLATED | 24 | 24 | 0 | 0 |

Every one of the 48 responses states "about six years" or an equivalent correct phrasing. Full text in `PHASE54_PUNE_EXECUTIONS.csv`.

### Sister age-gap (n=24/group, 48 calls, 0 pipeline failures)

| Condition | N | CORRECT | WRONG | AMBIGUOUS |
|---|---:|---:|---:|---:|
| CURRENT | 24 | 24 | 0 | 0 |
| ABLATED | 24 | 24 | 0 | 0 |

Every one of the 48 responses states "two years" or an equivalent correct phrasing. Full text in `PHASE54_SISTER_EXECUTIONS.csv`.

## 13. Pipeline Failures

**Zero** across all 96 calls (distinct from Phase 52, which had one unrelated infrastructure failure). All calls returned `status=SUCCESS`.

## 14. Statistical Analysis

Pre-registered method: two-sided Fisher's exact test on the CORRECT vs. NOT-CORRECT 2×2 table.

**Pune**: table `[[24,0],[24,0]]`. **p = 1.0** — zero variance in either group, no detectable difference by construction.

**Sister**: table `[[24,0],[24,0]]`. **p = 1.0**.

**Absolute percentage-point difference**: 0 in both scenarios. **Confidence intervals**: both point estimates 100%; the Wilson lower bound at n=24 with 24/24 successes remains comfortably above 85% for both conditions in both scenarios, meaning even accounting for finite-sample uncertainty, the true CURRENT and ABLATED rates are unlikely to differ substantially at this N — this bears on §19's discussion of statistical power, not on the point result itself.

## 15. Secondary Error Flags

Covered in §10: zero occurrences of any of the five tracked secondary flags (unsupported biography, unsupported location, contradiction, hallucinated number, fabricated relationship detail) in either condition, either scenario.

## 16. Interpretation

Applying Part 16's required neutral classification: **B — does not detect an engine-text effect.** Removing the seven identified grounding/anti-fabrication directive sentences from the Conversation Engine, while holding the persona payload exactly fixed (verified byte-identical per call) and every other control constant (verified per call), produced **no observable change** in B's behavioral correctness on either established scenario, at a sample size (n=24/group/scenario) powered to detect a 30-percentage-point degradation with 80% power.

This does not mean the ablated sentences are meaningless in general — only that, under this specific controlled comparison, on these two specific scenarios, at this sample size, removing them did not measurably change whether the model correctly stated the duration/gap. No causal claim beyond this narrow, controlled result is made.

## 17. What This Establishes

- The specific seven grounding/anti-fabrication sentences identified in Phase 53 as the strongest candidate explanation for current-B's ceiling performance **do not, under direct controlled ablation, account for it** — removing them changed nothing observable.
- The ablation mechanism itself worked correctly: the byte-level diff (§5) and per-call invariant checks (§11) confirm the intended single-variable manipulation was cleanly delivered on every one of 96 real calls, with zero contamination of the persona payload or any other control.
- No fabrication, contradiction, or unsupported-fact secondary error occurred in either condition (§10), which is itself informative: whatever accounts for current-B's grounded, accurate behavior, it is robust even to removing this specific candidate instruction set.

## 18. What This Does NOT Establish

- That the Conversation Engine text has *no* effect on this behavior — only that this specific ablation, of these specific seven sentences, did not detect one.
- Why current-B differs from historical-B (Phase 52's original, still-unresolved question) — this phase narrows the candidate-explanation space (Phase 53's #5 hypothesis is now weakened) but does not identify the actual cause.
- Anything about Phase 53's remaining unresolved hypotheses: #3 (merged-vs-split block structure) and #8 (model-version drift) are untouched by this phase, per its own explicit scope boundary.
- Any production or deployment recommendation.
- That B or ABLATED, or any condition, is "better" — no such comparison was made or is licensed by this data.

## 19. Remaining Uncertainty

- Whether a *different subset* of the current engine's ~188 lines (not the seven ablated here) is responsible for current-B's ceiling behavior — this phase tested only the specific candidate set Phase 53 identified, not an exhaustive search.
- Whether the merged-vs-split ENGINE/PERSONA block structure (Phase 53 §9, not tested this phase) has an independent effect.
- Whether model-version/routing drift (Phase 53 #8, unverifiable from source) is the actual explanation — this remains entirely untouched by any phase's work so far, since it is not resolvable from this codebase.
- Whether a scenario where current-B does *not* reach ceiling exists at all — both established scenarios have now shown 100% correctness across 254 total real calls this session (158 Phase 52 + 96 Phase 54), which may mean these two specific scenarios are simply no longer diagnostic for whatever distinguishes current from historical behavior, regardless of which engine/payload variable is manipulated.

## 20. Whether Secondary Experimentation Is Justified

**Not run this phase, per Part 17's explicit instruction against automatically continuing.** The primary result is a clean, unambiguous null (§14, §16) — not an ambiguous result that Part 17 would treat as justification for the merged-vs-split secondary experiment. Per that same instruction, the secondary experiment is deferred rather than run automatically. If further investigation of hypothesis #3 (block structure) or #8 (model drift) is wanted, that would need to be its own explicitly-scoped, separately-authorized phase — this phase does not recommend or decline that on the requester's behalf, consistent with Part 14's "do not make a production recommendation" and Part 16's neutral-classification-only discipline.

## 21. Production Changes

**None.** `BaselineConfiguration.CONVERSATION_ENGINE` was not modified. No other production file (`PersonaFieldAuthorizer`, `ProductionPersonaContextService`, `PersonaContextResolutionStage`, `RepositoryContextAssembler`, `ChatEngine`, `ChatEngineFactory`) was modified. The ablated engine text exists only as a `private companion object` constant inside the test file and was only ever written to a fresh in-memory H2 database via `ConversationEngineRepository.createNextVersion()`, never to a persistent database, never to production source.

## 22. API-Call Count

**96** (48 Pune + 48 sister). All real, all recorded in the two CSV files, none selectively discarded. Two zero-cost precondition failures occurred before any real call was made in this phase (a named-argument bug and an initial test-name-filter mismatch) — both caught and fixed with zero API spending, consistent with this phase's own fail-fast design discipline.

## 23. Final Status

**ENGINE-EFFECT NOT DETECTED**

The candidate grounding/anti-fabrication directives identified in Phase 53, when directly and surgically ablated from the current Conversation Engine while holding the persona payload and every other variable exactly fixed, produced no detectable change in B's behavioral correctness on either established scenario across 96 real, controlled API calls. This narrows but does not close Phase 53's investigation: the two remaining candidate explanations (ENGINE/PERSONA block-structure change, and model-version drift on the provider side) are unaddressed by this phase and were not automatically pursued, per this phase's own instruction against continuing without explicit justification. No production code was changed. No product or deployment recommendation is made. Phase 55 is not started.
