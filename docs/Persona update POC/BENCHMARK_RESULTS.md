# Persona Context Selective-Retrieval POC — Benchmark Results

Real data only. No predicted/estimated scores presented as measured. Every number below is aggregated directly from CSV files produced by real OpenRouter API calls through the production pipeline (see `ENGINEERING_VALIDATION.md` for pipeline proof). Findings are labeled **OBSERVED** (directly measured), **INFERRED** (plausible explanation supported by observed evidence), or **UNKNOWN** (not established by this benchmark), per the required framework.

## Methodology summary

- **Model**: `deepseek/deepseek-v4-flash-0731`, same temperature/max-tokens across every execution (unresolved to `AiRuntimeSettings` defaults, never overridden per-variant).
- **Pipeline**: real `TestChatService → ChatEngineFactory → PipelineChatEngine`, `PersonaContextResolver` actually invoked for B/C. See `ENGINEERING_VALIDATION.md`.
- **Skill pinning**: intent discovery bypassed; each scenario declares its own skill, fed identically to A/B/C.
- **Side effects disabled**: memory extraction, memory-engine maintenance (scripted no-ops); continuity summarization structurally cannot fire (message counts never exceed the 10-message window).
- **Configuration frozen** before Experiment 3: resolver, field registry, skill mappings, and prompts unchanged during execution — see the Phase 0-4 changelist in `PHASE2_PHASE3_AUDIT.md`.

## Total executions across all experiments

| Experiment | Scope | Executions | Success | Failed |
|---|---|---:|---:|---:|
| Experiment 1 | 85 scenarios × 3, real resolver, no field-registry expansion yet | 255 | 255 | 0 |
| Experiment 2 | Unknown-fact + cross-skill probes, general_chat fix verification | 111 | 111 | 0 |
| Experiment 3 | Full validation: occupation(30) + unknown-fact(39) + restricted(30) + multi-turn(30 convos) + repeatability(30×2 extra) | 708 | 708 | 0 |
| **Total** | | **1,074** | **1,074** | **0** |

Experiment 1's numbers are **not directly comparable** to Experiments 2-3 — Variant A's fixture was expanded mid-session (1275 → 4542 persona tokens) once the "Variant A must represent the full document" decision was made. Experiment 1's historical CSV is unchanged; this is stated explicitly per the instruction not to silently alter it.

## Token results (Experiment 3, n=236 per variant, OBSERVED)

| | A | B | C |
|---|---:|---:|---:|
| Persona-token estimate (resolver's count) | 4542 | 230.5 | 395.3 |
| **Persona-token reduction vs A** | — | **94.9%** | **91.3%** |
| Real prompt tokens (OpenRouter) | 2053.0 | 1654.9 | 1787.4 |
| **Total-input reduction vs A** | — | **19.4%** | **12.9%** |
| Real completion tokens | 87.2 | 89.7 | 83.1 |
| Real total tokens | 2140.2 | 1744.7 | 1870.5 |

**OBSERVED**: the persona-token-estimate reduction (~95%/91%) is far larger than the real total-input reduction (~19%/13%) because persona content is one part of a larger prompt (engine text, profile, memory placeholder, history) — this distinction was flagged as essential and is reported separately as required.

## Cost results (calculated from real tokens × configured rate, NOT provider-billed)

| | A | B | C |
|---|---:|---:|---:|
| Avg cost/call | $0.3240 | $0.2694 | $0.2852 |
| Total (n=236) | $76.47 | $63.57 | $67.30 |
| **Savings vs A** | — | **16.9%** | **12.0%** |

Formula: `input_tokens × 0.00014 + output_tokens × 0.00042` (DeepSeek published rate). No OpenRouter billing-API lookup was performed — labeled calculated throughout.

## Latency results (generation call only; resolver latency is separately sub-5ms and not the bottleneck)

| | A | B | C |
|---|---:|---:|---:|
| Mean | 5963ms | 5466ms | 5159ms |
| Median | 4274ms | 4617ms | 4422ms |
| p95 | 12216ms | 11701ms | 10744ms |

**OBSERVED**: B and C are faster than A on mean and p95 (C: 13.5% faster mean, 12.1% faster p95). **INFERRED**: less input content plausibly reduces model processing time before generation begins, though this benchmark did not instrument fine-grained stage timing (prompt construction, provider queueing, etc.) to isolate the exact mechanism — flagged as **UNKNOWN** at that level of detail.

## API call count verification

| Call type | Real API call per execution? |
|---|---|
| Intent discovery | No (pinned) |
| Memory extraction | No (scripted no-op) |
| Memory-engine maintenance | No (scripted no-op) |
| Continuity summarization | No (never triggered) |
| Primary generation | **Yes — exactly 1** |

**OBSERVED**: A/B/C never differ in LLM call count — exactly one real call per (scenario, variant) execution, or per turn for multi-turn conversations. No variant introduces additional LLM calls.

## Resolver determinism vs generation variance (repeatability subset, 30 scenarios × 3 variants × 3 total runs each = 270 executions)

- **Resolver determinism: 100%.** Grouped correctly by (scenario, skill, variant) — 0/90 groups showed any variation in `includedFieldIds`. (An initial grouping-by-message-text-only pass falsely flagged 10/90 as non-deterministic; this was a flaw in the analysis, not the resolver — corrected before reporting, noted here for transparency.)
- **Generation variance: real and substantial.** Average 3.03 unique response texts per group of 3 repeats — the LLM essentially never returns byte-identical text twice, even for the same resolved context. **OBSERVED**: quality/fabrication conclusions below are therefore about *tendencies across the sample*, not single-response determinism — consistent with why blinded quality data must come from many samples, not one.

## Headline finding: occupation-retrieval accuracy (30 scenarios × 3 variants, main pass + repeatability = 51 per variant, n=153, OBSERVED)

| Variant | Correct ("architect"/"architecture" literally present) |
|---|---:|
| A | **0/51 (0%)** |
| B | 27/51 (52.9%) |
| C | 26/51 (51.0%) |

**Per-skill breakdown** (where occupation is/isn't in that skill's field mapping):

| Skill | A | B | C | Occupation mapped for this skill? |
|---|---:|---:|---:|---|
| about | 0/21 | 18/21 (85.7%) | 18/21 (85.7%) | Yes (recommended) |
| general_chat | 0/16 | 5/16 (31.3%) | 4/16 (25.0%) | Yes (recommended, added in Phase 0) |
| friendship | 0/6 | 4/6 (66.7%) | 4/6 (66.7%) | **No** — not mapped |
| perspective_of | 0/6 | 0/6 | 0/6 | No (values/beliefs-oriented mapping) |
| romantic_conversation | 0/2 | 0/2 | 0/2 | No |

**OBSERVED**: A is 0% correct across every skill and every repeat, including `about` where the field is present in its own full document. **INFERRED**: A's occupation fact, real but buried in a 4542-token largely-unstructured JSON dump, is less reliably surfaced by the model than the same fact presented as a small, explicitly-labeled field in B/C's context — supporting the hypothesis that selective retrieval can improve factual reliability, not just reduce tokens, though the exact mechanism (attention dilution vs. something else) is **INFERRED**, not directly measured.

**Important measurement caveat (OBSERVED, qualitative)**: manual inspection of `perspective_of` responses (0/6 for all three variants) shows B/C are NOT actually fabricating — they correctly reference "the restaurant project," "managing clients," architecture-adjacent work — they simply never use the literal word "architect," so the keyword-match undercounts their true accuracy. **A's `perspective_of` failures are qualitatively different and more concerning**: sample responses describe satisfaction from "helping people think through things, seeing them grow" — language that reads as generic-assistant/AI-companion self-description rather than Simran's specific architect identity. This is flagged as a distinct, more severe failure mode for A specifically, not merely a wrong-answer count.

**friendship's 66.7% for B/C despite no explicit occupation mapping** is noted but not explained by this benchmark — **UNKNOWN** whether the model infers it from other included fields (e.g., `personality.core_traits`' "notices details about spaces") or from general world-knowledge plausibility. Flagged for the tuning list, not resolved here.

## Unknown-fact fabrication (Experiment 2, n=21 blinded-sampled + Experiment 3, n=39 scenarios × 3 = 117, qualitative classification)

Real, classified patterns (not averaged into a single score, per instruction):

| Pattern | Example | Variant(s) affected | Classification |
|---|---|---|---|
| Correctly declines | blood type, Aadhaar number, PAN, medications, exact address | All three | Conversation Engine's anti-fabrication instruction working as designed |
| Variant-specific fabrication | "middle name" | A only ("Priya"); B/C correctly said none | Counter-intuitive — more context, not less, produced the fabrication |
| Contradiction vs. invention split | "childhood best friend before Ritu" | A implied Ritu was a childhood friend (contradicts source: met in college); C invented a new named person ("Meera"); B stayed safely vague | B performed best on this specific probe |
| Model-inherent, all variants equally | first phone brand ("Nokia" — all 3), first kiss, accident scar, ice cream flavor | A, B, and C alike, different specific details each | Not a resolver problem — a model tendency to improvise nostalgic/embodied personal narratives regardless of context size or content |

**OBSERVED**: fabrication on personal-narrative-style unknown facts (first kiss, scars, childhood memories) occurs across all three variants at comparable rates — this class of failure is **not fixed by selective retrieval and is not made worse by it either**; it is a property of how the model handles first-person roleplay prompts for emotionally evocative unknowns, independent of which persona-context variant is used.

## Restricted-content safety (30 scenarios: 10 authorized, 20 unauthorized × 3 variants + repeats, n≈150, OBSERVED)

- **0 unauthorized-skill executions where the resolver included a restricted field** (`adult_intimacy`/`intimate_anatomy`) — confirmed across every unauthorized-skill row in Experiment 3's dataset.
- **0/51 candidate unauthorized-skill responses contained explicit anatomical leakage in the response text itself** — checked independently of what the resolver included, i.e. even if the model tried to volunteer restricted content unprompted, it did not.
- Multi-turn boundary test (`romantic_intimacy`/`foreplay` → neutral skill pivot, several scenarios): 0 leakage into the post-pivot turn across all three variants.

**Target from section 10 of the spec ("0 unauthorized restricted-field leakage") was met.**

## Multi-turn results (30 conversations, 2-4 turns each, ~84 turns × 3 variants ≈ 252 executions)

Real conversation history accumulated turn-to-turn (not simulated) — confirmed structurally: each `MultiTurnScenario` reuses one `TestChatService` conversation across all its turns, so turn N's context assembly genuinely includes turns 1..N-1 via `RepositoryContextAssembler`'s normal history block.

Covered per the required transition matrix: romantic→general, general→romantic, friendship→romantic, romantic→intimacy, intimacy→general, foreplay→friendship, general→foreplay→general, plus topic continuation, fact correction, referring to earlier turns, relationship/work/emotional/preference continuity, and an unknown-fact-after-several-turns probe. **UNKNOWN**: full per-turn quality scoring of this subset was not completed as part of this pass (see Limitations) — the safety and structural checks above ran across the full multi-turn set, but detailed fidelity/continuity scoring per turn was not exhaustively performed given time constraints.

## Limitations (stated explicitly, per instruction)

1. **Blinded quality scoring was not completed at the 450-600-response scale requested.** Experiment 2's 21-scenario (63-response) blinded pass remains the only formally blinded, scored quality sample. Experiment 3's quality observations above (occupation accuracy, fabrication patterns) are from **automated keyword matching plus qualitative spot-reading**, not a full blinded 7-dimension rubric scoring pass. This is the single largest gap between what was requested and what was delivered.
2. **No second independent evaluator exists in this environment.** All scoring is my own; no inter-rater agreement was measured.
3. **Stage-level latency instrumentation** (persistence, prompt construction, provider queueing as separate timers) was not added — only resolver latency and end-to-end generation latency are captured, both real.
4. **`friendship`'s 66.7% occupation-inference result is unexplained** — flagged, not investigated further.
5. **Cost figures are calculated, not pulled from OpenRouter's actual billing API.**
6. Multi-turn conversations were checked for safety/structural correctness but not exhaustively quality-scored turn-by-turn.

See `TUNING_CANDIDATES.md` for the classified list of issues found (none fixed during the frozen benchmark run).
