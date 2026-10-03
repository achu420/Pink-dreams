# Phase 42 — Decision Closure

**Zero API calls. Zero production, database, persona-data, renderer, or provider changes.** This document converts Phase 41's five human-gated decisions into an explicit implementation contract. **No decision is made on the owner's behalf.** No historical Phase 10–41 artifact was modified. No behavioral experiment is run. LA is not declared production-ready. The Phase 23/25/28/31 boundary is not reinterpreted.

---

## 1. Reconstruct the Five Decisions

### Decision 1 — Change B: Approach A vs. Approach B

- **Exact decision**: whether restricted-field filtering on the missing-skill-mapping fallback path is implemented by modifying `resolveVariantA()` in place (Approach A), or by adding a new, separate function that leaves `resolveVariantA()` untouched (Approach B).
- **Available options**: Approach A (filter inside `resolveVariantA()`); Approach B (new additive sibling function, called only from the two `?: return ...(...)` fallback sites).
- **What each option changes**: Approach A changes `resolveVariantA()`'s signature (adding `fieldRegistry`/`skillKey` parameters) and behavior for *every* caller, including the deliberate top-level `variant == "A"` research request. Approach B changes nothing about `resolveVariantA()`; it adds a new function invoked only from the two fallback branches.
- **What remains unchanged**: under Approach B, `resolveVariantA()`'s behavior for a deliberate `variant == "A"` request is untouched — historically and going forward. Under Approach A, nothing about the fallback-path outcome differs from Approach B's outcome; only the deliberate-request path's behavior changes.
- **Safety implications**: **LOCKED BY SOURCE — both approaches are safety-equivalent for the fallback paths.** Phase 40 §6 and Phase 41 §8 both independently re-traced this and found identical, complete restricted-field closure under either approach.
- **Regression implications**: Approach A touches a single existing, historically-significant function (higher regression surface, one function, many historical callers to re-verify). Approach B is purely additive (lower regression surface, new code only).
- **Historical-research implications**: Approach A would make `resolveVariantA()`'s behavior diverge from what Phases 6, 7, 9, and 10A documented it as ("full, unfiltered, by design") — those phases' own documents remain historically accurate as records of what was run *then*, but a future re-run for comparison purposes would no longer reproduce that behavior without a disclosure note. Approach B has no such implication.
- **Implementation impact**: Approach A requires a signature change (new parameters) affecting the deliberate research call site. Approach B requires no new dependency beyond what `resolve()` already holds.
- **Established vs. unresolved**: the safety-equivalence is **established**. The choice itself is **unresolved** — a genuine trade-off between consolidation (A) and historical-fidelity preservation (B), not a fact.

### Decision 2 — Change A: strict substitution of `NOT_USABLE` content with today's baseline

- **Exact decision**: whether `RepositoryContextAssembler.assemble()` should, without exception, substitute `core.content` for any provider result the §4-predicate (Phase 41) classifies `NOT_USABLE` — including the case where `renderedText` is non-blank (the raw-document case) — versus some less strict alternative not yet specified by any prior phase.
- **Available options**: (a) strict substitution, always, no exceptions; (b) some other, softer rule — not specified by source, since no prior phase proposed or evaluated an alternative.
- **What each option changes**: strict substitution (a) means `NOT_USABLE` content, including a non-blank raw document, is discarded unconditionally — the safest option traced. Option (b) is undefined; no prior phase specifies what a "softer" rule would even look like or why one would be preferred.
- **What remains unchanged**: `core.content`'s own value and computation are unaffected by either option — this decision only concerns whether and how often it is substituted in.
- **Safety implications**: **LOCKED BY SOURCE for option (a)** — Phase 40 §4 and Phase 41 §5 both establish that strict substitution independently closes the restricted-field leak for the fallback path (no reliance on Change B needed). No prior phase has evaluated the safety of any alternative to strict substitution, since none has been proposed.
- **Regression implications**: strict substitution is a new branch, unreachable in production today (provider is always null) — confirmed low-risk regardless of which option is chosen, since neither is exercised until Blocker C (production wiring) is separately resolved.
- **Historical-research implications**: none — Change A touches only `RepositoryContextAssembler.kt`, not `PersonaContextResolver.kt`.
- **Implementation impact**: strict substitution (a) is the option Phase 41's patch plan (§9) is written against; adopting anything else would require a new specification pass this document does not perform.
- **Established vs. unresolved**: that strict substitution *would work* and *would be safe* is **established**. That it is the *chosen* behavior is **not established as a decision** — Phase 41 explicitly flagged this as "assumed but not ratified" (§13/§14), and this document does not ratify it either.

### Decision 3 — Change C: concrete Kotlin type/interface shape

- **Exact decision**: what concrete Kotlin construct (data class, sealed class, enum-plus-fields, or another shape) should carry `renderedText`, `includedFieldIds`, returned `variant`, and `requestedVariant` across the `PersonaContextProvider` interface boundary.
- **Available options**: not enumerated exhaustively by any prior phase beyond naming "data class vs. sealed class vs. enum-plus-fields" as candidate shapes (Phase 39 §4, Phase 40 §7, Phase 41 §14) — no phase has evaluated the trade-offs between these specific options.
- **What each option changes / what remains unchanged**: **unresolved** — this is a pure implementation-detail question with no safety or behavioral consequence traced by any prior phase; whichever shape is chosen, the same four pieces of information (§Decision 3's subject) must be carried, and the classification predicate (Phase 41 §4) is unaffected by which concrete type carries them.
- **Safety implications**: none identified — this is a data-modeling choice, not a safety choice.
- **Regression implications**: whichever shape is chosen determines the exact mechanical form of the refactor touching all six provider implementations and the benchmark test call sites (Phase 41 §9, §12) — the *scope* of the regression surface is fixed regardless of shape; the *shape* affects only how each call site's code is written, not how many call sites are affected.
- **Historical-research implications**: none.
- **Implementation impact**: this decision must be made before Change C's patch can be written, since the interface signature itself depends on it.
- **Established vs. unresolved**: **fully unresolved** — no prior phase narrows this beyond naming three candidate shapes.

### Decision 4 — Change C scope: whether all three non-selective providers are included

- **Exact decision**: whether `SelectiveRawContextProvider`, `FullRawContextProvider`, and `StructuredFullDocumentContextProvider` must also migrate to Change C's new contract type, or whether Change C's scope is limited to the providers actually used in the currently-proposed B-vs-LA production path (`ResolvedPersonaContextProvider`, `AgeOnlyLabeledScalarDiagnosticProvider`, `LabeledScalarDiagnosticProvider`).
- **Available options**: (a) migrate all six providers; (b) migrate only the three used in the B-vs-LA path, leaving the other three on the old `String`-returning interface (requiring either a second interface or a compatibility shim, neither specified by any prior phase); (c) some other scope not yet considered.
- **What each option changes**: option (a) achieves full interface consistency across every `PersonaContextProvider` implementation. Option (b) reduces the immediate regression surface but leaves the interface non-uniform (two different contracts in the same codebase) and does not specify how the resulting type mismatch would be handled by any code that might polymorphically hold a `PersonaContextProvider` reference.
- **What remains unchanged**: under option (b), `SelectiveRawContextProvider`/`FullRawContextProvider`/`StructuredFullDocumentContextProvider`'s existing behavior is entirely untouched.
- **Safety implications**: none identified for either option — none of these three providers is part of the traced fallback-safety problem (Phases 36-41 all scoped their analysis to `ResolvedPersonaContextProvider`/`PersonaContextResolver`'s two fallback branches, which these three providers do not use).
- **Regression implications**: option (a) has a larger immediate regression surface (three more classes to migrate); option (b) defers that surface but creates an interface inconsistency whose own consequences have not been traced by any prior phase.
- **Historical-research implications**: none directly, though these three providers were used in Phase 7/10D/10E's own historical research (as D/FS, X/SR, FR) — any interface change touching them would need the same re-verification discipline applied to any other historically-used component.
- **Implementation impact**: this decision determines whether Change C is a single, uniform migration or a partial one requiring an additional design (an interface split or adapter) not specified anywhere in Phases 39-41.
- **Established vs. unresolved**: **fully unresolved** — Phase 40 §7 and Phase 41 §9/§13 both explicitly flagged this as unanalyzed, not merely undecided.

### Decision 5 — `PARTIAL`: formal definition now, or explicit deferral

- **Exact decision**: whether to introduce a new architectural concept (a minimum-viable-persona or minimum-field-count rule) to make `PARTIAL` a mechanically distinguishable state, or to explicitly defer this and proceed with the existing predicate (which classifies any non-empty selection as `USABLE`, regardless of size).
- **Available options**: (a) define a minimum-field-set or threshold rule now; (b) explicitly defer, proceeding with Changes A/B/C as specified, accepting that partial data is currently treated as fully usable.
- **What each option changes**: option (a) would require inventing a new data concept not present anywhere in the current schema (Phase 40 §5, Phase 41 §11) — no prior phase has done this, and doing so now would violate every prior phase's explicit "do not invent a threshold" instruction. Option (b) changes nothing about Changes A/B/C's specification, which does not depend on `PARTIAL` being resolved at all (Phase 41 §13, "not required for Changes A/B/C to be implemented").
- **What remains unchanged**: under option (b), the §4 predicate (Phase 41) is unaffected — a persona with one selected field remains classified `USABLE`, exactly as specified.
- **Safety implications**: none identified for either option — `PARTIAL` is a data-completeness question, not a restricted-field or raw-document safety question, both of which are fully addressed by Decisions 1-2 independent of this one.
- **Regression implications**: option (a) would require new tests and potentially new registry/schema concepts, an open-ended scope not bounded by any prior phase's analysis. Option (b) requires nothing further.
- **Historical-research implications**: none.
- **Implementation impact**: option (b) allows Changes A/B/C to proceed without waiting on this decision at all; option (a) would block or at minimum extend the implementation timeline with new design work.
- **Established vs. unresolved**: that Changes A/B/C do **not require** this decision to proceed is **established** (Phase 41 §13). The decision itself — whether to pursue `PARTIAL` at all, ever — is **unresolved**, and explicitly not something this document, or any prior phase, resolves by default.

## 2. No Option Chosen

Confirmed: no recommendation, ranking, safety/preference claim, inferred owner intent, silently-resolved trade-off, or implementation-preceding-decision appears anywhere in §1 above or elsewhere in this document. Every technical fact already established by Phase 40/41 (the two safety-equivalences in Decisions 1 and 2, and the "not required" finding in Decision 5) is preserved as a fact; every genuine trade-off is marked accordingly in §3.

## 3. Implementation Lock Table

| Decision | Options | Decision owner | Current status | Required before implementation |
|---|---|---|---|---|
| 1. Change B: Approach A vs. B | Modify `resolveVariantA()` in place / add separate additive function | Engineering/product owner | Safety-equivalence **LOCKED BY SOURCE**; choice itself **HUMAN DECISION REQUIRED** | An explicit, written choice between A and B before Change B's patch (Phase 41 §9) is written |
| 2. Change A: strict substitution | Strict, unconditional substitution / an unspecified alternative | Engineering/product owner | Safety property of strict substitution **LOCKED BY SOURCE**; adoption as the *chosen* behavior is **HUMAN DECISION REQUIRED** (Phase 41 assumed it without ratifying it) | Explicit written approval of strict substitution (or a specified, newly-analyzed alternative) before Change A's patch is written |
| 3. Change C: concrete type shape | Data class / sealed class / enum-plus-fields / other | Engineering/product owner | **HUMAN DECISION REQUIRED** — no prior phase narrows beyond naming candidates | A frozen, written type definition before any of the six provider classes are touched |
| 4. Change C scope: all six providers vs. three | Migrate all six / migrate only the three B-vs-LA-path providers (with an unspecified interface-split design) / other | Engineering/product owner | **HUMAN DECISION REQUIRED** — Phase 40/41 both flag this as unanalyzed | A frozen scope decision, and if option (b) is chosen, a separate design pass for the resulting interface split (not performed by any prior phase) |
| 5. `PARTIAL`: define now vs. defer | Define a minimum-viability rule now / explicitly defer | Engineering/product owner | Non-necessity for Changes A/B/C **LOCKED BY SOURCE**; the decision itself is **EXPLICITLY DEFERRED** unless the owner chooses to open it | No blocking requirement — Changes A/B/C may proceed under **EXPLICITLY DEFERRED** status for this decision alone; only becomes **HUMAN DECISION REQUIRED** if the owner elects to pursue option (a) |

## 4. Implementation May Begin Only When

- All five decisions in §3 have an owner-approved status (i.e., every `HUMAN DECISION REQUIRED` row has been explicitly resolved by the decision owner, in writing).
- The chosen Change A behavior (strict substitution, or a newly-specified alternative) is written down, not merely assumed from Phase 41's draft.
- The chosen Change B approach (A or B) is written down.
- The exact Change C contract (concrete type shape, per Decision 3) is frozen.
- Change C's provider scope (per Decision 4) is frozen — including, if option (b) is chosen, a completed design for the resulting interface split, which does not yet exist.
- `PARTIAL` is either formally defined (if the owner opens Decision 5) or its **EXPLICITLY DEFERRED** status is confirmed as acceptable to proceed without.
- **No unresolved decision is silently encoded into code** — i.e., an implementer must not, for example, simply pick Approach B "because it's additive" or a data class "because it's simplest" without the owner's explicit sign-off recorded per the above, even if such a choice would be technically reasonable.

## 5. Two Tracks, Explicitly Separated

### Track 1 — Safety/contract implementation

Concerns: `requestedVariant`; `variant` mismatch; `includedFieldIds`; the `NOT_USABLE` classification; strict fallback/substitution behavior (Decision 2); restricted-field protection (Decision 1); the provider/interface migration (Decisions 3-4); and the deferred `PARTIAL` question (Decision 5). This track is entirely about **code safety** — whether the system silently misbehaves when Persona Context data is absent, incomplete, or improperly routed.

### Track 2 — LA behavioral evidence

Concerns: Phase 26's statistically significant Pune-timeline result; Phase 30's statistically significant sister-age-gap result; the two tested scenario types; the accumulated statistical evidence across Phases 19-30; the unmet "more than two scenario types" generalization boundary (Phase 23/25/28/31); and production behavioral readiness for LA specifically.

### Explicit statement

**Completing Track 1 (closing all five decisions and implementing Changes A/B/C) does not establish that LA should be enabled in production.** The two tracks are gated by entirely different evidence: Track 1 answers "does the system silently misbehave" (a code-correctness question); Track 2 answers "does scalar labeling measurably change model output, and is that evidence sufficient for production" (a behavioral-effect question, still gated by the unmet Phase 23/25/28/31 boundary regardless of Track 1's status). **A fully-resolved Track 1 says nothing about Track 2, and vice versa** — this mirrors, and does not alter, Phase 39 §12's identical separation.

## 6. What Phase 42 Does NOT Do

- No production code changes.
- No database changes.
- No persona-data population.
- No renderer changes.
- No provider implementation changes.
- No API calls.
- No new behavioral experiment.
- No reinterpretation of the Phase 23/25/28/31 boundary.
- No LA production recommendation.
- No selection, ranking, or inferred resolution of any of the five decisions in §1/§3.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE42_DECISION_CLOSURE.md` (this document)

## Files modified

**None.**

## Production files modified

**None.**

## Database mutations

**None.**

---

**Phase 42 status: DECISION PACKAGE COMPLETE — IMPLEMENTATION BLOCKED UNTIL HUMAN DECISIONS ARE EXPLICITLY LOCKED.**

Phase 43 is not started automatically.
