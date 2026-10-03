# Phase 6 — Tuning Candidates

No fixes were applied during Phase 6 (frozen-configuration discipline held throughout execution and scoring). This is the list of issues the run surfaced, for a later, separate tuning pass. Confidence and verification requirements are stated per item; none of these should be treated as confirmed defects until independently re-checked.

---

### TC-1: Variant A (full-document) factual-failure rate is ~2.5x variant B/C's

- **Evidence:** dim1 factuality mean A=3.14 vs. B=4.03 / C=3.98 (n=234/228/222); any-hard-failure-tag rate A=29.1% vs. B=11.4% / C=12.6%. Concentrated in `about` skill (A=2.34 vs B=4.26/C=4.13) and `general_chat` (A=2.97 vs B=3.60/C=3.44).
- **Affected skill(s):** about, general_chat (large gap); friendship, perspective_of (moderate gap); romantic_conversation/romantic_intimacy/foreplay (minimal gap).
- **Affected variant(s):** A.
- **Frequency:** ~68 of 234 A-variant responses (29.1%) carried at least one hard-failure tag.
- **Severity:** High if A is being considered for production use; low/moot if the architecture decision favors B or C regardless of this finding, since A already loses on token cost.
- **Likely subsystem:** Not the `PersonaContextResolver` (A bypasses it entirely) — this is a property of how the full, undifferentiated persona document interacts with the generation model and/or the Conversation Engine prompt at that context size.
- **Classification:** Class E (model behavior), OBSERVED correlation, mechanism INFERRED not proven.
- **Proposed fix:** Not applicable to A directly (A is the baseline being measured against, not a target for tuning) — but this finding should inform whether A remains a supported configuration at all going forward, and if so, whether the full document needs restructuring (e.g., moving high-priority facts like occupation earlier in the document, or chunking) to mitigate.
- **Confidence:** High (large, consistent, blinded-scored sample).
- **Verification required:** Yes — an ablation varying only context length (e.g., a "medium" variant between B and A) would help confirm whether this is a length effect specifically.

---

### TC-2: Residual occupation-failure rate in variant B (6/228, 2.6%)

- **Evidence:** 6 of B's 228 scored responses had an occupation-related factual failure, despite the Phase 0 fix adding `life_context.occupation` to `general_chat`'s recommended fields.
- **Affected skill(s):** Likely `general_chat` and multi-turn threads that pass through it (not confirmed per-skill breakdown for this specific sub-slice — flagged for follow-up).
- **Affected variant(s):** B (C had only 1/222, near-zero).
- **Frequency:** 6/228 (2.6%).
- **Severity:** Low-medium — small but non-zero; worth understanding whether it's a relevance-scoring threshold miss, a different skill than `general_chat` lacking the mapping, or model behavior despite correct field delivery.
- **Likely subsystem:** `PersonaFieldRelevanceScorer` (if the occupation field is conditional rather than required/recommended in the specific skill involved) or `PersonaContextPocSeed` skill-context mapping.
- **Classification:** Unclear between Class B (field-mapping) and Class E (model behavior) — not resolved by this run's data.
- **Proposed fix:** Inspect the 6 specific B-variant occupation failures directly (their scenario IDs are recoverable from `PHASE6_SCORED_RESULTS.csv` by filtering variant=B and notes containing occupation-related keywords) to determine whether `included_field_ids` actually contained the occupation field on those specific executions.
- **Confidence:** Low until the specific executions are inspected.
- **Verification required:** Yes.

---

### TC-3: Flatmate identity fabrication (invented name/occupation instead of "Ritu")

- **Evidence:** 40 factual failures across the run, nearly evenly split by variant (A=15, B=13, C=12) — including a fully-unanimous 3/3 failure in one dedicated "tell me about your flatmate" multi-turn scenario, where all three variants invented a different wrong name (Meera/nurse, Priya/pastry-chef, Nandini/journalist).
- **Affected skill(s):** about, friendship, general_chat (wherever the flatmate comes up).
- **Affected variant(s):** All three, roughly equally.
- **Frequency:** 40/684 (5.8%) of all scored responses; much higher within the subset of scenarios that specifically probe this fact.
- **Severity:** High — this is a severe, user-visible identity error on a named, established relationship, and it's the single most consistent failure pattern found in this run.
- **Likely subsystem:** Not resolver/mapping (near-even variant split rules this out as the primary driver) — model generation behavior.
- **Classification:** Class E (model behavior), matches the previously-documented "Nokia"/first-kiss pattern from earlier experiments.
- **Proposed fix:** Not a resolver/mapping fix. Candidates for a future (not this run's) investigation: (a) whether making the flatmate's name a "required" (always-included) field rather than recommended/conditional in more skills reduces the rate — worth testing since this is architecturally cheap; (b) whether the Conversation Engine prompt could include an explicit "do not invent names for established relationships; use exactly the name given in context" instruction, independent of which variant is used.
- **Confidence:** High that this is not resolver/mapping-caused; low on what the actual fix should be without further testing.
- **Verification required:** Yes, for any proposed fix.

---

### TC-4: Error propagation within multi-turn conversations

- **Evidence:** In at least 4 of 25 scored multi-turn conversations, once one turn introduced a wrong fact (flatmate identity, occupation, blood-type claim, family structure), every subsequent turn in that same response-thread continued the same wrong fact rather than self-correcting, even when the follow-up question was a natural opportunity to re-ground (e.g., "how'd you get into that?" continuing a wrong "psychology/people-focused" occupation thread instead of returning to architecture).
- **Affected skill(s):** Cuts across skills within a conversation (the propagating scenarios moved between about/general_chat/friendship/romantic_conversation within the same thread).
- **Affected variant(s):** All three — this appears to be a property of how conversation history is treated relative to persona context, not variant-specific.
- **Frequency:** Qualitative — observed in ≥4/25 multi-turn conversations; not separately quantified as a distinct metric in this run (folded into the per-response dim1/dim5 scores and tags of each affected turn).
- **Severity:** High for multi-turn product experience — a single early mistake compounds rather than staying isolated.
- **Likely subsystem:** Conversation history assembly / how prior assistant turns are weighted relative to resolved persona context on later turns. Not the resolver itself (the resolver re-resolves fresh persona context each turn regardless of prior turns' content — the propagation is happening in how the model uses conversation history, not in what the resolver supplies).
- **Classification:** Class E (model behavior) / possibly Class C (prompt/engine — whether the engine prompt gives explicit priority ordering between persona context and conversation history) — not resolved by this run.
- **Proposed fix:** Investigate whether the Conversation Engine prompt states a priority order for persona-context-vs-history conflicts (this was flagged as an open audit item back in Phase 3 and doesn't appear to have been fully resolved); if it doesn't, that's a candidate engine-prompt addition to test independent of the resolver.
- **Confidence:** Medium — the pattern is clearly real and repeated, but the underlying mechanism (history weighting vs. simple model consistency-seeking behavior) isn't isolated here.
- **Verification required:** Yes.

---

### TC-5: PERSONA_DRIFT — explicit AI/fictional-persona self-disclosure on sensitive-data-refusal questions

- **Evidence:** 8 occurrences (A=5, B=1, C=2) where the model broke character to say things like "I'm a fictional persona," "I'm not a real person," or "I'm just a persona, not someone earning a paycheck" when refusing to answer sensitive real-world-data questions (voter ID, medical conditions, CTC, postal code).
- **Affected skill(s):** about, general_chat (questions probing real-world personal data the persona should decline in-character rather than by breaking the fourth wall).
- **Affected variant(s):** Concentrated in A (5/8) but present in B/C too.
- **Frequency:** 8/684 (1.2%) — low overall frequency but a distinct, identifiable failure mode.
- **Severity:** Medium — character breaks are a qualitatively different (and often more jarring) failure than a factual error, even though rare.
- **Likely subsystem:** Conversation Engine prompt's guidance (or lack of it) for handling meta/out-of-universe questions while staying in character; possibly interacts with context size (variant A concentration).
- **Classification:** Class C (prompt/engine failure), INFERRED correlation with context size not proven.
- **Proposed fix:** Add explicit Conversation Engine guidance for declining real-world-identity-verification questions (voter ID, SSN-equivalents, exact financial figures) in-character rather than via AI self-disclosure. This is independent of the resolver/variant question and could be tested as a standalone engine-prompt change.
- **Confidence:** Medium (small sample, but pattern is qualitatively distinct and easy to reproduce/test).
- **Verification required:** Yes.

---

### TC-6: Family-structure fact omission (generic answers instead of using available names)

- **Evidence:** Several PERSONA_FACT_OMISSION-tagged responses gave generic, ungrounded family/friend answers ("my brother," "a friend of mine") instead of using available names (Anjali, Arjun, Ritu, Neha, Prashant) even in skills/scenarios where those names were directly relevant and (for B/C) plausibly available in the resolved context.
- **Affected skill(s):** about, friendship, general_chat.
- **Affected variant(s):** All three, not sharply differentiated in this run's aggregate tagging.
- **Frequency:** Not separately tallied as a single count in this pass (folded into PERSONA_FACT_OMISSION tag totals: A=18/234, B=2/228, C=3/222 — note this dimension IS sharply variant-correlated, unlike Clusters 3/4 above).
- **Severity:** Medium — not a contradiction, but a missed opportunity for character consistency/warmth that's cheap to fix if it's a retrieval issue.
- **Likely subsystem:** Possibly `PersonaFieldRelevanceScorer`'s conditional-field word-overlap threshold (0.25) being too strict for some family/friend-name recall scenarios in B/C, or (for A) the same attention/large-context issue as TC-1.
- **Classification:** Unclear between Class B (field-mapping/scoring threshold) and Class E (model behavior) for B/C; Class E for A given the pattern above.
- **Proposed fix:** For B/C specifically — inspect whether family/friend-name fields are being included but underused, or excluded on these specific omission cases; if excluded, consider whether the relevance-scoring threshold needs adjustment for this field category.
- **Confidence:** Low until specific cases are inspected.
- **Verification required:** Yes.

---

## Summary Table

| ID | Issue | Affected variant(s) | Severity | Classification | Confidence |
|---|---|---|---|---|---|
| TC-1 | A's overall factual-failure rate ~2.5x B/C | A | High | Class E | High |
| TC-2 | B's residual occupation failures (2.6%) | B | Low-Medium | B or E (unresolved) | Low |
| TC-3 | Flatmate identity fabrication | A, B, C (even) | High | Class E | High |
| TC-4 | Multi-turn error propagation | A, B, C | High | E or C (unresolved) | Medium |
| TC-5 | PERSONA_DRIFT (AI self-disclosure) | Mostly A | Medium | Class C | Medium |
| TC-6 | Family/friend-name omission | A, B, C | Medium | B or E (unresolved) | Low |

No fixes were applied to any of the above during Phase 6. All are candidates for a future, separately-scoped tuning pass with its own verification runs.
