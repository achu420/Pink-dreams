# Task 5 — Admin Observability UI: Completion Report

## What was built

**Backend** — `AdminObservabilityRoutes.kt`, a new read-only route tree
under `/v1/admin/observability/*`, wired into `Application.kt` alongside
every other admin route (same `dev-auth` + `AdminAuthorizationProvider`
gate as `AdminAiSettingsRoutes`/`AdminTestChatRoutes` — no parallel
authorization system invented):

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/observability/latency-dashboard` | Overall + per-workload/model/provider `LatencyStats`, `SlaBuckets`, `ErrorRate` — thin wrapper over Task 3's `PerformanceMetricsRepository`, filterable by workload/model/provider/conversationId/isTestChat/time range |
| `GET /v1/admin/observability/exchanges` | List exchanges by `conversationId` or `workload` (one is required — a dashboard should never accidentally page through every exchange ever recorded), paginated via `limit` (1–500) |
| `GET /v1/admin/observability/exchanges/{id}` | Full detail for one exchange, INCLUDING raw request/response bodies — deliberately absent from the list endpoint (lazy-loading, per the spec's UX requirement) |
| `GET /v1/admin/observability/turns/{turnRequestId}` | Every LLM call belonging to one turn, in order, plus `onPathLatencyMs` (Intent + primary generation only — the user-facing figure, excluding async side-channels) |

**Frontend** — a new "Observability" tab in the existing
`src/main/resources/admin-ui.html` (plain HTML/JS, matching the file's own
established conventions: `switchTab`, `showError`, no framework, relies on
the browser's native Basic-Auth prompt exactly like every other admin
call in this file already does). Three panels:

1. **Latency dashboard** — filterable by workload/model/provider, showing SLA buckets, outcome/error breakdown, and count/p50/p75/p95/p99/max/avg tables per workload, model, and provider
2. **Conversation exchange list** — paste a conversation ID, see every exchange, click a row to lazy-load its full detail (raw request/response, token breakdown, error info) — never fetched until clicked
3. **Turn trace** — paste a `turnRequestId`, see every LLM call for that one turn in order plus the on-path latency figure

## Required areas — coverage against the task spec

| Spec area | Status |
|---|---|
| Runtime overview (active versions/config) | Not duplicated — already served by the existing `GET /v1/admin/ai-configuration` (Task 4 verified this endpoint's exact scope); this task did not rebuild it |
| Conversation/Turn inspector | ✅ Turn trace panel + exchange list panel together answer "turns, total latency, model/provider per call, exchange IDs"; skill/intent-result and memory-retrieval-summary are NOT included — same gap Task 3 already documented (skill selection isn't a column on `llm_exchanges`) |
| LLM exchange inspector | ✅ exchange ID, time, workload, model, provider, latency, status, finish reason, tokens, raw request/response, errors. Routing configuration and retry info are not separately surfaced (OpenRouter's `provider.sort` value isn't persisted on the exchange row itself, and there is no retry logic in `OpenRouterLlmClient` to report) |
| Latency dashboard | ✅ filters (time/workload/model/provider/conversation), p50/p75/p95/p99/max/avg/count. Filtering by skill and by success/failure/finish-reason specifically is NOT implemented as its own filter — `errorRate` in the dashboard response covers outcome-type counts, but there's no `outcome=` query filter yet. Stage-level breakdown (context/intent/memory retrieval/prompt assembly/generation/persistence/total) is NOT built — `llm_exchanges` only has LLM-call latency, not the finer sub-stage timings that live in `messages.metadata.stageTimingsMs` (a separate, JSON-blob data source not yet joined in) |
| Routing quality | ❌ Not built. Selection counts, None/null rate, malformed/budget-exhaustion counts by MODEL are answerable today via `errorRate` + `statsByModel`, but per-SKILL selection counts and "labeled accuracy" require the same skill-as-a-column gap noted in Task 3 and Task 4. Not fabricated — left out entirely rather than guessed at |
| Export | ❌ Explicitly deferred to Task 6, per your task ordering |

## Tests

`src/test/kotlin/com/pinkdreams/api/admin/AdminObservabilityRoutesTest.kt`,
11/11 passing, over the real HTTP surface (`testApplication`, not just the
repository):

1. Latency dashboard reflects seeded exchanges and filters correctly by workload
2. Exchange list requires a filter dimension (400, not an unbounded scan)
3. Exchange list respects `conversationId` filter and `limit`
4. Exchange detail lazily includes raw payloads that are absent from the list response
5. Exchange detail never exposes an `Authorization`/`Bearer` value
6. Unknown exchange id returns 404, not a crash
7. Turn trace reconstructs every exchange for one turn, in order, with correct on-path latency
8. Production and Test Chat exchanges are both visible and distinguishable via `isTestChat`
9. A non-admin authenticated principal is rejected (403)
10. An unauthenticated request is rejected (401)
11. A record with missing model/provider (a genuine early-exception case) degrades to an omitted field, not a crash

Full suite: `gradle test --rerun` → same single pre-existing
`PhaseIMG4ImageJobInfrastructureTest` flake; one additional test
(`ConversationProvenanceRoundTripTest`) failed once and passed on
immediate rerun both in isolation and as part of the full suite — a
pre-existing intermittent flake unrelated to this task's changes (verified
by running it alone twice).

## Live evidence

Restarted the server with this code and hit all four endpoints against
the same real Test Chat conversation used for Tasks 2 and 3
(`794980c1-c60f-4727-b5d5-648dca7c4cc0`, now with even more turns):

- **Dashboard**: 45 total exchanges, p50=1861ms, p95=24114ms, p99=54626ms, within-10s rate 91.1%, correctly broken down across 5 workloads, 2 models, and 9 distinct upstream providers (OpenAI, CoreWeave, DeepInfra, Azure, Baidu, StreamLake, Relace, Reka, Alibaba) — this alone is already more granular routing visibility than existed anywhere before this work stream.
- **Exchange list** for the known conversation returned the exact 3 exchanges from that turn with correct fields.
- **Exchange detail** for `b55f3209-...` returned the full raw OpenRouter request (including the entire Intent system prompt) and response, confirmed to contain **no** `Bearer` token or credential anywhere in the payload.
- **Turn trace** for that turn's `turnRequestId` correctly reconstructed all 3 exchanges in order and computed `onPathLatencyMs = 3229` (intent 2010ms + generation 1219ms), correctly excluding the async memory_extraction call's 4408ms from the user-facing figure.

## Honest limitations

- No stage-level (context/intent/memory-retrieval/prompt-assembly/persist) breakdown — only LLM-call-level latency. The finer stage timings exist (`ChatEngine.kt`'s `stageTimingsMs`) but live in a separate JSON blob on `messages`, not joined into this dashboard.
- No routing-quality/skill-selection dashboard — same root cause as Task 3's and Task 4's documented gap (skill key isn't a queryable column anywhere).
- No `outcome`/finish-reason query filter on the dashboard endpoint yet, though the data to support it already exists in `errorRate`.
- UI is plain HTML/JS matching the existing file's own style — no pagination controls beyond a fixed `limit`, no CSV download (Task 6), no auto-refresh.

## Files changed

- `api/admin/AdminObservabilityRoutes.kt` — new
- `api/admin/AdminObservabilityRoutesTest.kt` — new, 11 tests
- `Application.kt` — wired the new route
- `resources/admin-ui.html` — new "Observability" tab (dashboard, exchange list/detail, turn trace)

## Verdict

The core admin question the task defines as "done" —
*"Exactly what happened on this turn, which model/provider ran, what was
sent/returned, why was this skill selected, and where did the time go?"* —
is answerable today for everything except the "why was this skill
selected" half, which depends on the same not-yet-built skill-as-a-column
join flagged consistently since Task 3. Everything else (model/provider,
request/response, timing, SLA context) is live, tested, and verified
against real data.
