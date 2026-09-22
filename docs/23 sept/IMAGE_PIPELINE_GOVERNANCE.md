
````markdown
# IMAGE PIPELINE — GOVERNANCE

**Date:** 2026-09-23  
**Workstream:** Image Pipeline  
**Owner:** Product / Engineering  
**Execution agent:** Cursor  
**Status:** ACTIVE

---

# 1. PURPOSE

This document governs the entire Image Pipeline implementation workstream.

Every Image Pipeline task executed after this document MUST follow the rules defined here.

The objective is not merely to have image-generation backend infrastructure.

The objective is to have a complete, testable product capability:

```text
Persona
   ↓
Visual Identity
   ↓
Reference Images
   ↓
Seed Prompt
   ↓
Image Generation
   ↓
Candidates
   ↓
Admin Review
   ↓
Correction / Regeneration
   ↓
Image Warehouse
   ↓
Optional Chat Delivery
   ↓
SLA / Cost / Operations
````

The implementation must be usable through the Admin UI and must be backed by real backend functionality.

---

# 2. SOURCE OF TRUTH

The Image Pipeline workstream uses three categories of documents.

## 2.1 Governance

This file:

```text
IMAGE_PIPELINE_GOVERNANCE.md
```

Defines:

* Rules
* Engineering standards
* Definition of done
* Testing requirements
* Scope boundaries
* Model/provider requirements
* Documentation requirements

Governance rules apply to every task.

---

## 2.2 Implementation Status

The living implementation status file:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

defines what is actually implemented.

Cursor MUST update this file after every task.

It must never become a design document.

It must reflect the actual repository state.

---

## 2.3 Task Instructions

Each individual task will have its own MD file.

Example:

```text
IMAGE_PIPELINE_TASK_01_PERSONA_VISUAL_IDENTITY.md
IMAGE_PIPELINE_TASK_02_REFERENCE_IMAGES.md
IMAGE_PIPELINE_TASK_03_GENERATION_INPUT.md
...
```

A task file defines what Cursor must implement and verify.

---

# 3. MANDATORY WORKFLOW

For every task Cursor MUST follow:

```text
READ GOVERNANCE
      ↓
READ CURRENT STATUS
      ↓
INSPECT EXISTING IMPLEMENTATION
      ↓
DEFINE CURRENT STATE
      ↓
DEFINE TARGET STATE
      ↓
IMPLEMENT
      ↓
TEST BACKEND
      ↓
TEST API
      ↓
TEST ADMIN UI
      ↓
VERIFY END-TO-END BEHAVIOR
      ↓
UPDATE STATUS
      ↓
COMMIT
      ↓
MOVE TO NEXT TASK
```

Do not skip directly from task description to coding.

---

# 4. VERTICAL-SLICE RULE

## Every feature task must be complete and testable.

Do NOT divide implementation into:

```text
Task A = backend
Task B = API
Task C = Admin UI
```

Instead:

```text
Task A = complete product capability
```

For example:

### Persona Visual Identity

One task must cover, where applicable:

```text
Database
   ↓
Domain
   ↓
Service
   ↓
API
   ↓
Admin UI
   ↓
Persistence
   ↓
Validation
   ↓
Tests
```

The feature is not complete until the entire path works.

---

# 5. DEFINITION OF DONE

A task is considered DONE only when all applicable layers are complete.

```text
[ ] Backend implemented
[ ] Database implemented
[ ] API implemented
[ ] Admin UI implemented
[ ] Runtime wiring complete
[ ] Validation implemented
[ ] Persistence verified
[ ] Automated tests pass
[ ] Admin workflow manually verified
[ ] End-to-end behavior verified
[ ] Existing functionality not regressed
[ ] Implementation status updated
[ ] Git commit created
```

If an item does not apply, explicitly write:

```text
N/A — <reason>
```

Never silently skip it.

---

# 6. "IMPLEMENTED" DEFINITION

A capability may only be marked:

```text
IMPLEMENTED
```

when the actual runtime behavior exists.

The following are NOT sufficient:

* Documentation exists
* Backend class exists
* Database table exists
* API route exists
* UI placeholder exists
* Test fixture exists
* Code compiles

Example:

```text
Backend exists
API exists
Admin UI missing
```

Status:

```text
PARTIAL
```

Not:

```text
IMPLEMENTED
```

---

# 7. CURRENT STATE FIRST

Before modifying a feature, Cursor MUST document:

## CURRENT STATE

Explain:

* What exists
* Where it exists
* How it currently works
* What UI exists
* What API exists
* What database entities exist
* What tests exist
* What is missing

Use actual repository evidence.

---

## TARGET STATE

Then explain:

* What this task is expected to achieve
* Which user/admin workflow should become possible
* Which backend/API/UI pieces are required

Only then begin implementation.

---

# 8. EVIDENCE RULE

Every implementation claim must be supported by repository evidence.

Record:

* File path
* Class
* Function
* Route
* Database migration/entity
* Admin UI component
* Test name

Example:

```text
Implemented:
Admin Persona Visual Identity editing

Evidence:
src/main/kotlin/.../PersonaRoutes.kt
src/main/kotlin/.../AdminPersonaRoutes.kt
src/main/resources/admin-ui.html
PersonaVisualIdentityRepository.kt
PersonaVisualIdentityAdminTest
```

Do not claim functionality based only on documentation.

---

# 9. ADMIN UI IS PART OF THE PRODUCT

The Image Pipeline is not complete if only the backend works.

Where a feature is intended for Admin operation, the task MUST include the Admin UI.

The Admin must be able to perform the actual workflow without manually calling APIs.

For example:

```text
Admin
  ↓
Persona
  ↓
Visual Identity
  ↓
Edit
  ↓
Save
  ↓
Generate
  ↓
Review candidates
  ↓
Remark
  ↓
Regenerate
```

If the backend supports something but Admin UI does not expose it:

```text
PARTIAL
```

---

# 10. TESTING REQUIREMENT

Every task must have tests appropriate to the feature.

At minimum, where applicable:

## Unit

Test business rules.

## Integration

Test database/API interaction.

## Admin/UI

Verify the actual Admin workflow.

## End-to-end

Verify:

```text
Input
 ↓
Backend
 ↓
Persistence
 ↓
API
 ↓
Admin UI
```

A passing unit test does not automatically prove the Admin feature works.

---

# 11. REAL RUNTIME VERIFICATION

When a task changes a runtime path, verify the real path.

Do not rely exclusively on mocks if the real path can be safely exercised.

Clearly distinguish:

```text
AUTOMATED TEST
LIVE LOCAL VERIFICATION
REAL PROVIDER VERIFICATION
NOT VERIFIED
```

Never represent one as another.

---

# 12. FAILURE REPORTING

If something cannot be tested, write:

```text
NOT VERIFIED
```

and explain why.

Examples:

```text
NOT VERIFIED — OpenRouter credentials unavailable
```

or:

```text
NOT VERIFIED — two-process Postgres environment unavailable
```

Never fabricate a successful result.

---

# 13. MODEL / PROVIDER REQUIREMENT

The image pipeline will use an image-generation model available through:

```text
OpenRouter
```

The model must be configurable.

Do NOT hard-code the architecture around one permanent model.

The system must allow us to evaluate multiple candidate image models.

---

# 14. MODEL SELECTION PRINCIPLE

The product requirement is to evaluate models that provide broad image-generation capability suitable for our fictional persona use cases without unnecessary model-specific restrictions.

This does NOT mean bypassing platform/provider safety controls.

The model evaluation task must test multiple OpenRouter-accessible candidates.

At minimum evaluate:

```text
Model
Provider
Generation capability
Reference-image support
Identity consistency
Prompt adherence
Quality
Latency
Failure behavior
Cost
```

Do not declare a model "best" without actual comparative evidence.

---

# 15. IMAGE GENERATION INPUT CONTRACT

The intended generation input is:

```text
Persona
+
Visual Identity
+
Reference Images
+
Seed Prompt
+
Generation Configuration
```

Example:

```text
Generate an image of Simran swimming
in a yellow bikini at a Goa beach,
early morning, facing camera.
```

The generation system must preserve persistent persona identity while applying the requested scene.

---

# 16. REFERENCE IMAGE PRINCIPLE

Reference images are a core part of persona visual consistency.

The intended standard reference set is:

```text
1. Front / full-body
2. Face close-up
3. Left profile
4. Right profile
5. Back profile
```

The architecture should allow additional reference-image categories later.

Reference images must be associated with the correct persona.

They must be durable and accessible to the generation pipeline.

---

# 17. VISUAL IDENTITY PRINCIPLE

Persona visual identity may include persistent characteristics such as:

* Height
* Weight
* Body build
* Muscularity
* Skin/complexion
* Face characteristics
* Eye color
* Hair color
* Hair style
* Hair length
* Body proportions
* Birthmarks
* Moles
* Scars
* Tattoos
* Other persistent visual characteristics

Where the product requires representation of intimate/private visual information, it must be treated as a separate protected category.

Optional private reference images must not automatically become ordinary persona reference images.

---

# 18. IMAGE CANDIDATE PRINCIPLE

The intended generation workflow is:

```text
One generation request
        ↓
Four candidates
```

Candidates must be individually addressable.

Each candidate should have, where applicable:

* Candidate ID
* Persona
* Image asset
* Generation job
* Seed prompt
* Generation timestamp
* Model
* Provider
* Status
* Admin remark

---

# 19. ADMIN REVIEW PRINCIPLE

Admin should eventually be able to:

```text
View candidate
Select / shortlist
Reject / decline
Add remark
Save
Regenerate
```

Regeneration with correction should preserve history.

Example:

```text
Candidate #2
      ↓
Admin correction:
"Face does not sufficiently match the reference."
      ↓
Regenerate
      ↓
New candidate
```

Do not overwrite the original candidate unless explicitly required by the task.

---

# 20. IMAGE WAREHOUSE PRINCIPLE

Generated images belong to the Persona's Image Warehouse.

The intended conceptual structure is:

```text
Persona
│
├── Profile
│
├── Visual Identity
│
├── Reference Images
│
├── Image Warehouse
│    ├── Candidate
│    ├── Candidate
│    ├── Candidate
│    └── ...
│
└── Posts
     └── DEFERRED
```

The Image Warehouse should preserve generation history.

---

# 21. POSTS ARE SEPARATE

Publishing/post management is NOT part of the immediate Image Pipeline completion target.

Do not expand current tasks into:

* Instagram publishing
* Social publishing
* Automated posting
* Content factory
* Storyline automation

Posts may be implemented later.

If existing post functionality is discovered, document it.

Do not unnecessarily expand scope.

---

# 22. SLA REQUIREMENT

Image SLA must be measurable separately from text/LLM SLA.

Where applicable track:

```text
Queue latency
Generation latency
Provider latency
Storage latency
Total job latency
Success rate
Failure rate
Retry count
```

Admin must eventually have visibility into these metrics.

Do not mix image metrics into unrelated LLM SLA calculations.

---

# 23. COST REQUIREMENT

Image generation cost must eventually be measurable.

Track where available:

```text
Provider
Model
Generation count
Input/output information
Provider usage
Provider-reported cost
Estimated cost
Cost per job
Cost per candidate
Aggregate cost
```

Always distinguish:

```text
ACTUAL
ESTIMATED
UNAVAILABLE
```

Never fabricate provider pricing.

---

# 24. ENGINE / SKILL CONFIGURATION

The Image Pipeline should eventually allow Admin to understand and manage image-generation configuration.

This may include:

* Image engine
* Provider
* Model
* Generation settings
* Image skill
* Prompt configuration
* Version
* Enabled/disabled state

Do not implement this prematurely if it is not part of the current task.

Document existing capability and gaps.

---

# 25. SECURITY PRINCIPLE

Image-specific security must be verified.

At minimum consider:

* Admin authorization
* User authorization
* Persona ownership
* Image ownership
* Cross-user access
* Cross-persona access
* Reference-image protection
* Private reference-image protection
* Storage path traversal
* Provider credential protection
* Secret redaction

Sensitive/private persona data must not leak through ordinary APIs.

---

# 26. DATA PRIVACY PRINCIPLE

Private visual metadata and private reference images must be treated differently from ordinary public/generated images.

Do not expose them through:

* Public persona endpoints
* Ordinary user APIs
* Image warehouse APIs intended for normal generated assets
* Logs
* Error messages
* Observability payloads

Any uncertainty must be documented for review.

---

# 27. NO FAKE UI

Do not build UI elements that imply functionality that the backend does not actually support.

Examples:

```text
Fake SLA numbers
Fake cost numbers
Fake generation status
Fake storage status
Fake model selection
```

If backend support does not exist:

```text
show unavailable / disabled
```

or leave the feature out until it is actually wired.

---

# 28. NO DEAD BACKEND FEATURES

Conversely, do not declare a product feature complete simply because an API exists.

If Admin cannot actually use it where Admin usage is required:

```text
PARTIAL
```

---

# 29. NO UNNECESSARY ARCHITECTURAL REWRITE

Prefer:

```text
REUSE
EXTEND
WIRE
HARDEN
```

before:

```text
REPLACE
REDESIGN
REWRITE
```

Existing working image infrastructure should be reused unless there is concrete evidence that it cannot satisfy the product requirement.

---

# 30. NO SCOPE CREEP

Do not introduce unrelated work while completing Image Pipeline tasks.

Explicitly defer:

* Storyline automation
* Content factory
* Automated social publishing
* LoRA training
* Unrelated persona engine changes
* Unrelated conversation engine changes
* Unrelated memory engine changes

If a dependency is discovered, document it rather than silently expanding scope.

---

# 31. GIT RULES

Every completed task should produce a dedicated commit.

Commit message should identify the task.

Example:

```text
image: implement persona visual identity admin workflow
```

Do NOT push automatically unless explicitly instructed.

The branch and merge strategy will be provided separately.

---

# 32. STATUS UPDATE REQUIREMENT

After every task, update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

The update MUST contain:

```text
Task
Date
Status
Current state
What was implemented
Backend changes
Database changes
API changes
Admin UI changes
Tests
Manual verification
Known gaps
Known defects
Files changed
Commit
Push status
Next task
```

---

# 33. TASK VERDICT

Every task must end with exactly one:

```text
PASS
PASS WITH FINDINGS
BLOCKED
FAIL
```

### PASS

Complete and verified.

### PASS WITH FINDINGS

Core functionality works but documented non-blocking gaps remain.

### BLOCKED

Cannot complete because of an external dependency.

### FAIL

Implementation does not satisfy the task.

---

# 34. DO NOT HIDE FINDINGS

A task must not be marked PASS if important functionality remains unverified.

Examples:

```text
Backend PASS
Admin UI NOT VERIFIED
```

Overall:

```text
PASS WITH FINDINGS
```

not:

```text
PASS
```

---

# 35. MORNING REVIEW REQUIREMENT

The purpose of this workstream is to have a clean reviewable state by the morning.

The final review will use:

```text
IMAGE_PIPELINE_GOVERNANCE.md
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
Individual task documents
Git history
Automated test results
Live test results
Admin UI
```

The morning review should answer:

1. What actually works?
2. What does not work?
3. What is only partially implemented?
4. Can Admin configure a Persona's visual identity?
5. Can Admin upload/manage reference images?
6. Can we generate four candidates?
7. Can Admin review candidates?
8. Can Admin correct and regenerate?
9. Are generated images stored correctly?
10. Can we compare image models?
11. Can we measure SLA?
12. Can we measure cost?
13. Can users request images?
14. Is image identity consistent?
15. What remains before production?

---

# 36. FINAL GOVERNING PRINCIPLE

The goal is not:

> "Cursor completed the code."

The goal is:

> **"The product capability exists, is wired end-to-end, is usable from Admin, and has been tested."**

Every task must move the system toward that state.

```text
IMPLEMENT
   ↓
WIRE
   ↓
TEST
   ↓
VERIFY
   ↓
DOCUMENT
   ↓
COMMIT
   ↓
NEXT TASK
```

No task should be considered complete without following this cycle.

```

```
