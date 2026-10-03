# Phase 17 — Scalar-Label Effect Replication

**Exactly 8 real API calls made, as authorized. All 8 succeeded. All 8 payloads verified against the real captured request before being accepted.** Scenario selection was pre-registered in `PHASE17_PRE_REGISTRATION.md` before any call was made or any response inspected.

---

## Pre-registered four-scenario selection

See `PHASE17_PRE_REGISTRATION.md` (unchanged, written first): "How old are you?", "Where are you from?", "What city do you live in now?", "What's your educational background?" — all `about` skill, all using fields (`identity.age`, `identity.hometown`, `identity.current_city`, `life_context.education`) whose mapping to `about` has been unchanged since before Phase 10E.

## Stage 1 verification

Confirmed at pre-registration time (not re-litigated here): all 4 scenarios' field selection is identical under B in this run to the pre-registration record — `[identity.name, identity.age, identity.hometown, identity.current_city, life_context.occupation, life_context.education, personality.core_traits]` for all 4 (this is simply `about`'s full required+recommended set, unconditionally present regardless of which specific `about` question is asked — expected and correct).

## All 8 responses

| Scenario | Variant | Response |
|---|---|---|
| How old are you? | B | "I'm 27 — born and raised in Nashik, but I've been living in Pune for a few years now..." |
| How old are you? | L | "I'm 27. Just turned it this year, actually. Time's flying." |
| Where are you from? | B | "Born and raised in Nashik... I've been in Pune since college, so it feels like home now." |
| Where are you from? | L | "I'm from Nashik... grew up there, did my schooling there, and then moved to Pune for architecture. Now I live in Kothrud..." |
| What city do you live in now? | B | "I live in Pune now — specifically in Kothrud... close enough to the studio..." |
| What city do you live in now? | L | "I live in Pune — in the Kothrud area... Been here since my college days..." |
| What's your educational background? | B | "I studied architecture at a college in Pune, graduated in 2019. Honestly, the real learning happened later — on studio floors..." |
| What's your educational background? | L | "My formal education took me through a BA in Architecture from a college in Pune back in 2019. But honestly, the real learning has been on studio floors ever since..." |

## Per-pair classification

| Scenario | B correct? | L correct? | Material difference? | Scalar labeling plausibly relevant? | Classification |
|---|---|---|---|---|---|
| How old are you? | CORRECT (27, Nashik, Pune — all accurate) | CORRECT (27, plausible unverifiable embellishment "just turned it this year") | No — both correct, no contradiction in either | No — the question itself already specifies "age" is wanted; no ambiguity about which retrieved value to use | **R2 — no effect** |
| Where are you from? | CORRECT | CORRECT (more detailed, same facts) | No — both correct | No | **R2 — no effect** |
| What city do you live in now? | CORRECT | CORRECT | No — both correct, near-identical structure | No | **R2 — no effect** |
| What's your educational background? | CORRECT (architecture, Pune, 2019 — matches `life_context.education`; omits the "12th in Nashik" detail but does not contradict it) | CORRECT (same facts, same omission) | No — both correct, same minor omission in both | No | **R2 — no effect** |

**No response in either condition invented or contradicted any established fact. 8 of 8 CORRECT.**

## Comparison with Phase 14/15/16

| Phase | Scenario type | Required transformation | Labeling effect observed |
|---|---|---|---|
| 14/15/16 | "Been in this city long?" | **Derive** a new fact (duration) by relating two scalar numbers (current age, a life-event year) | Labeling coincided with a correct derivation where the unlabeled version had failed |
| 17 (all 4) | Direct identity/education recall | **Retrieve** a single already-stated fact; no relationship or derivation required | No effect — both conditions equally correct in every case |

## Does the B2 hypothesis gain additional support?

**No — not from these 4 pairs.** All four replications are R2 (no effect), 0 are R1. This does not strengthen B2 as a *general* property of scalar labeling improving persona-fact use; it is consistent with a **narrower** reading of the Phase 16 result: scalar labeling may matter specifically (or only) when the model must **combine or relate two numeric facts to derive a new one**, not when it must simply **recall a single already-stated fact** — a distinction the pre-registration's criterion 5 note anticipated could not be cleanly tested here, since the corpus offered no second exact arithmetic-relationship analog to the timeline scenario. Scenario #4 (educational background), the closest available approximation, did not require any arithmetic and, consistent with that, showed no effect either.

## Remaining alternative explanations

- The original Phase 14 B "three years" error and the Phase 16 L "six years" correction may be better explained by something specific to *that* scenario's competing 27−24=3 pathway (documented in Phase 15) than by scalar labeling as a general mechanism — this replication neither confirms nor rules that out, since none of these 4 scenarios contain a comparable competing-number ambiguity.
- It remains possible that scalar labeling *does* have a general small effect that this 4-scenario, 8-call sample is simply too small and too easy (all baseline B responses were already correct) to detect — these 4 scenarios had no headroom for improvement, since B was already at ceiling (4/4 correct) before labeling was applied.
- The Phase 16 finding could still be a single-instance generation-variance artifact rather than any labeling effect at all (an L3-adjacent possibility already flagged in Phase 16) — this replication does not resolve that either, since it tested a different scenario class (recall vs. derivation) rather than repeating the identical timeline scenario.

## Exact API-call count

**8.**

## Exact files modified

- **Created:** `docs/Persona update POC/PHASE17_PRE_REGISTRATION.md`, `docs/Persona update POC/PHASE17_EXECUTIONS.csv`, `docs/Persona update POC/PHASE17_SCALAR_LABEL_REPLICATION.md` (this document).
- **Modified:** `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase17ScalarLabelReplication()` only (additive).
- **Not modified:** `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt` (reused unchanged from Phase 16), any field mapping, any registry seed, any historical Phase 10–16 document.

## Explicit confirmation

Production rendering, resolver logic, field mappings, persona data, and engine content were untouched. No historical artifact (Phase 10 through Phase 16) was modified. Exactly 8 API calls were made, all succeeded, none were retried. No production fix was begun and no larger experiment was started — this phase stops here, per instruction.
