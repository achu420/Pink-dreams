# 23 — IMAGE PIPELINE ADMIN COST, SLA & OBSERVABILITY

**Purpose:** Make image-generation operational performance visible to Admin without changing the core generation architecture.

**Depends on:** Tasks 18–22.

---

# 1. Objective

Admin must be able to answer:

```text
How many images are we generating?
How many jobs succeed/fail?
How long do generations take?
Which model/provider is being used?
How much are we spending?
Are we meeting the expected SLA?
Where are failures occurring?
```

This task is about **visibility and measurement**, not model selection.

---

# 2. Product principle

Do not create a second telemetry system if the application already has:

- job events;
- metrics;
- logging;
- audit records;
- generation metadata.

Reuse existing infrastructure.

Add only the missing image-specific aggregation/API/UI required for Admin.

---

# 3. Admin dashboard

Add an Image Pipeline operational view inside the existing Admin area.

Minimum sections:

```text
Generation Overview
SLA
Cost
Models
Failures
Recent Jobs
```

If the current Admin UI architecture uses a different navigation structure, integrate into that structure rather than creating a parallel Admin application.

---

# 4. Generation overview

Display at minimum:

```text
Total jobs
Successful jobs
Failed jobs
Active jobs
Cancelled jobs
Total candidates generated
```

Support a selectable time range where the existing UI pattern permits:

```text
Today
Last 24 hours
Last 7 days
Last 30 days
Custom
```

If custom date filtering is not already supported, implement the smallest consistent filter needed for this feature.

---

# 5. Job status

Show image-generation jobs by status:

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
```

Use the application's actual enum/state names where they already exist.

Do not invent a second status model.

---

# 6. Recent jobs table

Admin should be able to inspect recent image jobs.

Minimum fields:

| Field | Purpose |
|---|---|
| Job ID | Traceability |
| Persona | Source Persona |
| Created | Job timestamp |
| Status | Current state |
| Provider | Provider used |
| Model | Model used |
| Candidates | Number generated |
| Duration | Generation latency |
| Cost | Recorded/estimated cost |
| Error | Redacted failure reason |

Sensitive information must not appear.

---

# 7. Job detail

Clicking a job should expose the existing generation lineage.

Show where available:

```text
Job ID
Persona
Identity version
Reference set
Seed prompt
Provider
Model
Engine
Skill
Configuration version
Created timestamp
Started timestamp
Completed timestamp
Duration
Candidate count
Cost
Status
Failure reason
```

Do not expose API keys or credentials.

---

# 8. SLA definition

The system must use one explicit definition for image-generation SLA.

Document the configured target.

Example:

```text
Generation SLA target = X seconds
```

Do not invent a target if the product has not yet defined one.

If no target exists, Admin should show:

```text
SLA target: Not configured
```

rather than silently choosing one.

---

# 9. SLA measurements

Track:

```text
Average generation duration
Median generation duration
P95 generation duration
P99 generation duration
SLA success percentage
```

If the existing metrics framework does not support all percentiles, implement only what is technically supported and document the gap.

---

# 10. SLA by model

Where enough data exists, show:

```text
Model
Jobs
Success rate
Median latency
P95 latency
Failure count
```

This allows later product evaluation without embedding a model ranking into the system.

Do not display a "best model" verdict automatically.

---

# 11. Cost tracking

For each generation job record, where provider pricing information is available:

```text
Provider
Model
Input usage
Output usage
Estimated cost
Currency
```

If exact provider billing data is unavailable, explicitly label the value:

```text
Estimated
```

Never display an assumed value as actual spend.

---

# 12. Cost aggregation

Admin should be able to see:

```text
Total recorded/estimated cost
Cost per successful job
Cost per candidate
Cost by model
Cost by Persona
Cost by time period
```

Only show dimensions supported by persisted data.

---

# 13. Cost calculation

Do not hard-code pricing into generation logic.

Prefer:

```text
Provider/model usage
        ↓
Pricing configuration
        ↓
Cost calculation
        ↓
Persisted/derived operational metric
```

Pricing changes must not rewrite historical actual usage.

If pricing configuration is changed, historical cost semantics must remain traceable.

---

# 14. Cost status

Every cost value should be distinguishable as:

```text
ACTUAL
ESTIMATED
UNAVAILABLE
```

Do not represent unavailable cost as zero.

---

# 15. Model usage

Show usage grouped by:

```text
Provider
Model
```

Minimum:

```text
Jobs
Successful jobs
Failed jobs
Candidates
Latency
Cost
```

This becomes the operational foundation for the later model-comparison exercise.

---

# 16. Failure observability

Admin should see failure categories where the backend can reliably classify them.

Examples:

```text
Provider error
Authentication/configuration error
Timeout
Invalid response
Storage failure
Validation failure
Lease/worker failure
Unknown
```

Use existing error categories if they already exist.

Do not create misleading classifications from arbitrary exception text.

---

# 17. Failure details

For an individual failed job, show:

```text
Status
Failure category
Redacted diagnostic
Timestamp
Provider/model
Job ID
```

Never expose:

```text
API keys
Authorization headers
Passwords
Session tokens
Secrets
```

---

# 18. Worker health

Where existing worker telemetry supports it, Admin should see:

```text
Worker identity
Current/last activity
Active jobs
Last heartbeat
Lease configuration
```

If this information is not currently available, document it as a gap rather than fabricating health data.

---

# 19. Storage health

Reuse the existing image storage diagnostics.

Expose or link to:

```text
Storage mode
Shared-storage contract
Read probe
Write probe
Storage readiness
```

Do not expose absolute server filesystem paths.

---

# 20. Queue health

Where supported, expose:

```text
Queued jobs
Running jobs
Oldest queued job age
```

This helps distinguish:

```text
slow provider
```

from:

```text
worker/queue problem
```

If queue-age measurement is not available, document the limitation.

---

# 21. Operational alerts

Do not build a new alerting platform.

If existing alerting infrastructure exists, image pipeline metrics should be usable by it.

Potential alert conditions:

```text
High failure rate
SLA degradation
Queue backlog
Storage unavailable
Worker heartbeat problems
Unexpected cost increase
```

Implement only the alert mechanisms already supported by the application architecture.

---

# 22. Recent-generation drill-down

Admin should be able to go from:

```text
Dashboard
 ↓
Job
 ↓
Candidate
 ↓
Image Warehouse
```

without losing the generation lineage.

Candidate detail should retain:

```text
Provider
Model
Engine
Skill
Identity version
Seed
Generation job
Parent candidate if regenerated
```

---

# 23. Time handling

All persisted timestamps should remain unambiguous.

Use the application's existing timestamp convention.

Admin should display timestamps in the application's normal Admin timezone/display convention.

Do not change database timestamp semantics for this task.

---

# 24. Data retention

Operational metrics must respect existing retention policy.

Do not introduce a second deletion policy that accidentally removes image-generation audit information required for the Warehouse or job lineage.

---

# 25. Performance

The dashboard must not execute expensive full-table scans on every Admin page load if the current database/data volume makes that unsafe.

Prefer:

```text
indexed queries
aggregated queries
existing metrics
cached summaries
```

where appropriate.

Do not prematurely build a separate analytics warehouse.

---

# 26. API design

Reuse existing Admin API conventions.

Possible conceptual endpoints:

```text
GET /v1/admin/images/metrics
GET /v1/admin/images/jobs
GET /v1/admin/images/jobs/{jobId}
GET /v1/admin/images/cost
GET /v1/admin/images/sla
```

**Do not blindly create these exact endpoints if equivalent routes already exist.**

Inspect the existing Admin route architecture first.

---

# 27. Authorization

All operational endpoints must use the existing Admin authorization mechanism.

Verify:

```text
Admin → allowed
Normal user → denied
Unauthenticated → denied
```

Do not introduce Basic Auth merely for this task if the application already has Admin session authorization.

---

# 28. Empty-state behavior

If there are no generations:

```text
No image generation data available.
```

Do not show:

```text
0% SLA
$0 actual spend
```

unless those values have a meaningful semantic definition.

---

# 29. Missing-cost behavior

If provider/model usage cannot be converted to cost:

```text
Cost: Unavailable
```

not:

```text
Cost: $0
```

---

# 30. Model comparison support

The dashboard must support collecting data for:

```text
Model A
Model B
Model C
```

but must not automatically declare:

```text
best model
winner
recommended model
```

Product evaluation will use the observed data plus actual image-quality review.

---

# 31. Test — dashboard totals

Create known test jobs.

Verify:

```text
total jobs
success count
failure count
candidate count
```

match the underlying records.

---

# 32. Test — latency

Create completed jobs with known timestamps.

Verify Admin calculates:

```text
duration = completed - started
```

according to the application's existing timestamp semantics.

---

# 33. Test — cost

Use a controlled test with known usage/pricing.

Verify:

```text
recorded usage
pricing
calculated cost
```

are traceable.

If live provider billing data is unavailable, use an explicitly marked test/estimated cost path.

---

# 34. Test — model grouping

Generate using two configured models where possible.

Verify each job appears under the correct model.

Verify changing the active model does not move historical jobs to the new model.

---

# 35. Test — failure grouping

Create controlled failures.

Verify:

```text
failed job
failure category
redacted diagnostic
```

appear correctly.

---

# 36. Test — authorization

Verify:

```text
Admin → 200/allowed
Normal user → 401/403 according to existing convention
Unauthenticated → 401/403 according to existing convention
```

Do not leak metrics through unauthorized endpoints.

---

# 37. Test — no secrets

Inspect:

```text
API responses
logs
browser network responses
database records
```

Verify no:

```text
OPENROUTER_API_KEY
DATABASE_PASSWORD
Authorization header
session token
```

appears in operational data.

---

# 38. Test — storage diagnostics

Confirm dashboard/storage section uses the existing storage diagnostics.

Verify no absolute filesystem path is exposed.

---

# 39. Test — restart

Restart application.

Verify:

```text
historical metrics/jobs remain available
```

where persistence is expected.

---

# 40. Test — large time range

Run the dashboard against a realistic test dataset.

Verify:

- acceptable response time;
- no timeout;
- no unbounded memory usage;
- correct aggregation.

---

# 41. Acceptance criteria

### AC-01
Admin can view image-generation operational metrics.

### AC-02
Admin can inspect recent image jobs.

### AC-03
Admin can inspect job-level generation lineage.

### AC-04
Success/failure counts match persisted jobs.

### AC-05
Latency is measurable.

### AC-06
SLA target is explicit or shown as not configured.

### AC-07
Cost is clearly marked actual, estimated, or unavailable.

### AC-08
Cost never defaults silently to zero.

### AC-09
Provider/model usage is traceable.

### AC-10
Failure categories are observable where supported.

### AC-11
Existing storage diagnostics are visible or reachable.

### AC-12
Admin authorization protects operational data.

### AC-13
Secrets never appear in operational UI/API/logs.

### AC-14
Historical job/model metadata is immutable.

### AC-15
Dashboard supports later model comparison without automatically ranking models.

---

# 42. Definition of Done

- [ ] Admin image metrics view implemented or verified if already present.
- [ ] Recent jobs view implemented or verified.
- [ ] Job detail lineage implemented or verified.
- [ ] SLA metrics implemented or verified.
- [ ] Cost metrics implemented or verified.
- [ ] Provider/model grouping implemented or verified.
- [ ] Failure visibility implemented or verified.
- [ ] Storage diagnostics integrated.
- [ ] Authorization tested.
- [ ] Secret redaction tested.
- [ ] Empty states tested.
- [ ] Missing-cost behavior tested.
- [ ] Historical integrity tested.
- [ ] Dashboard performance tested.
- [ ] Existing image tests remain green.
- [ ] New Admin observability tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` updated.

---

# 43. Cursor implementation instructions

Before coding:

1. Read Tasks 18–22.
2. Read `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`.
3. Inspect existing Admin dashboard/navigation.
4. Inspect existing image job metadata.
5. Inspect existing metrics/events.
6. Inspect existing cost/usage data.
7. Inspect existing SLA/latency telemetry.
8. Inspect existing storage diagnostics.
9. Inspect existing authorization.
10. Reuse existing abstractions.

Do not create duplicate:

```text
metrics framework
audit framework
authorization system
provider abstraction
storage diagnostics
job state model
```

unless the repository genuinely has no equivalent.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Task 23:
Admin metrics:
Jobs:
SLA:
Cost:
Model usage:
Failures:
Storage:
Authorization:
Tests:
Known gaps:
Files changed:
Git commit:
```

---

# 44. Required completion report

Cursor must return:

```text
TASK 23 — COST / SLA / OBSERVABILITY

Status:

Admin dashboard:
Implemented / Existing / Partial

Generation metrics:
Result:

Job detail:
Result:

SLA:
Target:
Metrics:
Result:

Cost:
Actual/Estimated/Unavailable:
Result:

Provider/model metrics:
Result:

Failure observability:
Result:

Storage diagnostics:
Result:

Authorization:
Result:

Security:
Result:

Tests:
Passed:
Failed:
Skipped:

Known gaps:

Files changed:

Commit:
```

---

# 45. Product interpretation

This task should make the pipeline measurable.

It should allow the product team to see:

```text
WHAT WE GENERATED
HOW LONG IT TOOK
HOW OFTEN IT FAILED
WHAT MODEL GENERATED IT
WHAT IT COST
```

It must **not** decide:

```text
which model is best
which Persona is best
which image should be published
```

Those remain product evaluation decisions.

**Critical rule:** Operational observability must describe the system accurately. Never convert missing telemetry into a zero, estimated cost into actual spend, or untested behavior into a PASS.
