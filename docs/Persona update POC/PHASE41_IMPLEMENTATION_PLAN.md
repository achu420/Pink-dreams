# Phase 41 — Safety Contract Implementation Plan & Decision Lock

**Zero API calls. Zero database mutations. Zero production code, persona-data, mapping, renderer, or provider modifications.** This document is an implementation plan only — it does not implement Change A, B, or C, does not select between them, and does not modify any Phase 1–40 historical artifact. No behavioral experiment is run. LA is not declared production-ready. The Phase 23/25 evidence boundary is not reinterpreted.

---

## 1. Executive Summary

Phase 40 established that the remaining safety work is a *decision* problem, not an *identification* problem: the architecture already has enough signal (a `variant` mismatch plus an empty `includedFieldIds`) to deterministically detect every traced unsafe fallback state, but detection (Change C) does not by itself prevent unsafe content from reaching the model — that requires either strict downstream enforcement (Change A) or source-level filtering (Change B), or both. This phase converts that finding into exact patch plans, a full deterministic test matrix, and a locked implementation boundary, without touching any code. **Implementation cannot begin immediately** — five specific human decisions gate it, enumerated in §14, none of which this document resolves.

## 2. Current Production Data Path

Re-traced from source in this phase (not merely inherited from Phase 33–40):

```text
Application.kt
    — chatEngineDependencies built once at startup; personaContextProvider omitted,
      defaulting to null (ChatEngineFactory.Dependencies, line 118)
    ↓
ChatEngineFactory.build(deps)
    — passes deps.personaContextProvider straight into RepositoryContextAssembler's
      constructor (line 325) — no intermediate transformation
    ↓
RepositoryContextAssembler.assemble(request: ChatRequest)
    — core = personaRepository.getActiveCoreVersion(request.personaId)  [ALWAYS fetched]
    — personaContent = if (personaContextProvider != null) {
          personaContextProvider.getPersonaContext(request.personaId, core.id,
              pinnedSkillKey ?: "general_chat", request.content)
      } else { core.content }
      — THIS LINE is the last point in the assembler where a decision could be made;
        today it can only branch on provider nullity, nothing finer
    ↓
PersonaContextProvider.getPersonaContext(...): String   [interface boundary — see §3]
    ↓
ResolvedPersonaContextProvider.getPersonaContext(...)
    — calls resolver.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, variant)
    — returns projection.renderedText ONLY
    ↓
PersonaContextResolver.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, requestedVariant): PersonaContextProjection
    — PersonaContextProjection IS CREATED HERE — either by the main return statement
      (non-A branch, variant = requestedVariant echoed back) or by resolveVariantA()
      (variant hardcoded to "A", includedFieldIds hardcoded to emptyList())
    ↓
PersonaContextRenderer.render(fieldValueMap): String
    — pure function, no knowledge of fallback/usability state
    ↓
engineAndPersonaContent(engine.content, personaContent) — final concatenation
    ↓
model request
```

**Precise answers to the six required questions**:

- **Where is `PersonaContextProjection` created?** Exclusively inside `PersonaContextResolver.resolve()` and its private `resolveVariantA()` helper — no other file constructs this type.
- **Where is `requestedVariant` known?** Only as `resolve()`'s own `variant: String` parameter, at the moment of the call — confirmed **not retained anywhere** past that call frame; `resolveVariantA()` does not receive it as a parameter (it takes only `personaCoreVersionId`), so even *inside* the fallback function, the original request is already gone.
- **Where is `variant` (returned) currently produced?** `PersonaContextProjection.variant` — set to the literal `"A"` inside `resolveVariantA()` unconditionally, or echoed from the caller's own `variant` argument in the non-A branch.
- **Where is `includedFieldIds` produced?** Same two sites — the non-A branch's `selectedFieldIds` (built incrementally during required/recommended/conditional selection), or `resolveVariantA()`'s hardcoded `emptyList()`.
- **Where does `renderedText` become consumable?** The moment `ResolvedPersonaContextProvider.getPersonaContext()` returns it as a bare `String` — this is simultaneously the point of consumability and the point every other signal is discarded (§3).
- **What is the last possible point at which `NOT_USABLE` content can be rejected before reaching the model?** **`RepositoryContextAssembler.assemble()`'s `personaContent` computation** — this is the last point in the traced chain with access to `core.content` (the safe substitute) before the value flows into `engineAndPersonaContent()` and onward to the model request. Any rejection decision must happen at or before this line; nothing downstream of it (the concatenation, the request construction) has access to `core.content` to substitute.

## 3. Current Projection Contract

`PersonaContextProjection` (defined in `PersonaContextResolver.kt`) already carries `renderedText: String`, `includedFieldIds: List<String>`, `excludedFieldIds: List<String>`, `personaTokenEstimate: Int`, and `variant: String`. **All of this is discarded** the instant `ResolvedPersonaContextProvider.getPersonaContext(...): String` returns only `projection.renderedText` — confirmed by direct re-reading of that one-line method body. The `PersonaContextProvider` interface itself (`chat/context/PersonaContextProvider.kt`, 16 lines) declares `getPersonaContext(...): String` — a bare, non-nullable string, with no room to carry any of the other four fields across the interface boundary. **This is the single chokepoint** identified consistently across Phases 34, 39, and 40 — re-confirmed once more in this phase by direct file reads, not by citation alone.

## 4. USABLE / NOT_USABLE Predicate

Deterministic predicate, using only the four already-established signals (`requestedVariant`, returned `variant`, `includedFieldIds`, `renderedText`):

```text
USABLE      := (requestedVariant == variant) AND includedFieldIds.isNotEmpty() AND renderedText.isNotBlank()
NOT_USABLE  := NOT USABLE
```

Applied to the ten specified cases:

| # | Case | `requestedVariant` | Returned `variant` | `includedFieldIds` | `renderedText` | Classification |
|---|---|---|---|---|---|---|
| 1 | Requested B → returned B + selected fields | `"B"` | `"B"` | non-empty | non-blank | **USABLE** |
| 2 | Requested C → returned C + selected fields | `"C"` | `"C"` | non-empty | non-blank | **USABLE** |
| 3 | Requested B/C → returned A | `"B"`/`"C"` | `"A"` | empty (`resolveVariantA()` always hardcodes this) | `""` or raw document | **NOT_USABLE** (mismatch alone is already sufficient) |
| 4 | Requested B/C → returned B/C + zero selected fields | `"B"`/`"C"` | `"B"`/`"C"` (matches — normal path ran to completion) | empty | `""` (renderer's own empty-map guard) | **NOT_USABLE** (via `includedFieldIds.isEmpty()`, since `variant` alone does not catch this case) |
| 5 | Requested B/C → returned B/C + non-empty selected fields | `"B"`/`"C"` | matches | non-empty | non-blank | **USABLE** |
| 6 | Empty rendered text | any | — | — | `""` | **NOT_USABLE** (redundant with cases 3/4, included for completeness) |
| 7 | Non-empty raw-document text with zero selected fields | `"B"`/`"C"` | `"A"` (only reachable via `resolveVariantA()`, which always empties `includedFieldIds`) | empty | **non-blank** (the dangerous case) | **NOT_USABLE** — this is the critical case: `includedFieldIds.isEmpty()` correctly flags it as unsafe *despite* `renderedText` being non-blank, which is exactly why `renderedText.isNotBlank()` alone is never sufficient and `includedFieldIds` must be checked independently |
| 8 | Missing persona data (no master document) | `"B"`/`"C"` | `"A"` | empty | `""` | **NOT_USABLE** |
| 9 | Missing skill mapping | `"B"`/`"C"` | `"A"` | empty | `""` or raw document (depends on whether a master document exists) | **NOT_USABLE** in both sub-cases |
| 10 | Unknown skill key | `"B"`/`"C"` | `"A"` | empty | same as case 9 | **NOT_USABLE** — code-identical to case 9, per Phase 38 §4 states 11-12, re-confirmed |

**What is mechanically classifiable vs. what remains `PARTIAL`/unknown**: every one of the ten cases above is mechanically classifiable as `USABLE` or `NOT_USABLE` using only the four signals. **`PARTIAL` is not one of the ten cases tested here** — it would apply to a state like "requested B, returned B, `includedFieldIds` has 2 of an expected 10 entries," which the predicate above classifies as `USABLE` (case 5's shape) because the predicate has no concept of "expected size." This is unchanged from Phase 40 §5 and is not re-litigated as resolved here — see §11.

## 5. Change A Analysis (downstream strict enforcement)

- **Exact mechanism**: `RepositoryContextAssembler.assemble()` evaluates the §4 predicate on the provider's result and substitutes `core.content` whenever `NOT_USABLE`, **unconditionally discarding `renderedText` in that case** — including in case 7 (the raw-document case), where `renderedText` is non-blank but still discarded because `includedFieldIds` is empty.
- **Prevents unsafe fallback from reaching the model?** **Yes**, for every one of the ten cases, provided Change A is implemented "strictly" (always substitutes on `NOT_USABLE`, never inspects or partially uses `renderedText` in that branch) — confirmed by the predicate's own construction in §4, which classifies case 7 correctly.
- **Protects restricted fields?** **Yes, independently of Change B** — since case 7's raw document (which is the only vector by which a restricted field could reach the fallback output) is discarded wholesale under a strict Change A, without needing any field-level filtering logic at all. This is the Phase 40 finding, re-confirmed here against the explicit predicate.

## 6. Change B Analysis (restricted/raw fallback protection)

- **Exact mechanism**: filter `resolveVariantA()`'s output (or a new sibling function) through `canAccessField()` before it is ever placed into `renderedText`, so that even if something downstream *did* consume a `NOT_USABLE` result, no restricted field could be present in it.
- **Prevents unsafe fallback from reaching the model?** Only partially, and only for the restricted-field dimension specifically — Change B does not address the "empty/blank content should become `core.content`" problem at all (that is Change A's job); a filtered-but-still-raw document would still not equal `core.content`, and would still be a form of unintended content reaching the model unless Change A also exists.
- **Protects restricted fields?** **Yes, at the source**, independent of whether the caller correctly checks the predicate — this is Change B's specific value as defense-in-depth, per Phase 40 §6.

## 7. Change C Analysis (`PersonaContextProjection` safety contract)

- **Exact mechanism**: extend `PersonaContextProvider.getPersonaContext(...)`'s return type from bare `String` to a structure carrying `renderedText`, `includedFieldIds`, returned `variant`, and `requestedVariant` (the one genuinely new piece of state, per Phase 39 §4) — enabling `RepositoryContextAssembler` to evaluate §4's predicate at all.
- **Relationship to A and B**: Change C is a **prerequisite for Change A as specified** (§2's finding: `RepositoryContextAssembler` cannot evaluate `variant`/`includedFieldIds` from a bare string today) — it is not itself a safety mechanism, only an enabling one. Change B does not require Change C at all (it operates entirely inside `PersonaContextResolver`, upstream of the interface boundary).

## 8. A/B/C Comparison

| Dimension | Change A | Change B |
|---|---|---|
| Prevents unsafe fallback from reaching model | **Yes** (all 10 cases, if strict) | **No**, by itself — addresses only the restricted-field dimension, not blank/raw-non-restricted content |
| Protects restricted fields | **Yes**, independently | **Yes**, at the source |
| Handles empty master document | **Yes** (case 4/6 pattern) | N/A — no restricted-field content to filter in this case |
| Handles missing registry | **Yes** (case 4 pattern) | N/A, same reasoning |
| Handles unknown skill | **Yes** (case 10) | **Yes**, for the restricted-field dimension specifically |
| Handles known skill with no mapping | **Yes** (case 9) | **Yes**, for the restricted-field dimension specifically |
| Changes historical resolver behavior | **No** — `PersonaContextResolver.kt` itself is untouched by Change A; only `RepositoryContextAssembler` changes | **Approach-dependent** (Phase 40 §6): Approach A of Change B modifies `resolveVariantA()` directly (historical impact); Approach B of Change B adds a new function (no historical impact) |
| Production files touched | `RepositoryContextAssembler.kt` only (plus Change C's interface ripple, a shared dependency) | `PersonaContextResolver.kt` only |
| New dependencies | None beyond Change C's contract | Approach A needs `fieldRegistry`/`skillKey` threaded into `resolveVariantA()`; Approach B needs none beyond what `resolve()` already holds |
| Regression surface | Low — this branch is never exercised in production today (provider always null) | Low for Approach B (purely additive); higher for Approach A (touches a historically-significant, frozen function) |
| Existing tests affected | None directly (new branch, not yet reachable in production) | Approach A: any future re-use of variant "A" for deliberate full-document research comparison would need re-verification; Approach B: none |

**Phase 40's finding preserved explicitly**: **Change A, implemented strictly, independently prevents `NOT_USABLE` content (including the dangerous raw-document case) from being consumed** — Change B is confirmed, again in this phase, to be a defense-in-depth improvement on top of Change A, not a strict co-requirement for closing the specific "reaches the model" risk. It remains valuable because it protects the *source* even against a hypothetical future caller of `PersonaContextResolver` that does not go through `RepositoryContextAssembler`'s Change-A-protected path at all (a caller this program has not enumerated but cannot rule out either, since `PersonaContextResolver` is a public class with no access restriction preventing a future, different caller).

## 9. Exact Patch Locations

**No file is edited in this phase.** Each entry below states the plan only.

### Change C

- **File**: `src/main/kotlin/com/pinkdreams/chat/context/PersonaContextProvider.kt`
- **Class/function**: the `getPersonaContext(...)` interface method.
- **Current behavior**: returns `String`.
- **Proposed behavior**: returns a new type carrying `renderedText: String`, `includedFieldIds: List<String>`, `variant: String` (returned), `requestedVariant: String`.
- **Smallest code change**: add the new type (a new file or a nested data class); change the interface's return type; update every implementer's return statement.
- **Interfaces affected**: `PersonaContextProvider` itself.
- **Callers affected**: `RepositoryContextAssembler.assemble()` (the only production call site today, though currently unreachable since the provider is always null); `TestChatContextAssembler.kt` (the test-harness equivalent); every one of the six implementing classes (`ResolvedPersonaContextProvider`, `AgeOnlyLabeledScalarDiagnosticProvider`, `LabeledScalarDiagnosticProvider`, `SelectiveRawContextProvider`, `FullRawContextProvider`, `StructuredFullDocumentContextProvider`) and every benchmark test method in `PersonaContextRealBenchmarkTest.kt` that calls `.getPersonaContext(...)` directly and treats the result as a `String` (a substantial number of call sites, spanning Phases 14 through 30's own test methods).
- **Tests that must be added/updated**: interface-conformance tests for all six implementers; every existing benchmark test method that currently does `val text = provider.getPersonaContext(...)` and performs string operations on `text` directly would need updating to extract `.renderedText` from the new type — a **mechanical, behavior-preserving refactor**, not a logic change, but touching a large number of existing test call sites.
- **Behavior that must remain byte-for-byte unchanged**: `renderedText`'s actual content, for every existing test scenario (Phases 14-30) — the refactor changes how the value is *carried*, never what the value *is*.

### Change A

- **File**: `src/main/kotlin/com/pinkdreams/chat/context/RepositoryContextAssembler.kt`
- **Class/function**: `assemble()`'s `personaContent` computation.
- **Current behavior**: `if (personaContextProvider != null) { provider.getPersonaContext(...) } else { core.content }`.
- **Proposed behavior**: evaluate the §4 predicate on the (post-Change-C) structured result; use `renderedText` only if `USABLE`; otherwise use `core.content`.
- **Smallest code change**: one conditional expression, extended by one predicate check.
- **Interfaces affected**: none new (consumes Change C's contract).
- **Callers affected**: none — `assemble()`'s own callers are unaffected, since its external signature (`ChatContext`) does not change.
- **Tests that must be added/updated**: new tests for each of §4's ten cases; existing `USABLE`-path tests (Phases 14-30) must continue passing unchanged, since case 5's outcome is identical to today's behavior when a provider is non-null and genuinely produces selective content.
- **Behavior that must remain byte-for-byte unchanged**: `core.content`'s own value and every non-persona context block (memory, profile, history) — confirmed by Phase 37 §9's locality analysis, unaffected by this further specification pass.

### Change B (both approaches documented, neither chosen)

- **File**: `src/main/kotlin/com/pinkdreams/chat/personacontext/PersonaContextResolver.kt`
- **Class/function**: `resolveVariantA()` (Approach A: modified in place) or a new sibling function (Approach B: additive).
- **Current behavior**: serializes `masterDoc.contentJson.toString()` with no filtering.
- **Proposed behavior**: filter top-level keys through `canAccessField()` before serialization, for whichever call sites route through the new/modified function.
- **Smallest code change**: Approach A — add a filtering loop inside the existing function, plus thread `fieldRegistry`/`skillKey` into its signature (affecting the deliberate `variant == "A"` call site too, per §8). Approach B — add a new function with the same filtering loop, called only from the two `?: return ...(...)` fallback sites, leaving `resolveVariantA()`'s own signature and the deliberate `variant == "A"` call path untouched.
- **Interfaces affected**: none (both approaches stay within `PersonaContextResolver.kt`'s private implementation).
- **Callers affected**: Approach A affects the deliberate `variant == "A"` top-level request path; Approach B affects only the two fallback branches.
- **Tests that must be added/updated**: new tests confirming no restricted-field substring appears in the fallback's output; Approach A additionally requires re-verifying Phase 6/7/9/10A's own historical characterization of "variant A" remains accurate for any future re-run, or is explicitly re-documented if not.
- **Behavior that must remain byte-for-byte unchanged**: Approach B — `resolveVariantA()`'s existing behavior, entirely. Approach A — no such guarantee is possible by construction, since the function itself changes.

## 10. Test Matrix

All tests use existing zero-network patterns (`interceptGeneration=true` for full-pipeline checks; direct resolver/provider calls for unit-level checks) — **no test in this matrix requires a real API call**.

### Baseline preservation

| Test | Expected | Component |
|---|---|---|
| `core.content` remains available and unchanged when provider is null | Byte-identical to today | `RepositoryContextAssembler` (pre-existing, regression-only) |
| Existing State-A behavior (provider null) unchanged after Changes A/B/C | Byte-identical | `RepositoryContextAssembler` |

### Persona Context

| Test | Expected | Component |
|---|---|---|
| Valid B (complete registry + mapping) | `USABLE`, Persona Context rendered | `PersonaContextResolver` + Change C |
| Valid C (same, variant C, behavioral-core union) | `USABLE`, Persona Context rendered | Same |
| Empty document (`{}`) | `NOT_USABLE`, baseline fallback (post Change A) | `PersonaContextResolver` + `RepositoryContextAssembler` |
| Missing document | `NOT_USABLE`, baseline fallback | Same |
| Empty registry | `NOT_USABLE`, baseline fallback | Same |
| Missing skill mapping | `NOT_USABLE`, baseline fallback, no restricted content (post Change A and/or B) | Same, plus `PersonaContextResolver` for Change B |
| Unknown skill | Identical to missing skill mapping | Same |
| Partially populated registry | `USABLE` per the mechanical predicate — **flagged `ENGINEERING DECISION REQUIRED`** for whether this is the *correct* production behavior, per §11 | `PersonaContextResolver` |

### Safety

| Test | Expected | Component |
|---|---|---|
| No restricted field reaches rendered production context (mapped skill) | Already validated, Phases 10C-30 — regression only | `PersonaContextResolver` |
| Raw master-document fallback cannot reach the model | **Requires Change A (strict) and/or Change B** — test asserts no raw-document substring appears in the final assembled system block | `RepositoryContextAssembler` (full pipeline, `interceptGeneration=true`) |
| `NOT_USABLE` cannot be consumed downstream | Test asserts `finalPersonaContent == core.content` for every `NOT_USABLE`-producing input from §4's table | `RepositoryContextAssembler` |

### Regression

| Test | Expected | Component |
|---|---|---|
| Normal chat (provider null, State A) | Unchanged | Full pipeline |
| Mapped skill | `USABLE`, Persona Context | `PersonaContextResolver` |
| Unmapped skill | `NOT_USABLE`, baseline fallback | Same |
| Multiple skills, mixed mapping | Each request's outcome matches its own skill's state, independently — no cross-request contamination | `PersonaContextResolver`, run per skill |
| History/memory/profile unaffected by any of Changes A/B/C | Byte-identical to pre-change behavior | `RepositoryContextAssembler` (locality re-confirmed, Phase 37 §9) |
| Restricted skills (`romantic_intimacy`/`foreplay`), field present and mapped | Field included correctly — already validated, regression only | `PersonaContextResolver` |

## 11. `PARTIAL`-State Analysis

**Does the current architecture have enough information to distinguish "valid but small persona context" from "accidentally incomplete persona context"?** **No — confirmed again in this phase, by the same method as Phase 40 §5**: no field-count manifest, no per-persona minimum-field declaration, and no completeness concept exists anywhere in `PersonaFieldRegistryEntry`, `SkillContextRequirement`, or the master-document schema. The §4 predicate, applied to both a 1-field and a 10-field selective result, returns `USABLE` for both, with no mechanism to tell them apart.

**No heuristic is introduced.** Per instruction, `identity.name`-required (or any other specific-field heuristic) is **not** adopted here, even as a tentative default — it remains, as in Phase 40, an *observed pattern* in the POC's own seed data (every POC skill happens to require `identity.name`), not a *stated architectural requirement* anywhere in source. Adopting it would be inventing a rule, which this phase does not do.

**This is documented as an unresolved product/architecture decision** — carried forward unchanged from Phase 39 §7 and Phase 40 §5, not re-opened or narrowed further here.

## 12. Regression Surface

| Component | Risk if Change A implemented | Risk if Change B implemented | Risk if Change C implemented |
|---|---|---|---|
| `RepositoryContextAssembler.kt` | Low — new branch, unreachable in production until Blocker C (provider activation) is separately resolved | None (file untouched) | Consumes the new contract type; mechanical |
| `PersonaContextResolver.kt` | Untouched | Approach A: moderate (touches a historically-significant function); Approach B: low (purely additive) | Untouched |
| `PersonaContextProvider.kt` + 6 implementers | None (file untouched) | None (file untouched) | **Highest surface of the three changes** — ripples to every implementer and every benchmark test call site (Phases 14-30) |
| Historical Phase 6/7/9/10A research characterization | Unaffected | Affected only under Change B Approach A | Unaffected |
| Production live traffic | **None** — `personaContextProvider` remains null in `Application.kt` regardless of any of these three changes; none of this is reachable in production today | Same | Same |

## 13. Implementation Boundary

### Can be implemented deterministically from existing evidence

- Change C's field set (`renderedText`, `includedFieldIds`, returned `variant`, `requestedVariant`) — derived, not invented, per Phase 39 §4.
- The §4 USABLE/NOT_USABLE predicate itself.
- Change A's mechanism (predicate-driven substitution of `core.content`).
- Change B's filtering logic (`canAccessField()` applied to `resolveVariantA()`'s or its sibling's output) — the *logic* is derivable; the *placement* (Approach A vs. B) is not.
- The full test matrix in §10.

### Requires a human/product decision

- Change B: Approach A vs. Approach B (confirmed safety-equivalent, Phase 40 §6, re-confirmed §8 here).
- Change A's strictness scope: whether it should be "always substitute on `NOT_USABLE`, no exceptions" (as specified throughout this document) or something more nuanced — this document assumes strict behavior throughout, since no source evidence suggests a reason for a softer rule, but this itself is a design choice being locked in, not merely observed, and should be explicitly ratified.
- Change C's concrete Kotlin type shape (data class / sealed class / enum-plus-fields).
- Change C's scope for `SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider` — whether they need to support the new contract at all.
- `PARTIAL`'s eventual definition, if pursued (§11) — not required for Changes A/B/C to be implemented, since the predicate in §4 does not depend on resolving `PARTIAL` at all, but flagged as a separate, still-open item.

### Requires new persona data/mapping work

- None of Changes A/B/C require this — confirmed, again, in this phase: all three are code-only, and — per Phase 40's own sequencing finding — do not need to wait for Blocker A (data population) to be implemented, since they fix a latent bug in the fallback path reachable in principle the moment a provider is ever non-null, independent of real data volume.
- Blocker A itself (populating `persona_field_registry`, `skill_context_requirement`, master documents for a real production persona) remains entirely separate and unaddressed by this document.

### Requires behavioral validation

- None of Changes A/B/C require a new behavioral (real-model) experiment — every test in §10 is deterministic and zero-network. The existing Phase 14-30 behavioral evidence (does labeling change model output) is unaffected by and unrelated to these safety changes.

### Must not be changed as part of the safety patch

- Historical benchmark providers (`AgeOnlyLabeledScalarDiagnosticProvider.kt`, `LabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, `FullRawContextProvider.kt`, `StructuredFullDocumentContextProvider.kt`) — their *behavior* must not change; only their *return type* changes under Change C, mechanically, per §9's "byte-for-byte unchanged `renderedText`" requirement.
- Phase 10–40 documents — none modified by this phase, none to be modified by any future implementation of A/B/C either (any correction would be a new, explicitly-dated addendum, per this program's standing discipline).
- Experimental B/LA/L behavioral characterization (Phases 14-30's own findings) — unaffected by, and irrelevant to, this safety-patch work.
- Benchmark mappings (`PersonaContextPocSeed.kt`'s seeded field registry/skill requirements) — untouched; this remains a benchmark/research fixture, not a production data source (Phase 36 §4 item 7), and none of Changes A/B/C alter it.

## 14. Human Decisions Required

Consolidated from §13's "requires a human/product decision" list, restated as a definitive gate:

1. Change B: Approach A vs. Approach B.
2. Ratification of Change A's strict-substitution behavior (this document assumes it but flags it as a decision, not merely an observation).
3. Change C's concrete Kotlin type shape.
4. Change C's scope for the three non-selective providers (`SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider`).
5. Whether to pursue a `PARTIAL` definition at all (not required for A/B/C, but a related open item worth a deliberate "not now" or "define as follows" decision rather than remaining silently unresolved indefinitely).

## 15. Explicitly Not Established

- Any causal, generalization, LA-vs-L, or 27−24 finding — entirely outside this phase's brief, unchanged from Phases 25-32.
- Any production recommendation for Path A/B/C from `PHASE32_ENGINEERING_DECISION_PACKAGE.md`.
- LA's production-readiness.
- `PARTIAL`'s definition.
- Whether `SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider` will ultimately support Change C's contract.
- Any timeline, resourcing, or sequencing commitment for implementing Changes A/B/C relative to Blocker A (data) or Blocker C (production wiring).

## 16. Stop Conditions

No STOP condition was encountered requiring an API call, a database mutation, a production code change, or an invented architectural rule. The one area where this document could have been tempted to invent behavior — `PARTIAL`'s definition and the `identity.name`-required heuristic — was instead explicitly left unresolved, per instruction, rather than resolved by assumption (§11).

## 17. Final Implementation Readiness Status

**Implementation cannot begin immediately.** Five human decisions (§14) gate it — none is a fact this document, or any prior phase, can resolve from source evidence alone; all five are genuine engineering/product trade-offs. **Once those five decisions are made, the patch plans in §9 and the test matrix in §10 are ready to execute as specified**, requiring no further audit or specification work beyond what Phases 33–41 have already produced. The single largest-effort item, regardless of which decisions are made, is Change C's interface migration (§9, §12) — its regression surface (touching six provider implementations and a substantial number of existing benchmark test call sites) is the dominant cost driver of this entire safety-patch effort, independent of the Change B Approach A/B choice or Change A's strictness ratification.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE41_IMPLEMENTATION_PLAN.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

## Explicit confirmation

Zero API calls were made. Zero database mutations occurred. Zero production code, persona-data, mapping, renderer, or provider modifications were made. No historical artifact (Phase 1 through Phase 40) was modified. No behavioral experiment was run. LA was not declared production-ready. The Phase 23/25 evidence boundary was not reinterpreted. Change A, B, and C were not selected on behalf of the product/engineering owner. Phase 42 is not started automatically.
