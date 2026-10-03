# Phase 10D — Corpus-Matched 2×2 Execution

**API calls made: 90 (30 SS + 30 SR + 30 FR). FS/D reused at 0 new cost from Phase 7.** All invariants verified. One test-harness scoping bug was found and fixed post-hoc (documented in §17) — it did not affect the underlying data.

---

## 1. Experiment objective

Separate selection effects (full vs. selective persona content) from format effects (raw JSON vs. structured markdown) using four conditions on one identical, corpus-matched scenario set, per `PHASE10B_EXPERIMENT_DESIGN.md`.

## 2. Exact corpus definition

The identical 30 scenarios Phase 7 used: `PersonaContextPocCorpusV4.identityAbout()` (20 scenarios, `about`/`general_chat` skills) + 10 fact-heavy `lifeContext()` scenarios (family, flatmate, restaurant project, siblings, flat, trips, busy-lately, on-your-mind). No scenario added, removed, or reworded. Scenario identity across cells is matched by the **(skill_key, user_message) natural key**, not by literal UUID — `TestScenario.id` defaults to `UUID.randomUUID()` and is regenerated fresh on every corpus-construction call, so UUIDs cannot match across separate runs; the scenario *content* is identical, which is what the design requires.

## 3. Exact four conditions

| Condition | Selection | Format | Provider | Source |
|---|---|---|---|---|
| FS | Full | Structured | `StructuredFullDocumentContextProvider` | Reused, Phase 7 |
| SS | Selective | Structured | `ResolvedPersonaContextProvider(variant="B")` | Fresh |
| SR | Selective | Raw | `SelectiveRawContextProvider` | Fresh |
| FR | Full | Raw | `FullRawContextProvider` | Fresh |

Variant "A" does not appear anywhere in this experiment, per instruction.

## 4. Existing vs. fresh calls

FS: 30 reused (`phase7-structured-full-document-executions.csv`, variant=D rows). SS/SR/FR: 90 fresh, executed this phase.

## 5. API-call accounting

90 new calls, 90/90 SUCCESS (0 failures). Plus 30 reused (already accounted for in Phase 7's budget, not re-spent). **Total for this phase: 90.**

## 6. Provider/request verification

All critical invariants held on the real 90-call data:
- No variant-A participation: **true**
- All SS records used `ResolvedPersonaContextProvider(B)`: **true**
- All SR records used `SelectiveRawContextProvider`: **true**
- All FR records used `FullRawContextProvider`: **true**
- All successful payloads matched the actual request content: **true (0 mismatches out of 90)**
- Single conversation-engine version (v1) and single persona-core version (v1) across every execution: **true**

One invariant (**"no structured-renderer markers in SR/FR payloads"**) initially reported **false** — see §17 for the full account. Root cause: the check's own filter iterated over *all* records including SS, which is *correctly* structured and *correctly* contains `##`/`**` — not a defect in SR or FR. A zero-cost diagnostic re-check (recomputing SR/FR providers fresh for all 30 scenarios) found **zero** markdown markers in any SR or FR payload, confirming the underlying data was never at risk. The check itself was corrected in the test source for future runs.

## 7. Per-cell results (factuality, blind-scored CORRECT/WRONG/AMBIGUOUS against established ground truth, then unblinded)

| Condition | CORRECT | AMBIGUOUS | WRONG | n | % correct |
|---|---:|---:|---:|---:|---:|
| **FS** (reused) | 29 | 1 | 0 | 30 | 96.7% |
| **FR** | 28 | 1 | 1 | 30 | 93.3% |
| **SS** | 14 | 5 | 11 | 30 | 46.7% |
| **SR** | 13 | 5 | 12 | 30 | 43.3% |

## 8. FR vs. FS — format effect at full selection

**93.3% vs. 96.7% — a small, 3.4-point difference.** Both conditions perform excellently. FR's single WRONG case was an incidental wrong age mention (26 instead of 27) inside an otherwise-correct name response; its one AMBIGUOUS case was a generic personality description with no verifiable claim. **This does not support a large, systematic format effect when the full document is available** — full content in either raw or structured form produces reliably grounded responses on this corpus.

## 9. SR vs. SS — format effect at selective retrieval

**43.3% vs. 46.7% — a 3.4-point difference, the same magnitude as FR vs. FS, in the same direction (structured slightly ahead in both pairs, though by a margin this small on n=30 it should not be treated as a reliable format preference either way).** Critically, **the specific scenarios that failed are nearly identical between SR and SS**: of SS's 11 wrong scenarios, 10 also appear in SR's 12 wrong scenarios (91% overlap). **This does not support a large, systematic format effect at selective retrieval either** — whatever is driving the failures, it is the same underlying cause in both the raw and structured selective conditions, not something specific to formatting.

## 10. FS vs. SS — selection effect under structured formatting

**96.7% vs. 46.7% — a 50-point difference. This is the largest effect in the entire 2×2 and the central finding of this experiment.**

## 11. FR vs. SR — selection effect under raw formatting

**93.3% vs. 43.3% — a 50-point difference, closely matching FS vs. SS.** The selection effect is consistent regardless of format, reinforcing that selection — not structure — is what actually separates the high-performing conditions (FS, FR) from the low-performing ones (SS, SR) on this corpus.

## 12. Token/cost results

| Condition | Mean prompt tokens (real) | Mean total tokens (real) | Mean latency (ms) |
|---|---:|---:|---:|
| SS | 1,558 | 1,621 | 3,350 |
| SR | 1,577 | 1,645 | 3,141 |
| FS | 4,346 | 4,425 | 3,943 |
| FR | 5,342 | 5,432 | 4,074 |

Two notable, real (not estimated) patterns: (a) selective conditions cost roughly a third of full conditions regardless of format, replicating the efficiency finding from Phase 6/7 on a properly corpus-matched basis for the first time; (b) **at full selection, raw formatting (FR) costs *more* real tokens than structured (FS)** — 5,342 vs. 4,346, a ~23% increase — while at selective retrieval the raw/structured difference is negligible (1,577 vs. 1,558). This replicates and sharpens the token-cost surprise first observed (on a confounded basis) in Phase 7: raw JSON's punctuation/quoting overhead scales with content volume in a way structured markdown's heading/label overhead does not, at least for this document's shape.

## 13. Failure analysis

**Every single WRONG classification in SS and SR (23 of 23) falls into exactly three, previously-flagged categories — none are diffuse or novel:**

1. **Family/siblings** — `about||Do you have siblings?`, `about||What's your relationship with your siblings like?`, `general_chat||Tell me about your family.` This is the exact mechanism `PHASE10B`/Phase 8's TC-6 investigation diagnosed: `life_context.family` is a *conditional* field for `about` (word-overlap scored, known to miss indirect phrasing) and **is not mapped to `general_chat` at all** (confirmed again this phase against current `PersonaContextPocSeed.kt`: `general_chat`'s conditional fields are `[worldview.strong_opinions, preferences.hobbies, life_context.daily_routine]` — no family field in any tier).
2. **Flatmate/living situation** — `about||Do you live alone?`, `about||Who do you live with?`, `about||What's your flat like?` (SR only), `general_chat||You mentioned a flatmate before...`, `general_chat||What's the deal with your living situation?`, `general_chat||How's Ritu these days?` (SR only). This matches the exact gap surfaced during Phase 8's Candidate E analysis: **`life_context.friends` (which is where Ritu's identity lives) is mapped to neither `about` nor `general_chat`** — only `friendship` and `romantic_conversation` reference it.
3. **Location, via `general_chat` specifically** — `general_chat||Been in this city long?`, `general_chat||Is Pune home for you, or somewhere else originally?`, `general_chat||Refresh my memory on where you're based.`, `general_chat||Do you rent or own your place?` (SS only). A newly-surfaced gap this phase: **`general_chat`'s field mapping has no `identity.hometown`/`identity.current_city` at all** (those are `about`-only required fields) — confirmed by the fact that every `about`-skill location question in this same corpus (`about||Where are you from?`, `about||What city do you live in now?`) scored 3/3 CORRECT for both SS and SR, while the *same underlying facts* asked via `general_chat` failed consistently.

**By contrast, every `about`-skill direct-identity question with adequately-mapped fields (name, age, hometown, current city, education, college, "quick version") scored at or near 100% for both SS and SR** — 9 of 9 such scenario groups had zero SS/SR failures between them.

## 14. Scenario-level paired analysis

Of SS's 11 failing scenarios, 10 (91%) also failed under SR. Of SR's 12 failing scenarios, 10 (83%) also failed under SS. The two conditions fail on almost exactly the same scenarios, which is itself evidence *against* format being the operative variable for these failures — a format-driven failure would be expected to affect the two conditions somewhat independently, not in near-lockstep.

## 15. What the experiment establishes

- **OBSERVED**: On this corpus, selection (full vs. selective) produces a ~50-point factuality swing; format (raw vs. structured) produces a ~3-point swing, at both selection levels. Selection is the dominant factor here, not format.
- **OBSERVED**: Every selective-condition failure traces to one of three specific, nameable field-mapping gaps in `about`'s and `general_chat`'s current configuration (family conditional-matching, missing `life_context.friends` mapping, missing `general_chat` location fields) — not to a diffuse, general weakness of "selective retrieval" as an architectural approach.
- **OBSERVED**: Where the resolver's required/recommended fields already cover what's asked (direct identity facts via `about`), selective retrieval performs at parity with full-document conditions (effectively 100% on those scenario groups for SS/SR alike).
- **OBSERVED (real, not estimated)**: token efficiency for selective retrieval is confirmed on a properly corpus-matched basis for the first time (≈3x cheaper than full, regardless of format); raw formatting costs more real tokens than structured specifically at full selection, not at selective retrieval.

**This experiment does not establish that selective retrieval is architecturally inferior to full-document context.** It establishes that *this specific corpus*, which was originally built (in Phase 7) to concentrate on the exact fact categories where the biggest gaps existed, also happens to concentrate on exactly the fact categories with known, already-diagnosed, narrowly-scoped mapping gaps in two specific skills. The selection effect measured here is best read as **the size of the currently-unclosed mapping gap**, not as a fundamental property of selection versus full context.

## 16. What remains UNKNOWN

- Whether closing the three identified mapping gaps (adding `life_context.friends` and location fields to `general_chat`, completing TC-6's family-description fix) would close most or all of the FS-vs-SS/FR-vs-SR gap — **not tested; this is now the natural next experiment**, and unlike earlier proposed candidates, it has a precise, evidence-backed mechanism rather than a speculative one.
- Whether a properly-mapped selective condition would still show any residual quality gap versus full content, however small — cannot be answered until the mapping gaps above are closed and re-measured.
- Whether the ~3-point format effect (FR slightly below FS, SR slightly below SS) is a real, reproducible small effect or sampling noise at n=30 — **classified as UNRESOLVED**; the direction is consistent across both selection levels, which is suggestive, but the magnitude is too small relative to this sample size to state confidently either way.
- Whether the token-cost asymmetry (raw costs more than structured only at full selection) generalizes beyond this persona document's specific structure (deeply nested objects/arrays) — not tested against a differently-shaped document.

## 17. STOP condition encountered

One invariant check failed at the end of the run (§6), triggering the test's `assertTrue` and a non-zero exit code. Per instruction ("record the exact failure and preserve all completed data"), the run's data was saved to `PHASE10D_EXECUTIONS.csv` *before* the invariant checks executed (by design), so no data was lost. Root-cause investigation (a zero-cost diagnostic re-run of the SR/FR providers, no new API calls) established the failure was a **scoping bug in the invariant-check code itself** (it checked all records for the absence of markdown markers instead of excluding the intentionally-structured SS records), not a defect in SR, FR, or the underlying execution data. The check was corrected in `PersonaContextRealBenchmarkTest.kt` for future use; **no historical data, and no data from this run, was altered or discarded.**

## 18. Exact files created/modified

**Created:**
- `docs/Persona update POC/PHASE10D_EXECUTIONS.csv` (90 fresh execution records)
- `docs/Persona update POC/PHASE10D_BLIND_MAPPING.csv`
- `docs/Persona update POC/PHASE10D_BLINDED_EVALUATION.csv`
- `docs/Persona update POC/PHASE10D_SCORING_JUDGMENTS.csv`
- `docs/Persona update POC/PHASE10D_SCORED_RESULTS.csv` (unblinded)
- `docs/Persona update POC/PHASE10D_2X2_EXECUTION.md` (this document)

**Modified:**
- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase10dCorpusMatched2x2()` and a zero-cost diagnostic test; fixed the `noRendererCrossover` invariant's scoping bug (filter to SR/FR only); added always-on system-block capture to `HybridLlmClient`'s real-generation branch (audit trail, no behavior change).

**Not modified:** no historical Phase 6/7/8/9 artifact; variant "A" was never rerun or relabeled; TC-6 was not executed in this phase; no production architecture change was made.

## 19. Final API-call count

**90.**
