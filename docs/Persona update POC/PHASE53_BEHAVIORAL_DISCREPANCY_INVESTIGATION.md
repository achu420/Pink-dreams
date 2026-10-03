# Phase 53 — Behavioral Discrepancy Investigation

**Diagnostic phase. Zero additional API calls made.** All findings below come from source reading and two new deterministic, zero-cost local tests. No production code was changed.

---

## 1. Objective

Determine why the current production pipeline produces B=100%/LA=100% (Phase 52) while the historical research harness produced B=20-78%/LA=80-93% (Phase 26/30), before spending any further API budget.

## 2. Phase 52 Observation

Pune: B 19/19, LA 20/20 valid-correct, gap 0 points, p=1.0. Sister: B 59/59, LA 59/59, gap 0 points, p=1.0. Both conditions at ceiling in both scenarios. Track A (pipeline delivers the intended, distinct B/LA condition) was independently confirmed correct in Phase 52 via per-call payload verification — that finding is not re-litigated here, only assumed as established background.

## 3. Historical B Reconstruction

Traced directly from source (`ResolvedPersonaContextProvider.kt`, `PersonaContextResolver.kt`, `PersonaContextRenderer.kt`), not from prior phase report descriptions:

- Provider: `ResolvedPersonaContextProvider(variant="B")`
- Resolver: research `PersonaContextResolver.resolve(personaId, coreVersionId, skillKey, msg, "B")` — required+recommended fields always included (subject to `canAccessField`), conditional fields scored via `PersonaFieldRelevanceScorer` against `currentUserMessage`
- Renderer: research `PersonaContextRenderer` — groups selected fields by `classification`, sorted alphabetically, each group under a `## Heading`; scalar values rendered bare (no field-name label); nested objects rendered as `**Key:** value` lines; arrays as `- item` lines
- Field set (Pune, with `applyOriginalPhase10eFixes` + Phase 26's own inline fix applied): `{identity.name, personality.core_traits, identity.hometown, identity.current_city, communication.style, narrative_state.unresolved_threads, life_context.occupation, identity.age, life_context.important_life_events}` (9 fields)
- Delivery: `TestChatService.CreationRequest.personaContextVariant="B"` → `RepositoryContextAssembler.personaContextProvider` → inserted as the (then-merged, pre-Phase-49) engine+persona system block
- Model: `deepseek/deepseek-v4-flash-0731`, `maxOutputTokens=1024`, `timeoutSeconds=300`, no explicit temperature (provider default)
- Skill: `general_chat` (real) for Pune; `about` (research-only, not in `BaselineConfiguration.SKILLS`) for the sister scenario
- Conversation Engine / Persona Core text: `BaselineConfiguration.CONVERSATION_ENGINE` / `.SIMRAN_PERSONA_CORE`, whatever version was active in the codebase at the time Phase 26/30 actually ran

## 4. Current B Reconstruction

- Authorizer: production `PersonaFieldAuthorizer.resolve(personaId, coreVersionId, skillKey, msg, BareScalarRendering)` — same required/recommended/conditional selection logic, independently reimplemented (Phase 46), same `PersonaFieldRelevanceScorer` class actually (confirmed: both old and new resolver classes import and use the identical `com.pinkdreams.chat.personacontext.PersonaFieldRelevanceScorer` — not two separate scorer implementations)
- Renderer: production `ProductionPersonaContextRenderer` with `BareScalarRendering` — structurally identical grouping/heading/nested-object/array logic to the research renderer (confirmed by direct side-by-side source read, §7)
- Field set: identical 9-field set (asserted equal in the Phase 52 harness's precondition check, which passed)
- Delivery: `PersonaContextResolutionStage` replaces the independently-addressable `ContextBlockType.PERSONA` block (post-Phase-49 split), separate from the `ENGINE` block
- Model/skill/message: identical to §3, held constant by harness construction

## 5. Historical LA Reconstruction

`AgeOnlyLabeledScalarDiagnosticProvider` — same `PersonaContextResolver` selection as B, same `PersonaContextRenderer`, except `identity.age`'s scalar renders as `Age: 27` instead of bare `27`. No other difference from historical B.

## 6. Current LA Reconstruction

`PersonaFieldAuthorizer` with `AgeOnlyLabeledScalarRendering` — identical selection to current B, identical renderer, only `identity.age` differs (`Age: 27` vs bare `27`), matching §5 exactly at the mechanism level.

## 7. Exact Payload Comparison

**Built and ran a new zero-cost test, `Phase53PayloadDiffTest`, that constructs BOTH the old `ResolvedPersonaContextProvider("B")` and the new `PersonaFieldAuthorizer`+`BareScalarRendering`, against the IDENTICAL seed data and identical applied fixes, and diffs the resulting strings character-by-character.**

**Result: `IDENTICAL? true` for both the Pune and sister scenarios.** The old-mechanism B text and the new-mechanism B text are byte-for-byte the same string in both cases — same headings, same field grouping, same order, same content, same whitespace.

**This is the single most important finding of this phase.** It conclusively rules out, with direct evidence rather than inference, every hypothesis about the PERSONA block's content or format: field selection differences (#1, #2, #12), rendering/formatting differences (#4), duplicate-path leakage into this specific block (#15), and field-ordering differences. The persona-context payload the model receives for the B condition is not merely similar — it is identical, whether delivered by the frozen 2025-era research mechanism or the current production Strategy-D pipeline.

## 8. Information Duplication Analysis

| Fact | B source (old + new, identical) | Other source(s) |
|---|---|---|
| Pune: moved at 21, current age 27 → ~6 years | `identity.age` (27, bare or labeled), `life_context.important_life_events` (lists "Moving to Pune at 21...") | Not present in `SIMRAN_PERSONA_CORE` (confirmed Phase 50: contains zero biographical facts) or `CONVERSATION_ENGINE` (behavioral instructions only, no biography) |
| Sister: Anjali 29, Simran 27 → 2-year gap | `identity.age` (27), `life_context.family` (Anjali's detail entry does not state her age numerically in the seed data — need arithmetic from context, per the scenario's own design) | Same — not duplicated in engine/core baseline text |

**No duplication was found that would trivialize the task** — the facts required to answer both scenarios correctly exist only in the Persona Context payload (proven identical old vs. new), not in the engine or persona-core baseline text, which remains fact-free per Phase 50's finding. Duplication is therefore **ruled out** as an explanation.

## 9. Prompt Position Analysis

**A real, confirmed structural difference exists, though its behavioral significance is unverified.** Historically (pre-Phase-49), `RepositoryContextAssembler` merged engine and persona content into ONE system block (`engineAndPersonaContent()`, since deleted). Currently (post-Phase-49), they are TWO separate, consecutive system blocks (`ENGINE` then `PERSONA`). The persona TEXT itself is identical (§7); only whether it shares a single message with the engine text, or occupies its own adjacent message, differs. This is a genuine, disclosed structural difference this phase did not isolate or test the effect of.

## 10. Strategy-D Interaction

Re-confirmed from Phase 52 §2/§9 (not re-derived here): Strategy-D always executes; in Phase 52's harness the old provider path was deliberately not used (bypassed via `personaContextProvider = null`), so no duplicate-write race applies to the Phase 52 data itself — the harness controls B/LA directly at the rendering-policy level, one clean write to the `PERSONA` block, no second write. This interaction (documented fully in Phase 52) does not affect the interpretation of Phase 52's own results; it was the reason the harness was built the way it was.

## 11. Model / Generation Configuration Comparison

| Dimension | Historical | Phase 52 | Same? |
|---|---|---|---|
| Model string | `deepseek/deepseek-v4-flash-0731` | `deepseek/deepseek-v4-flash-0731` | Yes (identical string) |
| maxOutputTokens | 1024 | 1024 | Yes |
| timeoutSeconds | 300 | 300 | Yes |
| temperature | unset (provider default) | unset (provider default) | Yes |
| Provider | OpenRouter | OpenRouter | Yes |

**Not verifiable from source, disclosed as a real limitation**: whether the OpenRouter-hosted model actually served under the alias `deepseek/deepseek-v4-flash-0731` is the *same underlying model weights/version* at both points in time. OpenRouter model aliases can be repointed by the provider without this codebase's knowledge; this is an external-service variable no amount of source reading in this repository can resolve.

## 12. Evaluation Comparison

Both Phase 26/30 and Phase 52 use the identical, explicitly-stated rubric: CORRECT if the stated duration/gap matches the true value ("six years" / "two years"), WRONG if it states an incorrect figure, AMBIGUOUS if no figure is committed to. Phase 52 explicitly re-adopted the historical rubric verbatim (§8 of the Phase 52 report) rather than defining a new one. **No evidence of an evaluation-criteria discrepancy** — both are simple, deterministic, human-scored (by me, reading the actual response text) pattern matches on the same target fact, not independently-scored or LLM-scored in a way that could introduce leniency drift.

## 13. Hypotheses

| Hypothesis | Evidence For | Evidence Against | Status |
|---|---|---|---|
| #1 Historical/current B not equivalent (selection/mechanism) | — | §7: byte-identical text | **REJECTED** |
| #2 Different information in current payload | — | §7: byte-identical text | **REJECTED** |
| #3 Different prompt location/order | §9: block merge→split structural change is real | Persona text itself unchanged; effect of block-splitting alone on model attention is untested | **UNRESOLVED, low-priority** (structural difference confirmed to exist, behavioral effect not isolated) |
| #4 Different format even with Bare policy | — | §7: byte-identical text (format is part of what's identical) | **REJECTED** |
| #5 System/developer prompt changed | §11 (git history): `BaselineConfiguration.kt` essentially created fresh 2026-09-19 ("Implement Phase D baseline config" + "Integrate AMIA canonical AI behavior v3"); current text contains explicit anti-fabrication/grounding directives ("Do not invent persona biography outside the supplied Persona Core," "Do not present inference or fiction as established fact") with no historical version available to diff against | No git-trackable historical engine text exists to confirm the OLD text lacked these directives (Phase 26/30's own docs were never git-committed) | **STRONGEST CANDIDATE, circumstantial, not directly proven** |
| #6 Conversation/history differs | — | Both use fresh conversations, empty history, by harness construction (Phase 52 §9) | **REJECTED** |
| #7 Skill selection/enrichment changed | — | Both harnesses use deterministic `IntentDiscovery` pinning the exact historical skill key; no skill-enrichment block relevant to these two skills in either era's minimal test setup | **REJECTED** |
| #8 Model/provider/version changed | Cannot be ruled out — OpenRouter alias behavior is opaque to this codebase | Model string, token limit, timeout all identical in source | **UNRESOLVED, unverifiable from source** |
| #9 Generation parameters changed | — | All parameters traced identical (§11) | **REJECTED** |
| #10 User prompt wording differs | — | Verified identical strings, copied verbatim from the Phase 26/30 artifacts into the Phase 52 harness | **REJECTED** |
| #11 Evaluation criteria differ | — | §12: identical rubric, verified | **REJECTED** |
| #12 Current B contains info historical B didn't | — | §7: byte-identical | **REJECTED** |
| #13 LA redundant given other context | — | §8: no duplication found anywhere else | **REJECTED** (as an explanation for the CEILING result specifically — B improving, not LA becoming redundant, is what changed) |
| #14 Historical interaction no longer architecturally possible | Not applicable — Phase 52's harness deliberately bypassed the old provider path entirely, so no interaction is being measured either way | — | **NOT APPLICABLE to this specific discrepancy** (relevant to Phase 52 §2's separate finding, not to why B improved) |
| #15 Multiple injection paths | — | Phase 52 harness explicitly sets `personaContextProvider = null`; only one path (Strategy-D) writes the block | **REJECTED** |
| #16 Evaluation harness changed | — | §12 | **REJECTED** |
| #17 Task too easy, both saturate | Consistent with the observation, but doesn't explain *why* it's easy NOW when it wasn't historically at the SAME persona-context payload | Doesn't independently explain the change without invoking #5 or #8 | **PARTIALLY CONSISTENT, subsumed by #5** |

## 14. Deterministic Tests

Two new zero-cost test files added this phase:
- `Phase53PayloadDiffTest.kt` — the decisive B-payload character-diff (§7), 2 tests, both passing, both confirming byte-identical text.

No other deterministic test was needed to reject hypotheses #1, #2, #4, #6, #7, #9, #10, #12, #15, #16 — each was resolved directly by source reading (§3-§12) without requiring new test code, since the relevant code paths were already fully traced in Phase 49-52's own work this session.

## 15. API Experiments

**None run this phase**, per the phase's own explicit instruction to exhaust zero-cost analysis first. §20 proposes, but does not execute, the next experiment that would be needed to move hypothesis #5 from "strongest candidate" to "confirmed."

## 16. Interpretation

The persona-context delivery mechanism itself — the entire subject of Phases 44-51's production-readiness work — is **exonerated** as the explanation for Phase 52's ceiling result. Its payload is provably, byte-for-byte identical to what the historical research harness delivered. Whatever changed, changed somewhere else in the system: most plausibly the Conversation Engine's own behavioral instructions, which git history shows were substantially rewritten ("Integrate AMIA canonical AI behavior v3") after Phase 26/30 ran and now contain explicit, repeated anti-fabrication and grounding directives that did not necessarily exist in whatever engine text Phase 26/30's B condition was actually tested against. This cannot be stated as confirmed, only as the best-supported remaining hypothesis, because the historical engine text itself was never preserved anywhere this phase can access (none of the historical Phase 26/30 documents were committed to git, and `BaselineConfiguration.kt` has no earlier tracked version).

## 17. What Phase 52 Actually Established

Restated, unchanged from that phase's own §16: the production Strategy-D pipeline correctly and verifiably delivers the intended, distinct B/LA persona-context condition (now doubly confirmed — once by Phase 52's own per-call verification, once by this phase's independent byte-level diff against the historical mechanism). The behavioral gap did not reproduce, and this phase adds: **not because of anything in the persona-context payload itself**.

## 18. What It Did NOT Establish

Unchanged from Phase 52 §17, with one addition: this phase did not establish, only circumstantially support, that the Conversation Engine text is the actual cause. It also did not and could not verify or rule out silent model-version drift on OpenRouter's side (#8) — a real, disclosed limit of what source-code analysis alone can determine.

## 19. Remaining Uncertainty

- Whether the Conversation Engine's anti-fabrication language (hypothesis #5) is actually causal, versus merely correlated with the timeline.
- Whether the historical engine text genuinely lacked equivalent grounding language (unverifiable — no historical copy exists).
- Whether OpenRouter's model routing for `deepseek/deepseek-v4-flash-0731` is stable across the time period spanning Phase 26/30 and Phase 52 (hypothesis #8, unverifiable from this codebase).
- Whether the ENGINE/PERSONA block-merge-to-split structural change (§9) has any independent behavioral effect, isolated from the engine-text content itself.

## 20. Proposed Next Experiment (NOT executed this phase)

**Hypothesis to isolate**: does the current Conversation Engine's anti-fabrication language, independent of the Persona Context payload (already proven identical), account for the ceiling shift?

**Design** (proposed only, requires separate authorization before any API spending):
- Independent variable: Conversation Engine text — CURRENT (AMIA v3, with anti-fabrication directives) vs. a reconstructed-as-best-effort OLDER-style engine text (would need to be manually authored to approximate pre-AMIA-v3 behavior, since no historical copy exists — itself a limitation to disclose upfront in that experiment's design, not discovered after the fact)
- Persona Context payload: held fixed at the already-proven-identical B and LA texts (§7) — no re-derivation needed
- Dependent variable: CORRECT/WRONG/AMBIGUOUS on the same two scenarios
- Controls: same skill, same model, same message, same field set — everything Phase 52 already controlled, minus the engine-text variable
- Sample size: given the very tight historical CIs (Phase 26 p=0.00036, Phase 30 p=0.0336) were themselves obtained at n=20 and n=59, a similarly-sized sample would likely suffice to detect an engine-text effect if one exists, but this would need its own fresh power justification specific to this new comparison, not reused wholesale
- Invalidation criteria: if the reconstructed "older-style" engine text cannot be verified to actually differ meaningfully from the current text in the relevant way, the experiment should not proceed (matching this phase's own STOP-condition discipline)
- Not run in this phase, per instruction

## 21. Production Changes

**None.** No production code, rendering policy, database, or persona data was modified this phase.

## 22. API Calls

**Zero.**

## 23. Final Status

**Not fully resolved — but substantially narrowed with direct, decisive evidence.** The persona-context delivery mechanism (Phases 44-51's entire subject) is conclusively ruled out as the explanation (§7, byte-identical payload). The most plausible remaining explanation is a change to the Conversation Engine's own behavioral instructions between when Phase 26/30 ran and now (§5, §16), supported by git history and the current text's explicit anti-fabrication language, but this is circumstantial, not proven by a direct historical diff, because no historical copy of that text was ever preserved. A small, explicitly-scoped follow-up experiment (§20) could resolve this further but was not run in this phase, per its own instruction to diagnose before spending further API budget. No production behavior was changed. No product or deployment decision is made. Phase 54 is not started.
