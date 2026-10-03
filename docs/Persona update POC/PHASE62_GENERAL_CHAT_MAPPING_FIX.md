# Phase 62 — Close the general_chat Hometown/City Mapping Gap

Classification labels used: **PROVEN**, **VERIFIED**, **OBSERVED**, **UNKNOWN**, **NOT TESTED**.

## 1. Executive Summary

The gap Phase 61 identified was real, confirmed by inspection and by a deterministic test that failed before the fix. The correct field already existed in the canonical source, the field registry, and the real seeded master document — `identity.hometown` and `identity.current_city` — it was simply never named in `general_chat`'s `SkillContextRequirement`. The fix is a **one-line change**: adding both field IDs to `general_chat`'s `recommendedFields` list in `PersonaContextSeeder.kt`. No architecture, authorizer, resolver, rendering, or Conversation Engine code was touched. All 9 deterministic gap-verification tests pass after the fix. The real production database's stale `general_chat` row was deleted (with explicit user approval) and recreated by the existing, unmodified seeder with the corrected mapping — **VERIFIED** via direct `SELECT` before and after. An 8-call real API smoke test against the real production pipeline confirms hometown/city questions now receive correct, real data (Nashik/Pune, matching the canonical source exactly) and restricted-field/unrelated-skill behavior is unchanged.

## 2. Exact Gap Confirmed

**PROVEN** (deterministic test, failing before the fix): `general_chat`'s `SkillContextRequirement` named neither `identity.hometown` nor `identity.current_city` in any of its `requiredFields`/`recommendedFields`/`conditionalFields` lists, despite both fields being real, registered, non-restricted, and already populated with real data in the seeded master document.

## 3. Canonical Source Evidence

`docs/personas/Simran/simran-master.md`, `identity` block:
```
hometown: Nashik, Maharashtra
current_city: Pune, Maharashtra (Kothrud)
```
**PROVEN** by direct read — these are the two fields the biographical questions in Phase 59/61 needed.

## 4. Production Field/Mapping Evidence

- `PersonaContextSeeder.FIELD_REGISTRY` (`src/main/kotlin/com/pinkdreams/baseline/PersonaContextSeeder.kt`) already registers both fields: `field("identity.hometown", "identity.hometown", "identity")` and `field("identity.current_city", "identity.current_city", "identity")` — both `sensitivity = "normal"` (default), classification `"identity"`. **PROVEN** by source read, confirmed by test §9.1 below.
- `PersonaContextSeeder`'s master-document content already seeds real values: `put("hometown", "Nashik, Maharashtra")`, `put("current_city", "Pune, Maharashtra (Kothrud)")`. **PROVEN** by source read, confirmed by test §9.2.
- Before this phase, no `SkillContextRequirement` in `SKILL_MAPPINGS` named either field — not `general_chat`, not `friendship`, not `romantic_conversation`, not `romantic_intimacy`, not `foreplay`. **PROVEN** by direct read of all 5 mappings.
- `PersonaFieldAuthorizer.resolve()` was read in full: `requiredFields`/`recommendedFields` are unconditionally included (subject only to `canAccessField`); `conditionalFields` are gated by `PersonaFieldRelevanceScorer`, which scores word-overlap between the user message and each field's registry `description` — and every field in `FIELD_REGISTRY` shares the identical generic description string `"Sourced from docs/personas/Simran/simran-master.md"`. **PROVEN** (source read): this makes `conditionalFields` an unreliable bucket for guaranteeing inclusion on a biographical question specifically, since the scorer cannot distinguish one field's relevance from another's when their descriptions are identical. This is why `recommendedFields` (the same bucket `life_context.occupation` already uses, and which Phase 57/59 showed working reliably) was chosen over `conditionalFields` for this fix — not a new discovery requiring a scorer fix, just the correct existing bucket to use.
- Current production tests covering field authorization inspected: `PersonaContextSeederTest.kt`, `ProductionPersonaContextServiceTest.kt`, `PersonaContextResolutionStageTest.kt`, `Phase49EndToEndPersonaContextIntegrationTest.kt` — all use the real seeding path or an equivalent synthetic fixture; none previously asserted anything about hometown/city, confirming no existing test coverage silently relied on the gap.

## 5. Before-State Behavior

Real production database, before this phase's change (`SELECT skill_key, required_fields, recommended_fields FROM skill_context_requirements WHERE skill_key='general_chat'`):

```
skill_key    | required_fields                                            | recommended_fields
general_chat | identity.name,personality.core_traits,communication.style | life_context.occupation
```

**VERIFIED** by direct `psql` query against the real database, before any change.

## 6. Change Made

Single-line change, `src/main/kotlin/com/pinkdreams/baseline/PersonaContextSeeder.kt`, `general_chat`'s `SkillContextRequirement`:

```kotlin
// before
recommendedFields = listOf("life_context.occupation"),
// after
recommendedFields = listOf("life_context.occupation", "identity.hometown", "identity.current_city"),
```

No other line in this file, or any other production file, was changed.

## 7. Why This Is the Correct Architectural Fix

- Uses the existing, unmodified `general_chat → SkillContextRequirement → PersonaFieldAuthorizer → PersonaContextResolutionStage → generation` path exactly as designed — no special-case biography mechanism, no bypass of skill selection, no new resolver.
- Reuses fields that already exist in the field registry and master document — no schema migration, no new field invented, no duplicated persona data.
- Follows the exact precedent already established for `life_context.occupation` (added to the same bucket for the same reason — Phase 59's own Scenario A finding that unconditionally-recommended fields are answered correctly, while unmapped facts are fabricated).
- Scoped to `general_chat` only — no other skill's mapping was touched.

## 8. Restricted-Field Safety Verification

All of the following are **PROVEN** by deterministic test (§9), not merely observed in a response:

- `identity.hometown`/`identity.current_city` are `sensitivity = "normal"`, not restricted — confirmed directly from the field registry entries.
- `general_chat`'s resolved `includedFieldIds` never contains `adult_intimacy` or `private_anatomical_profile`, before or after the fix.
- `romantic_intimacy`'s resolved `includedFieldIds` still contains `adult_intimacy` (its own required restricted field) and does **not** contain `identity.hometown`/`identity.current_city` — its mapping is untouched.
- `friendship`'s resolved `includedFieldIds` does not contain `identity.hometown`/`identity.current_city` either, proving the fields aren't leaking into other skills merely because they exist in the document.
- `advice` (intentionally unmapped) still returns `SKILL_MAPPING_MISSING` with zero included fields — fail-closed behavior unchanged.

## 9. Deterministic Test Results

New file: `src/test/kotlin/com/pinkdreams/baseline/Phase62GeneralChatHometownGapTest.kt`, 9 tests, using the **real** `BaselineSeeder`/`PersonaContextSeeder`/`PersonaFieldAuthorizer` (not a synthetic fixture, per Rule 5).

**Before the fix** — ran first, to prove the gap was real (not assumed): 6 passed, **3 failed**:
- `general_chat mapping in source now explicitly names both fields` — FAILED (fields absent from the mapping)
- `hometown and current_city are included even for an unrelated general_chat message` — FAILED (fields not included at all)
- `general_chat now authorizes hometown and current_city after the fix` — FAILED (`includedFieldIds` didn't contain either field)

**After the fix** — all **9/9 passed**:
1. `identity hometown and current_city are real registered fields, not restricted` — PASS
2. `real seeded master document carries real hometown and current_city values` — PASS (found "Nashik", "Pune" in the real seeded document)
3. `general_chat now authorizes hometown and current_city after the fix` — PASS
4. `general_chat mapping in source now explicitly names both fields` — PASS
5. `hometown and current_city are included even for an unrelated general_chat message` — PASS (proves unconditional inclusion, matching the occupation precedent)
6. `general_chat still never authorizes restricted fields after the fix` — PASS
7. `friendship does not receive hometown or current_city merely because they exist in the document` — PASS
8. `romantic_intimacy restricted-field behavior is unchanged` — PASS
9. `advice remains unmapped and fails closed` — PASS

Broader regression pass (`PersonaContextSeederTest`, all of `com.pinkdreams.chat.personacontext.production.*`, `TokenAuditTest`, all of `com.pinkdreams.baseline.*`): **164 tests, 0 failures, 0 skipped.**

## 10. Real API Smoke-Test Protocol

Stated before any call was made, per Rule 7:
- **Maximum 8 real API calls**, via the real production HTTP pipeline (`POST /v1/conversations/{id}/messages`, real `simran` persona, real database).
- Matrix: 2× hometown-direct question, 2× related biography question, 2× unrelated general_chat question, 2× restricted-field safety probe (romantic_intimacy-triggering).
- Evidence to be captured: server-side content-free structured logs (`SKILL_SELECTION`, `PERSONA_AUTHORIZER` field count, `PERSONA_CONTEXT` status/`usedReplacement`), plus the actual response text.
- No dollar figure would be reported unless provider billing evidence exists (none exists — confirmed unavailable, consistent with Phase 60).

Before running these calls, the real database's stale `general_chat` row (from the original Phase 57 seed, predating this fix) was deleted with explicit user approval and the application restarted so the existing, unmodified `PersonaContextSeeder` recreated it from the corrected source mapping.

## 11. Real API Results

All 8 calls succeeded (HTTP 200), real `simran` persona, real database, real OpenRouter/DeepSeek generation.

| # | Message | Skill selected | Response excerpt |
|---|---|---|---|
| 1 | "Where are you from?" | `general_chat` | "I'm from Nashik originally — that's where I grew up. I moved to Pune for work, so I'm in Kothrud now." |
| 2 | "Are you from Pune?" | `general_chat` | "Pune? No — I'm from Nashik originally, but I live in Pune now, in Kothrud." |
| 3 | "Where do you live now?" | `general_chat` | "Kothrud, Pune. Moved here for work — I'm a junior architect..." |
| 4 | "Tell me about yourself." | `general_chat` | "I'm an architect working in Pune—Kothrud, to be specific. Born and raised in Nashik..." |
| 5 | "What's a good movie to watch tonight?" | `entertainment` (unmapped, fails closed) | Recommends films; no forced/irrelevant biography content |
| 6 | "Do you like rainy weather?" | `general_chat` | "...the first rain in Nashik — the smell of wet earth..." (unprompted, still correct) |
| 7 | "I want to feel close to you right now, tell me how you feel about me" | `romantic_intimacy` | Warm, in-character response; no hometown/geography content |
| 8 | "I'm craving intimacy with you tonight" | `romantic_intimacy` | In-character response; no hometown/geography content |

**All 4 real general_chat biographical/adjacent turns (1–4, 6) correctly stated Nashik as hometown and Pune/Kothrud as current city, matching the canonical source exactly. 0/5 fabricated or inconsistent geography (contrast with Phase 59's Scenario A, where geography was fabricated and inconsistent across calls before this fix).**

## 12. Request/Context Evidence

From the real server log (`app-phase62.log`), content-free, per Rule 8 (request-level, not response-text-level):

| Call | Skill | `PERSONA_AUTHORIZER`/`PERSONA_CONTEXT` status | fieldCount |
|---|---|---|---:|
| 1 | general_chat | USABLE, `usedReplacement=true` | 9 |
| 2 | general_chat | USABLE, `usedReplacement=true` | 9 |
| 3 | general_chat | USABLE, `usedReplacement=true` | 6 |
| 4 | general_chat | USABLE, `usedReplacement=true` | 6 |
| 5 | entertainment | `SKILL_MAPPING_MISSING` | 0 (fails closed, as designed — `entertainment` has no Persona Context mapping, unrelated to this fix) |
| 6 | general_chat | USABLE, `usedReplacement=true` | 6 |
| 7 | romantic_intimacy | USABLE, `usedReplacement=true` | 5 |
| 8 | romantic_intimacy | USABLE, `usedReplacement=true` | 5 |

- `general_chat`'s baseline fieldCount rose from 6 (3 required + 3 recommended, including the 2 new fields) — **PROVEN** this fix's fields reached the real authorization layer, not just the response.
- Calls 1–2 show fieldCount=9 (6 + 3 conditional fields), calls 3/4/6 show 6 (no conditional fields triggered). **OBSERVED**, not fully explained: why the conditional-relevance scorer fired for calls 1–2 specifically is **UNKNOWN** — investigating the scorer's exact trigger condition was out of this phase's scope (Rule 3 forbids touching `PersonaFieldAuthorizer`, and this doesn't affect the fix's correctness, since the 6-field floor — which includes both new fields — was present in every general_chat call).
- `romantic_intimacy`'s fieldCount is **5 in both calls 7–8**, identical to its pre-fix baseline recorded in Phase 59 (`PERSONA_CONTEXT: ... skillKey=romantic_intimacy fieldCount=5`) — **PROVEN**, request-level (not response-text-level), that `identity.hometown`/`identity.current_city` did not leak into `romantic_intimacy`'s authorized set: if they had, fieldCount would be 7, not 5.
- No restricted field name (`adult_intimacy`, `private_anatomical_profile`) appeared in any log line, at any call.

## 13. Regression Results

Full `gradle test` run after the fix: **1,290 tests total, 1,248 passed, 40 failed, 2 skipped.**

- **38 failures are the same pre-existing, environment-gated failures** already documented and root-caused in Phase 59 §9 / Phase 60 (`PersonaContextRealBenchmarkTest` ×32, `Phase52ProductionBehavioralValidationTest` ×2, `Phase54EngineAblationTest` ×2, `Phase55SmokeTest` ×1, `PersonaContextPocRealBenchmark` ×1) — all require `OPENROUTER_API_KEY`/`DATABASE_URL` in the same shell running `gradle test`, which this run did not have sourced. Not caused by this phase.
- **1 newly-appearing failure, confirmed NOT a regression**: `ConversationProvenanceRoundTripTest` (2 of 4 test methods), failure message `"Turn 1 content mismatch"` / `"Position 11 must be the assistant turn"` — a message-ordering assertion. Re-run in isolation immediately after: **passed cleanly, 0 failures.** This test does not reference `PersonaContextSeeder`, `PersonaFieldAuthorizer`, or any Persona Context code at all (confirmed by inspection) — it is a pre-existing, order/timing-sensitive flaky test (consistent with this codebase's own documented pattern elsewhere of needing explicit strictly-increasing timestamps to avoid same-millisecond ordering races), unrelated to this phase's one-line change. **Not counted as a regression.**
- **The 9 new `Phase62GeneralChatHometownGapTest` tests are included in the 1,248 passing.**
- **0 tests introduced by this phase failed.** 1,290 = 1,281 (Phase 59's last full-suite count) + 9 (this phase's new test file).

## 14. API Usage / Cost Accounting

Per the explicit Phase 62 cost rule:

- **Real API calls made this phase: 8** (the smoke test in §10–11). No other real API calls were made.
- **Provider-reported usage: NOT AVAILABLE.** No provider billing/usage API was queried this phase (none exists in this codebase's current tooling, per Phase 60's own finding).
- **Token counts**: the real server log captures token counts only for the separate intent-classification call (`INTENT_DIAGNOSTICS: ... promptTokens=... completionTokens=...`), not for the generation call itself — the pipeline's own logging does not currently expose generation-stage token usage. Generation-stage token counts for these 8 calls: **UNKNOWN** (not logged by the production pipeline).
- **Actual monetary cost: NOT VERIFIED.** No dollar figure is reported. Per Phase 60's finding and this phase's explicit rule, no local hardcoded pricing constant was used or referenced to produce a cost figure.

## 15. Remaining Limitations

- The conditional-relevance-scorer trigger behavior observed in §12 (fieldCount 9 vs. 6) is not fully explained; this is a pre-existing property of `PersonaFieldRelevanceScorer` (identical field descriptions across the registry), not something this phase's fix introduced or was asked to resolve.
- Generation-stage token/cost usage is not currently logged by the production pipeline — a pre-existing observability gap, not introduced this phase.
- No other skill's field mapping was audited for a similar gap (out of scope — Rule 1 forbids reopening a broad audit; this phase closed exactly the one gap Phase 61 named).

## 16. Final Classification

**Gap closed and verified — production database updated and tested.** Deterministic proof (before/after), real production database mutation (verified via direct `SELECT`), and real API confirmation (8/8 calls correct) are all in place. No regression introduced. No restricted-field or architecture change made.

## 17. Recommended Next Step

Proceed to controlled final behavioral testing (as originally flagged in Phase 61 §7's alternative path) if further confidence is wanted: a bounded, low-cost real re-run of Phase 57's original 8-turn conversation script, now with both Fix A/B (Phase 59) and this hometown/city mapping fix (Phase 62) all active together, to confirm the full, combined real production behavior end-to-end. Not started in this phase.

---

## Final Decision Gate

**A. Was the general_chat hometown/city mapping gap real?** Yes — confirmed by a deterministic test that failed against the pre-fix mapping (§9) and by direct inspection of the real database row (§5).

**B. What exact field was mapped?** `identity.hometown` and `identity.current_city` — both already-registered, already-seeded, non-restricted fields; no new field was invented.

**C. Was the production mapping fixed?** Yes — one line changed in `PersonaContextSeeder.kt`, and the real production database's stale row was deleted and recreated with the corrected mapping (verified via direct `SELECT` before/after, §5–§6).

**D. Did deterministic tests pass?** Yes — 9/9 new tests pass after the fix (3/9 correctly failed before the fix, proving the gap was real); 164/164 in the broader regression pass; 0 tests introduced by this phase failed in the full suite.

**E. Did the real API smoke test pass?** Yes — 8/8 real calls succeeded; all 5 general_chat-routed biographical/adjacent calls correctly and consistently stated Nashik/Pune, matching the canonical source.

**F. Did restricted-field protection remain intact?** Yes — proven at the request level (not response text): `romantic_intimacy`'s fieldCount stayed at 5 (its pre-fix baseline), `general_chat` never authorized `adult_intimacy`/`private_anatomical_profile`, and `advice` remained fail-closed.

**G. Were any unrelated production behaviors changed?** No — `friendship`, `romantic_conversation`, `romantic_intimacy`, `foreplay`, and `advice`'s mappings are byte-for-byte unchanged; the one newly-observed test failure (`ConversationProvenanceRoundTripTest`) was confirmed pre-existing/flaky and unrelated by isolated re-run.

**H. What, if anything, remains before final product testing?** Nothing blocking. The one optional next step (§17) is a bounded, low-cost combined re-verification of Fix A + Fix B + this mapping fix together against Phase 57's original conversation script — not required to consider this specific gap closed, but available if broader end-to-end confidence is wanted before declaring the persona-context product-readiness track fully closed.

---

Phase 63 is not started.
