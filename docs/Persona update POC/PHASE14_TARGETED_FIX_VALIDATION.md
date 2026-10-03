# Phase 14 — Targeted 4-Call Validation of Phase 13 Fixes

**Exactly 4 real generation calls made, as authorized. All 4 succeeded. All 4 payloads verified against the actual captured request content before being accepted.**

---

## 1. Objective

Determine whether the two Phase 13 retrieval fixes actually change the model's answers for the two scenarios they targeted — nothing broader.

## 2. Exact four-call protocol

1. Friends fix, SS-M (variant B), "Do you rent or own your place?" (general_chat)
2. Friends fix, SR-M (variant X), same prompt
3. Timeline fix, SS-M (variant B), "Been in this city long?" (general_chat)
4. Timeline fix, SR-M (variant X), same prompt

Each fix applied in its own fresh `World()`, on top of the original (unchanged) Phase 10E fixes, matching Phase 13's design exactly. A deterministic, zero-cost pre-check (confirming the intended fields would be selected and no restricted field would appear) was run and passed for both fixes *before* any of the 4 calls were made.

## 3. Payload verification

For all 4 calls: `payload_matches_request = true` — the exact string returned by the relevant provider (`ResolvedPersonaContextProvider(B)` or `SelectiveRawContextProvider`) was confirmed present, verbatim, inside the real captured system block that was actually sent to OpenRouter (via `HybridLlmClient`'s always-on capture, same mechanism proven in Phase 10C). No restricted field (`intimate_anatomy`/`adult_intimacy`) appeared in any of the 4 selected-field lists.

## 4. Friends SS-M result

**Fields present:** `..., life_context.occupation, life_context.friends`.
**Response:** *"Rent. Two-bedroom flat in Kothrud with my flatmate Ritu. Split the rent down the middle, which keeps it manageable on a junior architect's salary. We've been there about two years now..."*
**Classification: CORRECT.** Uses the retrieved fact (Ritu, shared two-bedroom flat, ~2 years) accurately; no contradiction.

## 5. Friends SR-M result

**Fields present:** identical to SS-M.
**Response:** *"I rent. A small two-bedroom flat in Kothrud that I share with my flatmate Ritu... Plus, she has a cat and I've grown attached..."*
**Classification: CORRECT.** Same accurate use of the retrieved fact.

**Friends fix: 2/2 CORRECT. The retrieval gap is closed and the model correctly uses the newly-available fact in both variants.**

## 6. Timeline SS-M result

**Fields present:** `..., life_context.occupation, identity.age, life_context.important_life_events` — both required fields confirmed present (not inferred — read directly from `selected_field_ids`).
**Response:** *"Not long, no. About three years in Pune proper — moved here at 21 for architecture school. Before that, Nashik."*
**Classification: WRONG.** The response states "moved here at 21" (correctly citing the retrieved fact) in the same breath as "about three years" — but `identity.age` (27) was also present in this same context, and 27 − 21 = 6, not 3. The two facts needed to derive the correct duration were both confirmed present, and the model's own stated duration is internally inconsistent with its own stated starting point.

## 7. Timeline SR-M result

**Fields present:** identical to SS-M.
**Response:** *"Been here since I was 21 — came to Pune for architecture school and just never really left. It's home now, even though I still get a little nostalgic for Nashik sometimes."*
**Classification: CORRECT.** Correctly cites "since I was 21" (the real fact) and does not state any specific duration figure — avoids fabricating a wrong number entirely. This does not directly answer "long?" with an explicit "yes, N years," but it introduces no contradiction and grounds the answer in the real, retrieved fact.

**Timeline fix: 1/2 CORRECT, 1/2 WRONG.** The retrieval gap is closed in both variants (both facts are confirmed present in both), but only one of the two responses correctly avoided fabricating a wrong duration.

## 8. Phase 10E → Phase 14 comparison

| Scenario | Variant | Phase 10E (fields absent) | Phase 14 (fields present) |
|---|---|---|---|
| Rent or own | SS-M (B) | WRONG (implied solo ownership) | **CORRECT** |
| Rent or own | SR-M (X) | WRONG (implied solo ownership) | **CORRECT** |
| Been in city long | SS-M (B) | WRONG (wrong duration, no relevant fields) | **still WRONG** (wrong duration, now with both relevant fields present) |
| Been in city long | SR-M (X) | WRONG (wrong duration, no relevant fields) | **CORRECT** (avoids a wrong number, grounds in the real fact) |

## 9. Correct / Ambiguous / Wrong table

| Call | Classification |
|---|---|
| friends/B | CORRECT |
| friends/X | CORRECT |
| timeline/B | WRONG |
| timeline/X | CORRECT |

**3 of 4 CORRECT, 1 of 4 WRONG, 0 AMBIGUOUS.**

## 10. Retrieval-vs-model-behavior classification

| Call | A: fact absent | B: fact present, not used/derived correctly | C: insufficient evidence |
|---|---|---|---|
| friends/B | — | — (fact present AND used correctly) | — |
| friends/X | — | — (fact present AND used correctly) | — |
| timeline/B | — | **B — confirmed.** Both `identity.age` (27) and `life_context.important_life_events` ("moved at 21") were verified present in `selected_field_ids` for this exact call. The response used one of the two facts correctly (the starting point) while stating an incorrect duration inconsistent with the other. | — |
| timeline/X | — | — (facts present; response avoided the derivation entirely rather than getting it wrong) | — |

**`timeline/B` is the first confirmed Question-B (model-behavior) observation in this entire Phase 10–14 evidence chain.** It meets the strict bar established across every prior phase: the required facts are not inferred to be present from response content — they are read directly from the same `selected_field_ids` column used throughout this program, verified via the same provider-to-request equivalence check proven in Phase 10C/13, for this exact call.

## 11. What the four calls establish

- The friends lexical-coverage fix (Phase 13, Fix #1) **resolves its target scenario in both variants** — retrieval was the entire cause, and closing it fixed the answer.
- The timeline mapping fix (Phase 13, Fix #2) **closes the retrieval gap in both variants**, but the model's use of the retrieved facts is **inconsistent between variants** for this single scenario — one variant (X) handled the now-available facts safely (grounding without a specific wrong number), the other (B) did not (stating an internally-inconsistent duration).
- This is the first case in the entire Phase 10–14 program where a factuality failure survives after the relevant fields are confirmed present in context — a genuine, evidence-backed Question-B observation, not a mapping gap.

## 12. What remains unknown

- Whether `timeline/B`'s failure is a one-off (n=1 for this specific variant/scenario pair) or a reproducible pattern — not established at this sample size; per the no-scope-creep instruction, no additional calls were made to check.
- Whether the specific wording of `life_context.important_life_events`'s value (a bare "Moving to Pune at 21..." fact listed among five unrelated life events, with no explicit "years ago" framing) makes correct derivation harder than it would be with a differently-worded fact — not tested.
- Whether variant B and variant X systematically differ in how reliably they perform this kind of two-fact arithmetic, or whether this single pair is not representative — not established.
- Whether `timeline/X`'s avoidance strategy (citing the fact without deriving a number) is a general, reliable safe-fallback behavior, or coincidental to this one generation — not established.

## 13. API count

**4 (as authorized). All 4 succeeded. 0 retries. 0 additional calls of any kind.**

## 14. STOP status

No STOP condition was triggered — all 4 calls succeeded, all 4 payloads verified, no scope creep occurred (no mapping, description, resolver, or prompt was modified after seeing the answers; no scenario was added; no call was rerun). Per the standing instruction, this phase stops here — no architecture recommendation, no further experiment authorized by this document.

---

## Final report

- **API calls made:** 4 (exactly as authorized).
- **All 4 succeeded:** yes.
- **Friends issue:** resolved — 2/2 CORRECT in both variants.
- **Timeline issue:** partially resolved — retrieval gap closed in both variants; answer correctness improved from 0/2 to 1/2 CORRECT (SR-M/X), with SS-M/B now constituting the program's first confirmed present-fact/model-use (Question B) observation rather than a retrieval gap.
- **Genuine present-fact/model-use observation:** yes, exactly one (`timeline/B`), confirmed via the same field-presence and payload-equivalence evidence standard used throughout Phases 10–13.
- **Payload mismatches:** none — 4/4 verified.
- **Files created:** `docs/Persona update POC/PHASE14_EXECUTIONS.csv`, `docs/Persona update POC/PHASE14_TARGETED_FIX_VALIDATION.md`.
- **Files modified:** `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` (added `applyOriginalPhase10eFixes()` and `phase14TargetedFixValidation()` only — additive). No historical Phase 10E/11/12/13 document modified. No mapping, description, resolver, or seed file modified.
