# Phase 29 — Third-Scenario Corpus Inventory (Path B Feasibility)

**Zero API calls.** This is a static inspection of `PersonaContextPocSeed.kt` (the persona field registry, skill-context requirements, and seeded master document content) and the relevant scorer logic. No candidate is invented; every candidate below is drawn from content and mappings that already exist in the codebase. No production, mapping, resolver, renderer, or persona file was modified to produce this document.

---

## Method

Every registered field's content was re-inspected for number-adjacent values (re-confirming and extending the Phase 18/22 inventories, since new fields were added to the registry between Phase 18 and Phase 22's own audit). Every numeric value found was traced to its containing field, that field's classification (top-level scalar vs. nested array/object), and its current skill mapping(s), then checked against the six requirements Phase 29's instruction specifies for a valid Path B candidate.

## Candidate inventory and classification

| # | Candidate | Numbers involved | Containing field(s) | Classification | Reason |
|---|---|---|---|---|---|
| 1 | Sister age-gap (Anjali 29 vs. Simran 27) | 29, 27 | `identity.age` (scalar) + `life_context.family` (array) | **Already used (Phase 22/27)** | Excluded from consideration as a "third" scenario — this IS the second scenario already tested. |
| 2 | Pune-timeline (moved at 21, current age 27) | 27, 21 | `identity.age` (scalar) + `life_context.important_life_events` (array) | **Already used (Phase 14–21, 24, 26)** | Excluded — this is the first (primary) scenario. |
| 3 | Brother age-gap (Arjun 24 vs. Simran 27) | 27, 24 | `identity.age` (scalar) + `life_context.family` (array) | **E — confounded** | Same conclusion as Phase 22's own audit: the derived value (3) and the exact number pair (27, 24) are identical to the unresolved 27−24=3 alternative explanation tracked since Phase 15. Any result would be indistinguishable from a recurrence of that specific confound, not independent evidence. |
| 4 | Studio tenure ("current studio since 2020," implies ~5–6 years) | 2019, 2020 | `life_context.career_history` (scalar-classified but prose-embedded years, not a bare number) | **B — retrieval-blocked** | `life_context.career_history`'s default description equals its own literal field ID (`"life_context.career_history"`, per the seed's `entry()` helper default) — no natural phrasing shares a token with it, so it is not selectable under any current skill without a new description-enrichment fix. Registering that fix would itself be "introducing a new retrieval/mapping experiment," explicitly disallowed for Path B. Also requires an unstated "current year" assumption (Class C confound layered on top). |
| 5 | Friendship duration (Ritu/Neha/Prashant — "how long have you been friends") | none explicit; only relative/vague timing | `life_context.friends` (array, role descriptions only) + `life_context.important_life_events`'s "Ritu moving in about two years ago" (a flatmate date, not a friendship-start date) | **C — ambiguous ground truth** | Unchanged from Phase 18/22: no registered field states when any of the three friendships actually began. The only more-precise date lives in the unregistered `_full_document_supplement.timeline_and_narrative_state`. |
| 6 | "How old were you when Ritu moved in?" (derive 27−2=25) | 27, "about two years ago" | `identity.age` (scalar) + `life_context.important_life_events`'s "Ritu moving in about two years ago" (array, SAME field as candidate #2) | **E — confounded with the Pune-timeline mechanism** | This candidate is retrievable ONLY via the exact same fix already applied for the Pune-timeline scenario (adding `identity.age` + `life_context.important_life_events` to `general_chat`'s conditional fields, with the description `"years long since moved arrived came time"`) — no different fix exists for it, and using the existing one is not "independent" of the Pune scenario's own mechanism: same containing array field, same conditional-field fix, same trigger-word description tuned specifically around the Pune wording, high likelihood of triggering on overlapping vocabulary ("moved," "years," "since"). This shares too much machinery with scenario #2 to count as structurally independent, even though its derived value (25) and semantic framing (friendship/roommate history vs. residence duration) differ superficially. |
| 7 | Graduation year + current age ("how old were you when you graduated?") | 2019, 27 | `identity.age` (scalar) + `life_context.education` (recommended field for `about`, natively selectable — no fix needed) | **C — ambiguous ground truth (current-year dependency)** | `life_context.education` IS natively selectable under `about` (recommended, no fix required) — this clears the retrieval bar the other candidates fail. However, deriving "age at graduation" from a 2019 graduation year and a *current* age of 27 requires knowing the real-world current calendar year to convert "2019" into "years since then," then subtracting from 27 — this is not a stable, self-contained fact the way "moved at 21" or "Anjali is 29" are; the correct answer depends on when the conversation is happening in real time, which is not a persona fact at all. Confirmed independently in Phase 18 and Phase 22 for structurally identical reasons. |
| 8 | Daily-routine hours (up 7:30, home 6:30–7, bed 11 → derive "how many hours are you out of the house" or "how many hours do you sleep") | 7:30, 6:30, 11 | `life_context.daily_routine` (array/prose-embedded, natively conditional for `general_chat`, no fix needed) | **F — structurally incompatible with the scalar-label mechanism** | Retrieval is clean (natively selectable, no fix) and ground truth is unambiguous — but ALL operands live inside the same prose string within one array-classified field, rendered identically by B, LA, and L alike (the labeling manipulation only ever touches top-level `JsonPrimitive` scalars; `identity.age` is not even part of this derivation). LA could not differ from B at all on this scenario — there is no scalar-label distinction to expose. Confirmed identically in Phase 18/22. |
| 9 | Emergency-fund / financial-goal timeframe ("6-month emergency fund," "save enough for Ladakh") | 6 (months) | `_full_document_supplement.aspirations_extended` | **B — retrieval-blocked (unregistered field)** | Not a registered field at all — structurally unreachable by B/C/LA/L regardless of any mapping edit, per the same `_full_document_supplement` design constraint documented in Phase 12. Registering it would be a new field-registration change, explicitly out of scope. |
| 10 | "Waking up at 35" (a stated fear, implying 35−27=8 years) | 35, 27 | `_full_document_supplement.personality_extended` (Fears) | **B — retrieval-blocked (unregistered field)** | Same reason as #9 — unregistered, structurally unreachable. Also would require treating a hypothetical fear as a "fact" to derive from, which is a different kind of ground-truth ambiguity (Class C) layered on top even if it were registered. |
| 11 | Height + age (166cm, 27) | 166, 27 | `_full_document_supplement.visual_identity` | **B — retrieval-blocked (unregistered field)** | Same as #9/#10 — unregistered. Also, height and age do not combine into any meaningful derived numeric fact (no arithmetic relationship exists between them), so even if registered this would not satisfy the "derived-numeric" requirement at all. |
| 12 | Intimacy pacing ("usually takes 2–3 conversations") | 2, 3 | `relational.*` fields (restricted-adjacent, not itself restricted but thematically tied to romantic/intimate skills) | **F — structurally incompatible** | The number pair (2–3) is a vague range describing an interaction pattern, not a derivable fact combinable with any other stored number (there is no second operand to combine it with) — no B-vs-LA distinction is possible because no top-level scalar is involved in "answering" it, and there is nothing to derive (the number, if recalled at all, is a direct-recall fact, not a two-fact combination). |

## Result

**No Classification-A (clean) third candidate exists in the current corpus.** Every number-adjacent value in every registered field has now been individually traced (this inventory, extending Phase 18's and Phase 22's own audits) to one of: an already-used scenario (#1, #2), a confound with an existing unresolved mechanism (#3, #6), a retrieval block requiring a new mapping/registration change explicitly out of scope for Path B (#4, #9, #10, #11), an ambiguous or context-dependent ground truth (#5, #7), or a structural incompatibility with the scalar-labeling mechanism itself (#8, #12).

This is the same conclusion Phase 18 and Phase 22 each reached independently for the subsets of the corpus they examined — this inventory extends their coverage to the full current field registry (27 entries, including the fields added in the "Phase 1 Full Validation audit" work) and finds no additional candidate either audit missed.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE29_THIRD_SCENARIO_INVENTORY.md` (this document)

## Files modified

**None.**

## Not modified

Any Phase 10–28 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.
