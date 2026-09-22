# 17 — IMAGE PIPELINE END-TO-END PRODUCTION TEST & RELEASE GATE

**Depends on:**
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- Tasks 01–16 in this image-pipeline program

**Goal:** Verify that the complete image-generation product path works from Admin/Persona configuration through generation, identity/reference handling, candidate review, regeneration, storage, observability, and final handoff.

This is the **final implementation/test gate for the current image pipeline scope**.

Publishing/social distribution remains deferred unless explicitly included in a later task.

---

# 1. Core principle

Do not mark the image pipeline complete because individual unit tests pass.

The final gate must prove that the actual product path works:

```text
Persona
  ↓
Visual identity
  ↓
Reference images
  ↓
Seed instruction
  ↓
Image generation engine/model
  ↓
Generation job
  ↓
Worker
  ↓
Provider
  ↓
4 candidates
  ↓
Durable storage
  ↓
Image Warehouse
  ↓
Admin review
  ↓
Shortlist / decline / save
  ↓
Correction / regeneration
  ↓
Candidate lineage
  ↓
Optional chat attachment
  ↓
Observability / SLA / cost
```

---

# 2. Before testing

Cursor must first inspect:

- current branch
- current git status
- current image pipeline documentation
- current implementation-status document
- current database migration state
- current Admin routes/UI
- current image provider configuration
- current storage configuration
- current worker configuration
- current test suite

Do not begin destructive tests against unknown data.

Use clearly identifiable test Persona/data where possible.

---

# 3. Environment gate

Record:

```text
DATABASE:
Provider:
Connection:
Schema/migrations:

IMAGE PROVIDER:
Provider:
Model:
Configuration source:

IMAGE STORAGE:
Mode:
Directory:
Shared/local:

WORKER:
Worker name:
Lease:
Heartbeat:

APPLICATION:
Port:
Environment:
```

Secrets must never be committed into:

- source code
- markdown
- logs
- git
- screenshots
- test fixtures

---

# 4. Real provider gate

If real OpenRouter credentials are available, perform a controlled live generation.

If credentials are unavailable:

```text
NOT VERIFIED
```

Do not substitute Fake provider results and call them live-provider verification.

Record:

```text
Provider:
Model:
Request time:
Completion time:
Latency:
Number of candidates:
Provider response status:
Cost if available:
```

---

# 5. Database gate

Verify the application against the intended database configuration.

At minimum verify:

- migrations complete
- image jobs persist
- generated candidates persist
- image events persist
- candidate status changes persist
- regeneration lineage persists
- message attachment metadata persists
- retention references persist

If only H2 is tested:

```text
H2 VERIFIED
Real Postgres NOT VERIFIED
```

Do not claim production database verification without actually using the production-like database.

---

# 6. Persona setup test

Create/select a clearly named test Persona.

Verify Persona visual identity contains the configured data expected by the current product specification.

At minimum inspect:

```text
Physical attributes
Hair
Eyes
Skin/appearance
Distinctive marks
Body attributes
Wardrobe/style information
Reference images
Reference image types
Identity version
```

Where applicable, sensitive/private visual information must remain separately controlled according to the existing Persona visual-identity implementation.

---

# 7. Reference image gate

Verify reference images can be stored and identified by standard view.

Expected supported identity-reference categories:

```text
FRONT
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

Private reference images, where supported by the existing product design, must remain separately stored/authorized.

Verify the image-generation request can resolve the correct active identity/reference set.

---

# 8. Identity consistency gate

Generate an image using a Persona with reference images.

Verify:

```text
Persona identity selected
        ↓
active visual identity resolved
        ↓
correct reference set selected
        ↓
reference metadata reaches generation pipeline
```

The seed prompt must not silently replace the Persona identity.

Identity should remain the identity authority.

---

# 9. Seed generation test

Use a clear seed instruction.

Example:

```text
Generate an image of the Persona swimming in a yellow bikini
at an early morning beach in Goa, facing the camera.
```

Record:

```text
Original seed
Compiled prompt/context
Persona
Identity version
Reference set
Provider
Model
Generation ID
```

Do not overwrite the original seed when compiling the final provider prompt.

---

# 10. Candidate-count gate

A standard generation request should produce:

```text
4 candidates
```

unless the configured engine/provider contract explicitly specifies another number.

Verify:

- all candidates persist
- all have unique candidate IDs
- all reference the correct generation
- all reference the correct Persona
- all assets are retrievable
- no candidate silently overwrites another

---

# 11. Asset storage gate

Verify every successful candidate has:

```text
asset ID
storage key
MIME type
size
retrievable bytes
```

Verify:

```text
temporary write
→ validation
→ atomic/final move
→ durable asset
```

where this is the current storage implementation.

Test:

- invalid path
- oversized asset
- invalid MIME
- missing asset
- storage failure

No path traversal must be possible.

---

# 12. Image Warehouse gate

Open:

```text
Persona → Image Warehouse
```

Verify all generated candidates are visible.

For each candidate verify:

```text
Image
Status
Date/time
Generation ID
Persona
Model
Identity version
Reference set
Seed
Cost where available
Latency where available
Admin remark
```

The warehouse must be the historical source of generated candidates.

---

# 13. Candidate review gate

Test:

```text
Candidate 1 → Shortlist
Candidate 2 → Decline
Candidate 3 → Save
Candidate 4 → leave unchanged
```

Verify only intended candidates change.

Confirm declined candidates remain in the warehouse unless retention policy later removes them.

---

# 14. Admin remark gate

Add a remark:

```text
Face is close but hair needs correction.
```

Reload the candidate.

Verify the remark persists.

Verify the remark is distinguishable from the original seed.

---

# 15. Regeneration gate

Regenerate the candidate using an explicit correction.

Example:

```text
Keep the Persona identity and face consistent.
Change the hair to the reference style.
```

Verify:

```text
Original candidate remains unchanged
New generation created
New candidate created
Parent candidate recorded
Persona preserved
Identity version preserved
Reference set preserved
Original seed preserved
Correction recorded
```

---

# 16. Candidate lineage gate

Open the regenerated candidate.

Admin must be able to determine:

```text
Which candidate generated this?
Which generation did it belong to?
What was the original seed?
What correction caused the regeneration?
Which model generated the replacement?
```

No lineage may point to a nonexistent candidate.

---

# 17. Model comparison gate

If multiple candidate models are configured for evaluation:

Generate comparable requests.

Record:

```text
Model
Provider
Generation
Candidates
Latency
Cost
Admin outcome
```

Do not rank models automatically unless an explicit evaluation/scoring mechanism exists.

The warehouse must preserve enough metadata for later comparison.

---

# 18. Chat attachment gate

Generate through the chat-adjacent path if supported.

Verify:

```text
chat request
→ image job
→ worker
→ candidate
→ asset
→ assistant message metadata
```

On success verify:

```text
imageJobStatus = SUCCEEDED
imageAssetIds present
imageAssetUrls present
```

On failure verify the assistant message does not claim a successful image asset.

---

# 19. Worker reliability gate

Test:

```text
queued
→ claimed
→ running
→ completed
```

Verify lease ownership.

Test stale worker behavior:

```text
Worker A claims job
Worker A loses lease
Worker B reclaims job
Worker A attempts completion
```

Expected:

```text
Worker A cannot terminalize Worker B's job.
```

---

# 20. Heartbeat gate

For a generation longer than the normal lease period, verify heartbeat behavior where enabled.

Expected:

```text
claim
→ heartbeat
→ provider
→ heartbeat continues
→ completion
```

Verify only the current owner can renew.

If heartbeat is disabled:

```text
IMAGE_WORKER_HEARTBEAT_SECONDS=0
```

document that the test intentionally uses lease-only behavior.

---

# 21. Multi-instance gate

When shared storage and real database are available:

```text
Instance A
   ↓
shared Postgres
   ↓
shared IMAGE_STORAGE_DIR
   ↑
Instance B
```

Run two application processes using different:

```text
PINKDREAMS_PORT
IMAGE_WORKER_NAME
```

Verify:

- both can access shared jobs
- one worker claims a job
- another worker cannot incorrectly complete the same lease
- Instance B can retrieve Instance A's generated asset
- no duplicate terminal completion occurs
- shared storage reads work

If this is not run:

```text
Automated contract verified
Live two-process NOT VERIFIED
```

---

# 22. Security gate

Test:

### Unauthenticated

Attempt:

```text
create
read job
read result
read asset
modify candidate
```

Expected according to existing security contract:

```text
denied
```

### Cross-persona

Attempt to use Persona A credentials/context to access Persona B assets.

Expected:

```text
denied / not found
```

### Path traversal

Attempt:

```text
../
../../
absolute path
encoded traversal
```

Expected:

```text
rejected
```

---

# 23. Retention gate

Create a test asset that is:

```text
message referenced
```

Run retention.

Expected:

```text
asset retained
```

Create an old terminal candidate that is not referenced.

Verify it is eligible according to configured retention policy.

Verify active jobs are protected.

---

# 24. Observability gate

For one successful generation verify events exist for meaningful lifecycle stages.

At minimum:

```text
job created
job claimed
generation started
generation completed
asset stored
candidate persisted
```

For failure:

```text
failure event
error classification
job status
```

Secrets and full provider credentials must never appear in events/logs.

---

# 25. SLA gate

Verify Admin can inspect:

```text
queue latency
generation latency
total job latency
success/failure counts
```

If metrics are sample/demo data, label them as such.

Never fabricate live production metrics.

---

# 26. Cost gate

Verify cost metadata is recorded when the provider supplies usable cost information.

At minimum preserve:

```text
provider
model
generation
candidate/job
cost where available
```

If cost cannot be obtained:

```text
unknown
```

must remain different from:

```text
$0
```

---

# 27. Admin engine configuration gate

Verify Admin configuration reflects the actual runtime configuration.

Test:

```text
configured provider
configured model
image skill/engine configuration
```

Changing configuration must not silently change Persona identity data.

Identity and engine configuration remain separate concerns.

---

# 28. Failure matrix

Run at least:

| Failure | Expected |
|---|---|
| Provider timeout | retry/failure policy |
| Provider 5xx | retry if classified retryable |
| Permanent provider error | terminal failure |
| Storage failure | job failure / no false success |
| Invalid asset | rejected |
| Worker restart | job recoverable |
| Stale lease | reclaim |
| Stale worker completion | rejected |
| Missing asset | explicit failure/404 |
| Database failure | no fabricated success |

Record actual behavior rather than assuming it.

---

# 29. UI consistency gate

Verify Admin does not display false state.

Examples:

```text
Generation running
```

must not appear as:

```text
Completed
```

unless backend says completed.

Likewise:

```text
Cost unavailable
```

must not become:

```text
$0
```

and:

```text
Storage unavailable
```

must not become:

```text
Healthy
```

---

# 30. Complete product walkthrough

Perform one clean walkthrough without directly manipulating database rows.

```text
1. Open Persona
2. Configure/inspect visual identity
3. Verify reference images
4. Open Image Generation
5. Enter seed
6. Generate
7. Wait for worker
8. Receive candidates
9. Open Image Warehouse
10. Review candidates
11. Add remark
12. Shortlist one
13. Decline one
14. Save one
15. Regenerate one
16. Inspect lineage
17. Mark one for post
18. Inspect SLA
19. Inspect cost
20. Inspect generation metadata
21. Verify optional chat attachment
```

This is the primary product acceptance test.

---

# 31. Evidence collection

Cursor must record:

```text
Test name
Date/time
Environment
Provider
Model
Persona
Generation ID
Candidate IDs
Result
Evidence
Failure
```

Evidence can be:

- test output
- API response
- Admin screenshot
- log excerpt with secrets removed
- database inspection
- asset verification

Do not paste credentials into the report.

---

# 32. Test classification

Every verification must use one of:

```text
PASS
PASS WITH FINDINGS
FAIL
NOT VERIFIED
NOT APPLICABLE
```

Definitions:

### PASS
Actually tested and working.

### PASS WITH FINDINGS
Core behavior works but a documented limitation remains.

### FAIL
Expected behavior was tested and did not work.

### NOT VERIFIED
The required environment/test was unavailable.

### NOT APPLICABLE
The test genuinely does not apply.

Never use `PASS` for a test that was not actually executed.

---

# 33. Final release matrix

Cursor must produce:

| Area | Result | Evidence | Finding |
|---|---|---|---|
| Persona identity | | | |
| Reference images | | | |
| Prompt/seed | | | |
| Provider | | | |
| Model | | | |
| Candidate generation | | | |
| Asset storage | | | |
| Image warehouse | | | |
| Candidate review | | | |
| Regeneration | | | |
| Lineage | | | |
| Chat attachment | | | |
| Worker | | | |
| Lease | | | |
| Heartbeat | | | |
| Multi-instance | | | |
| Security | | | |
| Retention | | | |
| Observability | | | |
| SLA | | | |
| Cost | | | |
| Admin configuration | | | |

---

# 34. Final definition of done

The image pipeline may be called **production-ready for the implemented scope** only when:

- [ ] All required implementation tasks are complete.
- [ ] Admin can configure/inspect Persona visual identity.
- [ ] Reference images are available to generation.
- [ ] Seed generation works.
- [ ] Provider/model configuration is real.
- [ ] Candidate generation works.
- [ ] Candidates are durable.
- [ ] Image Warehouse works.
- [ ] Review actions work.
- [ ] Remarks work.
- [ ] Regeneration works.
- [ ] Lineage works.
- [ ] Mark-for-post handoff works.
- [ ] Chat attachment works where enabled.
- [ ] Worker recovery works.
- [ ] Lease ownership is enforced.
- [ ] Heartbeat works where enabled.
- [ ] Security tests pass.
- [ ] Retention tests pass.
- [ ] Observability works.
- [ ] SLA data works.
- [ ] Cost tracking works where provider data is available.
- [ ] Admin configuration is traceable.
- [ ] Automated tests pass.
- [ ] Relevant live-provider tests are completed or explicitly marked NOT VERIFIED.
- [ ] Relevant Postgres tests are completed or explicitly marked NOT VERIFIED.
- [ ] Multi-process tests are completed or explicitly marked NOT VERIFIED.
- [ ] No known critical failure remains.
- [ ] Implementation-status documentation is updated.

---

# 35. Scope boundary

This final gate does **not** automatically make these systems complete:

```text
Social publishing
Post scheduling
Instagram integration
Content calendar
Storyline engine
Daily autonomous generation
LoRA training
Advanced image ranking
Automated aesthetic scoring
Cloud object storage
```

These require separate product/engineering tasks unless already implemented and explicitly verified.

---

# 36. Required final documentation update

After testing, update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

and create/update:

```text
IMAGE_PIPELINE_FINAL_TEST_REPORT.md
```

The final report must contain:

```text
Overall verdict:
Environment:
Provider:
Model:

Automated tests:
Live provider:
Real Postgres:
Two-process:
Shared storage:
Admin UI:
Security:
Retention:
Observability:
SLA:
Cost:

Passed:
Failed:
Pass with findings:
Not verified:

Known limitations:

Required follow-ups:

Git branch:
Latest commit:
```

---

# 37. Git rule

Do not push unrelated changes.

Before final commit:

```text
git status
git diff
```

Review all changed files.

Commit only the image-pipeline changes belonging to this task.

Record:

```text
Branch:
Commit:
Working tree:
```

Do not push unless explicitly instructed by the project owner.

---

# 38. Final output required from Cursor

At completion, report exactly:

```text
IMAGE PIPELINE TASK 17

Verdict:
Automated tests:
Live provider:
Real Postgres:
Two-process:
Admin E2E:
Security:
Storage:
Observability:
SLA:
Cost:

Passed:
Failed:
Pass with findings:
Not verified:

Critical findings:

Non-critical findings:

Files changed:

Commit:

Implementation status updated:
Final test report updated:
```

No fabricated verification.

The final purpose of Task 17 is to establish exactly what is working, what has actually been tested, and what remains operationally unverified.
