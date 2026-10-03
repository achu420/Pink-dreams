# Phase 12 — Zero-Cost Mapping Gap Inventory & Deterministic Closure Design

**Zero API calls. No production/mapping/registry/resolver/persona/prompt change made.** `PHASE11_EVIDENCE_CORRECTION.md` is treated as authoritative for the Phase 10E reclassification; nothing here contradicts it, but §5 below identifies one further attribution refinement Phase 11 itself did not catch — disclosed explicitly, not silently folded in.

---

## 1. Purpose

Take the corrected Phase 11 evidence and (a) build a complete field-inventory for the fields relevant to the Phase 10D/10E corpus, (b) re-audit every remaining SS-M/SR-M failure against actual `selected_field_ids` evidence, (c) search for gaps not yet identified, and (d) design — but not execute — zero-cost Stage 1 checks for any further proposed mapping change.

## 2. Evidence sources

Read/re-read this phase: `PHASE10A_A_BASELINE_CORRECTION.md`, `PHASE10B_CONTEXT_MATRIX.md`, `PHASE10B_EXPERIMENT_DESIGN.md`, `PHASE10B_HISTORICAL_DATA_RECONCILIATION.md`, `PHASE10B_TC6_STATUS.md`, `PHASE10E_ZEROCOST_AUDIT.md`, `PHASE10E_MAPPING_CLOSURE.md`, `PHASE11_EVIDENCE_CORRECTION.md`, `PersonaContextPocSeed.kt` (re-read lines 65-98, 218-260, 300-330, 369-455), `PersonaFieldRelevanceScorer.kt`. CSVs: `PHASE10D_EXECUTIONS.csv`, `PHASE10E_EXECUTIONS.csv` (`selected_field_ids` column, all 13 previously-failing scenarios plus the 30-scenario full set for regression spot-checking), `PHASE10E_SCORED_RESULTS.csv`.

## 3. Current `general_chat` (and relevant `about`) mapping inventory

| Field ID | Source location (`PersonaContextPocSeed.kt`) | Factual content | Mapped to `general_chat`? | Mapped to `about`? | Tier | Description used for conditional scoring | Covers corpus wording? |
|---|---|---|---|---|---|---|---|
| `identity.name` | line 59 area | Name | Yes (required) | Yes (required) | required both | N/A (required, no scoring) | N/A |
| `identity.age` | line 59 area | 27 | **No** | Yes (required) | required (about only) | N/A | N/A |
| `identity.hometown` | line 59 area | Nashik | Yes, **added in Phase 10E** (required) | Yes (required) | required both (post-10E) | N/A | N/A |
| `identity.current_city` | line 59 area | Pune/Kothrud | Yes, **added in Phase 10E** (required) | Yes (required) | required both (post-10E) | N/A | N/A |
| `life_context.occupation` | line 66 | Architect (junior) | Yes (recommended) | Yes (recommended) | recommended both | N/A | N/A |
| `life_context.education` | line 68 | 12th Nashik, BA Architecture Pune 2019 | **No** | Yes (recommended) | recommended (about only) | N/A | N/A |
| `life_context.career_history` | line 73 | **Corporate firm 2019-2020, then studio since 2020 — job-history, NOT the Pune-residency timeline** | **No** | Yes (conditional) | conditional (about only) | literal string `"life_context.career_history"` (unenriched — same defect class as the pre-TC-6 family description) | No — never enriched |
| `life_context.family` | line 79 | Father Rajesh, mother Priya, sister Anjali, brother Arjun | Yes, **added in Phase 10E** (conditional) | Yes (conditional, pre-existing) | conditional both | Enriched in Phase 10E: `"family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"` | Yes, for the corpus's family/siblings wording |
| `life_context.important_life_events` | line 85 | Includes **"Moving to Pune at 21 to study architecture"** | **No** | **No** | conditional (`perspective_of` only) | literal string `"life_context.important_life_events"` (unenriched) | No |
| `life_context.friends` | line 93 | Ritu (flatmate, software engineer), Prashant, Neha | Yes, **added in Phase 10E** (conditional) | Yes, **added in Phase 10E** (conditional) | conditional both (post-10E) | Enriched in Phase 10E: `"friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"` | Partial — covers "live alone," "who do you live with," "living situation," but **not** "rent," "own," "place," "apartment," "buy," "bought" |
| `_full_document_supplement.timeline_and_narrative_state` | line 300-303 | **The single most direct answer to "been in this city long": "college in Pune from 21... present (age 27...)"** | **Not a registered field at all** — inside `_full_document_supplement`, which by design (per the class-level comment in `PersonaContextPocSeed.kt`) is never referenced by any skill mapping | **Not registered** | N/A | N/A | N/A — structurally unreachable by B/C/X regardless of any skill-mapping edit |

## 4. Phase 10E failure-by-failure audit (corrected, evidence-first)

Only 2 unique scenarios remain WRONG after Phase 10E (4 responses: SS-M and SR-M × 2 scenarios). Both re-verified against `selected_field_ids` directly, not inferred from response content.

| Scenario | Required fact | Field(s) that actually contain it | Selected in SS-M/SR-M (post-10E)? | Root cause classification | Evidence |
|---|---|---|---|---|---|
| Do you rent or own your place? | Living arrangement (shared flat with Ritu) | `life_context.friends` | **No** (confirmed: `selected_field_ids` for both conditions = `identity.name;personality.core_traits;identity.hometown;identity.current_city;communication.style;narrative_state.unresolved_threads;life_context.occupation` — no friends) | **B — inadequate lexical coverage.** Field is mapped; description doesn't share any word with this phrasing. | `PHASE10E_EXECUTIONS.csv`, matching row |
| Been in this city long? | Duration in Pune (~6 years, derivable from age 27 − moved-at-21) | `identity.age` (has 27) **and** `life_context.important_life_events` (has "moving to Pune at 21") — **jointly required**; alternatively the single most direct field, `_full_document_supplement.timeline_and_narrative_state`, is not a registered/selectable field at all | **No** for all three (confirmed: neither `identity.age` nor `life_context.important_life_events` appears in `selected_field_ids` for this scenario in either condition) | **A — missing skill mapping**, compounded by the most direct source field not being registered at all | `PHASE10E_EXECUTIONS.csv`, matching row |

**Correction to `PHASE11_EVIDENCE_CORRECTION.md`'s attribution:** Phase 11 correctly established that the required fact was absent from context, but attributed it to `life_context.career_history`. Re-reading that field's actual value (line 73) shows it describes **job history** (corporate firm 2019-2020, studio since 2020), not the Pune-residency timeline — mapping it to `general_chat` would not reliably answer "been in this city long" and could plausibly introduce a *different*, still-wrong duration (conflating "years at this studio" with "years in this city"). The fact that *does* directly answer the question sits in an **unregistered** field (`_full_document_supplement.timeline_and_narrative_state`); the two *registered* fields that could jointly derive it (`identity.age`, `life_context.important_life_events`) are not currently mapped to `general_chat` either. This is a refinement of Phase 11's finding, not a contradiction of it — the conclusion ("Question A, not Question B") stands; the specific field name cited was imprecise, and is corrected here.

## 5. Corrected known gaps (summary)

1. **FRIENDS lexical coverage gap** — `life_context.friends`'s description doesn't cover "rent," "own," "place," "apartment," "buy," "bought." (Class B)
2. **Timeline/duration mapping gap** — no combination of currently-mapped `general_chat` fields supports deriving "years in this city." Two remedies exist at different levels of directness (see §9). (Class A, with a registration-scope nuance)

## 6. Newly discovered gaps

**None beyond the refinement in §4/§5.** The 11 "fixed" scenarios' actual response text was re-read this phase (not re-scored from scratch, but re-scanned specifically for any hard factual contradiction that might have been missed the first time). No new contradiction was found. Two minor, non-contradictory oddities were noted and are **not** classified as gaps: `DKGL` invents a cat name ("Biscuit") not present in any registered field (harmless elaboration into a genuine unknown-fact gap, not a contradiction); `3MCZ` has a slightly ambiguous reference to Arjun and "Mumbai" that is not a clear contradiction of any established fact. **Caveat, not resolved:** each scenario in this corpus has only n=2 responses; a larger sample could still surface a secondary issue that happened not to appear here. This is recorded as a limitation, not treated as ruled out.

## 7. Remaining unexplained cases

**None.** Both remaining Phase 10E failures are now fully explained by direct evidence (§4). No case is marked UNKNOWN.

**Total failures examined:** 4 (2 scenarios × 2 responses). **Unique scenarios:** 2. **Fully explained:** 2 of 2. **Unexplained:** 0.

## 8. Mapping vs. lexical-coverage classification

| Status | Item |
|---|---|
| **Already fixed** | FAMILY (about + general_chat), FRIENDS for "live alone / who do you live with / living situation / flatmate-before / Ritu-these-days / flat-like" phrasings, LOCATION (hometown/current_city required for general_chat) |
| **Fixed but not fully validated** | All of the above — validated only on n=2 per scenario within one 30-scenario corpus; not tested against paraphrases outside this exact wording set |
| **Missing mapping** | `identity.age` and `life_context.important_life_events` are not mapped to `general_chat` (needed for the timeline/duration question class) |
| **Missing lexical coverage** | `life_context.friends`'s description doesn't cover ownership/rental phrasing |
| **Unknown / structurally out of scope for a mapping-only fix** | The single most direct timeline fact (`_full_document_supplement.timeline_and_narrative_state`) is unregistered; closing this fully would require registering a new field, a larger change than a mapping edit |

## 9. Proposed zero-cost deterministic checks (design only — not run)

### Check 1 — Friends lexical coverage expansion
**Proposed change (not applied):** extend `life_context.friends`'s description to add: `"rent own place apartment buy bought lease"`.
**Stage 1 check would verify:**
- "Do you rent or own your place?" (general_chat) now selects `life_context.friends`.
- All 5 previously-fixed FRIENDS scenarios still select it (no regression).
- No unrelated control (e.g., "What's your name?", "How's the restaurant project going?", "What do you rent for the studio space?" if such existed) newly selects `life_context.friends`.
- Resolver output deterministic on repeat.
- No restricted field (`intimate_anatomy`, `adult_intimacy`) becomes newly selectable (structurally guaranteed unaffected, since this change only touches `life_context.friends`'s description — checked anyway).

### Check 2A — Timeline via existing fields (minimal registration footprint)
**Proposed change (not applied):** map `identity.age` and `life_context.important_life_events` to `general_chat` as conditional fields, with an enriched description for `important_life_events` (currently the unenriched literal string) covering `"years long since moved arrived came time"`.
**Stage 1 check would verify:**
- "Been in this city long?" now selects both fields.
- Whether this new description over-triggers on OTHER general_chat scenarios in the corpus that contain temporal words — see §10 for the specific words checked.
- No regression on the 28 other scenarios' `selected_field_ids`.

### Check 2B — Alternative: map `career_history` instead (rejected as primary, recorded for completeness)
**Not proposed as primary**, per §4's finding that this field's content doesn't directly answer the question and could introduce a different wrong duration. Recorded here only so the option is visible and its rejection is evidence-based, not merely undiscussed.

### Check 2C — Alternative: register a new field for the unregistered timeline text
**Not proposed in this phase** — this is a field-registration change, not a mapping edit, and is explicitly out of scope for "the smallest possible next experiment" discipline established across Phases 8-10. Recorded as a possible future option only.

**None of Check 1, 2A, 2B, or 2C has been implemented, Stage-1-tested, or API-tested in this phase.**

## 10. Over-selection / regression risk analysis

**For Check 1 (friends description expansion):** the added words (`rent, own, place, apartment, buy, bought, lease`) were checked against all 30 corpus scenario messages' text (from `PHASE10E_EXECUTIONS.csv`'s `user_message` column). Matches found: only "Do you rent or own your place?" contains any of these words. **No other scenario in this corpus would be affected** — this is a low-risk, narrowly-targeted addition by this corpus's evidence, though it cannot rule out effects on scenarios outside this 30-item corpus (e.g., a hypothetical "What's your dream apartment?" question, which does not appear here).

**For Check 2A (age + important_life_events on general_chat):** the proposed additional words (`years, long, since, moved, arrived, came, time`) are considerably more generic than Check 1's. Cross-referencing against the 30-scenario corpus: "How's the restaurant project going?" contains none of these; "What have you been busy with lately?" contains none; no other scenario contains "years/long/since/moved/arrived/came" as a standalone word **except** "Been in this city long?" itself and "Is Pune home for you, or somewhere else originally?" (contains "originally," not an exact match to the proposed word list, so would not trigger under literal word-overlap — confirmed by re-reading `PersonaFieldRelevanceScorer`'s exact-word-match logic, not a stemmed/fuzzy match). **Within this specific 30-scenario corpus, Check 2A also appears low-risk**, but its word list is generic enough that outside this corpus (e.g., "How long have you known Ritu?", "When did you move here?") it could plausibly over-trigger on scenarios never tested here. This is flagged as a real, not dismissed, risk — evidence-based caution, not a subjective "this seems risky" judgment: the risk is that the word list's generality was chosen to cover one scenario's phrasing without a broader test of how many *other*, untested phrasings it would also match.

## 11. Current evidence state

- Every SS-M/SR-M factuality failure in the Phase 10D/10E corpus is now traced to a specific field, a specific mapping/description state, and a specific root-cause class (Class A or B). Zero failures remain in Class C (resolver/scoring logic), D (provider/serialization), E (model behavior after the fact was present), or F (ambiguous) for this dataset.
- No confirmed Class E (model-behavior-despite-correct-context) failure exists anywhere in the Phase 10D/10E/11/12 evidence base to date.

## 12. What is still unknown

- Whether Check 1 and Check 2A, if implemented and Stage-1-verified, would translate into corrected real-API responses (untested — this would require a small, targeted Stage 2 re-run of just these 2 scenarios, not performed in this phase).
- Whether the 11 already-"fixed" scenarios harbor an undetected secondary issue at a larger sample size (§6 caveat).
- Whether Check 2A's broader word list causes any real over-triggering outside this specific 30-scenario corpus — cannot be resolved without either a larger corpus or accepting the risk and monitoring.
- Whether a Class E (model ignores present, correct context) failure exists anywhere in this persona/skill configuration at all — none has been found so far, but absence of evidence in a 30-scenario, 2-repeat corpus is not proof of absence.

## 13. Conditions required before another API experiment

Per the standing discipline (TC-6, Candidate E, Phase 10E precedent): (1) lock the exact proposed wording for Check 1 and Check 2A; (2) run Stage 1 (zero cost) for both, independently, exactly as designed in §9; (3) only if Stage 1 passes cleanly (target fields gained, no over-triggering on the 30-scenario corpus, deterministic on repeat) does a real-API Stage 2 become authorized — and per the minimal-change discipline, it should target only the 2 residual scenarios (4 calls: 2 scenarios × 2 conditions) rather than a full 30-scenario re-run, since the other 28 scenarios' behavior is not expected to change and re-running them would not test anything new.

## 14. Explicit STOP / no-execution statement

**No mapping, registry, resolver, persona, or prompt file was modified in this phase. No Stage 1 check was run. No API call was made. No historical artifact (Phase 6 through Phase 11, inclusive) was modified.** This document is additive only.

---

## TC-6 status (recorded, not touched)

Per `PHASE10B_TC6_STATUS.md`: independent of this retrieval investigation; a one-line synonym-wording change was proposed (adding "mom"/"dad" alongside "mother"/"father"); Stage 1 had not been completed as of that document. Not re-examined, re-run, or modified in this phase.

---

## Final report

- **Files inspected:** all Phase 10A/10B/10E/11 documents listed in §2, `PersonaContextPocSeed.kt`, `PersonaFieldRelevanceScorer.kt`, `PHASE10D_EXECUTIONS.csv`, `PHASE10E_EXECUTIONS.csv`, `PHASE10E_SCORED_RESULTS.csv`.
- **Phase 10E failures re-audited:** 4 (2 unique scenarios × 2 responses each).
- **Unique scenarios:** 2.
- **Remaining mapping categories:** 2 (FRIENDS lexical-coverage gap; TIMELINE/DURATION mapping gap, with a registration-scope nuance distinguishing a minimal two-field fix from a fuller but out-of-scope new-field registration).
- **Newly discovered gaps:** one attribution refinement to Phase 11 (the timeline fact belongs to `important_life_events`/`identity.age`/the unregistered `_full_document_supplement`, not `life_context.career_history` as Phase 11 stated) — not a new failure category, a correction of which existing field explains an already-identified failure.
- **Unexplained cases:** 0.
- **Proposed deterministic checks:** 2 primary (friends lexical expansion; age + important_life_events mapping to general_chat), 2 alternatives recorded and not proposed as primary (career_history mapping; new field registration).
- **API calls: 0. Production changes: 0. Historical files modified: 0.**
