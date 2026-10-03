# Phase 45 — Production Architecture, Final

**Zero API calls. Zero code changes. Zero database changes. Zero production wiring. Zero behavioral experiment.** This is the final architecture design, building on and in places superseding Phase 44's draft where new requirements (observability, rendering-policy pluggability) warrant a refinement — every such refinement is disclosed as such, not silently substituted. No Phase 10–44 historical artifact is modified. No claim is made that LA should ship; that remains a separate, unresolved evidence question (§13).

Tags used throughout: **SOURCE FACT**, **DESIGN DECISION**, **DERIVED DESIGN**, **HUMAN DECISION REQUIRED**.

---

## 1. Production Architecture — Complete Flow

```text
ChatRequest (personaId, conversationId, userId, content)
    ↓
[1] Persona identification            — personaId is already present on ChatRequest (SOURCE FACT, re-confirmed Phase 44 §2)
    ↓
[2] Persona/version loading           — personaRepository.getActiveCoreVersion(personaId); fails the whole
                                         request (VALIDATION_FAILED) if absent — SOURCE FACT, pre-existing,
                                         unrelated to Persona Context, unchanged by this design
    ↓
[3] Skill identification              — owned by IntentDiscovery/SkillSelection, runs AFTER context assembly
                                         today (PipelineStage.CONTEXT_ASSEMBLY precedes SKILL_SELECTION —
                                         SOURCE FACT, Phase 40 §2). This is the single biggest structural
                                         constraint on the new design — see §2's resolver-timing discussion.
    ↓
[4] Field requirements                — a per-skill declaration of required/recommended/conditional fields
                                         (conceptually unchanged from today's SkillContextRequirement shape)
    ↓
[5] Field authorization                — THE single authoritative gate (§4) — decides, per field, whether
                                         it may be included for this skill, BEFORE selection/rendering
    ↓
[6] Field selection                    — applies [4]'s requirements, filtered through [5]'s authorization
    ↓
[7] Persona context projection         — the new PersonaContextResult (§5), carrying a STATUS, not just text
    ↓
[8] Rendering                          — a pluggable policy (§13) applied only to fields that survived [5]/[6]
    ↓
[9] Memory/profile/history integration — UNCHANGED, confirmed structurally independent (Phase 37 §9,
                                         re-verified: these are separate ContextBlocks with no data
                                         dependency on persona-context resolution)
    ↓
[10] Final model request               — assembled by the context assembler, using [7]'s status to decide
                                          between [8]'s rendered text and core.content (fallback, §3)
```

**Where each responsibility belongs — not assumed to match current class boundaries** (per instruction):

| Step | Owning component (new design) | Why here, not elsewhere |
|---|---|---|
| [1]–[2] | Existing `RepositoryContextAssembler`/`PersonaRepository` | Already correct, already tested, unrelated to Persona Context's own problems — **DESIGN DECISION**: do not touch what already works |
| [3] | Existing `IntentDiscovery`/`SkillSelection` | Unrelated to persona-field logic; a skill key is simply an *input* to steps [4]-[6] | **DESIGN DECISION** |
| [4]–[6] | **New**: a single `PersonaFieldAuthorizer` (or equivalently-named) component, replacing the research resolver's conflated selection+fallback logic | Selection and authorization are logically inseparable (a field cannot be "selected" independent of "is it allowed") — collapsing them into one component with one job (produce an authorized field set, nothing else) is simpler than the research code's split, where `canAccessField()` is a helper called *inside* a much larger, multi-purpose function | **DESIGN DECISION** |
| [7] | Same new component, returning `PersonaContextResult` | The result IS the authorized selection — no separate "projection" step needed beyond packaging | **DESIGN DECISION** |
| [8] | **New**: a separate, pluggable renderer taking a `ScalarRenderingPolicy` | Rendering (how a value looks) must never be coupled to authorization (whether a value is allowed) — this is the direct architectural answer to item 13's "rendering policy replaceable without rewriting retrieval/security" | **DESIGN DECISION** |
| [9]–[10] | Existing `RepositoryContextAssembler` | Unchanged | **DESIGN DECISION** |

**A structural constraint worth naming explicitly**: because skill identification [3] happens *after* context assembly [1]-[2] today (**SOURCE FACT**), a production Persona Context resolution attempted during the *first* context-assembly pass can only use a placeholder skill (`"general_chat"`, per existing code) — the *true* selected skill is not yet known. This is the same timing gap `PersonaContextResolutionStage` was originally meant to close (Phase 35 §0) and remains architecturally unresolved by anything in Phases 33-44. **This phase does not silently assume it away.** Two resolutions exist, neither chosen here: (a) resolve Persona Context using the placeholder skill only, accepting that skill-specific field selection cannot happen on the first pass (a regression from the *intended* selective-retrieval design, though not from *today's* actual behavior, which has no selective retrieval at all); (b) finally wire a second resolution pass after skill selection, using this phase's new component instead of the dormant `PersonaContextResolutionStage`. — **HUMAN DECISION REQUIRED** (§14).

## 2. Production Resolver: A, B, C, or D?

**Selected: D — replace the resolver concept with a cleaner pipeline/service**, not A (reuse), not B (refactor in place), not C (a parallel resolver with the *same shape* as the research one).

**Why, precisely**:
- **A (reuse) is rejected**: the research resolver's `resolveVariantA()` fallback is confirmed unsafe as a production fallback (Phase 37-41, restated as a given fact in this phase's instructions) — reusing it as-is would import that unsafe behavior directly.
- **B (refactor in place) is rejected**: Phase 44 already established that editing `PersonaContextResolver.kt` would put Phases 14-30's own benchmark-harness reproducibility at risk, since dozens of existing test methods depend on its exact current behavior. With no backward-compatibility requirement for *production*, there is no offsetting benefit to accepting that research-reproducibility risk.
- **C (a parallel resolver with the same conceptual shape — select fields, fall back internally) is rejected**: this would simply reproduce the *same architectural flaw* (a component that conflates "can this be resolved" with "what happens if it can't") in new code, rather than fixing the actual design defect (D1/D6, Phase 44 §4/§6).
- **D (a cleaner pipeline/service) is selected**: the new `PersonaFieldAuthorizer` (§1) has exactly one job — produce an authorized, structured field selection, or a well-defined non-usable status. It has no concept of "fallback content" at all (not even to return an empty string) — the caller (context assembler) owns 100% of fallback policy. This is a genuine architectural simplification, not merely a renamed class.

**— DESIGN DECISION, with the "why" derived directly from the given source findings (raw fallback unsafe; no backward-compat constraint) rather than invented.**

## 3. Fallback Semantics — Complete Table

| Condition | Data returned | `core.content` used? | Request continues? | Logged/observable? | Model receives persona data? | Considered safe? |
|---|---|---|---|---|---|---|
| Persona does not exist | N/A — fails upstream of Persona Context entirely | N/A | **No** — existing `VALIDATION_FAILED` (Phase 36 §7, unchanged) | Yes, via existing error path | No | Yes — pre-existing, unrelated behavior |
| Persona version does not exist | Same as above | Same | Same | Same | No | Yes |
| Persona context data does not exist (no document) | `PersonaContextResult(status = DOCUMENT_NOT_FOUND, renderedText = "", ...)` | **Yes** | Yes | Yes — status is a first-class field, trivially logged | No | Yes |
| Field registry missing/empty | `PersonaContextResult(status = REGISTRY_EMPTY, ...)` | Yes | Yes | Yes | No | Yes |
| Skill mapping missing | `PersonaContextResult(status = SKILL_MAPPING_MISSING, ...)` | Yes | Yes | Yes | No | Yes |
| Unknown skill | Same status as "skill mapping missing" — **DERIVED DESIGN**: the new authorizer, like the research resolver, has no way to distinguish "unknown to the whole system" from "known but unmapped" (Phase 38 §4, structurally still true — a lookup either finds a row or it doesn't), and does not need to; both produce `SKILL_MAPPING_MISSING` | Yes | Yes | Yes | No | Yes |
| Restricted field requested (present in a skill's field list, but that skill is not in the field's authorized set) | The field is silently **excluded from selection** — not a resolution failure, just one field not included; if this exclusion leaves zero fields, the overall status becomes `ZERO_FIELDS_SELECTED` | Depends — only if exclusion empties the whole selection | Yes | Yes — a restricted-field-rejection count is part of diagnostics (§9) | **No**, for that field specifically | Yes — this is the authorization boundary working as intended, not a failure mode |
| Provider/resolver failure (an exception — DB error, malformed data the parser cannot recover from) | Caught at the orchestration boundary; treated identically to `DOCUMENT_NOT_FOUND` for fallback purposes, with a distinct `RESOLVER_ERROR` status for diagnostics | Yes | Yes — **the chat request itself must not fail** merely because Persona Context resolution threw; this is a **DESIGN DECISION**, since no source establishes this explicitly, but it follows directly from D2's "strict substitution, no exceptions" locked position generalized to include exceptions, not only unusable results | Yes, with the underlying exception detail (not persona content) | No | Yes |
| Zero fields selected (registry/mapping technically present, but nothing passes required/recommended/conditional scoring) | `PersonaContextResult(status = ZERO_FIELDS_SELECTED, ...)` | Yes | Yes | Yes | No | Yes |
| Partially populated persona (some fields selected, not all conceivable ones) | `PersonaContextResult(status = USABLE, renderedText = <partial content>, ...)` | **No** — this is the `PARTIAL` question, resolved in §6: production does not distinguish this from full `USABLE` | Yes | Yes (field count is part of diagnostics) | **Yes** — partial content IS sent | **Yes, per §6's decision** — not flagged as unsafe, since no minimum-viability concept is adopted |
| Valid, complete persona context | `PersonaContextResult(status = USABLE, renderedText = <full content>, ...)` | No | Yes | Yes | Yes | Yes |

**No case invents behavior beyond what source or the given locked decisions support.** Where a genuine product judgment call exists (the partial-data "is this actually desirable" question, as distinct from "is it safe"), it is separated out to §6/§14, not silently baked into this table.

## 4. Restricted-Field Security — Single Authoritative Boundary

**The single authoritative place field authorization occurs**: inside the new `PersonaFieldAuthorizer`, at the point of *selection* — specifically, before any field ID is added to the result's `includedFieldIds`, it must pass an authorization check (`canAccessField`-equivalent logic, reused unchanged in concept from the research code, **SOURCE FACT** re: the existing gate's correctness — 0 leakage, Phases 10C-30). **No other component performs this check, ever** — not the renderer, not the context assembler, not any provider. — **DESIGN DECISION**.

**Fail-closed guarantee, verified against every named risk**:
- **Unknown skill** → no `SkillContextRequirement` row is found → zero fields are even considered for selection → nothing to authorize incorrectly.
- **Skill has no mapping** → identical mechanism.
- **Registry missing** → the authorizer's field-metadata lookup fails for every field ID → nothing passes.
- **Fallback occurs** → fallback (§3) never touches `PersonaFieldAuthorizer`'s output at all; it substitutes `core.content`, a completely different data source with no persona-context fields in it whatsoever.
- **Provider returns raw content** → **there is no "provider" concept that can return raw content in this design** — the only production component is the authorizer, which never has a raw-dump mode (this is D1's direct architectural consequence, restated).
- **Persona document malformed** → the repository layer's existing try/catch (Phase 38 §2 item 9, unchanged) silently yields an empty document, which produces zero authorized fields — same outcome as "document not found."
- **A future provider is added** → **this is the one risk not structurally eliminated by this design, only mitigated by architecture**: any *future* code that bypasses the single authorizer and reads the master document directly would reintroduce the risk. The design's answer is architectural discipline (only one code path is permitted to read `PersonaMasterDocuments` for production purposes) rather than a technical impossibility — **DERIVED DESIGN**, with the caveat stated plainly rather than overclaimed.

**No duplicated security logic**: exactly one `canAccessField`-equivalent check exists in the new design, confirmed by the single-authorizer architecture itself (§1 step [5]) — there is structurally nowhere else for a duplicate check to live, since rendering (§13) operates only on already-authorized fields.

## 5. Contract Design

Re-evaluating Phase 44 §7-8's "plain data class, no state machine needed" conclusion **in light of this phase's new observability requirement (item 9)** — this is a genuine refinement, disclosed explicitly:

```kotlin
enum class PersonaContextStatus {
    USABLE,
    DOCUMENT_NOT_FOUND,
    DOCUMENT_EMPTY,
    REGISTRY_EMPTY,
    SKILL_MAPPING_MISSING,
    ZERO_FIELDS_SELECTED,
    RESOLVER_ERROR,
}

data class PersonaContextResult(
    val status: PersonaContextStatus,
    val renderedText: String,       // "" for every non-USABLE status
    val includedFieldIds: List<String>,  // empty for every non-USABLE status
    val personaId: UUID,
    val personaVersion: UUID,
    val skillKey: String,
)

fun PersonaContextResult.isUsable(): Boolean = status == PersonaContextStatus.USABLE
```

**Why this refines, not merely restates, Phase 44's design**: Phase 44's `isUsable()` predicate (`requestedVariant == returnedVariant && includedFieldIds.isNotEmpty() && renderedText.isNotBlank()`) was a **derived** signal, reconstructed from data that had other purposes (a `variant` field whose primary job was describing *which selective strategy* was used). In the new architecture, there is no `variant` concept at all (§7 — only one canonical strategy exists), so that derivation mechanism no longer applies. **A first-class `status` field is simpler and more directly serves the new architecture's observability requirement (§9) than reconstructing a boolean from unrelated fields would be** — this is not adding a sealed hierarchy or state machine (still avoided, per D3's spirit — a plain enum plus a plain data class, no polymorphism, no behavior on the type beyond one predicate). — **DESIGN DECISION**, explicitly reconciling with (not contradicting) D3.

**Does the contract make invalid states hard to pass downstream?** Yes — `renderedText`/`includedFieldIds` are specified to always be empty for any non-`USABLE` status, so a caller that (incorrectly) skips the `isUsable()` check and uses `renderedText` directly for a non-`USABLE` result gets an empty string, not raw or partial content — this is a second, structural line of defense beyond D2's strict-substitution policy at the call site. — **DERIVED DESIGN**.

`personaId`/`personaVersion`/`skillKey` are included **because they are required for §9's observability** (logging which persona/skill a given resolution concerned) — not merely "might be useful," directly traceable to a named requirement (item 9). `diagnostics` (mentioned as a possible field in the instruction's own sketch) is **not** added as a separate field — the `status` enum plus the three identifier fields already cover every diagnostic need enumerated in §9 without a fifth, open-ended field whose contents aren't otherwise specified. — **DESIGN DECISION**.

## 6. `PARTIAL` — Re-Evaluated From First Principles

**Does production need a `PARTIAL` state?** **No — removed from the contract, per this phase's own direct evaluation.**

**Reasoning**: `PARTIAL` would only provide actionable behavior if the application did something *different* with it than with `USABLE` — per D2/D5 and this phase's own §3 table, the two locked, source-supported options are "use the content" (like `USABLE`) or "fall back" (like `NOT_USABLE`/any non-`USABLE` status). No third behavior has ever been specified by any prior phase or by this instruction. **A status with no distinct behavior is not a status the architecture needs to represent** — it is pure classification overhead. — **DERIVED DESIGN**, directly following the instruction's own stated test ("if it does not provide actionable behavior, remove it").

**If a minimum-viable-persona concept is wanted later**: it would need (a) a new data concept (a per-persona minimum-field declaration, not present anywhere in the schema today, confirmed unchanged across Phases 39-44) and (b) an owner for what "minimum viable" means for a given persona — **this is a content/product-authoring decision, not an engineering one**, since it depends on what a coherent persona actually requires (a judgment about character design, not code). — **HUMAN DECISION REQUIRED**, but explicitly **not blocking** — the architecture in §5 has no dependency on this ever being resolved.

## 7. Six Existing Providers — Re-Evaluated

**Do they represent genuine production strategies, or historical experiment variants?** Traced precisely, reusing Phase 44 §11's per-provider findings, now with a decisive conclusion:

| Provider | Genuine production strategy, or research-only? |
|---|---|
| `ResolvedPersonaContextProvider` (B/C) | **Research-only** — its "variant" concept and its coupling to the research resolver's fallback behavior (D1) make it structurally unsuitable for production as-is; its *underlying idea* (selective, structured retrieval) is the one production strategy retained, but reimplemented cleanly (§1-§2), not reused |
| `AgeOnlyLabeledScalarDiagnosticProvider` | **Research-only** — a diagnostic built specifically to isolate one experimental variable (Phase 20); its *rendering logic* (label one scalar) is retained as a rendering policy option (§13), not as a standalone provider |
| `LabeledScalarDiagnosticProvider` | **Research-only**, same reasoning — its rendering logic (label all scalars) becomes a second rendering policy option |
| `SelectiveRawContextProvider` (X) | **Research-only** — existed specifically to isolate structure from selection (Phase 10); raw/unstructured rendering has no identified production use case anywhere in Phases 33-45 |
| `FullRawContextProvider` (FR) | **Research-only** — deliberately unfiltered by design (Phase 10C); this is the *opposite* of what production's fail-closed authorization requires; never a production candidate |
| `StructuredFullDocumentContextProvider` (D) | **Research-only**, same reasoning as FR — deliberately includes everything, no selection |

**Conclusion**: **production needs exactly one canonical retrieval/authorization strategy** — the new `PersonaFieldAuthorizer` (§1-§2) — **not six, and not even a refactored version of any of the six.** The six existing providers remain, unmodified, as research artifacts (§12). — **DESIGN DECISION**, directly resolving the "genuine strategies vs. experiment variants" question the instruction poses, rather than leaving it open.

**This also resolves an open item from Phase 44** (§18 there: "FR/D placeholder-convention decision, HUMAN DECISION REQUIRED") — **that decision no longer exists**, because FR and D are not migrated to any production contract at all under this design; they remain exactly as they are, serving only their original research purpose. — **DERIVED DESIGN**, explicitly noted as dissolving a prior open item rather than silently dropping it.

## 8. Data Model / Authoring Lifecycle

```text
author persona          — EXISTS today (AdminPersonaRoutes.kt, Phase 36 §3) for PersonaCoreVersion.content only;
                           does NOT exist for structured, field-level Persona Context data
    ↓
create version           — EXISTS today (PersonaCoreVersionRepository), same scope limitation as above
    ↓
register fields          — DOES NOT EXIST — PersonaFieldRegistryRepository.create()/update() are only ever
                           called from PersonaContextPocSeed.kt (test-only) and the benchmark harness
                           (re-confirmed, Phase 36/40/44, unchanged in this phase's own re-grep)
    ↓
define skill requirements — DOES NOT EXIST, same reasoning, for SkillContextRequirementRepository
    ↓
define restricted fields  — DOES NOT EXIST as a production authoring capability — the concept
                           (sensitivity="restricted", allowedContexts) exists in the schema and is
                           correctly enforced by the authorization logic (§4) once data exists, but
                           nothing lets a real content author set these values today
    ↓
publish/version           — PARTIALLY EXISTS — PersonaCoreVersion publishing exists; an equivalent
                           "publish a Persona Context configuration" concept does not
    ↓
retrieve at runtime        — the subject of §1-§5 above; DESIGNED, not yet built
```

**What must be built, not implemented here**: an authoring mechanism for field registry entries, skill requirements, and restricted-field declarations, plus structured master-document content — for at least one real, non-benchmark persona. **No existing appropriate production mechanism was found for any of this** (re-confirmed, not assumed) — this remains **HUMAN DECISION REQUIRED** for *how* (migration data / admin tooling / another mechanism), consistent with Phase 44 §13, not resolved differently here since no new source evidence bears on it.

## 9. Observability

Minimum production diagnostics, none of which include persona content or restricted values:

| Field | Source | Why needed |
|---|---|---|
| `personaId` | `PersonaContextResult.personaId` | Correlates a resolution attempt to a specific persona |
| `personaVersion` | `PersonaContextResult.personaVersion` | Distinguishes behavior across persona revisions |
| `skillKey` | `PersonaContextResult.skillKey` | Identifies which skill's field requirements were applied |
| `status` | `PersonaContextResult.status` | The single most important diagnostic — directly answers "did this work, and if not, which of the seven defined ways did it fail" (§5's enum) |
| Selected field IDs | `PersonaContextResult.includedFieldIds` | Field IDs are not content — safe to log; confirms *which* facts were used without revealing their values |
| Requested field IDs (i.e., what the skill's requirement declared, before authorization) | Computed by the authorizer, not currently a `PersonaContextResult` field — **DESIGN DECISION**: log this separately at the authorizer's own call site, not on the result contract itself, since it is a debugging-only signal not needed by the context assembler's fallback logic | Enables computing "how many requested fields were excluded and why" without polluting the core contract |
| Restricted-field rejection count | Computed the same way — a count, not the field IDs or values themselves | Confirms the authorization boundary is active without ever logging what was rejected |
| Resolver/authorizer latency | Standard timing instrumentation around the `PersonaFieldAuthorizer` call, following the existing `STAGE_TIMINGS`-style pattern already used elsewhere in this codebase's pipeline (`context_assembly=71ms` etc., confirmed present in this program's own test logs throughout Phases 20-30) | Consistent with existing observability conventions — **DERIVED DESIGN**, pattern-matched from confirmed existing logging style, not invented from nothing |

**Explicitly never logged**: `renderedText`, any field value, any restricted-field content or even which specific restricted field was rejected (only a count) — **DESIGN DECISION**, directly satisfying the instruction's "do NOT log raw persona content or restricted values."

## 10. Testing Architecture

| Layer | Covers | Requires API calls? |
|---|---|---|
| Unit tests | Authorization predicate; every §3 status transition individually; the "restricted field excluded, not whole-request-failed" behavior; empty-registry, empty-document, malformed-document handling | No |
| Resolver tests | `PersonaFieldAuthorizer` end-to-end against a fixture persona, all 11 §3 conditions | No |
| Authorization tests | Restricted-field exclusion under every skill/mapping combination in §3's table | No |
| Restricted-field tests | Explicit assertion: no restricted field ID or value ever appears in `includedFieldIds`/`renderedText` for any unauthorized skill, across the full state matrix | No |
| Missing-data tests | `DOCUMENT_NOT_FOUND`, `REGISTRY_EMPTY`, `SKILL_MAPPING_MISSING` — each independently | No |
| Unknown-skill tests | Confirmed identical outcome to "skill mapping missing" (§3) | No |
| Malformed-data tests | Corrupt JSON row → `DOCUMENT_EMPTY`-equivalent outcome, not an exception | No |
| Integration tests | Full assembler → authorizer → renderer → final request, using the existing `interceptGeneration=true` zero-network pattern | No |
| Request-assembly tests | Confirm memory/profile/history blocks are unaffected by any Persona Context status (locality re-confirmed, Phase 37 §9) | No |
| Regression tests | `core.content` remains byte-identical as fallback in every non-`USABLE` case; existing State-A production behavior unchanged until this design is actually wired (§11 step 9) | No |
| Benchmark tests | **Explicitly separate track** — the existing Phase 14-30 benchmark suite continues testing the *research* resolver/providers, untouched; it is never repurposed to validate the *production* architecture, and the production test suite above never depends on benchmark-harness fixtures | No |

**Safety-correctness tests (all of the above) are structurally separated from model-behavior experiments (Phase 26/30's own track) — no test in this table requires a real model call, and no test in the existing behavioral suite is repurposed as a safety test.** — **DESIGN DECISION**, directly per instruction item 10's separation requirement.

## 11. Migration / Implementation Sequence

Ordered by actual source dependency, not by the instruction's illustrative example alone:

1. **Domain contracts** (§5) — `PersonaContextStatus`, `PersonaContextResult` — no dependencies.
2. **Field authorization logic** (§4) — depends on (1) for its return type; reuses the existing `canAccessField`-equivalent concept, no new dependency beyond existing repository interfaces.
3. **`PersonaFieldAuthorizer`** (§1-§2) — depends on (1)-(2); this is the new resolver-replacement, combining selection + authorization into one component.
4. **Rendering policy abstraction** (§13) — depends on (1) for the field-set shape it renders; independent of (2)-(3)'s internals, since it only consumes an already-authorized field set.
5. **Context-assembler fallback logic** (§3) — depends on (1) and (3); this is where `core.content` substitution actually happens.
6. **Observability instrumentation** (§9) — depends on (1)-(3); can be added incrementally, does not block (7).
7. **Tests** (§10) — depends on (1)-(6); written alongside, not strictly after, each step above in practice, but logically depends on all of them existing.
8. **Data-authoring mechanism** (§8) — **independent track**, no code dependency on (1)-(7); required only before real production data can exist, not before the code itself can be built and tested against fixtures.
9. **Production wiring** (`Application.kt`) — depends on (1)-(7) AND (8) (Blocker A) both being complete; explicitly the last step, matching Phase 34/36/44's consistent gating.
10. **Benchmark/regression validation against the NEW architecture specifically** — a final pass confirming the new production path behaves per §3's table under `interceptGeneration=true`, run once before (9), not conflated with the existing Phase 14-30 benchmark suite (which validates the *research* path and needs no re-validation from this work).

**Because backward compatibility is not required, no compatibility-shim step exists in this sequence** — confirmed, per instruction, the cleanest order is a direct build-and-replace, not a parallel-shim-then-migrate approach, since there is no live production traffic to shim for. — **DESIGN DECISION**.

## 12. Research vs. Production Boundary

| Component | Research/benchmark | Production |
|---|---|---|
| `PersonaContextResolver.kt` (incl. `resolveVariantA()`) | **Frozen, unchanged** — continues to back Phases 14-30's benchmark harness exactly as today | Not used |
| `ResolvedPersonaContextProvider.kt` | **Frozen, unchanged** | Not used |
| `AgeOnlyLabeledScalarDiagnosticProvider.kt` | **Frozen, unchanged** | Not used directly — its rendering *logic* is reimplemented as a production rendering-policy option (§13), not reused as a class |
| `LabeledScalarDiagnosticProvider.kt` | **Frozen, unchanged** | Same as above |
| `SelectiveRawContextProvider.kt` | **Frozen, unchanged** | Not used |
| `FullRawContextProvider.kt` | **Frozen, unchanged** | Not used |
| `StructuredFullDocumentContextProvider.kt` | **Frozen, unchanged** | Not used |
| `PersonaContextRenderer.kt` | **Frozen, unchanged** (continues serving the research resolver) | Not directly reused — a new, separate production renderer is built (§13), since the production renderer must be policy-parameterized in a way the research renderer was never designed for; duplicating a ~100-line rendering function is a smaller cost than coupling production rendering to a class whose contract is owned by the research track |
| `PersonaContextPocSeed.kt` | **Frozen, unchanged** — remains the benchmark fixture source | Not used — production data comes from the new authoring mechanism (§8), never from this file |
| `PersonaFieldAuthorizer` (new) | N/A | **Production-only**, new |
| `PersonaContextStatus`/`PersonaContextResult` (new) | N/A | **Production-only**, new |
| Rendering-policy abstraction (new) | N/A | **Production-only**, new, though its two initial policy implementations (bare, age-labeled) are *behaviorally* modeled on the research classes' logic |
| `RepositoryContextAssembler.kt` | Used by both today (shared class) | **Modified** — gains the new fallback logic (§3); this is the one existing file both tracks share, and the only one where a change to it could theoretically affect research-harness behavior — mitigated by the fact that the research harness's own `personaContextProvider` continues to be supplied the same way it is today (a non-null provider explicitly constructed per test), so the new fallback branch is simply never exercised by existing research tests unless they are updated to exercise it deliberately |
| `PersonaContextPocBenchmarkRunner.kt`, `PersonaContextPocCorpusV2/V3/V4.kt`, `PersonaContextRealBenchmarkTest.kt` | **Frozen, unchanged** — continue exercising the research path exactly as before | Not used |

**This table makes explicit what Phase 44 only partially separated**: nearly every existing Persona Context class is research-only under this design, and production is built almost entirely from new components, sharing only `RepositoryContextAssembler.kt` (and only its fallback branch, additively). — **DESIGN DECISION**.

## 13. LA Decision Remains Separate

**No conclusion is drawn that LA should ship.** The architecture is designed so that rendering policy is a pluggable, swappable parameter, independent of retrieval/authorization:

```kotlin
interface ScalarRenderingPolicy {
    fun renderScalar(fieldId: String, value: JsonElement): String
}

object BareScalarRendering : ScalarRenderingPolicy { /* mirrors today's B behavior */ }
object AgeOnlyLabeledScalarRendering : ScalarRenderingPolicy { /* mirrors LA */ }
object AllScalarsLabeledRendering : ScalarRenderingPolicy { /* mirrors L */ }
```

The `PersonaFieldAuthorizer` (§1-§2) produces an authorized field set **with no knowledge of which rendering policy will be applied to it** — rendering happens strictly after authorization, as a separate step (§1 step [8]). **Switching from bare rendering to age-only-labeled rendering (or any future policy) requires changing only which `ScalarRenderingPolicy` instance is passed to the renderer — zero changes to retrieval, authorization, or the fallback contract.** This is the direct, concrete architectural answer to item 13: the system supports baseline rendering, age-only labeling, broader scalar labeling, or any future strategy, as pure configuration. — **DESIGN DECISION**, and this is as far as this document goes — **it does not select which policy production should launch with**; that remains gated by the still-unmet Phase 23/25/28/31 evidence boundary, unchanged by anything in this phase.

## 14. Human Decisions — Only Genuine Remaining Items

1. **Skill-timing resolution strategy** (§1): accept that a first Persona-Context-resolution pass can only use a placeholder skill key (since skill selection happens after context assembly today), or invest in wiring a second, post-skill-selection pass (finally activating the concept `PersonaContextResolutionStage` was meant for, rebuilt cleanly). **Why real**: this is a pipeline-ordering trade-off with real latency/complexity cost on one side and reduced selection precision on the other — not resolvable from source alone. **Choices**: (a) single-pass, placeholder-skill only; (b) two-pass, true skill-aware. **Consequence**: (a) is simpler, ships sooner, but Persona Context selection is only ever as good as "general_chat"-style generic requirements; (b) is more architecturally correct but requires solving the timing/plumbing gap this program has flagged, unresolved, since Phase 35.

2. **Data-authoring mechanism** (§8): migration data, admin tooling, or another mechanism for populating field registry / skill requirements / restricted-field declarations / structured master documents for real personas. **Why real**: no existing production mechanism does this, and building one is itself a product-scoped engineering investment (UI, workflow, validation rules) this document cannot size or select without input on how personas will actually be authored going forward. **Choices**: (a) admin UI extension; (b) structured migration/config files per persona; (c) another mechanism. **Consequence**: (a) is more usable for non-engineers authoring personas but costs more UI engineering; (b) is faster to build but requires engineering involvement for every persona change; (c) unspecified.

3. **Minimum-viable-persona definition, if ever wanted** (§6): whether to define one at all, and if so, what it requires. **Why real**: this is a character-design/content judgment (what makes a persona "complete enough"), not an engineering fact — confirmed **not currently needed** by the architecture (§6), so this is a low-urgency decision, not a blocking one. **Choices**: (a) never define one, accept any non-empty selection as usable indefinitely; (b) define one later, owned by whoever owns persona-content design. **Consequence**: (a) requires no further work; (b) requires a future, separately-scoped design pass.

4. **Which rendering policy to launch with, if/when Persona Context ships** (§13): bare (B-equivalent), age-only-labeled (LA-equivalent), or another policy. **Why real**: this is exactly the evidence question Phase 23/25/28/31 left unmet, not a fact this architecture phase can or should resolve — deliberately kept separate, per instruction. **Choices**: as listed; also "defer this decision entirely and launch with bare rendering, revisit later." **Consequence**: each choice inherits the exact evidentiary status Phase 31 already documented (bare = the status quo baseline; LA = evidenced on two scenario types but short of the "more than two" boundary) — this document adds nothing new to that evidentiary picture, only notes the architecture supports switching later at negligible engineering cost.

**No other item in this document requires human input** — every other question posed by the instruction (resolver architecture choice, contract shape, PARTIAL, provider consolidation, fallback semantics, security boundary, sequencing) has a concrete, source-and-instruction-supported answer given directly in §1-§12, per instruction's own "do not ask me to choose implementation details that can reasonably be determined from the source."

---

## 15. Deliverable Confirmation & Implementation Readiness

**API calls: 0. Code changes: 0. Database changes: 0. Production wiring: 0. Behavioral experiment: 0.** No Phase 10–44 historical artifact was modified.

### Is the architecture implementation-ready?

**Yes, for the core safety/retrieval/authorization/rendering-policy architecture (§1-§5, §7, §9-§13)** — every design element there has a concrete Kotlin-level shape, a clear file/component boundary, and a specified dependency order (§11), sufficient to begin coding without another architecture-audit phase for *that* scope.

**Not fully ready for end-to-end production activation**, because four items remain genuinely open (§14) — but **none of the four blocks starting implementation of steps 1-7 of §11's sequence** (domain contracts, authorization, the new resolver-replacement, rendering policy, fallback logic, observability, tests) — those can all be built and fully tested (against fixtures, zero API calls) without resolving any of §14's four items. Only step 8 (data authoring) and step 9 (production wiring) are genuinely blocked pending §14.

**Recommendation for what comes next, stated as a fact about scope, not a production recommendation**: the next phase can be **implementation** of §11 steps 1-7, in order — not another architecture-analysis loop. Steps 8-9 remain gated on §14's decisions, to be addressed on their own timeline.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE45_PRODUCTION_ARCHITECTURE_FINAL.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

## Explicit confirmation

Zero API calls were made. Zero code, database, or production changes were made. No historical artifact (Phase 10 through Phase 44) was modified. No behavioral experiment was run. No claim is made that LA should be shipped — that question remains separate and unresolved, gated by the Phase 23/25/28/31 boundary, unchanged by this document.
