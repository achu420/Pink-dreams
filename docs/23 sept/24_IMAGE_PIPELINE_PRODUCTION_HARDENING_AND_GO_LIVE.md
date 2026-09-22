# 24 — IMAGE PIPELINE PRODUCTION HARDENING & GO-LIVE

**Purpose:** Perform the final operational hardening and go-live verification of the Image Pipeline after implementation and end-to-end QA.

**Depends on:** Tasks 18–23.

**Important:** This task is primarily verification and controlled fixes. Do not redesign working architecture merely to satisfy a checklist.

---

# 1. Objective

Establish whether the Image Pipeline is operationally ready for real product testing.

Verify:

```text
Application
 ↓
Database
 ↓
Worker
 ↓
Shared storage
 ↓
Provider / Model
 ↓
Generation
 ↓
Candidates
 ↓
Warehouse
 ↓
Admin
 ↓
Cost / SLA
```

The final report must distinguish:

```text
VERIFIED
NOT VERIFIED
BLOCKED
FAILED
```

---

# 2. Go-live gates

The following gates must be evaluated independently:

| Gate | Result |
|---|---|
| Application startup | |
| Database connectivity | |
| Image worker | |
| Shared storage | |
| OpenRouter/provider | |
| Image model | |
| End-to-end generation | |
| Chat attachment | |
| Admin workflow | |
| Two-process | |
| Lease heartbeat | |
| Restart/recovery | |
| Security | |
| Cost | |
| SLA/observability | |

Do not use one overall PASS to hide an unverified gate.

---

# 3. Environment declaration

Record:

```text
Branch:
Commit:
Application version:
Database:
Database URL host only:
Provider:
Model:
Engine:
Skill:
Storage mode:
Storage directory label:
Worker lease:
Heartbeat:
Process count:
Ports:
```

Never record:

```text
API keys
database passwords
session tokens
authorization headers
```

---

# 4. Secrets

Required production/live secrets must be supplied through environment variables or the approved secret-management mechanism.

At minimum verify the application can obtain:

```text
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
OPENROUTER_API_KEY
```

Use the repository's actual required variable names.

Do not create a new secrets file containing live credentials.

Do not commit credentials.

---

# 5. Credential validation

Validate credentials by executing the real application path.

Expected:

```text
Database → connects successfully
Provider → authenticated successfully
```

Do not treat:

```text
environment variable exists
```

as proof that the credential works.

If credentials are unavailable:

```text
BLOCKED
```

---

# 6. Real Postgres gate

Run the application against the intended PostgreSQL environment.

Verify:

```text
startup
migrations/schema
job creation
job updates
candidate persistence
metadata persistence
message attachment
retention-related reads
```

Inspect representative rows where safe.

Do not expose passwords or connection strings containing credentials.

---

# 7. OpenRouter live gate

Execute a real image-generation request through the configured OpenRouter path.

Record:

```text
provider
model
job ID
start
completion
candidate count
latency
success/failure
```

Do not fabricate a live result from Fake-provider tests.

If the provider request fails:

```text
FAIL
```

with the redacted failure reason.

If the key is unavailable:

```text
BLOCKED
```

---

# 8. Real image model gate

Confirm the actual model identifier used by the successful live request.

Verify it matches the configured model.

Expected:

```text
configured model
=
request model
=
candidate metadata model
```

If these differ, investigate before proceeding.

---

# 9. End-to-end live generation

Execute:

```text
Admin
 ↓
Persona
 ↓
active identity
 ↓
reference images
 ↓
seed prompt
 ↓
generation
 ↓
4 candidates
 ↓
warehouse
```

Verify the entire path using the real provider.

---

# 10. Candidate persistence

For every successful live candidate verify:

```text
candidate record exists
asset exists
asset is retrievable
metadata exists
candidate belongs to correct job
candidate belongs to correct Persona
```

---

# 11. Shared storage gate

For multi-instance deployment:

```text
Process A
Process B
      ↓
same PostgreSQL
same IMAGE_STORAGE_DIR
```

Verify:

```text
A writes asset
B reads asset
```

The test must use actual running processes, not merely two storage objects inside one JVM.

---

# 12. Storage configuration

Verify every instance has:

```text
same storage mode
same shared storage mount
same IMAGE_STORAGE_DIR semantics
```

If the environment uses local filesystem storage, verify the directory is genuinely shared.

Do not claim shared storage because the environment variables happen to have identical strings.

---

# 13. Two-process deployment

Start two application processes.

Example:

```text
Process A
PORT=18080
IMAGE_WORKER_NAME=image-worker-a

Process B
PORT=18081
IMAGE_WORKER_NAME=image-worker-b
```

Use the project's actual port/worker configuration names.

Both processes must point to:

```text
same PostgreSQL
same shared storage
```

---

# 14. Two-process test — job visibility

Create a job through Process A.

Verify Process B can observe the shared job state.

Then create a job through Process B.

Verify Process A can observe the shared state.

---

# 15. Two-process test — asset visibility

Generate an image through Process A.

Retrieve the resulting asset through Process B.

Expected:

```text
asset available
correct bytes
correct authorization
```

Repeat in the opposite direction if practical.

---

# 16. Two-process test — worker ownership

Use distinct worker identities.

Verify:

```text
worker A claims → worker A completes
worker B cannot complete A's job
```

Then test controlled lease expiry/reclaim if the test harness permits.

Expected:

```text
new owner may complete
old owner cannot complete
```

---

# 17. Heartbeat gate

Run a generation that lasts long enough to exceed the basic lease interval or use the existing controlled heartbeat test mechanism.

Verify:

```text
claim
 ↓
heartbeat renewals
 ↓
provider execution
 ↓
completion
```

Confirm the job is not reclaimed merely because generation duration exceeds the initial lease.

---

# 18. Heartbeat failure

Where safely testable:

```text
worker loses renewal
```

Expected:

```text
heartbeat stops / logs appropriately
completion remains ownership-gated
stale worker cannot terminalize after reclaim
```

Do not disable production workers destructively.

Use the existing test mechanism where available.

---

# 19. Restart during normal operation

Test:

```text
start
 ↓
generate
 ↓
restart
 ↓
continue/recover
```

Verify completed data remains available.

Where the application supports recovery of queued work, verify queued work behaves according to its documented contract.

---

# 20. Restart after successful generation

After a completed generation:

1. Restart the application.
2. Open Image Warehouse.
3. Retrieve candidates.
4. Retrieve assets.
5. Inspect metadata.

Expected:

```text
job persists
candidate persists
asset persists
lineage persists
```

---

# 21. Chat attachment gate

Verify:

```text
generation
 ↓
successful candidate
 ↓
message attachment metadata
 ↓
user-visible message
```

Use the real application path.

Verify the correct `imageAssetIds` are attached.

If the application intentionally separates generation from posting, test only the documented attachment path.

---

# 22. Terminal reconciliation

Simulate or reproduce the documented crash/restart scenario:

```text
job reaches terminal state
message attachment not completed
worker/application restarts
```

Verify the worker reconciliation mechanism restores the attachment according to the existing contract.

---

# 23. Admin security

Test:

```text
Admin → allowed
Normal user → denied
Unauthenticated → denied
```

for:

```text
generation controls
warehouse
storage diagnostics
metrics
job details
configuration
```

Use existing authorization conventions.

---

# 24. Reference image protection

Verify private Persona reference assets are not unintentionally exposed through:

```text
normal user APIs
unauthorized asset URLs
Admin diagnostics
logs
error messages
```

The exact access policy must follow the existing Persona visual implementation.

---

# 25. Secrets security

Inspect:

```text
application logs
HTTP responses
Admin APIs
database records
generated diagnostics
```

Verify no:

```text
OPENROUTER_API_KEY
DATABASE_PASSWORD
Authorization headers
session tokens
```

are exposed.

---

# 26. Storage security

Verify:

```text
path traversal protection
relative storage keys
root containment
MIME validation
size limits
atomic writes
```

Use the existing automated security tests and a representative live storage check.

---

# 27. Failure recovery

Test controlled failures for:

```text
provider failure
storage failure
invalid model/configuration
worker interruption
database interruption where safe
```

For each verify:

```text
job state
candidate state
asset state
error visibility
retry behavior
```

No false successful candidate may be created.

---

# 28. Cost gate

Run real provider generation.

Verify Admin records:

```text
model
usage if available
cost status
cost value if available
```

Clearly distinguish:

```text
ACTUAL
ESTIMATED
UNAVAILABLE
```

Do not report zero when cost is simply unknown.

---

# 29. SLA gate

Measure real generation latency.

Record:

```text
job start
provider request
job completion
total duration
```

Compare with the configured SLA target if one exists.

If no SLA target is configured:

```text
SLA target = NOT CONFIGURED
```

Do not invent a target.

---

# 30. Observability gate

Verify the Admin operational view can answer:

```text
What is running?
What succeeded?
What failed?
How long did it take?
What model was used?
What did it cost?
```

Verify historical jobs remain visible after restart.

---

# 31. Retention safety

Do not run destructive retention tests against production data.

In a test environment verify:

```text
active jobs protected
message-referenced assets protected
eligible old terminal assets cleaned
missing-file deletion is safe
```

Confirm multiple instances do not create a correctness problem.

---

# 32. Database integrity

After live testing inspect representative records:

```text
generation job
candidate
asset metadata
Persona association
message attachment
model/provider metadata
configuration snapshot
```

Verify foreign-key and ownership relationships remain correct.

---

# 33. Duplicate generation check

Submit one generation request.

Verify normal UI/API behavior does not create unintended duplicate jobs.

If the user intentionally submits twice, treat them as two requests unless the existing product contract defines idempotency.

---

# 34. Concurrency check

Where safe:

```text
two jobs
same Persona
same worker pool
```

Verify:

- jobs remain independent;
- assets do not overwrite each other;
- candidate IDs remain distinct;
- storage keys remain unique.

---

# 35. Storage collision check

Verify generated assets use portable unique keys.

Expected conceptual form:

```text
jobs/{jobId}/candidates/{index}
```

No candidate from one job may overwrite another.

---

# 36. Configuration consistency

Verify every production process agrees on required configuration:

```text
database
storage mode
storage directory
model
worker settings
```

Where settings are intentionally process-specific:

```text
port
worker name
```

they must be distinct where required.

---

# 37. Deployment checklist

Before declaring ready:

```text
[ ] Database configured
[ ] Provider credentials configured
[ ] Image model configured
[ ] Shared storage provisioned
[ ] Permissions verified
[ ] Worker identities unique
[ ] Ports configured
[ ] Secrets externalized
[ ] Admin access verified
[ ] Monitoring available
[ ] Cost tracking available
[ ] SLA tracking available
[ ] Backup/recovery responsibility understood
```

---

# 38. Git safety

Before committing:

1. Inspect `git status`.
2. Confirm no secrets are staged.
3. Confirm temporary image files are ignored.
4. Confirm generated assets are not accidentally committed.
5. Review changed files.
6. Run required tests.
7. Commit only intentional changes.

---

# 39. Required final gate table

Cursor must produce:

| Gate | Result | Evidence | Remaining issue |
|---|---|---|---|
| Application | | | |
| Real Postgres | | | |
| OpenRouter | | | |
| Real model | | | |
| End-to-end | | | |
| Shared storage | | | |
| Two-process | | | |
| Lease heartbeat | | | |
| Restart/recovery | | | |
| Chat attachment | | | |
| Admin security | | | |
| Storage security | | | |
| Cost | | | |
| SLA | | | |
| Observability | | | |
| Retention | | | |

---

# 40. Go-live classification

Use exactly one of:

### READY FOR PRODUCT TESTING

All critical operational gates passed.

### READY WITH DOCUMENTED FINDINGS

Core product path works, but non-critical operational gaps remain.

### BLOCKED

A required dependency such as credentials, database, provider, or shared storage prevents meaningful verification.

### NOT READY

A tested critical path has failed.

Do not use a stronger classification than the evidence supports.

---

# 41. Critical gate definition

For this project, the following are critical before real multi-instance product testing:

```text
Real Postgres
Real OpenRouter/model
End-to-end generation
Shared storage
Two-process behavior
Worker ownership/heartbeat
Asset persistence
Admin generation workflow
Security
```

If any critical gate is:

```text
FAIL
```

the pipeline is not ready for the next operational stage.

If any critical gate is:

```text
BLOCKED / NOT VERIFIED
```

state that explicitly.

---

# 42. Product handoff

After this task, the pipeline should be ready for controlled product evaluation where the team can test:

```text
2–3 Personas
multiple reference images
multiple image models
different seed prompts
identity consistency
scene quality
regeneration quality
cost
latency
```

Task 24 does not decide which model or Persona performs best.

---

# 43. Definition of Done

- [ ] Real environment declared.
- [ ] Real Postgres verified or explicitly blocked.
- [ ] OpenRouter verified or explicitly blocked.
- [ ] Actual model verified.
- [ ] Live end-to-end generation verified.
- [ ] Candidate persistence verified.
- [ ] Shared storage verified.
- [ ] Two-process behavior verified.
- [ ] Worker identities verified.
- [ ] Lease heartbeat verified.
- [ ] Restart/recovery verified.
- [ ] Chat attachment verified.
- [ ] Admin authorization verified.
- [ ] Storage security verified.
- [ ] Secret handling verified.
- [ ] Cost verified or clearly unavailable.
- [ ] SLA measured or clearly not configured.
- [ ] Observability verified.
- [ ] Retention safety verified in test environment.
- [ ] No secrets committed.
- [ ] All critical findings documented.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` updated.
- [ ] Go-live classification recorded.

---

# 44. Cursor implementation instructions

Read:

```text
Tasks 18–23
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
IMAGE_PIPELINE_FINAL_OPERATIONAL_GATE.md
IMAGE_PIPELINE_MULTI_INSTANCE_OPS.md
```

Then inspect the current repository state.

Do not assume earlier reports remain true after subsequent code changes.

Run the current branch.

If a test fails because of an actual application defect:

1. document it;
2. fix the smallest necessary defect;
3. add/regenerate the regression test;
4. rerun affected tests;
5. update this report.

Do not perform unrelated refactoring.

After completion update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

---

# 45. Required completion report

Cursor must return:

```text
TASK 24 — PRODUCTION HARDENING / GO-LIVE

Branch:
Commit:

Environment:

Real Postgres:
PASS / FAIL / BLOCKED / NOT RUN

OpenRouter:
PASS / FAIL / BLOCKED / NOT RUN

Actual image model:

End-to-end:
PASS / FAIL / BLOCKED

Shared storage:
PASS / FAIL / BLOCKED

Two-process:
PASS / FAIL / BLOCKED

Lease heartbeat:
PASS / FAIL / BLOCKED

Restart/recovery:
PASS / FAIL / BLOCKED

Chat attachment:
PASS / FAIL / BLOCKED

Admin security:
PASS / FAIL

Storage security:
PASS / FAIL

Cost:
PASS / PARTIAL / UNAVAILABLE

SLA:
PASS / PARTIAL / NOT CONFIGURED

Observability:
PASS / PARTIAL / FAIL

Automated tests:
Passed:
Failed:
Skipped:

Live tests:
Passed:
Failed:
Blocked:
Not run:

Defects found:

Defects fixed:

Remaining gaps:

Go-live classification:

Files changed:

Commit:
```

**Critical rule:** A previous automated PASS does not replace a current live verification. This task exists specifically to establish what is actually true in the environment in which the Image Pipeline will be tested.
