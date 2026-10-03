# Phase 38 — Production Fallback + Restricted-Field Safety Specification

**Zero API calls. Zero production code, database, migration, mapping, renderer, or provider changes. Zero Persona Context data population.** This is a specification/audit document only, turning Phase 37's two unresolved findings into a precise, implementation-ready spec. No historical Phase 10–37 artifact was modified. No behavioral experiment is run. The Phase 23/25 boundary is not reinterpreted. No production recommendation is made. LA is not declared production-ready.

---

## 1. Objective

Specify, precisely and only from source evidence: (1) what should happen when Persona Context data is missing or partially populated, and (2) how restricted fields must be prevented from reaching the model when the resolver falls back or encounters incomplete skill mappings. Where source evidence does not establish an answer, this document marks the question unresolved rather than inventing one.

## 2. Source Trace

Answering Part 1's nine questions precisely, extending (not re-deriving) Phase 33–37's own tracing:

1. **Where is `PersonaCoreVersions.content` obtained?** `PersonaRepository.getActiveCoreVersion(personaId)` inside `RepositoryContextAssembler.assemble()`, called unconditionally for every request, before any Persona Context logic runs.
2. **Where is Persona Context obtained?** `personaContextProvider.getPersonaContext(request.personaId, core.id, pinnedSkillKey ?: "general_chat", request.content)` — called only if `personaContextProvider != null` (never true in production today).
3. **Where can a null/blank Persona Context result replace or bypass the current baseline?** Exactly one place: the `if (personaContextProvider != null) {...} else { core.content }` ternary in `RepositoryContextAssembler.assemble()`. Today, a non-null provider's result — blank or not — is used verbatim, with no fallback to `core.content` once the `if` branch is taken.
4. **Where can a master document be serialized?** Two places: `PersonaContextRenderer.render(fieldValueMap)` (filtered, selective — the B/C path) and `resolveVariantA()`'s `masterDoc.contentJson.toString()` (raw, unfiltered — the A path and the fallback target of both missing-document and missing-skill-mapping conditions).
5. **Where are skill mappings consulted?** `SkillContextRequirementRepository.findBySkillKey(skillKey)`, called once per `resolve()` invocation, for variants B/C only (never for variant A, which skips this entirely).
6. **Where are restricted fields identified?** `PersonaFieldRegistryEntry.sensitivity` (`"restricted"` for exactly two entries today: `intimate_anatomy`, `adult_intimacy` — confirmed by direct grep of `PersonaContextPocSeed.kt`; no `"private"`-sensitivity entries exist in the current registry) and `allowedContexts` (`["romantic_intimacy", "foreplay"]` for both).
7. **Where, if anywhere, are restricted fields filtered before model serialization?** Exactly one place: `PersonaContextResolver.canAccessField(fieldId, skillKey, fieldRegistry)`, called only inside the required/recommended/conditional selection loops of `resolve()`'s **non-A** branch. **`resolveVariantA()` calls this function zero times** — confirmed by direct re-reading; `resolveVariantA()` contains no reference to `canAccessField` at all.
8. **Which component has enough information to safely decide whether Persona Context is complete enough to use?** `PersonaContextResolver.resolve()` itself has all the raw inputs (master document presence, registry completeness, skill-mapping presence) but, per Phase 37, lacks access to `core.content` to fall back to. `RepositoryContextAssembler.assemble()` has `core.content` but currently receives only the resolver's final string, with no structured signal about *why* that string is what it is (blank, partial, or fully raw) — it cannot today distinguish "provider intentionally returned an empty string because no persona is configured" from "provider returned an empty string because of a bug." **No single component today has both the completeness signal and the fallback content simultaneously** — this is the structural reason Phase 37 recommended a blank-string heuristic rather than a more precise completeness check.
9. **Which component has access to the baseline `core.content` needed for safe fallback?** Only `RepositoryContextAssembler.assemble()` — confirmed unchanged from Phase 37 §4/§6.

## 3. Current-State Matrix (component responsibility, not data states — see §4 for data states)

| Question | Component | Current capability |
|---|---|---|
| Detects missing master document | `PersonaContextResolver.resolve()` | Yes — via `?: return resolveVariantA(...)` |
| Detects missing skill mapping | `PersonaContextResolver.resolve()` | Yes — via `?: return resolveVariantA(...)`, **indistinguishable in code from "unknown skill key"** (§4, states 11-12) |
| Detects empty registry | Not detected explicitly | `canAccessField()` simply returns `false` per field; no code path counts registry size or flags "registry is empty" as a distinct condition |
| Detects restricted-field presence in a raw document | Not detected at all in `resolveVariantA()` — no filtering logic exists there | N/A |
| Has access to `core.content` for fallback | `RepositoryContextAssembler.assemble()` only | Confirmed, Phase 37 §4 |
| Has access to completeness/safety signal | No component today — see §2 item 8 | Structural gap |

## 4. Data-State Matrix

All 15 states, traced precisely from source (not from prior phase summaries, though consistent with them):

| # | State | What happens today (traced) | Provider result | Restricted data reachable? | Baseline preserved? | Deterministic? | Desired behavior established? |
|---|---|---|---|---|---|---|---|
| 1 | No master document | `resolve()` → `resolveVariantA()` → its own lookup also misses → `rawText = "" ` | Blank (`""`) | No (nothing to leak) | **No** — blank replaces `core.content`, not equal to it | Yes | **Established** (should preserve baseline — Phase 37 Invariant 1) |
| 2 | Empty master document (`contentJson = {}`) | **Path-dependent — a precise, previously-unstated nuance**: via the normal B/C path (document present, so no early fallback), `resolvePath` returns `null` for every field, `fieldValueMap` stays empty, `PersonaContextRenderer.render({})` returns `""` (its own explicit empty-map guard). **Via the `resolveVariantA()` fallback path** (e.g., reached because skill mapping is missing), `masterDoc.contentJson.toString()` on an empty `JsonObject` returns the literal two-character string `"{}"` — **not blank** | `""` (B/C path) **or** `"{}"` (A-fallback path) — **these differ**, and a naive `isBlank()` check (Phase 37's proposed fix) catches the first but **not** the second | No (empty document, nothing to leak either way) | No, in both sub-cases — `""` is not `core.content`, and `"{}"` is a useless, non-`core.content` string that would still be sent to the model | Yes | **Established** for intent (should preserve baseline); **not established** for exact implementation (an `isBlank()` check alone is insufficient — see §5 Invariant 1 refinement) |
| 3 | Valid document + empty registry | `canAccessField()` returns `false` universally → `selectedFieldIds` stays empty → `render({})` → `""` | Blank | No | No | Yes | Established (should preserve baseline, same as state 1) |
| 4 | Valid document + no skill mappings | `findBySkillKey()` returns `null` → `resolveVariantA()` → document **exists** → returns full raw `contentJson.toString()` | **Full raw document**, unfiltered | **Yes, if a restricted field is present in the document** — no `canAccessField()` gating in this path at all | No — this is neither blank nor `core.content`; it is an entirely different, uncontrolled text | Yes (deterministic, but deterministically unsafe) | **Established that this is unsafe** (Phase 37 §8); **not established** what the correct alternative behavior is (§5 Invariant 2 options) |
| 5 | Valid document + partial registry | Only registered field IDs can ever be selected (`fieldRegistry[fieldId] ?: return@forEach` / `?: return false`); unregistered fields referenced by a skill mapping are silently skipped | Non-empty but genuinely partial | Only registered restricted fields could ever surface, and only if also mapped+permitted — no new exposure vector beyond state 14 | Not applicable (Persona Context, not baseline, is the intended output when data exists) | Yes | **Not established** — whether partial-but-present content should be used as-is, or treated as "incomplete, fall back" (Phase 37 §11/§13, unresolved) |
| 6 | Valid document + partial skill mappings (some skills mapped, others not) | Per-request, skill-specific: mapped skills behave per state 5/8; unmapped skills behave per state 4 | Varies by which skill is active for that request | Yes, for requests routed to an unmapped skill, if the document contains a restricted field | No, for unmapped-skill requests | Yes | Same as states 4 and 5, combined — the risk is per-skill, not global |
| 7 | Valid document + complete registry + partial skill mappings | Same as state 6, with the registry no longer a limiting factor for mapped skills | Same as state 6 | Same as state 6 | Same as state 6 | Yes | Same as states 4/5 |
| 8 | Valid document + complete registry + complete relevant skill mappings | The **only** state this system was designed for and behaviorally validated on (Phases 14–30, using the POC's own Simran fixture) | Full, selective, structured Persona Context | No — `canAccessField()`'s `allowedContexts` gating is exercised correctly here, confirmed by 0 leakage across hundreds of calls throughout this program | Not applicable (Persona Context is the intended, non-fallback output) | Yes | **Established** — this is the validated, intended state |
| 9 | Invalid/malformed master document (corrupt JSON in the DB row) | `PersonaMasterRepository.rowToModel()`'s own `try/catch` silently converts a parse failure into `JsonObject(emptyMap())` — confirmed by direct reading of that method | Behaves identically to state 2's B/C-path sub-case (`""`) — never reaches state 2's `"{}"`-fallback sub-case unless routed through `resolveVariantA()`, in which case it WOULD produce `"{}"`, per state 2's own path-dependent finding | No | No | Yes | Established for the same reason as state 1; the silent swallow-and-treat-as-empty behavior itself is a general data-integrity concern not scoped to Persona Context specifically, noted but not further analyzed here |
| 10 | Invalid/missing persona ID | Caught earlier, in `RepositoryContextAssembler.assemble()`'s pre-existing `personaRepository.getActiveCoreVersion(...) ?: return StageResult.Failed(VALIDATION_FAILED)` — Persona Context resolution is never attempted | N/A — request fails before this stage | No | N/A (request fails cleanly) | Yes | **Established** — this is correct, pre-existing, unrelated behavior (Phase 36 §7) |
| 11 | Unknown skill key | `findBySkillKey(skillKey)` returns `null` — **the code cannot distinguish "unknown to the whole system" from "known to production but simply not yet mapped" (state 12); both are the identical code path** | Same as state 4 if a document exists, state 1 if not | Same as state 4 | Same as state 4/1 | Yes | Same as state 4 — unsafe if a document exists, established-safe if not |
| 12 | Known skill key with no mapping | **Identical to state 11** — this is not a separate code path, only a separate way of describing the same input to `findBySkillKey()` | Same as state 11 | Same as state 11 | Same as state 11 | Yes | Same as state 11 |
| 13 | Restricted field present but not permitted for current skill | **Safe only if the normal B/C `resolve()` path is reached** (document AND matching skill mapping both present) — `canAccessField()` correctly excludes it there. **Unsafe if `resolveVariantA()` is reached instead** (state 4/6/7/11/12) — no filtering exists in that function at all | Varies — safe in the B/C path, unsafe in the A-fallback path | **Conditionally yes** — depends entirely on which code path is taken, not on this state's description alone | N/A | Yes | **Established that the B/C path is safe; established that the A-fallback path is not — the state's overall safety is therefore path-dependent, not a fixed property of "restricted field present"** |
| 14 | Restricted field present with valid mapping (skill IS `romantic_intimacy`/`foreplay`, field is required/recommended/conditional for it) | Selected and rendered normally, exactly like any other field — this is the intended, by-design behavior | Field included, correctly gated | Yes, by design and intent | N/A | Yes | **Established** — this is the system's designed, validated behavior |
| 15 | Multiple skills, some mapped some not | No cross-request contamination — each `resolve()` call is independent given fresh inputs; but in aggregate, **every** request routed to an unmapped skill independently hits state 4/11/12's risk if a document exists, scaling with the proportion of unmapped skills in real traffic | Varies per request | Yes, proportional to unmapped-skill request volume | No, for those requests | Yes, per-request | Same as states 4/11/12, stated as an aggregate/scaling concern rather than a new mechanism |

## 5. Baseline Fallback Analysis

**Meaning, per instruction**: "Persona Context cannot safely provide a usable persona block, so preserve today's `PersonaCoreVersions.content`."

**What source evidence establishes**: states 1, 3, 9 (and the B/C-path sub-case of state 2) all currently produce a genuinely blank (`""`) result, and Phase 37 already established that this should instead equal `core.content` — **this much is established**, not merely proposed.

**What is newly refined in this phase**: Phase 37's proposed `isBlank()`-style check is **necessary but not sufficient**, per state 2's path-dependent finding — a `"{}"` result (reachable specifically via the `resolveVariantA()` fallback on an empty-but-present document) is non-blank text and would pass an `isBlank()` check unmodified, while being just as useless as an empty string. **A more precise completeness signal than "is the final string blank" is required to fully satisfy Invariant 1** — for example, checking `includedFieldIds.isEmpty()` (a structural signal already computed by `resolve()` and present on `PersonaContextProjection`, though not currently exposed through `ResolvedPersonaContextProvider.getPersonaContext()`'s `String`-only return type) would catch both the blank and the `"{}"` cases uniformly, since both correspond to zero fields having been genuinely selected. **This is a specification refinement, not an implementation** — no code is changed in this phase.

## 6. Partial-Data Analysis

**Meaning, per instruction**: "Some Persona Context data is valid, but some required information is missing."

**Direct answer, per states 5, 6, 7**: **the existing architecture does not establish whether partial data should be used as-is, fall back completely, merge with baseline, or be rejected.** No design document, code comment, test, or architectural decision anywhere in Phases 10–37 addresses this question directly — Phase 37 §11/§13 already flagged this as unresolved, and this phase's deeper trace does not surface a different conclusion. **This is marked unresolved, per instruction, rather than resolved by invention.** The one relevant, but not decisive, observation: the required/recommended/conditional tiering mechanism itself (`resolve()`'s own three-pass structure) is *structurally* tolerant of partial registration — it does not error or refuse to proceed if some fields are unregistered, it simply omits them — which is consistent with, but does not prove, an intended "use whatever is available" philosophy.

## 7. Restricted-Field Lifecycle

Tracing `intimate_anatomy` and `adult_intimacy` (the only two restricted fields in the current registry — confirmed, §2 item 6) precisely:

1. **Where it exists in the data model**: as top-level keys in the persona master document's JSON (per the POC's Simran fixture), and as `PersonaFieldRegistryEntry` rows with `sensitivity = "restricted"`, `allowedContexts = ["romantic_intimacy", "foreplay"]`.
2. **Whether it can be selected**: only within `resolve()`'s non-A branch, and only if a `SkillContextRequirement` for the active skill explicitly lists the field in `requiredFields`/`recommendedFields`/`conditionalFields` (confirmed: only `romantic_intimacy` and `foreplay`'s POC-seeded requirements ever do this).
3. **Whether it can be serialized**: yes, in two distinct ways — (a) selectively, via `PersonaContextRenderer.render()`, only if selection (2) succeeded; (b) wholesale, via `resolveVariantA()`, with **no selection check of any kind**, whenever that function is reached and the master document contains the field.
4. **Whether skill mappings gate it**: yes, for path (3a); **no gating exists at all** for path (3b).
5. **Whether unmapped skills can cause it to bypass gating**: **yes — this is precisely state 4/11/12's mechanism.** An unmapped skill key causes `resolve()` to call `resolveVariantA()`, which has no concept of "skill" or "restriction" at all once invoked.
6. **Whether the raw-document fallback can expose it**: **yes, confirmed directly** — `resolveVariantA()`'s `masterDoc.contentJson.toString()` includes every top-level key in the document, restricted or not, with no filter.
7. **Whether production currently has any path capable of reaching it**: **no, not today** — because no master document exists in production (Phase 36 §6), `resolveVariantA()` always hits its own empty-lookup branch (`rawText = ""`) rather than ever serializing real content, restricted or otherwise. **This protection is incidental (absence of data), not architectural (absence of a filtering mechanism)** — the moment Blocker A work populates any master document before every skill's mapping is complete, this incidental protection disappears.
8. **What would change once Persona Context data starts being populated**: the instant a master document exists for a persona, **every skill key without a corresponding `SkillContextRequirement` row becomes a live path to full, unfiltered document exposure**, including any restricted field the document contains — this is not a hypothetical, it is the direct, mechanical consequence of the code as traced in items 5-6 above.

## 8. Safety Invariants

Per instruction, derived only from source evidence, not invented:

- **Invariant 1 — Baseline preservation**: **Established as a goal** (Phase 37, refined §5 above): `finalPersonaContent` should equal `PersonaCoreVersions.content` whenever Persona Context cannot produce genuine, field-level-selected content — refined in this phase to require a structural "zero fields selected" signal, not merely a blank-string check, to correctly cover the `"{}"` edge case (state 2).
- **Invariant 2 — No raw-document bypass**: **Established as a requirement, not yet established as a specific fix**: no execution path should serialize the complete raw master document merely because a skill mapping is missing (states 4/6/7/11/12). Two candidate fixes were already surfaced in Phase 37 §11/§13 (filter `resolveVariantA()` itself, or redirect the missing-skill-mapping fallback away from it) — **this phase does not choose between them**, since source evidence does not establish a preference; both are logically valid, with different trade-offs (Phase 37's own analysis: filtering `resolveVariantA()` would also affect its *deliberate*, historically-frozen research use in Phases 6/7/9/10A).
- **Invariant 3 — Restricted-field exclusion**: **Established for the B/C selective path** (0 leakage confirmed empirically, Phases 10C-30) — **not established, and currently violated in principle, for the `resolveVariantA()` fallback path** (§7 items 3b, 6).
- **Invariant 4 — Unknown-skill safety**: **Not established as safe today.** An unknown/unmapped skill key currently *does* cause broader persona data exposure risk, specifically via the Invariant-2 mechanism — this invariant, as stated in the instruction, is **not currently satisfied** by the codebase, and closing it is equivalent to closing Invariant 2.
- **Invariant 5 — Partial-data safety**: **Explicitly unresolved**, per §6 — no rule is created here.

## 9. Implementation Boundaries

For each proposed change, without implementing any of them:

### 1. Baseline fallback (Invariant 1)

- **Component/file**: `RepositoryContextAssembler.kt` (per Phase 37 §6-7, unchanged conclusion).
- **Current responsibility**: choose between provider output and `core.content` based only on provider nullity.
- **Proposed responsibility**: additionally treat "provider selected zero fields" (not merely "provider returned a blank string") as equivalent to a null provider.
- **Dependencies required**: `ResolvedPersonaContextProvider.getPersonaContext()`'s return type would need to expose the `includedFieldIds.isEmpty()` signal somehow (today it discards `PersonaContextProjection` and returns only `renderedText: String`) — **this is a larger interface change than Phase 37's original blank-string proposal**, since it touches the `PersonaContextProvider` interface's contract, not only `RepositoryContextAssembler`'s call site. Alternatively, a narrower fix could check both `isBlank()` and a hardcoded `"{}"`/`"{ }"` literal-equality check as a pragmatic (if less general) stand-in — this phase surfaces both options without choosing.
- **Additive or modifying**: modifying (changes an existing conditional's behavior), though narrowly scoped.
- **Potential regression surface**: any currently-passing test that relies on a non-blank-but-structurally-empty provider result being used as-is (none identified in this program's own test suite, but not exhaustively verified here — a regression pass would need to check this).
- **Tests required**: per §10's matrix, states 1, 2, 3, 9.
- **Affects current production behavior before Persona Context is enabled?** **No** — `personaContextProvider` is null in production today, so this branch is never taken regardless of this change.

### 2. Raw-document/restricted-field protection (Invariants 2-4)

- **Component/file**: `PersonaContextResolver.kt`'s `resolveVariantA()`, or the call sites that route to it as a fallback (the two `?: return resolveVariantA(...)` lines).
- **Current responsibility**: serve as both (a) the deliberate, historically-frozen "variant A" research condition and (b) the fallback target for missing-document and missing-skill-mapping conditions.
- **Proposed responsibility**: **not established which of the two candidate approaches is correct** (§8 Invariant 2) — either add `canAccessField()`-style filtering to `resolveVariantA()` itself (affecting both uses (a) and (b) identically), or introduce a **separate** fallback function for use (b) only, leaving (a) frozen and untouched.
- **Dependencies required**: if a separate fallback function is chosen, it would need the same `fieldRegistry`/`canAccessField()` access `resolve()`'s own non-A branch already has — no new dependency beyond what `PersonaContextResolver` already holds.
- **Additive or modifying**: the separate-function approach is additive (new function, existing `resolveVariantA()` untouched, preserving its frozen research role); the shared-function approach is modifying (changes `resolveVariantA()`'s behavior for both its historical and fallback roles simultaneously).
- **Potential regression surface**: the shared-function approach risks affecting Phase 6/7/9/10A's own historical, already-published "variant A" characterization if that provider were ever re-run for comparison purposes — the separate-function approach avoids this entirely.
- **Tests required**: per §10's matrix, states 4, 6, 7, 11, 12, 13.
- **Affects current production behavior before Persona Context is enabled?** **No**, for the same reason as fix 1 — this code path is never reached while `personaContextProvider` is null.

### 3. Partial-data handling (Invariant 5)

- **Component/file**: undetermined — **this phase does not specify an implementation boundary for an unresolved requirement**, per instruction ("do not invent desired behavior where source evidence is absent"). Once a decision is made (§12), the boundary would likely be `PersonaContextResolver.resolve()` itself (the only component with visibility into which fields were actually selected vs. requested).
- **Everything else**: not specified, pending the human/engineering decision flagged in §11/§12.

### 4. Unknown-skill handling

- **Component/file**: same as fix 2 — this is not a separate mechanism from Invariant 2/4, per §4 states 11-12's finding that "unknown" and "known-but-unmapped" are code-identical. No separate implementation boundary exists for this beyond fix 2.

## 10. Deterministic Test Matrix

All tests use the existing zero-API-cost patterns proven since Phase 10C (`interceptGeneration=true` where a full pipeline run is needed; direct resolver/provider calls where it is not). **No test in this matrix requires a real model/API call.**

| # | Input state | Expected behavior if established | Unresolved if not | Component under test | Real API call required? |
|---|---|---|---|---|---|
| 1 | No Persona Context data (no document) | `finalPersonaContent == core.content` (Invariant 1) | — | `RepositoryContextAssembler` (post-fix) | No |
| 2 | Empty document (`{}`), reached via B/C path | `finalPersonaContent == core.content` | — | `RepositoryContextAssembler` + `PersonaContextRenderer` | No |
| 2b | Empty document (`{}`), reached via missing-skill-mapping fallback | `finalPersonaContent == core.content` (requires the §5 refinement, not just `isBlank()`) | — | `RepositoryContextAssembler` + `PersonaContextResolver` | No |
| 3 | Missing registry | `finalPersonaContent == core.content` | — | `PersonaContextResolver` + `RepositoryContextAssembler` | No |
| 4 | Missing skill mapping (document present) | No restricted field present in output; **exact non-restricted content unresolved pending Invariant-2 decision** | **Yes — which non-restricted fallback content is correct is unresolved (§8)** | `PersonaContextResolver` | No |
| 5 | Partial mapping (some fields registered/mapped, not all) | **Unresolved (§6)** | **Yes** | `PersonaContextResolver` | No |
| 6 | Complete mapping (state 8) | Full, selective Persona Context, matching Phase 14-30's already-validated behavior | — | Full pipeline (existing pattern) | No |
| 7 | Unknown skill | Identical to test 4 (states 11-12 are code-identical) | Same as test 4 | `PersonaContextResolver` | No |
| 8 | Restricted field present, unmapped skill | **No restricted-field string should appear in output** (Invariant 3/4) | — (this specific check IS establishable, even though the general fallback content is not) | `PersonaContextResolver` | No |
| 9 | Restricted field present, mapped skill (`romantic_intimacy`/`foreplay`) | Field included, exactly as Phases 10C-30 already validate | — | `PersonaContextResolver` (regression, already covered) | No |
| 10 | Multiple skills, mixed mapping | Each request's outcome matches its own skill's individual state (4/6/7/8/11/12), independently | — | `PersonaContextResolver`, run per skill | No |
| 11 | Malformed data (corrupt JSON row) | Same as test 1/2 (silently becomes empty document per `rowToModel()`'s try/catch) | — | `PersonaMasterRepository` + `PersonaContextResolver` | No |
| 12 | Invalid persona ID | Existing `VALIDATION_FAILED` behavior, unrelated to Persona Context (already covered by existing, non-Persona-Context tests, not re-specified here) | — | `RepositoryContextAssembler` (pre-existing) | No |
| 13 | Baseline fallback verification (general) | `finalPersonaContent` is byte-for-byte equal to `core.content` in every fallback case, not merely "non-empty" | — | `RepositoryContextAssembler` | No |

## 11. Production Activation Dependency Chain

| Category | Items |
|---|---|
| **Must be fixed** | Invariant 1 implementation (baseline fallback, including the `"{}"` edge case refinement); Invariant 2/3/4 implementation (raw-document bypass / restricted-field protection for the fallback path) |
| **Must be decided** | Which Invariant-2 approach (shared vs. separate fallback function); Invariant 5's behavior for partial data (use-as-is / fall back / merge / reject — genuinely undecided by any existing source); whether `resolveVariantA()`'s historical research role should ever be allowed to diverge from its production-fallback role, or whether they must remain textually identical |
| **Must be tested** | The full matrix in §10, plus Phase 34/36's own "required before A→B" test items (production-wired equivalence tests, broader regression corpus, restricted-field validation against real skill keys) — not repeated in full here, only referenced |
| **Not required for initial activation** | Resolving the LA-vs-L question, the 27−24 alternative, magnitude-stability questions, or any other item from the B-vs-LA behavioral research track (Phases 14-32) — none of these bear on whether Persona Context can be safely activated at all, only on whether a *labeling* refinement on top of it would help; **this document does not conflate the two tracks** |

## 12. Established vs. Unresolved Decisions

Answering Part 9's six questions, kept explicitly separate, per instruction:

1. **Is there enough evidence to implement the baseline fallback?** **Partially.** The *goal* (Invariant 1) is established. The *exact* implementation needs the §5 refinement (a structural "zero fields selected" signal, not a bare `isBlank()` check) to be fully correct — this phase specifies that refinement but does not implement it, and a decision on *how* to expose that signal through the `PersonaContextProvider` interface (§9 fix 1) is still needed.
2. **Is there enough evidence to implement restricted-field protection (for the fallback path)?** **Partially.** The *problem* is fully established and traced (§7). The *fix approach* (shared vs. separate fallback function, §8 Invariant 2) is not decided by source evidence — this is a genuine engineering trade-off, not a fact.
3. **Is there enough evidence to define partial-data behavior?** **No.** §6 is explicitly unresolved; no source establishes an intended answer.
4. **Is there enough evidence to populate production Persona Context data?** **No** — this is Blocker A from Phase 36, entirely unaddressed by this phase (out of scope, per instruction: "no Persona Context data population").
5. **Is there enough evidence to wire Persona Context into production?** **No** — this is Blocker C from Phase 36/34, and is additionally gated on Blockers A and B (including this phase's own newly-specified refinements) being resolved first, per the dependency ordering in §11.
6. **Is there enough evidence to declare LA production-ready?** **No** — LA is not addressed by this phase at all (it operates entirely downstream of the questions this phase specifies), and this phase does not declare it production-ready, per instruction.

**These six answers are not collapsed into a single readiness verdict — each stands on its own.**

## 13. Exact Remaining Blockers

- Blocker A (data availability) — unchanged from Phase 36, out of this phase's scope.
- Blocker B (resolver safety) — **specified further, not resolved**: the exact completeness signal (§5) and the Invariant-2 fix approach (§8) are both now precisely characterized as open engineering decisions, narrower than Phase 37 left them, but still open.
- Blocker C (production integration) — unchanged from Phase 34/36, additionally gated on B's refinements.
- The Invariant-5 partial-data question — a **new**, explicitly-named blocker this phase isolates cleanly for the first time as a decision item in its own right (previously bundled loosely into "resolver safety" by Phase 37).

## 14. What Phase 38 Does NOT Establish

- Which of the two Invariant-2 fix approaches (shared vs. separate fallback function) is correct.
- What partial-data behavior should be (Invariant 5) — explicitly and repeatedly marked unresolved throughout this document, not resolved here.
- Whether `SelectiveRawContextProvider`/`FullRawContextProvider` (the other raw-document-adjacent providers, outside the currently-proposed B-vs-LA path) have analogous unaudited risks — out of this phase's scope, as it was out of Phase 37's.
- Any causal, generalization, LA-vs-L, or 27−24 finding — entirely outside this phase's brief, unchanged from Phases 25-32.
- A production recommendation of any kind, for any of Path A/B/C from `PHASE32_ENGINEERING_DECISION_PACKAGE.md`.
- LA's production-readiness — not addressed, not declared.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE38_PRODUCTION_SAFETY_SPEC.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

## Key findings

1. Phase 37's proposed `isBlank()` fallback check is necessary but not sufficient — an empty-but-present master document (`{}`), reached via the `resolveVariantA()` fallback path specifically, produces the literal non-blank string `"{}"`, which would bypass a bare blank-string check. A structural "zero fields selected" signal is needed instead, which requires a small interface change (exposing `includedFieldIds` alongside `renderedText`) not previously scoped.
2. The raw-document-exposure risk (Phase 37 §8) is confirmed to have two viable, undecided fix approaches with different trade-offs — this document does not choose between them.
3. "Unknown skill key" and "known skill key with no mapping" are proven code-identical — the resolver has no way to distinguish them, which simplifies (rather than complicates) the required fix, since only one code path needs addressing for both.
4. The restricted-field protection mechanism (`canAccessField()`) is confirmed to be entirely absent from the `resolveVariantA()` code path — this is not a partial or edge-case gap, it is a complete absence of filtering in that function.
5. Partial-data behavior (Invariant 5) is formally isolated as its own, distinct, unresolved decision — not previously named this precisely.

## Unresolved questions

Every item in §14, restated: the Invariant-2 fix approach; Invariant-5's intended behavior; whether other raw-document providers share this risk; all Blocker A/C items from Phase 34/36, unchanged.

---

## Explicit confirmation

Zero API calls were made. Zero production, database, migration, mapping, renderer, or provider changes were made. Zero Persona Context data was populated. No historical artifact (Phase 10 through Phase 37) was modified. No behavioral experiment was run. The Phase 23/25 boundary was not reinterpreted. No production recommendation was made. LA was not declared production-ready. Phase 39 is not started automatically.
