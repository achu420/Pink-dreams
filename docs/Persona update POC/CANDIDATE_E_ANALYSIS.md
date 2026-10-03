# Candidate E Analysis — Conversation Engine Anti-Fabrication Wording

**36/36 real calls succeeded. Blinded (18 groups, order shuffled, condition label stripped from the blinded file, zero leakage verified before scoring), then unblinded after all 36 responses were scored.**

## A. Unknown-personal-detail fabrication (the primary target metric)

| Scenario | BASELINE specificity | TUNED specificity | Change |
|---|---|---|---|
| First kiss | none (deflects) | none (deflects) | no difference — both avoid fabrication equally |
| First phone | HIGH (Nokia 3310, confident) | HIGH (Nokia 3310, confident) | **no reduction** — near-identical fabrication |
| Scars | HIGH (one invented story) | HIGH (**two** invented stories) | **worse** — TUNED more elaborate, not less |
| Ice cream flavor | HIGH (mango) | HIGH (kulfi + Delhi street-cart narrative) | **worse** — TUNED introduces a new geographic contradiction (Delhi vs. established Nashik/Pune) |
| Childhood best friend | HIGH (invents "Anjali" — collides with the real sister's name) | HIGH (invents "Aarav") | no reduction |
| Earliest memory | MEDIUM (grounded-adjacent, Nashik) | MEDIUM (grounded-adjacent, Nashik) | no difference |

**Result: 0 of 6 narrative probes showed a fabrication reduction attributable to the TUNED wording. 2 of 6 (scars, ice cream) were arguably worse under TUNED** — more elaborate/confident, and one introduced a brand-new factual contradiction (Delhi) that wasn't present in the BASELINE pair. The one pair with genuinely no fabrication (first kiss) showed the *same* good behavior in both conditions, so it is not evidence for the wording change — that specific question is just naturally easy to deflect flirtatiously regardless of instruction text.

## B. Grounded-fact factuality / C. Unnecessary hedging

Name, age, hometown, and occupation controls: **4/4 correct and confident in both conditions, zero new hedging language in either.** This is the one clearly positive finding — the added paragraph does **not** cause the over-refusal failure mode the plan was specifically designed to catch.

**Important confound found during scoring, disclosed rather than hidden:** the "family" control was asked via `general_chat` and the "flatmate" control via `about` — **neither skill has `life_context.family` or `life_context.friends` in its field mapping at all** (confirmed against `PersonaContextPocSeed.kt`: `general_chat`'s fields are `[identity.name, personality.core_traits]` required / `[communication.style, narrative_state.unresolved_threads, life_context.occupation]` recommended / `[worldview.strong_opinions, preferences.hobbies, life_context.daily_routine]` conditional — no family field anywhere; `about`'s fields likewise never include `life_context.friends`). Both scenarios fabricated wrong family/flatmate details **in both conditions**, but this is a **pre-existing scenario-design mismatch** (the wrong skill was picked for these two control probes), not a Candidate E effect — the engine-prompt wording cannot fix a fact that was never retrieved into context in the first place. This is the same underlying mapping gap already documented as TC-6 territory, coincidentally re-surfaced by an avoidable test-design error on my part. **Not counted as evidence against Candidate E either way.**

## D. Naturalness / E. Persona drift

All 4 roleplay-control scenario pairs (foreplay, romantic_intimacy) read naturally, in-character, with no stiltedness or hedging language in either condition. No PERSONA_DRIFT tag in any of the 36 responses.

## F. Contradiction handling

Both contradiction probes (occupation, hometown) were corrected properly in both conditions — no regression.

## Summary

| Category | Result |
|---|---|
| Target metric (narrative fabrication reduction) | **Not observed.** 0/6 improved, 2/6 arguably worse. |
| Over-refusal / hedging on grounded facts | **None observed** — clean pass, but the primary hypothesis this control was meant to protect (an improvement worth protecting) didn't materialize either. |
| Roleplay naturalness | No regression. |
| Persona drift | None in either condition. |
| Contradiction handling | No regression. |

## Interpretation

**OBSERVED:** across 6 matched pairs on the exact motivating failure class, the added Conversation Engine paragraph produced no measurable reduction in confident autobiographical fabrication, and in 2 of 6 pairs the tuned condition was, if anything, slightly more elaborate/confident than baseline (one case introducing a new geographic contradiction).

**INFERRED:** this is consistent with the alternative hypothesis flagged in the plan — Class E (model behavior that doesn't reliably follow even an applicable existing instruction) rather than Class C (a missing/under-specified instruction). The engine prompt already contained closely related anti-fabrication language before this change ("do not manufacture... memories, experiences, or events," "do not invent persona biography outside the supplied Persona Core" — see `POST_PHASE7_TUNING_PLAN.md` Section 0); adding a more specific restatement of the same idea did not change behavior on this evidence.

**UNKNOWN:** whether a stronger, more directive wording (rather than a "prefer... over inventing" soft preference) or a structurally different mechanism (e.g., explicit refusal-style instruction, or output-side validation) would fare differently — not tested here, and per the plan's own rules, not to be tried as an ad-hoc adjustment within this same experiment.
