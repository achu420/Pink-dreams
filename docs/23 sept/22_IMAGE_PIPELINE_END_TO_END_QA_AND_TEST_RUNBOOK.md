# 22 — IMAGE PIPELINE END-TO-END QA & TEST RUNBOOK

**Purpose:** Provide one controlled, repeatable procedure for validating the complete image pipeline before product acceptance testing.

**This is a QA/test task, not an invitation to redesign the architecture.**

**Depends on:** Tasks 18–21 and the existing Image Pipeline Implementation Status.

---

# 1. Objective

Validate the complete production path:

```text
Admin
  ↓
Persona
  ↓
Active visual identity
  ↓
Reference images
  ↓
Seed prompt
  ↓
Prompt / Engine / Skill
  ↓
Provider / Model
  ↓
Image generation job
  ↓
4 candidates
  ↓
Object storage
  ↓
Image Warehouse
  ↓
Admin review
  ↓
Remark / Shortlist / Decline / Save
  ↓
Correction
  ↓
Regeneration
  ↓
Lineage
```

The purpose is to establish what actually works, what does not, and what remains unverified.

---

# 2. Golden rule

Cursor must test the system that exists.

Do not:

- fabricate successful live tests;
- mark an untested integration as verified;
- replace real provider testing with Fake-provider results;
- replace real Postgres testing with H2-only results;
- silently change architecture to make a test pass;
- hide failures;
- mark a feature complete because the code compiles.

Every result must be classified as:

```text
PASS
FAIL
NOT RUN
BLOCKED
PARTIAL
```

---

# 3. Required test environment

Record:

```text
Application version / commit:
Branch:
Database:
Database version:
Provider:
Model:
Engine:
Skill:
IMAGE_STORAGE:
IMAGE_STORAGE_DIR:
IMAGE_WORKER_LEASE_SECONDS:
IMAGE_WORKER_HEARTBEAT_SECONDS:
Port:
Number of application processes:
```

Never place secrets in this document.

---

# 4. Test data

Use 2–3 real test Personas from the Admin system.

For each Persona verify:

```text
Persona ID
Persona name
Active visual identity
Identity version
Reference images
```

Minimum reference set where available:

```text
FACE_CLOSE
FRONT
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

Use the actual reference types supported by the existing Persona Visual implementation.

---

# 5. Test Persona preparation

Before generation:

1. Open Persona in Admin.
2. Open Visual configuration.
3. Confirm physical/visual attributes.
4. Confirm active identity version.
5. Confirm reference images.
6. Confirm each reference is correctly labelled.
7. Confirm references are accessible to the image pipeline.
8. Record the identity version used for testing.

Expected:

```text
Persona identity = known
Reference set = known
Identity version = known
```

---

# 6. Test A — Admin generation entry point

### Steps

1. Open Admin.
2. Open a test Persona.
3. Open image generation.
4. Enter a seed prompt.

Example:

```text
Generate an image of the Persona swimming in a yellow bikini
at a Goa beach, early morning, facing the camera.
```

5. Submit generation.

### Verify

- Persona is correct.
- Seed is retained.
- Current model is shown or traceable.
- Generation job is created.
- No duplicate job is created by normal UI behavior.

### Result

```text
PASS / FAIL / BLOCKED
```

Record evidence.

---

# 7. Test B — Identity resolution

Inspect the resulting generation metadata/logs where available.

Verify:

```text
Persona ID
Identity version
Reference set
```

are the intended values.

The Admin must not need to manually copy Persona physical attributes into the seed prompt.

### Failure conditions

Fail if:

- wrong Persona is used;
- stale identity is unexpectedly used;
- identity information is missing from lineage;
- references are silently omitted when required.

---

# 8. Test C — Prompt construction

Verify the seed prompt and compiled prompt remain distinguishable.

Record:

```text
Seed prompt:
Compiled prompt/configuration:
```

Verify Persona identity is not accidentally replaced by contradictory seed instructions.

Do not require the compiled prompt to be displayed publicly to normal users.

---

# 9. Test D — Provider/model execution

Verify:

```text
Provider:
Model:
Engine:
Skill:
```

for the generation.

Confirm that the actual provider/model used matches the configured selection.

If the live provider is unavailable:

```text
NOT RUN / BLOCKED
```

Do not substitute a Fake result and call the live test a PASS.

---

# 10. Test E — Four-candidate result

Wait for the job to reach a terminal state.

Expected when the provider returns four images:

```text
Candidate 1
Candidate 2
Candidate 3
Candidate 4
```

Verify:

- each candidate has a distinct asset;
- each candidate is persisted;
- each candidate belongs to the correct job;
- each candidate belongs to the correct Persona;
- each candidate has correct model/provider metadata.

If the provider returns fewer than four:

Record the actual count.

Do not manufacture missing candidates.

---

# 11. Test F — Asset persistence

For every successful candidate:

1. Retrieve the asset.
2. Verify it is readable.
3. Verify the asset URL/API works according to existing authorization.
4. Restart the application if practical.
5. Retrieve it again.

Expected:

```text
Asset remains available after restart.
```

Verify the configured ObjectStorage implementation is actually being used.

---

# 12. Test G — Image Warehouse

Open the Persona Image Warehouse.

Verify each candidate has:

```text
Image
Created date/time
Status
Seed
Provider
Model
Identity version
```

Where supported:

```text
Job ID
Candidate ID
Engine
Skill
Cost
Latency
Parent candidate
```

No candidate should disappear simply because the generation request has completed.

---

# 13. Test H — Candidate review

Select candidate 1.

Add:

```text
Admin remark:
Face matches reference. Hair needs improvement.
```

Save.

Reload.

Verify remark persists.

---

# 14. Test I — Shortlist

Select candidate 1.

Set:

```text
Shortlisted
```

Reload the Warehouse.

Verify status persists.

Verify shortlisting does NOT:

- modify Persona identity;
- delete other candidates;
- publish the image;
- create a user-facing post.

---

# 15. Test J — Decline

Select another candidate.

Set:

```text
Declined
```

Add a reason if supported.

Reload.

Verify:

```text
status = Declined
remark = retained
candidate = retained
```

---

# 16. Test K — Save

Use the project's actual Save semantics.

Verify that saving:

- retains the asset;
- retains metadata;
- does not silently publish;
- does not alter Persona identity.

If "Save" is not a distinct backend state in the current implementation, document that fact rather than inventing one.

---

# 17. Test L — Candidate identity review

For each selected candidate compare against the Persona reference set.

Review:

### Face

- facial proportions
- eyes
- nose
- lips
- recognizable features

### Hair

- color
- style
- length

### Body

- configured proportions
- physical characteristics

### Distinctive marks

- birthmarks
- moles
- other configured features

### Overall

Record an Admin observation such as:

```text
Consistent
Needs correction
Identity drift
```

Do not create a numerical identity score unless the system explicitly implements a validated comparison method.

---

# 18. Test M — Regeneration

Choose one candidate with a visible correction.

Example:

```text
Correction:
Keep the same Persona, scene and composition.
Match the hairstyle and hair color to the active identity references.
```

Request regeneration.

Expected:

```text
Original candidate remains.
New generation job is created.
New candidate(s) are created.
```

---

# 19. Test N — Regeneration lineage

Open the regenerated candidate.

Verify it identifies:

```text
Parent candidate
Original job
Correction instruction
Persona
Identity version/reference lineage
Provider
Model
```

Expected:

```text
Candidate A
   ↓
Correction
   ↓
Candidate B
```

Candidate A must remain intact.

---

# 20. Test O — Multiple regeneration rounds

Perform:

```text
A
 ↓ correction 1
B
 ↓ correction 2
C
```

Verify the lineage remains understandable.

Expected:

```text
C → B → A
```

No candidate should be overwritten.

---

# 21. Test P — Model change

If multiple models are configured:

1. Generate using Model A.
2. Record metadata.
3. Change active model to Model B.
4. Generate using the same Persona and seed.
5. Compare metadata.

Expected:

```text
Job A → Model A
Job B → Model B
```

Historical candidates must remain Model A.

If only one live model is currently available:

```text
NOT RUN
```

and record why.

---

# 22. Test Q — Same Persona / same seed comparison

Use:

```text
Same Persona
Same identity version
Same reference set
Same seed
```

Generate through two models if available.

Keep the comparison controlled.

Record observations:

```text
Identity consistency
Prompt adherence
Reference adherence
Scene quality
Composition
Latency
Cost
Failures
```

Do not declare an automatic winner.

---

# 23. Test R — Failure handling

Cause a controlled failure where safe.

Possible method:

- invalid test model;
- deliberately unavailable provider configuration;
- provider test failure mechanism.

Verify:

```text
Job = FAILED
```

and:

- no fake successful asset exists;
- credentials are not exposed;
- error is redacted;
- Admin can understand that generation failed;
- retry behavior follows the existing contract.

---

# 24. Test S — Worker lease / heartbeat

For a controlled long-running job verify:

```text
claim
 ↓
heartbeat
 ↓
provider execution
 ↓
heartbeat continues
 ↓
complete
```

Verify:

- owner can renew;
- non-owner cannot renew;
- stale worker cannot complete after lease ownership changes;
- completion remains ownership-gated.

If a live long-running provider test cannot be executed:

```text
NOT RUN
```

Keep automated Fake-provider results separate.

---

# 25. Test T — Two-process test

Run:

```text
Process A
Process B
```

against:

```text
same Postgres
same shared IMAGE_STORAGE_DIR
different IMAGE_WORKER_NAME
different ports
```

Example:

```text
A → 18080
B → 18081
```

Verify:

1. A can create/process a job.
2. B can read the resulting asset.
3. Shared DB state is visible to both.
4. Worker identities are distinct.
5. Lease ownership prevents stale completion.
6. Storage paths are shared.
7. An asset created by A is retrievable by B.

If the two-process environment cannot be created:

```text
NOT RUN
```

Do not call the H2 contract test a live two-process PASS.

---

# 26. Test U — Separate storage-root safety

Where practical:

```text
Process A → storage A
Process B → storage B
```

with shared DB.

Verify B cannot falsely report an asset as available when its bytes are absent.

Expected:

```text
missing asset / 404
```

rather than fabricated success.

---

# 27. Test V — Application restart

During or after a completed generation:

1. Stop the application.
2. Restart.
3. Open Image Warehouse.
4. Retrieve candidates.
5. Retrieve assets.

Verify:

```text
jobs persist
candidates persist
statuses persist
remarks persist
assets persist
lineage persists
```

---

# 28. Test W — Configuration persistence

Restart after configuration changes.

Verify:

```text
active model
engine
skill
storage configuration
```

remain correct according to the project's configuration mechanism.

---

# 29. Test X — Security

Verify:

- Admin can access Admin generation.
- Normal user cannot access Admin warehouse APIs.
- Protected assets require appropriate authorization.
- Private identity references remain protected.
- API keys never appear in responses.
- API keys never appear in logs.
- DB passwords never appear in logs.
- Error responses are redacted.

---

# 30. Test Y — Cost and latency

For completed live generations record:

```text
Provider
Model
Job start
Job completion
Latency
Candidate count
Reported cost
```

If cost is unavailable:

```text
Cost unavailable
```

Never assume zero cost.

Compare the observed values with existing SLA expectations.

---

# 31. Test Z — Retention/recovery

Verify the existing retention system does not delete:

```text
active jobs
message-referenced assets
currently required candidates
```

Do not perform destructive retention tests against production data.

Use a dedicated test environment.

---

# 32. Full test matrix

Cursor must produce a table like:

| Test | Result | Evidence | Notes |
|---|---|---|---|
| Admin generation | | | |
| Identity resolution | | | |
| Prompt construction | | | |
| Provider/model | | | |
| Four candidates | | | |
| Asset persistence | | | |
| Warehouse | | | |
| Remarks | | | |
| Shortlist | | | |
| Decline | | | |
| Save | | | |
| Identity review | | | |
| Regeneration | | | |
| Lineage | | | |
| Multiple regeneration | | | |
| Model comparison | | | |
| Failure handling | | | |
| Lease heartbeat | | | |
| Two-process | | | |
| Shared storage | | | |
| Restart | | | |
| Configuration | | | |
| Security | | | |
| Cost/SLA | | | |
| Retention | | | |

---

# 33. Evidence requirements

For every PASS provide enough evidence to reproduce the result.

Examples:

```text
API response
Admin UI observation
test name
log excerpt
job ID
candidate ID
database row inspection
```

Never paste secrets into evidence.

Redact:

```text
API keys
passwords
tokens
session cookies
```

---

# 34. Failure classification

Use:

### PASS

Test executed successfully.

### PARTIAL

Some part passed but an important component did not.

### FAIL

Expected behavior was not achieved.

### NOT RUN

Test was not executed.

### BLOCKED

Test could not be executed because of a concrete dependency.

Examples:

```text
OpenRouter key unavailable
Postgres credentials unavailable
shared volume unavailable
```

---

# 35. Important distinction

Maintain separate results for:

```text
Automated contract test
Fake provider test
H2 test
Real Postgres test
Real OpenRouter test
Live two-process test
Admin UI test
```

Do not combine them into one generic "PASS".

---

# 36. No fabricated verification

If Cursor has:

```text
213 automated tests passed
```

that means:

```text
213 automated tests passed
```

It does NOT mean:

```text
OpenRouter works
Postgres works
two JVMs work
Admin UI works
production storage works
```

Those must be explicitly tested.

---

# 37. Final QA gate

At the end classify the pipeline:

```text
CORE IMPLEMENTATION
PASS / PARTIAL / FAIL

ADMIN WORKFLOW
PASS / PARTIAL / FAIL

LIVE PROVIDER
PASS / NOT RUN / BLOCKED / FAIL

REAL POSTGRES
PASS / NOT RUN / BLOCKED / FAIL

TWO-PROCESS
PASS / NOT RUN / BLOCKED / FAIL

SHARED STORAGE
PASS / NOT RUN / BLOCKED / FAIL

SECURITY
PASS / PARTIAL / FAIL

COST / SLA
PASS / PARTIAL / NOT RUN

PRODUCT ACCEPTANCE
PASS / NOT RUN
```

Do not call the overall system production-ready merely because automated tests pass.

---

# 38. Definition of Done

- [ ] All applicable tests in this runbook were executed.
- [ ] Each test has a result.
- [ ] Evidence is recorded.
- [ ] Live and automated results are clearly separated.
- [ ] Admin workflow has been exercised.
- [ ] 2–3 Personas have been tested where environment permits.
- [ ] Four-candidate generation has been tested.
- [ ] Identity/reference consistency has been reviewed.
- [ ] Warehouse workflow has been tested.
- [ ] Regeneration lineage has been tested.
- [ ] Model comparison has been tested where multiple models are available.
- [ ] Cost/latency have been captured where available.
- [ ] Security has been tested.
- [ ] Restart/recovery has been tested.
- [ ] Two-process testing has been run where environment permits.
- [ ] Shared storage has been tested.
- [ ] No secrets were committed or recorded.
- [ ] All failures and blocked tests are documented.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.
- [ ] QA report is committed.

---

# 39. Cursor instructions

Before executing:

1. Read all Image Pipeline task MD files in the `docs/23 sept/` folder.
2. Read `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`.
3. Inspect the current branch and uncommitted changes.
4. Confirm the application can start.
5. Confirm test environment variables.
6. Confirm which tests can actually be run.

Then execute the runbook in order.

Do not make broad architecture changes during QA.

If a defect is found:

```text
Stop
Document
Fix only if necessary for the current gate
Add/adjust regression test
Re-run affected tests
Continue
```

After completion update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with the complete test matrix.

---

# 40. Required completion report

Cursor must return:

```text
TASK 22 — END-TO-END QA

Environment:
Commit:
Branch:

CORE IMPLEMENTATION:
Result:

ADMIN WORKFLOW:
Result:

LIVE PROVIDER:
Result:

REAL POSTGRES:
Result:

TWO-PROCESS:
Result:

SHARED STORAGE:
Result:

LEASE HEARTBEAT:
Result:

SECURITY:
Result:

COST/SLA:
Result:

PRODUCT WORKFLOW:
Result:

TEST MATRIX:
<full table>

Automated tests:
Passed:
Failed:
Skipped:

Live tests:
Passed:
Failed:
Not run:
Blocked:

Defects found:

Defects fixed:

Known remaining gaps:

Files changed:

Commits:

Final QA gate:
```

---

# 41. Handoff to Task 23+

Task 22 should not decide which image model is best.

Its job is to establish:

```text
Does the pipeline work?
```

Task 23 focuses on:

```text
Can Admin observe cost and SLA?
```

Later product acceptance focuses on:

```text
Which model/output actually works best for our Personas and use cases?
```

Those are separate decisions.

**Critical rule:** Do not move to final product acceptance while pretending unexecuted live-provider, real-Postgres, or two-process tests passed. The report must show exactly what was proven and what remains unverified.
