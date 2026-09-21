# Task 3 — SLA / Performance Data Model: Completion Report

(Note: this supersedes the on-disk `03_RUNTIME_PIPELINE_AND_CONTEXT_AUDIT.md`
task file for this session — per your explicit re-ordering, "Task 3" in this
work stream means the SLA/Performance aggregation layer over Task 2's raw
exchange data, not the pipeline/context audit. The audit file remains
unstarted and out of scope until you say otherwise.)

## What was built

`PerformanceMetricsRepository`
(`src/main/kotlin/com/pinkdreams/persistence/repositories/PerformanceMetricsRepository.kt`)
— a pure, read-only aggregation layer over the `llm_exchanges` table Task 2
already writes. No second write path, no cache, no materialized table:
every call queries the raw rows fresh and computes stats in application
code (percentiles need a single, engine-independent definition that works
identically on H2 in tests and PostgreSQL in production — the two don't
agree on a native percentile function/syntax).

### Capabilities

- **`latencyStats(filter)`** → count, p50, p75, p95, p99, max, avg
  (nearest-rank percentile method)
- **`slaBuckets(filter)`** → counts for ≤5s / ≤8s / ≤10s / >10s / >20s, plus
  a derived `within10sRate`
- **`errorRate(filter)`** → total / success / malformed / budget-exhaustion
  / provider-error / exception counts, plus a derived `failureRate`
- **`statsByWorkload` / `statsByModel` / `statsByProvider`** — the same
  `LatencyStats` shape grouped by each dimension
- **`turnBreakdown(turnRequestId)`** — reconstructs every LLM call that
  happened for one turn, in order, with an `onPathLatencyMs()` helper that
  sums only the user-facing stages (Intent + primary generation),
  deliberately excluding the async side-channels (memory extraction,
  continuity, memory-engine maintenance) from the user-facing latency
  figure — matching the distinction `ChatEngine.kt`'s own
  `stageTimingsMs` comment already draws
- **`MetricsFilter`** — workload, model, provider, conversationId,
  isTestChat, from/to time range — composable across every method above

## Dimensions: what's covered vs. an honest gap

The spec asked for breakdowns by Skill / Engine / Persona / Model /
Provider / Intent / Conversation / Turn / Date-time. What's directly
queryable from `llm_exchanges` today:

| Dimension | Status |
|---|---|
| Model | ✅ `statsByModel` |
| Provider | ✅ `statsByProvider` |
| Engine (= pipeline stage/workload) | ✅ `statsByWorkload` — the closest real equivalent; `llm_exchanges` has no separate "conversation engine version" column |
| Conversation | ✅ `MetricsFilter.conversationId` |
| Turn | ✅ `turnBreakdown(turnRequestId)` |
| Date-time | ✅ `MetricsFilter.from`/`to` |
| Production vs Test Chat | ✅ `MetricsFilter.isTestChat` |
| Skill / Persona / Intent result | ❌ Not implemented |

**Why the gap is real, not an oversight**: the skill actually selected for
a turn, and the persona/engine *version* used, are not columns on
`llm_exchanges` — they exist only inside a free-form JSON metadata blob on
the `messages` table (`Messages.metadata`), populated by
`RepositoryChatPersistence`. Joining against that would mean parsing JSON
per row in application code for every aggregation query, which is a real
but separate piece of work, not something to fake with a placeholder
column. The smallest safe correction, if this breakdown becomes a priority:
either (a) add `skill_key`, `engine_version_id`, `persona_core_version_id`
columns to `llm_exchanges` itself (cheap, since `ChatEngineFactory` already
knows all three at the point it tags `workload`), or (b) join through
`Messages.requestId = llm_exchanges.turn_request_id` and parse the existing
metadata blob. Option (a) is smaller and was not done here only because it
wasn't explicitly requested and touches the Task 2 write path again after
that task was already verified and reported — flagging it for your call
rather than silently expanding Task 2's scope.

## Test evidence

`src/test/kotlin/com/pinkdreams/persistence/repositories/PerformanceMetricsRepositoryTest.kt`,
8/8 passing:

1. Percentiles match hand-computed nearest-rank values against a known
   1..10-second dataset (p50=5000, p75=8000, p95=p99=max=10000, avg=5500)
2. SLA buckets count correctly across boundary values (exact ≤5000/≤10000
   boundaries included in their bucket, not excluded)
3. Error rate distinguishes SUCCESS from every failure outcome type
4. `statsByWorkload` separates intent from generation correctly
5. `statsByProvider` separates upstream hosts correctly
6. `MetricsFilter.conversationId` narrows results to one conversation
7. `turnBreakdown` reconstructs a 3-call turn in order and excludes the
   async memory_extraction call from `onPathLatencyMs()`
8. An empty result set returns `null` percentiles, not a crash or a
   misleading zero

Full suite: `gradle test --rerun` → same one pre-existing, unrelated
`PhaseIMG4ImageJobInfrastructureTest` flake as before Task 3; no
regressions introduced.

## Live evidence

Cross-checked the repository's aggregation logic against a direct SQL
query over the real PostgreSQL data from Task 2's live Test Chat
conversation (`794980c1-c60f-4727-b5d5-648dca7c4cc0`, now with more turns
sent since Task 2's report):

| workload | n | min | max | avg | ≤5s | ≤10s | >10s |
|---|---|---|---|---|---|---|---|
| continuity_summarization | 7 | 1459ms | 6119ms | 2936ms | 6 | 7 | 0 |
| intent_discovery | 12 | 739ms | 2475ms | 1497ms | 12 | 12 | 0 |
| memory_engine_maintenance | 2 | 27540ms | **54626ms** | 41083ms | 0 | 0 | 2 |
| memory_extraction | 12 | 961ms | 24114ms | 4588ms | 10 | 10 | 2 |
| primary_generation | 12 | 1160ms | 2368ms | 1742ms | 12 | 12 | 0 |

This is exactly the evidence the SLA model exists to surface: primary
generation and intent discovery — the user-facing, on-path calls — are
consistently fast and never exceed 10s across 12 real calls each. The real
outliers are entirely in the async side-channels: a second
memory-engine-maintenance batch took 54.6 seconds, and two memory-extraction
calls exceeded 10s. None of this affected the user-facing response (those
calls are fire-and-forget by design), but it is now a queryable fact
instead of an invisible background risk — which is the whole point of
"observe everything → establish SLA" before touching the Admin UI or
optimizing anything.

## Honest limitations

- Percentile/bucket/error-rate methods pull all matching rows into memory
  before computing — fine at current and near-term volume (one row per LLM
  call, not per token), but would need a SQL-side percentile approach (or
  pagination) if `llm_exchanges` grows into the millions of rows. Not a
  concern yet; flagging so it isn't forgotten.
- No caching layer — every call is a fresh query. Acceptable for an
  admin/debugging surface: correctness over speed here, deliberately.
- No API/route exposes this yet — this task built the data model only, per
  your explicit ordering that Task 4 (Admin AI Settings verification) and
  Task 5 (Admin Observability UI) come after this.

## Files changed

- `persistence/repositories/PerformanceMetricsRepository.kt` — new
- `persistence/repositories/PerformanceMetricsRepositoryTest.kt` — new, 8 tests

## Verdict

Task 3's aggregation layer is complete for every dimension `llm_exchanges`
can answer directly (workload/engine, model, provider, conversation, turn,
date-time, test-vs-production), proven by 8 passing unit tests plus a live
cross-check against real production-shaped data that surfaced a genuine,
previously-invisible 54-second outlier. The Skill/Persona/Intent-result
breakdown gap is documented, not hidden, with a concrete smallest-fix
proposal for your call.
