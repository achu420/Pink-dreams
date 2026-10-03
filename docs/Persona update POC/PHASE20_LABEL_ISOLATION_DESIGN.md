# Phase 20 — Isolate the Scalar-Label Effect (Stage 1 Audit + Stage 2 Design)

**Zero API calls made in this phase.** Stage 1 ran entirely through `HybridLlmClient.interceptGeneration = true` — the real production pipeline (`TestChatService` → `ChatEngineFactory` → `PipelineChatEngine` → `RepositoryContextAssembler`) executed end to end, but every "generation" call was intercepted and answered locally, never reaching OpenRouter. **Stage 2 is designed below but NOT executed** — it requires separate authorization per standing protocol.

No Phase 10–19 document was modified. `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt` (Phase 16), and `SelectiveRawContextProvider.kt` (Phase 10) were not modified. One new isolated diagnostic class was added: `AgeOnlyLabeledScalarDiagnosticProvider.kt`, wired as an additive `variant == "LA"` branch in `TestChatService.kt`, touching no existing branch (A/B/C/D/X/FR/L all unchanged).

---

## Objective

Determine whether the Phase 14/16/19 divergence between B (unlabeled) and L (Phase 16's fully-labeled diagnostic) is specifically attributable to **scalar field labeling**, and if so, whether labeling the single relevant field (`identity.age`) is sufficient, or whether the effect (if real) depends on L's broader labeling of *all* top-level scalars.

## Question A vs Question B

This phase investigates **Question B only** (model use/interpretation of present facts). Question A (retrieval/context availability) is not in scope — the field set is held identical to Phase 14/16/19 throughout (verified in §1 below).

---

## STAGE 1 — Zero-cost source/experiment audit

### 1. Documents and source read

`PHASE14_TARGETED_FIX_VALIDATION.md`, `PHASE15_TIMELINE_B_ROOT_CAUSE.md`, `PHASE16_LABELED_SCALAR_DIAGNOSTIC.md`, `PHASE17_PRE_REGISTRATION.md` + `PHASE17_SCALAR_LABEL_REPLICATION.md`, `PHASE18_DERIVED_NUMERIC_INVENTORY.md`, `PHASE19_TIMELINE_LABEL_REPRODUCIBILITY.md` + `PHASE19_EXECUTIONS.csv`, and the source files `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `PersonaContextResolver.kt`, `SelectiveRawContextProvider.kt`, `TestChatService.kt`.

### 2. Exactly what differs between A/B/L/X (established from source, not inferred from output)

Direct line-by-line comparison of `PersonaContextRenderer.kt` (production, used by B) and `LabeledScalarDiagnosticProvider.kt` (Phase 16, variant L) confirms:

- **Selection**: identical. L calls `resolver.resolve(..., "B")` verbatim — it never re-derives or re-scores field selection. B's own selection is used unchanged.
- **Grouping/ordering**: identical. Both group by `classification` via `groupBy { it.value.first }.toSortedMap()` (alphabetical by classification), and within each group, field order follows the `LinkedHashMap`/`mutableMapOf` insertion order, which in both B (`PersonaContextResolver.kt:96`, `mutableMapOf` = `LinkedHashMap` in Kotlin) and L (`LabeledScalarDiagnosticProvider.kt:51`, `linkedMapOf`) is the order of `selectedFieldIds`/`includedFieldIds` — the SAME list, since L reuses B's own `resolve(..., "B")` call. Order is therefore provably identical, not merely assumed.
- **Headings**: identical (`classificationToHeading()` copied verbatim, same `"## $heading"` format, same join `"\n\n"` between sections).
- **Nested object/array rendering** (`renderObject()`, `renderArray()`): copied verbatim from `PersonaContextRenderer.kt` into `LabeledScalarDiagnosticProvider.kt` — byte-identical logic, confirmed by direct comparison.
- **The one intentional difference**: `renderValue()`'s `JsonPrimitive` branch in `PersonaContextRenderer.kt` (used by B) returns the bare value only — `fieldId` is accepted but unused. `LabeledScalarDiagnosticProvider.kt`'s `renderTopLevelValue()` for `JsonPrimitive` instead returns `"$label: ${element.content}"`, where `label` is derived from **every** top-level scalar `fieldId`, not age alone.

**Confirmed, not assumed: L labels ALL top-level scalars** (per Phase 16 §"Exact B-vs-diagnostic payload difference": `identity.name`, `identity.hometown`, `identity.current_city`, `identity.age`, `life_context.occupation`, `communication.style` — six fields, all labeled uniformly). There is no age-only special case anywhere in the Phase 16 implementation. This directly answers instruction item 4: **"L" = L-all**, not "age-label-only," and this was verified by re-reading the actual code, not assumed.

Variant X (`SelectiveRawContextProvider`) differs from both B and L far more broadly: raw single-line JSON (`JsonObject(selected).toString()`) instead of multi-line markdown with `## Heading` sections; every field (not just scalars) is labeled by its JSON key; no classification grouping; no nested `**Key:**` bolding (JSON's own object/array syntax replaces it); compact whitespace vs. markdown's blank-line section separators. **X cannot serve as a clean scalar-labeling isolation condition** — this was already established in Phase 15 and is reconfirmed here: X changes too many things at once (structure, format, all-field labeling via JSON keys, whitespace) to attribute any B-vs-X difference to scalar labeling specifically.

### 3. What L changes — checklist (per instruction item 3)

| Change type | Does L change it? |
|---|---|
| Only scalar labels | No — see below |
| ALL scalar labels | **Yes** — every top-level `JsonPrimitive`, confirmed above |
| Section formatting (headings, grouping) | No — identical to B |
| Whitespace | No — identical join/heading logic |
| Ordering | No — identical, same field-ID list, same insertion order |
| Punctuation | No new punctuation beyond the `"Label: "` prefix itself |
| JSON-vs-markdown structure | No — L remains markdown, like B (unlike X) |
| Any other content | No — nested arrays/objects render byte-identical to B |

**Conclusion: L's only change vs. B is uniform scalar labeling across all six top-level scalar fields.** This was not assumed; it was verified by direct source comparison (§2) and is independently reconfirmed by the Phase 16/19 line-diff checks (every B-only line has an L-only counterpart differing by exactly a `"Label: "` prefix, same count, no added/removed lines).

### 4. Competing hypotheses this phase is designed to separate

- **H1** — age/scalar labeling contributes to the result (the label makes "27" identifiable as `identity.age` specifically).
- **H2** — some other B-vs-L formatting difference contributes. Per §3, no such other difference exists between B and L — B and L differ in *nothing* except scalar labeling. **H2 is therefore not separable from H1 using the existing B/L pair**, because there is no other formatting difference to attribute an effect to. H2 remains live only as "labeling matters, but for a scalar OTHER than age" — which is exactly what the L-age vs. L-all comparison designed below is built to test.
- **H3** — the Phase 19 difference is primarily generation variance, not attributable to any formatting change at all.
- **H4** — the model's behavior relates to some other structural property of the rendered context not yet identified. Given §3's exhaustive checklist confirms no other property differs between B and L, H4 has no candidate mechanism within the current B/L/X evidence base; it remains an open "unknown unknown" that this phase cannot rule out, only note as unaddressed.

### 5. Designing the smallest controlled comparison — the L-age intermediate condition

Per instruction item 6, an intermediate condition was investigated: **L-age = B with ONLY `identity.age` explicitly labeled**, leaving every other top-level scalar (`identity.name`, `identity.hometown`, `identity.current_city`, `life_context.occupation`, `communication.style`) as a bare, unlabeled value exactly as in B.

**This was implementable without changing any additional content.** A new isolated class, `AgeOnlyLabeledScalarDiagnosticProvider.kt`, was created: it reuses B's resolver selection verbatim (identical to L), copies the same grouping/heading/nested-rendering logic verbatim (identical to L), and its `renderTopLevelValue()` differs from L's only in a single conditional — `if (fieldId == "identity.age") "Age: ${element.content}" else <bare value, exactly as in B>`. No fact, no selection, no ordering, no heading, no nested content changes. This condition is referred to as **"LA"** in code and going forward.

`PersonaContextRenderer.kt` and `LabeledScalarDiagnosticProvider.kt` were **not modified** to build this — LA is a new, separate, isolated file, following the same pattern established in Phases 7/10/16 (FR, X, L never modify the shared renderer).

### 6. Constancy of all controlled variables across B / L / LA

Verified live in `phase20LabelIsolationStage1Audit()` (zero API cost), using the exact same `World()`, same timeline mapping fix (`identity.age` + `life_context.important_life_events` added to `general_chat`'s conditional fields, matching Phase 14/16/19), same scenario ("Been in this city long?", `general_chat`):

- **Persona facts**: identical — single seeded master document, one persona core version, throughout.
- **Selected fields**: identical — `bFieldIds == {identity.name, personality.core_traits, identity.hometown, identity.current_city, communication.style, narrative_state.unresolved_threads, life_context.occupation, identity.age, life_context.important_life_events}`, matching Phase 14/16/19 exactly (PASS).
- **Engine/persona version**: identical by construction — single `World()`, single seeded engine v1, single persona core v1.
- **User message**: identical string literal for all three conditions.
- **History/memory/user profile**: absent for all three — fresh conversation, fresh `testUserId`, no prior messages, matching Phase 19's construction.
- **Skill**: identical (`general_chat`).
- **Resolver output**: identical — L and LA both call `resolver.resolve(..., "B")` verbatim, never re-deriving selection.
- **Provider payload**: differs from B **only** in the intended formatting manipulation (see §7/§8 for exact verification).

### 7. Exact provider-output diffs (zero-cost, verified in `phase20LabelIsolationStage1Audit()`)

| Comparison | Result |
|---|---|
| B vs LA | Exactly one line differs: B has `27`; LA has `Age: 27`. No other line added, removed, or changed. **PASS.** |
| L vs LA | LA's bare lines (`Simran Kohli`, `Nashik, Maharashtra`, `Pune, Maharashtra (Kothrud)`, `Architect (junior)`, the communication-style sentence) are exactly the un-labeled form of L's corresponding labeled lines (`Name: Simran Kohli`, `Hometown: Nashik, Maharashtra`, `Current city: Pune, Maharashtra (Kothrud)`, `Occupation: Architect (junior)`, `Style: ...`). The `Age: 27` line is identical (present, unchanged) in both L and LA — confirmed absent from both diff sets. **PASS.** |
| No restricted field in B/L/LA | None of the three payloads contain `intimate_anatomy` or `adult_intimacy`. **PASS.** |
| LA determinism | Two successive calls with identical input produce byte-identical output. **PASS.** |

### 8. What each provider actually sends to the model (provider → request equivalence, zero-cost)

Using `HybridLlmClient.interceptGeneration = true` (proven mechanism from Phase 10C), each of B, L, and LA was run once through the **real** `TestChatService.sendMessage()` → `ChatEngineFactory` → `RepositoryContextAssembler` pipeline, with the outbound "generation" call intercepted and answered locally (zero network calls). The captured system block was checked to contain the exact provider output string verbatim:

| Variant | Provider output appears verbatim in captured request |
|---|---|
| B | **true** |
| L | **true** |
| LA | **true** |

This confirms all three conditions' provider output genuinely reaches the request payload unmodified by any downstream assembly step, for this exact scenario/skill/persona/engine combination — not merely that "a provider exists."

### 9. Zero-cost assertions implemented

All of the following were coded as explicit pass/fail checks in `phase20LabelIsolationStage1Audit()` and all passed: identical selected fields (B/L/LA share the same field-ID set by construction); identical scalar values (age value "27" unchanged in all three; every other scalar's value unchanged); identical non-target content (nested arrays/objects byte-identical across all three); no restricted-field changes; deterministic output (LA checked explicitly; B/L already established deterministic in Phases 10C/16/19); provider-to-request equivalence (§8).

### 10. Production renderer modification

**Not modified.** `PersonaContextRenderer.kt` remains exactly as it was after Phase 15's audit. LA is implemented as a new, isolated diagnostic class (`AgeOnlyLabeledScalarDiagnosticProvider.kt`), following the established FR/X/L pattern.

---

## STAGE 1 RESULT: CLEAN — no STOP condition triggered

- The proposed conditions (B, L, LA) **do** isolate the intended variable: LA changes exactly the age label and nothing else, relative to both B (adds one label) and L (removes five labels, keeps one).
- No intermediate provider changes facts or selection — confirmed identical field sets and identical underlying values throughout.
- The provider-to-request path was verified for all three conditions via the proven interceptGeneration mechanism.
- No comparison introduces an uncontrolled formatting difference — LA's only degrees of freedom relative to B and L are exactly the ones documented above.
- The exact B-vs-L difference was established from source, not inferred: uniform all-scalar labeling, confirmed by direct code comparison (§2–§3).
- No historical artifact needed modification to proceed.

**Zero API calls were made anywhere in Stage 1.**

---

## STAGE 2 — Experiment design (NOT executed; requires separate authorization)

### Hypotheses

- **H1** (age-labeling-specific): L-age alone reproduces L-all's improvement over B — i.e., the effect (if any) is driven by labeling `identity.age` specifically, not by labeling the other five scalars.
- **H2** (broader-labeling-dependent): L-age does NOT reproduce the improvement, but L-all does — i.e., the effect (if any) requires labeling scalars beyond age (e.g., overall payload "label density" or salience of the structured-key convention matters, not the age label in isolation).
- **H3** (generation variance): neither L-age nor L-all shows a reliable improvement over B at this sample size — the Phase 16/19 result was consistent with ordinary sampling variance, not a labeling effect at all.
- **H4** (unidentified mechanism): results are inconsistent with H1/H2/H3 in a way that suggests some other, currently unidentified property — flagged as inconclusive, not force-fit into H1/H2/H3.

### Exact conditions

Three conditions, all on the identical scenario/mapping/world setup verified in Stage 1:
- **B** — normal structured B (`ResolvedPersonaContextProvider`, variant "B").
- **LA** — B + only `identity.age` labeled (`AgeOnlyLabeledScalarDiagnosticProvider`, new variant "LA").
- **L** — existing Phase 16 fully-labeled diagnostic (`LabeledScalarDiagnosticProvider`, variant "L").

### Exact scenario

**"Been in this city long?"**, skill `general_chat` — the same scenario used in Phase 14/16/19, per instruction (no new scenario invented). This remains the only scenario in the corpus meeting all three criteria: retrievable facts, a demonstrated B failure, and a demonstrated L success (Phase 18 confirmed no clean independent second derived-numeric scenario exists).

### Proposed API-call count

**15 calls**: 5×B, 5×LA, 5×L — matching Phase 19's per-condition sample size (5) for direct comparability, extended to a third condition. Not yet authorized.

### Predetermined order

Fixed before any call, not altered by observed answers, mirroring Phase 19's design: `List(5){"B"} + List(5){"LA"} + List(5){"L"}`. (B and L repeat Phase 19's exact protocol; LA is inserted as the new middle block so B and L's own call-order relationship to each other is unchanged from Phase 19, aiding cross-phase comparison.)

### Scoring rules

Identical rubric to Phase 19: CORRECT / WRONG / AMBIGUOUS, plus the same sub-flags — "six years" (or equivalent) present; "three years" present; explicit reference to age 27; explicit reference to moving at 21; avoids stating any specific duration (counted as AMBIGUOUS, not CORRECT, per Phase 19's own discipline). Any other materially different answer is recorded as its own note, not force-fit into these categories.

### Interpretation rules

- Report raw CORRECT/WRONG/AMBIGUOUS counts per condition (B/LA/L) before any interpretation, exactly as Phase 19 did.
- **What would support H1**: LA's CORRECT rate is materially higher than B's and comparable to L's (e.g., LA ≥ 4/5 CORRECT, similar to L's Phase 19 result of 5/5).
- **What would support H2**: LA's CORRECT rate remains similar to B's (i.e., the "three years"/AMBIGUOUS pattern persists in LA), while L's CORRECT rate stays high — implying the effect (if real) depends on something beyond the single age label.
- **What would support H3**: neither LA nor L shows a materially higher CORRECT rate than B in this run, i.e., this run's B/L split does not replicate Phase 19's 3/5-vs-5/5 pattern.
- **What would remain inconclusive**: mixed results not cleanly matching any of the above (e.g., LA between B and L with no clear separation at n=5), or any STOP condition firing mid-run.
- No claim of causation will be made regardless of outcome — n=5 per condition is a reproducibility/pattern check, not a powered causal experiment, consistent with Phase 19's own explicit limitation.

### STOP conditions (before or during Stage 2, if authorized)

- A precondition-verification check (field-set equality, payload-content checks, restricted-field check, line-diff checks — same style as Phase 19 §2) fails before any call is made.
- A harness defect is discovered — disclose it, fix only the harness, re-run zero-cost verification, do not silently continue (same standing rule as Phase 19).
- Any call's `payload_matches_request` is false — do not score that call as valid evidence; report and stop rather than substitute or retry silently.
- Any response reveals a restricted field or a contradiction not anticipated by the scoring rubric — report as its own category, do not force-fit.

### What this design does NOT do

- Does not invent a new scenario (per instruction).
- Does not attempt to make a production recommendation from Stage 2 results, if run.
- Does not expand into a broader formatting experiment beyond the three named conditions.
- Does not reinterpret Phase 19's own recorded results in light of any Stage 2 outcome — Phase 19's document stands as-is regardless of what Stage 2 shows.

---

## Explicit statement: has Stage 2 been run?

**No.** Only Stage 1 (zero-cost audit, including the isolated LA provider's implementation and zero-cost verification) has been performed. The Stage 2 design above is a proposal only. No API call has been made under Phase 20. Execution requires separate, explicit authorization, per the standing protocol reaffirmed in this phase's own instructions.

---

## Exact API-call count

**0.**

## Files modified

- **Created:** `src/main/kotlin/com/pinkdreams/chat/context/AgeOnlyLabeledScalarDiagnosticProvider.kt` (new, isolated diagnostic; does not modify `PersonaContextRenderer.kt` or `LabeledScalarDiagnosticProvider.kt`), `docs/Persona update POC/PHASE20_LABEL_ISOLATION_DESIGN.md` (this document).
- **Modified:** `src/main/kotlin/com/pinkdreams/testchat/TestChatService.kt` — one additive `else if (variant == "LA")` branch, not touching the A/B/C/D/X/FR/L branches. `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase20LabelIsolationStage1Audit()` only (additive).
- **Not modified:** `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `SelectiveRawContextProvider.kt`, any field mapping persisted outside the test's own in-memory `World()`, any Phase 10–19 document.

## Explicit confirmation

No production rendering, resolver logic, field mapping, persona data, or engine content was modified. No historical artifact (Phase 10 through Phase 19) was modified. Zero API calls were made. Stage 2 is designed only, not executed, and requires separate authorization before any call is made under it.
