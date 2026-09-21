# Release Note — Primary Generation Latency + Response Quality Hardening

## What was measured

A live 35-turn accumulating Test Chat conversation (Simran, engine v7, casual → emotional → coaching → flirting → intimacy themes) was run through the real pipeline against the real OpenRouter API, capturing per-stage timings, token usage, and response content via the existing `stageTimingsMs` / provider-exchange diagnostics. This established the actual production-shaped latency and quality baseline — not the context-free numbers from the prior phase, which measured a fundamentally different (bottom-line, non-accumulating) workload.

Baseline findings: total per-turn latency p50 was 23.2s (avg 24.4s, p95 51.3s, max 51.9s) — roughly 49% Intent Discovery, 46% primary generation, and under 2% context assembly/memory/skill enrichment combined. Both Intent and generation reasoning-token variance were the dominant cost, not context size or prompt assembly.

A 6-pair reasoning ON/OFF replay of real captured generation prompts from that conversation (casual chat, companionship, "I freeze around girls" coaching, flirting, an AI-nature question, and intimacy pacing) showed reasoning OFF cut generation latency roughly 3-4x with no observed loss of persona warmth, naturalness, or capability-discovery quality.

The active Conversation Engine (v7) and Simran's Persona Core were also read from the live database (not assumed from the seed file) and manually reviewed against the 35-turn transcript for lecture behavior, persona consistency, and capability discovery.

## What changed

1. **Primary generation now runs with `reasoning: {enabled: false}`** (`AiRuntimeSettings.generationConfig()`). Intent Discovery, memory extraction, continuity summarization, and memory-engine maintenance are untouched — all remain at the provider's reasoning default (on).
2. **Conversation Engine v8** (published and activated): adds one new "Comfort over analysis" section addressing a measured pattern where emotionally loaded disclosures (loneliness, breakups) produced 150-175-word responses that explained the meaning of the user's feelings rather than simply comforting them. No other section was touched.
3. **Two stray draft engine versions (v5, v6)** — leftover debug artifacts from prior ADMIN-3 testing, one of which literally read "SHOULD NEVER APPEAR IN EXISTING TEST CHAT" — were archived. They were `published` but not active, which meant Test Chat's own "no version specified" default-resolution logic (prefers the highest-numbered published-but-not-active version) could silently serve this broken placeholder content instead of the real engine. This was corrupting the accuracy of any Test-Chat-based measurement that didn't explicitly pin a version.

## What did NOT change

- Model (`deepseek/deepseek-v4-flash-0731`), generation budget (2048), Intent Discovery's context window (RECENT_4) and reasoning (on) — all confirmed unchanged by direct code inspection and by the regression tests below.
- Persona Core, Skills, and Memory Engine were read and audited but not modified — the audit found no evidence implicating them.
- Memory extraction remains asynchronous and off the user-facing critical path.

## Latency: before vs after (same 35-turn conversation script, live)

| Stage | Before p50 | After p50 | Before avg | After avg |
|---|---|---|---|---|
| generation | 7642ms | 2553ms | 11237ms | 5272ms |
| intent_discovery | 7904ms | 6326ms | 12060ms | 9867ms |
| total_before_persist | 23213ms | 13575ms | 24389ms | 16374ms |
| response length (words) | 87 (p50) | 69 (p50) | 93 (avg) | 78 (avg) |

Generation latency dropped ~66% at p50 and ~53% on average. Total per-turn latency dropped ~41% at p50. One provider-side network spike (49.4s wall-clock for a 107-token, reasoning-disabled call) was observed in the "after" run — confirmed via the actual request/response payload that reasoning was off and token count was small, so this is infra-side variance, not a reasoning artifact.

## Token impact

Prompt tokens were essentially unchanged (~2700-2900 avg, since neither context nor engine size changed materially). Completion tokens dropped from avg 433 to avg 108 per turn (reasoning tokens no longer counted), which is the direct mechanism of the latency win.

## Tests

- `GenerationReasoningConfigTest` (new): locks `reasoningEnabled=false` for primary generation and `null` for side-channel calls.
- `IntentDiscoverySamplingConfigTest` (updated): assertion updated to reflect the new, evidence-based generation config; Intent Discovery's own config is unchanged and still asserted `null`.
- `AiRuntimeSettingsTest` (updated): two assertions updated to include `reasoningEnabled=false` in the expected `GenerationConfig`.
- Full suite: 767/767 passing.

## Known remaining risks

- The reasoning ON/OFF generation comparison used 6 paired real-prompt replays, not the 25+ live turns ideally wanted for this kind of decision — the direction and magnitude of the effect were consistent and mechanistically explicable (a stylistic/conversational task does not need a discrete reasoning judgment the way Intent's classification does), and was further corroborated by a full 35-turn live run, but a larger dedicated A/B remains the natural next validation step.
- The currently active Conversation Engine (v7/v8) has no explicit centralized "hard sexual rules" section — earlier draft versions (v1/v2) had one; the currently live content relies on distributed per-skill "do not" lists plus an undefined reference to "the product's configured adult-content boundaries." This is a policy question, not a technical one, and was deliberately left unchanged pending an explicit decision.
- Provider-side network latency spikes (observed once, ~49s) exist independent of any configuration this phase controls.
