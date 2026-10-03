# Phase 18 — Arithmetic / Derived-Fact Scenario Inventory

**Zero API calls. No code, mapping, persona, renderer, or historical file modified.** This is an inventory only, built from the existing persona master document (`PersonaContextPocSeed.kt`), the existing scenario corpora (`PersonaContextPocCorpusV2.kt`, `V3.kt`, `V4.kt`, and the original `PersonaContextPocBenchmarkRunner.kt`), and the existing Phase 6–17 record of what has already been run.

---

## Method

Every scenario across all four corpus files containing a number-adjacent word (old, age, year, long, since, born, when, "how many") was extracted and individually classified. Scenarios were **not** flagged as candidates merely for containing a number — each was checked against the actual persona-fact structure required to answer it.

## Full candidate list and classification

| Scenario | Skill | Classification | Reasoning |
|---|---|---|---|
| How old are you? | about | **B — Numeric recall** | `identity.age`=27 answers directly, no combination needed |
| Been in this city long? | general_chat | **A — True derived-numeric** | Requires `identity.age`(27) − the "moved at 21" fact in `life_context.important_life_events` = 6. Already used, Phases 14–17. |
| What year did you finish college? | about | **B — Numeric recall** | `life_context.education` states "2019" directly |
| Where do you see yourself in five years? | perspective_of | **D — Not relevant** | Forward-looking opinion question, "five years" is part of the question, not a fact to derive from stored data |
| You're the oldest sibling, aren't you? | about | **C — Non-numeric relational reasoning** | Requires comparing birth-order labels ("older sister" Anjali, "younger brother" Arjun) already explicitly stored as relational labels in `life_context.family` — no arithmetic needed, just correct retrieval/comparison of a stored relationship term |
| You've been at your current studio for like ten years, right? | general_chat | **A — True derived-numeric** | See detailed entry below |
| What's the longest friendship you've had? | friendship | **A/C, borderline — see confound below** | See detailed entry below |
| How long have you known each other? (multi-turn, closest-friend thread) | friendship | **A/C, borderline — see confound below** | Same underlying data-ambiguity as the friendship-duration scenario above |
| Have you two been friends long? | friendship (multi-turn, Ritu-specific) | **A/C, borderline** | Same class of issue as above — depends on an unregistered meeting-date fact |
| You've been at your current studio for like ten years, right? / How do you usually unwind after a long day? / Does your family get along with your friends? / How do you usually cope when you are? / Would you want to keep doing this long-term? / What's your take on marriage? / Are you the type to hold a grudge? / What's a belief you hold strongly? / Who do you turn to when things go wrong? / What's your love language? / etc. (all remaining matches) | various | **D — Not relevant** | Contain a number-adjacent word ("long," "when," etc.) used idiomatically, not requiring any numeric derivation from stored facts |

---

## Detailed entries for the two clean/borderline TRUE DERIVED-NUMERIC candidates beyond the already-used one

### Candidate: "You've been at your current studio for like ten years, right?" (general_chat, contradiction-test category)

1. **Exact scenario text:** "You've been at your current studio for like ten years, right?"
2. **Exact persona facts required:** current age (27); the year she joined the current studio (2020, per `life_context.career_history`'s value: "...Left to work at the current smaller studio in 2020").
3. **Field IDs containing those facts:** `identity.age`; `life_context.career_history`. (Note: `life_context.important_life_events` also has a bullet — "Joining the smaller studio and finding work that felt more real" — but this bullet contains **no year**, only `career_history`'s value has "2020" explicitly.)
4. **Currently selectable for `general_chat`?** `identity.age` — **yes** (added by the Phase 13 fix). `life_context.career_history` — **no**. It is registered and conditionally mapped to `about` only (with an unenriched, TC-6-style literal description, the same defect class already documented for `life_context.family` before its Phase 8/13 fix), and is not mapped to `general_chat` in any tier, before or after Phase 13.
5. **Exact derivation required:** approximately 2024/2025-2020 ≈ current-year minus 2020, or equivalently age(27) minus (graduation age + 1 corporate year) — a two-step derivation, messier than the single-subtraction age/21 case, since it also depends on knowing her graduation age, which is itself only indirectly inferable (BA 2019, no birth year stored).
6. **Already used in Phases 14–17?** **No.** It was used once, earlier, in the original Phase 6 blinded-quality pass (batch08, scenario group `4aef68e6`) under the pre-Phase-10A-correction A/B/C variants — not in the B-vs-X-vs-L diagnostic chain, and not under the current (Phase 13-fixed) mapping state.
7. **Independent enough to serve as a second test?** Partially — it is a genuinely different question from the timeline scenario, but see the confound below.
8. **Competing interpretation / confound:** **This scenario cannot currently be tested as a clean "does labeling matter" experiment**, because the fact needed (the 2020 studio-join year) is not selectable under the *current* mapping at all — testing it now would reproduce a Question-A (retrieval-absent) failure, not isolate Question-B (model-use) behavior, unless a **new, not-yet-validated mapping fix** (mapping `life_context.career_history` to `general_chat`, and/or enriching its description) is designed and Stage-1-verified first. Using this scenario would re-open exactly the retrieval-completeness work Phase 18 was asked not to re-litigate, rather than provide an independent test of the labeling hypothesis alone.

### Candidate: "What's the longest friendship you've had?" (friendship) — and the related multi-turn "How long have you known each other?" / "Have you two been friends long?"

1. **Exact scenario text:** "What's the longest friendship you've had?" (single-turn); "Tell me about your closest friend." → "How long have you known each other?" (multi-turn); "Have you two been friends long?" (a separate multi-turn, Ritu-specific).
2. **Exact persona facts required:** when each friendship began — Ritu, Prashant, Neha.
3. **Field IDs containing those facts:** `life_context.friends` (contains only role descriptions — "software engineer, cat owner" for Ritu, "colleague from the current studio" for Prashant, "friend from architecture college" for Neha — **no dates**); `life_context.important_life_events` (contains "Ritu moving in about two years ago" — this is when they became **flatmates**, not when the friendship began); the actual founding date of the Ritu and Neha friendships ("met in college... from 21") exists **only** in `_full_document_supplement.timeline_and_narrative_state`, which is **not a registered field at all** (the same structural gap already documented in Phase 12 for the timeline scenario's most direct source).
4. **Currently selectable for `friendship`?** `life_context.friends` — yes (recommended). The actual founding-date fact needed to correctly answer "longest" — no, not selectable under any registered field.
5. **Exact derivation required:** a comparison across three different, differently-precise timeframes (Ritu: met in college ~6 years ago per the unregistered field, but only "moved in 2 years ago" is registered; Neha: "friend from architecture college," implying a similarly long ~6-year friendship, but with no explicit registered duration at all; Prashant: "colleague from the current studio," implying a shorter, ~studio-tenure-length friendship with no explicit duration registered either).
6. **Already used in Phases 14–17?** No. The multi-turn "closest friend" thread was scored once in the original Phase 6 blinded pass (batch07, group `b455bc9e`), pre-Phase-10A-correction, under A/B/C.
7. **Independent enough to serve as a second test?** **No — not recommended as a clean second test.** The ground truth itself is ambiguous/underspecified even in the master document: no registered field states when the Ritu or Neha friendships actually began, so there is no single, unambiguous correct answer to compare model output against, unlike the age/21 case where 27 and 21 are both unambiguous, explicitly stored numbers.
8. **Competing interpretation / confound:** any experiment using this scenario would be testing "does the model correctly guess/approximate an underspecified duration," not "does the model correctly derive a duration from two clearly stored numbers" — a fundamentally different and messier question than the one Phase 15/16/17 investigated. Data ambiguity is a confound that would contaminate any labeling-effect conclusion drawn from this scenario.

---

## Result

**The existing corpus contains no additional TRUE DERIVED-NUMERIC scenario that is both independent of the already-used timeline scenario and free of confounds that would contaminate a clean labeling-effect test.**

- One candidate ("ten years at the studio") is genuinely independent of the Phase 14–17 chain and structurally similar in *type* to the timeline scenario, but is currently **not retrievable at all** under the present mapping — using it would require first designing and validating a new mapping fix (a Question-A task), which would reintroduce exactly the retrieval-completeness work this phase was scoped to avoid, rather than provide a clean second Question-B test.
- The friendship-duration family of scenarios (three separate corpus entries) are relational/derived in *spirit* but rest on ground truth that is itself ambiguous or absent from any registered field — any result from these would be confounded by data ambiguity, not attributable cleanly to labeling.
- All other number-containing scenarios in the corpus are either pure numeric recall (Category B) or not relevant (Category D) — confirmed by individual inspection, not assumed from surface wording.

**This is explicitly distinguished from the four Phase 17 scenarios**, which were clean, unambiguous, fully-retrievable Category B (numeric recall) scenarios that simply had no headroom to show a labeling effect because baseline B was already correct on all four. The candidates surfaced in this phase are different in kind: one is blocked by a retrieval gap, the others by ground-truth ambiguity — neither is a "clean recall scenario with no headroom" the way Phase 17's four were.

**The remaining empirical options, per the instruction, without choosing between them:**
1. Repeat the existing timeline scenario ("Been in this city long?") under controlled B vs. L conditions (multiple trials) to start separating a real labeling effect from single-call generation variance.
2. Add a genuinely new derived-numeric scenario, designed from scratch with an unambiguous, fully-registered-and-mappable ground truth (unlike both candidates found in this inventory).

**No choice between these two options is made here. No further action is taken. This phase stops.**
