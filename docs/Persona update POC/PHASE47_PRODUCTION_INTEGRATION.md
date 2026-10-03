# Phase 47 — Production Integration Readiness & Wiring

**Status:** production wiring NOT activated. `RepositoryContextAssembler.kt`, `ChatEngine.kt`, `ChatEngineFactory.kt`, `Application.kt` are all byte-for-byte unchanged by this phase. A new, fully-tested, standalone facade (`ProductionPersonaContextService`) exists and compiles, but is not called from any production code path.

Tag legend used throughout: **ESTABLISHED** (re-verified from current source in this phase), **IMPLEMENTED** (code exists and compiles), **TESTED** (a deterministic, zero-API-call test asserts it), **UNKNOWN** (not determinable without information this phase doesn't have), **REQUIRES HUMAN DECISION**, **BLOCKED**.

---

## 1. Objective

Determine — from current source, not from prior phase reports — whether the Phase 46 production foundation (`PersonaFieldAuthorizer`, `PersonaContextResult`, `ScalarRenderingPolicy`) can be safely connected to the real production chat request path without making any unresolved product, skill-timing, or data-authoring decision. If yes, wire it and prove it with tests. If no, leave production unchanged, build the safe standalone seam, and identify the smallest remaining blocker precisely.

No behavioral/API experiment was authorized or performed in this phase. **No LLM API calls were made.**

---

## 2. Actual production call path (re-verified from source this phase)

**ESTABLISHED.** Traced directly in `ChatEngine.kt` (`PipelineChatEngine.process()`), lines ~328–405:

1. `PipelineStage.CONTEXT_ASSEMBLY` (line 328–334): `contextAssembler.assemble(request)` runs. In production this is `RepositoryContextAssembler`.
2. `PipelineStage.SKILL_SELECTION` (line 341–405): `intentDiscovery.selectSkill(request, context)` runs **after** context assembly, producing the real `SkillSelection` (a model-selected skill key, or `None`). Immediately after, `memoryContextEnricher?.enrich(...)` and then `skillContextEnricher?.enrich(memoryEnrichedContext, skillSelection)` run — both with the real skill key already known.
3. `PipelineStage.GENERATION` follows.

This confirms the stage ordering asserted in prior phases (`CONTEXT_ASSEMBLY` before `SKILL_SELECTION`) is correct. **However, this phase's re-verification surfaces a refinement prior phases did not have**: a real, model-selected skill key **is** available later in the same `process()` call, before `GENERATION` — via `SkillContextEnricher`, which runs at line 381–402. Prior phases (44/45/46, working from `RepositoryContextAssembler.assemble()` alone) concluded "no real skill key is available in time." That conclusion is **corrected** below (§5) rather than silently carried forward — a post-skill-selection integration point does structurally exist. The actual blocker is different and more precise than previously stated.

---

## 3. Verified integration point

**ESTABLISHED.** `RepositoryContextAssembler.kt` lines 60–76 (`assemble()`):

```kotlin
val core = personaRepository.getActiveCoreVersion(request.personaId)
val personaContent = if (personaContextProvider != null) {
    personaContextProvider.getPersonaContext(request.personaId, core.id, pinnedSkillKey ?: "general_chat", request.content)
} else {
    core.content
}
val block0 = ContextBlock("system", engineAndPersonaContent(engine.content, personaContent))
```

Re-confirmed facts, this session:
- `personaContextProvider` defaults to `null` (`RepositoryContextAssembler.kt` line 40) and `Application.kt`'s production `chatEngineDependencies` construction never passes one — grepped this session, zero references. Production always takes the `core.content` branch.
- `personaContent` and `engine.content` are concatenated into **one opaque string** (`block0`), computed entirely inside `assemble()`, **before** `SKILL_SELECTION` runs and before any real skill key exists. The `pinnedSkillKey ?: "general_chat"` expression is dead code in production (the provider that would consume it is always null) — it exists only for the POC/benchmark harness, which passes a real pinned skill explicitly.
- `block0` is then subject to the assembler's own token-budget trimming loop (lines 102–128) before `ChatContext` is returned.

`RepositoryContextAssembler` remains the component that **creates** the persona+engine block, and is confirmed (again, from source, not citation) to be the only place persona content enters the context today. It is **not**, however, the only plausible integration point once skill timing is considered — see §5.

---

## 4. PersonaId propagation

**ESTABLISHED.**
- `ChatRequest` (`ChatEngine.kt` ~line 15–22) carries `personaId: UUID` directly, and `request` (the same object) stays in scope through every subsequent stage of `process()`, including past `SKILL_SELECTION`.
- `ChatContext` (`ChatEngine.kt` line 63–74) has **no `personaId` field** — only `personaCoreVersionId: UUID?`. This was correctly identified in prior phases and is re-confirmed unchanged.
- Consequence, corrected from the prior (pre-summary) assumption: personaId is **not** actually unavailable at a post-skill-selection integration point — a new enricher analogous to `SkillContextEnricher` would receive `request.personaId` directly from the `request` object already in scope in `ChatEngine.process()`, the same way `intentDiscovery.selectSkill(request, context)` does. The earlier framing ("ChatContext lacks personaId, therefore no downstream stage can use it") conflated "not present on `ChatContext`" with "not obtainable at that point in the call" — those are not the same thing, and the correction matters for §5.

---

## 5. Skill-timing findings (re-verified, corrected from prior phases)

**ESTABLISHED — refined conclusion, explicitly correcting the pre-compaction working conclusion.**

Prior phases (44, 45, and this session's own pre-compaction reasoning) stated flatly that "no real skill key is available at the point persona-context resolution would need one." Re-tracing `ChatEngine.kt` this session shows that statement is **imprecise**: a real skill key *is* available later in the same request, at the `skillContextEnricher.enrich()` call site (line 384), strictly after `SKILL_SELECTION` and strictly before `GENERATION`. `SkillContextEnricher` (`skill/SkillContextEnricher.kt`) is a working, wired production example of exactly this: it takes the real `SkillSelection`, looks up the skill's content, and inserts a **new, separate** `ContextBlock` into `context.blocks`.

The actual, more precise blocker is structural, not temporal:

1. `SkillContextEnricher` only ever **adds** a new block; it never rewrites an existing one. It has no need to locate or replace `block0` (Engine+Persona) because it isn't trying to change persona content, only add skill content.
2. `block0` (Engine+Persona) is already fully computed — engine content and persona content concatenated into one opaque string, and already passed through the assembler's token-budget trimming — by the time any real skill key exists. `ContextBlock(val role: String, val content: String)` (`ChatEngine.kt` line 100) carries no tag distinguishing "this is the persona portion" from "this is the engine portion" within `block0`, so a later stage cannot cleanly find-and-replace just the persona content without re-deriving the engine content independently (duplicating `engineRepository.getActiveEngine()` and `engineAndPersonaContent()` logic outside `RepositoryContextAssembler`) and without re-running the budget-trimming loop that already ran against the original `block0`.
3. Making persona content skill-aware in time for the real skill key would therefore require one of:
   - **(a)** Fabricating/pinning a skill key at `CONTEXT_ASSEMBLY` time (the `pinnedSkillKey ?: "general_chat"` path) — explicitly forbidden by this phase's instruction ("do not fake a skill key").
   - **(b)** Modifying `RepositoryContextAssembler.kt` to defer persona-content resolution, or to split `block0` into a separately-addressable persona block and engine block, so a later stage can safely substitute just the persona portion after skill selection, without disturbing budget accounting for the rest. This is a production **behavior/architecture change** to an existing file, not "wiring an already-built, inert facade" — it is out of scope for a phase whose own rule is "if any of the 9 conditions fail, do NOT partially activate... leave production unchanged."

Condition checked against Phase 47's own list: **"no fabricated skill key is required" fails** — not because no real skill key ever exists in the request lifecycle (it does, later), but because using it safely for persona-context resolution requires either fabricating a key at the only point persona content is currently assembled, or restructuring how that assembly works, which itself is a separate, unauthorized design decision (touching context-block structure is exactly the kind of decision this phase is not authorized to make unilaterally).

**REQUIRES HUMAN DECISION**, carried forward and sharpened from Phase 45 item 1 ("skill-timing strategy"): specifically, whether to (a) accept a two-pass context assembly (build once for budget/engine purposes, re-resolve persona content after skill selection, re-check budget), (b) split `RepositoryContextAssembler`'s persona and engine blocks so a later enricher can safely substitute one without touching the other, or (c) move skill selection earlier in the pipeline (a much larger change, since `SKILL_SELECTION` currently depends on the assembled context as one of its inputs). This phase does not choose among them.

---

## 6. Rendering-policy integration

**ESTABLISHED / TESTED.** `ProductionPersonaContextService` takes `renderingPolicy: ScalarRenderingPolicy` as a constructor parameter with a labeled technical default (`BareScalarRendering`), never selected as a product decision (KDoc, `ProductionPersonaContextService.kt` lines 27–36). Test **H** (`ProductionPersonaContextServiceTest.kt`) constructs two service instances differing only in rendering policy and asserts they select the identical `includedFieldIds` and `status`, differing only in `renderedText` — proving swap-only-affects-rendering holds at the facade level, not just at the `PersonaFieldAuthorizer` level (Phase 46 already proved it one layer down).

---

## 7. Safe fallback implementation

**IMPLEMENTED / TESTED.** `ProductionPersonaContextService.resolvePersonaContent(...)` (lines 48–61): calls `authorizer.resolve(...)` inside a `try/catch` returning `null` on any exception, then delegates to `PersonaContextFallback.resolvePersonaContent(result, coreContent)`, which returns `result.renderedText` only if `result != null && result.isUsable()`, else `coreContent` unconditionally. This is the same strict-substitution rule locked in Phase 45 (D2), now exercised one layer higher, at the facade a real caller would actually use.

---

## 8. Security / restricted-field guarantees

**TESTED**, this phase, at the facade level (not merely re-citing Phase 46's authorizer-level tests):
- Test **D**: a `restricted` field outside its `allowedContexts` never appears in the assembled string.
- Test **J**: an unregistered raw document field (simulating what the old `resolveVariantA()` would have leaked) never reaches final content — falls back to `coreContent` exactly.
- Test **K**: sweeps three separate unusable states (no document, no skill mapping, mapped-but-unauthorized) and confirms a restricted field cannot leak in any of them.
- Test **M**: reflection-based check that neither `ProductionPersonaContextService` nor `PersonaFieldAuthorizer` references the research `PersonaContextResolver` type anywhere in their declared fields or method signatures — a direct, automated check (not just a code-review claim) against "no accidental dependency on research code."

---

## 9. Test matrix and results

File: `src/test/kotlin/com/pinkdreams/chat/personacontext/production/ProductionPersonaContextServiceTest.kt`, 13 tests (A–M), self-contained `Fixture` (fresh H2 in-memory DB per test instance via `DatabaseFactory.connectInMemory()`), zero LLM/HTTP imports.

| Case | Assertion | Result |
|---|---|---|
| A | Valid persona context is used, not core content | PASS |
| B | No document → core content, exactly | PASS |
| C | Empty document → core content, exactly | PASS |
| D | Restricted field never rendered | PASS |
| E | Missing skill mapping → core content | PASS |
| F | Unknown skill key → core content | PASS |
| G | Never-throws contract holds for unseeded IDs (facade-level defense-in-depth; `RESOLVER_ERROR` itself is covered by Phase 46's `PersonaFieldAuthorizerTest`, since `PersonaFieldAuthorizer` is a non-open class that cannot be subclassed for a forced exception here) | PASS |
| H | Rendering policy changes text only, not selection/status | PASS |
| I | Core content never lost across unusable states (no-doc, empty-doc) | PASS |
| J | No raw/unregistered document field reaches final content | PASS |
| K | No restricted field reaches final content across 3 unusable states | PASS |
| L | personaId/personaVersion/skillKey correctly propagated onto `PersonaContextResult` | PASS |
| M | No reference to research `PersonaContextResolver` in the new classes | PASS |

Verified via JUnit XML (`build/test-results/test/TEST-com.pinkdreams.chat.personacontext.production.ProductionPersonaContextServiceTest.xml`):
```
<testsuite name="...ProductionPersonaContextServiceTest" tests="13" skipped="0" failures="0" errors="0" ...>
```
Combined with Phase 46's `PersonaFieldAuthorizerTest` (19/19 passing, unchanged this phase): **32/32 deterministic tests passing across both files, 0 API calls.**

Both `gradle compileKotlin -q` and `gradle compileTestKotlin -q` completed with no output (success) this phase, confirming the new facade and its test file compile cleanly against the rest of the codebase.

---

## 10. Files changed this phase

- **New:** `src/main/kotlin/com/pinkdreams/chat/personacontext/production/ProductionPersonaContextService.kt` (created in the portion of this phase before the context summary; re-confirmed present and unmodified since).
- **New:** `src/test/kotlin/com/pinkdreams/chat/personacontext/production/ProductionPersonaContextServiceTest.kt` (created this phase, 13 tests, all passing).
- **New:** this document.

No other files were created or modified in Phase 47.

---

## 11. Files deliberately untouched

`RepositoryContextAssembler.kt`, `ChatEngine.kt`, `ChatEngineFactory.kt`, `Application.kt`, `SkillContextEnricher.kt`, and every research-phase file (`PersonaContextResolver.kt`, `PersonaContextRenderer.kt`, `ResolvedPersonaContextProvider.kt`, all Diagnostic providers, `PersonaContextPocSeed.kt`, `PersonaContextRealBenchmarkTest.kt`) — confirmed via `git status --porcelain` (this phase) that the only pre-existing modifications to pipeline files (`ChatEngineFactory.kt`: +15/-0 lines; `RepositoryContextAssembler.kt`: +7/-2 lines, the `personaContextProvider`/`pinnedSkillKey` parameters) predate this session, matching Phase 46's own disclosure, and gained zero additional changes in Phase 47.

---

## 12. Was production wiring activated?

**No.** `RepositoryContextAssembler` was not modified to call `ProductionPersonaContextService`, `ChatEngineFactory`/`Application.kt` were not modified to construct or inject it, and no new enricher analogous to `SkillContextEnricher` was added to `ChatEngine.process()`. Production chat behavior after this phase is identical to before it, byte-for-byte, for every file in the real request path.

This satisfies Phase 47's own instruction precisely: of the 9 required conditions, **"no fabricated skill key is required" is not satisfiable without either faking a skill key at `CONTEXT_ASSEMBLY` or making an unauthorized structural change to how `RepositoryContextAssembler` builds its blocks** (§5). Per the instruction's own rule — "if ANY ONE of the 9 conditions is not source-verifiable... do NOT partially activate. Leave production unchanged" — wiring was correctly not attempted.

---

## 13. Remaining blockers

1. **REQUIRES HUMAN DECISION** — skill-timing strategy (§5): two-pass assembly vs. splitting the persona/engine block vs. moving skill selection earlier. This is the smallest, single remaining blocker; everything else needed to wire safely (safe fallback, restricted-field guarantees, pluggable rendering, personaId access, compilation, deterministic tests) is already **IMPLEMENTED** and **TESTED**.
2. **REQUIRES HUMAN DECISION** (carried unchanged from Phase 45, still open, not re-litigated here): data-authoring mechanism for `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` (still empty in production — re-confirming this was out of scope for this phase's zero-cost, non-behavioral mandate, and nothing in this phase touched or seeded those tables in production).
3. **REQUIRES HUMAN DECISION** (carried unchanged): minimum-viable-persona definition, final rendering-policy choice (Bare/AgeOnly/AllLabeled) — `BareScalarRendering` remains only a disclosed technical default, never elevated to a product choice in this phase.

---

## 14. Exact next decision/action

The next action is a **human decision on item 1 in §13**, not further code. Once that decision is made, the remaining engineering work is small and already de-risked by this phase's tests:
- If (b) is chosen (split persona/engine blocks): a scoped edit to `RepositoryContextAssembler.assemble()` to keep `block0` as two separately-tagged blocks, plus a new post-skill-selection enricher (structurally modeled on `SkillContextEnricher`) that calls `ProductionPersonaContextService.resolvePersonaContent(request.personaId, core.id, realSkillKey, request.content, existingPersonaBlockContent)` and substitutes the result.
- If (a) is chosen (two-pass assembly): re-running persona resolution after skill selection and re-checking the token budget, which touches more of `RepositoryContextAssembler`'s trimming logic.
- If (c) is chosen (reorder pipeline stages): the largest change, affecting `ChatEngine.process()`'s stage ordering itself and any code relying on the current `CONTEXT_ASSEMBLY`-before-`SKILL_SELECTION` order.

No code should be written toward any of these until the human decision is made — writing it now would mean guessing at the decision, which this program's discipline (Phases 33–47) has consistently avoided.

---

## 15. No API calls statement

No LLM API calls (OpenRouter or otherwise), no network requests, and no behavioral/B-vs-LA experiment were made or attempted in Phase 47. All verification in this phase was: source re-reading (`Read`/`Grep`), local compilation (`gradle compileKotlin -q`, `gradle compileTestKotlin -q`), local deterministic test execution against an in-memory H2 database (`gradle test --tests ...`), and `git status`/`git diff` inspection. This matches the phase's explicit constraint.

---

## Closing answer to Phase 47's meta-question

**"Can the new safe architecture now be connected to production without making an unresolved product/skill/data decision?"**

**No.** The blocker is narrower than prior phases believed: a real, model-selected skill key *does* become available later in the same request (via the existing `SkillContextEnricher` call site), but the persona+engine context block is already computed and budget-trimmed *before* that point, as one opaque, untagged string — so using the real skill key safely requires either fabricating one earlier (forbidden) or restructuring how `RepositoryContextAssembler` builds its blocks (an unauthorized architecture decision, not a wiring step). What was built and tested instead: `ProductionPersonaContextService`, a complete, standalone, dependency-injectable facade over the Phase 46 foundation — safe fallback, restricted-field guarantees, and swappable rendering policy all proven with 13 new deterministic tests (32/32 combined with Phase 46) — ready to be called the moment the skill-timing decision in §13 item 1 is made, with zero further foundational work required.

Phase 47 stops here. No Phase 48 is started.
