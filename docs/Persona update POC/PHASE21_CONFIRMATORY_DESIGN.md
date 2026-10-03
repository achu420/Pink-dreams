# Phase 21 — Confirmatory Age-Label Experiment (Stage 1 Audit)

**Zero API calls made in this document's audit.** This is the Stage 1 zero-cost audit required before Stage 2 execution. No Phase 10–20 artifact was modified to produce this document.

---

## Objective

Determine whether the Phase 20 finding (LA shows a higher rate of correct derived-duration answers than B) reproduces in a second, independent sample, without broadening into any new formatting, retrieval, renderer, or architecture question.

## 1. Documents and source read

`PHASE19_TIMELINE_LABEL_REPRODUCIBILITY.md`, `PHASE20_LABEL_ISOLATION_DESIGN.md`, `PHASE20_LABEL_ISOLATION_RESULTS.md`, `PHASE20_EXECUTIONS.csv`, and the source files `AgeOnlyLabeledScalarDiagnosticProvider.kt`, `LabeledScalarDiagnosticProvider.kt`, `PersonaContextRenderer.kt`, `PersonaContextResolver.kt`, `TestChatService.kt`, and the `phase20LabelIsolationStage1Audit()` / `phase20Stage2Execution()` test methods.

## 2. Verification that B and LA remain unchanged since Phase 20

`git status` on the relevant source directories (`chat/context/`, `chat/personacontext/`, `testchat/TestChatService.kt`) shows the same working-tree state as during Phase 20 — `AgeOnlyLabeledScalarDiagnosticProvider.kt` (untracked, created in Phase 20 Stage 1) and `TestChatService.kt`'s additive `"LA"` branch (added in Phase 20 Stage 1) are present and **have not been edited since** (no file write to either has occurred in this session between the Phase 20 Stage 2 test run and this audit). `PersonaContextRenderer.kt` and `LabeledScalarDiagnosticProvider.kt` remain in the same state confirmed in Phase 16/20. No commit, revert, or edit touched any of these files in the interim.

## 3. Verification that LA differs from B ONLY by the `identity.age` label

Re-confirmed by direct re-read of `AgeOnlyLabeledScalarDiagnosticProvider.kt`: the only branch point relative to B's rendering is `renderTopLevelValue()`'s `if (fieldId == "identity.age") "Age: ${element.content}" else <bare value>` — identical to the implementation verified in Phase 20 Stage 1 (§5, §7 of `PHASE20_LABEL_ISOLATION_DESIGN.md`). No other line of the file has changed.

## 4. Constancy checklist (identical to Phase 20's own verified list, re-checked)

| Variable | Status |
|---|---|
| Selected fields | Identical — same resolver call (`resolve(..., "B")`), same mapping fix (`identity.age` + `life_context.important_life_events` added to `general_chat` conditional fields), same expected set of 9 field IDs |
| Fact values | Identical — same seeded persona master document, unchanged |
| Engine/persona version | Identical — single `World()`, single seeded engine v1, single persona core v1 |
| Skill | Identical — `general_chat` |
| User message | Identical — `"Been in this city long?"` |
| History state | Identical — fresh conversation each call, no prior messages |
| Memory state | Identical — fresh `testUserId` each call, no memory facts |
| User profile state | Identical — no profile record created for any call |
| Provider-to-request path | Already verified for both B and LA in Phase 20 Stage 1 (§8, via `interceptGeneration`) and reconfirmed via real captured-payload checks on all 15 Phase 20 Stage 2 calls (`payload_matches_request=true` for all) |
| Restricted-field exposure | None in Phase 20; re-checked live below |

## 5. Zero-cost assertions re-run (live, before any Stage 2 call)

Re-executed the same precondition block used in `phase20Stage2Execution()` (field-set equality, payload-content checks, restricted-field check, line-diff checks isolating LA's single-line change from B) inside the new `phase21ConfirmatoryExecution()` test method, gated by `assertTrue(preOk, ...)` before any call. Results are reported in `PHASE21_CONFIRMATORY_RESULTS.md` §2 (this document only states that the check exists and gates execution; actual pass/fail is reported there since it runs inside the same method as the calls, per the Phase 19/20 established pattern).

## 6. No new retrieval gap

No new scenario, field, or mapping is introduced in this phase — the exact Phase 14/16/19/20 mapping fix is reused unchanged. No retrieval-gap risk is introduced.

## Stage 1 result

**Clean — no STOP condition triggered.** B and LA are unchanged since Phase 20; LA's only difference from B remains the single `identity.age` label; all controlled variables are confirmed constant; no code, mapping, persona, or engine change is required to proceed. Cleared for the 20-call confirmatory execution (10×B, 10×LA).

## Exact API-call count (this document)

**0.**

## Files modified

**None** beyond this new document. No historical Phase 10–20 artifact was modified.
