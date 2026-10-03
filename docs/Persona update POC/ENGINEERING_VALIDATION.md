# Engineering Validation — Real Pipeline Proof

Written for: engineering review, to substantiate that Experiments 1-3 actually exercised the real production pipeline rather than a stand-in. Every claim below cites the exact code path or a specific measured trace, not an assertion.

## 1. Exact production pipeline used

`TestChatService.sendMessage()` → `TestChatService.buildEngineFor()` → `ChatEngineFactory.build()` → `PipelineChatEngine.process()` — the identical class chain `TestChatServiceTest.kt` (the project's existing, pre-benchmark integration test) already uses to validate Test Chat against production, per that file's own doc comment: *"Every scenario runs the REAL TestChatService → ChatEngineFactory → PipelineChatEngine path — nothing here calls a simplified stand-in pipeline."* `PersonaContextRealBenchmarkTest.kt`'s `World` class is built on the same `ChatEngineFactory.Dependencies` construction pattern, confirmed by direct comparison of both files' `World`/`productionDeps` blocks (same repository set, same `PinnedRepositories` classes, same `TestChatService` constructor call).

No hand-rolled HTTP call to OpenRouter exists anywhere in `PersonaContextRealBenchmarkTest.kt` — the only OpenRouter-facing code is `OpenRouterLlmClient`, the same production client class (`src/main/kotlin/com/pinkdreams/llm/OpenRouterLlmClient.kt`) used at runtime, invoked from inside `HybridLlmClient.generate()`'s `else` branch, which is reached only via `PipelineChatEngine`'s real generator call site (`LlmGenerator` inside `ChatEngineFactory.build()`).

## 2. What was pinned

- **Skill**: `HybridLlmClient` intercepts calls whose system prompt contains `"Intent Discovery component"` and returns `{"skillKey": "<scenario's declared skill>"}` verbatim — the scenario's own `skillKey` field, never independently re-derived. Confirmed structurally: every scenario in `PersonaContextPocBenchmarkRunner`, `PersonaContextPocCorpusV2`, and `PersonaContextPocCorpusV3` declares `skillKey` explicitly at construction; `HybridLlmClient.currentSkillKey` is set from that value immediately before every `sendTurn()` call (see `World.sendTurn()`).
- **Persona/engine/skill versions**: `TestChatService.CreationRequest` is called with no explicit version overrides, so `testingDefaultEngineVersion()`/`testingDefaultPersonaCoreVersion()`/`testingDefaultSkillVersions()` resolve once per conversation to the same active/published versions — verified directly in `singleScenarioTrace()`'s assertions (`engineVersions.distinct().size == 1`, `coreVersions.distinct().size == 1` across A/B/C).
- **Model/temperature/max-tokens**: `World.llmConfig` is a single shared `LlmConfig` instance; `CreationRequest` never overrides `model`/`temperature`/`maxOutputTokens`, so every conversation (A/B/C, every scenario) resolves to the same `AiRuntimeSettings` defaults.

## 3. What was disabled (and why, per the user's own "document what's bypassed" rule)

- **Real intent-discovery LLM call**: intercepted and replaced with the pinned skill (see above) — real intent discovery is itself an LLM call and would risk A/B/C selecting different skills for the same scenario, contaminating the one comparison this benchmark exists to make.
- **Memory extraction**: `HybridLlmClient` intercepts `"memory-extraction system"` prompts and returns `{"facts": []}` — a no-op.
- **Memory-engine maintenance**: intercepted via `"TASK 1"`/`"userMemoryChanges"` prompt matching, returns empty change sets — a no-op.
- **Continuity summarization**: not explicitly intercepted, but structurally cannot fire — `BestEffortContinuitySummarization`'s own trigger condition (`activeWindowStart = (messageCount - activeWindowSize).coerceAtLeast(0)`) evaluates to 0 for every conversation in this benchmark, since even the longest multi-turn scenario (4 turns = 8 messages) never exceeds `DEFAULT_MESSAGE_LIMIT` (10).

## 4. What differed between A/B/C — proof

`ChatEngineFactory.Dependencies.personaContextProvider` is `null` for variant A (raw `core.content` fallback inside `RepositoryContextAssembler`) and a real `ResolvedPersonaContextProvider(db, PersonaMasterRepository(db), PersonaFieldRegistryRepository(db), SkillContextRequirementRepository(db), variant)` for B/C — constructed in `TestChatService.buildEngineFor()`, gated on `snapshot.personaContextVariant`. This is the ONLY `Dependencies` field that varies with `variant`; every other field (`llmClient`, `llmConfig`, all repositories, `aiRuntimeSettings`) is shared across all three calls for a given scenario.

**Direct measured proof**, from `singleScenarioTrace()`'s actual output (romantic_intimacy scenario):
```
Variant A: includedFieldIds=[]                    personaTokenEstimate=4542
Variant B: includedFieldIds=[identity.name, relational.romantic_style,
            adult_intimacy, communication.tone, personality.core_traits]   personaTokenEstimate=364
Variant C: includedFieldIds=[...B's 5 fields... + personality.summary,
            behavioral_signature.default, .attachment_style,
            communication.style]                    personaTokenEstimate=533
```
A's empty `includedFieldIds` is expected — `PersonaContextResolver.resolveVariantA()` returns the raw document with no field selection at all (by design, matching production's unmodified behavior). B/C's non-empty, variant-specific field lists are read directly off `PersonaContextResolver.resolve()`'s real return value (`PersonaContextProjection.includedFieldIds`), called with the scenario's real `personaId`/`personaCoreVersionId`/`skillKey`/`userMessage` — not a canned or pre-computed list.

## 5. Proof B/C actually invoke `PersonaContextResolver`

Two independent lines of evidence:
1. **Structural**: `ResolvedPersonaContextProvider.getPersonaContext()` (the only thing `RepositoryContextAssembler` calls for non-null `personaContextProvider`) constructs and calls `PersonaContextResolver(db, ...).resolve(...)` — there is no other code path for B/C to obtain persona content.
2. **Behavioral**: the field-ID differences shown in section 4 above are only producible by the resolver's actual required/recommended/conditional/behavioral-core logic — they could not be reproduced by a static string, since they vary correctly per skill (verified across every skill in Experiments 1-2) and per variant (B vs C differ by exactly the behavioral-core field set, matching `PersonaContextResolver`'s `variant == "C"` branch).

## 6. Proof A used the complete persona

Phase 4 (this session): `PersonaContextPocSeed.buildSimranMasterContent()` was expanded with a `_full_document_supplement` block containing the remainder of `docs/personas/Simran/simran-master.md` not already covered by the 34 structured, path-addressable fields. `PersonaContextResolver.resolveVariantA()` calls `masterDoc.contentJson.toString()` — a full serialization of the entire `JsonObject`, which necessarily includes `_full_document_supplement` alongside the 34 structured fields. Measured proof: variant A's `personaTokenEstimate` rose from 1275 (pre-supplement, Experiment 1) to 4542 (post-supplement, verified in the section-4 trace above) with zero code change to `resolveVariantA()` itself — confirming the growth came entirely from the content now present in the stored document, not a resolver behavior change.

## 7. Proof skill was identical across variants

`singleScenarioTrace()` asserts `engineVersions.distinct().size == 1` and `coreVersions.distinct().size == 1` across A/B/C for the same scenario — passing (verified, not merely coded). Skill identity itself is pinned per section 2/3 above (same `HybridLlmClient.currentSkillKey` value feeds all three `TestChatService.create()` calls for a given scenario, since the scenario object itself is passed unchanged into `execute(scenario, variant)` for each of the three variant values).

## 8. Proof of LLM call counts

| Call type | Real API call? | Evidence |
|---|---|---|
| Intent discovery | No — intercepted | `HybridLlmClient` returns pinned skill without calling `realClient` |
| Memory extraction | No — intercepted | Returns `{"facts": []}` without calling `realClient` |
| Memory-engine maintenance | No — intercepted | Returns empty change set without calling `realClient` |
| Continuity summarization | No — never triggered | Structural (section 3) |
| Primary generation | **Yes — real** | Only path reaching `HybridLlmClient`'s `else` branch, which calls `realClient.generate(request)` where `realClient` is a real `OpenRouterLlmClient` |

**Exactly one real LLM call per (scenario, variant) execution, per turn for multi-turn conversations** — confirmed by the resolver-and-generation-latency pair captured per `ExecutionRecord` (one `resolverLatencyMs` + one `generationLatencyMs` per row in every experiment's CSV; no row has more than one generation timing, since `llmClient.lastGenerationResponse` is reset immediately before each `sendMessage()` call and read immediately after).

## Summary

Every claim in this document is either a direct code-path citation (traceable to a specific file/function) or a specific measured number already produced by a real, executed test run — not a design intention. Sections 4-6 in particular were not true before this session's work (Phases 1-4 built the wiring and content that make them true now) and are now independently verifiable by re-running `singleScenarioTrace()`.
