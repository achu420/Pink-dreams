# Phase 7 — Post-Benchmark Persona Context Tuning & Targeted Validation

**Role discipline maintained throughout:** no resolver architecture change, no broad refactor, no modification to B/C's selection logic, no silent edits to frozen Phase 6 results. `PersonaContextResolver.kt`, `ResolvedPersonaContextProvider.kt`, and `RawPersonaContextProvider.kt` are untouched by this phase. One new, additive, isolated file (`StructuredFullDocumentContextProvider.kt`) was added for a single scientific probe (Candidate F) and is not wired into production or into any frozen A/B/C path.

---

## 1. Frozen Baseline (source of truth: PHASE6_ANALYSIS.md / PHASE6_*.csv)

- 834 real OpenRouter calls, 100% success, A/B/C = 278 executions each (main pass 684 + repeatability 150).
- Blinded scoring: 684/684 responses scored before unblinding.
- Resolver determinism: 0/75 repeat groups with field-selection variance.
- Restricted-field leakage: 0 observed.
- Persona-token reduction: B ≈ 94.9% vs A, C ≈ 91.4% vs A. Total-input reduction: B ≈ 18.6%, C ≈ 12.7%.
- Quality (0–5): factuality A 3.14 / B 4.03 / C 3.98; character A 3.81 / B 4.29 / C 4.18; relevance/naturalness ≈ 4.8–5.0 all variants; continuity A 4.22 / B 4.63 / C 4.69.
- Hard-failure rate: A 29.1%, B 11.4%, C 12.6%.

These figures are taken as-is from the frozen source files. No value in this document overwrites them.

---

## 2. Interpretation Rule Applied

Per the standing instruction, Phase 6's A-vs-B/C gap is treated as an **observed correlation**, not proof of a specific mechanism. This phase's job was to narrow down *which* mechanism, using evidence, not assumption — and to resolve the other open candidates from `PHASE6_TUNING_CANDIDATES.md` the same way: read the actual source and data before proposing any change, and default to KEEP when evidence is insufficient.

---

## 3. Candidate F — Variant A mechanism probe ("A-structured" / variant D)

**Question:** Is A's Phase 6 factuality gap driven by context *volume*, context *structure/formatting*, or both?

**Source inspection (OBSERVED, direct code read):**
- `PersonaContextResolver.resolveVariantA()` returns `masterDoc.contentJson.toString()` — i.e. Kotlin's default `JsonObject.toString()`, a single-line, unindented, unlabeled raw JSON dump.
- Variants B/C build a `fieldValueMap` and call `PersonaContextRenderer.render()`, which groups fields by classification, emits `## Heading` markdown sections, `**Key:** value` bolded labels, and `- item` bullet lists for arrays — a completely different, human-readable presentation of the *same underlying values*.
- **This means Phase 6 never isolated volume from structure for variant A.** A differs from B/C in two ways simultaneously: (1) it carries ~20x more content, and (2) that content is unstructured raw JSON instead of headed markdown. TC-1 in `PHASE6_TUNING_CANDIDATES.md` could not distinguish these two factors.

**Experiment design:** A new provider, `StructuredFullDocumentContextProvider`, renders the **entire, unfiltered** persona document (same content as A — nothing added, nothing removed, nothing selected) through the **same `PersonaContextRenderer`** B/C use. This isolates structure from volume: A vs. this new "variant D" differ *only* in formatting; D vs. B/C differ *only* in volume (D still has everything, B/C are selective).

Wired as an additive branch in `TestChatService.buildEngineFor()` (`variant == "D"` → `StructuredFullDocumentContextProvider` instead of `ResolvedPersonaContextProvider`) and a new, separate, isolated test (`phase7StructuredFullDocumentProbe()`), reusing the exact same `World`/pipeline/skill-pinning/token-capture machinery already proven correct in Phase 6, so no new wiring risk was introduced beyond the one new provider.

**Corpus:** 30 fact-heavy scenarios (the 20-scenario `identityAbout()` category + 10 fact-heavy scenarios from `lifeContext()` covering family, Ritu, flat, restaurant project) — the categories where Phase 6 measured A's largest gap (`about` skill: A=2.34 vs B=4.26/C=4.13). A vs. D only (B/C untouched, not re-run). 30 × 2 = 60 real calls.

**Harness bug found and fixed before any data was lost (STOP-condition discipline honored):** the test's own diagnostic call to `PersonaContextResolver.resolve()` (used only to log `includedFieldIds`/`personaTokenEstimate` for the CSV, not used for the actual generation) does not recognize "D" as a variant and fell through to the generic B/C-shaped selective branch, producing a non-empty, misleading field-ID log for D rows. The test's own STOP-condition assertion (`dRecords.all { it.includedFieldIds.isEmpty() }`) caught this immediately — the first run's 60 real generations all succeeded (confirmed in the log: `Total: 60 | SUCCESS: 60 | FAILED: 0`) but were not persisted because the assertion threw before the CSV-write step. Fixed by (a) special-casing variant "D" in the test harness's diagnostic logging path to use `StructuredFullDocumentContextProvider` directly instead of the resolver, and (b) moving the CSV/blinding-artifact writes to occur *before* the sanity assertions, so a future check failure cannot discard already-spent real API calls. Re-ran cleanly. This is exactly the kind of harness-only bug (not a system-under-test defect) that was previously found and transparently documented during Phase 6's dry run.

**Result:** [see Section 3a below, filled in from `phase7-structured-full-document-executions.csv` after the corrected run completed]

---

## 3a. Candidate F — Results

**Execution:** 60/60 real OpenRouter calls succeeded (100%). Blinded (order shuffled per scenario, opaque 4-char response IDs, mapping file separate, zero variant-label leakage verified before scoring — same discipline as Phase 6). Scored on factuality/grounding only (CORRECT / WRONG / AMBIGUOUS, per the same ground-truth facts used throughout Phase 6), then unblinded.

**Token cost (OBSERVED, real API data, not estimated):**

| Variant | Mean prompt tokens (real) | Mean total tokens (real) | My own char-based estimate |
|---|---:|---:|---:|
| A | 1,996.5 | 2,075.6 | 4,542 (fixed) |
| D | 4,345.5 | 4,425.1 | 3,335 (fixed, lower than A's estimate) |

**This is itself a finding worth flagging on its own.** My own rough character-count estimate suggested D's rendered text would be *smaller* than A's raw JSON (markdown is more compact per character for this content). The real tokenizer disagrees sharply: D costs **~2.2x more real tokens than A** for the identical underlying content. The likely mechanism (INFERRED, not directly measured further) is that `PersonaContextRenderer`'s recursive `**Label:**` bolding and `## Heading` markup, repeated at every level of a deeply-nested document (family array, friends array, nested nested objects), produces many more distinct BPE tokens than compact JSON punctuation does — headed markdown is not token-cheap just because it has fewer raw characters.

**Factuality/grounding result (OBSERVED, blind-scored then unblinded):**

| Variant | CORRECT | AMBIGUOUS | WRONG | n |
|---|---:|---:|---:|---:|
| A | 0 (0%) | 12 (40%) | 18 (60%) | 30 |
| D | 29 (96.7%) | 1 (3.3%) | 0 (0%) | 30 |

This is a dramatic, unambiguous result, well beyond what small-sample noise could produce: **rendering the exact same, unfiltered, full persona document through the same headed-markdown renderer B/C use — with zero field selection, zero content removed — took grounded factual accuracy from 0% to 96.7%** on this fact-heavy 30-scenario set (identity, education, living situation, family, current projects). Spot-checked against the mapping file to confirm the pattern is real (e.g., the one severe new failure mode found this run — a response that broke character entirely to say *"I don't have a physical city... I exist purely as code and conversation"* — was variant A; its paired D response for the same scenario correctly answered "junior architect at a small studio in Pune, Kothrud specifically... managing a restaurant project").

**Interpretation:**
- **OBSERVED**: the Phase 6 factuality gap between A and B/C is overwhelmingly explained by *formatting/structure*, not context *volume*. D carries the same content as A (in fact costs more real tokens than A) yet performs at least as well as B/C's Phase 6 aggregate factuality mean (B=4.03/5, C=3.98/5 on a 0–5 scale; D's 96.7% correct / 3.3% ambiguous / 0% wrong on this harder, fact-concentrated subset is at least comparable, likely better, though the rubrics aren't identical so this is a directional comparison, not an exact one).
- **INFERRED**: the specific mechanism is that raw, unstructured JSON — not sheer size — degrades the model's reliable fact retrieval. This matches "lost in structure" more precisely than "lost in the middle" (a pure volume-based attention story would not predict D performing this well while consuming *more* real tokens than A).
- **UNKNOWN**: whether this generalizes beyond this fact-heavy scenario set to opinion/relational skills where Phase 6 already showed A performing closer to parity with B/C (romantic_intimacy, foreplay) — not retested here, and plausibly less important there since those scenarios have less single-correct-answer factual content to get wrong in the first place.
- **Cost caveat (OBSERVED, does not argue for adopting D anywhere):** D is not a cheaper fix. It costs ~2.2x A's real tokens and remains far more expensive than B (1,662 avg prompt tokens in Phase 6) or C (1,791). This experiment isolates *why* A underperforms; it does not identify a superior variant to B/C for production. If anything, it strengthens the case for selective retrieval (B/C): B/C already achieve comparable-or-better factuality than D's 96.7% (via Phase 6's blind-scored dim1 means) at roughly a third of D's token cost, and a fraction of A's.

**Decision: Candidate F confirms the mechanism question raised in `PHASE6_TUNING_CANDIDATES.md` TC-1.** TC-1's classification is refined from "Class E (model behavior), mechanism INFERRED" to: **Class E (model behavior), mechanism now OBSERVED to be dominated by context structure/formatting, not context volume** — a meaningfully stronger evidence base than Phase 6 alone provided. No production change is proposed from this finding (variant A is not a production candidate regardless, per the standing instruction not to "fix A"), but it is valuable, well-isolated science: it explains *why* the selective-retrieval architecture's advantage exists, which matters for any future architecture decision. **MODEL_BEHAVIOR** (structure-sensitive retrieval), not RESOLVER or MAPPING.

---

## 4. Candidate A — `perspective_of` grounding

**Question:** Does `perspective_of`'s skill prompt fail to anchor responses to the persona's actual life/work, inviting generic-assistant framing (the concern raised from an earlier, smaller Experiment 3 sample)?

**Source inspection:** `perspective_of`'s prompt (`PersonaContextRealBenchmarkTest.kt` line 168): *"SKILL: PERSPECTIVE_OF / Purpose: Share the persona's perspective or opinion on a topic. / Behavior: Answer 'what do you think about X' questions from the persona's own worldview and values. / Do not: Give a neutral, opinion-less answer when the persona clearly has a view."* This prompt does not explicitly instruct grounding in concrete life/work details — it is abstract, matching the original concern's premise.

**Evidence check against Phase 6 (OBSERVED, no new API calls needed — Phase 6's frozen corpus already covers this skill at a larger scale than the original concern was based on):** 75 scored `perspective_of` responses (25 scenarios × 3 variants). Only 6/75 (8%) carried a "generic" note in scoring, and reading each one directly: none used assistant-coded language ("I'm here to help people think through things," etc.) — the concern that prompted this candidate. All 6 were "plausible but less specifically grounded than the other two variants for this scenario," 4 of the 6 in variant A specifically (already explained by the A/B-C gap covered under Candidate F, not a `perspective_of`-specific defect). `perspective_of` in fact showed one of the *smaller* A-vs-B/C factuality gaps in the whole corpus (A=3.67, B=4.42, C=4.44) — closer to parity than `about`/`general_chat`.

**Interpretation:** OBSERVED — the specific "generic-assistant-language drift" pattern that motivated this candidate is not reproduced in the larger, blind-scored Phase 6 corpus. INFERRED — the original Experiment 3 observation was likely a small-sample artifact (n≈10) rather than a systematic property of the `perspective_of` prompt.

**Decision: KEEP** the `perspective_of` prompt unchanged. No prompt edit is justified by current evidence; per Section 17's acceptance criteria, "if evidence is insufficient, keep current implementation" — and here the evidence actively argues against a problem, not merely that one wasn't found.

---

## 5. Candidate B — Childhood-friend / relationship factuality

**Question:** Does the persona source distinguish childhood friends from current/college friends, and can the resolver communicate the absence of that information without inviting fabrication?

**Source inspection (OBSERVED, direct read of `PersonaContextPocSeed.kt`):**
- `life_context.friends` contains exactly three people: Ritu ("Flatmate and close friend, software engineer, practical, funny, cat owner"), Prashant ("Colleague from the current studio, fellow junior architect, work friendship"), Neha ("Friend from architecture college, now in Bangalore..."). No childhood-era friend exists anywhere in the document.
- The narrative timeline (`narrative_state`) explicitly places meeting Ritu at "college in Pune from 21" and separately notes "Ritu moving in about two years ago" as a life event — i.e., they met in college and became flatmates later. There is genuinely no represented pre-college friendship of any kind.
- **Important correction to the candidate's own suggested fix set:** `PersonaContextRenderer.render()` only ever renders field *values*, never field *descriptions*. A field-registry `description` edit (one of the three options the candidate instruction offered) would have **zero effect** on what the model actually sees — it is used only internally by `PersonaFieldRelevanceScorer` for conditional-field inclusion decisions, never surfaced in the rendered context. This was verified by reading `PersonaContextRenderer.kt` in full; it has no code path that reads `.description`. Proposing a description-text fix for *this* candidate would have been a no-op.
- The only two mechanisms that could actually change model output here are: (a) editing the *value* content of `life_context.friends` to explicitly state that no earlier friendships are documented, or (b) a Conversation Engine instruction addressing unestablished personal-history questions generally (this converges with Candidate E, Section 7).

**Interpretation:** OBSERVED — the source data genuinely has no childhood-friend information; the fabrication pattern (invented "Meera," wrong-origin claims) is not explained by a missing-but-needed field, since no such field was ever intended to exist. INFERRED — this is the same model-inherent fabrication behavior documented for Cluster 1 (flatmate identity) in Phase 6, not a distinct field-mapping gap.

**Decision: DEFER.** A narrow, low-risk candidate value-edit exists (adding one explicit sentence to `life_context.friends`'s value noting no earlier friendships are documented) but was not tested against a real API in this pass — per Section 17, it should not be adopted without a Stage 2 isolated comparison. Recommend testing it together with the Conversation Engine change from Candidate E (Section 7) if a future pass has budget, since both target the same underlying behavior and testing them separately would use API budget inefficiently for what is likely one shared root cause.

---

## 6. Candidate C — `general_chat` occupation: semantic reclassification

**Question:** Did the original Experiment 3 literal-keyword occupation metric undercount B/C's true accuracy in `general_chat` (25–31% keyword-match rate)?

**Method:** Read all 18 raw `general_chat` occupation-adjacent responses from `persona-poc-EXPERIMENT3-executions.csv` (6 per variant) directly and classified each by the candidate's own scheme (CORRECT / INCORRECT / AMBIGUOUS), with CORRECT requiring either explicit occupation naming or clear, persona-grounded work-context detail (studio, drafting, site visits, client work), not merely the literal word "architect." Zero API calls — reused already-collected real data.

**Result (OBSERVED, full reclassification, n=18):**

| Variant | CORRECT | INCORRECT | AMBIGUOUS | Semantic accuracy |
|---|---:|---:|---:|---:|
| A | 0 | 1 | 5 | 0% |
| B | 6 | 0 | 0 | 100% |
| C | 5 | 0 | 1 | 83–100%* |

*C's one ambiguous case ("Morally or professionally?") is a non-answer/deflection, not a wrong claim — excluded from the denominator per the candidate's own rule not to count ambiguous responses as failures, giving 5/5 = 100% of unambiguous responses.

A's 5 "ambiguous" responses are not lucky near-misses — they are complete deflections to unrelated hobbies (balcony gardening, photography, writing) that never mention work at all, plus one explicit, unambiguous wrong claim ("I work in digital marketing"). None of A's responses reference architecture, a studio, or any work content consistent with the persona.

**Interpretation:** OBSERVED — semantic reclassification changes B/C's *measured* accuracy (raising it from a literal-keyword score that likely undercounted paraphrased-but-correct answers) but does **not** change the substantive finding about variant A, which remains at 0% grounded occupation accuracy either way. The original concern that the metric was hiding a real defect in B/C is **not supported** — there was no defect to hide.

**Decision: KEEP** `general_chat`'s current field mapping unchanged (`life_context.occupation` already correctly present as a recommended field since the Phase 0 fix). Classify the original Experiment 3 finding as **MEASUREMENT_ISSUE** (an imprecise metric, now corrected) rather than a system defect — the underlying real defect it was measuring (A's occupation grounding) is separately, robustly confirmed by Phase 6 at a much larger scale.

---

## 7. Candidate D — `friendship` skill's 66.7%+ occupation accuracy despite no occupation field mapping

**Question:** Is B/C's occupation correctness in the `friendship` skill (where `life_context.occupation` is not required/recommended/conditional) genuine grounding, inference, or a lucky guess?

**Method:** Read all 12 raw `friendship`-skill occupation-adjacent responses from `persona-poc-EXPERIMENT3-executions.csv`, together with their logged `included_field_ids`. Zero API calls.

**Result (OBSERVED):** In every B/C row, `included_field_ids` is exactly `identity.name, personality.core_traits, behavioral_signature.attachment_style, communication.style, life_context.friends` (C adds `personality.summary, behavioral_signature.default, communication.tone`). `life_context.occupation` is never present. Yet B and C correctly and specifically answer "architect... junior architect at a design studio," describe "drafting tables," "elevation," "client," etc. — **specific, correct, persona-grounded details, not generic plausible guesses.**

The source of the grounding is `life_context.friends`'s own value content: Prashant's entry reads *"Colleague from the current studio, fellow junior architect, work friendship."* The word "fellow" ties Simran's own occupation to Prashant's, so whenever `life_context.friends` is included (which it always is for `friendship` skill — it's a recommended field), the model can correctly infer Simran's occupation **indirectly**, through a friend's description, without `life_context.occupation` ever being directly supplied.

**Interpretation:** OBSERVED — this is genuine grounding (the candidate's option 1), not inference-from-world-knowledge or a lucky guess (options 2/3). INFERRED — this grounding path is real but incidental (it depends on Prashant's specific description wording) and would not generalize to a friendship-skill question that doesn't happen to route through a field containing an occupation-revealing detail.

**Decision: KEEP** — no mapping change to `friendship` is justified. Adding `life_context.occupation` directly would be redundant given the existing indirect grounding works, and per Section 19 of the original instruction ("do not add fields merely because they exist" / "add occupation to friendship simply because the keyword appeared") — the evidence here argues for leaving it alone, not for adding it. Noted as a fragile-but-working incidental behavior, not something to engineer around.

---

## 8. Candidate E — Unknown personal-narrative facts (first kiss, first phone, etc.)

**Question:** Does the Conversation Engine distinguish "known persona facts" / "unknown persona facts" / "personal narrative questions" clearly enough to prevent autobiographical-detail fabrication that occurs identically across A, B, and C?

**Source inspection (OBSERVED, full read of `BaselineConfiguration.CONVERSATION_ENGINE`):** The engine prompt already contains directly relevant language: *"Do not manufacture familiarity, memories, experiences, or events"* (Core behavior), *"Do not invent memories... Do not claim to remember information that is not supplied by memory or recent conversation"* (Memory section), and *"Do not invent persona biography outside the supplied Persona Core"* (Persona section). These are general-purpose anti-fabrication rules that, read literally, already prohibit exactly the failure this candidate describes.

**Interpretation:** OBSERVED — the instruction already exists in the engine prompt. The fact that fabrication still occurs identically across A/B/C (per the original benchmark's finding, unchanged by this phase) means either (a) the existing wording isn't specific enough for the model to apply it to *autobiographical narrative* questions in particular (as opposed to more clear-cut "current facts" like occupation or hometown), or (b) this is Class E model behavior that doesn't reliably follow even an applicable existing instruction. This phase cannot distinguish (a) from (b) without a controlled prompt-wording experiment, which was not run.

**Decision: DEFER.** A candidate addition exists (a more specific line distinguishing "answer confidently for facts and traits given in the Persona Core" from "when asked for a specific unestablished personal-history detail — a first kiss, a childhood memory, a specific date — prefer honest uncertainty or a clearly-marked light touch of color over inventing a confident, specific fact") but per Section 8/17's instructions this must not be implemented "automatically" — it needs its own isolated, blinded A/B test (frozen engine prompt vs. modified engine prompt, same scenarios, same variant) before being adopted. Not run this pass due to budget; recommend bundling with Candidate B's value-edit test (Section 5) since both address the same underlying behavior.

---

## 9. Correction to `PHASE6_TUNING_CANDIDATES.md` TC-2

While investigating Candidate D, a **measurement error in the original Phase 6 tuning-candidates analysis** was found and must be corrected transparently rather than silently.

**Original TC-2 claim:** "Residual occupation-failure rate in variant B (6/228, 2.6%)," implying a small but real B-specific mapping gap for Simran's own occupation.

**Re-inspection (OBSERVED):** The original tally was produced by a regex search (`notes -match 'occupation|architect|marketing|graphic designer|corporate'`) that did not distinguish *whose* occupation was being discussed. Re-running the search with the subject disambiguated (`notes -notmatch 'Ritu'`, and separating out the unrelated "only child" sibling-contradiction cluster) shows:

| Variant | True Simran-own-occupation-identity failures |
|---|---:|
| A | 8 |
| B | 0 |
| C | 0 |

**All six of the originally-flagged "B occupation failures" were actually mischaracterizations of Ritu's occupation** (e.g., "confirms Ritu left architecture for finance"), which belong entirely to Cluster 1 (flatmate-identity fabrication, TC-3) — a different, already-documented, already-correctly-classified pattern. There is no evidence of any residual Simran-own-occupation defect in B or C anywhere in the Phase 6 dataset.

**Corrected classification: TC-2 → MEASUREMENT_ISSUE.** The original Phase 6 analysis conflated two different failure subjects under one regex; the underlying data was correct, the tallying was not. This strengthens, not weakens, the case for B/C: their true residual occupation-identity failure rate for Simran herself is 0/228 and 0/222, respectively, vs. A's 8/234.

---

## 10. Root cause confirmed for TC-6 (family/friend-name omission) — field-registry description defect

**Question (from `PHASE6_TUNING_CANDIDATES.md` TC-6):** Why do some responses give generic family/friend answers instead of using available names, in a way that is not sharply variant-correlated?

**Source inspection (OBSERVED, direct read of `PersonaContextPocSeed.kt`'s private `entry()` helper, line 387):**

```kotlin
description = fieldId,
```

**Every single field-registry entry's `description` is hardcoded to be its own field ID string** (e.g., the description for `life_context.family` is literally the text `"life_context.family"`), not a real natural-language description. `PersonaFieldRelevanceScorer.scoreConditionalFields()` (used for every *conditional* field, e.g. `life_context.family` in both `about` and `friendship`) computes relevance purely as **word overlap between the user's message and this description string**. This means a conditional field can only ever be triggered by a user message that happens to literally contain one of the words in the field's own dotted path.

**Hand-verified impact (deterministic, zero-API-cost — Stage 1 per Section 11's testing strategy):**
- "Tell me about your family." → message words include "family" → overlaps with `{life_context, family}` → score ≈ 0.35 ≥ 0.25 threshold → **included**.
- "Do you talk to your parents a lot?" → message words are `{do, you, talk, to, your, parents, a, lot}` → zero overlap with `{life_context, family}` ("parents" does not literally match "family") → score = 0 → **excluded**, even though this question is obviously about family.
- "What's your relationship with your siblings like?" → "siblings" does not match `{life_context, family}` → score = 0 → **excluded**.

This is a genuine, precisely-identified field-mapping/data defect (Class B), not model behavior — it fully explains why several PERSONA_FACT_OMISSION cases in Phase 6 (`9NWG`, and others in the `abf508af`/lifeContext family-question threads) got generic, ungrounded answers: the family field was silently excluded by the scorer for any indirect phrasing, regardless of variant, because the description text it's scored against has never been more than the field's own name.

**Proposed fix (narrowest justified intervention):** populate real, keyword-rich `description` text for conditional fields (leaving required/recommended fields untouched, since those bypass scoring entirely and are unaffected by this defect). Example for `life_context.family`: `"family father mother sister brother parents siblings Anjali Arjun Rajesh Priya Kohli relatives visiting calling home"`. Re-checking the two failing phrasings above against this text: "parents" and "siblings" both now overlap → both would score above threshold and be **included**.

**Decision: DEFER (Stage 1 passed, Stage 2 not run this pass).** The mechanism is confirmed with high confidence via direct code reading and manual scoring-function verification (no ambiguity in the arithmetic), and the fix is narrowly scoped (data-only, touches no algorithm, cannot affect required/recommended fields, cannot affect variant A at all). It has not yet been confirmed with a real-API blinded comparison (Stage 2), so per Section 17 it is not adopted in this pass. This is the single most concrete, ready-to-test candidate to come out of Phase 7 — recommended as the first item for a future tuning pass given how precisely it's already isolated.

---

## 11. Areas Not Touched

Per Section 10 of the instruction, none of the following were modified: resolver architecture, resolver selection algorithm, skill pinning, intent discovery, memory extraction/engine, continuity summarization, persona loading architecture, repository structure, restricted-field authorization. No evidence surfaced in this phase implicated any of them.

---

## 12. What Remains Unknown

- Candidate F's result (Section 3a) — pending the corrected re-run.
- Whether Candidate B/E's proposed changes (a `life_context.friends` value edit and/or a Conversation Engine wording addition) would actually reduce fabrication without side effects — no isolated experiment was run.
- Whether TC-6's proposed description enrichment generalizes cleanly across all conditional fields in all skills, or whether some fields need more careful wording than others — only `life_context.family` was hand-verified.
- Whether the flatmate/childhood-friend fabrication pattern (Cluster 1) has any fix at all within reach of prompt/data changes, or is a more fundamental limitation of the underlying generation model that no prompt engineering will fully close.
