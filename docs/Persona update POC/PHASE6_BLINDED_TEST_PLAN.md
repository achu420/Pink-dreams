# Phase 6 — Blinded Quality Validation: Test Plan

Written before execution, per instruction. Reviewable independently of results.

## Frozen-state verification (done before this plan)

`git status` on `PersonaContextResolver.kt`, `PersonaContextRenderer.kt`, `PersonaFieldRelevanceScorer.kt`, `BaselineConfiguration.kt` (skill prompts, Conversation Engine): **zero uncommitted changes**. `PersonaContextPocSeed.kt`, `ChatEngineFactory.kt`, `RepositoryContextAssembler.kt`, `TestChatService.kt` show changes from Phase 0/1/4 wiring — all made and verified before the freeze instruction, nothing since. No discrepancy. Proceeding.

## Corpus categories and target counts

New scenarios only where the category requires genuine diversity beyond what Experiments 1-3 already covered; existing scenarios are not mechanically repeated into this corpus (a handful of previously-used occupation/unknown-fact scenarios are intentionally reused for continuity where noted, not padding).

| Category | New scenarios | Notes |
|---|---:|---|
| A — Identity/About (direct + indirect) | 20 | 10 direct, 10 indirect phrasing |
| B — Life Context | 20 | work, routine, family, flatmate, aspirations, food/preferences |
| C — Personality | 15 | traits, communication style, attachment, behavioral signature |
| D — Perspective/Worldview | 15 | values, beliefs, moral boundaries, decision-making |
| E — Friendship | 15 | incl. the Ritu/childhood-friend contradiction probe repeated with new phrasings |
| F — Romantic Conversation | 15 | emotional intimacy, trust, boundaries, relational style |
| G — Romantic Intimacy/Foreplay | 15 | authorized-context only, tone/skill-adherence focus |
| H1 — Unknown facts, sensitive/structured | 12 | new phrasings, expect clean non-fabrication |
| H2 — Unknown facts, nostalgic/embodied | 10 | expect model-inherent fabrication tendency, not a resolver test |
| I — Contradiction tests | 12 | Ritu, occupation, location, family, career history |
| J — Cross-skill transitions (single-turn pairs treated as 2-turn multi-turn) | included in K below | |
| K — Multi-turn (2-6 turns) | 25 conversations, ~90 turns | covers full transition matrix from section 4/J + continuity categories |

**Single-turn total**: 20+20+15+15+15+15+15+12+10+12 = **149 scenarios**
**Multi-turn**: 25 conversations, ~90 turns

## A/B/C execution counts

- Single-turn: 149 × 3 = **447 executions**
- Multi-turn: ~90 turns × 3 = **~270 executions**
- Repeatability subset: 25 scenarios (stratified across categories) × 3 variants × 2 extra repeats = **150 executions**
- **Main-pass + repeatability total: ~867 executions**, within the 450-1000+ target range, prioritizing scenario breadth over repetition per instruction.

## Skill pinning

Identical mechanism already validated in Experiments 1-3: `HybridLlmClient` intercepts the Intent Discovery system prompt and returns the scenario's own declared `skillKey`, never independently re-derived. Recorded per execution in the CSV's `skill_key` column, verifiable per-row.

## LLM call-count instrumentation

Reused unchanged from the existing harness: `HybridLlmClient.lastGenerationResponse` is set only inside the `else` branch (real generation), reset to `null` immediately before each `sendMessage()` call. A per-execution assertion (new, added to the dry-run) will confirm exactly one non-null generation response per (scenario, variant) execution before the full run proceeds.

## Occupation metric change (section 5)

Replacing `contains("architect")` with a 5-way classification applied during blinded scoring, not automated: `EXPLICIT_CORRECT`, `IMPLICIT_CORRECT`, `INCORRECT`, `FABRICATED`, `NON_ANSWER`. This requires the scorer (me) to read and judge each occupation-adjacent response, not a keyword script — folded into the blinded quality pass, not a separate automated check.

## Blinding and randomization method

1. Execute all real API calls with full metadata (scenario, skill, variant, tokens, latency) into `PHASE6_EXECUTIONS.csv` — the raw, unblinded ground truth.
2. For each scenario (or multi-turn conversation), generate 3 random opaque response IDs (4-character alphanumeric, collision-checked) and shuffle the A/B/C→ID assignment independently per scenario.
3. Write `PHASE6_BLIND_MAPPING.csv` (`scenario_id, response_id, variant`) — the ONLY file containing the A/B/C mapping. This file is written but **not opened again until scoring is complete**.
4. Write `PHASE6_BLINDED_EVALUATION.csv` (`scenario_id, response_id, skill, user_message, response_text` — no variant column at all, shuffled row order within each scenario group) — this is the only file I read during scoring.
5. Score into `PHASE6_BLIND_SCORES.csv` keyed by `response_id` only.
6. Only after all scores are written, join `PHASE6_BLIND_SCORES.csv` against `PHASE6_BLIND_MAPPING.csv` to unblind and compute aggregates.

**Disclosed limitation**: I am both the executor and the sole evaluator. This file-separation discipline is the best available approximation of blinding in this environment — it prevents the variant label from being visible in the data I read while scoring, but cannot fully rule out incidental inference from writing style patterns I might subconsciously recall from having generated the corpus. This is a real, acknowledged limit on rigor, stated here rather than glossed over.

## Quality rubric

Exactly the 7 dimensions + 11 binary flags specified (persona factuality, character consistency, unknown-fact handling, skill appropriateness, conversation continuity [N/A for single-turn], naturalness, grounded specificity; fabrication/contradiction/wrong_occupation/generic_assistant_language/character_break/skill_mismatch/continuity_break/restricted_content_leakage/unsupported_personal_memory/persona_fact_omission/other_material_failure).

## Failure taxonomy

The 6-class system (A–F: resolver/retrieval, field-mapping, prompt/engine, persona-data, model behavior, benchmark/evaluator) applied to every flagged failure during unblinding, tracing available context before assigning a class — not defaulting to "resolver failure" whenever B/C underperforms.

## Statistical reporting

Mean, median, distribution, and rate-based metrics per dimension/flag, broken down by variant, skill, category, single/multi-turn, and direct/indirect — no invented confidence intervals or significance tests beyond what's directly calculable from the actual score distributions.

## Stopping criteria

The 10 STOP conditions from the instruction, checked explicitly at the dry-run stage (section below) and spot-checked again after the full run before scoring begins.

## Dry-run plan

5 scenarios (one from category A, one from H1, one from I, one multi-turn 2-turn conversation, one from G) × 3 variants = 15 single-turn-equivalent executions + the multi-turn's 2 turns × 3 = 6 more = 21 total real calls. Verifies: identical scenario/skill/version across A/B/C, exactly one generation call each, blind mapping constructed correctly, no variant label leaks into the blinded file. **Full ~867-execution run does not start until this dry-run is reported and reviewed** (per the explicit instruction to not blaze through automatically).

## Expected output artifacts

`PHASE6_EXECUTIONS.csv`, `PHASE6_BLIND_MAPPING.csv`, `PHASE6_BLINDED_EVALUATION.csv`, `PHASE6_BLIND_SCORES.csv`, `PHASE6_RESULTS.md`, `PHASE6_TUNING_CANDIDATES.md` — all under `docs/Persona update POC/`.
