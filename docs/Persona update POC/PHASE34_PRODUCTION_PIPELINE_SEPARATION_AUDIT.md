# Phase 34 — Production Pipeline Separation Audit (A → B and B → LA)

**Zero API calls. Zero production, mapping, resolver, renderer, or registry changes.** This is a source-code audit only. No historical Phase 10–33 artifact was modified. No new experiment is designed or authorized. No transition (A→B, B→LA, or A→LA) is declared production-ready or recommended.

---

## STEP 1 — Reconstruct the Current Production Path

```text
Production request
    ↓
Application.kt            — builds chatEngineDependencies ONCE at startup (line 293)
    ↓
ChatEngineFactory.kt       — Dependencies.personaContextProvider defaults to null (line 118);
                              Application.kt never overrides this default (confirmed: no
                              "personaContextProvider =" assignment appears anywhere in
                              Application.kt's Dependencies construction)
    ↓
RepositoryContextAssembler.kt — assemble(): "if (personaContextProvider != null) {...} else { core.content }"
    ↓
persona/context provider selection — ALWAYS takes the `else` branch in production
    ↓
context construction       — personaContent = core.content (PersonaCoreVersion.content, a plain
                              text field seeded from BaselineConfiguration.SIMRAN_PERSONA_CORE)
    ↓
PersonaContextRenderer     — NEVER INVOKED in this path; core.content is a pre-formatted string,
                              not a JSON document requiring rendering
    ↓
engine prompt/request      — engineAndPersonaContent(engine.content, core.content) — a simple
                              string concatenation (RepositoryContextAssembler.kt, confirmed
                              unchanged since Phase 10A's own audit of this exact code)
    ↓
model generation           — OpenRouterLlmClient sends the assembled system block
```

### Confirmed from source (not from prior phase summaries)

1. **Where `personaContextProvider` is created**: it is never created in the production path. The only two places in the entire codebase that construct a non-null `PersonaContextProvider` are `TestChatService.buildEngineFor()` (test/benchmark harness) and the various diagnostic provider classes' own constructors, invoked only from that same test file.
2. **Where it is passed**: `ChatEngineFactory.Dependencies.personaContextProvider` → `ChatEngineFactory.build()` line 325 → `RepositoryContextAssembler`'s constructor parameter of the same name. Traced end to end; no intermediate reassignment exists anywhere in `ChatEngineFactory.kt`.
3. **Why production currently receives `null`**: `Application.kt`'s single `chatEngineDependencies` construction (lines 293-325) lists every other dependency explicitly but omits `personaContextProvider` entirely, so Kotlin applies the class default (`null`). This is confirmed by direct reading of the full parameter list at that call site — not inferred from absence alone, the surrounding code comment at line 288-292 explicitly documents this `Dependencies` bundle as "the production configuration."
4. **What code path executes when it is `null`**: `RepositoryContextAssembler.assemble()`'s ternary immediately falls to `core.content` — a single, direct branch, no further indirection.
5. **What context actually reaches the model today**: `engine.content` (the active Conversation Engine's universal-rules text) concatenated with `core.content` (`BaselineConfiguration.SIMRAN_PERSONA_CORE` — a short, generic, fact-sparse personality sketch, per Phase 10A's byte-for-byte reconstruction, unchanged since), plus separately-assembled profile/memory/sensitive-preference/continuity/skill/history blocks.
6. **Whether production's current path corresponds to historical variant A**: **Yes, exactly** — this is the same `null`-provider → `core.content` fallback Phase 10A identified as the actual mechanism behind every historical "variant A" execution. Production today is, and has always been, running what this program calls "A."
7. **Whether any production code already has dormant support for B/LA**: **Yes, structurally, but disconnected.** `ChatEngineFactory.Dependencies` and `RepositoryContextAssembler`'s constructor both already accept a `personaContextProvider` parameter — the *plumbing* exists and would accept `ResolvedPersonaContextProvider(..., variant="B")` or `AgeOnlyLabeledScalarDiagnosticProvider(...)` without any signature change. What is missing is (a) `Application.kt` ever actually constructing and passing one, and (b) `personaId` availability at the point `ChatEngineFactory.build()` is called for the one shared production engine — `PersonaContextResolutionStage.kt`'s own doc comment states this blocker explicitly: "Real implementation requires plumbing personaId through to this stage... blocked by personaId availability." This is a **known, previously-documented, unresolved blocker in production source code**, not a Phase 34 discovery, but confirmed still present and unaddressed by re-reading the file in this phase.

---

## STEP 2 — Three Pipeline States

### STATE A — Current production

`personaContextProvider = null` → `core.content` (`SIMRAN_PERSONA_CORE`, ~988 tokens, zero biographical facts) → concatenated with engine content. No field selection occurs. No `PersonaMasterDocuments` table is ever read.

### STATE B — Selective retrieval (hypothetical)

`personaContextProvider = ResolvedPersonaContextProvider(db, personaMasterRepo, fieldRegistryRepo, skillReqRepo, variant = "B")` → `PersonaContextResolver.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, "B")` → field selection against `SkillContextRequirement`/`PersonaFieldRegistry` → `PersonaContextRenderer.render(fieldValueMap)` → structured markdown text. **This requires (i) `Application.kt` to construct and pass a non-null provider, and (ii) `personaId` to be available at that construction point — the exact blocker `PersonaContextResolutionStage.kt` documents as unresolved.**

### STATE LA — Selective retrieval + age label (hypothetical)

Identical field selection to State B (`AgeOnlyLabeledScalarDiagnosticProvider` calls `resolve(..., "B")` verbatim), with one rendering difference: `identity.age`'s value is prefixed `"Age: "` instead of rendered bare.

```text
A
│
├── retrieval change (activate personaContextProvider, resolve personaId
│    availability, select fields via SkillContextRequirement/registry,
│    render via PersonaContextRenderer) ──→ B
│
└── retrieval + rendering change (both of the above, plus the age-label
     conditional in a labeling-aware renderer) ──→ LA

B
│
└── rendering-only change (identity.age: "27" → "Age: 27"; every other
     line byte-identical) ──→ LA
```

---

## STEP 3 — A → B Difference Audit

| Difference | Classification | Status |
|---|---|---|
| Persona content source (`core.content` vs. `PersonaMasterDocuments.contentJson`) | Retrieval | **Confirmed** — different table, different repository, different content entirely (Phase 10A) |
| Field selection (none vs. required/recommended/conditional tiering) | Field selection | **Confirmed** — A performs no field selection at all; B introduces `PersonaContextResolver.resolve()`'s full selection logic, previously never exercised in production |
| Context content (generic personality sketch vs. specific biographical facts) | Context content | **Confirmed** — A contains zero facts (no name-spelled-out details beyond "Simran," no age, city, family, friends); B would contain whichever facts the active skill's mapping selects |
| Formatting (plain paragraph text vs. `## Heading` structured markdown) | Formatting | **Confirmed** — A's `SIMRAN_PERSONA_CORE` is unstructured prose; B's renderer produces headed, classified sections |
| Restricted-field behavior | Restricted-field behavior | **Confirmed, materially**: **A cannot expose `intimate_anatomy`/`adult_intimacy` under any circumstance, for any skill, because the table that contains them (`PersonaMasterDocuments`) is never read in the A path at all.** B would be the first production path capable of exposing them, gated only by `canAccessField()`'s `allowedContexts` check — a real, previously-inert gate that would become load-bearing for the first time. |
| Skill interaction | Skill interaction | **Confirmed, materially**: today, `SkillContextEnricher` (the production skill mechanism) injects a skill's prompt text as an independent context block, entirely unconnected to persona content — the active skill has **zero** effect on what persona information is present, because persona content is always the same generic `core.content` regardless of skill. B would introduce skill-dependent persona content for the first time — a new coupling that does not exist today. |
| Memory | Memory | **Confirmed unaffected** — `MemoryService.selectForContext()` is invoked identically regardless of `personaContextProvider`'s value; it is a separate `assemble()` step reading from `memoryFactRepository`/`memoryService`, with no dependency on persona rendering. |
| User profile | User profile | **Confirmed unaffected** — `profileContent(profile)` is assembled from `userProfileRepository.findByUserId()` independently. |
| Conversation history | Conversation history | **Confirmed unaffected** — `messages`/`continuityBlock` are assembled from `messageRepository`/`conversation.continuitySummary`, independent of persona content. |
| Persona information (volume/specificity) | Persona information | **Confirmed** — B would supply real, specific facts where A supplies none; this is the entire subject of Phases 10D onward. |
| Token/context size | Token/context size | **Confirmed, directionally, not numerically for a live system**: Phase 10D's real, OpenRouter-measured data (SS: 1,558 mean prompt tokens; FS: 4,346) establishes that selective retrieval costs meaningfully more than A's near-zero-fact baseline in absolute terms, though still far less than a full-document alternative — this is real historical data from the benchmark harness, not a new measurement, and it has never been captured for actual production traffic (different system prompt, different skill distribution, different conversation histories). |
| Other — `personaId` availability at the engine-construction call site | Other | **Unknown / requires validation** — `PersonaContextResolutionStage.kt`'s own comment identifies this as unresolved; this audit did not find any change to that file's status since it was written. |
| Other — `SkillContextRequirement` mapping does not exist for production's actual skill set | Other | **Unknown / requires validation** — the POC's `SkillContextRequirement` seed (`PersonaContextPocSeed.kt`) hardcodes exactly 7 skill keys (`about`, `perspective_of`, `general_chat`, `friendship`, `romantic_conversation`, `romantic_intimacy`, `foreplay`). Production's actual skill keys are admin-configured via `SkillRepository` (a different, DB-backed entity with only `key`/`content`, no field-mapping concept) — whether production's real, currently-configured skill keys match, overlap with, or diverge entirely from the POC's 7 hardcoded keys **cannot be determined by static source reading alone**; it requires a runtime/database inspection this phase does not perform (not an API call to a model, but still outside this phase's zero-cost, source-only scope as instructed). |

---

## STEP 4 — B → LA Difference Audit

Verified directly against current source (`ResolvedPersonaContextProvider.kt`'s use of `PersonaContextResolver`, vs. `AgeOnlyLabeledScalarDiagnosticProvider.kt`):

| Item | Differs B→LA? | Evidence |
|---|---|---|
| Field IDs | **No** | LA calls `resolve(..., "B")` verbatim — the exact same call B's own resolution uses; `includedFieldIds` is byte-identical by construction. |
| Field values | **No** | `personaMasterRepo.resolvePath(masterDoc.contentJson, registryEntry.path)` is called identically in both; the underlying JSON value is never altered. |
| Field selection | **No** | See "Field IDs" — LA never re-implements or re-derives selection. |
| Restricted fields | **No** | `canAccessField()` is evaluated entirely inside `resolve()`, before either B's or LA's rendering step begins. |
| Ordering | **No** | Both use `groupBy { classification }.toSortedMap()` and an insertion-ordered map populated by iterating the same `includedFieldIds` list in the same order (confirmed in Phase 20's line-by-line audit, re-verified against current source in this phase). |
| Nested structures | **No** | `renderObject()`/`renderArray()` in `AgeOnlyLabeledScalarDiagnosticProvider.kt` are unmodified copies of `PersonaContextRenderer`'s own methods. |
| Scalar values | **Partially — exactly one field's *representation*, never its value** | `identity.age`'s underlying numeric value (27) is unchanged; only its rendered string form changes (`"27"` → `"Age: 27"`). **This is confirmed, not assumed, to be the ONLY scalar field whose rendering changes**, because `AgeOnlyLabeledScalarDiagnosticProvider.kt`'s conditional is `if (fieldId == "identity.age")` — a single, explicit, hardcoded string comparison; every other top-level scalar falls through to the identical bare-value branch B uses. |
| Memory | **No** | Neither B nor LA touches memory assembly; this is a `RepositoryContextAssembler`-level concern, unaffected by which persona-content provider is plugged in. |
| Profile | **No** | Same reasoning. |
| History | **No** | Same reasoning. |
| Skill selection | **No** | The skill key is an *input* to `resolve()` (via `pinnedSkillKey` in tests; would be the intent-discovery-resolved skill in a hypothetical production wiring) — rendering downstream of selection cannot feed back into which skill was chosen. |
| Persona version | **No** | Both read from the same `personaCoreVersionId` passed in; no version-selection logic exists in either provider. |
| Engine version | **No** | Persona-content providers have no interaction with `engineRepository.getActiveEngine()` — entirely separate `assemble()` steps. |

**On whether age is the only production rendering difference merely because LA's diagnostic isolated it**: this audit does not assume that. It confirms it directly from `AgeOnlyLabeledScalarDiagnosticProvider.kt`'s single hardcoded conditional — there is no other branch, flag, or condition in that file capable of affecting any other field's rendering. **If a *production* implementation of age-labeling were built independently (not by reusing this exact diagnostic file), it could in principle be written differently and affect other fields — this audit confirms only what the existing, already-tested diagnostic file does, not what any future production implementation would necessarily do.**

---

## STEP 5 — Production Skill Audit

**A structural finding applies to every row below, stated once rather than repeated per skill**: in production State A, no skill has any effect on persona content — persona content is always `core.content`, identically, regardless of which skill (if any) is selected. `SkillContextEnricher` injects a skill's own prompt text as an independent block; it has no code path into persona-content assembly. The POC's skill-key strings (`about`, `general_chat`, `friendship`, `romantic_conversation`, `romantic_intimacy`, `foreplay`, `perspective_of`) are hardcoded in `PersonaContextPocSeed.kt` and used only within `TestChatService`; production's actual, admin-configured skill keys (via `SkillRepository`) were not verified to match these strings in this audit (see Step 3's "other" row) — this is marked **unknown** below wherever it depends on that correspondence.

| Skill | Uses persona context today? | Current production path | B impact | LA impact | Validation status |
|---|---|---|---|---|---|
| General chat (production skill key, if configured) | Yes, but only the generic `core.content`, unconditionally | State A, identical to every other skill | Would introduce skill-dependent field selection for the first time | Would additionally label `identity.age` if selected | **Unknown** — depends on whether production's actual skill key matches `"general_chat"` and whether `SkillContextRequirement`'s hardcoded mapping is ever wired to it |
| "About"-style self-introduction skill | Yes, but only the generic `core.content` | State A | Same as above | Same as above | **Unknown**, same caveat |
| Romantic/intimacy-related skills | Yes, but only the generic `core.content` — **critically, `core.content` (`SIMRAN_PERSONA_CORE`) contains no intimate/restricted content of any kind**, so today these skills receive no more or less intimate persona detail than any other skill | State A | **This is the single most consequential B-impact row**: activating B would be the first time `canAccessField()`'s `allowedContexts` gate (currently `["romantic_intimacy", "foreplay"]` for `intimate_anatomy`/`adult_intimacy`) becomes load-bearing in production, since it is the only mechanism that would ever decide whether restricted content reaches a real model call | Same B-dependent exposure; LA itself does not touch restricted fields (Step 4) | **Unknown / requires validation** — whether production's real skill keys for these conversation types match the POC's hardcoded strings, and whether the registry's `allowedContexts` list is complete and correctly scoped, has never been validated against real production skill configuration |
| Foreplay (production skill key, if configured) | Same as above | State A | Same restricted-field consideration as above | Same as above | **Unknown / requires validation**, same reasoning |
| Any other skill that consumes persona context | Yes, only generically, per State A | State A | Field-selection behavior would depend entirely on whether a `SkillContextRequirement` mapping exists for that skill key — for any skill key not among the POC's 7 hardcoded ones, `PersonaContextResolver.resolve()` would fall through to `skillContextReqRepo.findBySkillKey(skillKey) ?: return resolveVariantA(personaCoreVersionId)` (confirmed at `PersonaContextResolver.kt` line 44-45) — i.e., **an unmapped skill key would silently behave like variant A even under State B**, a fallback this audit confirms exists in source but which has never been exercised against real skill keys | Same fallback applies | **Unknown** — the complete, current set of production skill keys was not enumerated in this audit (requires a database query, not a static source read) |

**No behavior is inferred beyond what source code establishes.** Every "unknown" above is unknown specifically because it depends on runtime/database state (the actual configured `Skill` rows) rather than on anything resolvable from the Kotlin source tree alone.

---

## STEP 6 — Restricted Data Audit

Full chain, traced from source:

```text
field registry (PersonaFieldRegistryRepository, 27 entries incl. intimate_anatomy/adult_intimacy)
    ↓
resolver (PersonaContextResolver.resolve() — canAccessField() gate, checked per field, per skill)
    ↓
provider (ResolvedPersonaContextProvider / AgeOnlyLabeledScalarDiagnosticProvider — both call resolve()
          and only ever render what resolve() already returned in includedFieldIds)
    ↓
renderer (PersonaContextRenderer / the diagnostic's copy — renders whatever it is given, applies
          no independent access-control logic of its own)
    ↓
production request (RepositoryContextAssembler.assemble() concatenates whatever the provider returned)
```

**Does activating B change the set of fields that can reach production?**

**Yes, unambiguously.** Today (State A), the set of fields that can reach production is **empty** — no field from `PersonaMasterDocuments` reaches a real request, because that table is never read. Activating B would, for the first time, make the entire 27-field registry potentially reachable, subject only to `canAccessField()`'s gate: non-restricted fields (`sensitivity != "restricted" && sensitivity != "private"`) are always accessible if a skill's mapping selects them; `intimate_anatomy`/`adult_intimacy` require the skill key to appear in their `allowedContexts` list (`["romantic_intimacy", "foreplay"]`).

**Does LA change that set further?**

**No.** Confirmed in Step 4 — LA reuses B's exact `includedFieldIds` with zero modification; it cannot add or remove a field from the reachable set.

**Specific attention items:**

- **Restricted fields**: gated by `canAccessField()`, a real and correctly-scoped mechanism *in the benchmark harness* (0 leakage confirmed across hundreds of calls in every phase that checked it) — but **never exercised against real production skill keys or real production traffic**, per Step 5's findings.
- **Conditionally selectable fields**: gated by `PersonaFieldRelevanceScorer`'s word-overlap scoring against the *current user message* — this scoring logic has not been modified by any phase and is identical whether B or LA is used (LA never re-scores).
- **Unregistered fields**: `_full_document_supplement` (containing `visual_identity`, `public_expression`, `timeline_and_narrative_state`, and the four `_extended` personality/worldview/aspirations/preferences/communication blocks) is **structurally unreachable by B or LA under any configuration** — confirmed by the class-level design intent documented in `PersonaContextPocSeed.kt` itself and re-verified: no `SkillContextRequirement` or `PersonaFieldRegistryEntry` references any `_full_document_supplement` key.
- **Memory/profile/history**: entirely outside this chain — confirmed unaffected by either B or LA, per Steps 3-4.
- **Fields available only to particular skills**: this is `canAccessField()`'s `allowedContexts` mechanism itself — already covered above.

**No changes were made to the registry, resolver, provider, or renderer to produce this audit.**

---

## STEP 7 — Token / Latency / Cost Boundary

| Item | A→B | B→LA |
|---|---|---|
| Expected context-size direction (known from source) | **Increase** — A's `core.content` is a fixed ~988-token block; B would replace it with a variable, skill-dependent selection of real facts, structurally larger for any skill with a non-trivial field mapping (Phase 10D's real measurement: SS mean prompt tokens 1,558 vs. an equivalent-position A baseline) | **Negligible increase** — one field's rendered line grows by a fixed 5 characters (`"27"` → `"Age: 27"`); no other line is affected (Step 4) |
| Additional rendering work | **New work introduced** — `PersonaContextRenderer.render()` (grouping, sorting, heading generation) is not exercised at all in State A | **Negligible additional work** — one extra string-equality check and one extra string concatenation per rendered payload, on top of B's existing rendering |
| Additional resolver work | **New work introduced** — `PersonaContextResolver.resolve()`'s full required/recommended/conditional tiering and `PersonaFieldRelevanceScorer` invocation, not exercised in State A | **None** — LA reuses B's resolution verbatim, no additional resolver invocation |
| Additional model calls | **None, for either transition** | **None** — confirmed no phase in this program introduces a second generation call or retry tied to labeling |
| Caching implications | **Unknown / requires runtime measurement** — no caching layer exists for either `core.content` or resolver/renderer output in the current source; whether either State A or State B benefits from prompt caching at the LLM-provider level was not evaluated in this program | **Unknown / requires runtime measurement** — same caveat, and LA's marginal 5-character change is unlikely to interact with caching differently than B, but this is not measured |
| Possible latency changes | **Unknown / requires runtime measurement** — network-bound generation latency is expected to dominate any CPU-bound resolution/rendering cost, per the code's structure (a few in-memory operations vs. a multi-second network round trip), but this is a reasoned expectation, not a measurement | **Unknown / requires runtime measurement**, same caveat, at a smaller expected magnitude given the smaller code delta |

**Known from source**: the *direction* of context-size change (A→B increases; B→LA is a marginal, single-field increase) and the *absence* of any additional model call in either transition.

**Requires runtime measurement**: actual latency, actual token counts against a real tokenizer for any hypothetical production request shape, and any caching interaction — none of these has been measured for a production-wired configuration anywhere in this program (Phase 33's Step 3 finding, reconfirmed here).

---

## STEP 8 — Regression Requirements

### A → B

| Area | Test required | Exists today? |
|---|---|---|
| Retrieval correctness | Confirm `resolve()` selects the intended fields for every production skill key, once those keys are known | **No** — only the POC's 7 hardcoded skill keys have ever been tested, never real production skill keys |
| Persona factuality | Broad regression corpus (Phase 10D/10E-style) run against B in a production-shaped context (real engine content, real skill prompts, real memory/history) | **Partially** — the Phase 10D/10E corpus exists and was run, but only inside the benchmark harness, never against production's actual engine/skill content |
| Skill-specific retrieval | Per-skill validation that the mapping (if any) matches the skill's actual intent | **No** — production skill keys were not enumerated or matched to the POC's mapping in this audit |
| Restricted fields | Confirm `allowedContexts` correctly scopes restricted fields under production's real skill keys | **No** — only validated against the POC's hardcoded `romantic_intimacy`/`foreplay` strings, never confirmed to match production's real configuration |
| Memory | Confirm memory assembly is unaffected by activating a non-null provider | **Not explicitly tested, though source analysis (Step 3) shows no code-path dependency** — a regression test would still be prudent before a production change |
| Profile | Same as memory | Same as memory |
| Conversation history | Same as memory | Same as memory |
| Fallback behavior | Confirm `resolveVariantA()`'s fallback (when `skillContextReqRepo.findBySkillKey()` returns null) behaves safely for any unmapped real skill key | **Not tested against a real unmapped key** — only tested implicitly within the POC's own fixed skill set |
| Empty/failed retrieval | Confirm behavior when `personaMasterRepo.findByPersonaCoreVersionId()` returns null (no master document for a given persona/version) | **Not explicitly tested** — the POC always seeds exactly one master document; production would need to handle a persona with no master document at all (the common case today, since none is currently authored for real personas beyond the POC's Simran) |
| Token/context limits | Confirm the existing `tokenBudget`/`tokenEstimator` machinery in `RepositoryContextAssembler` correctly accounts for B's larger persona block | **Not tested** — Phase 10D measured real tokens but did not test budget-enforcement interaction |
| Production request equivalence | Provider output → actual production request payload, via the same `interceptGeneration`-style mechanism already proven, but against real `Application.kt`/`ChatEngineFactory` wiring rather than the test harness | **Not performed** — every such check to date used `TestChatService`, never the real production construction path |

### B → LA

| Area | Test required | Exists today? |
|---|---|---|
| Scalar rendering | Exact-match unit tests for every top-level scalar type in the registry, labeled and unlabeled | **No dedicated unit test file** — only ad hoc assertions inside benchmark test methods |
| Structured rendering | Confirm headings/sections unaffected | **Implicitly confirmed repeatedly** (Phase 20/24/26/27/30 diffs), but not as a standalone regression test |
| Nested objects | Confirm `renderObject()` output identical B vs. LA | **Confirmed in every phase's line-diff check** (e.g., Phase 20 §7, Phase 24) |
| Arrays | Confirm `renderArray()` output identical | **Confirmed identically to nested objects** |
| Nulls | Confirm missing/null field values are skipped identically | **Confirmed by source reading in this phase (Step 4/Phase 33 Step 2.1) — not separately unit-tested** |
| Numbers | Confirm only `identity.age` (the sole currently-registered numeric top-level scalar) is affected | **Confirmed in this phase, Step 4** |
| Booleans | N/A — no registered field is boolean-typed | **Untested, because no such field exists yet** |
| Strings | Confirm every other string scalar renders bare, identically | **Confirmed repeatedly (Phase 20/24/26/27/30)** |
| Restricted fields | Confirm labeling cannot alter which restricted fields are selected | **Confirmed by source reading (Step 4) — labeling is strictly downstream of `canAccessField()`** |
| Exact request equivalence | Provider output → captured request, for LA specifically | **Confirmed repeatedly via `interceptGeneration`, but only within `TestChatService`, never real production wiring** |
| Regression of existing B behavior | Confirm introducing LA-style logic does not alter B's own output when B is the active provider | **Trivially true today, since LA and B are separate files and B is never modified** — would need re-confirming if a production implementation merges the label logic into `PersonaContextRenderer.kt` itself rather than keeping a separate diagnostic file |

---

## STEP 9 — Is Another Large Behavioral Experiment Required at This Stage?

**No, not for the questions this audit was scoped to answer.** The A→B and B→LA differences are fully resolvable by source inspection and the deterministic checks already performed in Phases 10C/16/20/24/26/27/30 (zero-cost field-selection/payload-diff/determinism checks) — none of that requires a new behavioral (real-model-response) experiment. Phases 26 and 30 already established, behaviorally, that B and LA/L produce statistically different outputs on two scenario types; Phase 34 does not need to re-establish that.

**One open experimental question was surfaced by this audit's source inspection and is recorded, per instruction, without being executed:**

> **Open experimental question — not executed in Phase 34**: whether `PersonaContextResolver.resolve()`'s unmapped-skill-key fallback (`skillContextReqRepo.findBySkillKey(skillKey) ?: return resolveVariantA(personaCoreVersionId)`) behaves correctly and safely when exercised against real, production-configured skill keys that may not match any of the POC's 7 hardcoded `SkillContextRequirement` entries — this cannot be answered by source reading alone, because it depends on runtime/database state (the actual configured `Skill` rows) not visible to static analysis, and is a deterministic-behavior question, not a model-response question, so if pursued it would be a **validation task (Step 8's "fallback behavior" row), not a new behavioral experiment**.

**Phase 35 is not automatically created to pursue this.**

---

## STEP 10 — Decision Separation

| Question | Evidence currently available | What remains unknown |
|---|---|---|
| Does selective retrieval differ from current production A? | **Yes, extensively** — Step 3's full difference table, confirmed by direct source reading in this phase | Whether production's real skill keys map to any `SkillContextRequirement` entry at all (Step 3/5) |
| Is B's retrieval behavior validated for production? | Validated only inside the benchmark harness (`TestChatService`), across the POC's fixed 7 skill keys and 30-scenario corpus (Phase 10D/10E) | Not validated against real production skill keys, real engine content, real memory/history state, or the `personaId`-availability blocker `PersonaContextResolutionStage.kt` documents as unresolved |
| Does LA differ from B only in rendering? | **Yes, confirmed exhaustively in Step 4** — field IDs, values, selection, restricted fields, ordering, nested structures unaffected; only `identity.age`'s rendered representation changes | Whether a *production* implementation of labeling (as opposed to the existing diagnostic file) would necessarily preserve this exact isolation — this audit confirms the diagnostic's behavior, not any future implementation's |
| Is LA behavior experimentally supported? | Yes, on two scenario types, both statistically significant (Phase 26 p≈0.00036, Phase 30 p≈0.0336) | Whether it holds on other scenario/skill/persona combinations; causal mechanism; LA-vs-L (Phase 32/33, unchanged) |
| Is A → B production-safe? | Not established — Step 3/5/6/8 identify multiple **unknown/requires-validation** items (personaId availability, real skill-key mapping, restricted-field scoping against real skills, no master document for other personas, no production-wired regression test) | All items marked "Unknown / requires validation" throughout Steps 3, 5, 6, 8 |
| Is B → LA production-safe? | The rendering-only delta itself is well-isolated and understood (Step 4) | Whether it is safe **presupposes A→B is already safe**, since LA cannot exist in production without B existing there first; independently, no unit-test suite or real-tokenizer cost measurement exists (Step 7-8) |
| Is A → LA production-safe? | **Not established, and not the same question as the already-tested B → LA comparison.** See below. | Everything under both A→B and B→LA simultaneously — A→LA is not a single, smaller delta; it is the composition of both transitions, each with their own distinct, unresolved validation items |

**Explicit distinction, as required**: **A → LA is not equivalent to the already-tested B → LA experiment.** Phases 26/27/30 tested B → LA — a controlled comparison where selective retrieval was already active (within the benchmark harness) and only the labeling representation varied. **No experiment in this program has ever tested A → LA directly** — i.e., comparing today's actual production behavior (State A, generic `core.content`) against a hypothetical fully-labeled selective-retrieval state. Such a comparison would conflate two independent variables (retrieval activation and label rendering) exactly the way Phase 10A found the historical "A vs. D" comparison had done — repeating that same methodological error here is explicitly avoided by keeping A→B and B→LA analytically separate throughout this document.

---

## STEP 11 — Final Gate

### ESTABLISHED

- Production, today, runs State A exclusively — `personaContextProvider` is always `null`, confirmed by direct reading of `Application.kt`'s full `Dependencies` construction.
- State A is the same mechanism Phase 10A identified as historical variant A — confirmed identical code path.
- `personaId` availability at the shared production engine's construction point is a known, previously-documented, still-unresolved blocker to activating B in production (`PersonaContextResolutionStage.kt`'s own comment, re-confirmed unchanged in this phase).
- B→LA's isolation (only `identity.age`'s rendered representation changes; nothing else) is confirmed exhaustively from current source in this phase, independent of and in addition to every prior phase's own diff checks.
- Production's skill mechanism (`SkillContextEnricher`/`SkillRepository`) is architecturally independent of and disconnected from the POC's skill-to-field-mapping mechanism (`SkillContextRequirement`) — confirmed by tracing both code paths; they share no code and no data today.
- Restricted fields (`intimate_anatomy`, `adult_intimacy`) cannot reach production under State A, for any skill, because the table containing them is never read — confirmed structurally, not merely by absence of a reported leak.
- The `_full_document_supplement` content is structurally unreachable by B or LA under any configuration, confirmed unchanged.
- No code, mapping, renderer, provider, or registry file was modified to produce any finding in this document.

### NOT ESTABLISHED

- Whether production's real, currently-configured skill keys correspond to any of the POC's 7 hardcoded `SkillContextRequirement` entries.
- Whether activating B in production is safe given the unresolved `personaId`-availability blocker.
- Real, tokenizer-measured token/cost and latency impact of either transition in a production-shaped request.
- Whether B's field-mapping and restricted-field scoping, validated only against the POC's fixed skill set and 30-scenario corpus, would hold against production's actual skill/scenario distribution.
- Whether an A→LA comparison would show the same, a different, or no statistically detectable effect relative to the already-tested B→LA comparison — this has never been tested and is explicitly distinguished from the completed research.
- The causal mechanism, LA-vs-L sufficiency, magnitude stability, and 27−24 alternative — all unchanged from Phase 32/33.

### REQUIRED BEFORE A → B

- Resolution of the `personaId`-availability blocker documented in `PersonaContextResolutionStage.kt`.
- Enumeration of production's actual, currently-configured skill keys and an explicit mapping decision (reuse, adapt, or replace the POC's `SkillContextRequirement` set) for each.
- A production-wired provider-to-request equivalence test (the `interceptGeneration` pattern, applied against real `Application.kt`/`ChatEngineFactory` construction, not `TestChatService`).
- A broad regression pass (Phase 10D/10E-style corpus or successor) run against B in a production-shaped context (real engine content, real skill prompts).
- Restricted-field validation against production's real skill keys specifically, not only the POC's hardcoded `romantic_intimacy`/`foreplay` strings.
- Fallback-behavior validation for personas without an authored master document (the default state for any persona beyond the POC's Simran) and for any unmapped real skill key.
- Token-budget-interaction testing between B's larger persona block and `RepositoryContextAssembler`'s existing budget-enforcement logic.

### REQUIRED BEFORE B → LA

- A deterministic unit-test suite for the labeling logic itself (not merely ad hoc benchmark-test assertions), independent of whichever production A→B validation is completed first.
- Confirmation that a production implementation of labeling (however it is eventually built — as a renderer modification, a wrapper, or a separate provider) preserves the same isolation this audit confirmed for the existing diagnostic file, since that isolation is a property of the diagnostic's specific code, not a guarantee about any future implementation.
- Real, tokenizer-measured cost comparison, however small the expected delta.
- All of B's own "required before A→B" items, since B→LA cannot be evaluated in production isolation from B's own unresolved validation needs.

## HUMAN / ENGINEERING OWNER DECISIONS

- Whether to invest engineering effort in resolving the `personaId`-availability blocker at all, independent of any labeling question — this is a prerequisite architectural task, not something the labeling research can answer.
- Whether production's skill system should be unified with the POC's `SkillContextRequirement` concept, kept permanently separate, or replaced by a third design — a system-architecture decision outside this investigation's scope.
- Whether to pursue Path A, B, or C from `PHASE32_ENGINEERING_DECISION_PACKAGE.md` (unchanged, restated as still open).
- Whether the newly-precise A→B / B→LA separation in this document changes the relative priority or sequencing of any engineering work already under consideration.
- Resourcing and ownership for each item listed under "Required before A→B" and "Required before B→LA," none of which this Question-B research track is positioned to perform as a continuation of its own existing scope.

**No decision above is made or chosen by this document.**

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE34_PRODUCTION_PIPELINE_SEPARATION_AUDIT.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Source paths inspected

`src/main/kotlin/com/pinkdreams/Application.kt`, `src/main/kotlin/com/pinkdreams/chat/ChatEngineFactory.kt`, `src/main/kotlin/com/pinkdreams/chat/context/RepositoryContextAssembler.kt`, `src/main/kotlin/com/pinkdreams/chat/PersonaContextResolutionStage.kt`, `src/main/kotlin/com/pinkdreams/chat/personacontext/PersonaContextResolver.kt`, `src/main/kotlin/com/pinkdreams/chat/personacontext/PersonaContextRenderer.kt`, `src/main/kotlin/com/pinkdreams/chat/context/ResolvedPersonaContextProvider.kt`, `src/main/kotlin/com/pinkdreams/chat/context/AgeOnlyLabeledScalarDiagnosticProvider.kt`, `src/main/kotlin/com/pinkdreams/chat/skill/SkillContextEnricher.kt`, `src/main/kotlin/com/pinkdreams/persistence/repositories/SkillRepository.kt`, `src/main/kotlin/com/pinkdreams/baseline/PersonaContextPocSeed.kt`, `src/main/kotlin/com/pinkdreams/persistence/repositories/PersonaMasterRepository.kt`, `src/main/kotlin/com/pinkdreams/testchat/TestChatContextAssembler.kt`, `src/main/kotlin/com/pinkdreams/testchat/TestChatService.kt`.

## Deterministic checks performed

Source-code tracing and cross-reference only (no code execution, no test run, no build invoked). Every claim above is anchored to a specific file and, where quoted, an exact line or code snippet re-read in this phase.

## A → B differences found

11 distinct differences classified (Step 3), spanning retrieval, field selection, context content, formatting, restricted-field behavior, skill interaction, token/context size, and two "other" items (personaId availability; skill-key mapping correspondence) — full table in Step 3.

## B → LA differences found

Exactly one: `identity.age`'s rendered representation (`"27"` → `"Age: 27"`). All 13 other checked items (field IDs, values, selection, restricted fields, ordering, nested structures, memory, profile, history, skill selection, persona version, engine version) confirmed unchanged — full table in Step 4.

## Unresolved production risks

The `personaId`-availability blocker; unknown correspondence between production's real skill keys and the POC's hardcoded `SkillContextRequirement` set; restricted-field scoping never validated against real skill keys; no production-wired provider-to-request equivalence test; no real-tokenizer cost/latency measurement; no broad regression pass in a production-shaped context; no A→LA experiment ever run (distinct from the completed B→LA experiments).

## Whether A → B is production-validated

**No.**

## Whether B → LA is production-validated

**No** — and this question presupposes A→B validation, which itself has not occurred.

## Whether A → LA is production-validated

**No — and this is explicitly not the same question as whether B→LA (the already-tested comparison) is validated.** No experiment in this program has ever directly compared State A against a hypothetical fully-labeled selective-retrieval state; doing so would conflate two independent variables in the same way the historical "A vs. D" comparison did before Phase 10A's correction.

---

## Explicit confirmation

Zero API calls were made. Zero production, mapping, resolver, renderer, provider, or registry changes were made. No historical artifact (Phase 10 through Phase 33) was modified. No new behavioral experiment was designed or executed. The Phase 23/25 boundary was not reinterpreted. B was not declared production-ready. LA was not declared production-ready. No transition (A→B, B→LA, A→LA) was recommended. Phase 35 is not started, per instruction.
