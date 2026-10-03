# Phase 22 — Independent Derived-Numeric Scenario Audit

**Zero API calls in this audit**, except the zero-cost, network-free `interceptGeneration=true` provider-to-request check used to verify the selected candidate (identical mechanism to Phase 10C/20/21 — the real pipeline runs end to end, but no request ever reaches OpenRouter). No Phase 10–21 document, historical CSV, production mapping, resolver, renderer, persona data, or engine logic was modified.

---

## Objective

Determine whether a genuinely independent second derived-numeric scenario now exists in the current persona data/field registry/skill mappings, without re-opening the retrieval/mapping work Phase 18 deliberately left closed.

## Method

Re-read `PHASE18_DERIVED_NUMERIC_INVENTORY.md`, `PHASE19_TIMELINE_LABEL_REPRODUCIBILITY.md`, `PHASE20_LABEL_ISOLATION_RESULTS.md`, `PHASE21_CONFIRMATORY_RESULTS.md`, the complete current `PersonaFieldRegistryRepository` seed (`seedPersonaFieldRegistry()`, 27 entries — unchanged since before Phase 18), the complete current `SkillContextRequirement` seed for all 7 POC skills (`seedSkillContextRequirements()`), and the seeded persona master document content (`buildSimranMasterContent()`). Every numeric-adjacent value across every *registered* field was individually checked — not just fields matching Phase 18's original scenario list, since new fields (`life_context.career_history`, `life_context.daily_routine`, `life_context.family`) were added to the registry in the "Full Validation audit" Phase 1 work referenced in the seed file's own comments, after Phase 18 last inventoried the corpus.

## Candidate inventory and classification

| # | Candidate | Fact pair | Classification | Reasoning |
|---|---|---|---|---|
| 1 | **"Is there a big age gap between you and your sister in your family?"** (sister age gap: Anjali 29 − Simran 27 = 2) | `identity.age` (27, top-level scalar) + Anjali's age (29, embedded in `life_context.family`'s array-of-objects prose) | **A — CLEAN** | See detailed verification below. |
| 2 | "How much older are you than your brother?" (Arjun age gap: 27 − 24 = 3) | `identity.age` (27) + Arjun's age (24, embedded in `life_context.family`) | **E — OTHERWISE UNSUITABLE** | Technically retrievable and unambiguous, but the derived answer (3) and the exact number pair (27, 24) are **identical** to the already-tracked, unresolved competing explanation from Phase 15 ("B's original 'three years' error may stem from anchoring to 24, the corporate-departure age, rather than genuine 27−21 arithmetic"). Any result on this candidate would be indistinguishable from a recurrence of that specific confound rather than a new, independent data point — using it would contaminate rather than clarify the existing 27−24=3 question. Excluded on that basis alone, not because of any retrieval or ground-truth problem. |
| 3 | "You've been at your current studio for like ten years, right?" (studio tenure: 2020 join year, requires "current year") | `life_context.career_history` (contains "2020") | **B — RETRIEVAL-CONFOUNDED** | `life_context.career_history` **is** already a native conditional field for both `about` (line 410) and was the subject of Phase 18's original analysis for `general_chat` (where it is NOT mapped). However, even under `about` (where it IS nominally listed), its **default description equals its own literal field ID** (`"life_context.career_history"`, set by the seed's `entry()` helper's default `description = fieldId`) — the same un-enriched-description defect class previously identified and fixed for `life_context.family`/`life_context.friends` in Phase 8/13 (the "TC-6" pattern). No natural phrasing of a studio-tenure question would share a literal token with `"life_context.career_history"`, so the relevance scorer would not select it under any natural message. Making this candidate usable would require a description-enrichment fix analogous to Phase 13's Fix #1/#2 — exactly the kind of mapping change this phase is scoped to avoid. Separately, it also inherits Phase 18's original "requires an unstated current-year assumption" confound (2019/2020 are absolute years, not an elapsed-time fact; deriving "ten years" requires knowing today's real-world date, which is not a persona fact). Two independent reasons to classify B, not A. |
| 4 | "What's the longest friendship you've had?" / "How long have you known each other?" (Ritu/Neha/Prashant friendship duration) | `life_context.friends` (role descriptions only, no dates) + `life_context.important_life_events` (has "Ritu moving in about two years ago" — a flatmate date, not a friendship-start date) | **C — GROUND-TRUTH-CONFOUNDED** | Unchanged from Phase 18's own finding: no registered field states when any of the three friendships actually began. The only more-precise founding dates live in `_full_document_supplement.timeline_and_narrative_state`, which is not a registered/selectable field. Ground truth remains ambiguous. |
| 5 | "How many hours are you usually out of the house on a work day?" (daily routine: up 7:30, home 6:30–7 → ~11 hours) | Both operands (7:30, 6:30) embedded in the same `life_context.daily_routine` prose string | **E — OTHERWISE UNSUITABLE** | Ground truth is actually unambiguous and the field **is** natively conditional for `general_chat` (line 440) — this is the closest thing to a second "clean" retrieval candidate found. But it fails the mechanism test, not the retrieval test: both numeric operands live inside the *same* string within a single array-classified field, rendered via `renderArray()`/`renderObject()` in **every** condition (B, L, LA) identically — the B-vs-LA/L labeling manipulation only ever touches **top-level `JsonPrimitive` scalars**, and neither operand here is a top-level scalar. LA and B would therefore produce byte-identical output for this scenario's family/routine content (only `identity.age`, an unrelated field for this question, would differ, and age isn't even part of this derivation). This candidate cannot test the age-labeling hypothesis at all — it is not an "artificially label an unrelated scalar" case (per the instruction's specific warning) so much as a case where **no labelable scalar is involved in the derivation whatsoever**. |
| 6 | "What year did you graduate, and how old were you?" (education year 2019 + age 27) | `life_context.education` (2019) + `identity.age` (27) | **C — GROUND-TRUTH-CONFOUNDED** | Deriving "age at graduation" from a 2019 graduation year and a *current* age of 27 requires knowing the real-world current calendar year (to compute "years since 2019" and subtract from 27) — this is not a persona fact and is not fixed/stable the way the timeline scenario's "moved at 21" fact is (that fact directly states the relevant age; this one does not). Same class of confound Phase 18 identified for the studio-tenure candidate, independently confirmed here for education. |
| 7 | Any other phrasing of "how long have you lived in Pune?" using the same age-21-move-in fact | `identity.age` (27) + `life_context.important_life_events`'s "Moving to Pune at 21" | **D — NOT INDEPENDENT** | Explicitly excluded per instruction — this is the existing Phase 14–21 fact pair regardless of wording. |

## Detailed verification of Candidate #1 (the clean candidate)

**Scenario:** *"Is there a big age gap between you and your sister in your family?"* — skill `about`, **no Phase 10E/13/19/20/21 mapping fix applied** (pure baseline seed, unmodified since before Phase 10E).

Verified live, zero-cost, in `phase22CandidateScenarioZeroCostCheck()` (via `interceptGeneration=true`, zero network calls):

1. **Ground truth is unambiguous.** `identity.age` = 27 (Simran). `life_context.family`'s array entry for "older sister" states "29, married, lives in Bombay..." — Anjali's age (29) is explicit, unambiguous prose within a registered field. Derived answer: 29 − 27 = 2 years.
2. **Every required fact is represented by registered/selectable fields.** `identity.age` and `life_context.family` are both registered (`seedPersonaFieldRegistry()`, present since before Phase 18).
3. **Every required field is mapped to the relevant skill.** `identity.age` is a **required** field for `about` (baseline, unconditional). `life_context.family` is a **native conditional** field for `about` (baseline `conditionalFields` list, line 410 of the seed — not a Phase 10E/13 addition).
4. **The fields are actually retrievable under the current mapping.** Confirmed live: `resolver.resolve(..., "about", msg, "B").includedFieldIds` = `{identity.name, identity.age, identity.hometown, identity.current_city, life_context.occupation, life_context.education, personality.core_traits, life_context.family}` — `life_context.family` IS selected. This works because the field's baseline (un-enriched) description defaults to its own literal field ID string, `"life_context.family"`, which tokenizes (on non-word characters) to `{"life_context", "family"}`; the scenario message contains the literal word "family", producing exactly one token-overlap, which the scorer's formula (`(1/3)*0.8 = 0.267`) already clears the 0.25 selection threshold **without any description enrichment**.
5. **No new mapping change is required.** Confirmed — this test ran on a fresh `World()` with zero fixture calls beyond the baseline seed (explicitly, no `applyOriginalPhase10eFixes()` call).
6. **No hidden `_full_document_supplement` field is required.** Both facts come from top-level registered fields (`identity.age`, `life_context.family`).
7. **No inference from missing narrative-only information is required.** Both ages are explicit numbers in the seeded content, not inferred.
8. **The question has not already been used in Phases 14–21.** Confirmed — `life_context.family` was previously used only in Phase 8/13/14 for a *different* scenario ("Do you rent or own your place?" / general implied-family retrieval), never for a numeric age-gap derivation.
9. **Does not depend on the same exact fact pair as the Pune/age-21 scenario.** Confirmed — this uses `identity.age`(27) + Anjali's age(29), a completely different pair and a different derived value (2, not 6), with no overlap with the 21/24/27 triad already tracked.
10. **Can be tested with B vs LA while keeping everything else constant.** Confirmed live: B-vs-LA payload diff for this exact scenario is `bOnly=["27"]`, `laOnly=["Age: 27"]` — identical single-line diff pattern to the established timeline scenario. The `life_context.family` array (containing both Anjali's and Arjun's ages) renders byte-identical in B and LA, since neither condition's labeling manipulation touches array/nested-object rendering (only top-level scalars) — exactly the same structural pattern already relied upon in the Pune-timeline scenario, where the "21" operand similarly lives inside an unlabeled array in all conditions.

**One disclosed nuance, not a disqualifying confound:** because `life_context.family` renders as a single array containing *all four* family members, both siblings' ages (Anjali 29, Arjun 24) are present in the payload simultaneously, in every condition. The task therefore requires the model to correctly attend to the entry explicitly labeled "older sister" rather than "younger brother" — this is a legitimate part of what the scenario tests (entity binding + arithmetic), not an ambiguity in the ground truth itself, since each entry is explicitly and unambiguously labeled by relationship in the source data (`"relationship": "older sister"` / `"younger brother"`). This is noted for transparency, not treated as invalidating the classification.

**Provider-to-request equivalence** (zero-cost, via `interceptGeneration`): confirmed for both B and LA on this exact scenario — the real captured system block contains each provider's output verbatim.

## STOP conditions checked — none triggered

- The corpus **does** contain an independent numeric relationship (Candidate #1).
- Candidate #1 requires **no new mapping** (native baseline `about` conditional field).
- Ground truth for Candidate #1 is unambiguous.
- Candidate #1 is **not** another form of the Pune timeline task (different fields, different fact pair, different derived value).
- Candidate #1 requires **no change** to B or LA's implementation.
- Candidate #1 **can** be isolated to the intended formatting variable (verified live, §"Detailed verification" item 10).
- **No historical artifact required modification** to establish any of the above.

## Result

**A clean (Classification A) independent scenario exists: Candidate #1, the sister age-gap question.** Per instruction, since a clean scenario was found, `PHASE22_GENERALIZATION_DESIGN.md` is produced (design only, not executed).

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE22_INDEPENDENT_SCENARIO_AUDIT.md` (this document)
- `docs/Persona update POC/PHASE22_GENERALIZATION_DESIGN.md` (companion design document, per instruction, since a clean candidate was found)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase22CandidateScenarioZeroCostCheck()` only (additive, zero-cost, no API calls).

## Not modified

Any Phase 10–21 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, `PersonaContextRenderer.kt`, `LabeledScalarDiagnosticProvider.kt`, `AgeOnlyLabeledScalarDiagnosticProvider.kt`.
