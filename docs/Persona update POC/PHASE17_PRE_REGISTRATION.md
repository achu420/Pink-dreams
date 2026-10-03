# Phase 17 — Pre-Registered Scenario Selection (written BEFORE any API call or response is seen)

## Selected scenarios (all skill=`about`, all from the existing 30-scenario Phase 10D/10E corpus, unmodified)

| # | Scenario | Relevant scalar field(s) | Ground truth | Notes |
|---|---|---|---|---|
| 1 | "How old are you?" | `identity.age` | 27 | Direct single-scalar recall |
| 2 | "Where are you from?" | `identity.hometown` | Nashik, Maharashtra | Direct single-scalar recall |
| 3 | "What city do you live in now?" | `identity.current_city` | Pune, Maharashtra (Kothrud) | Direct single-scalar recall |
| 4 | "What's your educational background?" | `identity.hometown` + `life_context.education` (+ `identity.age`, always co-selected for `about`) | "12th from a school in Nashik. BA in Architecture from a college in Pune (2019). No higher degree." | Combines the most scalar facts simultaneously of any available non-excluded scenario |

## Selection rationale against the 6 criteria

1. **Requires interpreting at least one scalar field** — all 4 satisfy this directly.
2. **Field already selected by B/SS-M** — `identity.age`, `identity.hometown`, `identity.current_city` are `about`'s **required** fields, and `life_context.education` is `about`'s **recommended** field — all four have been part of `about`'s mapping since before Phase 10E and are unchanged by any later phase. Confirmed by direct re-read of `PersonaContextPocSeed.kt`'s `about` `SkillContextRequirement` entry.
3. **Known ground-truth answer exists** — yes for all 4, taken directly from the persona master document values.
4. **Does not depend on any mapping change introduced after Phase 10E** — confirmed: `about`'s required/recommended field list for these exact fields has been identical since the original baseline, before Phase 10E's own edits (which only added a *conditional* `life_context.friends` entry to `about`, irrelevant to these 4 scenarios) and long before Phase 13's edits. These 4 scenarios' field selection is unaffected by any fix made in Phases 10E–16.
5. **At least one scenario should involve a relationship between two scalar facts** — **honest disclosure, stated before any call is made:** the existing 30-scenario corpus does not contain a second exact analog to the age/timeline arithmetic relationship (that scenario, "Been in this city long?", is itself excluded by criterion 4/6, since it depends on the Phase 13 mapping fix). Scenario #4 is the closest available approximation — it is the only remaining scenario where multiple scalar facts (hometown, education, and the co-selected age) must be synthesized into one answer — but it does not require *arithmetic derivation* the way the timeline case did; the answer is a direct concatenation of already-stated facts, not a computed new fact. This limitation is disclosed now, before any response is seen, rather than discovered after the fact.
6. **Avoid scenarios depending on `life_context.friends`, location-as-a-Phase-13-fix, or any other Phase 13 fix** — confirmed: none of the 4 selected scenarios touch `life_context.friends`, and while scenario #3 concerns "current city," `identity.current_city` for the `about` skill was already a **required** field at the original baseline (unlike `general_chat`, where it was a Phase 13-adjacent Phase 10E addition) — so this scenario is not confounded by any later fix.

**This document is written and saved before any Phase 17 API call is made or any model response is inspected.**
