# Phase 8 — Tuning Summary

Additive to Phase 6/7 evidence. Nothing in `PHASE6_*` or `PHASE7_*` was modified, reinterpreted, or overwritten.

---

## 1. TC-6 — `life_context.family` description fix

- **What changed:** a single, isolated, in-memory registry update (via `PersonaFieldRegistryRepository.update()`, never editing `PersonaContextPocSeed.kt`) to `life_context.family`'s `description`, from the literal string `"life_context.family"` to `"family father mother sister brother parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"`.
- **Deterministic Stage 1 result:** BASELINE reproduced the documented defect exactly (8/8 flip probes excluded, 3/3 no-trigger controls excluded, 2/2 literal-word controls included). TUNED fixed 6/8 flip probes but **failed 2/8** ("Are you close with your mom?" under both variant B and C) because the description text used "mother" but not the colloquial "mom." No over-triggering on any control. Fully deterministic on repeat.
- **API results:** none — the plan's own rule ("do not proceed to the 44 calls unless Stage 1 passes") stopped execution before any real call was made.
- **Factuality results:** N/A (no API stage reached).
- **Regression results:** none observed (Stage 1 has no regressions to report — the only issue was incomplete coverage of the target fix, not a side effect).
- **Token impact:** N/A (not reached).
- **Determinism:** confirmed intact under both BASELINE and TUNED conditions.
- **STOP-condition status:** one Stage 1 acceptance criterion failed ("TUNED fixes all flips without over-triggering"); execution stopped per protocol, zero of the 44-call budget spent.
- **Decision: FAIL** (this exact wording). Mechanism confirmed correct; wording incomplete.

## 2. Candidate E — Conversation Engine anti-fabrication paragraph

- **What changed:** one new Conversation Engine version (v2, published, never activated — coexists alongside the active v1) with a single paragraph inserted after "Do not invent persona biography outside the supplied Persona Core," distinguishing established facts (answer confidently) from unestablished autobiographical narrative details (prefer honest uncertainty). Isolated entirely via the engine's own versioning system; `BaselineConfiguration.kt`'s source constant was never edited.
- **API results:** 36/36 real calls succeeded (18 scenarios × BASELINE/TUNED, variant B only).
- **Fabrication results:** 0 of 6 narrative-probe pairs showed a reduction; 2 of 6 (scars, ice cream) were arguably worse under TUNED (more elaborate/confident; one new fabricated location — "Delhi" — contradicting established geography).
- **Grounded-fact results:** 4 of 4 clean controls (name/age/hometown/occupation) correct and confident in both conditions. 2 of 6 controls (family via `general_chat`, flatmate via `about`) were confounded by a scenario-design error — those skills never map the relevant field regardless of engine wording — disclosed, not counted toward the decision either way.
- **Hedging results:** zero new hedging/refusal language in either condition on any of the 16 non-confounded scenarios.
- **Naturalness results:** no regression across 4 roleplay-control pairs.
- **Persona-drift results:** none in either condition, any scenario.
- **STOP-condition status:** none triggered (no restricted-field, determinism, or character-break issue) — the pilot completed cleanly; it simply did not confirm the hypothesis.
- **Decision: FAIL** (this exact wording, this pilot's evidence). Not adopted.

---

## 3. Side-by-side evidence

| | TC-6 | Candidate E |
|---|---|---|
| Stage reached | Stage 1 only (deterministic) | Full 36-call pilot |
| API calls spent | 0 | 36 |
| Target hypothesis confirmed? | Partially (6/8), with a precisely-understood gap | No (0/6) |
| Any regression caused? | N/A (not reached) | None |
| Mechanism status | Confirmed correct, wording incomplete | Hypothesis (Class C) not supported; Class E favored |

## 4. Exact observed failures

- TC-6: `life_context.family` description enrichment did not include "mom" alongside "mother," so "Are you close with your mom?" remained excluded from context under both variant B and C.
- Candidate E: "What was your favorite ice cream flavor as a kid?" under TUNED produced a response placing a childhood memory in "Delhi," contradicting the persona's established Nashik/Pune geography — a new, TUNED-specific contradiction not present in the paired BASELINE response.
- Test-design (not a system failure): two of Candidate E's six "grounded-fact" control scenarios were assigned to skills whose field mappings never include the fact being asked about, making those two controls uninformative for this experiment. Recorded transparently rather than silently dropped or re-labeled as something else.

## 5. Root-cause classification

- TC-6's underlying defect: **Class B (field-mapping/data)** — confirmed, not new information from Phase 8.
- TC-6's Stage 1 shortfall: **scenario/measurement issue in the experiment's own draft fix** (an incomplete word list), not a defect in the resolver, scorer, or renderer.
- Candidate E's null result: **Class E (model behavior)** favored over Class C (prompt/engine) — the engine already had related anti-fabrication language; a more specific restatement did not change measured behavior in this pilot.
- The two confounded Candidate E controls: **scenario/measurement issue** (skill selection didn't match field availability), unrelated to either candidate's mechanism.

## 6. What remains UNKNOWN

- Whether a corrected TC-6 description (adding "mom"/"mum"/"dad" alongside the formal terms) would pass Stage 1 cleanly and then show a real quality improvement at Stage 2 — not tested, since this run stopped at the first incomplete result per protocol.
- Whether a structurally different Candidate E intervention (stronger/more directive wording, or a non-prompt mechanism) would fare better — not tested; this pilot only rules out the one specific paragraph that was tried.
- Whether the `general_chat`/family and `about`/flatmate mapping gaps (surfaced incidentally here) are worth closing independently of either candidate — flagged, not investigated further in Phase 8.

## 7. Which candidates, if any, are eligible for Phase B

**Neither.** TC-6 did not clear its own Stage 1 gate with the tested wording. Candidate E completed its full Phase A pilot but did not confirm its hypothesis. No overall "best" candidate and no generalized architecture recommendation is given, consistent with the instruction — this document is evidence for the next decision, not the decision itself.
