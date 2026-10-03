# Phase 33 — Production Readiness / Engineering-Decision Gate Audit

**Zero API calls. Zero production, mapping, resolver, or renderer changes.** This is a source-code audit only, performed by reading the actual current codebase (not by re-citing prior phase reports). No historical Phase 10–32 artifact was modified. No new experiment is designed, authorized, or executed. No path from `PHASE32_ENGINEERING_DECISION_PACKAGE.md` is recommended, ranked, or selected.

---

## STEP 1 — Reconstruct the Current Production Difference

### 1.1 What B currently sends to the model — and where it actually runs

Traced from source, not from prior reports:

- **Production instantiation** (`Application.kt:293-325`): `ChatEngineFactory.Dependencies(...)` is built once at startup for the real, live chat engine. Its `personaContextProvider` field is **never set** in this construction — it is omitted entirely, so it takes the class default.
- **The default** (`ChatEngineFactory.kt:118`): `val personaContextProvider: PersonaContextProvider? = null`.
- **What that null does** (`RepositoryContextAssembler.kt`, the `assemble()` method): `val personaContent = if (personaContextProvider != null) { personaContextProvider.getPersonaContext(...) } else { core.content }`. With a null provider, production always uses `core.content` — the plain-text `PersonaCoreVersion.content` field.

**Conclusion, stated precisely: in real production chat, "B" (the `ResolvedPersonaContextProvider` / `PersonaContextResolver` / `PersonaContextRenderer` selective-retrieval machinery this entire investigation tested) is never invoked at all.** Production's actual persona content is the same generic, fact-sparse `core.content` text that Phase 10A identified as historical variant "A" — not B, not LA, not L. This machinery only ever runs inside `TestChatService` (the benchmark/POC harness), which builds its own separate `RepositoryContextAssembler` instance per test conversation and is the only code path that ever supplies a non-null `personaContextProvider`.

### 1.2 What LA sends to the model

`AgeOnlyLabeledScalarDiagnosticProvider.kt` (in `src/main/kotlin`, but wired only from `TestChatService.kt`, never from `Application.kt`/`ChatEngineFactory`): calls `PersonaContextResolver.resolve(..., "B")` verbatim for field selection (identical to B's own selection, no re-derivation), then renders the result with one change from the shared rendering logic — `renderTopLevelValue()`'s `JsonPrimitive` branch checks `if (fieldId == "identity.age")` and, only in that case, prefixes the value with `"Age: "`; every other top-level scalar and every nested object/array renders exactly as `PersonaContextRenderer` (the class B uses) would.

### 1.3 The exact renderer/provider difference responsible for LA's behavior

`PersonaContextRenderer.kt`'s `renderValue()` (used by B, via `ResolvedPersonaContextProvider` → `PersonaContextResolver.resolve()`) has this exact code for a `JsonPrimitive`:

```kotlin
is kotlinx.serialization.json.JsonPrimitive -> {
    val value = element.jsonPrimitive.content
    if (value.length < 500) value else value.take(500) + "..."
}
```

The `fieldId` parameter is accepted by the function but **never referenced** in this branch — every top-level scalar renders as a bare value, with no field-name label, regardless of which field it is. `AgeOnlyLabeledScalarDiagnosticProvider.kt`'s equivalent branch (`renderTopLevelValue()`) is a **separate, copied, non-shared** implementation that adds one conditional: if `fieldId == "identity.age"`, prefix with `"Age: "`. **No line of `PersonaContextRenderer.kt` has ever been modified to introduce this behavior** — it exists only in the parallel diagnostic file.

### 1.4 Could LA's age-only labeling be introduced into the production renderer without changing other behavior?

This question has two parts that must not be conflated: **(a) modifying `PersonaContextRenderer.kt` itself** to add the label, versus **(b) making B/LA reach real production traffic at all**, since today neither does.

**On (a) — the rendering change itself, in isolation:**

| Dimension | Would change? | Basis |
|---|---|---|
| Field selection | **No** | The label is applied in `renderValue`/`renderTopLevelValue`, strictly downstream of `PersonaContextResolver.resolve()`'s field-selection logic (`selectedFieldIds`, `canAccessField`, `PersonaFieldRelevanceScorer`) — none of which the diagnostic touches, confirmed by direct reading of `AgeOnlyLabeledScalarDiagnosticProvider.kt`, which calls `resolve(..., "B")` verbatim rather than reimplementing selection. |
| Field values | **No** | `element.content` (the underlying string/number value) is read identically in both branches; only a label prefix is added to the rendered *string*, never to the stored value. |
| Field ordering | **No** | Both use the same `groupBy { classification }.toSortedMap()` and the same insertion-ordered field map (`mutableMapOf`/`linkedMapOf`, populated by iterating `selectedFieldIds` in the same order) — confirmed identical in Phase 15/20's own line-by-line comparison, re-verified here directly against current source. |
| Restricted-field behavior | **No** | `canAccessField()` (`PersonaContextResolver.kt:132-147`) is evaluated entirely inside `resolve()`, before any rendering occurs; a labeling change downstream of that gate cannot affect which fields pass it. |
| Persona semantics | **No** | The label is a display-only string transformation; the underlying JSON value, its classification, and its semantic meaning in the master document are untouched. |
| Memory behavior | **No** | Memory selection (`MemoryService.selectForContext`) is a separate stage in `RepositoryContextAssembler.assemble()`, invoked independently of `personaContent`/`personaContextProvider` and never reads persona-rendering output. |
| User-profile behavour | **No** | Same reasoning — `profileContent(profile)` is assembled as a separate `ContextBlock`, independent of the persona block's rendering. |
| History behavior | **No** | `messages`/`continuityBlock` are assembled from `messageRepository`/`conversation.continuitySummary`, entirely independent of persona rendering. |
| Skill behavior | **No** | The skill key (`pinnedSkillKey` in tests; the resolved skill in real intent-discovery-driven production flow) is an input *to* `resolve()`, not an output influenced by rendering — labeling a value after selection cannot feed back into skill resolution. |

**On (b) — reaching production traffic at all**: this is the larger, unaddressed gap. Making LA's behavior (or B's, or any selective-retrieval variant) live in production would require setting `personaContextProvider` to a non-null value in `Application.kt`'s `chatEngineDependencies` construction — a change this program's entire Question-B investigation has never made, tested end-to-end in a live setting, or evaluated for the dozens of skills/scenarios production chat actually serves (as opposed to the two narrow, hand-picked derived-numeric scenarios tested). **This is a materially larger step than "adding a label" — it is activating an entire code path (selective retrieval itself) that has never carried real user traffic.**

---

## STEP 2 — Production-Safety Risk Audit

### 2.1 Context semantics

| Item | Audit finding |
|---|---|
| Scalar fields other than age | `renderTopLevelValue()`'s conditional (`if (fieldId == "identity.age")`) means every other top-level scalar (`identity.name`, `identity.hometown`, `identity.current_city`, `life_context.occupation`, `communication.style` — the six fields confirmed present in the tested scenario's field set) renders bare, exactly as under B. Confirmed unaffected by direct source reading, not by the diagnostic's own past test output. |
| Nested objects | Rendered via `renderObject()`, a byte-for-byte copy of `PersonaContextRenderer`'s own `renderObject()` — already labeled with `**Key:**` bolding in both B and LA, unaffected by the age-only change. |
| Arrays | Rendered via `renderArray()`, same copy relationship — unaffected. |
| Null values | `PersonaMasterRepository.resolvePath()` returns `null` when a path segment is missing; both the resolver's field-value map construction (`if (value is JsonElement)`) and the diagnostic's equivalent line skip null/non-JsonElement values identically — no divergence introduced. |
| Booleans | The current registered field set (27 entries, per `PersonaContextPocSeed.kt`) contains no boolean-typed field content; this is untested territory for both B and LA equally, not a labeling-specific gap. |
| Strings | The overwhelming majority of registered fields (name, hometown, current_city, occupation, communication.style, etc.) are strings — confirmed rendered identically (bare) in both B and LA except for `identity.age`. |
| Numbers | Only `identity.age` is a numeric `JsonPrimitive` among the currently registered top-level scalars — confirmed by re-reading `buildSimranMasterContent()`'s `identity` object (`name`, `age`, `hometown`, `current_city` — only `age` is `put("age", 27)`, a number; the rest are `put(..., "string")`). This is why the tested mechanism could only ever manipulate this one field in the current corpus — not because of a design limitation, but because no other top-level scalar in the registered set is numeric. |
| Dates | No registered field is date-typed in the current schema — untested, not a labeling-specific gap. |
| Enums | No registered field uses an enum representation — untested, not a labeling-specific gap. |

### 2.2 Rendering behavior

| Item | Audit finding |
|---|---|
| Existing structured rendering (B) | Unaffected — `PersonaContextRenderer.kt` itself has not been modified by any phase in this program; the diagnostic is a separate file. |
| JSON/raw rendering (X/`SelectiveRawContextProvider`, FR/`FullRawContextProvider`) | Entirely separate provider classes; the age-labeling mechanism has never been applied to or tested against raw JSON rendering. |
| Field headings | `## $heading` sections use the same `classificationToHeading()` mapping in both B and LA (copied verbatim) — unaffected. |
| Section boundaries | Same `sections.joinToString("\n\n")` join in both — unaffected. |
| Ordering | Addressed in 1.4 above — unaffected. |
| Escaping | Neither `PersonaContextRenderer.kt` nor the diagnostic performs any escaping of field values beyond Kotlin's own string handling — no divergence, and no escaping-related risk specific to adding a label prefix (a label is a fixed, hardcoded string, not derived from user or model input). |
| Truncation | Both `renderValue()` (B) and the diagnostic's equivalent apply the same `if (value.length < 500) value else value.take(500) + "..."` truncation rule to the underlying value — the label prefix is added outside this truncation check in the diagnostic, meaning a label could in principle push a rendered line slightly past what an equivalent unlabeled line would consume, though for `identity.age` specifically (a 2-digit number) this is a negligible (5-character) addition. |
| Tokenization | No phase in this program has measured LA's tokenization against a real tokenizer — see Step 3. |

### 2.3 Retrieval behavior

Confirmed by direct source reading (not by re-assertion of prior findings): the label is applied strictly after `PersonaContextResolver.resolve()` returns its `PersonaContextProjection` (`includedFieldIds`, `excludedFieldIds`, `renderedText` — note the diagnostic does not even use `resolve()`'s own `renderedText`, it re-renders from the same `includedFieldIds` independently). Because the labeling logic is entirely downstream of and has zero code path back into `resolve()`, `canAccessField()`, `PersonaFieldRelevanceScorer`, or the skill-context-requirement lookups, it **cannot** alter: selected fields, resolver behavior, skill mappings, restricted fields, or retrieval ranking (there is no ranking mechanism beyond the deterministic required/recommended/conditional tiering, which the label never touches). **Context size** is minimally affected — see Step 3 for the concrete character/token-count implications, which are not yet measured against a real tokenizer.

### 2.4 Runtime behavior

| Item | Audit finding |
|---|---|
| Latency | Not measured for LA specifically in any phase of this program. The change itself (a single conditional string-prefix operation per scalar field, evaluated at most 6 times per rendered payload in the tested scenarios) is computationally trivial relative to the network-bound LLM generation call that dominates observed latency in every phase's own logs (typically several seconds per call) — this is a reasoned expectation based on the code's structure, **not a measured result**, and is stated as such rather than as a validated fact. |
| Additional model calls | None — the labeling change affects only the content of the existing single system-block assembly step; it introduces no new call, retry, or round-trip. |
| Allocation/caching implications | `AgeOnlyLabeledScalarDiagnosticProvider` allocates its own `linkedMapOf`/`mutableListOf` structures per call, exactly as `ResolvedPersonaContextProvider`'s underlying `PersonaContextResolver.resolve()` does — no additional caching layer exists or would be introduced by this specific change; this has not been benchmarked for memory/GC impact. |
| Deterministic behavior | **Confirmed, repeatedly, across Phases 10C/16/20/24/26/27/30's own zero-cost checks**: calling the same provider with the same inputs twice in immediate succession produces byte-identical output. This is an established fact, not merely presumed. |
| Failure handling | Neither `PersonaContextRenderer.kt` nor the diagnostic contains any failure-handling logic beyond what already exists in `resolvePath()` (returns `null` on a missing path, silently skipped by the caller) and `personaMasterRepo.findByPersonaCoreVersionId()` (returns `null` if no master document exists, causing `getPersonaContext()` to return `""` — identical behavior in B's `ResolvedPersonaContextProvider` and in the diagnostic, confirmed by direct comparison of both classes' early-return lines). |
| Fallback behavior | If the master document lookup fails, both B and LA return an empty string for the persona-content block, which `RepositoryContextAssembler` would then concatenate as an empty block — identical, pre-existing behavior neither the B pathway nor the labeling change alters. |

**None of the above is asserted to be "safe" merely because the diagnostic provider has behaved deterministically in prior phases — determinism of output given fixed input is a narrower, already-established claim than "safe under the full range of production conditions" (concurrent access, malformed persona documents, partial data, etc.), none of which this program has tested for B, LA, or L.**

---

## STEP 3 — Token/Cost Audit

Using only existing, already-collected measurements (no new API calls):

- **Rendered character count**: For the tested scenario (Pune timeline, `general_chat` skill), Phase 16's own audit recorded the exact B-vs-L diff: six scalar lines, each gaining a label prefix of roughly 4-12 characters (`"Name: "`, `"Age: "`, `"Hometown: "`, `"Current city: "`, `"Occupation: "`, `"Style: "`). For LA specifically (age-only), only one line changes: `"27"` (2 characters) → `"Age: 27"` (7 characters) — a **5-character increase** in the rendered persona block, confirmed by direct diff in every phase from Phase 20 onward.
- **Approximate token count**: `PersonaContextResolver.estimateTokens()` uses a `length/4` character-based heuristic (confirmed at `PersonaContextResolver.kt:149-152`). A 5-character increase under this heuristic rounds to at most a 1-2 token increase — a back-of-envelope calculation from the existing formula, **not a measurement against any real tokenizer**.
- **Request payload size**: proportionally negligible — none of Phase 26, 27, or 30 captured `total_tokens`/`prompt_tokens` fields for their calls (unlike Phase 10D/10E, which did capture real OpenRouter-reported token counts for the FS/FR/SS/SR comparison, but never for B/LA/L). **This means the actual, real-tokenizer-measured cost impact of LA vs. B has never been captured in this program, for any of Phase 16, 19, 20, 21, 22, 24, 26, 27, or 30.**
- **Expected model cost**: given the above, no real cost figure exists to report. The character-count-based estimate (≈5 characters, ≈1-2 tokens per labeled scalar) suggests a negligible per-request cost delta if this pattern held at scale, but **this is an inference from an approximate heuristic, not a validated measurement, and this audit does not present it as one.**

**If an actual tokenizer/model measurement were desired, it would require either a real API call (capturing `usage.prompt_tokens` from an actual Op24nRouter response) or a local tokenizer library invocation — neither was performed in this phase, per the explicit "do not run API calls" constraint. This measurement remains unvalidated.**

---

## STEP 4 — Regression Surface

Every consumer of `PersonaContextRenderer` (searched exhaustively via `grep -rln "PersonaContextRenderer"` across `src/main/kotlin`):

| Consumer | Uses scalar rendering? | Could labels alter behavior? | Risk |
|---|---|---|---|
| `PersonaContextResolver.kt` (constructs a `PersonaContextRenderer()` instance, calls `.render(fieldValueMap)` for variants B/C) | Yes — this IS the scalar-rendering call site | No — `PersonaContextRenderer.kt` itself has not been, and under this audit's Step 1.4 findings would not need to be, modified in a way that touches anything but the `JsonPrimitive` branch's output string | **Confirmed unaffected** by any change scoped to that one branch; would become **potentially affected** the moment `PersonaContextRenderer.kt` itself is edited (as opposed to a parallel diagnostic file) |
| `StructuredFullDocumentContextProvider.kt` (variant D/FS — constructs its own `PersonaContextRenderer()`, renders the full field set) | Yes | If `PersonaContextRenderer.kt` were edited to add labeling for all callers, this consumer's output would change too (it was never tested with labeling — Phase 10D/10E's FS/FR results predate and are independent of the labeling investigation) | **Potentially affected** if the shared renderer class is edited; **confirmed unaffected** today, since the renderer is unmodified |
| `TestChatService.kt` (wires B/D/X/FR/L/LA providers by variant string, per conversation) | Indirectly — it selects which provider to instantiate, never renders directly | No — this is a dispatch/wiring layer, not a rendering consumer | **Confirmed unaffected** |
| `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `LabeledScalarDiagnosticProvider.kt` | These are copies, not consumers of the shared class — they duplicate the relevant logic rather than calling into `PersonaContextRenderer` | N/A — by construction, editing `PersonaContextRenderer.kt` does not affect these diagnostic files, and vice versa, since they share no code, only a parallel implementation | **Confirmed unaffected in either direction** |
| `RepositoryContextAssembler.kt` (production + test) | No — it calls `personaContextProvider.getPersonaContext(...)` polymorphically; it never imports or calls `PersonaContextRenderer` directly | No — this class has no knowledge of rendering internals | **Confirmed unaffected** |
| Any admin/observability route (`AdminObservabilityRoutes.kt`, etc.) | No — searched; no reference to `PersonaContextRenderer` found in the admin/API route files | No | **Confirmed unaffected** |
| Production chat via `Application.kt`/`ChatEngineFactory` (the real, live user-facing path) | **No — confirmed in Step 1.1: `personaContextProvider` is always `null` in production, so `PersonaContextRenderer` is never invoked for real user traffic at all today** | Not applicable while this remains the case; would become directly, fully affected the moment production wiring changes (a decision this audit does not make or evaluate) | **Confirmed unaffected today; unknown/requires validation if production wiring is ever changed — this is the single largest open item in the entire regression surface** |

## STEP 5 — Minimum Validation Suite Required Before a Production Renderer Change

### A. Deterministic unit tests
Exact-match tests asserting `PersonaContextRenderer.render()`'s output for a fixed field-value map, both with and without any proposed labeling change, covering: every current field classification (identity, life_context, personality, etc.), a field with a null/missing value, a field at the 500-character truncation boundary, and (if labeling is generalized beyond age) every top-level scalar type currently registered. **None of these exist today** — no unit test file for `PersonaContextRenderer.kt` was found in `src/test/kotlin` beyond the ad hoc assertions embedded in the Phase 16/20/24/26/27/30 benchmark test methods themselves, which are POC/diagnostic tests, not unit tests guarding the production renderer.

### B. Pipeline tests
Provider output → actual request payload equivalence tests, of the kind already established via `interceptGeneration=true` in Phase 10C and reused in every subsequent phase's pre-flight audit. **These exist for the diagnostic providers and B, run repeatedly and successfully** (most recently Phase 24/26/27/30's own pre-flight checks) — but only within the `TestChatService` test harness, never against the actual production `ChatEngineFactory`/`Application.kt` wiring, since that wiring never supplies a non-null provider today.

### C. Regression tests
Re-running the full Phase 10D/10E-style 30-scenario corpus (or a successor) under any proposed production rendering change, to confirm no regression on the many non-numeric, non-derived-reasoning scenarios that corpus covers. **Not performed for LA or L at any point in this program** — every labeling experiment (Phases 16 through 30) used only the two narrow derived-numeric scenarios, never the broader corpus.

### D. Restricted-field tests
Confirming that no proposed labeling change can cause a restricted field (`intimate_anatomy`, `adult_intimacy`) to be selected, rendered, or exposed. **Already covered, repeatedly, for the exact scope tested**: every phase from Phase 10C onward included an explicit restricted-field check, and every one passed (0 leakage across hundreds of calls). This item is **established for the tested scope**, not an open gap — though it has only ever been checked for the two scenarios and the `about`/`general_chat` skills used, not for `romantic_intimacy`/`foreplay` (the only skills where restricted fields are ever legitimately selectable) under a labeling change.

### E. Token/cost tests
Comparing B vs. any proposed production rendering using real, API-measured token counts (not the character-based heuristic used in Step 3). **Not performed anywhere in this program** — this is the concrete gap Step 3 identified.

### F. Behavioral validation
This is explicitly **distinguished from the already-completed Phase 14–30 research experiments**: those experiments answered a narrow scientific question (does labeling change model output on two specific derived-numeric scenarios) using a diagnostic, non-production-wired provider. A production behavioral-validation suite would need to test the *actual* production-integrated rendering change (once one exists) against the *actual* production skill/scenario distribution — neither of which exists yet. **No new behavioral experiment is run in this phase**, per instruction.

---

## STEP 6 — Three Paths (reconstructed from Phase 32, unchanged)

### Path A — Change the existing decision boundary

- **Prerequisites**: an explicit engineering/product decision to redefine `PHASE23_DECISION_BOUNDARY.md`'s "more than two scenario types" clause.
- **Engineering work**: none required to satisfy the boundary itself (a documentation/policy change); substantial work still required before any actual production change (Steps 4-5 above).
- **Experimental work**: none required by this path itself.
- **Unresolved questions**: everything in Phase 32 §4/§10 — causal mechanism, LA-vs-L, magnitude stability, generalization beyond two narrow scenarios, and (newly surfaced by this audit) the fact that neither B nor LA has ever run in production at all.
- **Production risks**: proceeding toward a production change on the strength of two scenario types, given this audit's Step 1 finding that production currently uses neither B nor LA (it uses the "A"-equivalent generic core content) — adopting LA in production would simultaneously introduce selective retrieval (B) *and* labeling (LA) to real traffic for the first time, a larger and differently-scoped change than "swap B for LA," which was this document's original framing.
- **What evidence would still be missing**: everything in Step 5's unmet items (A-C, E), regardless of this path's boundary decision.

### Path B — Create genuinely new persona content, register/validate a third clean scenario, then conduct the required B-vs-LA experiment

- **Prerequisites**: new/expanded persona master-document content with an unambiguous, independent, derivable numeric fact pair (per `PHASE29_THIRD_SCENARIO_INVENTORY.md`'s findings on why none currently exists).
- **Engineering work**: field registration/mapping validation (Phase 13-style zero-cost checks) for the new content; confirmation the new scenario isolates the same phenomenon (top-level scalar operand present, B not at ceiling, no numeric/entity overlap with the existing 27/21/24/29 cluster).
- **Experimental work**: a full B-vs-LA statistical test (interleaved, pre-registered, same Fisher's-exact framework) once the new scenario exists — not designed or executed here.
- **Unresolved questions**: same as Path A, plus whatever the new scenario's own result would (or would not) resolve.
- **Production risks**: the new scenario, even if successful, still would not by itself validate any of Step 5's B/C/E items (broader regression, real token cost) — it would only add a third scenario-type data point to the same narrow research question this program has pursued throughout.
- **What evidence would still be missing**: same as Path A's list, plus the new experiment's own execution.

### Path C — Preserve the existing boundary unchanged

- **Prerequisites**: none — this is the status quo.
- **Engineering work**: none.
- **Experimental work**: none required, though not precluded if separately authorized in the future.
- **Unresolved questions**: all of them remain open indefinitely under this path, including (per this audit's own new finding) the fact that neither B nor LA has ever touched production traffic.
- **Production risks**: none introduced (nothing changes); the cost of this path is opportunity cost only — a possibly-real, twice-significant effect remains unexploited pending further evidence or a boundary decision.
- **What evidence would still be missing**: everything in Step 5, plus the third-scenario-type requirement itself.

**No path is ranked, scored, or recommended.**

---

## STEP 7 — Decision Gate

### ESTABLISHED

- Production chat, as configured in `Application.kt` today, never invokes `PersonaContextResolver`, `PersonaContextRenderer`, `ResolvedPersonaContextProvider`, or any labeling variant (LA, L) — `personaContextProvider` is always `null` in the production `Dependencies`, so production always uses `core.content` (the generic, fact-sparse persona-core text), for every real user conversation.
- `AgeOnlyLabeledScalarDiagnosticProvider`'s age-only labeling logic is applied strictly downstream of field selection, with no code path back into selection, `canAccessField`, or skill-mapping logic — confirmed by direct source reading in this phase, not by re-citing prior claims.
- The labeling change, in isolation, does not alter field selection, field values, field ordering, restricted-field gating, memory/profile/history assembly, or skill resolution — each traced to its specific, unmodified code location in this phase.
- Determinism of the diagnostic providers' output (same input → same output) is an established, repeatedly-confirmed fact, not merely presumed.
- Restricted-field non-leakage has been confirmed, repeatedly, for every scenario/skill combination actually tested (0 leakage across hundreds of calls) — though only for the `about`/`general_chat` skills, never for `romantic_intimacy`/`foreplay`.
- No unit-test suite, broad regression corpus, or real-tokenizer-measured cost comparison exists for LA or L anywhere in this program.

### NOT ESTABLISHED

- Causal mechanism behind the observed B-vs-LA statistical differences (Phase 26, Phase 30).
- Whether age-specific labeling is necessary or sufficient relative to full scalar labeling (LA vs. L) — only two small, identical-scoring direct comparisons exist.
- Magnitude stability of the effect (10-60 points observed across samples/scenarios).
- Generalization beyond the two tested scenario types, this one persona, and the two skills used.
- Real, tokenizer-measured token/cost impact of introducing labeling (only a character-count heuristic exists).
- Latency, allocation, or concurrency behavior under any production-scale load.
- Behavior of a labeling change against the broader Phase 10D/10E-style scenario corpus (regression risk).
- Whether the effect would hold, or how it would interact, if selective retrieval (B) itself were ever activated in production for the first time — a change this audit found to be a prerequisite for LA to matter in production at all, and one this program has never tested end-to-end outside the benchmark harness.

### REQUIRED BEFORE PRODUCTION CHANGE

- A deterministic unit-test suite for `PersonaContextRenderer.kt` (or its production-integrated successor), covering every current field type and edge case (Step 5A).
- Pipeline (provider-to-request) equivalence tests run against the *actual* production `ChatEngineFactory`/`Application.kt` wiring, not only the `TestChatService` harness (Step 5B, extended).
- A full regression pass against the broader existing scenario corpus (Phase 10D/10E-style or successor), not just the two narrow derived-numeric scenarios (Step 5C).
- Restricted-field verification extended to `romantic_intimacy`/`foreplay`, the only skills where restricted fields are ever legitimately selectable, under any proposed rendering change (Step 5D, extended).
- Real, API-measured token/cost comparison (Step 5E) — not the character-count heuristic used in this audit.
- A separate, explicit engineering evaluation of activating selective retrieval (B) in production at all, since this audit found that is a logical prerequisite to LA mattering in a live system, and it has never been done or tested end-to-end outside the benchmark harness.

### REQUIRES HUMAN/ENGINEERING OWNER DECISION

- Which of Path A, B, or C to pursue (Phase 32 §11 — unchanged, restated here as still unresolved).
- Whether the "more than two scenario types" boundary clause should be revised, held, or superseded by a different criterion entirely (a policy decision, not an experimental one).
- Whether the newly-surfaced finding in this audit — that activating LA in production would also mean activating selective retrieval (B) in production for the first time — changes the risk calculus enough to warrant treating this as a larger architectural decision than the original "B vs. LA" framing assumed. This audit surfaces the finding; it does not resolve what to do about it.
- Resourcing and timeline for any of Step 5's required validations, none of which this investigation is positioned to perform as a continuation of the existing Question-B research track.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE33_PRODUCTION_READINESS_AUDIT.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Tests/checks performed

Source-code inspection only (no code execution): traced `Application.kt`, `ChatEngineFactory.kt`, `RepositoryContextAssembler.kt`, `TestChatContextAssembler.kt`, `PersonaContextResolutionStage.kt`, `PersonaContextRenderer.kt`, `PersonaContextResolver.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `LabeledScalarDiagnosticProvider.kt`, `ResolvedPersonaContextProvider.kt`, `PersonaMasterRepository.kt` (`resolvePath`), and `PersonaContextPocSeed.kt`'s field-type composition. No test was run; no build was invoked beyond what was already compiled from prior phases.

## Unresolved items

All items listed under "NOT ESTABLISHED" and "REQUIRED BEFORE PRODUCTION CHANGE" above.

## Whether a production change is currently validated by this phase

**No.** This phase performs an audit only. It does not validate, authorize, or recommend any production change, and it surfaces a previously-unstated fact material to any future such decision: production today uses neither B nor LA, so introducing LA into production would also mean activating selective retrieval itself for the first time outside the benchmark harness.

---

## Explicit confirmation

Zero API calls were made. Zero production, mapping, resolver, or renderer changes were made. No historical artifact (Phase 10 through Phase 32) was modified. No new experiment was designed, authorized, or executed. No path was recommended, ranked, or selected. LA was not declared production-ready. The Phase 23/25 boundary was not reinterpreted or loosened. Phase 34 is not started, per instruction.
