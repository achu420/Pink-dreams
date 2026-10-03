# Phase 39 — Persona Context Safety Contract + Implementation Spec

**Zero API calls. Zero code changes. Zero database mutation. Zero Persona Context data population.** This is a specification-only document, converting Phase 38's findings into an exact, implementation-ready contract. No historical Phase 10–38 artifact was modified. No behavioral experiment is run. The Phase 23/25 boundary is not reinterpreted. No production rollout is recommended. LA is not declared production-ready. Where source evidence is insufficient, this document marks the item as requiring an engineering decision rather than resolving it by invention.

---

## 1. Objective

Define the exact, minimal contract needed to distinguish "Persona Context exists but produced no usable content" from "Persona Context contains genuinely usable content," and to specify — without implementing — the smallest set of changes that would make baseline fallback, restricted-field protection, partial-data handling, and unknown-skill handling all safe and well-defined, keeping each concern separable per Phase 38's own discipline.

## 2. Phase 38 Findings Verified

Re-traced directly against current source in this phase, not merely restated:

1. **`"{}"` can be produced by `resolveVariantA()`**: confirmed — `masterDoc?.contentJson?.toString() ?: ""` on a non-null `masterDoc` with `contentJson = JsonObject(emptyMap())` calls `JsonObject.toString()`, which produces the literal string `"{}"`. Observable at the exact point `ResolvedPersonaContextProvider.getPersonaContext()` returns `projection.renderedText`.
2. **`isBlank()` cannot be the sole fallback signal**: confirmed by (1) — `"{}"` is non-blank.
3. **`includedFieldIds.isEmpty()` is a structural signal**: confirmed, and in this phase found to be **more powerful than Phase 38 characterized** — see §3 below. `resolveVariantA()` hardcodes `includedFieldIds = emptyList()` **unconditionally**, regardless of whether `rawText` ends up empty or contains the full raw document. This means the signal catches not only the empty-fallback cases but also the *dangerous, non-empty raw-document* case (Phase 38 state 4) — a fact Phase 38 did not fully exploit in its own recommendation.
4. **`resolveVariantA()` has no `canAccessField()` gating**: confirmed by direct re-reading — zero references to `canAccessField` anywhere in that function.
5. **Unknown and known-but-unmapped skill keys converge to the identical code path**: confirmed — both are simply `findBySkillKey(skillKey) == null`, a single boolean the resolver treats identically regardless of *why* the lookup failed.
6. **Partial-data behavior remains unresolved**: confirmed — no design artifact anywhere in Phases 10–38 specifies intended behavior for this case.
7. **The three concerns (baseline fallback, restricted-field protection, partial-data handling) are separable**: confirmed, and this phase's own analysis (§3) shows they can in fact share a **common underlying signal** (`includedFieldIds` + `variant`) even while remaining conceptually and architecturally distinct fixes, per Phase 38 §9's boundary analysis, unchanged here.

## 3. Usability-State Model

### A signal Phase 38 did not fully exploit: `variant` mismatch

`PersonaContextProjection.variant` is set to the literal string `"A"` inside `resolveVariantA()`, **unconditionally**, every time that function returns — regardless of whether it was reached via the deliberate top-level `variant == "A"` request or via either of the two silent-fallback `?: return resolveVariantA(...)` branches. This means: **if a caller requests `resolve(..., variant = "B")` (or `"C"`) and the returned `projection.variant` comes back as `"A"`, that is, by construction, proof that an unintended fallback occurred** — no separate flag or new field is needed to detect this, it is already computable from data the resolver already returns via `PersonaContextProjection`.

**Critically for a hypothetical production activation**: every real provider instantiation this program has ever used for a non-diagnostic purpose constructs `ResolvedPersonaContextProvider` (and `AgeOnlyLabeledScalarDiagnosticProvider`/`LabeledScalarDiagnosticProvider`) with variant `"B"` — never `"A"` (that value is reserved, throughout this program's history, for explicit historical/diagnostic comparison calls only, per Phase 10A). **In a production context that only ever requests variant `"B"`, a returned `variant == "A"` is unconditionally an unintended fallback — there is no legitimate production scenario in which it would be intentional.**

### Combining the two signals

`includedFieldIds.isEmpty()` alone is necessary but ambiguous: it is `true` both for the safe "nothing to fall back to" case *and* the dangerous "raw, unfiltered document was returned" case (§2 item 3) *and* the intentional, historical, diagnostic "A" request. `variant` mismatch alone is insufficient: it catches the two `?: return resolveVariantA(...)` fallback branches, but **not** the case where the normal B/C path itself runs to completion and legitimately selects zero fields (empty registry, or a master document whose `contentJson` is empty but reached via the *normal*, non-fallback path — Phase 38 state 3 and part of state 2). **The two signals are complementary, not redundant — a correct usability determination requires both.**

### Proposed state model

```text
USABLE      : requested variant matches returned variant (no fallback occurred)
              AND includedFieldIds.isNotEmpty()
              AND renderedText.isNotBlank()

NOT_USABLE  : returned variant == "A" while a selective variant ("B"/"C") was requested
              (an unintended fallback occurred — covers both the empty-fallback case
               and, critically, the dangerous non-empty-raw-document case, since both
               share this same signature)
              OR includedFieldIds.isEmpty() while the requested variant was honored
              (the normal path ran to completion but selected nothing)

PARTIAL     : NOT MECHANICALLY DISTINGUISHABLE FROM USABLE BY EXISTING SOURCE —
              see explicit limitation below

INVALID     : NOT A SEPARATE, REACHABLE STATE per existing source — malformed JSON is
              silently absorbed into an empty JsonObject at the repository layer
              (PersonaMasterRepository.rowToModel()'s try/catch), which then behaves
              identically to the "empty master document" case, not as a distinct
              error state
```

**Explicit limitation, stated rather than papered over**: the source code has **no concept of "how many fields this persona is expected to have."** There is no manifest, no minimum-field-count configuration, no per-persona completeness declaration anywhere in the registry or master-document schema. Consequently, **`PARTIAL` cannot be mechanically distinguished from `USABLE` by anything in the current architecture** — a persona with 2 selected fields and a persona with 20 selected fields are, to every piece of existing code, simply two instances of "some fields were selected," with no signal indicating whether either is "enough." This is not a gap this phase can close by inventing a threshold; it is recorded as a genuine architectural absence.

**`INVALID` is similarly not a reachable state distinct from `NOT_USABLE`** — every malformed-input path traced in Phase 38 (§4 state 9) resolves deterministically to the same empty-content behavior as a genuinely absent document, with no separate error signal surfacing anywhere in the traced call chain.

## 4. Provider/Result Contract

**Does `String?` (or today's actual `String`, non-nullable) suffice?** **No** — confirmed precisely: a bare string cannot encode the `variant`-mismatch signal at all (that information exists only on `PersonaContextProjection`, discarded the moment `ResolvedPersonaContextProvider.getPersonaContext()` returns `projection.renderedText`), and while a string *could* in principle be inspected for blankness or literal `"{}"` equality, that would be a fragile, string-content-based heuristic layered on top of an interface that already computes a much cleaner structural answer internally and simply throws it away.

**Minimum necessary additive contract, derived from what `PersonaContextResolver.resolve()` already computes (not invented)**:

```text
Conceptually (not a chosen concrete implementation):
  - renderedText: String            (already exists, on PersonaContextProjection)
  - includedFieldIds: List<String>  (already exists, on PersonaContextProjection)
  - variant: String                 (already exists, on PersonaContextProjection — the RETURNED variant)
  + requestedVariant: String        (NOT currently retained anywhere past the initial resolve() call —
                                      this is the one genuinely new piece of information needed, since
                                      today nothing remembers what was originally asked for once
                                      resolveVariantA() has already substituted its own "A" label)
```

**Why each field is necessary, and why nothing less suffices**: `renderedText` is the content itself, needed regardless. `includedFieldIds` is needed to detect the "ran to completion but selected nothing" case (empty registry / empty document via the normal path). `variant` (returned) is needed to detect "an unintended fallback occurred" when combined with knowledge of what was requested. `requestedVariant` is the **one net-new piece of state** — every other field already exists on `PersonaContextProjection` and is simply not propagated past `ResolvedPersonaContextProvider.getPersonaContext()`'s current `String`-only return type. **No other new field is derivable as necessary from the traced code** — this phase does not add a `resolutionStatus` enum, a timestamp, an error-detail field, or anything else not directly traceable to a specific, already-identified failure mode.

**This phase does not choose a concrete Kotlin type** (data class shape, sealed class, enum) for this contract — that is an implementation decision, not a specification one, and is left to whoever implements Change C (§9).

## 5. Baseline Fallback Contract

**Invariant, restated precisely**: if Persona Context cannot safely provide usable context (per §3's `NOT_USABLE` classification), `finalPersonaContent` must equal `core.content`, not a blank or otherwise degraded string.

Tested against the eight cases specified:

| Case | Classification | Should be |
|---|---|---|
| `null` (no provider) | Not a resolver case at all — this is `RepositoryContextAssembler`'s own pre-existing null check, unaffected by anything in this contract | Baseline (already correct today) |
| `""` | `NOT_USABLE` (via `includedFieldIds.isEmpty()`, and/or `variant` mismatch if reached via `resolveVariantA()`) | Baseline fallback |
| Whitespace-only | `NOT_USABLE`, same reasoning — no traced code path in this system currently produces a whitespace-only (as opposed to fully empty) string, but the classification would treat it identically to `""` if it ever occurred | Baseline fallback |
| `"{}"` | `NOT_USABLE` — `variant` mismatch (reached only via `resolveVariantA()`) **and** `includedFieldIds.isEmpty()`, both signals agree | Baseline fallback |
| Empty `includedFieldIds` | `NOT_USABLE`, by definition of the model in §3 | Baseline fallback |
| Malformed output | Collapses into the empty-document case at the repository layer (§3, "INVALID is not a reachable state") — classified `NOT_USABLE` via the same signals | Baseline fallback |
| Partial output | **Classified `USABLE`, not `NOT_USABLE`, by the mechanical model in §3** (`includedFieldIds.isNotEmpty()`) — whether this is the *correct* production behavior is exactly Invariant 5's unresolved question (§7), not re-litigated here | **Not baseline fallback, per the mechanical model** — but flagged as depending on the unresolved partial-data decision |
| Valid selected fields | `USABLE` | Persona Context (no fallback) |

## 6. Restricted-Field Contract

**Invariant, restated precisely**: no restricted field may reach the model through a raw-document bypass merely because a skill mapping is missing.

### Approach A — Filter inside `resolveVariantA()`

- **Exact component boundary**: `PersonaContextResolver.resolveVariantA()`, adding a `canAccessField()`-gated filter over `masterDoc.contentJson`'s top-level keys before serialization.
- **Required dependencies**: `resolveVariantA()` would need `fieldRegistry`/`skillKey` access it does not currently take as parameters (it currently takes only `personaCoreVersionId`) — a signature change.
- **Effect on existing callers**: both call sites (`variant == "A"` top-level request, and the two `?: return resolveVariantA(...)` fallback branches) would be affected identically, since they share the same function.
- **Effect on historical diagnostic/research behavior**: **direct** — Phases 6, 7, 9, 10A's own historical characterization of "variant A" as "full, unfiltered, by design" would no longer be literally true of the function if re-run after this change; those phases' own documents would remain historically accurate as records of what was run *then*, but the function's *current* behavior would diverge from its own historical description, requiring a disclosure note (not a rewrite, per this program's standing discipline) if this were ever implemented.
- **Restricted-field safety**: **fully closed**, for both call sites.
- **Regression surface**: any future re-use of variant "A" for a deliberate, full-document comparison (as Phase 6/7/9 did) would now silently receive filtered, not raw, content — a change to a previously-frozen research baseline.
- **Test requirements**: re-verify Phase 6/7/9/10A's own historical characterization is either still accurate (if this function is never re-run for that purpose again) or explicitly re-documented (if it is).

### Approach B — Separate safe fallback path, `resolveVariantA()` untouched

- **Exact component boundary**: a new, additive function (e.g., conceptually `resolveSafeFallback()`), called only from the two `?: return ...` fallback branches; the top-level `variant == "A"` request continues to call the original, unmodified `resolveVariantA()`.
- **Required dependencies**: the new function would need the same `fieldRegistry`/`canAccessField()` access already available inside `resolve()`'s own non-A branch — no dependency beyond what `PersonaContextResolver` already holds as constructor parameters.
- **Effect on existing callers**: only the two fallback branches change target; the deliberate `variant == "A"` request path is completely untouched.
- **Effect on historical diagnostic/research behavior**: **none** — `resolveVariantA()` remains byte-for-byte as it has always been, preserving Phase 6/7/9/10A's historical accuracy without qualification.
- **Restricted-field safety**: fully closed for the two fallback branches; the deliberate `variant == "A"` request path remains intentionally unfiltered, exactly as it has always been by design.
- **Regression surface**: minimal — purely additive, no existing function's behavior changes.
- **Test requirements**: new tests for the new function only; no re-verification of historical Phase 6/7/9/10A behavior needed, since nothing about `resolveVariantA()` changed.

**This phase does not choose between A and B.** Approach B has a smaller regression surface and preserves historical accuracy without qualification; Approach A is more consolidated but carries a real cost to this program's own historical research record. **This is an engineering decision, not a fact resolvable from source.**

## 7. Partial-Data Contract

Six states, traced precisely (extending, not repeating, Phase 38 §4's states 5-7):

| State | What happens today | Info available | Safe field filtering possible? | Baseline fallback possible? | Production-required behavior | Established or decision? |
|---|---|---|---|---|---|---|
| 1. Master document absent | `NOT_USABLE` (§3/§5) | None | N/A (nothing to filter) | Yes | Baseline fallback | **Established** |
| 2. Document exists, registry empty | `NOT_USABLE` (`includedFieldIds.isEmpty()`, `canAccessField()` universally `false`) | Document content exists but nothing is registered to access it | Yes, trivially (everything is filtered out, by construction) | Yes | Baseline fallback | **Established** |
| 3. Document exists, registry partial | `USABLE` per the mechanical model (some fields selected) | Partial field set, with no signal distinguishing "this is everything this persona has" from "this is missing entries" | Yes, for whatever is registered | Not automatically, since `includedFieldIds` is non-empty | **Unresolved** — could mean "use partial content" (current mechanical default) or "treat as insufficiently configured, fall back" | **Engineering decision required** |
| 4. Registry complete, skill mappings incomplete | For the mapped skill: `USABLE`. For the unmapped skill: `NOT_USABLE` via the fallback signal, **with restricted-field risk if Approach A/B (§6) is not yet implemented** | Depends entirely on which skill is active per-request | Yes, for the mapped skill; N/A for the unmapped skill (routes to fallback) | Yes, for the unmapped skill, once §6 is resolved | Mapped skill: Persona Context. Unmapped skill: baseline fallback (once §6 closes the raw-document risk) | **Established for the mapped-skill case; established that the unmapped-skill case is unsafe until §6 is resolved; NOT established whether "baseline fallback" or some other unmapped-skill behavior is ultimately correct beyond safety (see §8)** |
| 5. Requested skill mapped, another active skill unmapped | No cross-request interaction — each request's outcome depends solely on its own skill, per Phase 38 §4 state 15's aggregate-risk finding, unchanged | Same as state 4, evaluated per-request | Same as state 4 | Same as state 4 | Same as state 4, applied independently per request | Same as state 4 |
| 6. Some fields selected but required persona information absent (e.g., `identity.name` never registered, though other fields are) | `USABLE` per the mechanical model — the system has no concept of "required for a coherent persona voice" beyond the required/recommended/conditional *mapping* tiers (which are about relevance to a skill, not about baseline persona coherence) | Whatever was selected, with no signal that something structurally important (like a name) is missing | Yes, for whatever is registered | Not automatically | **Unresolved** — this is a sharper version of state 3's question: even a single selected field currently counts as `USABLE`, with no minimum-viable-persona concept in the architecture at all | **Engineering decision required** |

**No merge strategy is invented.** Where "unresolved" appears above, it remains unresolved in this document.

## 8. Unknown/Unmapped-Skill Contract

**Invariant, restated precisely**: an unmapped skill must never broaden persona-context exposure.

Traced precisely:

```text
skill key (from IntentDiscovery, or pinnedSkillKey in a test/benchmark context)
    ↓
skill lookup — SkillContextRequirementRepository.findBySkillKey(skillKey)
    — returns null identically whether the key is "unknown to the whole system"
      or "known to production but simply has no SkillContextRequirement row" (Phase 38 §4, states 11-12)
    ↓
mapping lookup — same call; a null result routes directly to resolveVariantA(personaCoreVersionId)
    ↓
selected fields — NONE are computed; resolveVariantA() never reaches the
    required/recommended/conditional selection loops or canAccessField() at all
    ↓
restricted-field gate — NOT APPLIED in this path (§2 item 4, §6)
    ↓
renderer — NOT INVOKED; resolveVariantA() serializes raw JSON directly, bypassing
    PersonaContextRenderer entirely
    ↓
final model-bound content — either "" (no document) or the FULL RAW DOCUMENT
    (document present) — the exact point where safety must be enforced
```

**Exactly where safety must be enforced**: at the `?: return resolveVariantA(personaCoreVersionId)` call inside the skill-mapping-lookup branch specifically (the second of the two `?:` fallback sites in `resolve()`) — this is the single point where an unmapped skill's request diverges from the safe, gated selection path. **This is the same enforcement point identified in §6** (Approach A or B) — the unknown/unmapped-skill contract and the restricted-field contract are, at the implementation level, the **same fix**, applied at the **same call site**, confirming Phase 38 §9 fix 4's finding that these are not independent mechanisms.

## 9. Minimal Implementation Sequence (not implemented)

### Change A — Baseline fallback

- **Exact file/component**: `RepositoryContextAssembler.kt`.
- **Exact responsibility**: extend the existing `personaContent` computation to treat a `NOT_USABLE` result (per §3's model) as equivalent to a null provider.
- **Dependency changes**: requires Change C (the result contract) to be available first, since `RepositoryContextAssembler` cannot evaluate `variant`/`includedFieldIds` from a bare `String`.
- **Expected behavior**: `finalPersonaContent == core.content` whenever `NOT_USABLE`.
- **Regression risk**: low — this branch is never exercised in production today (provider is always null).
- **Deterministic tests required**: §10's baseline-fallback rows.

### Change B — Restricted-field protection

- **Exact file/component**: `PersonaContextResolver.kt` — either modifying `resolveVariantA()` (Approach A) or adding a new function (Approach B), per §6.
- **Exact responsibility**: ensure the missing-skill-mapping fallback path cannot serialize a restricted field without `canAccessField()` gating.
- **Dependency changes**: Approach A needs `fieldRegistry`/`skillKey` threaded into `resolveVariantA()`; Approach B needs no new dependency beyond what `resolve()` already has.
- **Expected behavior**: no restricted-field content reaches the fallback's output under any skill-mapping-missing condition.
- **Regression risk**: Approach A affects historical variant-A research behavior (§6); Approach B does not.
- **Deterministic tests required**: §10's restricted-field rows.

### Change C — Result/status contract

- **Exact file/component**: `PersonaContextProvider` interface (`chat/context/PersonaContextProvider.kt`) and every implementing class (`ResolvedPersonaContextProvider`, `AgeOnlyLabeledScalarDiagnosticProvider`, `LabeledScalarDiagnosticProvider`, `SelectiveRawContextProvider`, `FullRawContextProvider`, `StructuredFullDocumentContextProvider`).
- **Exact responsibility**: propagate `includedFieldIds`, returned `variant`, and the originally-requested variant, alongside `renderedText`, instead of collapsing to a bare `String`.
- **Dependency changes**: this is the **largest-surface** change of the five — it touches the shared interface every provider implements, not a single file. Each implementing class would need to expose the same additional information its own internal resolution already computes (straightforward for `ResolvedPersonaContextProvider` and the labeling diagnostics, which already call `resolve()`; less obviously applicable to the raw/full-document providers, which do not use `PersonaContextResolver.resolve()`'s variant/field-selection machinery at all — see §14).
- **Expected behavior**: `RepositoryContextAssembler` (or whatever calls the provider) can evaluate §3's state model without string-content heuristics.
- **Regression risk**: **highest of the five changes** — an interface change ripples to every implementer, including the ones (`SelectiveRawContextProvider`, `FullRawContextProvider`) this phase has not analyzed for how they would even populate the new fields, since they were explicitly out of scope in both Phase 37 and Phase 38.
- **Deterministic tests required**: interface-conformance tests for every implementing class, in addition to the behavioral tests in §10.

### Change D — Partial-data behavior

- **Exact file/component**: undetermined — **explicitly not specified**, per §7's unresolved status, consistent with Phase 38 §9's identical stance.
- Everything else: pending the engineering decision in §14.

### Change E — Unknown-skill behavior

- **Exact file/component**: identical to Change B — confirmed in §8 to be the same fix, not a separate one. No independent implementation entry beyond Change B.

**These changes are not combined merely because they touch related files** — Change C is a prerequisite for Change A to be implementable in the form specified, but Changes A, B, D, and E remain conceptually and architecturally independent of each other, per Phase 38's own discipline, preserved here.

## 10. Deterministic Test Specification

| Case | Expected contract |
|---|---|
| No provider (`null`) | Baseline |
| Null provider result | N/A — the interface today returns a non-nullable `String`; this row is not reachable under the current type signature and would only become relevant if Change C introduces nullability |
| Blank provider result (`""`) | Baseline |
| `"{}"` | Baseline (requires Change A+C together; a bare `isBlank()` check alone does not achieve this, per §2 item 2) |
| Empty selected-field set (`includedFieldIds.isEmpty()`, variant matches request) | Baseline (this is the empty-registry / empty-document-via-normal-path case) |
| One valid selected field | `USABLE` per §3's mechanical model — **but whether one field constitutes sufficient "usability" for production purposes is `ENGINEERING DECISION REQUIRED`**, per §7 state 6 |
| Fully valid context (complete registry + mapping, mirroring the POC's own Simran fixture) | Persona Context — already validated behaviorally, Phases 14-30 |
| Malformed document | Baseline (collapses into the empty-document case at the repository layer, §3) |
| Unknown skill | No exposure — `ENGINEERING DECISION REQUIRED` for *which approach* (§6), but the *requirement* itself ("no exposure") is established |
| Unmapped skill | Identical to "unknown skill," per §8's finding that these are code-identical |
| Restricted field present, skill permits it | Filtered correctly — already validated, Phases 10C-30 (0 leakage across hundreds of calls) |
| Restricted field present, missing mapping | `ENGINEERING DECISION REQUIRED` for the specific fix approach (§6); the requirement ("must not reach the model") is established |
| Partial mapping | `ENGINEERING DECISION REQUIRED`, per §7 states 3-4 |
| Multiple skills, mixed mapping | `ENGINEERING DECISION REQUIRED` for the underlying per-skill behavior (same as partial mapping/unmapped skill); the *aggregation* itself (no cross-request contamination) is established, per Phase 38 §4 state 15 |

**Every "Engineering decision required" row above traces back to either §6's Approach A/B choice or §7's partial-data/minimum-viability question — no new, previously-unidentified unresolved item is introduced by this test matrix.**

## 11. Production Activation Gate

| Category | Items |
|---|---|
| **Proven by source** | The exact mechanism of every failure mode traced in Phase 36-39; the fact that `variant` mismatch + `includedFieldIds` emptiness together fully classify `NOT_USABLE` vs. `USABLE` (modulo the `PARTIAL` limitation, §3); the fact that the unknown-skill and restricted-field-bypass problems are the same fix at the same call site (§8) |
| **Requires implementation** | Changes A, B, C (§9) — none implemented in this phase |
| **Requires deterministic tests** | The full matrix in §10, executed after Changes A/B/C, plus Phase 34/36's own broader regression-test requirements (production-wired equivalence, real skill-key validation) — referenced, not repeated |
| **Requires production-data decision** | Blocker A from Phase 36 (data availability) — entirely unaddressed by this phase |
| **Requires human/engineering decision** | The §6 Approach A/B choice; the §7 partial-data/minimum-viability question; the concrete Kotlin shape of Change C's result contract (§4); whether Change C's interface change should also apply to `SelectiveRawContextProvider`/`FullRawContextProvider` (§14) |
| **Still outside this investigation** | Blocker C's actual `Application.kt` wiring (Phase 34/36); anything from the B-vs-LA behavioral research track (§12) |

**This is not a rollout recommendation** — it is an inventory of what is proven versus what remains open, per instruction.

## 12. LA-vs-Persona-Context Separation

Explicitly, per instruction:

1. **Persona Context activation** (Blocker A/B/C, this phase and Phase 34-38) is a data-availability and resolver-safety question, entirely about whether *any* selective persona content can safely reach production at all.
2. **Selective field retrieval** (the B mechanism itself — required/recommended/conditional tiering, `canAccessField()`) is the mechanism Persona Context activation would turn on.
3. **Scalar labeling** (LA/L) is a *formatting refinement on top of* selective retrieval, tested behaviorally in Phases 14-30, entirely independent of whether selective retrieval itself is safely activatable in production.
4. **Production LA adoption** would require both (1)/(2) to be safely resolved **and** a separate decision about whether the labeling effect (Phase 26/30's statistical results) is judged sufficient for production use, per the unmet Phase 23/25 boundary (Phase 31).

**Solving the Persona Context safety contract (this phase) does not establish that LA should be used, and must not be read as doing so.** The two tracks are gated by entirely different evidence: this phase's findings are about *code safety* (does the system silently misbehave), while Phases 14-30's findings are about *behavioral effect* (does labeling change model output). **A fully-implemented, fully-safe Persona Context system (closing every item in §11) would still say nothing about whether LA specifically should be adopted on top of it** — that remains gated by the unmet "more than two scenario types" boundary (Phase 31), unchanged by anything in this document.

## 13. Established Facts

- `variant` mismatch (returned `"A"` when a selective variant was requested) is an existing, already-computed, zero-new-dependency signal for detecting an unintended fallback, not previously articulated this precisely in Phase 37/38.
- `includedFieldIds.isEmpty()` catches both the empty-fallback cases and, critically, the dangerous non-empty-raw-document case, since `resolveVariantA()` always sets it to an empty list regardless of content.
- These two signals are complementary, not redundant — neither alone fully classifies every traced state.
- The system has no concept of "expected complete field count" or "minimum viable persona" — `PARTIAL` is not mechanically distinguishable from `USABLE` by anything in current source.
- `INVALID` is not a reachable, distinct state — malformed input collapses into the empty-document case at the repository layer.
- The unknown-skill and restricted-field-bypass problems are the same underlying fix, at the same call site (§8) — confirmed, not merely asserted from Phase 38.
- Change C (the result contract) is a prerequisite for Change A as specified, and is the largest-regression-surface change of the five, since it touches a shared interface with six implementing classes, two of which (`SelectiveRawContextProvider`, `FullRawContextProvider`) were not analyzed for how they would populate the new contract.

## 14. Engineering Decisions Still Required

- §6: Approach A (filter `resolveVariantA()`, affecting historical research accuracy) vs. Approach B (separate fallback function, no historical impact).
- §7: whether partial data (states 3, 6) should be used as-is, merged with baseline, or rejected — and relatedly, whether any minimum-field-count or minimum-viable-persona concept should be introduced at all (a new architectural concept, not merely a bug fix).
- §4: the concrete Kotlin shape of the result contract (data class vs. sealed class vs. enum-plus-fields).
- Whether Change C's interface change should extend to `SelectiveRawContextProvider`/`FullRawContextProvider`, which do not use `PersonaContextResolver.resolve()`'s variant/field-selection machinery and were not analyzed in this phase for how (or whether) they could populate the same contract fields meaningfully.
- Sequencing of Changes A-E relative to Blocker A (data population) and Blocker C (production wiring) — not specified here beyond the dependency note that Change A requires Change C.

## 15. What Phase 39 Does NOT Establish

- A chosen implementation for Change C's concrete type shape.
- A chosen approach between §6's Approach A and B.
- Partial-data / minimum-viable-persona behavior (§7).
- Whether `SelectiveRawContextProvider`/`FullRawContextProvider` need or can support the same result contract.
- Any causal, generalization, LA-vs-L, or 27−24 finding — unchanged from Phases 25-32.
- Any production recommendation, for any of Path A/B/C from `PHASE32_ENGINEERING_DECISION_PACKAGE.md`.
- LA's production-readiness — explicitly not addressed, not declared, per §12's separation.
- That solving this contract makes Persona Context, or LA on top of it, ready to ship — per §11/§12, multiple items remain outside this phase's scope entirely.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE39_PERSONA_CONTEXT_SAFETY_CONTRACT.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

## Key findings

1. A `variant`-mismatch check (returned `"A"` vs. requested `"B"`/`"C"`) is an already-computed, zero-new-dependency signal for detecting unintended fallback — sharper than Phase 38's `includedFieldIds`-only proposal, and the two signals are shown to be complementary, not redundant.
2. `includedFieldIds.isEmpty()` is shown to catch the dangerous non-empty-raw-document case too, since `resolveVariantA()` always hardcodes it to an empty list regardless of content — a stronger finding than Phase 38 stated.
3. The system has no "minimum viable persona" concept anywhere in source — `PARTIAL` cannot be mechanically distinguished from `USABLE` today, an explicit, named architectural limitation, not merely an unresolved preference.
4. The unknown-skill and restricted-field-bypass fixes are confirmed to be the identical fix at the identical call site.
5. The proposed result contract requires exactly one genuinely new piece of state (`requestedVariant`) — every other field already exists on `PersonaContextProjection` and is simply discarded today at the provider-interface boundary.
6. Change C (the result-contract interface change) is identified as the largest-regression-surface, most consequential of the five proposed changes, since it ripples to six implementing classes, two of which remain unanalyzed for compatibility.

## Unresolved questions

Every item in §14, restated: the §6 Approach A/B choice; the §7 partial-data/minimum-viability decision; Change C's concrete type shape; whether the raw/full-document providers can support the same contract; sequencing relative to Blockers A and C.

---

## Explicit confirmation

Zero API calls were made. Zero code was implemented. Zero database was mutated. Zero Persona Context data was populated. No historical artifact (Phase 10 through Phase 38) was modified. No behavioral experiment was run. The Phase 23/25 boundary was not reinterpreted. No production rollout was recommended. LA was not declared production-ready. Solving this contract is explicitly stated not to establish LA's production readiness, per §12. Phase 40 is not started automatically.
