# Phase 10E — Zero-Cost Mapping Audit (pre-experiment gate)

No API calls. This audit enumerates all 23 SS/SR WRONG classifications from Phase 10D, verifies the ~91% overlap precisely, and confirms (or refutes) that exactly three mapping gaps explain them before any further real generation is spent.

## 1–2. Enumeration and classification

Union of SS's 11 and SR's 12 wrong scenario keys = **13 unique failing scenarios** (10 shared, 1 SS-only, 2 SR-only):

| Scenario | Skill | Failed in | Root cause (from actual response content, not just topic) | Category |
|---|---|---|---|---|
| Do you have siblings? | about | SS, SR | claims only child | FAMILY |
| What's your relationship with your siblings like? | about | SS, SR | claims only child | FAMILY |
| Tell me about your family. | general_chat | SS, SR | wrong parents/siblings entirely | FAMILY |
| Do you live alone? | about | SS, SR | claims lives alone / with 2 other architects | FRIENDS |
| Who do you live with? | about | SS, SR | claims lives alone | FRIENDS |
| What's your flat like? | about | SR only | implies living alone | FRIENDS |
| You mentioned a flatmate before — who's that again? | general_chat | SS, SR | invents "cousin Rhea" / "Maya" | FRIENDS |
| What's the deal with your living situation? | general_chat | SS, SR | describes solo studio | FRIENDS |
| How's Ritu these days? | general_chat | SR only | wrong occupation (café, not software engineer) | FRIENDS |
| Been in this city long? | general_chat | SS, SR | wrong years-in-Pune, wrong move reason | LOCATION |
| Is Pune home for you, or somewhere else originally? | general_chat | SS, SR | wrong hometown (Nagpur/Delhi, not Nashik) | LOCATION |
| Refresh my memory on where you're based. | general_chat | SS, SR | wrong city (Mumbai, not Pune) | LOCATION |
| Do you rent or own your place? | general_chat | SS only | wrong city (Bandra/Mumbai) | LOCATION |

**All 13 unique scenarios classify cleanly into exactly one of the three proposed categories. Zero "something else." Requirement #5 (check for hidden additional gaps) is satisfied — none found.**

Tally: FAMILY = 3 scenarios, FRIENDS = 6 scenarios, LOCATION = 4 scenarios (3+6+4=13 ✓).

## 3. Overlap verification

SS wrong (11) ∩ SR wrong (12) = 10 scenarios. 10/11 = **90.9%** of SS's failures also fail under SR; 10/12 = **83.3%** of SR's failures also fail under SS. (The "~91%" figure cited refers to the SS-relative overlap specifically.) Confirmed precisely, not just approximately.

## 4. Do the proposed mapping additions actually cover what's asked?

Re-verified against current `PersonaContextPocSeed.kt`:

- **`about` skill:** `life_context.family` is already a conditional field (line 410) but its description is the literal string `"life_context.family"` (the TC-6 defect) — so it fails to match "siblings"-worded questions via word-overlap, even though the field itself is correctly mapped. **Fix: TC-6's already-designed description enrichment** (`"family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"` — note "siblings" is already present in this exact string from Phase 8/10B's design), not a new mapping. `about` has **no** `life_context.friends` mapping at all (confirmed: required=`[identity.name, identity.age, identity.hometown, identity.current_city]`, recommended=`[life_context.occupation, life_context.education, personality.core_traits]`, conditional=`[worldview.strong_opinions, aspirations.career_goals, life_context.career_history, life_context.family]` — no friends field in any tier) — **needs a new mapping entry.**
- **`general_chat` skill:** confirmed required=`[identity.name, personality.core_traits]`, recommended=`[communication.style, narrative_state.unresolved_threads, life_context.occupation]`, conditional=`[worldview.strong_opinions, preferences.hobbies, life_context.daily_routine]` (line 432-440) — **no `life_context.family`, no `life_context.friends`, no `identity.hometown`/`identity.current_city` anywhere.** All three category fixes require new mapping entries for this skill specifically.

Every proposed addition directly supplies the exact fact the corresponding failing scenario asks for. No addition is speculative or unrelated to an observed failure.

## 5. Confirmed minimal change set (4 edits, 3 conceptual categories)

1. **TC-6 fix** — enrich `life_context.family`'s description (fixes `about`'s 2 indirect FAMILY failures).
2. **Add `life_context.family`** to `general_chat`'s conditional fields, using the same enriched description (fixes general_chat's 1 FAMILY failure — a literal-word match, so the enrichment isn't even strictly required for this one scenario, but is applied for consistency).
3. **Add `life_context.friends`** to both `about`'s and `general_chat`'s field mappings (fixes 6 FRIENDS failures).
4. **Add `identity.hometown` + `identity.current_city`** to `general_chat`'s field mappings (fixes 4 LOCATION failures).

All four are pure field-registry/skill-mapping edits (`PersonaFieldRegistryRepository.update()` for the description, `SkillContextRequirementRepository.update()` for the two skills' field lists) — no resolver algorithm change, no renderer change, no engine wording change, no persona-data change, no scenario change. Both repositories already expose `update()` methods, enabling the same fully-isolated, in-memory, non-persisted change pattern already proven for TC-6 in Phase 8.

**Audit conclusion: confirmed. Proceeding to the controlled SS-M/SR-M experiment.**
