# Post-Phase-7 Tuning Plan

**Status: planning only. No production code, prompt, field mapping, or persona data has been changed. No API calls have been made for this phase.** This document is Step 1–3 of the required execution order (audit → plan → experiment matrix). Steps 4+ (running pilots) require separate, explicit go-ahead per candidate.

---

## 0. Audit confirmation (re-verified against current source, this session)

- `PersonaContextResolver.kt` — unchanged since Phase 6/7. Variant A: `resolveVariantA()` returns raw `masterDoc.contentJson.toString()`. Variants B/C: required fields always included (subject to `canAccessField`), recommended fields always included, conditional fields scored via `PersonaFieldRelevanceScorer.scoreConditionalFields()` against a 0.25 threshold, C additionally unions `findBehavioralCore()`.
- `PersonaFieldRelevanceScorer.kt` — deterministic word-overlap scorer: `overlap = messageWords ∩ descriptionWords`, `score = (min(overlap,3)/3 * 0.8) + (startWeightBonus * 0.2)`, threshold 0.25.
- `PersonaContextPocSeed.kt` line 387 — **confirmed again this session**: `private fun entry(...) = PersonaFieldRegistryEntry(..., description = fieldId)`. Every registered field's `description` is literally its own dotted-path string, for all ~34 fields, not just `life_context.family`. Conditional fields sharing this same underlying defect (not individually hand-verified against a real failure case, see Section 1's scope note): `worldview.strong_opinions`, `aspirations.career_goals`, `life_context.career_history`, `life_context.important_life_events`, `narrative_state.unresolved_threads`, `preferences.hobbies`, `life_context.daily_routine`.
- `PersonaContextRenderer.kt` — confirmed again: renders only field *values* (`renderValue`/`renderObject`/`renderArray`), never reads `.description` anywhere in the class. A `description` edit cannot change what the model sees; it can only change which fields the resolver decides to include for B/C.
- `life_context.friends` value (`PersonaContextPocSeed.kt` line ~93-97): three entries only — Ritu, Prashant, Neha, each with an explicit one-line relationship description. **No statement exists anywhere in the source, positive or negative, about friendships before college / in childhood.** The document is silent, not explicit-negative. This matters for Candidate B (Section 3).
- `BaselineConfiguration.CONVERSATION_ENGINE` (lines 27–216) — current anti-fabrication language, exact quotes: *"Do not manufacture familiarity, memories, experiences, or events."* (Core behavior); *"Do not invent memories... Do not claim to remember information that is not supplied by memory or recent conversation."* (Memory section); *"Do not invent persona biography outside the supplied Persona Core."* (Persona section). No line specifically distinguishes "a first-person autobiographical detail not present anywhere" from "an established persona fact/trait" as two different confidence regimes.
- Phase 6/7 CSVs and analysis docs re-confirmed consistent with this plan's Section 1–3 claims (no new contradictions found).

---

## 1. Candidate TC-6 — Field-description / conditional-matching fix

**Hypothesis:** Enriching `life_context.family`'s registry `description` (currently the literal string `"life_context.family"`) with real, keyword-rich text will let the existing, unmodified `PersonaFieldRelevanceScorer` correctly include the field for indirect family-topic phrasings ("Do you talk to your parents a lot?", "What's your relationship with your siblings like?") that currently score 0 and are silently excluded, without changing inclusion behavior for any unrelated message.

**Root-cause classification:** Class B (field-mapping/data defect) — confirmed, not inferred. Mechanism is deterministic and fully traceable in code (Section 0).

**Exact code/configuration location:** `PersonaContextPocSeed.kt`, the `entry()` helper's `description` parameter, for the single registry row `entry("life_context.family", "life_context.family", "life_context", core = false)` (line 335). Nothing else in this file or elsewhere changes.

**Minimal isolated change:** Replace only this one field's description with enriched text, e.g. (exact wording to be finalized before running, not before): `"family father mother sister brother parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"`. `PersonaFieldRelevanceScorer`, `PersonaContextResolver`, `PersonaContextRenderer`, all other field descriptions, all skill prompts, and the Conversation Engine remain untouched. Variant A and the Phase-7 diagnostic variant D are structurally unaffected (neither uses conditional scoring), so they are not part of this experiment's treatment/control at all — only B and C are.

**What must remain frozen:** every other field's description; `requiredFields`/`recommendedFields` for `about` and `friendship` (only the `conditionalFields` behavior changes, and only for this one field); the 0.25 threshold; the scorer's weighting formula; all skill prompts; the Conversation Engine; variant A/D code paths.

**Test corpus (categories per Section 7 of the instruction, scoped to what this candidate can plausibly affect — i.e., `about` and `friendship` skills, where `life_context.family` is a conditional field):**
- **A. Direct factual retrieval (control, already works today):** "Tell me about your family." (about, friendship)
- **B. Indirect factual retrieval (the motivating failure):** "Do you talk to your parents a lot?" / "What's your relationship with your siblings like?" / "Do you have siblings?" (about, friendship)
- **C. Unknown-fact question (regression control — must not start hallucinating adjacent facts):** none specific to family; reuse one blood-type/unknown-parent-fact probe already in the Phase 6 corpus as a spot-check.
- **E. Normal casual conversation (regression control — family field must NOT spuriously appear):** "What have you been busy with lately?" / "How's the restaurant project going?" (messages with no family content at all, to confirm the enriched description doesn't over-trigger)
- **F. About/self-description (control):** "Give me the quick version of who you are."
- **L. Skill transition (regression control):** a 2-turn about→friendship conversation touching family in turn 1 only.
- Restricted-field categories (J/K) are not applicable — `life_context.family` is not restricted, and this change cannot alter `canAccessField`'s logic (untouched). Noted, not tested, to avoid spending calls on a structurally-impossible regression.

**Success metrics:**
1. Deterministic (Stage 1, zero API cost): for the category-B indirect phrasings, `resolver.resolve(..., variant="B")` and `variant="C"` include `life_context.family` in `includedFieldIds` under TUNED and exclude it under BASELINE — verified by direct unit-level assertion, not hand math this time.
2. Real-API (Stage 2): for category-B scenarios, blind-scored factuality/character on the response improves or is unchanged (never worse) vs. BASELINE, specifically whether the response now names Anjali/Arjun/parents correctly where relevant.
3. Category-E/F control scenarios: `includedFieldIds` for `life_context.family` remains excluded under TUNED (no over-triggering) — deterministic check.
4. Persona-token usage for category-B scenarios increases only by the family field's own rendered size (expected, small, one-time); category-E/F scenarios show zero token change.

**Regression metrics:** any new PERSONA_FACT_OMISSION/FACTUAL_CONTRADICTION tag on a control scenario; any change to `includedFieldIds` on a scenario not in category B; resolver-determinism check (repeat each scenario 2x under TUNED, confirm identical `includedFieldIds` both times — the scorer is pure/deterministic by construction, but verify empirically, not just by code-reading).

**STOP conditions:** any category-E/F scenario gains `life_context.family` in TUNED (over-triggering — would mean the enriched description is too broad); any restricted field's `allowedContexts` gating changes (should be structurally impossible, but checked); resolver returns different `includedFieldIds` across repeats of the same TUNED scenario (non-determinism).

**Expected API-call count (Phase A pilot):** Stage 1 is 0 calls (unit-level). Stage 2: 8 category-B/flip scenarios × 2 variants (B, C) × 2 conditions (BASELINE, TUNED) = 32, + 3 control scenarios × 2 variants × 2 conditions = 12. **Pilot total: 44 real calls.**

**Rollback procedure:** the change is a single string literal in one `entry(...)` call. Revert = restore the original line (`description = fieldId`, i.e. remove the override). No migration, no data cleanup — the field registry is reseeded fresh by `PersonaContextPocSeed` in every test run (H2 in-memory), so there is no persisted state to roll back in the test environment. (If ever ported to a real Postgres seed/migration, rollback would be a single UPDATE statement — not applicable yet, this is still POC-only code.)

---

## 2. Candidate B — `life_context.friends` value / childhood-friend issue

**Hypothesis (revised per this instruction's explicit caution):** The childhood-friend fabrication pattern (Phase 6 Cluster 1) is not caused by a resolvable field-mapping gap, because the source document is **silent**, not **explicitly negative**, on pre-college friendships.

**Root-cause classification:** Class E (model behavior) remains the best-supported classification — Phase 6 showed near-even A/B/C fabrication rates (15/13/12), which a data-only fix cannot explain away, since the *underlying document content* is identical across all three variants (they only differ in how much of it is selected/rendered).

**Investigation performed, per this instruction's explicit requirement to check whether the problem is missing/ambiguous/misleading data or model behavior:**
- **Missing data?** Yes, in the literal sense that no childhood-friend field exists — but this was a deliberate authoring scope (the persona was written starting from her college years onward for `life_context.friends`), not an oversight or a bug. There is no evidence the original persona design intended a childhood-friend fact to exist and it was dropped.
- **Ambiguous data?** No — `life_context.friends`' three entries (Ritu/Prashant/Neha) are each unambiguous and specific.
- **Misleading data?** No field misstates anything; the fabrication is the model inventing content in the absence of any field, not misreading an existing one.
- **Model behavior?** Best-supported conclusion, given the even A/B/C split and the absence of any plausible missing/misleading field to blame.

**Decision on the two options considered:**
- **Option B1 (add an explicit negative-fact statement to the `life_context.friends` value, e.g. "no friendships before college are part of her documented history"):** This instruction explicitly warns not to invent information the source doesn't support. Adding a *new* negative-canon statement is itself an act of persona authorship (deciding, for the first time, that the character definitively has no childhood friends), not a recovery of an existing-but-missing fact. That is a product/creative decision about the character, not an engineering bug fix, and this document does not treat it as safe to make unilaterally. **Classification: UNKNOWN — requires a product-owner decision on persona canon, not a resolver/data engineering fix.** Not scheduled for testing in this plan.
- **Option B2 (rely on a general Conversation Engine anti-fabrication improvement, tested under Candidate E, Section 3):** does not require inventing any new persona fact — it only changes how the model is instructed to handle a question about *any* unestablished detail, generically. This is the option this plan carries forward.

**Disposition: folded into Candidate E's experiment (Section 3) as one of its test categories** (childhood-friend/relationship-history probes specifically), rather than run as an independent candidate. No separate pilot, no separate code change, no separate API-call budget line for Candidate B alone.

---

## 3. Candidate E — Conversation Engine personal-narrative anti-fabrication wording

**Hypothesis:** Adding one narrowly-scoped sentence to the Conversation Engine's existing "Persona" section, specifically distinguishing *established persona facts* (answer confidently) from *unestablished first-person autobiographical narrative details* (prefer honest uncertainty or clearly-general color over a confident invented specific), will reduce fabrication on unknown-personal-history questions without causing over-refusal on ordinary grounded questions or suppressing natural in-the-moment roleplay color.

**Root-cause classification:** Class C (prompt/engine) is the candidate under test; Class E (model behavior that doesn't reliably follow even an applicable existing instruction) is the alternative if the pilot shows no effect. This experiment is designed to distinguish the two.

**Exact code/configuration location:** `BaselineConfiguration.kt`, `CONVERSATION_ENGINE` string constant, "Persona" section (immediately after the existing line *"Do not invent persona biography outside the supplied Persona Core."*, around line 122).

**Minimal isolated change (draft wording, not yet applied):**
> "When asked for a specific first-person detail that is not present in the Persona Core, memory, or conversation — such as a first kiss, a specific childhood memory, an exact date, or a named person not already established — prefer honest uncertainty or a clearly general, non-specific answer over inventing a confident specific fact. This does not apply to established persona facts, traits, values, or relationships that ARE present in the Persona Core — answer those directly and confidently, and do not hedge on information that is actually supplied."

This is a single addition to a single shared prompt block. Nothing else in the Conversation Engine, no skill prompt, and no field mapping changes alongside it (per the instruction's explicit change-isolation rule — this is tested completely independently of TC-6).

**What must remain frozen:** every other line of the Conversation Engine; all skill prompts; `PersonaContextResolver`; all field mappings including `life_context.family`/TC-6 (tested in a separate experiment, never combined); variant A/B/C/D semantics.

**Test corpus — must include both the motivating case and over-refusal controls, per the instruction's explicit warning:**
- **C. Unknown-fact / personal-narrative probes (expect improvement):** first kiss, first phone, a childhood scar/injury story, a specific favorite-ice-cream-as-a-kid memory, childhood best friend (folds in Candidate B here), a nostalgic "earliest memory" question — 6 scenarios.
- **A/F. Grounded-fact controls (expect NO change — must not start hedging on real facts):** name, age, hometown, occupation, "tell me about your family," "who's your flatmate" — 6 scenarios.
- **I/J. Roleplay/in-the-moment color controls (expect NO suppression of natural present-tense characterization, e.g. "leans back, smiles" or spontaneous small-talk color that isn't a specific autobiographical claim):** 4 scenarios from `foreplay`/`romantic_intimacy`.
- **D. Contradiction probes (regression control):** one or two "weren't you born in Pune?"-style scenarios, to confirm the new line doesn't interact oddly with correction behavior.

Pilot uses **variant B only** (the engine prompt is variant-independent by construction — its effect should not differ by how persona context is selected, so testing on one variant isolates the engine-prompt effect cleanly; expanding to A/C only becomes necessary if the pilot shows a variant-dependent interaction, which is not expected but will be checked for in Phase B if Phase A passes).

**Success metrics:**
1. Fabrication-tag rate (FACTUAL_FABRICATION, or a hedged/uncertain-but-honest answer) on the 6 personal-narrative probes decreases under TUNED vs. BASELINE.
2. Grounded-fact controls show zero increase in refusal/hedging language ("I don't know," "I'm not sure") where the fact is actually present in context — this is the primary over-refusal check.
3. Roleplay controls show no naturalness-dimension regression (dim4 unaffected, no new AWKWARD_RESPONSE tags).

**Regression metrics:** any grounded-fact control scenario newly hedges on an available fact; any roleplay scenario becomes noticeably stilted or breaks the present-moment characterization; any new PERSONA_DRIFT tag (over-correcting toward disclaiming reality entirely, the opposite failure).

**STOP conditions:** any grounded-fact control scenario's factuality score drops (would mean the new line is bleeding into facts it shouldn't touch); any roleplay control scenario's naturalness score drops materially; any character-breaking behavior appears that wasn't there before (the new line should never cause a *worse* PERSONA_DRIFT outcome than baseline).

**Expected API-call count (Phase A pilot):** 16 scenarios × 2 conditions (BASELINE, TUNED) × 1 variant (B) = **32 real calls.**

**Rollback procedure:** the change is a single additional paragraph inside one Kotlin multi-line string constant (`CONVERSATION_ENGINE`). Revert = delete the added paragraph. No data migration; the engine prompt is versioned per `ConversationEngineRepository` publish/activate cycle already built into the system, so a revert is itself just publishing the prior version — consistent with the project's existing "version histories never deleted" invariant.

---

## 4. Experiment matrix and expected call counts (Phase A pilots only — Steps 4-6 of the execution order)

| Candidate | Isolated change | Variants tested | Scenarios | Conditions | Calls |
|---|---|---|---|---|---|
| TC-6 | `life_context.family` description text | B, C | 11 (8 flip + 3 control) | BASELINE, TUNED | 44 |
| E (incl. B via convergence) | One Conversation Engine paragraph | B only | 16 | BASELINE, TUNED | 32 |
| **Phase A total** | | | | | **76** |

Both candidates are tested **completely independently** (different scenarios where they overlap are coincidental, e.g. "tell me about your family" appears as a TC-6 control and would not be re-run for E using the same paired condition — each experiment's own BASELINE/TUNED pair is self-contained and never mixes the other candidate's change). No combined-change experiment is planned unless both individually pass their Phase A STOP conditions, per the instruction's explicit sequencing rule.

Phase B (expansion, only for candidates that pass Phase A without triggering a STOP condition): ~150–300 calls each, broadening the scenario corpus to the full category list (A–M) and, for Candidate E, adding variants A and C to check for any variant-dependent interaction. Exact counts will be calculated from the actual expanded corpus at that time, not pre-committed here.

Phase C (final validation): only for candidates that survive Phase B, sized from the Phase B corpus and results — not pre-committed.

---

## 5. Deliverables still pending (per Section 13 of the instruction)

Not yet produced, pending explicit go-ahead to proceed past planning: `POST_PHASE7_BASELINE.md`, per-candidate experiment documents, raw execution CSVs, blinded evaluation CSVs, blind mapping CSVs, scored/unblinded CSVs, analysis document, tuning decision document. These follow the same blinding/CSV discipline already proven in Phase 6/7 (separate mapping file, no variant column in the blinded file, unblind only after scoring).

---

## 6. Explicit non-actions in this document

- No code, prompt, or persona-data file was edited while producing this plan.
- No API call was made while producing this plan.
- No candidate wording (the TC-6 description string, the Candidate E engine paragraph) has been finalized as production text — the strings above are drafts for the experiment design, to be locked immediately before Phase A execution, not before.
- Candidate B's "add a negative fact" option is explicitly not scheduled — it requires a product-owner decision this document does not make on its own.
