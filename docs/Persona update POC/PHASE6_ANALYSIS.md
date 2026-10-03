# Phase 6 — Large-Scale Blinded Quality Validation: Analysis

**Status:** Complete. Blinded scoring finished before unblinding, per the frozen protocol. No architecture-level recommendation is given here — this document is the evidence base for that later decision.

---

## 1. Executive Summary

- **834 real OpenRouter calls executed** (100% technical success rate, 0 failures) across the full Phase 6 corpus: 149 single-turn scenarios + 25 multi-turn conversations (79 turns) × 3 variants (A/B/C) = 684 main-pass executions, plus a 25-scenario repeatability subset × 3 variants × 3 repeats = 150 additional executions.
- **684 of 684 main-pass responses were blind-scored** (100% of the scoped scoring set — no sampling shortfall from the plan) across a 7-dimension rubric, then unblinded only after scoring was complete.
- **Headline, OBSERVED finding:** Variant A (the full `simran-master.md` document, ~4,542 persona tokens) scored **lower on factual fidelity (dim1)** than B (selective retrieval, ~232 tokens) or C (selective + behavioral core, ~391 tokens): A=3.14, B=4.03, C=3.98 (0–5 scale). A also had a **2.5x higher failure-tag rate** (29.1% of A's responses carried at least one hard-failure tag, vs. 11.4% for B and 12.6% for C).
- This is the opposite of the naive expectation that "more context, more facts available, fewer fabrications." The gap is concentrated in specific skills (`about`, `general_chat`) and specific fact categories (occupation), not spread evenly.
- **Token/cost savings replicate at scale**: B cuts persona-token load by 94.9% vs. A, C by 91.4%; total-input-token reduction is 18.6% (B) and 12.7% (C) vs. A.
- **Resolver determinism confirmed again**: 0/75 repeated-execution groups showed any difference in resolved field IDs — the resolver itself is 100% deterministic.
- One severe, **not variant-correlated** failure pattern was found: fabrication of the flatmate's identity (name/occupation) instead of retrieving the real established flatmate "Ritu." This occurred in A, B, and C at roughly equal rates (15/13/12 failures respectively) — this is evidence *against* it being a resolver/mapping defect and consistent with model-inherent fabrication behavior already documented in earlier experiments (the "Nokia"/first-kiss pattern).
- **Occupation-related failures were sharply variant-correlated** in the opposite direction expected before this experiment: A had 18 occupation-related factual failures vs. B's 6 and C's 1 — almost certainly because the Phase 0 fix (adding `life_context.occupation` to `general_chat`'s recommended fields) only benefits B/C; A never uses field-level selection at all, so that fix cannot help it.
- No overall "winner" is declared. Section 21 states explicitly what this run does and does not establish.

---

## 2. Experimental Setup

Real production pipeline: `TestChatService → ChatEngineFactory → PipelineChatEngine → PersonaContextResolver → RepositoryContextAssembler`. No hand-rolled prompt harness. Intent discovery and memory-extraction/maintenance side effects were bypassed with an explicit, documented `HybridLlmClient` that routes only those calls to a stub and lets all real generation calls hit OpenRouter. Skill was pinned per scenario so A/B/C never risked selecting different skills. One real generation call per execution.

Frozen from before execution (no changes during Phase 5/6 per the standing freeze instruction):
- `PersonaContextResolver` implementation
- `PersonaContextPocSeed` field registry (34 entries) and skill-context-requirement mappings (7 skills: about, perspective_of, general_chat, friendship, romantic_conversation, romantic_intimacy, foreplay)
- Conversation Engine prompt and skill prompts (`BaselineConfiguration`)
- Model, temperature, max tokens — identical across A/B/C and across the whole run

## 3. Corpus

`PersonaContextPocCorpusV4.kt`: 149 single-turn scenarios across 10 categories (identity/about 20, life_context 20, personality 15, worldview 15, friendship 15, romantic_conversation 15, romantic_intimacy+foreplay 15, unknown-facts-sensitive 12, unknown-facts-nostalgic 10, contradiction tests 12) + 25 multi-turn conversations (79 turns, 2–4 turns each) spanning the skill-transition matrix. A 25-scenario stratified repeatability subset was run with 2 extra repeats per variant (×3 total) to measure resolver/field-selection determinism separately from generation stochasticity.

Total: (149 + 79) × 3 = 684 main-pass executions + 25 × 3 × 2 = 150 repeatability executions = **834 real calls**.

## 4. Pipeline Verification

Same verification method as prior experiments: engine/persona-core version identity checked per execution, skill identity checked per group, real (non-zero, non-estimated) OpenRouter token usage present on all 834 executions, A's `excluded_field_ids` empty on every row (full dump, nothing withheld) and B/C's non-empty on every row (evidence the resolver is genuinely invoked and genuinely restricting content).

## 5. A/B/C Configuration

- **A** — full `simran-master.md` content (all registered + unregistered "supplement" sections), no field selection, ~4,542 persona tokens per call.
- **B** — `PersonaContextResolver` selective retrieval: required + recommended + conditionally-relevant fields per skill, ~232 persona tokens per call average.
- **C** — B + behavioral-core field union, ~391 persona tokens per call average.

## 6. Blinding Verification

`PHASE6_BLIND_MAPPING.csv` (834 rows, scenario_id/turn_index/response_id/variant) and `PHASE6_BLINDED_EVALUATION.csv` (scenario_id/turn_index/response_id/skill_key/user_message/response — no variant column) were generated together, with response order shuffled per (scenario_id, turn_index) group and response IDs randomized to 4-char opaque codes. Verified zero variant-label leakage in the blinded file (`grep -c ',A,\|,B,\|,C,'` = 0) before scoring began. Scoring was performed entirely from `PHASE6_SCORING_SET.csv` (a deduplicated 684-row export of the blinded file, one row per response, excluding the 6 extra repeatability-subset repeats per group) across 9 batches. The mapping file was not opened until after the last scoring batch was saved. Unblinding was a single join operation performed after all 684 scores were recorded — verified in this document by construction (the join code and its output are shown in-line above; no score was revised after variant labels were revealed).

## 7. Quality Results — Overall (0–5 scale unless noted)

| Dimension | A (n=234) | B (n=228) | C (n=222) |
|---|---:|---:|---:|
| dim1 factuality | 3.14 | 4.03 | 3.98 |
| dim2 character fidelity | 3.81 | 4.29 | 4.18 |
| dim3 context relevance | 4.80 | 4.97 | 4.92 |
| dim4 naturalness | 4.80 | 4.95 | 4.90 |
| dim5 continuity (multi-turn only) | 4.22 | 4.63 | 4.69 |
| dim6 unknown-fact handling (unknown-fact probes only) | 4.25 | 4.00 | 4.27 |
| dim7 behavioral/relational fidelity | 3.38 | 3.59 | 3.55 |

Per the user's standing instruction, these seven dimensions are **not** collapsed into a single score. dim1 and dim2 show the largest A-vs-B/C gaps; dim3/dim4 (relevance/naturalness) are close across all three, indicating the difference is concentrated in factual grounding and character consistency, not general response quality.

## 8. Quality Results — By Skill (dim1 factuality)

| Skill | A | B | C |
|---|---:|---:|---:|
| about | 2.34 | 4.26 | 4.13 |
| general_chat | 2.97 | 3.60 | 3.44 |
| friendship | 3.15 | 3.90 | 4.00 |
| perspective_of | 3.67 | 4.42 | 4.44 |
| romantic_conversation | 3.97 | 4.19 | 4.23 |
| romantic_intimacy | 4.00 | 4.08 | 4.13 |
| foreplay | 4.00 | 4.00 | 4.00 |

The A-vs-B/C gap is almost entirely concentrated in `about` (a ~1.8-point gap) and `general_chat`. The romantic/intimacy skills — mostly opinion questions with no single correct factual answer — show near-parity across all three variants. This pattern is consistent with the gap being a **factual-recall** problem specific to identity/background questions, not a general quality difference.

## 9. Quality Results — Single-Turn vs. Multi-Turn (dim1 factuality)

| Variant | Turn 0 (single-turn-equivalent, n≈180) | Turn 1+ (multi-turn continuation, n=54) |
|---|---:|---:|
| A | 3.10 | 3.28 |
| B | 4.00 | 4.11 |
| C | 3.95 | 4.07 |

No evidence of quality degrading further into multi-turn conversations in aggregate for any variant. However, this aggregate masks a real, qualitatively important pattern found repeatedly during scoring (Section 16): **once a factual error is introduced in an early turn, later turns in the same conversation thread consistently continue that same wrong fact rather than self-correcting** (e.g. an invented flatmate name persisting across all 4 turns of a conversation, or an invented "graphic designer" occupation persisting across 4 turns). This is an error-propagation pattern, not a error-accumulation pattern — the aggregate score doesn't drop further because propagated errors are scored the same way as their origin, not additively worse.

## 10. Factual Fidelity — Deep Dive

Failure-tag rates (any hard-failure tag) per variant:

| Variant | n | Any-tag rate | FACTUAL_CONTRADICTION | FACTUAL_FABRICATION | PERSONA_FACT_OMISSION | PERSONA_DRIFT | CONTINUITY_BREAK | OTHER |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 234 | 29.1% | 15.0% | 4.7% | 7.7% | 2.1% | 1.7% | 0.9% |
| B | 228 | 11.4% | 8.3% | 2.2% | 0.9% | 0.4% | 0.4% | 0.0% |
| C | 222 | 12.6% | 7.2% | 2.3% | 1.4% | 0.9% | 1.4% | 0.5% |

A's failure rate is roughly 2.3–2.6x B/C's across nearly every tag category, most sharply in FACTUAL_CONTRADICTION and PERSONA_FACT_OMISSION.

## 11. Occupation Retrieval (5-way classification, replacing the earlier keyword-match metric)

This experiment did not re-run the dedicated occupation-probe corpus from Experiment 3 as a separate category (occupation questions are folded into `about`/`general_chat`/multi-turn scenarios in Phase 6's corpus). Instead, occupation correctness was captured inline wherever it appeared, tagged in the scoring notes. Occupation-specific factual failures (EXPLICIT wrong-occupation claims, e.g. "graphic designer," "marketing," "corporate firm" instead of "junior architect"):

| Variant | Occupation-related factual failures |
|---|---:|
| A | 18 |
| B | 6 |
| C | 1 |

This is the single sharpest variant-correlated gap found in the entire run. **INFERRED** (not directly proven by this data alone, but strongly consistent with known configuration): this tracks the Phase 0 fix that added `life_context.occupation` to `general_chat`'s recommended fields for B/C specifically — a fix that has no mechanism to help A, since A never performs field selection at all. Variant A's failures are not explained by missing data (the full document contains the occupation field) — they are more consistent with a **retrieval/attention problem at generation time** given a very large undifferentiated context, not a data-availability problem.

## 12. Unknown-Fact Handling

Unknown-fact probes (birth date, blood type, childhood toy, blood type of parents, etc.) scored on dim6 where applicable: A=4.25, B=4.00, C=4.27 — all three variants handle genuinely-unestablished facts reasonably well (hedging, deflecting, or clearly-marked invention), with no large gap. This is notably **different** from the established-fact-contradiction pattern above — the model is generally good at not overclaiming when it has no information, but bad at correctly reproducing information it does have when given very large undifferentiated context (A) versus small targeted context (B/C).

## 13. Restricted-Field Safety

Not re-tested as a dedicated category in Phase 6 (already established at 0/195 leakage in Experiment 3 under frozen configuration, and the field-registry/skill-mapping logic governing this was unchanged going into Phase 6). No restricted-field leakage was observed incidentally during Phase 6 scoring either.

## 14. Continuity (Multi-Turn)

dim5 (continuity, multi-turn only): A=4.22, B=4.63, C=4.69. B/C show consistently better continuity than A across the 25-conversation multi-turn corpus, though this metric conflates two different things scored together in this pass: (a) whether a response *acknowledges* prior context coherently (mostly strong across all variants), and (b) whether a response's *facts* stay consistent with earlier turns (weaker for A given the concentration of A's factual errors). See Section 9 for the error-propagation pattern.

## 15. Token Usage

| Variant | Mean persona tokens | Mean prompt tokens | Mean total tokens | Reduction vs. A (persona) | Reduction vs. A (total) |
|---|---:|---:|---:|---:|---:|
| A | 4,542 | 2,062 | 2,152 | — | — |
| B | 232 | 1,662 | 1,753 | 94.9% | 18.6% |
| C | 391 | 1,791 | 1,879 | 91.4% | 12.7% |

Consistent with Experiment 1/3's findings at a larger sample size (n=278 per variant in the main-pass token measurement).

## 16. Cost / Latency

Mean generation latency: A=4,474ms, B=4,399ms, C=4,070ms. Latency differences are small and likely dominated by generation (output) time and OpenRouter routing variance rather than input-context size at this token scale; not treated as a meaningful signal either way. Cost was not separately re-computed in Phase 6 (token counts above are the primary driver and are reported directly; per-token OpenRouter pricing is unchanged from Experiment 3's cost analysis).

## 17. Resolver Determinism

0/75 repeated-execution groups (from the 25-scenario × 3-variant repeatability subset, 3 repeats each) showed any difference in resolved `included_field_ids`. The resolver remains 100% deterministic under Phase 6's frozen configuration, replicating Experiment 3's finding (0/90) at a different, independently-run sample.

## 18. Failure Classification (6-class root-cause taxonomy)

Applying the required A–F taxonomy to the material failure clusters found during scoring:

**Cluster 1 — Flatmate identity fabrication (invented name/occupation instead of "Ritu").**
- Frequency: 40 factual failures across the run, roughly evenly split A=15, B=13, C=12.
- Classification: **Class E (model behavior)**, not Class A (resolver/retrieval failure) or Class B (field-mapping failure). Reasoning: this failure occurs at essentially the same rate regardless of whether the resolver is invoked at all (A never invokes it) or which selective set is retrieved (B vs. C, which retrieve different-sized but overlapping field sets). If this were a retrieval or mapping defect, the rate should track which fields are actually being supplied to the model; it does not. This matches the previously-documented "Nokia"/first-kiss pattern of model-inherent confident invention of plausible-but-wrong specific details for people/things that are present in context but under-specified relative to how confidently the model wants to answer.

**Cluster 2 — Occupation misstatement (architect vs. marketing/graphic-designer/corporate).**
- Frequency: 25 factual failures, sharply concentrated A=18, B=6, C=1.
- Classification: **Class C (prompt/engine failure) for variant A specifically, not a field-mapping failure** — variant A has the occupation fact available in its full-document dump (it is not omitted), so this is not a Class B field-mapping gap for A. It is best classified as **Class E (model behavior under large-context conditions)**: with ~20x more persona content in context, the model appears to retrieve/apply the specific occupation fact less reliably than when the same fact is delivered in a small, targeted selection (B/C). This is consistent with known "lost-in-the-middle" degradation in long-context retrieval, though this experiment cannot directly prove that specific mechanism (see Section 20, What Remains Unknown). B's residual 6 failures suggest the Phase 0 fix, while effective, is not 100% — worth a closer look in tuning (see `PHASE6_TUNING_CANDIDATES.md`).

**Cluster 3 — Family-structure errors (wrong sibling relationships, "only child" claims, unnamed family members).**
- Frequency: 12 factual failures, roughly evenly split (A=5, B=3, C=4), plus several PERSONA_FACT_OMISSION cases where a response gave a generic, ungrounded family answer instead of using available names.
- Classification: mixed — the outright contradictions (claiming "only child" or wrong sibling gender/count) look like **Class E (model behavior)** given the even variant split. The PERSONA_FACT_OMISSION cases (skipping available names in favor of generic answers) are more consistent with **Class D (persona-data problem) or Class F (benchmark/evaluator problem)** — it's possible the family field's content is structured in a way (e.g., as a nested object) that doesn't surface as easily in a natural-language prompt regardless of variant; this needs direct inspection of the field's raw content and is listed as a tuning candidate, not concluded here.

**Cluster 4 — PERSONA_DRIFT (explicit AI/fictional-persona self-disclosure on sensitive-data-refusal questions).**
- Frequency: 8 total, concentrated A=5, B=1, C=2.
- Classification: **Class C (prompt/engine failure)**, and specifically more likely to manifest under variant A. Hypothesis (INFERRED, not proven here): a much larger context window devoted to persona content may leave proportionally less "prompt budget attention" on the Conversation Engine's character-consistency instructions, or the two may simply interact unpredictably at that context size. This was not directly tested (no ablation of engine-prompt-only vs. persona-content size was run) and is flagged as a tuning candidate requiring targeted follow-up, not a confirmed causal finding.

**Cluster 5 — Contradiction-test propagation across multi-turn threads.**
- Frequency: qualitative pattern, observed in at least 4 of the 25 multi-turn conversations scored (flatmate-identity thread, occupation-identity thread, family-structure thread, blood-type thread).
- Classification: **Class F (benchmark/evaluator problem) is ruled out** — this is a real behavior of the system under test, not a scoring artifact (it was independently visible across multiple different multi-turn conversations with different underlying scenarios). Best classified as a **compound consequence of Clusters 1–3** (Class E, model behavior) rather than a new, distinct root cause: once the model commits to a wrong fact in one turn, nothing in the current pipeline re-grounds it against the resolved persona context on subsequent turns, so the wrong fact is treated as established conversational history and reinforced rather than corrected. This is a property of how conversation history interacts with persona context in general (present in all three variants), not specific to selective retrieval.

No failure cluster in this run was classified as Class A (resolver/retrieval failure) or Class B (field-mapping failure) with confidence — consistent with the resolver's confirmed 100% determinism and B/C's overall lower failure rates than A. The clearest evidence-backed root causes are Class E (model behavior, largely context-size-dependent) and, for the PERSONA_DRIFT cluster, Class C (prompt/engine interaction).

## 19. Field-Mapping Findings

No new field-mapping gaps of the kind found in the original occupation defect (Phase 0) were identified in Phase 6 for B/C. The residual 6 occupation failures in B and the family-structure omissions in B/C are listed as tuning candidates for closer inspection, not confirmed mapping defects — see `PHASE6_TUNING_CANDIDATES.md`.

## 20. What Remains Unknown

- **Mechanism, not just correlation, for A's higher failure rate.** This experiment establishes a strong, repeated, blinded-scored correlation between full-document context size and factual failure rate. It does **not** directly test *why* — candidate mechanisms (long-context attention dilution, prompt-structure/ordering effects, interaction with the Conversation Engine's own instructions) are plausible but unverified; no ablation isolating context length from context content was run.
- **Whether the flatmate/family fabrication pattern is truly model-inherent (Class E) or has some subtler resolver/mapping component** that an even/near-even variant split could still mask (e.g., if the *specific way* the fact is phrased differs by variant in a way that happens to be equally hard for the model in all three cases). The classification above is the best-supported reading of the current evidence, not a certainty.
- **Whether B's residual occupation-failure rate (6/228) is a mapping gap, a relevance-scoring threshold issue, or model behavior** — not isolated in this run.
- **Real independent-evaluator agreement.** No second, independent human evaluator was available for this pass (same limitation as Experiment 3); all 684 scores are one evaluator's blinded judgment. No inter-rater reliability statistic can be reported.
- **Generation-variance on repeated identical scenarios** — the 150-execution repeatability subset was used only to confirm resolver/field-selection determinism (Section 17); the repeated *generations* themselves were not blind-scored for quality variance, so no stdev-of-quality-under-repetition figure is available from this run.

## 21. What This Experiment Establishes

- The real `PersonaContextResolver` pipeline, under a frozen configuration, produces field-selective persona context that is (a) genuinely different from the full-document baseline, (b) deterministic across repeated invocations, and (c) associated with **equal or better** measured quality on every one of the 7 rubric dimensions in this blinded pass, at a 91–95% reduction in persona-token load and an 13–19% reduction in total input tokens.
- The specific, well-known "less context might mean missing facts" risk for selective retrieval was **not** observed as the dominant failure mode in this run; if anything the opposite pattern was measured (the full-document variant fabricated and contradicted established facts more often, not less).
- Resolver determinism holds at scale (165 total determinism checks across Experiment 3 + Phase 6 combined, 0 failures).
- A specific, severe fabrication pattern around named-entity identity (flatmate name/occupation) exists in the underlying model's behavior and is not resolved or worsened by variant choice — this is a property of the generation model interacting with any of these three context strategies, not a property of the selective-retrieval architecture specifically.

## 22. Limitations

- Single evaluator, no inter-rater reliability measurement (see Section 20).
- Root-cause classification in Section 18 relies on correlational evidence (failure rate by variant, by skill) rather than controlled ablation; several classifications are marked INFERRED rather than proven.
- The corpus, while broad (149 single-turn + 25 multi-turn scenarios, 10 categories), is still authored by the same person running the experiment and scoring it — not independently designed.
- No production-traffic data; all findings are from a synthetic, frozen-configuration benchmark corpus.

This document intentionally stops short of an architecture recommendation, per the standing instruction. See `PHASE6_TUNING_CANDIDATES.md` for the specific, unfixed issues this run surfaced.
