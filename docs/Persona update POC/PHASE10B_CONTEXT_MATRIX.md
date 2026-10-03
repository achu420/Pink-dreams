# Phase 10B — Verified Actual Context Matrix

Zero API calls, zero production changes. Every row below is traced from source, not inferred from variable names or labels.

---

## Full call path (traced once, applies to every variant)

```
World.createConversation(variant, skill, engineVersion?)
  → TestChatService.create(CreationRequest(personaContextVariant = variant, ...))
    → TestChatService.buildEngineFor(conversation)
      → constructs ChatEngineFactory.Dependencies(personaContextProvider = <see per-variant below>)
      → ChatEngineFactory.build(dependencies) → PipelineChatEngine
        → RepositoryContextAssembler(personaContextProvider = deps.personaContextProvider, ...)
World.sendTurn(...) → service.sendMessage(...)
  → PipelineChatEngine's context-assembly stage calls RepositoryContextAssembler.assemble(...)
    → val personaContent = if (personaContextProvider != null) personaContextProvider.getPersonaContext(...) else core.content
    → block0 = ContextBlock("system", engineAndPersonaContent(engine.content, personaContent))
  → generation stage sends the assembled ChatContext (all blocks) to HybridLlmClient.generate(request)
    → HybridLlmClient forwards non-intent/non-memory calls to the REAL OpenRouterLlmClient
```

**Separately, and never joined to the path above:** `World.sendTurn()` also calls `resolver.resolve(personaId, personaCoreVersionId, skillKey, userMessage, variant)` directly, purely to populate `ExecutionRecord`'s diagnostic columns (`includedFieldIds`, `excludedFieldIds`, `personaTokenEstimate`) for the CSV. **This call's return value is discarded — it is never passed to `RepositoryContextAssembler` or to the generation request.** A resolver invocation existing in the code is not evidence that its output reached the model; the only path that matters is the one above, through `personaContextProvider`.

---

## Verified variant matrix

| Variant | `personaContextProvider` value | Source content actually read | Selection applied | Rendering applied | Actually sent to the model? |
|---|---|---|---|---|---|
| **A** | `null` (the `?.takeIf { it != "A" }` guard forces this) | `PersonaCoreVersionRepository.content` = `BaselineConfiguration.SIMRAN_PERSONA_CORE` (static, generic personality prose, no biographical facts) | None — there is no field-level data to select from; the content itself has no structure | Plain prose text, as authored — not JSON, not markdown, not a "raw serialization" of any structured data | **YES**, but of the generic core text, never the master document |
| **B** | `ResolvedPersonaContextProvider(variant="B")` | `PersonaMasterRepository.contentJson` (the seeded `simran-master.md` JSON) | `PersonaContextResolver.resolve(..., "B")`: required + recommended + conditionally-scored fields | `PersonaContextRenderer.render(...)` — headed markdown, bolded labels | **YES** |
| **C** | `ResolvedPersonaContextProvider(variant="C")` | same as B | same as B, plus `findBehavioralCore()` fields unioned in | `PersonaContextRenderer.render(...)` | **YES** |
| **D** | `StructuredFullDocumentContextProvider(personaMasterRepo)` | same as B/C | **None** — the entire document is wrapped as one field and rendered whole, nothing filtered | `PersonaContextRenderer.render(...)` (recursively expands the single wrapped field) | **YES** |
| **X** | `SelectiveRawContextProvider(db, personaMasterRepo, fieldRegistryRepo, skillContextReqRepo)` | same as B/C/D | Internally calls `PersonaContextResolver.resolve(..., "B")` and consumes `includedFieldIds` verbatim — does not re-derive selection | Fetches each selected field's raw `JsonElement` value directly (bypassing the renderer) and calls `JsonObject(selected).toString()` | **Provider is wired correctly (traced, and production code compiles) but has NEVER been executed — zero real or dry-run invocations exist.** See "X's actual status" below. |

**`RawPersonaContextProvider`** (a separate, pre-existing class) is defined but **never instantiated anywhere in the codebase** — confirmed by a repo-wide search (`grep -rn "RawPersonaContextProvider("` matches only its own class declaration). It is not production's default, not variant A's actual mechanism, and not used by any test. Its name is misleading relative to what it would do if used (it reads `PersonaCoreVersionRepository.content`, same generic text as A's real fallback — it is not a "full raw master document" provider despite the name suggesting otherwise). It is dead code, not a hidden fifth path.

---

## X's actual status (must not be overstated)

- The class `SelectiveRawContextProvider.kt` and the `TestChatService` "X" branch **do compile** (`gradle compileKotlin` succeeded after both were added).
- A dry-run verification test, `experiment1DryRunCheck()`, was drafted in `PersonaContextRealBenchmarkTest.kt` but **was never compiled or run** — work was interrupted by the variant-A discovery before that step. On inspection while preparing this document, that draft test contains a leftover, incorrect reference (`xText()` called as a function outside the scope where `xText` was defined as a local variable) that would cause a **compile failure** if run as-written today.
- **Conclusion: X is architecturally sound by source-tracing, but empirically unverified.** No claim in this or any prior document should be read as "X has been shown to work" — only "X's code correctly reuses B's resolver selection and renders raw JSON, by inspection." This must be confirmed by an actual passing dry run before any real API call is spent on it.

---

## The correct 2×2 matrix, restated with actual mappings

| Cell | Selection | Formatting | Which variant/provider | Status |
|---|---|---|---|---|
| **FS** (Full + Structured) | FULL | STRUCTURED | **D** (`StructuredFullDocumentContextProvider`) | Exists, confirmed actually sent, real data collected (Phase 7, 30 scenarios) |
| **SS** (Selective + Structured) | SELECTIVE | STRUCTURED | **B** (and C) (`ResolvedPersonaContextProvider`) | Exists, confirmed actually sent, extensive real data (Experiments 1/3, Phase 6) |
| **SR** (Selective + Raw) | SELECTIVE | RAW | **X** (`SelectiveRawContextProvider`) | Code exists and is correctly wired; **zero executions, dry-run or real** |
| **FR** (Full + Raw) | FULL | RAW | **Does not exist anywhere in this codebase as an actually-sent condition.** `PersonaContextResolver.resolveVariantA()` produces this exact content but is only ever called diagnostically. | **Must be newly built.** |

Variant **A** does not occupy any cell of this matrix — it is not a formatting variant of the biographical data at all, since it contains no biographical data to format. It is a third, orthogonal condition ("no persona facts, generic character prose") that was previously mislabeled as belonging to this matrix. It should be tracked separately, not retired, since it may still be a useful reference point for "what happens with zero grounding" — but it must never again be cited as "full raw document."
