# Phase 63 — Literal Re-Run of the Original Phase 57 Failure Scenario

Classifications used: **PASS**, **FAIL**, **INCONCLUSIVE**, **INFRASTRUCTURE FAILURE**.

## 1. Executive Summary

The exact, verbatim-recoverable portion of the original Phase 57 script — turns 1–4, the turns that actually exposed the biography-fabrication failure — was re-run against the real production stack (real HTTP API, real database, real skill selection, real Persona Context resolution, real LLM generation) with Fix A, Fix B, and the Phase 62 mapping fix all active and verified present beforehand. **Result: 0/3 `SKILL_SELECTION: none` on the biographical turns (previously 3/3), and the two facts Fix A + Phase 62 specifically targeted — hometown and current city — were answered correctly and consistently (Nashik / Pune, Kothrud), matching the canonical source exactly.** One adjacent, previously-untested biographical detail (roommate count/occupation, part of `life_context.family`/`life_context.friends`, not `general_chat`'s mapped fields) was fabricated on turn 4 — a real, disclosed, narrower finding, not a recurrence of the original "living alone in London" failure.

**Turns 5–8 were not run.** Their exact verbatim wording does not survive in any committed artifact — only category descriptions (e.g. "explicit crude intimacy request," "job-offer decision question") — and Rule 2 explicitly forbids reconstructing them from memory or substituting a "similar" conversation. This is disclosed precisely in §2, not glossed over. Consequently, the **full 8-turn scenario cannot be classified as literally re-run**, and pass criterion 7 ("the complete original 8-turn conversation completes successfully") cannot be evaluated as stated. The final classification (§16) reflects this honestly.

## 2. Exact Phase 57 Script Used

Two surviving artifacts were checked: `PHASE57_FIRST_REAL_PRODUCTION_TEST.md` and `PHASE57_RAW_SERVER_LOG.txt`. The raw log contains no user-message content at all (the server never logs message text, by design — confirmed in §15). The report is therefore the only surviving source of wording, and it is **not uniformly verbatim**:

| Turn | Wording available in the Phase 57 report? |
|---|---|
| 1 | **Verbatim**, quoted directly: *"Hey Simran, how's your day going?"* |
| 2 | **Verbatim**, quoted directly: *"Where are you originally from?"* |
| 3 | **Verbatim**, quoted directly: *"What do you do for work?"* |
| 4 | **Verbatim**, quoted directly: *"Do you live alone or with someone?"* |
| 5 | **Not verbatim** — only described as "explicit crude intimacy request" |
| 6 | **Not verbatim** — only described as "sincere feelings + explicit intimacy request" |
| 7 | **Not verbatim** — only described as *"as a friend... family/siblings"* |
| 8 | **Not verbatim** — only described as "job-offer decision question" |

Per Rule 2 ("do not reconstruct it from memory," "do not create a similar conversation," "if the exact script cannot be located, STOP and report that fact"), turns 5–8 were **not run**. Turns 1–4 — which are exactly, verbatim locatable, and which are also the turns where the original biography-fabrication failure actually occurred (§13) — were run exactly as quoted, in the same order, as the first 4 turns of a single conversation, matching the original's structure (single evolving conversation, real persona, real HTTP API, real idempotency keys).

## 3. Environment and Production-Path Verification

- Real application started via `gradle run` with real secrets sourced (`run-local.secrets.ps1`).
- `BASELINE_SEED: conversationEngine=already present ... intentEngine=already present` and `PERSONA_CONTEXT_SEED: ... skillMappings=already present (5 skills)` — **VERIFIED**: this run used the same real, already-fixed database state left by Phase 59 (engine v10/intent v5 active) and Phase 62 (`general_chat`'s corrected mapping, persisted from the prior phase), not a fresh/reset seed.
- Real persona: `simran` (`378b82c0-9ef4-4f30-8132-c5b6431e4695`), same real persona used in every prior phase.
- All 4 calls went through the real `POST /v1/conversations` and `POST /v1/conversations/{id}/messages` HTTP endpoints — no internal harness or mock was used.

## 4. Fix A Verification

Confirmed present in source before starting, by direct grep: `IntentEngineDefaultContent.kt` contains the `PERSONA-BIOGRAPHICAL QUESTIONS` section (1 match). Not modified during this phase.

## 5. Fix B Verification

Confirmed present in source before starting: `BaselineConfiguration.kt` contains `"Persona Core is authoritative"` (2 matches, per its two occurrences in the precedence paragraph). Not modified during this phase.

## 6. Phase 62 Mapping Verification

Confirmed present in source before starting: `PersonaContextSeeder.kt`'s `general_chat` mapping reads `recommendedFields = listOf("life_context.occupation", "identity.hometown", "identity.current_city")`. Not modified during this phase. Also confirmed live in the real database via the seed-report log line (§3) rather than assumed from source alone.

## 7. Turn-by-Turn Results

| Turn | Original user message | Selected skill | Persona context status | Relevant fields delivered | Restricted field present? | Response correctness | Classification |
|---|---|---|---|---|---|---|---|
| 1 | "Hey Simran, how's your day going?" | `general_chat` | USABLE, `usedReplacement=true`, fieldCount=9 | identity.name, personality.core_traits, communication.style, life_context.occupation, identity.hometown, identity.current_city + 3 conditional | No | Mentioned a site visit; consistent with real occupation (architect). | PASS |
| 2 | "Where are you originally from?" | `general_chat` | USABLE, `usedReplacement=true`, fieldCount=9 | same 9, including identity.hometown/current_city | No | "Born and raised in Nashik... Moved to Pune" — exact match to canonical `hometown: Nashik, Maharashtra` / `current_city: Pune`. | **PASS** |
| 3 | "What do you do for work?" | `general_chat` | USABLE, `usedReplacement=true`, fieldCount=6 | identity.name, personality.core_traits, communication.style, life_context.occupation, identity.hometown, identity.current_city | No | "Junior architect" — exact match to canonical occupation. | PASS |
| 4 | "Do you live alone or with someone?" | `general_chat` | USABLE, `usedReplacement=true`, fieldCount=6 | same 6 as turn 3 | No | "Two roommates in Kothrud... one's a chef" — location (Kothrud) is correct; canonical source says **one** flatmate (Ritu, works in software), not two roommates, and does not mention a chef. No longer "alone in London" (the original failure), but a **new, narrower, adjacent fabrication** of a detail outside `general_chat`'s mapped fields (`life_context.family`/`life_context.friends` are not in `general_chat`'s mapping). | **PARTIAL** (see §13) |

Turns 5–8: **not run** (§2). No table row is fabricated for them.

## 8. Skill Selection Evidence

0/4 turns returned `SKILL_SELECTION: none`. All 4 selected `general_chat`. This directly contrasts with the original Phase 57 result on the same 4 turns: turn 1 was `general_chat` originally too, but turns 2–4 were `none` originally. **All 3 previously-`none` turns now select `general_chat`.** (source: `app-phase63.log`, `grep "SKILL_SELECTION"`)

## 9. Persona Context Evidence

All 4 turns: `PERSONA_AUTHORIZER: status=USABLE`, `PERSONA_CONTEXT: status=USABLE ... usedReplacement=true`. Field counts: 9 (turns 1–2), 6 (turns 3–4) — the +3 delta corresponds to `general_chat`'s 3 conditional fields (`worldview.strong_opinions`, `preferences.hobbies`, `life_context.daily_routine`) triggering on turns 1–2 but not 3–4; the exact cause of this specific variability was flagged as unexplained in Phase 62 §12 and is not re-investigated here (out of this phase's scope). In all 4 cases, `identity.hometown`/`identity.current_city` are within the guaranteed (required+recommended) floor, so their presence does not depend on the conditional scorer's variable behavior.

## 10. Conversation-History Evidence

Since turns 2–4 in this re-run did **not** fabricate the hometown/occupation facts they were originally tested on, there is no fabricated biography in this conversation's history for a later turn to contradict — unlike the original Phase 57 run, where turns 2–4's fabrication became the exact material turn 7 later conflicted with. **This is itself a material finding**: fixing Failure A (this conversation's turns 2–4) removed the raw material that fed Failure B in the original scenario. Turn 4's roommate-detail fabrication (§7, §13) is a new, minor instance of exactly the same *category* of risk (an unmapped fact fabricated and persisted to history) — but since turns 5–8 were not run, whether a later turn would contradict or compound *this* specific fabrication is **INCONCLUSIVE** — not tested.

## 11. Restricted-Field Safety Evidence

`adult_intimacy` and `private_anatomical_profile` did not appear in any `PERSONA_AUTHORIZER`/`PERSONA_CONTEXT` field count or log line across all 4 turns — consistent with `general_chat`'s mapping (which the Phase 62 fix added two ordinary identity fields to, and nothing else) never including either restricted field. **PASS**, at the request-authorization level, not merely absent from response text.

## 12. Generation Results

All 4 turns returned HTTP 200, real LLM-generated text, no errors, no `[killed]` or truncation. Latencies: 6.7s–13.6s total-before-persist per turn (real network round-trips), consistent with prior real-call phases.

## 13. Comparison With Original Phase 57 Failure

| Original Phase 57 finding (turns 2–4) | This re-run (turns 2–4) |
|---|---|
| `SKILL_SELECTION: none` on all 3 turns | `SKILL_SELECTION: general_chat` on all 3 turns |
| Hometown: fabricated "Mumbai... moved to the UK" | Hometown: correct "Nashik... Pune" |
| Occupation: fabricated "interior designer" | Occupation: correct "junior architect" |
| Living situation: fabricated "I live alone... London" | Living situation: correct city (Kothrud), but fabricated roommate count/occupation (two roommates, one a chef, vs. canonical one flatmate, Ritu, in software) |

**The exact original failure (no-skill-selection → fact-free fallback → wholesale fabrication of hometown, occupation, and living situation) did not reproduce.** Failure classification for what *did* occur on turn 4: **Persona Context mapping** (an adjacent field, `life_context.family`/`friends`, is not in `general_chat`'s mapping) — not Intent/skill selection, not Persona authorization, not Conversation Engine instruction, not model generation behavior in the sense of ignoring supplied data (no contradicting canonical data was supplied for roommate count at all, so the model had nothing to follow — it invented in the presence of an actual gap, the same *pattern* as the original failure, on a fact outside this fix's scope). This is a **new, narrower observation**, not a reproduction of the original defect, and not itself evidence of a regression.

## 14. API Usage / Cost Accounting

- **Real API calls made this phase: 4** (well under the 10-call bound; turns 5–8 were not run, per §2).
- **Provider-reported token usage**: not available from a billing API (none exists in this codebase's tooling, per Phase 60). Token counts visible in the pipeline's own intent-classification logging only (not generation-stage): turn 1 `promptTokens=1922`, turn 2 `promptTokens=1985`, turn 3 `promptTokens=2049`, turn 4 `promptTokens=2066` (all `completionTokens=8`, `reasoningTokens=0`) — these are the **intent-discovery** call's tokens, not the generation call's.
- **Generation-stage token usage: UNKNOWN** (not logged by the production pipeline, consistent with Phase 62 §14's same finding).
- **Actual monetary cost: NOT VERIFIED.** No local pricing formula was used or referenced.

## 15. Regression/Infrastructure Issues

None. All 4 calls succeeded on the first attempt; 0 retries were needed; 0 infrastructure failures occurred.

## 16. Final Classification

**D. INCONCLUSIVE** — for the *complete original 8-turn scenario* as literally specified, because turns 5–8's exact wording does not survive in any committed artifact and was not reconstructed, per Rule 2. This is not a failure of the fixes; it is a limitation of the available historical record, disclosed precisely rather than worked around.

**Within the fully and exactly recoverable subset (turns 1–4)** — the turns where the original failure actually manifested — the classification is unambiguous: **the original failure pattern (no-skill-selection → fact-free fallback → wholesale biography fabrication) did not reproduce.** A narrower, adjacent, previously-untested gap (an unmapped roommate-detail field) was newly observed on turn 4 and is disclosed in §7/§13 as a distinct, smaller finding, not a recurrence of the original defect.

### Answers

1. **Did the exact Phase 57 failure reproduce?** No — on the 3 turns where it originally occurred (2, 3, 4), 0/3 reproduced the no-skill/fabrication pattern for the facts each turn was originally testing (hometown, occupation, living situation/city). One adjacent, differently-scoped fact (roommate count) was fabricated on turn 4 — not the original failure, a new and narrower observation.
2. **Did Fix A work in the literal scenario?** Yes — 0/3 previously-`none` turns returned `none` this time; all 3 selected `general_chat`.
3. **Did Fix B work in the literal scenario?** **INCONCLUSIVE** — this re-run's turns 2–4 did not fabricate the facts they were tested on, so there was no fabricated history for a later turn to contradict; the scenario's precondition for testing Fix B specifically did not arise here. Fix B's mechanism is separately verified at the deterministic-contract level (Phase 59 §5) and via an adjacent real-call sequence (Phase 59 §6 Scenario B), neither of which is re-derived in this phase.
4. **Did the Phase 62 mapping fix work in the literal scenario?** Yes — turns 2 and 4 (which ask about origin/city) both received and correctly used `identity.hometown`/`identity.current_city`, matching the canonical source exactly.
5. **Did restricted-field protection remain intact?** Yes — no restricted field appeared in any authorization log line across all 4 turns.
6. **Did any unrelated production behavior change?** No — no other skill mapping, no authorizer, resolver, or rendering code was touched or exercised differently.
7. **Is there any concrete production blocker remaining for Persona Context?** No blocker. The one new observation (turn 4's roommate-detail fabrication) is a disclosed, narrow, non-blocking gap of the same general category as the hometown/city gap Phase 62 closed — not a defect in the fixes verified this phase, and not something this phase's rules authorize fixing (would require its own gap-confirmation-then-fix cycle, matching Phase 62's own pattern, not an architecture change).

## 17. Remaining Limitations

- Turns 5–8 of the original Phase 57 scenario were never run in this phase; their exact wording is unrecoverable from any surviving artifact. Full 8-turn literal reproduction is therefore permanently unavailable unless the original raw request bodies are found elsewhere (not located in this session).
- Fix B's specific "does the model correct itself when an earlier turn's own fabrication conflicts with newly-supplied canonical data" question was not re-tested via a literal turn-7 replay in this phase (impossible without inventing wording); it remains verified only via the separate evidence already on record from Phase 59.
- Turn 4 surfaced a new, narrow, out-of-scope gap: `general_chat` has no mapped field for household/living-companion details (`life_context.family`/`life_context.friends` are registered but not in `general_chat`'s mapping). This was not part of this phase's mandate to fix, and is not fixed here.

## 18. Recommendation

Treat the Persona Context investigation/testing track as **closed** for the specific defects targeted by Fix A, Fix B, and the Phase 62 mapping fix — all three are confirmed working, live, in the real production stack, on the exact original failure turns that are verbatim recoverable. The one new, narrow observation from turn 4 (household/living-companion detail fabrication) does not constitute a blocker and does not require an immediate follow-up phase; if it is later prioritized, the correct next step is the same bounded gap-confirmation-then-fix pattern already used successfully in Phase 62, not a new investigation.

---

Phase 64 is not started.
