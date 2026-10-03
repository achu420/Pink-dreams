# TC-6 Tuning Decision

**Decision: FAIL (Stage 1) — do not carry forward this exact wording. Not INCONCLUSIVE: the failure is fully understood, not ambiguous.**

## Basis

Stage 1's own explicit acceptance test — "TUNED fixes all flips without over-triggering" — did not pass: 2 of 16 flip probe×skill checks ("Are you close with your mom?" under both B and C) remained excluded under the TUNED description. All other Stage 1 checks passed cleanly (BASELINE matched the documented defect, 14/16 flips fixed, zero over-triggering on any control, resolver output deterministic on repeat).

## Why FAIL and not PASS-with-caveat

The plan's own Stage 1 success criterion was binary ("category-B indirect phrasings... must be included... under TUNED") and this run did not meet it for all tested phrasings. Per the plan and this instruction, a Stage 1 shortfall stops the candidate before any API spend — it does not get rounded up to a pass because most of it worked.

## Disposition

- **44-call API pilot: NOT executed. Zero API budget spent on TC-6.**
- **No production/prompt/mapping change adopted.** The isolated in-memory registry update used for this test does not persist anywhere.
- **Mechanism validated, wording not:** the underlying hypothesis (enriching a conditional field's description fixes indirect-phrasing exclusion) is now confirmed correct for 6/8 phrasings with a single narrow, easily-explained gap (colloquial synonym not included in the word list). This is a **wording-completeness problem in the draft fix**, not a flaw in the fix's mechanism or in the resolver/scorer it targets.
- **Recommended next step (not authorized to execute without a fresh go-ahead, per the "one causal hypothesis per experiment" and "do not silently improve" rules):** a **new**, separately-tracked Stage 1 attempt with a revised description string that includes common colloquial family-address terms (mom/mum/dad/pop alongside mother/father), re-run through the same deterministic gate before any API call is considered again.

## Eligible for Phase B?

**No.** Per the plan's own framework, only candidates that pass Phase A (which for TC-6 requires passing Stage 1 first) are eligible for Phase B expansion. This attempt did not reach Stage 2, so there is no Phase-A pilot result to evaluate for Phase B eligibility.
