# Phase 16 — Labeled-Scalar Diagnostic

**Exactly 1 real API call made, as authorized.** New isolated diagnostic class `LabeledScalarDiagnosticProvider.kt` created (a copy of `PersonaContextRenderer`'s nested-object/array logic with one intentional change); `PersonaContextRenderer.kt` itself was **not modified**. One additive `variant == "L"` branch added to `TestChatService.buildEngineFor()`, alongside the existing D/X/FR branches, not touching A/B/C.

---

## Stage 1 — zero-cost verification (all 7 checks passed)

| # | Check | Result |
|---|---|---|
| 1 | `identity.age` rendered with an explicit age label | **PASS** — `"Age: 27"` present |
| 2 | `life_context.important_life_events` content unchanged | **PASS** — identical 6-item text, byte-for-byte |
| 3 | "21" and "24" events still present | **PASS** |
| 4 | No additional facts introduced | **PASS** — identical field-ID set to B |
| 5 | Selection equivalent to Phase 14 B | **PASS** — same 9 fields |
| 6 | B-vs-L difference is *exactly* a label prefix per scalar line, no value changes, no added/removed lines beyond labeling | **PASS**, on the second attempt — see correction below |
| 7 | X/raw payload unchanged from Phase 14 | **PASS** |

**One test-harness error was found and corrected within this phase, disclosed rather than silently fixed:** the first version of check #6 assumed only the `identity.age` line should differ between B and L. It failed, because `LabeledScalarDiagnosticProvider` — correctly, per the instruction's own wording ("scalar fields receive explicit field labels," plural) — labels *every* top-level scalar (name, hometown, current_city, age, occupation, communication.style), not age alone. This was a flaw in the test's own assertion, not in the diagnostic provider or in Stage 1's design intent. Corrected to verify the more precise, correct invariant: every line that differs between B and L differs by *exactly* a `"Label: "` prefix, with no underlying value change and no line added or removed beyond that labeling — re-run, passed cleanly.

## Exact B-vs-diagnostic payload difference

| B (unlabeled) | L (labeled) |
|---|---|
| `Simran Kohli` | `Name: Simran Kohli` |
| `Nashik, Maharashtra` | `Hometown: Nashik, Maharashtra` |
| `Pune, Maharashtra (Kothrud)` | `Current city: Pune, Maharashtra (Kothrud)` |
| `27` | `Age: 27` |
| `Architect (junior)` | `Occupation: Architect (junior)` |
| `Warm, clear, direct. Says what she thinks...` | `Style: Warm, clear, direct. Says what she thinks...` |

Every other line (the `life_context.important_life_events` bullets, `narrative_state.unresolved_threads` bullets, `personality.core_traits` bullets — all nested arrays, unaffected by this change) is byte-for-byte identical across B and L.

## Stage 2 — the one API call

**Prompt:** "Been in this city long?" (general_chat, variant L).
**Response:** *"Born here, actually — Nashik. But I moved to Pune when I was 21 for architecture school, so that's... six years now? Yeah, six. Kothrud feels like home at this point, though. How about you — are you a Pune native or just passing through?"*

- **Classification: CORRECT.** The model explicitly derives and states "six years" — the arithmetically correct answer (27 − 21 = 6) — and gets there visibly ("moved to Pune when I was 21... so that's... six years now? Yeah, six"), showing the derivation step rather than skipping it.
- **Explicitly uses the age/timeline facts:** yes — both "21" and the implied use of her current age are present in the reasoning shown.
- **Produces a numerical duration:** yes — "six," stated twice for emphasis/self-correction.
- **Does the "three years" behavior recur:** **no.**
- **Minor, non-disqualifying oddity:** the opening "Born here, actually — Nashik" is a slightly confusing phrasing (Nashik is her hometown, not "this city" which the question refers to — Pune), but it does not contradict any established fact and the substantive answer that follows is correct and unambiguous; not counted as a factual error.
- **Payload verified:** `payload_matches_request = true` — the exact `L` provider output was confirmed present in the real captured request before this response is treated as valid evidence.

## Interpretation

**Classification: L1 — evidence supporting the scalar-label interpretation.** The labeled payload produced a materially different, arithmetically correct response, and the original B "three years" error did not recur, while every other input (facts, selection, engine/persona version, message text, absence of history/memory/profile) was held identical to the Phase 14 B/X calls.

**The `27 − 24 = 3` competing explanation from Phase 15 remains explicitly documented and is not dismissed by this result.** This single call does not rule out that B's original error came from anchoring to "24" (the corporate-job-departure age) rather than genuinely failing at 27−21 arithmetic in general — it only shows that, in this one instance, with age explicitly labeled, the model used the correct pair of numbers (27, 21) and did not reproduce the 24-anchored error. Whether explicit labeling made the *correct* pair of numbers more salient (consistent with L1/B2) or whether this is simply generation-to-generation variance unrelated to labeling (an L3-adjacent possibility that a single sample cannot fully exclude) cannot be distinguished with n=1.

## Comparison with Phase 14 B and X

| Condition | Age presentation | Response | Duration stated | Correct? |
|---|---|---|---|---|
| Phase 14 B | Unlabeled ("27") | "About three years..." | 3 (wrong) | WRONG |
| Phase 14 X | Explicit JSON key (`"identity.age":27`) | "Been here since I was 21..." | none stated | CORRECT (no fabricated number) |
| Phase 16 L | Explicit label ("Age: 27") | "...so that's... six years now? Yeah, six." | 6 (correct) | CORRECT |

## What this test supports

- A single, controlled, payload-verified instance in which explicitly labeling scalar fields (specifically age) in the structured rendering coincided with the model correctly deriving and stating the previously-miscalculated duration, with no other input variable changed.
- This is consistent with, and adds a second data point in favor of, the B2 (structured-context interpretation) hypothesis from Phase 15, strengthening it from "plausible, based on a source-code-verified structural difference" to "plausible, now with one supporting behavioral observation."

## What this test does NOT establish

- **Not proof of causation.** LLM generation is stochastic; a single call cannot rule out that B would have occasionally answered correctly anyway, or that L would occasionally answer incorrectly on a different sample.
- **Does not rule out B1** (a general arithmetic-derivation limitation) as a contributing factor in the original B failure — it is possible labeling helped for reasons other than "making 27 identifiable as age" (e.g., subtly changing which numbers the model attends to first).
- **Does not establish that scalar labeling should be adopted more broadly** — this is a single-scenario, single-call diagnostic, explicitly scoped by instruction to avoid becoming a broader formatting experiment.
- **Does not reproduce or test determinism** — no repeat call was made (none was authorized), so response-to-response variance for this exact labeled condition remains unmeasured.

## Exact API-call count

**1.**

## Files modified

- **Created:** `src/main/kotlin/com/pinkdreams/chat/context/LabeledScalarDiagnosticProvider.kt` (new, isolated; does not modify `PersonaContextRenderer.kt`).
- **Created:** `docs/Persona update POC/PHASE16_EXECUTION.csv`, `docs/Persona update POC/PHASE16_LABELED_SCALAR_DIAGNOSTIC.md` (this document).
- **Modified:** `src/main/kotlin/com/pinkdreams/testchat/TestChatService.kt` — one additive `else if (variant == "L")` branch, not touching the A/B/C/D/X/FR branches. `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase16LabeledScalarDiagnostic()` only.
- **Not modified:** `PersonaContextRenderer.kt`, any field mapping, any registry seed value beyond the same isolated in-memory `repository.update()` pattern already used in Phases 8/13/14 (not persisted anywhere), any historical Phase 10–15 document.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 15) was modified. Exactly one API call was made, as authorized, and no retry occurred despite the result being notable.

**Stopping here per instruction — this is not extended into a broader formatting experiment.**
