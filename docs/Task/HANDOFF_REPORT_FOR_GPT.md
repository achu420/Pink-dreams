# Pink Dreams — Observability Work Stream Handoff (Tasks 2–6)

## Context / why this work happened

Pink Dreams is a Kotlin/Ktor backend for a persona-based conversational AI
platform. It routes generation calls through OpenRouter across ~27
upstream providers. A prior work stream had already brought latency down
significantly (p50 ~5.3s, p95 ~10.0s, average ~5.9s, ≤10s SLA 95.6%,
Intent-classification reliability 100%). The remaining latency problems
were rare generation/provider outliers, not a systemic issue.

Rather than continuing to guess which component was slow, the explicit
decision was: **stop optimizing latency, build full observability first,
establish a real SLA baseline, give admins visibility into the system,
then resume optimization only when it can be justified by evidence.**
That produced a 5-task sequence (Tasks 2 through 6), executed in strict
order, no latency-optimization code touched in this stream.

## What was built, task by task

### Task 2 — LLM Observability (raw exchange capture)
Every LLM call the system makes — Intent classification, primary
generation, memory extraction, continuity summarization, memory-engine
maintenance — is now captured as a row, on **both success and failure**,
without any call-site rewiring:

- `ObservableLlmClient` is a decorator that wraps the single shared
  `LlmClient` instance exactly once, at construction time.
- An `ExchangeCapturing` interface lets the decorator recover the raw
  provider exchange even when the underlying call throws, so exceptions
  don't lose the request/response pair.
- Every exchange is tagged with an explicit `workload` string at the call
  site (`intent_discovery`, `primary_generation`, `memory_extraction`,
  `continuity_summarization`, `memory_engine_maintenance`).
- Captured fields: turn ID, conversation ID, workload, model, provider
  (actual upstream host, e.g. CoreWeave/DeepInfra/Fireworks), latency,
  prompt/completion/reasoning/total tokens, finish reason, HTTP status,
  an outcome enum (`SUCCESS`, `MALFORMED`, `BUDGET_EXHAUSTION`,
  `PROVIDER_ERROR`, `EXCEPTION`), error class/message, and the full raw
  request/response bodies.
- Persistence failures are swallowed (`persistSafely`) so observability
  code can never break a user-facing response.
- 10/10 tests passing.

### Task 3 — SLA / Performance Data Model
A pure aggregation layer (`PerformanceMetricsRepository`) reading the
Task 2 table — no second write path:

- Percentiles (p50/p75/p95/p99/max/avg) computed in application code
  using nearest-rank, so results are identical on H2 (tests) and
  PostgreSQL (prod) — the two engines don't agree on a percentile
  function.
- SLA buckets: ≤5s / ≤8s / ≤10s / >10s / >20s, plus a within-10s rate.
- Error-rate breakdown by outcome type.
- Breakdowns by workload, model, and provider.
- Per-turn reconstruction (`turnBreakdown`) that lists every LLM call
  belonging to one turn and computes on-path latency (Intent +
  generation only, excluding async side-channels like memory extraction).
- 8/8 tests passing; cross-checked against live data (45 exchanges,
  p50=1861ms, p95=24114ms — matched a manual SQL query).

### Task 4 — Admin AI Settings Verification (no code, audit only)
Before building UI on top of "settings," this task nailed down what is
*actually* admin-editable in production vs. what only looks like it is:

- **Genuinely admin-editable, live**: `model`, `temperature`,
  `maxOutputTokens` — persisted in `ai_settings`, take effect on the next
  request, no redeploy.
- **Experimental/code-only knobs**: `generationProviderSortOverride`,
  `intentModelOverride`, `intentJsonModeOverride`,
  `intentMaxOutputTokensOverride` — set once at process startup in
  `Application.kt`; changing them requires a code change and redeploy.
- **Test-Chat-only pins**: per-conversation overrides that never
  propagate to production state.
- Flagged an honest gap: skill/persona/intent-result breakdowns aren't
  queryable yet because "which skill was selected" isn't a column
  anywhere, only free text inside a metadata blob.

### Task 5 — Admin Observability UI
A new read-only route tree (`AdminObservabilityRoutes`, admin-gated same
as every other admin route) plus a new "Observability" tab in the
existing plain-HTML/JS admin console:

- `GET /v1/admin/observability/latency-dashboard` — overall +
  per-workload/model/provider stats, SLA buckets, error rates, filterable.
- `GET /v1/admin/observability/exchanges` — list by conversation or
  workload (lazy: summaries only).
- `GET /v1/admin/observability/exchanges/{id}` — full detail including
  raw request/response, fetched only on click.
- `GET /v1/admin/observability/turns/{turnRequestId}` — every LLM call
  for one turn, in order, with on-path latency.
- 11/11 tests passing. Live-verified: 45 exchanges, 9 distinct upstream
  providers, zero secret/`Bearer`-token leakage anywhere in the payloads.

### Task 6 — Export / Analysis
Two more endpoints reusing the same filters and auth:

- `GET /v1/admin/observability/export/exchanges.csv` — bulk CSV of
  17 summary columns. **Deliberately excludes raw request/response
  bodies** (conversation content is sensitive, and CSV is a bad format
  for large multi-line JSON anyway) but **preserves every exchange ID**
  so an analyst can join a row of interest back to the detail endpoint
  for its full payload.
- `GET /v1/admin/observability/export/performance.json` — the same
  aggregate shape as the dashboard endpoint, for offline/programmatic
  analysis.
- 4 new tests (15/15 total in that test file). Live-verified against
  real data; grepped a 200-row export for `bearer`/`api key`/`sk-or` —
  zero matches.

## Bugs found and fixed along the way

- **Test Chat silently losing production defaults**: `TestChatService`
  used `.copy()` unconditionally, which overwrote non-null production
  config with `null` whenever a test conversation didn't explicitly pin
  a value. Fixed with an `?: productionDependencies.X` fallback pattern,
  plus a regression test. This was a real correctness bug independent of
  the observability work — Test Chat and production could silently
  diverge.
- JVM out-of-memory during full test suite runs (fixed by capping test
  heap to 2g in `build.gradle.kts`).
- A `ConcurrentModificationException` in a pre-existing test caused by
  unsynchronized iteration over a list mutated by background threads.

## Current state

- Full test suite is green except one pre-existing, unrelated flake
  (`PhaseIMG4ImageJobInfrastructureTest`), confirmed on multiple runs
  before this work started.
- All work is committed on branch `feature/admin-3-test-chat`
  (commit `eb0f84c`), not yet pushed or merged.
- Nothing here touches generation latency, model choice, or provider
  routing — this stream is purely "see everything," not "make it
  faster."

## Known gaps (intentionally left open, not fabricated)

- No stage-level breakdown (context assembly / prompt build / persist)
  — only LLM-call-level latency. The finer timings exist in a separate
  JSON blob (`messages.stageTimingsMs`) not yet joined in.
- No skill-selection / routing-quality dashboard — same root cause:
  "which skill fired" isn't a queryable column anywhere yet.
- No outcome/finish-reason query filter on the dashboard (the data
  exists in `errorRate`, just no dedicated filter param yet).

## What should happen next

With capture → aggregate → visualize → export all in place, the
system now has enough evidence to resume latency work in a
non-speculative way: pull the SLA dashboard, look at what's actually in
the >10s bucket (by provider/model), and optimize specifically that,
rather than guessing at the whole pipeline again.
