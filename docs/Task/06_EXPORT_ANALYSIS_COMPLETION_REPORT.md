# Task 6 — Export/Analysis: Completion Report

## What was built

Two new read-only endpoints under the same `/v1/admin/observability/*`
route tree Task 5 built, reusing the same admin auth gate
(`requirePrincipal(adminAuthorizationProvider)`) and the same
`PerformanceMetricsRepository.MetricsFilter` (workload/model/provider/
conversationId/isTestChat/from/to) as every other observability endpoint
— no new filter vocabulary introduced.

| Endpoint | Format | Purpose |
|---|---|---|
| `GET /v1/admin/observability/export/exchanges.csv` | CSV | Bulk export of exchange **summary** fields, filterable, for spreadsheet analysis |
| `GET /v1/admin/observability/export/performance.json` | JSON | The same aggregate shape as Task 5's `latency-dashboard` endpoint, for programmatic/offline analysis |

## CSV export — what's in it and what's deliberately left out

17 columns: `id, turnRequestId, conversationId, workload, isTestChat,
model, provider, latencyMs, promptTokens, completionTokens,
reasoningTokens, totalTokens, finishReason, httpStatusCode, outcome,
errorClass, createdAt`.

**Raw `requestBody`/`responseBody` are never included in this export.**
Rationale (documented in code, `AdminObservabilityRoutes.exchangesToCsv`):

1. Conversation content is sensitive — Task 2's own security section
   already treats raw exchange payloads as data requiring careful
   handling. A bulk CSV is a much easier thing to mishandle (downloaded,
   emailed, pasted into a third-party spreadsheet tool) than a single
   exchange viewed in the admin UI's lazy-loaded detail panel.
2. CSV is a poor format for large, multi-line JSON payloads regardless of
   sensitivity — it would break row alignment or require heavy escaping
   for no analytical benefit.

**Exchange IDs (`id`, `turnRequestId`) are always preserved** so an
analyst who spots an interesting row (an outlier latency, a
`PROVIDER_ERROR`) can join back to
`GET /v1/admin/observability/exchanges/{id}` for that one row's full raw
request/response — the export is a triage surface, the detail endpoint is
the drill-down.

Values are RFC 4180-escaped (`csvEscape`) — fields containing a comma,
quote, or newline are quoted, with embedded quotes doubled. Default limit
10,000 rows, hard-capped at 50,000 via the same `limit` query param
pattern used elsewhere. Filtered result set with zero rows returns just
the header line (not an empty body), so a spreadsheet tool always sees
valid CSV.

## JSON export

Deliberately returns **the exact same `LatencyDashboardResponse` shape**
as Task 5's `latency-dashboard` endpoint (`overall`, `slaBuckets`,
`errorRate`, `byWorkload`, `byModel`, `byProvider`) rather than a new
response type — an "export" of aggregate stats is not a different
computation, just a different delivery intent (save-to-file vs.
render-in-UI). No raw exchange rows are included in this format; use the
CSV export for row-level data.

## Admin UI

Added an "Export" section to the existing Observability tab
(`admin-ui.html`) below the exchange list/detail panels: two buttons,
"Export exchanges as CSV" and "Export performance stats as JSON", both
reusing whatever workload/model/provider filter is currently set in the
dashboard filter inputs. Each opens the export URL in a new tab
(`window.open`) rather than fetching client-side — the browser's own
Basic-Auth session and native "Save As" handling is exactly what a bulk
download needs, matching this file's existing convention of relying on
the browser's native auth prompt rather than building a custom auth flow.

## Tests

Added to `AdminObservabilityRoutesTest.kt` (now 15/15 passing):

1. CSV export includes a header row, preserves exchange/turn IDs, and
   respects the `workload` filter (only matching rows returned)
2. CSV export never includes raw request or response body content, even
   when a seeded exchange's `requestBody` contains an easily-greppable
   marker string
3. JSON export returns the same top-level shape (`overall`, `slaBuckets`,
   `byWorkload`) as the dashboard endpoint
4. Both export endpoints reject a non-admin authenticated principal (403),
   same as every other observability endpoint

Full suite (`gradle test --rerun`): same single pre-existing
`PhaseIMG4ImageJobInfrastructureTest` flake, unrelated to this task.

## Live evidence

Restarted the server with this code and hit both export endpoints against
the real database (45+ exchanges from the same Test Chat conversation
used in Tasks 2/3/5):

- **CSV**: `GET .../export/exchanges.csv?limit=5` returned 5 correctly
  formatted rows plus header, spanning `memory_engine_maintenance`,
  `memory_extraction`, `continuity_summarization`, and
  `primary_generation` workloads with correct model/provider/latency/
  token columns.
- **Secret check**: `GET .../export/exchanges.csv?limit=200` grepped for
  `bearer`, `api.key`, `sk-or` (case-insensitive) — zero matches.
- **JSON**: `GET .../export/performance.json?workload=primary_generation`
  returned the full `LatencyDashboardResponse` shape, correctly filtered
  to 12 primary_generation exchanges (p50=1775ms, p95=2368ms, 100%
  within-10s, single model/provider breakdown matching the filter).
- **Auth**: non-admin UUID against the CSV endpoint returned 403, matching
  every other observability route's behavior.

## Files changed

- `persistence/repositories/PerformanceMetricsRepository.kt` — added
  `findExchanges(filter, limit)` + a local `rowToExchange` mapper (kept
  separate from `LlmExchangeRepository`'s own mapper to avoid a
  back-reference between the two repositories)
- `api/admin/AdminObservabilityRoutes.kt` — added the two export routes,
  `exchangesToCsv()`, `csvEscape()`
- `api/admin/AdminObservabilityRoutesTest.kt` — 4 new tests
- `resources/admin-ui.html` — Export section in the Observability tab

## Verdict — Tasks 2 through 6 complete

This closes the full sequence: **Task 2** (every LLM exchange captured
uniformly, success and failure, via a decorator with no call-site
changes) → **Task 3** (SLA/percentile aggregation over that raw data,
consistent across H2/PostgreSQL) → **Task 4** (verified which settings
are genuinely admin-editable vs. code-only vs. Test-Chat-only, before
building UI on top of them) → **Task 5** (Admin UI surfacing dashboard,
exchange inspection, and turn tracing) → **Task 6** (bulk export of the
same data for offline analysis).

Per your stated governing principle, no latency-optimization work was
done in this stream — the system now has the observability foundation
(capture → aggregate → visualize → export) to let the *next* optimization
pass be evidence-driven instead of another guess at which component is
slow.

Known gaps carried forward from Tasks 3/4/5 (skill-selection breakdown,
stage-level sub-timings, routing-quality dashboard) are unchanged by this
task — Task 6 exports exactly the data those tasks already expose, no
more.
