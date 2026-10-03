# Tuning Candidates — Post-Benchmark

No fix in this document has been applied. The configuration used for Experiment 3 remains exactly as frozen. Each candidate is classified by root-cause type per the required distinction: **architecture defect** / **field-mapping defect** / **prompt defect** / **model behavior** / **scenario-data defect**.

---

### 1. Variant A: 0% occupation accuracy across 51 executions, all skills

- **Skill/field**: all skills, `life_context.occupation`
- **Evidence**: `BENCHMARK_RESULTS.md` occupation section — 0/51, including `about` where the field is present in A's own full document
- **Root cause**: **model behavior**, specific to large-context retrieval reliability — INFERRED, not directly proven, that the fact is present but not reliably attended to when embedded in a 4542-token unstructured JSON blob
- **Proposed fix**: none proposed at the architecture/mapping layer — this is evidence *for* selective retrieval, not a defect to patch in A. If A is kept in production for any purpose, consider whether the full document needs internal structuring/headers (not tested here) to see if that alone improves A's reliability without full selective retrieval — **expected effect: unknown, would need a dedicated follow-up test isolating "structured full document" as a 4th condition.**
- **Test in follow-up?**: Yes — a 4th variant ("A-structured": full document but with clear markdown headers per section) would isolate whether the failure is about *content volume* or *content structure*.

### 2. Variant A: `perspective_of` responses drift toward generic-assistant language

- **Skill/field**: `perspective_of`, no specific field — a prompt/framing issue
- **Evidence**: `BENCHMARK_RESULTS.md`, sample quotes ("helping people think through things, seeing them grow")
- **Root cause**: **model behavior**, possibly **prompt defect** — `perspective_of`'s skill prompt is abstract ("share perspective on topics") and may not sufficiently anchor the model to architect-specific perspective when given a huge undifferentiated context to draw from
- **Proposed fix**: not applied. Candidate: add one concrete grounding line to `perspective_of`'s skill prompt (e.g., "ground your perspective in your actual work and life, not generic assistant framing") — **expected effect: unknown**, should be A/B tested in isolation before broad rollout since it changes a prompt shared by all skill-prompt-adjacent behavior.
- **Test in follow-up?**: Yes.

### 3. `general_chat` occupation accuracy only 25-31% despite the field being recommended

- **Skill/field**: `general_chat`, `life_context.occupation`
- **Evidence**: 5/16 (B), 4/16 (C) — much lower than `about`'s 18/21 (85.7%) with the same field mapped
- **Root cause**: **likely scenario/measurement defect, not a real gap** — manual spot-check (documented in the chat, not yet re-verified against this specific 16-row subset) suggests many `general_chat` occupation questions are indirect ("Got any client meetings today?", "Busy week at work?") where the model correctly references work context (client, project, studio) without the literal word "architect" — the keyword match likely undercounts true accuracy here the same way it did for `perspective_of`.
- **Proposed fix**: none — re-measure with a broader match pattern (architect, architecture, studio, client project, design work) before concluding this is a real gap.
- **Test in follow-up?**: Yes — re-run the keyword classification with an expanded term list before treating this as a real defect.

### 4. `friendship` occupation accuracy 66.7% (B/C) despite occupation not being mapped to that skill

- **Skill/field**: `friendship`
- **Evidence**: 4/6 correct despite `life_context.occupation` absent from `friendship`'s required/recommended/conditional lists
- **Root cause**: **UNKNOWN** — not established whether this is inference from `personality.core_traits` ("notices details about spaces"), from `life_context.friends`, or from general world-knowledge plausibility (the model may simply guess a plausible profession that happens to match, and this needs occupation-keyword verification against ground truth, not just presence of the word).
- **Proposed fix**: none. Flagged for direct investigation (read the actual 4 "correct" responses to confirm they're genuinely grounded, not lucky guesses) before treating as a finding either way.
- **Test in follow-up?**: Yes.

### 5. Middle-name fabrication in Variant A only

- **Skill/field**: `about`/`general_chat` unknown-fact probe, no specific field (identity.name is present, no middle-name field exists anywhere)
- **Evidence**: `BENCHMARK_RESULTS.md` unknown-fact table — A said "Priya," B/C correctly said none
- **Root cause**: **model behavior** — large identity block in A plausibly invites elaboration; no field-mapping or prompt cause identified
- **Proposed fix**: none proposed — single data point, low stakes (a name, not a safety-relevant fact); more repeats needed before any prompt change is justified.
- **Test in follow-up?**: Optional — low priority given severity.

### 6. Childhood-best-friend fabrication/contradiction — different failure per variant

- **Skill/field**: `friendship`, `life_context.friends`
- **Evidence**: A implied Ritu was a childhood friend (contradicts `life_context.career_history`/origin story: met in college); C invented "Meera"; B stayed vague
- **Root cause**: **model behavior**, arguably **field-mapping gap** — no field distinguishes "childhood friends" from "current friends" (`life_context.friends` only lists current-era friends: Ritu, Prashant, Neha), so any variant asked about pre-Ritu childhood friendships has nothing grounding it either way.
- **Proposed fix**: candidate — add an explicit `life_context.family`-adjacent field or extend the field's description to note "no childhood friends beyond family are documented," so the resolver's presence doesn't invite the model to assume there must be one. **Expected effect**: reduce contradiction rate on this specific question class; unlikely to have broad effect elsewhere.
- **Test in follow-up?**: Yes, low-cost to test (single field description change).

### 7. First-phone / first-kiss / accident-scar / ice-cream: fabrication in ALL THREE variants equally

- **Skill/field**: none — general model tendency
- **Evidence**: `BENCHMARK_RESULTS.md` unknown-fact table
- **Root cause**: **model behavior**, explicitly NOT an architecture or mapping defect — confirmed by being present identically in A, B, and C
- **Proposed fix**: out of scope for the persona-context resolver entirely. If this class of fabrication matters for production, the fix belongs in the Conversation Engine prompt (a stronger, more specific anti-fabrication instruction for *personal narrative* questions specifically, distinct from *biographical fact* questions) or in output validation — not in field mapping.
- **Test in follow-up?**: Only if pursued as a separate Conversation Engine prompt experiment — explicitly not a persona-context-resolver fix.

### 8. Initial resolver-determinism alarm (10/90 groups) — resolved during analysis, recorded for transparency

- **Root cause**: **scenario/analysis defect**, not a system defect — my first grouping pass grouped by `(user_message, variant)` only, which conflated intentionally-identical restricted-context probe text used across different skills. Regrouping by `(user_message, skill_key, variant)` showed 0/90 non-deterministic groups — the resolver is confirmed fully deterministic.
- **Proposed fix**: none needed in the system. Note for future benchmark analysis: always group by skill, not just message text, since this corpus intentionally reuses question text across skills for the authorized/unauthorized restricted-context comparison.

---

## Summary by category

| Category | Count | Items |
|---|---:|---|
| Architecture defect | 0 | None found |
| Field-mapping defect | 1 (candidate) | #6 (childhood-friend field description) |
| Prompt defect | 1 (candidate) | #2 (perspective_of grounding) |
| Model behavior (not fixable at resolver/mapping layer) | 4 | #1, #5, #7, and partially #6 |
| Scenario/measurement defect (not a real system issue) | 2 | #3 (likely), #8 (confirmed) |
| Unresolved / needs investigation | 1 | #4 |

**No architecture-level defects were found in this benchmark.** The resolver behaves correctly and deterministically; restricted-field gating held with zero leakage across ~150 authorized/unauthorized executions. The issues found cluster in two places: (a) known, low-cost, optional field-mapping/prompt refinements (#2, #6), and (b) model-inherent fabrication tendencies for personal-narrative questions that no persona-context strategy — A, B, or C — currently prevents (#7), which is a Conversation-Engine-level question, not a resolver question.
