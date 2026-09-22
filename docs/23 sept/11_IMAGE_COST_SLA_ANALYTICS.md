# 11 — IMAGE COST, SLA & GENERATION ANALYTICS

**Depends on:**
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- `Image Model & Provider Configuration.md`
- `IMAGE_GENERATION & CANDIDATE CREATION.md`
- `IMAGE WAREHOUSE & CANDIDATE LIFECYCLE.md`
- `10_IMAGE_REVIEW_SHORTLIST_ADMIN_WORKFLOW.md`

**Goal:** Make image-generation cost, latency/SLA, volume, failure and provider/model usage measurable in the Admin UI using real persisted/runtime data. No fake metrics and no invented costs.

---

## 1. Purpose

The Admin needs to answer:

1. How many image generations happened?
2. How many succeeded/failed?
3. How long are image generations taking?
4. Which provider/model is being used?
5. How many candidates are produced?
6. How many generations are regenerations?
7. What is the recorded image-generation cost?
8. What is the cost by provider/model/persona/time period when supported?
9. Where are failures happening?
10. Is the image pipeline meeting the operational SLA?

This task is **observability and reporting only**.

Do not build billing infrastructure.

---

# 2. Source of Truth

Before implementing anything:

1. Inspect existing `image_generation_events`.
2. Inspect existing image job records.
3. Inspect provider response metadata.
4. Inspect existing SLA implementation.
5. Inspect existing admin image APIs.
6. Reuse existing observability patterns.

Do not create a second telemetry system if the current implementation can support these metrics.

Do not manufacture missing provider costs.

---

# 3. Required Metrics

## Generation volume

At minimum:

```text
generation_requests
successful_generations
failed_generations
cancelled_generations
regenerations
```

Define each metric clearly.

A regeneration is a generation request with candidate lineage indicating that it originated from an earlier candidate.

---

# 4. Candidate Metrics

Track where available:

```text
candidates_requested
candidates_generated
candidates_saved
candidates_shortlisted
candidates_declined
```

The system must distinguish:

```text
generation/job
```

from:

```text
candidate/image asset
```

Do not count four candidates as four independent generation requests unless the provider actually made four independent requests.

---

# 5. Latency / SLA Metrics

Measure at least:

```text
queue latency
provider latency
total generation latency
```

Definitions must be explicit.

### Queue latency

```text
worker start - job creation
```

### Provider latency

```text
provider completion - provider request start
```

### Total generation latency

```text
terminal completion - job creation
```

If one of these timestamps is unavailable, report the metric as unavailable rather than estimating it.

---

# 6. Percentiles

Where the existing observability system supports aggregation, provide:

```text
p50
p95
p99
```

for total generation latency.

Optional:

```text
queue p50/p95/p99
provider p50/p95/p99
```

Do not calculate misleading percentiles from an insufficient sample without labeling the sample.

---

# 7. Sample Size

Every SLA view must show sample count.

Example:

```text
Total generations: 42
Successful: 38
Failed: 4

Total latency
p50: 31.2s
p95: 58.4s
p99: 71.1s

Sample: 42
```

A percentile without sample size is incomplete.

---

# 8. Cost Tracking

Cost must come from actual provider/model information when available.

Possible sources:

- Provider-reported cost
- Existing OpenRouter usage/cost metadata
- Configured model pricing, only if explicitly maintained as a trusted configuration
- Existing persisted cost field

Priority:

```text
actual provider-reported cost
        >
trusted configured cost calculation
        >
unknown
```

Never invent a price.

If cost cannot be determined:

```text
cost = UNKNOWN
```

not:

```text
cost = 0
```

---

# 9. Cost Metrics

Where data exists, provide:

```text
total recorded cost
average recorded cost / generation
average recorded cost / candidate
cost by provider
cost by model
cost by persona
cost by date
regeneration cost
```

Clearly label metrics as **recorded cost** if the provider does not provide complete billing data.

---

# 10. Cost Coverage

Show:

```text
cost coverage
```

Example:

```text
42 generations
37 with recorded cost
5 without cost

Cost coverage: 88.1%
```

This prevents the Admin from interpreting incomplete cost data as complete spend.

---

# 11. Provider / Model Breakdown

Admin should be able to see:

| Provider | Model | Requests | Success | Failure | Avg latency | Recorded cost |
|---|---|---:|---:|---:|---:|---:|
| OpenRouter | Model A | ... | ... | ... | ... | ... |
| OpenRouter | Model B | ... | ... | ... | ... | ... |

Only show providers/models actually recorded in the system.

Do not hardcode a preferred model.

---

# 12. Persona Breakdown

Provide image-generation usage by persona where persona ID is available.

Example:

```text
Persona
Generations
Successful
Failed
Regenerations
Candidates
Recorded cost
Avg latency
```

Do not expose private persona data beyond what Admin already has access to.

---

# 13. Time Filters

Admin should be able to filter by:

```text
Today
Last 7 days
Last 30 days
Custom range
```

If the current Admin UI has an established filter component, reuse it.

Do not add multiple incompatible date/time implementations.

---

# 14. SLA Definition

The image pipeline needs an explicit operational SLA target.

The implementation must first inspect whether a target already exists.

If a target is already configured:

- Reuse it.

If no target exists:

- Add a configuration value with a documented default only after confirming it does not conflict with existing architecture.
- Do not silently invent a business SLA.

Example configuration shape:

```text
IMAGE_SLA_SECONDS
```

The exact name should follow existing configuration conventions.

---

# 15. SLA Compliance

Show:

```text
SLA target
Completed within SLA
Completed outside SLA
SLA compliance %
```

Example:

```text
SLA target: 60s

Completed: 38
Within SLA: 34
Outside SLA: 4

Compliance: 89.5%
```

Do not call the system healthy/unhealthy based solely on a model opinion.

Show the measured data.

---

# 16. Failure Analytics

Admin should see failure categories when classification exists.

Examples:

```text
VALIDATION
PROVIDER_4XX
PROVIDER_5XX
TIMEOUT
RATE_LIMIT
STORAGE
WORKER
UNKNOWN
```

Use the actual existing failure taxonomy if one exists.

Do not create a second incompatible taxonomy.

Show:

```text
failure count
failure percentage
last error category
```

Do not expose secrets or raw authorization headers.

---

# 17. Retry Analytics

Track where available:

```text
initial attempts
retry attempts
jobs eventually succeeded after retry
jobs failed after retries
```

This is important because:

```text
provider request failure
```

is different from:

```text
final generation failure
```

---

# 18. Admin Dashboard

Add an Image Analytics section to the existing Admin image area.

Minimum dashboard:

```text
IMAGE GENERATION

Requests       Successful       Failed
   42              38              4

REGENERATIONS

Requests       Successful
   8                7

LATENCY

p50     p95     p99
31s     58s     71s

SLA

Target     Compliance
60s        89.5%

COST

Recorded spend
$X.XX

Coverage
88.1%
```

Use the existing Admin UI design system.

Do not create fake placeholder values.

---

# 19. Analytics API

Reuse existing admin image APIs where practical.

Expected capability:

```text
GET /v1/admin/images/analytics
```

or equivalent existing route.

The response should contain structured data, for example:

```json
{
  "range": {
    "from": "...",
    "to": "..."
  },
  "requests": 42,
  "successful": 38,
  "failed": 4,
  "regenerations": 8,
  "latency": {
    "sampleCount": 38,
    "p50Seconds": 31.2,
    "p95Seconds": 58.4,
    "p99Seconds": 71.1
  },
  "sla": {
    "targetSeconds": 60,
    "within": 34,
    "outside": 4,
    "compliancePercent": 89.5
  },
  "cost": {
    "recorded": 12.34,
    "coveragePercent": 88.1
  }
}
```

Exact schema should follow existing API conventions.

---

# 20. Date / Time Semantics

Document the timezone used by the analytics API.

Prefer:

- UTC storage
- explicit timezone conversion for Admin display

Do not silently mix server-local time and browser-local time.

Boundary behavior for:

```text
from
to
```

must be deterministic.

---

# 21. Performance

Analytics must not scan unbounded raw image data on every Admin request if the existing architecture already supports aggregation.

Start with the simplest safe implementation.

If current volume is small:

- Querying existing event/job tables may be acceptable.

If volume is already large:

- Reuse indexed fields.
- Add appropriate indexes.
- Consider aggregation only when justified.

Do not introduce a complex analytics warehouse for MVP.

---

# 22. Security

Analytics endpoints are Admin-only unless an existing architecture explicitly supports another audience.

Verify:

- unauthenticated → rejected
- normal user → rejected
- Admin → allowed
- arbitrary persona ID/filter → properly authorized
- secrets absent from responses

Do not return raw provider credentials or internal authorization data.

---

# 23. Data Integrity

Analytics must not double-count because of:

- retries
- worker lease reclaim
- duplicate delivery
- regenerated candidates
- repeated Admin refreshes

Define the counting unit.

Recommended:

```text
generation request = one image job
candidate = one generated candidate
attempt = one provider attempt
```

These must remain separate.

---

# 24. Observability Failure

Analytics/telemetry must not block image generation.

If an observability write fails:

```text
generation continues
```

and the failure is logged through the existing safe logging path.

Analytics may then show incomplete coverage.

Do not convert missing telemetry into zero.

---

# 25. Admin UI Acceptance Tests

## Test 1 — Empty system

With no image generations:

Expected:

```text
Requests: 0
Cost: UNKNOWN / 0 recorded
Latency: no sample
SLA: no sample
```

Do not display fake values.

---

## Test 2 — Successful generation

Generate one image job.

Verify:

- request count increments
- success increments
- latency becomes available
- provider/model appears
- candidate count appears

---

## Test 3 — Failed generation

Force a Fake provider failure.

Verify:

- request count increments
- failure increments
- success does not increment
- failure category appears
- error does not expose secrets

---

## Test 4 — Retry

Force a retryable provider failure followed by success.

Expected:

```text
Requests: 1
Final result: success
Attempts: >1
```

Do not count the retry as another user generation request.

---

## Test 5 — Regeneration

Regenerate an existing candidate.

Expected:

```text
generation requests +1
regenerations +1
```

Original generation remains unchanged.

---

## Test 6 — Cost coverage

Create:

- one generation with cost
- one generation without cost

Expected:

```text
2 requests
1 cost-covered request
50% cost coverage
```

Do not report unknown cost as zero.

---

## Test 7 — Date filter

Create records across two date ranges.

Verify:

- Today
- Last 7 days
- Custom range

return deterministic counts.

---

## Test 8 — Admin security

Verify:

```text
anonymous → 401/403
normal user → 401/403
admin → success
```

---

## Test 9 — Refresh

Load analytics repeatedly.

Expected metrics remain stable.

No duplicate event insertion.

---

# 26. Definition of Done

This task is complete only when:

- [ ] Generation volume is measurable.
- [ ] Success/failure counts are measurable.
- [ ] Regeneration count is measurable.
- [ ] Candidate counts are distinguishable from generation counts.
- [ ] Queue/provider/total latency are correctly defined where timestamps exist.
- [ ] p50/p95/p99 are implemented where supported.
- [ ] Sample counts are displayed.
- [ ] Recorded cost is measurable where available.
- [ ] Cost coverage is visible.
- [ ] Unknown cost is not treated as zero.
- [ ] Provider/model breakdown works.
- [ ] Persona breakdown works where authorized/data exists.
- [ ] Failure categories work using the existing taxonomy.
- [ ] Retry behavior does not double-count requests.
- [ ] SLA target is sourced from existing configuration or explicitly documented.
- [ ] SLA compliance is measurable.
- [ ] Admin analytics UI works.
- [ ] Admin analytics API works.
- [ ] Security is enforced server-side.
- [ ] Existing image observability remains non-blocking.
- [ ] Existing image pipeline tests remain green.
- [ ] New unit/integration/security tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 27. Cursor Implementation Rule

Before coding:

1. Inspect existing image events.
2. Inspect existing SLA implementation.
3. Inspect existing cost/provider metadata.
4. Inspect Admin image routes/UI.
5. Reuse existing aggregation/query patterns.
6. Do not create duplicate telemetry.
7. Do not invent provider costs.
8. Do not invent a business SLA without documenting the source.
9. Do not change generation behavior merely to create analytics.

After coding, update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Implemented:
Reused:
Not implemented:
Files changed:
DB/index changes:
API changes:
Admin UI changes:
Metrics:
Cost source:
SLA source:
Tests:
Known gaps:
Git commit:
```

The task is not complete until the Admin UI can show real values generated by the tested image pipeline.
