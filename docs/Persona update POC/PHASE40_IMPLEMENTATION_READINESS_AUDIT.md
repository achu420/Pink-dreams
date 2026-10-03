# Phase 40 — Persona Context Safety Contract Implementation Readiness Audit

**Zero API calls. Zero production code, database, migration, or persona-data changes.** This document audits the Phase 39 contract against every known failure mode and produces an implementation-ready specification — it does not implement anything. No historical Phase 10–39 artifact was modified. No behavioral experiment is run. LA is not declared production-ready. Per instruction, the two restricted-field approaches (Phase 38/39) are not chosen between, since source evidence does not make either objectively required for safety (§6).

Throughout, findings are tagged **Established** (directly confirmed from source), **Derived** (a conclusion this phase reaches by combining established facts), **Unresolved** (a genuine open question source does not answer), or **Requires Human Decision** (an engineering/product trade-off, not a fact).

---

## 1. Reconstruct the Exact Production Safety Contract

Restated precisely from Phase 39, each piece re-verified against current source in this phase:

| Element | Definition | Source location | Status |
|---|---|---|---|
| `requestedVariant` | The variant string (`"A"`/`"B"`/`"C"`) originally passed into `PersonaContextResolver.resolve()` | **Does not exist as retained state anywhere today** — the caller's original argument is not preserved past the initial call once `resolveVariantA()` substitutes its own `"A"` label into the returned projection | **Established** (absence confirmed) |
| Returned `variant` | `PersonaContextProjection.variant` — always `"A"` when `resolveVariantA()` produced the result, else echoes the requested `"B"`/`"C"` | `PersonaContextResolver.kt` — `resolveVariantA()`'s hardcoded `variant = "A"`; the non-A branch's `return PersonaContextProjection(..., variant = variant)` | **Established** |
| `includedFieldIds` | List of field IDs actually selected; empty (`emptyList()`) unconditionally whenever `resolveVariantA()` produced the result, regardless of content length | Same file, both branches | **Established** |
| `USABLE` | `requestedVariant == returned variant` (no fallback occurred) AND `includedFieldIds.isNotEmpty()` AND `renderedText.isNotBlank()` | Phase 39 §3, re-verified this phase | **Derived** (a classification built from established signals, not itself a source-level fact) |
| `NOT_USABLE` | `returned variant == "A"` while a selective variant was requested, OR `includedFieldIds.isEmpty()` while the requested variant was honored | Phase 39 §3 | **Derived** |
| `PARTIAL` | Not mechanically distinguishable from `USABLE` by any existing signal | Phase 39 §3 | **Unresolved** (architectural absence, not merely undecided) |
| Baseline `core.content` fallback | `RepositoryContextAssembler.assemble()`'s `personaRepository.getActiveCoreVersion(request.personaId)`-derived `core.content`, already in scope at the point `personaContent` is computed | `RepositoryContextAssembler.kt` | **Established** |

## 2. Complete Current Data Flow

```text
Application.kt
    — constructs chatEngineDependencies with personaContextProvider OMITTED (defaults to null)
    ↓
ChatEngineFactory.kt
    — Dependencies.personaContextProvider: PersonaContextProvider? = null (line 118)
    — passed straight through to RepositoryContextAssembler's constructor (line 325)
    ↓
RepositoryContextAssembler.assemble(request: ChatRequest)
    — core = personaRepository.getActiveCoreVersion(request.personaId) — ALWAYS fetched, regardless
      of provider state; this is where core.content (the baseline) lives, already in scope
    — personaContent = if (personaContextProvider != null) {
          personaContextProvider.getPersonaContext(request.personaId, core.id, pinnedSkillKey ?: "general_chat", request.content)
        } else { core.content }
      — THE ONE SIGNAL AVAILABLE HERE TODAY: whether the provider is null. Nothing about
        USABLE/NOT_USABLE/PARTIAL is observable at this line under the current interface.
    ↓
PersonaContextProvider.getPersonaContext(...): String   ← interface boundary; return type is a bare,
    non-nullable String — THIS IS WHERE EVERY OTHER SIGNAL IS DISCARDED
    ↓
ResolvedPersonaContextProvider.getPersonaContext(...)
    — calls resolver.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, variant)
    — returns projection.renderedText ONLY — variant, includedFieldIds, excludedFieldIds,
      personaTokenEstimate are all computed, then discarded at this exact line
    ↓
PersonaContextResolver.resolve(...): PersonaContextProjection
    — HAS every signal: requested variant (its own parameter), returned variant (what it sets on
      the projection it returns), includedFieldIds (what it computes during selection)
    — does NOT have core.content (no dependency on PersonaCoreVersionRepository at all)
    ↓
PersonaContextRenderer.render(fieldValueMap): String
    — pure rendering, no knowledge of "how did we get these fields" or fallback state at all
```

**Where each required signal currently exists, and where it is discarded**:

| Signal | Exists at | Discarded at |
|---|---|---|
| `requestedVariant` | Never retained past the `resolve()` call's own parameter scope | Immediately — no caller of `resolve()` keeps a copy alongside the returned `PersonaContextProjection` |
| Returned `variant` | `PersonaContextProjection.variant` | `ResolvedPersonaContextProvider.getPersonaContext()`'s `return projection.renderedText` line |
| `includedFieldIds` | `PersonaContextProjection.includedFieldIds` | Same line |
| `core.content` | `RepositoryContextAssembler.assemble()`'s local `core` variable | Never discarded — it remains in scope, but is not passed anywhere it could be compared against a completeness signal |

**Smallest interface boundary that must change, precisely identified**: `PersonaContextProvider.getPersonaContext(...): String` (`chat/context/PersonaContextProvider.kt`, 16 lines total, confirmed by direct re-read in this phase) — the single method signature every one of the six implementing classes overrides. **This is the one and only interface boundary in the entire traced flow where the needed signals cross a component seam and are lost.** Everything upstream of it (inside `PersonaContextResolver`) already has the data; everything downstream of it (`RepositoryContextAssembler`) already has `core.content`; the interface itself is the chokepoint. — **Established**, re-confirmed by direct reading of the 16-line file in this phase, not merely inherited from Phase 39.

## 3. Contract Audited Against Every Known Failure Mode

| Failure mode | Contract classification | Does the Phase 39 contract correctly resolve it? |
|---|---|---|
| Missing master document | `NOT_USABLE` (`resolveVariantA()` fired, `variant` mismatch, `includedFieldIds.isEmpty()`) | **Yes** — both signals agree |
| Empty master document `{}` | Path-dependent: via normal path, `NOT_USABLE` (`includedFieldIds.isEmpty()`, `variant` matches request); via `resolveVariantA()` fallback (e.g. missing skill mapping on this same empty document), `NOT_USABLE` (`variant` mismatch AND `includedFieldIds.isEmpty()`) | **Yes, in both sub-cases** — this is precisely the case Phase 38's bare `isBlank()` proposal missed and Phase 39's two-signal model fixes |
| Empty registry | `NOT_USABLE` (`includedFieldIds.isEmpty()`, `variant` matches request — normal path ran to completion) | **Yes** |
| Missing skill mapping (document present) | `NOT_USABLE` (`variant` mismatch) — **classification correctly flags this as unsafe**, but the classification alone does not by itself filter restricted content; it only tells the caller "do not use this as Persona Context, fall back" | **Yes for classification; the actual restricted-field closure still requires Change B (§6) — the contract correctly identifies the danger but a separate fix is what neutralizes it** |
| Unknown skill key | Identical code path to "missing skill mapping" (Phase 38 §4 states 11-12, re-confirmed) | Same as above |
| Known skill with no mapping | Identical to unknown skill key | Same as above |
| Partial registry (some fields registered, not all) | `USABLE` per the mechanical model — **the contract does not flag this as a problem at all** | **No — this is exactly `PARTIAL`'s unresolved status (§5); the contract, as specified, classifies genuinely incomplete data as fully usable** |
| Requested B/C, returned A | `NOT_USABLE` via `variant` mismatch | **Yes, directly, by design — this is the signal's primary purpose** |
| Raw unfiltered master-document exposure | Correctly classified `NOT_USABLE` (same signals as "missing skill mapping") — **but classification is not the same as prevention**; a `NOT_USABLE` result that is still passed downstream unfiltered before Change A's fallback logic runs would still contain the raw content in `renderedText` | **Partially — the contract enables detection; Changes A and B (§7) are what actually prevent the unsafe content from being used** |
| Restricted-field exposure via the fallback path | Same as above — detected by classification, not prevented by classification alone | **Partially, same reasoning** |
| Valid B/LA/C projection | `USABLE` (variant matches, fields non-empty, text non-blank) | **Yes** — this is the only state the system was designed for and the only one behaviorally validated (Phases 14–30) |

**Key distinction surfaced in this audit, not explicit in Phase 39**: **the contract is a detection mechanism, not a prevention mechanism by itself.** Correctly classifying a result as `NOT_USABLE` does not, on its own, stop the raw or restricted content from being present in `renderedText` and potentially used if a caller does not act on the classification. The contract's value depends entirely on `RepositoryContextAssembler` (Change A) actually checking and discarding `NOT_USABLE` content, and on Change B closing the raw-document generation itself at the source. **This phase treats this as a `Derived` finding**, not a contradiction of Phase 39 — Phase 39's §9 already scoped Change A and Change B as necessary, separate implementation steps; this audit simply makes explicit that the contract (Change C) alone, without A and B, provides visibility but not safety.

## 4. Sufficiency Determination

Answering the five specific questions:

- **Prevents silent empty persona context?** **Yes, once Change A is implemented on top of the contract** — the contract alone provides the classification; Change A is what actually substitutes `core.content`. Contract alone: detection only. Contract + Change A: **prevention confirmed**. — **Derived**
- **Prevents accidental raw-document exposure?** **No, not from the contract alone.** The contract correctly *labels* the raw-document case as `NOT_USABLE`, but `renderedText` still *contains* the raw document at that point — something downstream must discard it, which is exactly Change B's job (filtering at the source) or Change A's job (discarding `NOT_USABLE` content before it reaches the model). **Both must exist together; the contract by itself is necessary but not sufficient.** — **Derived**
- **Prevents restricted-field leakage?** **Same answer as raw-document exposure** — the contract detects the dangerous state; only Change B (filtering `resolveVariantA()` or its replacement) or Change A (discarding `NOT_USABLE` results entirely, including their content) actually stops the leak. **If Change A is implemented as "discard the entire `renderedText` for any `NOT_USABLE` result, unconditionally," this alone would suffice to prevent leakage even without Change B** — a point not explicitly stated in Phase 39, surfaced here: **Change A, if implemented strictly (never using `NOT_USABLE` content for any purpose), independently closes the restricted-field leak for the fallback path, making Change B a defense-in-depth improvement rather than a strict prerequisite for safety.** This is a **Derived** finding worth flagging to whoever makes the Change A/B sequencing decision.
- **Prevents mistaken A-fallback being treated as valid B/C?** **Yes, directly** — this is the contract's core purpose and it is fully established to work for every traced fallback path (§3).
- **Prevents loss of today's `core.content` baseline?** **Yes, once Change A is implemented** — same reasoning as the first bullet.

## 5. `PARTIAL` Audit

**Should it remain an explicit unresolved state?** **Yes — Established as the correct current characterization**, not merely a placeholder. Re-confirmed in this phase: no field-count manifest, no per-persona "required fields" declaration, and no minimum-viability concept exists anywhere in `PersonaFieldRegistryEntry`, `SkillContextRequirement`, or the master-document schema. There is genuinely nothing in the current data model that could be compared against to compute a completeness ratio.

**Can the current architecture represent it at all, even in principle, without new data?** **No** — `Unresolved`, confirmed by exhaustive search: the closest existing concept is a `SkillContextRequirement`'s `requiredFields` list, which states which fields are *relevant to a skill*, not which fields are *necessary for the persona to be coherent*. These are different concepts (skill-relevance vs. persona-completeness) that the current schema conflates by using the same word "required" for the former only.

**What information would be required to make it deterministic?** Not invented here, only enumerated as options requiring a human decision: (a) a new, explicit "minimum required field set" declaration per persona (a new data concept, not currently present anywhere); (b) a configurable completeness threshold (e.g., "at least N of M registered fields must be selected") — arbitrary without a stated design rationale; (c) treating `identity.name` (or some other specific field) as a hard prerequisite for *any* `USABLE` classification, on the reasoning that a persona without even a name is definitionally incomplete — this is the one option with some intuitive grounding in the existing schema's own structure (name is present in every skill's required-field set in the POC's own seed data, e.g. `about`, `general_chat`, `friendship` all require `identity.name`), but **this pattern-matching observation is not itself a stated design decision anywhere in source, so this phase does not adopt it as a rule** — it is surfaced as a candidate, not established. — **Requires Human Decision** for all three options.

## 6. Restricted-Field Approach Comparison — Impact Analysis (no choice made)

| Dimension | Approach A (filter `resolveVariantA()`) | Approach B (separate additive fallback function) |
|---|---|---|
| Safety outcome for the fallback paths | Fully closes the leak | Fully closes the leak — **identical safety outcome, confirmed by re-tracing both in this phase** |
| Impact on historical research baseline (Phases 6, 7, 9, 10A) | Direct — the function's behavior would diverge from what those phases documented it as ("full, unfiltered, by design"), requiring a disclosure note if ever re-run for comparison | None — `resolveVariantA()` remains byte-for-byte unchanged |
| Regression surface | Touches one existing, historically-significant function | Purely additive; zero existing behavior changes |
| Dependencies required | `fieldRegistry`/`skillKey` threaded into a function that currently takes only `personaCoreVersionId` — a signature change affecting the deliberate `variant == "A"` call site too | Same data already available inside `resolve()`'s own non-A branch; no new dependency |
| Consolidation | One function serves both roles | Two functions, one frozen, one new |

**Does existing source evidence make one objectively required for safety?** **No — confirmed by this phase's own re-analysis.** Both approaches achieve identical, complete restricted-field closure for every fallback path traced in Phase 38/39/40. The difference between them is entirely about regression surface and historical-research-baseline preservation, not about safety outcome. **Per instruction, this phase does not choose between them** — the choice remains a **Requires Human Decision** item, exactly as Phase 39 left it, now with the safety-equivalence explicitly confirmed rather than merely asserted.

## 7. Implementation-Ready Specification

### Exact files/classes likely to change

| File | Change | Category |
|---|---|---|
| `chat/context/PersonaContextProvider.kt` | Interface method return type changes from `String` to a structure carrying `renderedText`, `includedFieldIds` (or equivalent), returned `variant`, and `requestedVariant` | Change C |
| `chat/personacontext/PersonaContextResolver.kt` | No change to `resolve()`'s own logic; `resolveVariantA()` either gains filtering (Approach A) or a new sibling function is added (Approach B) | Change B |
| `chat/context/ResolvedPersonaContextProvider.kt` | Updated to return the new contract type instead of `projection.renderedText` alone (trivial — the data already exists on `PersonaContextProjection`) | Change C |
| `chat/context/AgeOnlyLabeledScalarDiagnosticProvider.kt`, `LabeledScalarDiagnosticProvider.kt` | Same trivial update as above (both already call `resolve()` and have every needed field available) | Change C |
| `chat/context/SelectiveRawContextProvider.kt`, `FullRawContextProvider.kt`, `StructuredFullDocumentContextProvider.kt` | **Unresolved how these would populate the new contract** — none of them call `PersonaContextResolver.resolve()`'s variant/field-selection machinery; `SelectiveRawContextProvider` reuses B's `includedFieldIds` but not `variant`-mismatch semantics in the same way; `FullRawContextProvider`/`StructuredFullDocumentContextProvider` have no selection concept at all (they always include everything) | **Requires Human Decision** — Change C's scope for these three classes |
| `chat/context/RepositoryContextAssembler.kt` | `personaContent` computation extended to check the new contract's usability classification and fall back to `core.content` when `NOT_USABLE` | Change A |

### Exact interface/data-contract changes

Conceptually (concrete Kotlin shape left as a **Requires Human Decision** item, per Phase 39 §4, unchanged):

```text
PersonaContextProvider.getPersonaContext(...): <new type>, replacing : String

<new type> conceptually carries:
  - renderedText: String
  - includedFieldIds: List<String>
  - returnedVariant: String
  - requestedVariant: String
```

### Invariants (restated precisely, now cross-referenced to which change enforces each)

1. `finalPersonaContent == core.content` whenever the contract classifies `NOT_USABLE` — **enforced by Change A**.
2. No restricted field reaches `renderedText` via any fallback path — **enforced by Change B** (either approach), and independently reinforced by a strict Change A that never uses `NOT_USABLE` content for any purpose (§4).
3. `requestedVariant != returnedVariant` is always classified `NOT_USABLE` — **enforced by the contract itself (Change C)**, consumed by Change A.
4. `PARTIAL` remains explicitly unmodeled until a human decision is made per §5 — **no change enforces this; it is a deliberate non-invariant**.

### Zero-cost deterministic tests required

All via the existing `interceptGeneration=true`/direct-resolver-call patterns, proven since Phase 10C — **no test in this specification requires a real API call**:

- Contract classification tests: one per row in Phase 39 §10's matrix, re-targeted at the new contract type instead of string heuristics.
- Change A tests: `finalPersonaContent` byte-equality against `core.content` for every `NOT_USABLE`-producing input.
- Change B tests: no restricted-field substring present in output for every missing-skill-mapping/unknown-skill input, for whichever approach is eventually chosen.
- Change C conformance tests: every implementing class returns a well-formed contract instance (not merely compiles) — including explicit, separate test coverage confirming whichever decision is made for `SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider` (§7 table).
- Regression tests: existing Phase 14-30 behavioral test methods' own assertions must continue to pass unchanged after Change C's interface migration (a refactor, not a behavior change, for the already-validated `USABLE` path).

### Migration/data requirements

**None required by Changes A, B, or C themselves** — all three are code-only changes, independent of Blocker A (data population, Phase 36). They would, however, need Blocker A resolved before their effects become observable in production (today, with no master document ever present, every request already resolves to `NOT_USABLE` — implementing Changes A/B/C alone, without any data population, would simply make production's *existing* empty-persona-content behavior also *correctly fall back to `core.content`* instead of sending blank content, which is itself a strict improvement over today's latent bug, reachable even before Blocker A or Blocker C work begins, **if** `personaContextProvider` were ever activated).

### Failure behavior

Traced precisely: no case in Phase 38/39/40's combined analysis produces an exception, a `null` propagating uncaught, or non-deterministic output. Every failure mode resolves to a deterministic string (today: sometimes wrong; post-Change-A: `core.content`). **This remains true after the proposed changes** — Changes A/B/C are reclassifications and substitutions of existing deterministic outputs, not new failure-handling logic with its own new failure modes.

### Rollback behavior

Since Changes A/B/C do not touch production's actual request path (`personaContextProvider` remains `null` in `Application.kt` regardless of any of these changes, per Blocker C being untouched), **rollback is trivial and low-risk**: reverting the code changes restores the prior interface/behavior exactly, and because the new code path is never exercised in production until Blocker C is separately resolved, there is no data-migration or in-flight-request concern to roll back.

### Explicit items requiring human/product-owner decision

- Approach A vs. B for restricted-field protection (§6) — confirmed safety-equivalent, so this is purely a trade-off decision.
- `PARTIAL`'s eventual definition, if one is ever adopted (§5) — three candidate options enumerated, none chosen.
- Change C's concrete Kotlin type shape (data class / sealed class / enum-plus-fields).
- Change C's scope for `SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider` — whether they need the new contract at all, given they are not part of the currently-proposed B-vs-LA production path.
- Whether Change A should be "strict" (never use any `NOT_USABLE` content, closing the restricted-field leak independently of Change B, per §4's finding) or "classification-only" (rely on Change B separately for restricted-field safety) — this specific strictness choice was not explicitly named in Phase 39 and is a new, actionable decision point surfaced by this audit.
- Sequencing of Changes A/B/C relative to Blocker A (data population) and Blocker C (production wiring) — Changes A/B/C are shown in this phase to be independently valuable even before Blocker A/C are addressed (they fix a latent bug in the fallback path itself, observable in principle the moment `personaContextProvider` is ever non-null, regardless of how much real data exists), which is a new sequencing insight: **these changes do not need to wait for Blocker A to be worth implementing.**

---

## Established Facts

- The single interface boundary where every needed signal is lost is `PersonaContextProvider.getPersonaContext(...): String`, confirmed by direct re-reading of the full (16-line) interface file in this phase.
- `core.content` is already in scope at `RepositoryContextAssembler.assemble()`'s exact point of need, requiring no new dependency for Change A.
- The Phase 39 contract correctly classifies every traced failure mode as `USABLE`/`NOT_USABLE` except `PARTIAL`, which it does not attempt to model and is confirmed, again, to have no representable signal in current source.
- The contract alone (Change C) is a detection mechanism; it does not by itself prevent raw-document or restricted-field exposure — Changes A and/or B are what convert detection into prevention.
- A strict Change A (never using any `NOT_USABLE` content) independently closes the restricted-field leak for the fallback path without requiring Change B at all — a new finding this phase surfaces, not previously stated in Phase 38 or 39.
- Approach A and Approach B (§6) are confirmed safety-equivalent; the choice between them is not decidable from source evidence.

## Derived Findings

- Detection (Change C) and prevention (Changes A/B) are conceptually and practically separable, and Phase 39's own change-boundary analysis already implied this without stating it as explicitly as this audit does.
- Changes A/B/C do not need to wait for Blocker A (data population) to be worth implementing, since they fix a latent, already-reachable-in-principle bug in the fallback path itself.

## Unresolved

- `PARTIAL`'s eventual definition (§5) — three candidate approaches, none adopted.
- Whether `SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider` can or should support the new contract (§7).

## Requires Human Decision

- Approach A vs. B (§6) — confirmed safety-equivalent, purely a trade-off choice.
- Change A's strictness (never-use-`NOT_USABLE`-content vs. classification-only) — newly surfaced, actionable.
- Change C's concrete type shape.
- `PARTIAL`'s definition, if pursued at all.
- Scope of Change C for the three non-selective providers.
- Sequencing relative to Blocker A/C, though this phase notes Changes A/B/C do not strictly require Blocker A to precede them.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE40_IMPLEMENTATION_READINESS_AUDIT.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

## Explicit confirmation

Zero API calls were made. Zero production, database, migration, or persona-data changes were made. No historical artifact (Phase 10 through Phase 39) was modified. No behavioral experiment was run. LA was not declared production-ready. Approach A and Approach B (§6) were not chosen between, since source evidence establishes them as safety-equivalent rather than one being objectively required. Phase 41 is not started automatically.
