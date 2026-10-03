# Candidate E Tuning Decision

**Decision: FAIL — the primary hypothesis (reduced personal-narrative fabrication) is not supported by this pilot's evidence. Not adopted.**

## Basis

The pilot's own stated success metric #1 ("fabrication-tag rate on the 6 personal-narrative probes decreases under TUNED vs. BASELINE") did not occur: 0 of 6 matched pairs showed a reduction, and 2 of 6 showed the TUNED condition as more elaborate/confident than BASELINE, including one new factual contradiction (a fabricated "Delhi" location) that was not present in the corresponding BASELINE response.

## Why this is not "inconclusive"

The result is not ambiguous — it is a clean, matched, blinded, 6-pair comparison with a consistent null-to-negative direction, not a mixed or noisy signal. Per the plan's own instruction ("Do not call something a pass merely because the target scenarios improved"), the same standard applies in reverse: this cannot be called a pass, and the absence of any positive signal across all 6 target pairs is itself a clear (negative) finding, not an unclear one.

## What did NOT regress (recorded for completeness, not to offset the FAIL)

- No new over-refusal/hedging on grounded facts (name, age, hometown, occupation) — 4/4 correct and confident in both conditions.
- No roleplay/naturalness degradation across 4 foreplay/romantic_intimacy pairs.
- No new PERSONA_DRIFT.
- No contradiction-handling regression.

These are genuinely clean results, but per the plan's framework a candidate is judged primarily on whether it achieves its stated purpose, not merely on the absence of harm.

## Confound disclosed (does not change the decision)

Two of the six "grounded-fact" controls (family via `general_chat`, flatmate via `about`) were assigned to skills that don't map the relevant field at all (`life_context.family`/`life_context.friends` are absent from those skills' required/recommended/conditional lists) — both conditions fabricated wrong details there for a reason unrelated to Candidate E's wording. This is a scenario-design error on my part, disclosed rather than quietly excluded, and does not affect the FAIL decision (which rests entirely on the 6 narrative-probe pairs where the fields being asked about genuinely aren't skill-mapping-dependent).

## Root-cause classification

**Class E (model behavior)**, not Class C (prompt/engine gap). The engine already contained closely related anti-fabrication language before this addition; restating the same idea more specifically did not change measured behavior on this evidence. This does not prove no wording could ever work — only that this specific, single-paragraph, "prefer honest uncertainty" framing did not, at this sample size.

## Eligible for Phase B?

**No.**
