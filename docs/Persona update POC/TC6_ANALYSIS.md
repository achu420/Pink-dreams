# TC-6 Analysis — Field-Description Fix (`life_context.family`)

**Stage reached: Stage 1 (deterministic, zero-API-cost). Stage 2 (44-call API pilot) was NOT run.** Per the Post-Phase-7 Tuning Plan's explicit instruction ("Do not automatically proceed to the 44 API calls unless Stage 1 passes... If any Stage 1 STOP condition fails: STOP TC-6 immediately. Do not spend the planned API budget. Document the failure."), execution stopped here.

---

## What was changed (isolated, reverted automatically)

`PersonaFieldRegistryRepository.update()` was called against the running test's in-memory H2 database to change **only** `life_context.family`'s `description` from `"life_context.family"` to:

> `"family father mother sister brother parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"`

No file was edited. `PersonaContextPocSeed.kt`, `PersonaContextResolver.kt`, `PersonaFieldRelevanceScorer.kt`, and every other field/skill mapping were untouched. The change exists only for the lifetime of one JUnit test method's in-memory database and is gone the moment the JVM exits — there is nothing to roll back.

## BASELINE result (confirms the documented defect exactly)

| Probe | Category | B | C |
|---|---|---|---|
| about: "Do you have siblings?" | flip | excluded | excluded |
| about: "What's your relationship with your siblings like?" | flip | excluded | excluded |
| about: "Do you talk to your parents a lot?" | flip | excluded | excluded |
| about: "Are you close with your mom?" | flip | excluded | excluded |
| friendship: (same 4 messages) | flip | excluded | excluded |
| about/friendship: "What's your flat like?" / "What do you usually do together on weekends?" / "What are your hobbies?" | control-no-trigger | excluded | excluded |
| about/friendship: "Tell me about your family." | control-already-works | **included** | **included** |

BASELINE matches TC-6's hypothesis precisely: every indirect phrasing is silently excluded; the literal-word phrasing already works; no control over-triggers.

## TUNED result

| Probe | B | C |
|---|---|---|
| "Do you have siblings?" (about, friendship) | included | included |
| "What's your relationship with your siblings like?" (about, friendship) | included | included |
| "Do you talk to your parents a lot?" (about, friendship) | included | included |
| **"Are you close with your mom?" (about, friendship)** | **excluded** | **excluded** |
| control-no-trigger (×3) | excluded | excluded |
| control-already-works (×2) | included | included |

**6 of 8 flip probes (75%) were fixed exactly as predicted. 1 of 8 (12.5%, tested identically in both skills = 2 of 16 probe×skill combinations) was NOT fixed: "Are you close with your mom?"**

## Root cause of the Stage 1 failure

The proposed description string includes `"mother"` but not `"mom"`. `PersonaFieldRelevanceScorer` does pure literal word-overlap — "mom" and "mother" do not match each other. This is not a flaw in the underlying mechanism (which is confirmed to work exactly as hypothesized for every other flip probe) — it's an incomplete word list in the specific draft string that was locked in the plan. No over-triggering occurred on any control; determinism held on every repeat.

## Why this is reported as a FAIL rather than silently fixed and re-run

The Post-Phase-7 Tuning Plan and this instruction both explicitly require: "Do not automatically proceed... STOP TC-6 immediately... Document the failure" on any Stage 1 failure, and separately "Do not silently improve either candidate during the experiment" / "Do not modify scenario wording after seeing results." Both rules point the same direction here: the exact TUNED string was committed to before running Stage 1, Stage 1 found it incomplete, and the correct response is to stop and report — not to quietly add "mom" to the list and re-run as if the first attempt hadn't happened.

## Classification

**Root cause: Class B (field-mapping/data defect), confirmed** — this was never in question; Stage 1 re-confirms it with 100% precision on 14/16 probe×skill combinations. The **Stage 1 failure itself is a scenario/wording-completeness issue in the experiment's own draft fix**, not a new defect in the resolver, scorer, or renderer. Any future attempt at this candidate should treat colloquial variants (mom/mum/dad/pop, etc.) as a first-class requirement of the description text, and Stage 1 itself (now built as a reusable, zero-cost deterministic gate) should be re-run against any revised wording before any API budget is spent again.
