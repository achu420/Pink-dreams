

````markdown
# IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md

**Date:** 2026-09-23  
**Purpose:** Living implementation baseline for the Image Pipeline work  
**Owner:** Cursor implementation agent  
**Status:** MUST BE UPDATED THROUGHOUT THE 23 SEPTEMBER IMAGE PIPELINE PROGRAM

---

# 1. PURPOSE

This document is the single source of truth for answering:

1. What already exists?
2. What is partially implemented?
3. What is missing?
4. What has Cursor implemented?
5. What has been tested?
6. What has NOT been tested?
7. Which parts are production-ready?
8. Which parts are only infrastructure and not yet product-complete?

Every image-pipeline task must update this document before declaring the task complete.

Do NOT mark a feature complete merely because code exists.

A feature is complete only when:

```text
Backend implemented
        +
Admin UI implemented where applicable
        +
API wired
        +
Persistence verified
        +
Happy path tested
        +
Important failure paths tested
        +
Admin can actually operate it
        +
Implementation documented
````

---

# 2. IMPORTANT GOVERNANCE

## 2.1 Do not redesign the architecture unnecessarily

Reuse the existing image pipeline wherever possible.

Do not introduce a second image-generation architecture.

Do not duplicate:

* image job system
* provider abstraction
* storage abstraction
* observability
* SLA system
* persona identity system
* prompt compiler
* worker system

Extend existing infrastructure unless there is a demonstrated reason not to.

---

# 3. IMAGE MODEL REQUIREMENT

## Required direction

The image generation provider must use **OpenRouter**.

The system must support selecting an image model through configuration/admin configuration rather than hardcoding a single model permanently.

The initial testing requirement is:

```text
OpenRouter
    ↓
Candidate image model(s)
    ↓
Generate multiple images
    ↓
Admin evaluates output
```

## Important product requirement

The selected image model should be suitable for our intended fictional-persona image generation use cases and should not unnecessarily restrict ordinary creative image generation.

Do NOT silently substitute a restrictive provider/model.

Record:

```text
provider
model
generation configuration
request parameters
latency
token/cost information where available
```

---

# 4. CURRENT IMPLEMENTATION BASELINE

The following was already implemented before this 23 September work.

## 4.1 Image job system

Status:

**IMPLEMENTED**

Existing capabilities include:

* image job creation
* job persistence
* job state transitions
* idempotent creation
* worker processing
* lease ownership
* stale worker protection
* retry/failure handling
* worker recovery

---

# 5. WORKER

Status:

**IMPLEMENTED**

Existing architecture:

```text
Image Job
   ↓
Worker claims job
   ↓
Lease ownership
   ↓
Image generation handler
   ↓
Provider
   ↓
Asset storage
   ↓
Candidate
   ↓
Job completion
```

Lease heartbeat has also been implemented.

Current configuration includes:

```text
IMAGE_WORKER_LEASE_SECONDS
IMAGE_WORKER_HEARTBEAT_SECONDS
IMAGE_WORKER_NAME
```

Two-worker ownership protection has been tested through automated tests.

Live multi-process production verification remains an operational test rather than a code-completeness requirement.

---

# 6. STORAGE

Status:

**IMPLEMENTED**

Current supported production architecture:

```text
Shared PostgreSQL
       +
Shared IMAGE_STORAGE_DIR
```

Implemented:

* durable local-file storage
* atomic writes
* path traversal protection
* MIME validation
* size limits
* storage diagnostics
* shared-storage contract
* retention cleanup
* asset retrieval

Object storage such as S3/GCS/R2 is NOT currently implemented.

Do not add cloud object storage unless specifically requested.

---

# 7. IMAGE API

Status:

**IMPLEMENTED**

Existing capabilities include:

```text
Create image job
Get job
Get result
Get asset
Cancel
Requeue
Attach message
```

User-owned image endpoints also exist.

Admin image endpoints exist.

The remaining work is to verify that these APIs support the final persona/image-warehouse product requirements.

---

# 8. IMAGE OBSERVABILITY

Status:

**IMPLEMENTED**

Existing capabilities include:

* image generation events
* provider information
* model information
* latency
* outcomes
* failures
* SLA aggregation

Admin SLA endpoint exists.

However, the final product requirements additionally require:

```text
generation cost
cost per model
cost per provider
cost per generation
cost per persona
cost over time
```

These must be explicitly audited and implemented if missing.

---

# 9. PERSONA VISUAL IDENTITY

Status:

**IMPLEMENTED (2026-09-23 Task 24 — PASS WITH FINDINGS)**

Evidence:

* Domain: `PhysicalGuide` (height/weight/build/muscularity/anatomy/marks), `PrivateVisualGuide`
* Versioning: `persona_visual_versions` + draft→publish→activate (unchanged lifecycle, extended fields)
* Admin APIs: ensure / physical-guide / private-guide / style-constraints / publish-activate
* Admin UI: Persona Detail → Visual Identity editable form
* PromptCompiler: expanded identity + private guide + styleConstraints text
* Status report: `docs/for cursor/24_PERSONA_VISUAL_IDENTITY_AND_REFERENCE_IMAGES_STATUS.md`

Findings: browser Admin UI not manually clicked this session (API-tested); wardrobe catalog still not compiled into prompt text.

---

# 10. REFERENCE IMAGE SYSTEM

## REQUIRED PRODUCT DIRECTION

Reference images are a core part of persona visual consistency.

Each persona should eventually support a standardized reference-image set.

Recommended categories:

```text
FRONT
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK
FULL_BODY
```

Optional:

Status (2026-09-23 Task 24):

**IMPLEMENTED (standard slots + PRIVATE + Admin upload/preview/remove)**

Roles: `FRONT`, `FACE_CLOSE`, `LEFT_PROFILE`, `RIGHT_PROFILE`, `BACK`, `PRIVATE`, `OTHER` (+ legacy).  
Admin multipart upload, content GET, delete, slot replace (archive prior). Generation auto-select prefers standard slots and excludes PRIVATE by default. Migration `V011`.

```text
THREE_QUARTER_LEFT
THREE_QUARTER_RIGHT
BODY_DETAIL
OTHER
```

Private reference images, if supported, must be stored separately with stronger access controls.

---

# 11. IMAGE IDENTITY MATCHING

Required future behavior:

```text
Persona
   ↓
Structured visual identity
   +
Reference images
   +
Seed prompt
   ↓
Image generation
   ↓
Generated candidates
   ↓
Identity consistency evaluation
```

The system should be designed so generated images can be compared against the persona's reference identity.

This does NOT necessarily mean automated facial-recognition scoring must be implemented immediately.

First implementation should establish:

* reference-image metadata
* reference-image retrieval
* consistent model input
* candidate association
* admin review

Automated identity scoring can be added after the basic pipeline works.

---

# 12. PROMPT SYSTEM

Status:

**PARTIALLY IMPLEMENTED / NEEDS PRODUCT VERIFICATION**

Existing:

```text
PromptCompiler
```

Final desired flow:

```text
Seed Prompt
+
Persona Visual Identity
+
Reference Images
+
Relevant wardrobe/style metadata
+
Generation configuration
        ↓
Final Image Prompt
```

Example seed:

```text
Generate an image of Simran swimming in a yellow bikini
at a Goa beach in the early morning, facing the camera.
```

The system should preserve the seed intent while automatically supplying persona identity information.

---

# 13. IMAGE GENERATION OUTPUT

## Required

One generation request should produce multiple candidates.

Initial target:

```text
4 candidates
```

Example:

```text
Generation Request
       ↓
       ├── Candidate 1
       ├── Candidate 2
       ├── Candidate 3
       └── Candidate 4
```

Each candidate must remain individually identifiable.

---

# 14. IMAGE WAREHOUSE

## REQUIRED NEW PRODUCT FEATURE

Every persona should have an **Image Warehouse**.

Concept:

```text
PERSONA
   │
   ├── Visual Identity
   ├── Reference Images
   ├── Image Warehouse
   │      ├── Candidate 1
   │      ├── Candidate 2
   │      ├── Candidate 3
   │      └── Candidate 4
   │
   └── Posts
```

Each generated image should store metadata such as:

```text
image ID
persona ID
generation ID
created timestamp
status
seed prompt
final compiled prompt
model
provider
admin remarks
asset reference
```

---

# 15. IMAGE WAREHOUSE STATUS

Minimum statuses:

```text
GENERATED
SHORTLISTED
DECLINED
SAVED
POSTED
```

Implementation may use a smaller internal state machine if documented, but the Admin UI must make the product state understandable.

---

# 16. ADMIN IMAGE REVIEW

Required admin workflow:

```text
Generate
   ↓
4 candidates
   ↓
Admin reviews
   ↓
Select one or more
   ↓
Add remarks
   ↓
Shortlist / Save / Decline
```

Admin must be able to see enough metadata to understand what generated the image.

---

# 17. REGENERATION

Required workflow:

```text
Candidate
   ↓
Admin gives correction
   ↓
Regenerate
   ↓
New candidate(s)
```

The correction should not silently replace the original.

Example:

```text
Original:
"yellow bikini"

Admin correction:
"Make the pose more natural and have her face the camera."

New generation:
linked to original candidate
+
correction
+
new generation metadata
```

This creates an auditable generation history.

---

# 18. IMAGE COMMENTS / REMARKS

Admin should be able to add remarks to individual candidates.

Examples:

```text
Face looks accurate
Hair is wrong
Body proportions need correction
Pose is good
Background is good
Reject
Try again with stronger reference
```

Remarks must be persisted.

---

# 19. SAVE / POST SEPARATION

Important:

**SAVE and POST are different concepts.**

Save:

```text
Keep image in Image Warehouse
```

Post:

```text
Make image available to the Persona's post/publishing system
```

Publishing/posting is intentionally a separate later phase.

For the current work:

```text
Image Warehouse
      ↓
Selected image
      ↓
Marked for Post
```

Actual publishing can remain deferred.

---

# 20. PERSONA POST SECTION

Planned but NOT required for the first image-generation testing milestone.

Future structure:

```text
Persona
   ├── Profile
   ├── Visual Identity
   ├── Reference Images
   ├── Image Warehouse
   └── Posts
```

Posts will eventually contain images selected from the warehouse.

Do NOT expand this into social publishing/storyline/content-factory work unless explicitly requested.

---

# 21. ADMIN SLA

Admin must be able to inspect:

```text
generation count
success count
failure count
latency
p50
p95
p99
average
max
provider
model
persona
```

Important:

Image SLA must remain separate from text/LLM SLA.

---

# 22. ADMIN COST TRACKING

## REQUIRED AUDIT

Determine whether the current implementation can answer:

```text
How much did image generation cost?
```

Minimum desired dimensions:

```text
total image spend
spend by model
spend by provider
spend by persona
spend by date
cost per generation
cost per successful image
```

If the provider/model does not expose reliable cost data, clearly mark:

```text
UNKNOWN
```

Do not fabricate cost.

---

# 23. IMAGE ENGINE / SKILL CONFIGURATION

Admin should eventually be able to configure:

```text
image provider
image model
generation parameters
candidate count
image skill configuration
prompt configuration
```

The exact configuration surface must be audited against the existing engine/config architecture.

Do not create a parallel configuration system if the existing AI settings system can be extended.

---

# 24. ADMIN UI — REQUIRED PRODUCT SURFACE

The final Admin image area should provide at minimum:

```text
PERSONA
  ↓
Visual Identity
  ↓
Reference Images
  ↓
Image Warehouse
  ↓
Generate
  ↓
Candidates
  ↓
Review
  ↓
Shortlist / Save / Decline
  ↓
Regenerate
  ↓
Remarks
```

Separate admin area:

```text
Image Operations
  ├── Jobs
  ├── SLA
  ├── Cost
  ├── Provider
  ├── Model
  └── Storage Health
```

---

# 25. WHAT IS ALREADY COMPLETE

Based on previous implementation reports:

| Area                       | Current status |
| -------------------------- | -------------- |
| Image jobs                 | IMPLEMENTED    |
| Job state machine          | IMPLEMENTED    |
| Worker                     | IMPLEMENTED    |
| Worker lease               | IMPLEMENTED    |
| Lease heartbeat            | IMPLEMENTED    |
| Retry/recovery             | IMPLEMENTED    |
| Durable local storage      | IMPLEMENTED    |
| Shared storage contract    | IMPLEMENTED    |
| Asset retrieval            | IMPLEMENTED    |
| Image APIs                 | IMPLEMENTED    |
| Admin image generation API | IMPLEMENTED    |
| Admin job operations       | IMPLEMENTED    |
| Image observability        | IMPLEMENTED    |
| Image SLA                  | IMPLEMENTED    |
| Image model evaluation     | IMPLEMENTED    |
| Retention                  | IMPLEMENTED    |
| Chat attachment            | IMPLEMENTED    |
| End-user ownership APIs    | IMPLEMENTED    |
| Security tests             | IMPLEMENTED    |
| Automated image tests      | IMPLEMENTED    |

---

# 26. WHAT NEEDS PRODUCT-LEVEL VERIFICATION

These areas must be checked against the final product requirement:

| Area                                      | Status           |
| ----------------------------------------- | ---------------- |
| Persona visual attributes                 | AUDIT            |
| Reference image management                | AUDIT / EXTEND   |
| Standard reference categories             | IMPLEMENT/VERIFY |
| Reference image injection into generation | VERIFY           |
| Seed prompt UX                            | VERIFY           |
| 4 candidate generation                    | VERIFY           |
| Candidate warehouse                       | IMPLEMENT/EXTEND |
| Candidate status                          | IMPLEMENT/EXTEND |
| Candidate remarks                         | IMPLEMENT/EXTEND |
| Regeneration with correction              | IMPLEMENT/EXTEND |
| Generation history                        | IMPLEMENT/EXTEND |
| Model selection                           | AUDIT            |
| OpenRouter model configuration            | AUDIT            |
| Cost tracking                             | AUDIT            |
| Admin image SLA UI                        | AUDIT            |
| Image engine/skill configuration          | AUDIT            |
| Identity consistency evaluation           | PLAN/IMPLEMENT   |
| Persona Post section                      | DEFER            |

---

# 27. LIVE VERIFICATION STATUS

Previous verification established:

```text
Fake provider + H2
        PASS
```

Still requiring operational verification where credentials/environment are available:

```text
OpenRouter live generation
Real PostgreSQL
Two-process worker
Shared storage in real environment
Admin UI against real data
Real image SLA
Real provider cost
```

Do not claim these are verified until actually executed.

---

# 28. CURRENT TESTING PRINCIPLE

Every feature added during this program must have:

### Backend

* implementation
* persistence
* API
* validation
* error handling

### Admin

* UI
* API integration
* loading state
* empty state
* failure state
* successful state

### Test

At minimum:

```text
happy path
invalid input
permission/security
persistence
failure path
```

Where applicable:

```text
concurrency
retry
idempotency
multi-instance
```

---

# 29. CURSOR UPDATE REQUIREMENT

At the end of EVERY image-pipeline task, update this file with:

```text
## TASK UPDATE — [TASK NAME]

Date:
Commit:

Implemented:
- ...

Admin UI:
- ...

Backend:
- ...

Database:
- ...

API:
- ...

Tests:
- ...

Tests passed:
- ...

Tests failed:
- ...

Live verification:
- ...

Not verified:
- ...

Known gaps:
- ...

Files changed:
- ...

Next recommended task:
- ...
```

Do not simply say "implemented".

Explain exactly what exists.

---

# 30. DO NOT MARK COMPLETE IF

The task must remain incomplete if:

* backend exists but Admin UI cannot operate it
* Admin UI exists but does not persist changes
* API exists but is not connected to the real worker
* fake data is displayed as real data
* metrics are fabricated
* provider was not actually called
* OpenRouter was not tested but is claimed to work
* image candidate exists only in memory
* generated image cannot be retrieved after restart
* persona identity is not actually passed to generation
* reference images are stored but never used
* regeneration overwrites the original candidate
* remarks disappear after refresh
* access control is missing
* failures are swallowed without diagnosis

---

# 31. DEFINITION OF IMAGE PIPELINE PRODUCT-READY

The image pipeline should eventually support this complete flow:

```text
ADMIN
  │
  ▼
PERSONA
  │
  ├── Physical Visual Identity
  │
  ├── Reference Images
  │      ├── Front
  │      ├── Face Close
  │      ├── Left
  │      ├── Right
  │      └── Back
  │
  ▼
IMAGE GENERATION
  │
  ├── Seed Prompt
  ├── Persona Identity
  ├── Reference Images
  ├── Model
  └── Generation Settings
  │
  ▼
4 CANDIDATES
  │
  ├── Candidate 1
  ├── Candidate 2
  ├── Candidate 3
  └── Candidate 4
  │
  ▼
ADMIN REVIEW
  │
  ├── Select
  ├── Remark
  ├── Regenerate
  ├── Save
  ├── Decline
  └── Mark for Post
  │
  ▼
IMAGE WAREHOUSE
  │
  ├── Metadata
  ├── Prompt
  ├── Model
  ├── Cost
  ├── SLA
  └── History
```

---

# 32. IMMEDIATE NEXT ACTION

## Task log (2026-09-23)

| Task | Verdict | Commit |
| --- | --- | --- |
| Task 24 Persona Visual Identity & Reference Images | PASS WITH FINDINGS | f7fcf9e |
| 23-03 Image Generation + Candidate lifecycle (seed/4/UI/status/regen/seed fixtures) | PASS WITH FINDINGS | b1d28e4 |
| 23-04 Image Warehouse tab + API | PASS WITH FINDINGS | 13e4365 |
| 23-05 Image Cost / SLA Admin | PASS WITH FINDINGS | b2880c8 |
| 12 Image Model Evaluation | PASS WITH FINDINGS | (this commit) |

Next queued MD files under `docs/23 sept/`:

* `13_IMAGE_IDENTITY_CONSISTENCY_REFERENCE_MATCHING.md` ← next
* `14_IMAGE_GENERATION_ENGINE_SKILL_CONFIGURATION.md`
* `15_IMAGE_WAREHOUSE_CANDIDATE_MANAGEMENT.md`
* `Image Model & Provider Configuration.md`

Before implementing new functionality beyond the current task:

**Cursor must audit the current repository against this document.**

Do not assume that the previous reports are still accurate.

For every section above, classify it as:

```text
EXISTS AND VERIFIED
EXISTS BUT INCOMPLETE
EXISTS BUT NOT VERIFIED
MISSING
DEFERRED
```

Then produce:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with the actual repository evidence.

Only after this status is updated should the next feature task begin.

---

# 33. FINAL RULE

This document is a **living implementation ledger**, not a design proposal.

The repository is the source of truth.

If this document and the code disagree:

```text
CODE → VERIFY → UPDATE THIS DOCUMENT
```

Never report implementation based only on a previous agent's claim.

```
```
