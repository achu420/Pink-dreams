# Persona Context POC — Phase 2/3 Audit (Skill×Field Matrix, Prompt/Engine Review)

Written for: engineering review before the expanded benchmark (per plan Phase 4/5). Every mapping decision below states its rationale; "omitted" is a decision, not an oversight.

## Scope note (stated up front, per instruction not to silently change historical results)

Experiment 1's Variant A used only the ~25–34 fields transcribed into `persona_master_documents`, not the full 1434-line `docs/personas/Simran/simran-master.md`. **Experiment 1's token/cost/latency numbers are fixture-level measurements, not production-representative savings** — they are left unchanged in `persona-poc-EXPERIMENT1-executions.csv` and in the Experiment 1 report already given; this document does not retroactively alter them. Phase 4's expanded benchmark must decide explicitly what the Variant-A fixture represents (see "Open decision" at the end).

## Part 1 — Full skill inventory

26 real skills exist in `BaselineConfiguration.SKILLS` (production), plus 2 POC-only skills (`about`, `perspective_of`) created for this benchmark and not present in production. **7 have real, DB-seeded field mappings today** (the ones the benchmark corpus actually exercises): `about`, `perspective_of`, `general_chat`, `friendship`, `romantic_conversation`, `romantic_intimacy`, `foreplay`.

## Part 2 — Skill × Field matrix, all 28 skills

Fields referenced below are the 34 already registered (Phase 1). "Genuine need" = the skill's own prompt (read for each skill below) plausibly requires the field to answer in-character without fabricating. Where a skill's need can't be validated against any benchmark scenario, it is marked **proposed, not inserted** — inserting an untested mapping into the seed would itself be "adding for completeness," which the user's instruction explicitly rules out.

### Already mapped, DB-seeded, benchmark-covered (unchanged from Phase 1, restated here for completeness of the matrix)

| Skill | Required | Recommended | Conditional | Restricted |
|---|---|---|---|---|
| about | identity.name, .age, .hometown, .current_city | life_context.occupation, .education, personality.core_traits | worldview.strong_opinions, aspirations.career_goals, life_context.career_history, life_context.family | — |
| perspective_of | worldview.core_values, personality.core_traits | decision_patterns, aspirations.career_goals, worldview.beliefs | life_context.important_life_events, narrative_state.unresolved_threads, worldview.moral_boundaries, aspirations.life_goals/.dreams | — |
| general_chat | identity.name, personality.core_traits | communication.style, narrative_state.unresolved_threads, life_context.occupation | worldview.strong_opinions, preferences.hobbies, life_context.daily_routine | — |
| friendship | identity.name, personality.core_traits, behavioral_signature.attachment_style | communication.style, life_context.friends | preferences.hobbies, life_context.important_life_events, life_context.family | — |
| romantic_conversation | identity.name, personality.core_traits, relational.romantic_style | communication.style, worldview.core_values, relational.what_attracts | decision_patterns, relational.affection_preferences, .trust, .relationship_boundaries | — |
| romantic_intimacy | identity.name, relational.romantic_style, adult_intimacy | communication.tone, personality.core_traits | intimate_anatomy | intimate_anatomy, adult_intimacy |
| foreplay | identity.name, adult_intimacy | communication.tone | intimate_anatomy | intimate_anatomy, adult_intimacy |

### Remaining 19 production skills — audited, proposed mappings (NOT inserted into seed — no benchmark scenarios exist to validate against)

| Skill | Proposed Required | Proposed Recommended | Proposed Conditional | Restricted | Rationale |
|---|---|---|---|---|---|
| companionship | identity.name, personality.core_traits | communication.style | life_context.occupation, preferences.hobbies | none | Near-identical need profile to general_chat/friendship — low risk if added later. |
| emotional_support | identity.name, personality.core_traits | behavioral_signature.default, communication.style | relational.trust | none | Needs comfort-behavior grounding, not biography. |
| flirting | identity.name, relational.romantic_style | communication.tone, personality.core_traits | relational.what_attracts | none | Deliberately kept free of adult_intimacy/intimate_anatomy — flirting ≠ foreplay; giving it restricted access would be scope creep the field registry's allowedContexts gate is specifically designed to prevent. |
| relationship_building | identity.name, relational.romantic_style | relational.what_attracts, communication.style | relational.trust, decision_patterns | none | |
| relationship_discussion | identity.name, relational.romantic_style | relational.relationship_boundaries, relational.trust | relational.affection_preferences | none | Meta-conversation about the relationship itself, not intimacy. |
| dating | identity.name, personality.core_traits, relational.romantic_style | communication.style, relational.what_attracts | decision_patterns | none | |
| playful_teasing | identity.name, personality.core_traits | communication.tone | worldview.strong_opinions | none | |
| sexual_stimulation | identity.name, adult_intimacy | communication.tone | intimate_anatomy | intimate_anatomy, adult_intimacy | **Process risk found**: if this skill is ever mapped, the field registry's `allowedContexts` for `intimate_anatomy`/`adult_intimacy` (currently `["romantic_intimacy","foreplay"]`) must ALSO be updated to include `sexual_stimulation`, or `canAccessField` silently blocks it even though it's listed in required fields — an easy-to-miss two-place-update footgun, flagged for whoever implements this later. |
| social_practice | identity.name | personality.core_traits | — | none | Practice/coaching mode — deep persona grounding matters less than in real conversation; kept minimal deliberately. |
| conversation_practice | identity.name | personality.core_traits | — | none | Same as above. |
| flirting_practice | identity.name | relational.romantic_style, communication.tone | — | **explicitly none** | Practice mode must never receive restricted content — no legitimate need, and granting it would be a policy risk, not just a quality one. |
| dating_practice | identity.name | relational.romantic_style | — | **explicitly none** | Same reasoning as flirting_practice. |
| relationship_guidance | identity.name, personality.core_traits | decision_patterns, worldview.core_values | relational fields depending on topic (conditional scoring already handles this) | none | Advisor framing — values/decision-making matter more than romantic specifics. |
| breakup_support | identity.name, personality.core_traits, behavioral_signature.default | relational.trust | — | none | Persona has no authored breakup history in simran-master.md — correctly stays `unknown`, not filled in for this audit; nothing here should imply she's discussing her own breakup. |
| confidence_building | identity.name, personality.core_traits | worldview.core_values | — | none | |
| encouragement | identity.name, personality.core_traits | behavioral_signature.default | — | none | |
| advice | identity.name, personality.core_traits, decision_patterns | worldview.core_values | — | none | |
| problem_solving | identity.name | personality.core_traits | — | none | Functional/practical skill — most relational/emotional fields genuinely don't apply; kept minimal on purpose, not by omission-by-neglect. |
| learning | identity.name | personality.core_traits | — | none | Informal-teacher framing; curiosity/observant traits are the only relevant slice. |
| entertainment | identity.name, personality.core_traits | communication.tone, preferences.hobbies | — | none | |
| image_share | identity.name | — | — | none | Different modality — behavior lives in the image-generation subsystem (IMG-phase, separate from persona text context), not in Persona Context fields. |

### Cross-cutting findings from this matrix

1. **Over-retrieval risk in Variant C, not yet measured**: C's behavioral-core union (`personality.*`, `communication.*`, `behavioral_signature.*`) applies regardless of skill. For utility-oriented skills (`problem_solving`, `learning`, `image_share`), that union adds tokens for traits that plausibly matter less than in relationship-oriented skills. Not fixed here — flagged as something Phase 5's per-skill quality data should actually test once/if these skills are added to the benchmark, rather than assumed.
2. **Duplicated-responsibility risk, `narrative_state.unresolved_threads`**: this field is static (fixed at persona-authoring time) while the Memory Engine's memory facts and continuity summary are dynamic (grow from real conversation). For a long-running real user, the authored "unresolved threads" could drift out of sync with what's actually happened. Not fixed here (per the plan's iteration rule — don't blindly fix without evidence of an actual problem); the Conversation Engine's own priority order already states memory/history as supporting context to the current message, which should naturally de-weight a stale static thread if it ever conflicts. Worth watching in Phase 5's continuity-scenario results, not pre-emptively changed.
3. **Under-retrieval footgun (documented, not yet hit)**: adding a skill to a field's required/recommended list is not sufficient for restricted fields — `allowedContexts` on the field-registry row must also be updated, or the field silently never resolves. See the `sexual_stimulation` row above for the concrete case.

## Part 3 — Skill prompt audit (the 7 already-mapped skills)

Checked each skill's `BaselineConfiguration.SKILLS` prompt text against its field mapping for the two failure patterns named in the plan:

- **general_chat**: prompt makes no explicit mention of occupation/work-talk, but is intentionally general-purpose — the fabrication Experiment 1 found was a mapping gap, not a prompt gap (prompt doesn't claim the persona should always know how to answer "what are you up to," the fabrication was the model filling silence, now closed by the Phase 0 mapping fix). No prompt change needed.
- **about, perspective_of** (POC-only, no production `BaselineConfiguration` prompt — these were authored fresh for this POC in the benchmark harness's `World.init`): their prompts are short and intentionally generic ("answer questions about background," "share perspective from the persona's own worldview"). No mismatch found against their mappings.
- **friendship, romantic_conversation, romantic_intimacy, foreplay**: each skill's prompt content (read via `BaselineConfiguration.SKILLS`) stays at the "purpose/behavior/do-not" level without naming specific persona facts it expects — so there's no case here of "prompt expects info the mapping doesn't provide." The opposite check (mapping provides info the prompt doesn't explain how to use) also comes up empty: the Conversation Engine's own "Skill is an interaction mode, not a replacement personality" line already establishes that skills don't need to individually instruct how to use Persona Core content — that instruction lives once, at the engine level, correctly avoiding duplication across 26 skill prompts.

**No skill-prompt changes are made in this pass** — the two failure patterns the plan asked to check for were not found in the 7 audited skills.

## Part 4 — Conversation Engine audit

Read in full (`BaselineConfiguration.CONVERSATION_ENGINE`, ~166+ lines). Findings:

- **Anti-fabrication instruction: present.** `"Do not invent persona biography outside the supplied Persona Core."` (already existed, not added by this audit).
- **Priority order: present and explicit.** Current message > Persona Core > Conversation Engine > Selected Skill > Memory/history, with an explicit rule against a low-priority detail overriding a direct current request.
- **Memory-vs-persona conflict handling: present.** "Do not invent memories," "do not claim to remember information not supplied," "use supplied memories only when relevant" — this is the layer that should keep the duplicated-responsibility risk in Part 2 finding #2 from actually causing contradictions, though this remains untested until Phase 5's continuity scenarios run.
- **The one real gap**: the engine's "Persona Core is authoritative" framing was written before selective retrieval existed and doesn't distinguish "the whole Persona Core" from "a skill-relevant excerpt of it." This is not a missing instruction (the anti-fabrication clause already covers the failure mode in principle) — it's a framing gap that made the Phase-0-confirmed occupation fabrication possible despite the instruction existing. **No engine-prompt change is made here** — the demonstrated actual failure was closed at the mapping layer (smallest justified change, per the plan), and speculatively rewriting a 26-skill-shared prompt on a single confirmed failure mode risks a much larger blast radius than the evidence supports. Flagged for re-evaluation if Phase 5's larger corpus surfaces more instances of the same pattern.

## Consolidated changelist (this pass)

| Change | File | Status | Reason |
|---|---|---|---|
| Add `life_context.occupation` to `general_chat` recommended | `PersonaContextPocSeed.kt` | **Applied, verified** (Phase 0) | Root-caused fabrication, closed and confirmed with 45 real calls |
| Register 9 new fields (career_history, daily_routine, family, beliefs, moral_boundaries, life_goals, dreams, trust, relationship_boundaries) | `PersonaContextPocSeed.kt` | **Applied, verified** (Phase 1) | Real content existed in source doc but was inaccessible to B/C |
| Wire those 9 fields into about/perspective_of/general_chat/friendship/romantic_conversation | `PersonaContextPocSeed.kt` | **Applied, verified** (Phase 1) | Genuine per-skill need, documented above |
| 19-skill proposed mapping matrix | this document | **Documented, NOT inserted** | No benchmark scenarios exist yet to validate against; inserting now would be "mapping for completeness" |
| Conversation Engine prompt | — | **No change** | Anti-fabrication + priority instructions already present and correctly worded; single confirmed failure mode was closed at the mapping layer |
| 7 already-mapped skill prompts | — | **No change** | Audited, no mismatch found against current mappings |

## Open decision for Phase 4 (not resolved by this audit — needs explicit direction before the benchmark runs)

Per the scope-note above, Variant A must represent something specific before the expanded benchmark: either (a) expand `buildSimranMasterContent()` to transcribe the full `simran-master.md` document so Variant A is genuinely production-representative, or (b) keep the current fixture and explicitly re-label it in all reporting as "Variant A = the same ~34-field curated fixture B/C draw from, not the full document" so no reader mistakes fixture-level savings for production-level savings. Recommend (a) for the final benchmark, since the whole point of the comparison is A-vs-selective-retrieval — a truncated A understates A's real token cost and overstates B/C's real savings percentage.
