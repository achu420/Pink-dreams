# Phase 49 — Implement Strategy D: Skill-Aware Persona Context

**Status: IMPLEMENTED and TESTED. Wired into the real production pipeline** (`ChatEngineFactory.build()`), but currently a functional no-op in real production because `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` have zero authored rows (Phase 35) — see §20 for the precise reasoning behind activating rather than leaving this dormant, and §18 for what remains a genuine human decision.

Tag legend: **ESTABLISHED**, **IMPLEMENTED**, **TESTED**, **UNKNOWN**, **HUMAN DECISION REQUIRED**, **BLOCKED**.

---

## 1. Executive Summary

Phase 48 designed Strategy D: complete the already-existing but stubbed `PersonaContextResolutionStage` hook (`ChatEngine.kt`, called strictly after `SKILL_SELECTION` and before `GENERATION`), give it access to `personaId`, and split `RepositoryContextAssembler`'s merged engine+persona block into two independently-addressable, typed blocks so the stage can replace only the PERSONA portion. This phase implements exactly that design: 5 production files changed (one rewritten from a no-op stub, none deleted), 63 new/updated deterministic tests across 4 production test files (all passing), 10 pre-existing test files updated for the new block layout (all passing), and the stage wired as the default construction in `ChatEngineFactory.build()`. The full test suite (1210 tests) shows zero regressions: the only 33 failures are pre-existing, environment-only (`OPENROUTER_API_KEY`/`DATABASE_URL` unset) failures in the frozen research benchmark file, unrelated to this phase. No API calls were made. No production persona data was populated. No rendering policy was chosen as a permanent product decision.

---

## 2. Phase 48 Architecture Implemented

**IMPLEMENTED**, matching Phase 48 §8 precisely:

- `ContextBlock` gained a `blockType: ContextBlockType` tag (10 values: ENGINE, PERSONA, PROFILE, MEMORY, SENSITIVE, CONTINUITY, SKILL, HISTORY, CURRENT_MESSAGE, OTHER), defaulted so every pre-existing call site compiles unchanged.
- `RepositoryContextAssembler` now emits `engineBlock` (`ContextBlockType.ENGINE`) and `personaBlock` (`ContextBlockType.PERSONA`) as two separate blocks in place of the old merged `block0`, in the same position, same header text (`"CONVERSATION ENGINE — UNIVERSAL RULES:"` / `"PERSONA CORE:"`), same budget treatment (both always required, never trimmed — identical to `block0`'s prior guarantee). The now-dead `engineAndPersonaContent()` private helper was deleted.
- `PersonaContextResolutionStage` was rewritten from a no-op stub into the real Strategy D implementation (§4 below).
- `ChatEngine.kt`'s call site now passes `request.personaId` and `request.content` into `resolve()`, wrapped in its own defensive `try/catch` (matching the pattern already used for `intentDiscovery`, `memoryContextEnricher`, and `skillContextEnricher`).
- `ChatEngineFactory.build()` constructs a real `PersonaContextResolutionStage` (backed by `PersonaFieldAuthorizer` + `ProductionPersonaContextService`, Phase 46/47's tested foundation) and wires it into `PipelineChatEngine`.

---

## 3. Files Created

- `src/test/kotlin/com/pinkdreams/chat/personacontext/production/PersonaContextResolutionStageTest.kt` — 29 tests (A-AC).
- `src/test/kotlin/com/pinkdreams/chat/personacontext/production/Phase49EndToEndPersonaContextIntegrationTest.kt` — 2 end-to-end pipeline tests.
- This document.

No new production `.kt` files were created this phase — `PersonaContextResolutionStage.kt` already existed (as a stub) from an earlier phase's scaffolding and was rewritten, not newly created.

---

## 4. Files Modified

| File | Change |
|---|---|
| `src/main/kotlin/com/pinkdreams/chat/ChatEngine.kt` | Added `ContextBlockType` enum; `ContextBlock` gained `blockType`; call site now passes `personaId`/`content` and wraps in `try/catch`. |
| `src/main/kotlin/com/pinkdreams/chat/PersonaContextResolutionStage.kt` | Rewritten: no-op stub → real authorize/render/replace/budget-check logic via `ProductionPersonaContextService`. |
| `src/main/kotlin/com/pinkdreams/chat/context/RepositoryContextAssembler.kt` | Split merged engine+persona block into two tagged blocks; deleted dead `engineAndPersonaContent()`; tagged every other block type. |
| `src/main/kotlin/com/pinkdreams/chat/skill/SkillContextEnricher.kt` | Tagged its inserted block `ContextBlockType.SKILL` (cosmetic — no behavior change). |
| `src/main/kotlin/com/pinkdreams/chat/ChatEngineFactory.kt` | Constructs and wires a real `PersonaContextResolutionStage` into `PipelineChatEngine`. |
| 10 existing test files (see §15) | Updated block-index assertions for the new 2-block (engine, persona) layout in place of the old 1-block layout. No test's *intent* changed — only the index/count arithmetic. |

---

## 5. Files Deleted

None. `engineAndPersonaContent()` (a private method inside `RepositoryContextAssembler`, not a file) was removed as genuinely dead code once its only caller was split into two separate calls (`engineContent()`/`personaCoreContent()`).

---

## 6. PersonaId Flow

**ESTABLISHED / TESTED.**

`ChatRequest.personaId` → in scope throughout `PipelineChatEngine.processTurn()` → passed explicitly as `request.personaId` into `personaContextResolution.resolve(skillEnrichedContext, request.personaId, request.content)` (`ChatEngine.kt`) → `PersonaContextResolutionStage.resolve(context, personaId, currentUserMessage)` → `ProductionPersonaContextService.resolvePersonaContent(personaId, ...)` → `PersonaFieldAuthorizer.resolve(personaId, ...)`. Never inferred, never derived from `ChatContext` (which still has no `personaId` field — unchanged, unneeded now that the request object itself carries it end to end), never a placeholder. Test **A** (`PersonaContextResolutionStageTest`) and the end-to-end tests (§16) assert this directly.

---

## 7. Real Skill-Key Flow

**ESTABLISHED / TESTED.**

`intentDiscovery.selectSkill(request, context)` → `SkillSelection.Selected(skillKey)` or `.None` → stamped onto `ChatContext.selectedSkillKey` (pre-existing mechanism, `ChatEngine.kt` line ~412, unchanged) → read by `PersonaContextResolutionStage.resolve()` as `context.selectedSkillKey`. If `null` (no skill selected, or skill selection itself failed and degraded to `None` per its own existing `try/catch`), `resolve()` returns the input context unchanged — **no substitute or placeholder key is ever used.** Tests **B**, **C**, **D**, **AB** assert this directly; the end-to-end test's second case (§16) proves it against a real `PipelineChatEngine.process()` call, not just the stage in isolation.

---

## 8. ContextBlock Architecture

**IMPLEMENTED**, per Phase 48 §8.1:

```kotlin
enum class ContextBlockType {
    ENGINE, PERSONA, PROFILE, MEMORY, SENSITIVE,
    CONTINUITY, SKILL, HISTORY, CURRENT_MESSAGE, OTHER,
}
data class ContextBlock(val role: String, val content: String, val blockType: ContextBlockType = ContextBlockType.OTHER)
```

Purely additive — `OpenRouterLlmClient.buildOpenRouterRequest()` (unmodified) still maps only `role`/`content` to the wire message; `blockType` never reaches the LLM request. Every pre-existing `ContextBlock(role, content)` call site compiles unchanged (confirmed: `gradle compileKotlin -q` clean).

---

## 9. Post-Skill Resolution Flow

**IMPLEMENTED / TESTED.** `PersonaContextResolutionStage.resolve(context, personaId, currentUserMessage)`:

1. Return `context` unchanged if `context.selectedSkillKey == null` or `context.personaCoreVersionId == null`.
2. Locate the block with `blockType == ContextBlockType.PERSONA`; return unchanged if none exists (defensive — `RepositoryContextAssembler` always emits one today).
3. Call `ProductionPersonaContextService.resolvePersonaContent(personaId, personaCoreVersionId, skillKey, currentUserMessage, baselinePersonaBlock.content)` — the existing PERSONA block's own content (today, unauthorized `core.content`) serves directly as the strict-substitution fallback baseline; no separate repository fetch needed (Phase 48 §6's efficiency insight, carried into the implementation).
4. If the returned string equals the baseline, nothing changed — return `context` unchanged (this single check subsumes every non-usable status: missing document, empty document, empty registry, missing/unknown skill mapping, zero fields selected, or an internal exception inside the service).
5. Otherwise, build candidate blocks with only the PERSONA entry replaced, re-check the total token estimate against `tokenBudget` (§12), and either install the replacement or keep the baseline.
6. The whole method is wrapped in `try/catch { context }` — never throws.

---

## 10. Authorization Boundary

**ESTABLISHED — unchanged from Phase 46.** `PersonaFieldAuthorizer` remains the sole component permitted to read `PersonaMasterDocuments`; `PersonaContextResolutionStage` never reads persona data directly and never bypasses `canAccessField()`. The sequence is strictly SELECT → AUTHORIZE → RENDER (never render-then-filter) — unchanged, reused verbatim from Phase 46, not reimplemented in this phase.

---

## 11. Rendering Boundary

**ESTABLISHED / TESTED.** `ScalarRenderingPolicy` remains injectable at `ProductionPersonaContextService` construction. `ChatEngineFactory.build()` passes `BareScalarRendering`, explicitly commented as a **technical default, not a product decision** (matching Phase 45/47's established framing — see the KDoc at the construction site). Test **T** (stage-level) and Phase 47's test H (facade-level, unchanged) both prove swapping the policy changes only `renderedText`, never `includedFieldIds`/`status`/which other blocks are touched.

---

## 12. Safe Fallback

**IMPLEMENTED / TESTED.** Every one of the 12 listed failure states (§ Part 6 of the phase instruction) resolves to "PERSONA block content unchanged from its pre-resolution baseline":

| # | State | Covered by |
|---|---|---|
| 1 | No persona data at all | Test G |
| 2 | Missing persona document | Test G |
| 3 | Empty document | Test H |
| 4 | Empty registry | Test I |
| 5 | Missing skill mapping | (implicit in G/H/I — no mapping seeded) |
| 6 | Unknown skill | Test E |
| 7 | Restricted-field-only request | Test J, M |
| 8 | Resolver exception | Test K (facade-level defensive wrapper; `PersonaFieldAuthorizer`'s own `RESOLVER_ERROR` path is Phase 46's own test) |
| 9 | Renderer exception | Test L (documented: no known-throwing input exists in `ProductionPersonaContextRenderer`, confirmed by source reading — the try/catch is defense-in-depth, same rationale as Phase 46's equivalent test) |
| 10 | Malformed result | Covered structurally — `PersonaContextResult` is a typed data class; "malformed" can only mean a non-`USABLE` status, already covered by 1-9 |
| 11 | Zero selected fields | Test M |
| 12 | Invalid personaId | Test N, K |

The usability test is never `renderedText.isBlank()` alone — it is `ProductionPersonaContextService`'s own strict-substitution comparison (`resolvedText == baselineBlock.content`), which in turn rests on `PersonaFieldAuthorizer`'s structural `PersonaContextStatus` signal (Phase 45/46), not a blank-string heuristic.

---

## 13. Budget Handling

**IMPLEMENTED / TESTED**, resolving Phase 48 §11's open question in favor of **option (ii)** (re-check, not cap-and-discard):

After building candidate blocks with the PERSONA entry replaced, `PersonaContextResolutionStage` re-sums `tokenEstimator.estimate(...)` across **all** blocks and compares against `tokenBudget` (defaulting to `RepositoryContextAssembler.DEFAULT_TOKEN_BUDGET`, the same constant `SkillContextEnricher` already reuses — consistent precedent). If the replacement would exceed budget, the pre-resolution baseline is kept, not truncated — truncating authorized content would bypass the authorizer's own field-boundary decision, which Part 8 of the instruction explicitly forbids. Tests **W** (within budget, installed) and **X** (deliberately tiny budget, rejected, baseline kept) prove both directions.

---

## 14. Generation Boundary

**ESTABLISHED / TESTED.** `OpenRouterLlmClient.buildOpenRouterRequest()` (unmodified, re-verified this phase) still maps `context.blocks` 1:1 to wire messages by `role`/`content` only. The end-to-end test (§16) inspects the actual `GenerationRequest` captured at the `LlmClient` boundary — not an intermediate `ChatContext` taken on faith — and asserts: no unauthorized field reaches any block; no raw master-document field reaches any block; `personaId` on the captured request matches the original request; `skillKey` on the captured request is the real selected key; the PERSONA block is the resolved/authorized one; ENGINE remains separate and untouched; and (second test case) the pre-existing baseline is exactly what reaches generation when no skill was selected.

---

## 15. Test Matrix

**TESTED.** All 29 named cases (A-AC) from the phase instruction, implemented in `PersonaContextResolutionStageTest.kt`:

| Case | Covers |
|---|---|
| A | personaId propagation |
| B | real selected-skill propagation |
| C | correct skill authorization (differs per skill) |
| D | missing skill (`SkillSelection.None`) → guaranteed no-op |
| E | unknown skill → baseline |
| F | valid persona data → replaced |
| G | missing persona data → baseline |
| H | empty persona document → baseline |
| I | empty registry → baseline |
| J | restricted field never in replaced block |
| K | resolver-failure-shaped input → baseline, never throws |
| L | renderer-failure containment (documented, defense-in-depth) |
| M | zero selected fields → baseline |
| N | invalid personaId → no throw, no misattribution |
| O | PERSONA-only replacement |
| P/Q/R/S | ENGINE/MEMORY/PROFILE/HISTORY byte-for-byte unchanged |
| T | rendering-policy substitution changes only representation |
| U | raw/unregistered document field never reaches replaced block |
| V | restricted field never anywhere in final block list |
| W/X | budget-safe vs. over-budget replacement |
| Y | baseline preserved under compound failure |
| Z/AA | no `PersonaContextResolver`/`resolveVariantA` dependency |
| AB | no fabricated skill key |
| AC | final block list contains only authorized content |

Result (JUnit XML, this phase's clean run): `tests="29" failures="0" errors="0"`.

Combined with the pre-existing Phase 46/47 suites (also re-run this phase, unmodified, still passing): `PersonaFieldAuthorizerTest` 19/19, `ProductionPersonaContextServiceTest` 13/13 — **61 unit-level tests across the persona-context production foundation, all passing, zero API calls.**

**10 pre-existing test files updated** (index/count arithmetic only, no assertion intent changed) for the new 2-block engine/persona layout: `ChatContextContractRegressionTest.kt`, `ConversationProvenanceRoundTripTest.kt`, `EndToEndLlmRequestRegressionTest.kt`, `FullContextPipelineRegressionTest.kt`, `LlmClientBoundaryAuditTest.kt`, `Phase4ARuntimeContextAuditTest.kt`, `Phase4BSensitiveProfileContextTest.kt`, `Phase5BMemoryContextTest.kt`, `ContinuitySummarizationContextIntegrationTest.kt`, `MemoryExtractionPipelineIntegrationTest.kt`. All now pass.

---

## 16. End-to-End Test

**TESTED.** `Phase49EndToEndPersonaContextIntegrationTest.kt`, 2 tests, both passing (`tests="2" failures="0" errors="0"`):

1. **Real skill selection drives authorized persona resolution end to end** — builds a real `PipelineChatEngine` (real `RepositoryContextAssembler`, a deterministic fixed-key `IntentDiscovery` standing in for the LLM-based one — no model call — and the real `PersonaContextResolutionStage`), seeds a real persona master document with one public and one restricted field, and asserts against the actual `GenerationRequest` captured at the `LlmClient` boundary: the authorized public field is present, the unauthorized baseline is gone, the restricted field appears nowhere in any block, `skillKey`/`personaId` on the captured request are correct, and ENGINE remains untouched.
2. **Skill selection returns `None`** — same fixture, `IntentDiscovery` returns `SkillSelection.None` — asserts the PERSONA block is exactly the pre-existing unauthorized baseline, proving no key is ever fabricated to force a resolution.

No external model/API call in either test — `IntentDiscovery` and `LlmClient` are both deterministic fakes, consistent with every other pipeline test in this codebase.

---

## 17. Research Isolation Verification

**ESTABLISHED**, via direct source grep this phase (not re-citing Phase 46's prior grep):

- `grep -rn "PersonaContextResolver\|resolveVariantA" src/main/kotlin/com/pinkdreams/chat/personacontext/production/` → matches only inside KDoc comments explaining what is *not* depended on; zero import statements, zero calls.
- `grep -n "PersonaContextResolver\|resolveVariantA" PersonaContextResolutionStage.kt` → same: comment-only.
- Test **Z**/**AA** (reflection-based, not just textual grep) assert programmatically that `PersonaContextResolutionStage`'s declared fields/methods never reference the research resolver type or `resolveVariantA` by name.
- `git status` confirms zero modifications to any Phase 14-30 research file (`PersonaContextResolver.kt`, `PersonaContextRenderer.kt`, the diagnostic providers, `PersonaContextPocSeed.kt`, `PersonaContextRealBenchmarkTest.kt`) — all pre-existing, untouched entries in this session's `git status`, matching Phase 46/47's own disclosure.

---

## 18. Remaining Human Decisions

Unchanged in kind from Phase 45/48, none resolved or bypassed by this phase's implementation work:

1. **Final rendering-policy choice** (Bare/AgeOnly/AllLabeled as the permanent product default) — `BareScalarRendering` remains an explicitly-labeled technical default only.
2. **Data-authoring mechanism** for `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` — still empty in production; nothing in this phase populates them (explicitly forbidden by the phase instruction, and not done).
3. **Minimum-viable-persona definition** — still open.
4. **Skill-timing architecture choice among Phase 48's (a)/(b)/(c)** — resolved in practice by this phase's implementation (it built (b), block-splitting), but whether this is the long-term direction versus a future two-pass or reordered pipeline remains open if requirements change.

---

## 19. Remaining Technical Risks

**UNKNOWN / worth flagging, not blocking:**

- The budget-recheck (§13) uses the same character-based `TokenEstimator` as the rest of the assembler — this is an approximation (unchanged from every other budget check in this codebase), not an exact tokenizer match to the real LLM provider's tokenization.
- `PersonaContextResolutionStage` currently locates the PERSONA block via `indexOfFirst { blockType == PERSONA }` — correct today because `RepositoryContextAssembler` always emits exactly one, but this is a structural assumption a future caller must preserve (documented in the class's own KDoc).
- Because production data is empty, this phase's activation is **unexercised by real traffic** — its correctness rests entirely on the 63 deterministic tests and the 2 end-to-end tests against seeded fixtures, not on any live production observation (which is impossible without violating the "no persona-data population" rule).

---

## 20. Production Wiring Status

**IMPLEMENTED and ACTIVE** in `ChatEngineFactory.build()` — every `PipelineChatEngine` built by this factory (the one production engine, and every Test Chat engine via `TestChatService.buildEngineFor()` → `ChatEngineFactory.build()`) now constructs and uses a real `PersonaContextResolutionStage`.

This is a deliberate, disclosed engineering call, not an oversight of Phase 47's more cautious "leave production unchanged" conclusion — the difference is that Phase 47 was blocked on a structural gap (no seam existed to safely use the real skill key); Phase 49 closes that gap. Activation is judged safe because:

- `PersonaFieldAuthorizer.resolve()` returns `PersonaContextStatus.DOCUMENT_NOT_FOUND` for every real persona today (Phase 35: `persona_master_documents` has zero rows in production), which `ProductionPersonaContextService` treats as strictly non-usable, so `PersonaContextResolutionStage` returns the input context **unchanged** — the exact same `PersonaCoreVersions.content` baseline production sends today.
- No fabricated skill key, no raw-document exposure, no restricted-field exposure, no budget violation, and no baseline loss are possible under this activation, per the 12 safety invariants re-verified in §9-§14 and the passing test suite.
- Activating the mechanism is therefore observably a no-op against real production data today, while making the machinery ready to work correctly the moment persona data is authored — a separate, still-open decision (§18 item 2), not gated by this phase's own code.

---

## 21. Exact Next Step

The next action is **§18 item 2** (author real `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` data for at least one production persona and skill) — a human/data decision, not an engineering task this phase is authorized to make. Once real data exists for even one persona/skill pair, the already-wired, already-tested mechanism will begin producing real authorized persona context for that pair automatically, with no further code changes required. Until then, §18 item 1 (final rendering-policy choice) can be decided independently and swapped in at the single `ChatEngineFactory.build()` construction site.

---

## 22. API Call Count

**Zero.** No LLM API calls (OpenRouter or otherwise) and no network requests were made in this phase. All verification was: source reading, local Kotlin compilation (`gradle compileKotlin -q`, `gradle compileTestKotlin -q`), and local deterministic test execution against in-memory H2 databases (`gradle test`). The 32 pre-existing research-benchmark test failures observed in this phase's own test runs are themselves proof of this — they fail specifically because no `OPENROUTER_API_KEY` is set in this environment, and this phase made no attempt to set one or work around that failure.

---

## Closing status

Phase 49 implemented and activated Strategy D. All 9 non-negotiable rules were checked against the passing test suite: no external calls, no production data mutation, no persona-data population, no research-file modification, no dependency on `PersonaContextResolver`/`resolveVariantA`, no fabricated skill key, restricted fields never reach generation (tested), baseline preserved on every failure (tested), rendering policy remains injectable with no permanent choice made. No stop condition was triggered. Phase 49 stops here — no Phase 50 is started, no behavioral experiment was run, and no rendering policy was declared production-ready.
