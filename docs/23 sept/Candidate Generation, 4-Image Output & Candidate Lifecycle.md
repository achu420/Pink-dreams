# IMAGE PIPELINE — TASK 05

## Candidate Generation, 4-Image Output & Candidate Lifecycle

**Date:** 2026-09-23
**Status:** READY FOR CURSOR IMPLEMENTATION
**Depends on:**

* `IMAGE_PIPELINE_GOVERNANCE.md`
* `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
* Persona Visual Identity task
* `IMAGE_TASK_04_MODEL_PROVIDER_STATUS.md`

---

## 1. Purpose

Make the **candidate-generation workflow** complete and testable from Admin.

The required product behavior is:

```text
Admin/User requests image
        ↓
Persona visual identity resolved
        ↓
Seed prompt supplied
        ↓
Image model generates candidates
        ↓
4 candidates displayed
        ↓
Admin evaluates each candidate
        ↓
Admin can:
  - shortlist
  - decline
  - remark
  - regenerate
  - save
  - eventually post
```

This task is primarily about the **candidate layer and Admin workflow**.

Do not implement publishing in this task.

---

# 2. Product Requirement

A single image-generation request should produce up to **4 independent candidates**.

Example:

```text
Persona: Simran Kohli

Seed:
"Simran swimming in a yellow bikini
at a Goa beach, early morning,
facing the camera."
```

The system sends the generation request to the configured image model and produces:

```text
Candidate 1
Candidate 2
Candidate 3
Candidate 4
```

Each candidate must be independently manageable.

---

# 3. Candidate Data

Every generated candidate should have, at minimum:

```text
candidateId
jobId
personaId
createdAt
status
assetId / asset reference
prompt/seed reference
model
provider
adminRemark
```

Where already supported by the existing schema, reuse existing fields rather than creating duplicates.

---

# 4. Candidate Status

Implement a clear candidate lifecycle.

Required statuses:

```text
GENERATED
SHORTLISTED
DECLINED
SAVED
```

If the existing implementation uses different names, document the existing terminology and map it cleanly rather than creating duplicate concepts.

### Important

`POSTED` should **not** be implemented as part of this task unless the existing system already requires it.

Publishing is a separate phase.

---

# 5. Status Semantics

### GENERATED

Image was successfully generated and is awaiting Admin decision.

### SHORTLISTED

Admin wants to retain the image as a preferred candidate.

### DECLINED

Admin explicitly rejected the candidate.

### SAVED

Admin explicitly wants to retain the image in the persona's image warehouse.

If the product architecture determines that `SHORTLISTED` and `SAVED` are actually the same state, do not blindly duplicate states.

Instead, document the distinction and ask for clarification only if the existing model makes it impossible to represent the desired workflow.

---

# 6. Image Warehouse

Each persona needs an image warehouse.

Conceptually:

```text
Persona
 ├── Visual Identity
 ├── Reference Images
 ├── Image Warehouse
 │     ├── Image A
 │     ├── Image B
 │     ├── Image C
 │     └── ...
 └── Posts
```

For this task implement the **Image Warehouse candidate view**.

Publishing remains separate.

---

# 7. Admin Persona → Image Warehouse

Inside the Persona Admin experience, provide access to:

```text
Persona
   ↓
Images / Image Warehouse
```

The Admin should be able to see generated images belonging to that persona.

Each image should show:

```text
thumbnail
date/time
status
model
seed prompt
admin remark
```

Do not show raw provider secrets.

---

# 8. Generation History

The Admin must be able to distinguish separate generation jobs.

Example:

```text
Generation #104
2026-09-23 09:41
Model: <configured model>
Prompt: Swimming in yellow bikini...
Candidates: 4
```

Opening the generation should display its candidates.

---

# 9. Candidate Grid

The Admin UI should display the candidates as a visual grid.

Example:

```text
┌─────────────┐ ┌─────────────┐
│ Candidate 1 │ │ Candidate 2 │
│             │ │             │
│    IMAGE    │ │    IMAGE    │
│             │ │             │
└─────────────┘ └─────────────┘

┌─────────────┐ ┌─────────────┐
│ Candidate 3 │ │ Candidate 4 │
│             │ │             │
│    IMAGE    │ │    IMAGE    │
│             │ │             │
└─────────────┘ └─────────────┘
```

Each candidate must have its own controls.

---

# 10. Candidate Actions

For every candidate Admin should be able to:

```text
Shortlist
Decline
Save
Add/Edit Remark
```

If an action is not implemented by the current backend, implement the backend first.

Do not create UI buttons that do nothing.

---

# 11. Remarks

Admin must be able to attach a remark to an individual candidate.

Example:

```text
Candidate 2

Remark:
"Face looks good but hair is wrong.
Regenerate using longer hair."
```

The remark must persist.

Refreshing the page must not lose it.

---

# 12. Regeneration

The product requirement includes:

> Ask the model to regenerate after giving correction on one image.

Implement the backend contract needed for this workflow.

Expected flow:

```text
Candidate 2
     ↓
Admin enters correction
     ↓
Regenerate
     ↓
new generation request
     ↓
new candidate(s)
```

### Important

Do not overwrite the original candidate.

The original must remain available for history/audit.

Preferred model:

```text
Original Candidate
      ↓
Regeneration Request
      ↓
New Candidate / New Generation Job
```

The relationship should be recorded if the existing architecture supports it.

For example:

```text
parentCandidateId
```

or equivalent.

---

# 13. Regeneration Prompt

The system must distinguish:

### Original seed

```text
Simran swimming in a yellow bikini...
```

from:

### Correction

```text
Keep the same face and body.
Change the hairstyle to long black hair.
```

The resulting generation should preserve the original generation context and apply the correction.

Do not replace the original seed with only the correction.

Expected conceptual prompt:

```text
PERSONA VISUAL IDENTITY
+
ORIGINAL SEED
+
ADMIN CORRECTION
```

The exact implementation should continue using the existing `PromptCompiler`.

---

# 14. Reference Images

Candidate generation must continue to use the persona's configured reference-image system.

The candidate workflow must not bypass:

```text
Persona
 ↓
Visual Identity
 ↓
Reference Images
 ↓
PromptCompiler
 ↓
Image Model
```

This is critical for visual consistency.

---

# 15. Candidate Identity Consistency

Do not implement an automatic image similarity score in this task.

However, the architecture should preserve the metadata necessary for future validation:

```text
personaId
reference-image set/version
generation model
prompt
generation timestamp
```

Future work can compare generated candidates against the reference identity.

---

# 16. Candidate Metadata

Every candidate must allow us to answer:

> Where did this image come from?

At minimum:

```text
Persona
Generation Job
Model
Provider
Seed Prompt
Creation Time
Reference Identity Version
Status
Admin Remark
```

If a field cannot currently be persisted, document the gap rather than fabricating it.

---

# 17. Admin Filters

If practical within the existing Admin UI, support:

```text
Status
Persona
Date
Model
```

Do not build a large search system for this task.

At minimum, the Admin must be able to inspect all candidates for a persona.

---

# 18. Candidate Persistence

A generated image must not disappear when:

* Admin refreshes the browser.
* Server restarts.
* Worker restarts.
* Another worker processes a job.

The candidate metadata must be durable.

The image asset must use the existing durable storage implementation.

---

# 19. Security

Admin candidate operations must enforce the existing Admin authentication.

Verify:

* unauthenticated request → rejected
* non-admin request → rejected
* Admin → allowed

Do not expose candidates through an unprotected endpoint.

If user-owned image endpoints already exist, do not weaken their ownership checks.

---

# 20. API Requirements

Reuse existing image APIs where possible.

The final API should support equivalent operations for:

```text
List candidates
Get candidate
Update candidate status
Update candidate remark
Request regeneration
```

Do not create duplicate endpoints if the existing image-job API can be extended cleanly.

Document the actual endpoints implemented.

---

# 21. Four-Candidate Requirement

This needs explicit testing.

### Test case

Request one generation.

Expected:

```text
1 job
4 successful candidates
```

If the provider returns fewer than four images:

```text
actual count must be reported
```

Never fabricate four records pointing to the same image.

---

# 22. Partial Generation

Test:

```text
provider attempts 4
candidate 1 succeeds
candidate 2 succeeds
candidate 3 fails
candidate 4 succeeds
```

The system must not falsely report:

```text
4 successful candidates
```

It should report the actual result.

If the existing provider treats the whole generation as atomic, document that behavior.

---

# 23. Candidate Selection

Admin should be able to shortlist **one or more** candidates.

Example:

```text
Candidate 1 → SHORTLISTED
Candidate 2 → DECLINED
Candidate 3 → SHORTLISTED
Candidate 4 → GENERATED
```

Do not force only one winner.

---

# 24. Image Warehouse vs Post

Keep these concepts separate:

```text
IMAGE WAREHOUSE
    ↓
stored/generated images
```

versus:

```text
POSTS
    ↓
images intentionally published to users
```

This task only implements the warehouse side.

Do not accidentally treat every generated image as a post.

---

# 25. Testing Requirements

### Backend tests

* [ ] Four candidates generated.
* [ ] Candidate persistence.
* [ ] Candidate status update.
* [ ] Candidate remark persistence.
* [ ] Multiple candidates can be shortlisted.
* [ ] Candidate decline works.
* [ ] Regeneration creates a new job/candidate.
* [ ] Original candidate remains intact.
* [ ] Persona association is preserved.
* [ ] Model/provider metadata preserved.
* [ ] Security/authorization tests.
* [ ] Partial generation behavior tested.
* [ ] Existing imaging tests remain green.

### Admin tests

Verify:

```text
Persona
 ↓
Image Warehouse
 ↓
Generation
 ↓
4 candidates
 ↓
Shortlist
 ↓
Remark
 ↓
Save
 ↓
Refresh
 ↓
Data remains
```

---

# 26. Manual Acceptance Test

Cursor must provide an exact test procedure.

Minimum:

```text
1. Open Admin.
2. Open a test persona.
3. Open Image Warehouse.
4. Generate an image using a seed prompt.
5. Verify a generation job is created.
6. Verify up to 4 candidates appear.
7. Open each candidate.
8. Verify model/provider metadata.
9. Add a remark to one candidate.
10. Refresh.
11. Verify remark remains.
12. Shortlist two candidates.
13. Verify both remain shortlisted.
14. Decline another candidate.
15. Verify status persists.
16. Request regeneration with a correction.
17. Verify a new generation is created.
18. Verify original candidate remains.
19. Verify new candidate contains generation lineage where implemented.
```

---

# 27. Required Cursor Status File

Create:

```text
docs/23 sept/IMAGE_TASK_05_CANDIDATE_WORKFLOW_STATUS.md
```

Use this exact structure:

```markdown
# Image Task 05 — Candidate Workflow Status

## 1. What already existed

## 2. What I changed

## 3. Candidate data model

## 4. Candidate statuses

## 5. Image Warehouse

## 6. Admin UI

## 7. Candidate actions

## 8. Remarks

## 9. Regeneration

## 10. Reference-image integration

## 11. APIs

## 12. Security

## 13. Tests

## 14. Manual test procedure

## 15. Test results

## 16. Files changed

## 17. Known gaps

## 18. NOT implemented

## 19. Git commit
```

Every section must describe the **actual implementation**, not the intended architecture.

---

# 28. Stop Condition

After this task:

**STOP.**

Do not implement:

* post publishing
* automatic visual scoring
* image feed
* social publishing
* storyline
* daily generation
* LoRA
* advanced image editing

Those will be separate tasks.

The objective is to leave us with a **complete, persistent, Admin-testable candidate workflow** so that tomorrow we can actually generate images and evaluate them persona-by-persona.
