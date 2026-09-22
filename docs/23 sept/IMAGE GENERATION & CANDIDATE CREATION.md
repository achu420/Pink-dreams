

````md
# 23-03 — IMAGE GENERATION & CANDIDATE CREATION

## Purpose

Implement the complete, testable image-generation workflow:

Admin/User request
→ seed prompt
→ resolve persona visual identity
→ compile generation prompt
→ call OpenRouter image model
→ generate 4 candidates
→ persist all candidates
→ display them in Admin UI
→ allow admin to inspect, select, remark, regenerate, save, or decline.

This task must include BOTH:

1. Backend implementation
2. Admin UI implementation

Do not mark this task complete if only the API/backend exists.

---

# 1. GOVERNANCE

This task is governed by:

`23-00_IMAGE_PIPELINE_GOVERNANCE.md`

The governance document is authoritative for:

- scope
- implementation order
- model/provider requirements
- testing rules
- documentation requirements
- definition of done

Do not redesign the existing image architecture unless the current implementation makes this task impossible.

Reuse existing image-pipeline infrastructure wherever possible.

---

# 2. EXISTING IMPLEMENTATION

Before changing code, inspect:

- current ImageGenerationService
- ImageJobRepository
- ImageJobWorker
- ImageGenerationHandler
- PromptCompiler
- Persona visual identity implementation
- ObjectStorage
- generated_candidates
- image_generation_events
- existing Admin Image UI
- existing image APIs
- current OpenRouter provider implementation

Do not recreate components that already exist.

At the beginning of this task update:

`docs/23 sept/23-03_IMAGE_GENERATION_IMPLEMENTATION.md`

with:

```text
Existing:
Implemented:
Partially implemented:
Missing:
Reused:
Changed:
````

---

# 3. MODEL REQUIREMENT

Image generation must use an OpenRouter-accessible image model.

The model configuration must NOT be hardcoded into the UI.

The effective image model must come from the existing image-generation configuration mechanism.

Required configuration visibility:

* provider
* model
* generation settings
* image count
* relevant token/cost configuration if supported

The immediate testing goal is to allow us to evaluate image models through OpenRouter.

Do not silently substitute the existing text-generation model.

Do not claim that a model is unrestricted unless the provider/model behavior has actually been verified.

For tomorrow's testing, the system must support selecting/configuring an OpenRouter image model without changing application code.

---

# 4. GENERATION INPUT

The generation request must contain at minimum:

### Persona

```text
personaId
```

### Seed prompt

Free-form scene instruction supplied by the user/admin.

Example:

```text
Generate an image of the persona swimming in a yellow bikini
at a Goa beach in the early morning, facing the camera.
```

The seed prompt describes the requested scene.

It must NOT replace the persona's identity.

---

# 5. PERSONA IDENTITY INPUT

The image generation pipeline must resolve the current active visual identity for the persona.

The generation context should include the applicable:

### Physical identity

Examples:

* height
* body proportions
* build
* skin tone
* eye color
* hair color
* hairstyle
* facial characteristics
* distinguishing marks
* moles
* birthmarks
* muscularity
* other configured physical attributes

### Clothing/style information

Where configured:

* clothing preferences
* wardrobe characteristics
* style
* relevant appearance constraints

### Reference images

The active identity reference images must be available to the generation pipeline.

Reference images are authoritative for visual consistency.

Do not duplicate identity data into a separate image-specific persona system if the existing persona visual identity already provides it.

---

# 6. REFERENCE IMAGE TYPES

The system should support identity references with explicit labels.

Recommended standard:

```text
FACE_CLOSE
FRONT
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

Additional categories may exist, but these five should be supported.

Each uploaded reference must have:

* asset ID
* persona ID
* reference type
* active/inactive state
* created timestamp
* optional admin remark

Private/reference images must remain separately controlled from ordinary identity images.

Do not expose private reference assets through ordinary image result APIs.

---

# 7. PROMPT CONSTRUCTION

Use the existing `PromptCompiler`.

Do NOT create a second independent prompt-generation path.

The logical structure should be:

```text
PERSONA IDENTITY
        +
REFERENCE IMAGE CONTEXT
        +
SEED PROMPT
        +
IMAGE GENERATION CONFIGURATION
        ↓
COMPILED IMAGE PROMPT
```

Identity must remain authoritative.

The seed prompt controls the requested scene.

Example:

```text
Identity:
female persona with configured physical characteristics...

References:
front + face close + left profile + right profile + back

Scene:
swimming in a yellow bikini at Goa beach,
early morning,
facing camera
```

The system must preserve the distinction between:

* identity
* scene
* style
* generation instructions

---

# 8. GENERATION COUNT

The default generation request for this workflow is:

```text
4 candidates
```

The backend must create/persist all four results.

Do not overwrite candidate 1 with candidate 2 etc.

Each candidate must have a unique identity.

Example:

```text
job
 ├── candidate 1
 ├── candidate 2
 ├── candidate 3
 └── candidate 4
```

If the provider returns fewer than requested:

* record the actual result count
* record the failure/partial state
* do not fabricate missing candidates

---

# 9. CANDIDATE DATA

Each generated candidate should retain enough information for later evaluation.

At minimum:

```text
candidateId
jobId
personaId
assetId
status
createdAt
seedPrompt
effectiveModel
provider
generation metadata
admin remark
```

Candidate statuses should support the planned lifecycle.

Minimum statuses:

```text
GENERATED
SHORTLISTED
DECLINED
POSTED
```

Use the existing schema/status model if already present rather than creating duplicates.

---

# 10. ADMIN GENERATION UI

Add/complete an Admin UI workflow for generating images.

Admin should be able to:

1. Select persona
2. See that persona's current visual identity
3. Enter seed prompt
4. Submit generation request
5. See job status
6. See generated candidates
7. Inspect each candidate
8. Add admin remark
9. Shortlist candidate
10. Decline candidate
11. Save candidate
12. Request regeneration

The UI must use real backend state.

No fake/demo candidate data.

---

# 11. GENERATION FORM

Minimum UI:

```text
Persona
[ selected persona ]

Seed Prompt
[ multiline text area ]

Image Model
[ effective/configured model ]

Number of Candidates
[ 4 ]

[ Generate ]
```

The model field may be read-only if model configuration is controlled elsewhere.

Do not allow the UI to invent a model configuration that the backend ignores.

---

# 12. JOB PROGRESS

After clicking Generate:

```text
QUEUED
   ↓
RUNNING
   ↓
SUCCEEDED
```

or:

```text
QUEUED
   ↓
RUNNING
   ↓
FAILED
```

The UI must poll/use the existing status mechanism.

Do not block the HTTP request waiting for image generation if the existing architecture is asynchronous.

---

# 13. CANDIDATE GRID

After completion show all candidates.

Example:

```text
┌────────────┐ ┌────────────┐
│ Candidate 1│ │ Candidate 2│
│            │ │            │
│   IMAGE    │ │   IMAGE    │
│            │ │            │
│ [Select]   │ │ [Select]   │
│ [Decline]  │ │ [Decline]  │
└────────────┘ └────────────┘

┌────────────┐ ┌────────────┐
│ Candidate 3│ │ Candidate 4│
│            │ │            │
│   IMAGE    │ │   IMAGE    │
│            │ │            │
│ [Select]   │ │ [Select]   │
│ [Decline]  │ │ [Decline]  │
└────────────┘ └────────────┘
```

The UI must clearly distinguish candidate state.

---

# 14. ADMIN REMARK

Each candidate must support an admin remark.

Example:

```text
Candidate 2

Remark:
"Face is good but hair is wrong."

[Save Remark]
```

The remark must persist in backend storage.

Do not store remarks only in browser state.

---

# 15. SHORTLIST

Admin can select one or more candidates.

Example:

```text
Candidate 1   [Shortlist]
Candidate 2   [Decline]
Candidate 3   [Shortlist]
Candidate 4   [Decline]
```

Multiple candidates may be shortlisted.

Do not assume exactly one winner.

---

# 16. SAVE

"Save" means retain the generated asset in the persona's image warehouse.

The saved image must remain accessible independently of the generation job.

Do not delete the underlying asset when the job record expires.

The existing retention/reference rules must be respected.

---

# 17. IMAGE WAREHOUSE

Inside each persona there should eventually be an image warehouse.

For this task implement the minimum required data/API/UI needed to inspect generated candidates.

Each image should expose:

```text
thumbnail
created date/time
status
admin remark
seed prompt
model
provider
candidate/job reference
```

The full warehouse workflow may be completed in the subsequent task if necessary.

Do not expand this task into publishing/social posting.

---

# 18. REGENERATION

Admin must be able to request regeneration.

The regeneration request must preserve the original identity.

Example:

Original:

```text
Swimming in yellow bikini.
```

Admin correction:

```text
Face should match the reference more closely.
Hair should be shoulder length.
```

The new generation should be based on:

```text
same persona identity
+
same relevant reference images
+
original scene
+
admin correction
```

Do not mutate the original candidate.

Create a new generation/job/candidate lineage.

---

# 19. REGENERATION UI

Candidate:

```text
[Regenerate]
```

opens:

```text
Correction
[ Face should match the reference more closely... ]

[Regenerate]
```

The generated replacement must appear as a new candidate/result.

The original must remain auditable.

---

# 20. SECURITY

Verify:

* only authorized admin/users can create image jobs
* persona ownership/access rules are respected
* candidate assets cannot be accessed by another user without authorization
* private identity/reference images cannot leak through ordinary image APIs
* raw provider secrets are never stored in candidate metadata
* OpenRouter API key never appears in Admin UI
* seed prompts and remarks are escaped safely in HTML

Add tests.

---

# 21. OBSERVABILITY

Every generation must produce observable information using the existing image observability infrastructure.

At minimum capture:

```text
jobId
candidate count
provider
model
latency
outcome
error classification
```

Do not duplicate telemetry systems.

Generation failures must be diagnosable from Admin.

---

# 22. COST

If provider usage/cost information is available from the OpenRouter response, preserve it.

Do not fabricate cost.

If cost is not currently available:

```text
cost = UNKNOWN
```

rather than zero.

The later Admin cost dashboard will use this data.

---

# 23. TEST REQUIREMENTS

Implement tests for:

### Backend

* valid generation request
* missing persona
* missing seed prompt
* invalid persona
* identity resolution
* reference resolution
* prompt compilation
* four candidate persistence
* partial provider result
* provider failure
* candidate status update
* admin remark persistence
* shortlist
* decline
* regeneration
* candidate lineage
* authorization
* private reference protection

### Integration

At minimum:

```text
create request
→ job
→ worker
→ Fake provider
→ 4 candidates
→ assets
→ result API
→ Admin UI data
```

Use Fake provider for deterministic CI tests.

---

# 24. LIVE TEST PREPARATION

The implementation must make this workflow possible with OpenRouter:

```text
Admin
 ↓
Select persona
 ↓
Seed prompt
 ↓
Generate
 ↓
OpenRouter image model
 ↓
4 candidates
 ↓
Admin review
```

Do not claim live verification unless actually executed.

Live testing requires:

```text
OPENROUTER_API_KEY
```

and the configured image model.

---

# 25. ACCEPTANCE TEST

The task is complete only when the following can be demonstrated:

### Step 1

Admin opens a persona.

### Step 2

Admin sees the current visual identity.

### Step 3

Admin enters:

```text
Swimming in a yellow bikini
at a Goa beach in the early morning,
facing the camera.
```

### Step 4

Admin clicks:

```text
Generate
```

### Step 5

A real image job is created.

### Step 6

Worker processes the job.

### Step 7

Provider returns image candidates.

### Step 8

Four candidates are displayed.

### Step 9

Admin can:

```text
shortlist
decline
remark
save
```

### Step 10

Admin can request regeneration with a correction.

### Step 11

The new candidate retains the same persona identity.

### Step 12

Original and regenerated candidates remain auditable.

---

# 26. DEFINITION OF DONE

Do NOT mark complete merely because code compiles.

All must be true:

* [ ] Backend generation request works
* [ ] Admin generation UI works
* [ ] Persona identity is resolved
* [ ] Reference images are used
* [ ] Seed prompt is passed
* [ ] Existing PromptCompiler is used
* [ ] OpenRouter image model configuration works
* [ ] Four candidates are supported
* [ ] Candidates persist
* [ ] Candidate assets persist
* [ ] Candidate grid works
* [ ] Remarks persist
* [ ] Shortlist works
* [ ] Decline works
* [ ] Save works
* [ ] Regeneration works
* [ ] Regeneration preserves original candidate
* [ ] Authorization tested
* [ ] Private references protected
* [ ] Observability captured
* [ ] Cost is not fabricated
* [ ] Automated tests pass
* [ ] Admin UI manually tested
* [ ] Live OpenRouter test is either completed or explicitly marked NOT VERIFIED

---

# 27. REQUIRED STATUS UPDATE

At the end of the task update:

`docs/23 sept/23-03_IMAGE_GENERATION_IMPLEMENTATION.md`

with:

## Implementation Status

```text
PASS
PASS WITH FINDINGS
BLOCKED
```

## Backend

* implemented:
* reused:
* changed:
* missing:

## Admin UI

* implemented:
* reused:
* changed:
* missing:

## APIs

List every endpoint used.

## Database

List every table/field changed.

## Model

Report:

```text
provider:
model:
configuration source:
```

## Tests

```text
passed:
failed:
skipped:
```

## Live Verification

```text
OpenRouter:
Real Postgres:
Admin UI:
```

Do not claim anything that was not actually tested.

## Known Gaps

List every remaining gap.

## Git

Record:

```text
branch:
commit:
pushed:
```

---

# 28. STOP CONDITION

When this task is complete:

STOP.

Do not start:

* image warehouse redesign
* social posting
* daily image generation
* storyline
* publishing automation
* LoRA
* image model benchmarking

Those belong to later tasks.

Wait for the next instruction.

```

**Next after this:** image **Warehouse + candidate lifecycle**. That is where we make the persona-level gallery, timestamps, status, remarks, seed prompt, and saved/generated image history properly usable in Admin.
```
