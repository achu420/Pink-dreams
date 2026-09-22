# 16 — IMAGE COST, SLA & OBSERVABILITY ADMIN

**Goal:** Give Admin a truthful operational view of image-generation health, latency, cost, failures, workers, and storage without inventing metrics.

**Depends on:** Image Pipeline Governance, Implementation Status, Tasks 01–15.

---

## 1. Product objective

Admin must be able to answer:

- How many image generations happened?
- How many succeeded/failed?
- How long are they taking?
- Which provider/model is being used?
- What is the image-generation cost?
- Which Persona/model is consuming generation volume?
- Are workers healthy?
- Are jobs stuck?
- Is storage healthy?
- Can I trace a metric back to actual jobs/candidates?

This is an operational dashboard, not an analytics/recommendation system.

---

## 2. Existing backend must be reused

Before implementing anything, inspect and reuse:

- `image_generation_events`
- existing image job state
- existing SLA calculations
- existing provider/model metadata
- existing cost fields
- existing storage diagnostics
- existing Admin image routes

Do not create a second telemetry system if the existing observability architecture can be extended.

Do not duplicate SLA or cost calculations in the frontend.

---

## 3. Admin dashboard

Add an Image Operations / Image SLA area to the existing Admin UI.

Suggested sections:

```text
IMAGE OPERATIONS

[Total Generations] [Success Rate] [Failure Rate] [Avg Latency]
[Total Cost]        [Active Jobs]  [Stuck Jobs]   [Storage]

--------------------------------------------------

Generation volume
Success / failure
Latency / SLA
Cost
Provider / model
Worker health
Storage health
Recent failures
```

Use the project's existing Admin UI patterns.

---

## 4. Time filters

Minimum:

```text
Today
Last 7 days
Last 30 days
Custom range
```

All metrics must use an explicit time window.

Display the selected period.

Do not mix historical samples with current-period values without clearly labeling them.

---

## 5. Core metrics

At minimum expose:

```text
Total image jobs
Succeeded jobs
Failed jobs
Running jobs
Queued jobs
Retrying jobs
Success rate
Failure rate
Average generation latency
Average total job latency
```

Where useful:

```text
P50
P95
P99
```

Only display percentiles if the backend has enough real observations to calculate them.

---

## 6. Cost metrics

Expose:

```text
Total image spend
Average cost/job
Average cost/candidate
Cost by model
Cost by provider
Cost by Persona
```

Only show cost where the provider supplies usable cost information.

Important:

```text
unknown cost != $0
```

Never fabricate zero cost.

If cost is unavailable:

```text
Cost: unavailable
```

---

## 7. Model/provider breakdown

Admin must be able to identify:

```text
Provider
Model
Number of generations
Success rate
Failure rate
Latency
Cost where available
```

This is needed for the upcoming model evaluation.

Do not automatically label one model as "best".

The dashboard provides measurements; product decisions are made separately.

---

## 8. Persona breakdown

Where Persona information is available:

```text
Persona
Generation count
Success count
Failure count
Average latency
Cost
```

This allows Admin to detect unusually high generation activity.

Do not expose private Persona information unnecessarily.

---

## 9. SLA

Use the existing image SLA definition.

At minimum show:

```text
Average latency
P50/P95 where supported
SLA target
Within SLA
Outside SLA
```

Example:

```text
Image SLA

Target: 30s

Within SLA: 92%
Outside SLA: 8%
P95: 41s
```

The values must come from actual backend observations.

No fake dashboard values.

---

## 10. Queue/worker health

Show operational state:

```text
Queued jobs
Running jobs
Retrying jobs
Stale jobs
Worker count where known
```

If worker identity is available:

```text
Worker
Current jobs
Last activity
```

Do not claim a worker is healthy merely because its process exists.

Use available heartbeat/lease information.

---

## 11. Stuck jobs

Admin must be able to identify jobs that appear stuck according to the existing lease/state rules.

For a stuck job show:

```text
Job ID
Persona
Created
Started
Worker
Lease information
Provider
Model
Last error
Current state
```

Do not automatically delete or retry jobs from the dashboard unless existing Admin controls explicitly support that operation.

---

## 12. Recent failures

Show recent image failures.

Minimum:

```text
Job ID
Time
Persona
Provider
Model
Failure classification
Redacted error
Retry count
```

Errors must be safe for Admin display.

Never display:

- API keys
- authorization headers
- secrets
- raw credentials

---

## 13. Storage health

Reuse the existing storage diagnostics.

Show:

```text
Storage mode
Shared-storage contract
Read readiness
Write readiness
Storage probe result
```

For multi-instance deployments also expose whether the operator has declared the storage shared, using the existing configuration.

Do not expose absolute filesystem paths unless the existing security contract explicitly allows it.

---

## 14. Storage usage

If actual usage data is available, show:

```text
Object count
Bytes used
Maximum configured bytes
```

If actual usage cannot be calculated reliably:

```text
Not available
```

Do not estimate storage usage and present it as real.

---

## 15. Generation detail drill-down

Every summary metric should be traceable to actual image jobs where possible.

Example:

```text
Failure rate
   ↓
failed jobs
   ↓
job detail
   ↓
candidate(s)
   ↓
provider/model
   ↓
error
```

Admin should not have to guess where a metric came from.

---

## 16. Candidate-level metadata

From an image candidate, Admin should be able to identify:

```text
Generation ID
Candidate ID
Persona
Provider
Model
Identity version
Reference set
Created time
Generation latency
Cost where available
Status
```

Reuse the Image Warehouse data from Task 15.

---

## 17. Date/time handling

Use one consistent backend time representation.

Display Admin-friendly local time according to existing application conventions.

Do not calculate duration from displayed UI strings.

Durations must come from backend timestamps or backend-calculated values.

---

## 18. Empty states

Correct empty states:

```text
No image generations in this period.
```

```text
No cost data available for this period.
```

```text
No failures recorded.
```

Do not show:

```text
0
```

when the real meaning is "data unavailable."

---

## 19. API requirements

Use existing Admin image routes where possible.

The backend should provide the data required by the dashboard.

Possible capabilities:

```text
GET image SLA summary
GET image generation metrics
GET image cost summary
GET provider/model breakdown
GET recent failures
GET worker/job health
GET storage diagnostics
```

Follow current project route conventions instead of blindly creating these exact paths.

---

## 20. Authorization

All operational dashboards are Admin-only.

Verify:

- unauthenticated request denied
- normal user denied
- authorized Admin allowed

Do not expose aggregate operational data through public image APIs.

---

## 21. Performance

Dashboard queries must not load every candidate image.

Use aggregate database queries where possible.

Never retrieve image binary data for an SLA/cost dashboard.

Pagination should be used for detailed job/failure lists.

---

## 22. Observability event integrity

Verify image events contain enough information to support the dashboard.

Useful fields include:

```text
job ID
event type
timestamp
provider
model
Persona where appropriate
status
latency
failure classification
cost where available
```

Avoid storing sensitive prompt/reference data unnecessarily in telemetry.

---

## 23. No fake metrics rule

This is mandatory.

The UI must never invent:

```text
cost
latency
success rate
storage usage
worker health
SLA
```

If data is missing, display:

```text
Unavailable
```

or:

```text
Not enough data
```

as appropriate.

---

## 24. Acceptance test — generation metrics

1. Generate successful image jobs.
2. Generate a controlled failed job.
3. Open Admin Image Operations.

Verify:

- total count changes correctly
- success count changes correctly
- failure count changes correctly
- success rate is calculated correctly

---

## 25. Acceptance test — latency

Generate jobs with known timestamps.

Verify:

```text
queue latency
generation latency
total latency
```

are calculated from backend timestamps.

No client-clock calculation.

---

## 26. Acceptance test — cost

If live provider returns cost:

1. Generate an image.
2. Inspect job/candidate.
3. Inspect dashboard.

Verify cost is consistent.

If provider cost is unavailable:

```text
Cost = unavailable
```

not `$0`.

---

## 27. Acceptance test — provider/model

Generate using configured provider/model.

Verify dashboard attributes the generation to the correct:

```text
provider
model
```

No hardcoded model names.

---

## 28. Acceptance test — failures

Cause a controlled provider/storage failure.

Verify:

- failure is recorded
- classification is visible
- retry information is visible
- secret data is absent
- failure count increases

---

## 29. Acceptance test — storage

Run the existing storage readiness probe.

Verify Admin dashboard agrees with backend storage diagnostics.

Do not duplicate or contradict `GET /v1/admin/images/storage`.

---

## 30. Acceptance test — worker

With a running image worker verify:

```text
queued → running → terminal
```

is observable.

With a stale job verify the dashboard identifies it according to existing stale-job rules.

---

## 31. Acceptance test — permissions

Attempt dashboard access as:

```text
unauthenticated
normal user
Admin
```

Expected:

```text
Admin → allowed
others → denied
```

according to existing authorization conventions.

---

## 32. Acceptance test — no images loaded

Open the dashboard while inspecting network/backend behavior.

Verify aggregate dashboard queries do not download full image assets.

---

## 33. Definition of Done

- [ ] Image operations dashboard exists.
- [ ] Date filtering works.
- [ ] Generation volume works.
- [ ] Success/failure metrics work.
- [ ] Latency/SLA works.
- [ ] Cost works where provider data exists.
- [ ] Provider/model breakdown works.
- [ ] Persona breakdown works where supported.
- [ ] Worker/job health works.
- [ ] Recent failures work.
- [ ] Storage diagnostics are represented correctly.
- [ ] No fake metrics.
- [ ] Admin authorization is enforced.
- [ ] Dashboard does not download unnecessary image binaries.
- [ ] Backend aggregate queries are tested.
- [ ] Existing image tests remain green.
- [ ] New Admin/API tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

## 34. Cursor implementation instructions

Before coding:

1. Inspect existing image observability.
2. Inspect `image_generation_events`.
3. Inspect current SLA endpoint.
4. Inspect storage diagnostics.
5. Inspect Admin image UI.
6. Inspect provider/model metadata.
7. Inspect existing cost support.
8. Reuse existing aggregation patterns.
9. Do not create a second telemetry database.
10. Do not add fake/demo metrics.

After coding update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Observability:
Metrics:
SLA:
Cost:
Provider/model:
Persona:
Worker:
Storage:
Admin UI:
Security:
Tests:
Known gaps:
Git commit:
```

---

## 35. Completion report

Cursor must report:

```text
TASK 16 — IMAGE COST/SLA/OBSERVABILITY

Status:
Backend:
Admin UI:
Metrics:
SLA:
Cost:
Provider/model:
Worker:
Storage:
Security:

Tests:
Passed:
Failed:
Skipped:

Known gaps:

Files changed:

Commit:
```

No metric may be reported as verified unless it was actually tested.
