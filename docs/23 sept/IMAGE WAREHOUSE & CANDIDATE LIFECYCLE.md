

````md
# 23-04 — IMAGE WAREHOUSE & CANDIDATE LIFECYCLE

## Purpose

Build the persona-level image warehouse where every generated image can be reviewed, managed, and tracked by Admin.

The warehouse must provide a persistent history of generated images and their lifecycle.

The workflow is:

Persona
→ Image Warehouse
→ Generated candidates
→ Review
→ Remark
→ Shortlist / Decline
→ Save
→ Later Post

This task is about **image management and lifecycle**.

It does NOT implement publishing/social posting.

---

# 1. GOVERNANCE

This task is governed by:

`23-00_IMAGE_PIPELINE_GOVERNANCE.md`

Previous dependency:

`23-03_IMAGE_GENERATION_AND_CANDIDATE_CREATION.md`

Do not duplicate generation logic.

Use the existing:

- image jobs
- generated candidates
- object storage
- image APIs
- persona identity
- Admin image generation UI
- observability

---

# 2. FIRST: INSPECT EXISTING IMPLEMENTATION

Before changing code inspect:

- `generated_candidates`
- image job tables
- object storage
- image result APIs
- Admin image generation UI
- persona Admin UI
- candidate status implementation
- message attachment implementation
- retention implementation

Update:

`docs/23 sept/23-04_IMAGE_WAREHOUSE_IMPLEMENTATION.md`

with:

```text
Existing:
Implemented:
Partially implemented:
Missing:
Reused:
Changed:
````

Do not assume the schema is missing if it already exists.

---

# 3. PRODUCT MODEL

Every persona should have an Image Warehouse.

Conceptually:

```text
PERSONA
   │
   └── IMAGE WAREHOUSE
          │
          ├── Image A
          ├── Image B
          ├── Image C
          ├── Image D
          └── ...
```

The warehouse contains images generated for that persona.

Images must remain associated with the persona even after the original generation job has completed.

---

# 4. IMAGE RECORD

Every warehouse image should expose at minimum:

```text
imageId
personaId
jobId
candidateId
assetId

createdAt

status

seedPrompt

adminRemark

provider

model

generation metadata
```

Use existing fields where available.

Do not create duplicate fields simply because the names differ.

---

# 5. IMAGE STATUS

The minimum lifecycle is:

```text
GENERATED
SHORTLISTED
DECLINED
SAVED
POSTED
```

However:

### Important

Do not blindly add all of these states if the current domain already has a compatible state model.

Map the existing implementation to the product lifecycle.

Document the mapping in:

`23-04_IMAGE_WAREHOUSE_IMPLEMENTATION.md`

---

# 6. STATUS MEANING

## GENERATED

Image has successfully been generated and is available for review.

Example:

```text
Generated
22 Sep 2026, 11:42
```

---

## SHORTLISTED

Admin has selected the image as a preferred candidate.

Multiple images can be shortlisted.

Do NOT assume only one winner.

---

## DECLINED

Admin does not want this image.

The underlying image should remain auditable unless retention policy later removes it.

Do not immediately delete declined images.

---

## SAVED

Image is explicitly retained as a usable persona image.

If the existing architecture considers every generated candidate already durable, distinguish:

* generated
* saved/approved

rather than duplicating physical files.

Document the exact interpretation.

---

## POSTED

Reserved for the future publishing workflow.

This task must NOT implement social publishing.

If the current database supports a `POSTED` state already, it may be retained.

Otherwise do not build publishing just to support the status.

---

# 7. ADMIN PERSONA VIEW

Inside:

```text
Admin
→ Personas
→ Select Persona
```

add:

```text
Image Warehouse
```

Example:

```text
Persona
├── Basic Details
├── Visual Identity
├── Reference Images
├── Image Warehouse
└── Posts
```

`Posts` can remain disabled/not implemented until the later publishing task.

---

# 8. IMAGE WAREHOUSE UI

Display generated images as a grid.

Example:

```text
┌──────────────┐ ┌──────────────┐
│              │ │              │
│    IMAGE     │ │    IMAGE     │
│              │ │              │
├──────────────┤ ├──────────────┤
│ SHORTLISTED  │ │ GENERATED    │
│ 22 Sep 11:42 │ │ 22 Sep 11:43 │
│              │ │              │
│ [Open]       │ │ [Open]       │
└──────────────┘ └──────────────┘
```

The UI must use actual stored assets.

No placeholder images.

No fake metadata.

---

# 9. FILTERS

Admin should be able to filter by:

```text
Status
Date
Model
Provider
```

Minimum required:

```text
All
Generated
Shortlisted
Declined
Saved
Posted
```

If a status does not exist yet, it should not appear as a fake selectable state.

---

# 10. SORTING

Support:

```text
Newest first
Oldest first
```

Default:

```text
Newest first
```

Use server-side ordering where practical.

Do not load thousands of images into the browser just to sort them client-side.

---

# 11. IMAGE DETAIL

Clicking an image should open a detail view.

Show:

```text
Image

Status
Created At
Persona
Seed Prompt
Model
Provider
Generation Job
Candidate ID
Admin Remark
```

Also show the actual image.

---

# 12. SEED PROMPT

The original seed prompt must remain accessible.

Example:

```text
Seed Prompt

Swimming in a yellow bikini
at a Goa beach in the early morning,
facing the camera.
```

This is important for later:

* regeneration
* comparison
* debugging
* model evaluation
* content review

Do not replace the original seed prompt when an admin adds a correction.

---

# 13. ADMIN REMARK

Each image must support:

```text
Admin Remark
```

Example:

```text
Face is very close to reference.
Hair needs correction.
```

The remark must be persisted.

The UI must show when it was last updated if the existing data model supports timestamps.

---

# 14. CANDIDATE ACTIONS

From the image detail screen Admin should be able to:

```text
Shortlist
Decline
Save
Add Remark
Regenerate
```

Actions that are not yet implemented must not appear as functional buttons.

If regeneration belongs to the next task, show it only if the backend already supports it.

---

# 15. MULTIPLE SHORTLISTED IMAGES

The warehouse must support:

```text
Image A → SHORTLISTED
Image B → SHORTLISTED
Image C → DECLINED
Image D → SHORTLISTED
```

There must be no database constraint forcing exactly one shortlisted image.

---

# 16. IMAGE LINEAGE

Every generated image must be traceable back to its generation.

Minimum:

```text
Persona
 ↓
Image Job
 ↓
Candidate
 ↓
Asset
```

For regenerated images, eventually support:

```text
Original Candidate
       ↓
Regeneration Job
       ↓
New Candidate
       ↓
New Asset
```

Do not destroy the original.

---

# 17. REGENERATION COMPATIBILITY

This task does not need to implement the regeneration engine if it is not already available.

However, the warehouse must not prevent it.

Candidate records should retain enough information for the next task to know:

* original seed prompt
* persona
* identity
* source candidate
* model
* generation configuration

If regeneration lineage is not currently supported, document it as:

```text
Required by next task:
Candidate lineage / parentCandidateId
```

Do not silently invent an incompatible schema.

---

# 18. ASSET STORAGE

Warehouse records must point to durable assets.

Use the existing:

```text
ObjectStorage
```

implementation.

Do not store large image binaries directly inside the normal candidate database record unless the existing architecture explicitly requires it.

The database should retain asset references.

---

# 19. IMAGE URLS

The Admin UI must use authenticated image access.

Do not expose internal filesystem paths.

Do not return:

```text
C:\...
/home/...
/var/...
```

to the browser.

Use the existing authenticated asset endpoint.

---

# 20. SECURITY

Verify:

### Admin

Admin can:

* view images
* update status
* add remarks
* manage candidates

### Normal user

Normal users must NOT gain access to the entire persona warehouse merely because they can interact with the persona.

User access should only expose assets explicitly intended for user-facing delivery.

---

# 21. PRIVATE REFERENCE IMAGES

Private identity/reference images are NOT warehouse images.

Keep the distinction:

```text
Persona
├── Visual Identity
│   ├── Public/identity references
│   └── Private references
│
└── Image Warehouse
    └── Generated images
```

Do not mix private identity references into generated candidate listings.

---

# 22. RETENTION

Warehouse implementation must work with the existing retention policy.

Important:

An image that is:

* referenced by a message
* explicitly saved
* required by an active workflow

must not accidentally disappear because the original generation job aged out.

Review:

`ImageRetentionCleaner`

and existing retention tests.

Do not create a second retention mechanism.

---

# 23. DATABASE

Before changing schema determine whether:

```text
generated_candidates
```

already contains the required information.

If yes:

**reuse it.**

Only add a new table if the existing schema genuinely cannot represent the warehouse.

If a new table is required, document:

* why
* relationships
* indexes
* foreign keys
* deletion behavior

---

# 24. API

Minimum required capabilities:

```text
GET /v1/admin/personas/{personaId}/images
```

or equivalent existing endpoint.

Support:

```text
status filter
date filter
pagination
sorting
```

Detail:

```text
GET /v1/admin/images/{imageId}
```

Actions:

```text
POST /v1/admin/images/{imageId}/shortlist
POST /v1/admin/images/{imageId}/decline
POST /v1/admin/images/{imageId}/save
POST /v1/admin/images/{imageId}/remark
```

Reuse existing endpoints where possible.

Do NOT create duplicate APIs if the current image-generation routes already provide the functionality.

---

# 25. PAGINATION

The warehouse must be designed for growth.

Do not return the entire warehouse indefinitely.

Minimum:

```text
page
pageSize
total
items
```

or an equivalent cursor-based mechanism.

Default page size can be reasonable, e.g.:

```text
20
```

Use the existing API conventions.

---

# 26. ADMIN UX — EMPTY STATE

When a persona has no generated images:

```text
No generated images yet.

Generate your first image for this persona.
```

Provide a clear route/button to generation.

Do not show misleading SLA/cost metrics when there is no data.

---

# 27. ADMIN UX — ERROR STATE

If warehouse loading fails:

```text
Unable to load image warehouse.

Retry
```

Do not silently display an empty warehouse.

Empty and error states must be distinguishable.

---

# 28. ADMIN UX — IMAGE ACTION FEEDBACK

After actions such as:

```text
Shortlist
Decline
Save
Remark
```

the UI must show success/failure.

Refresh the actual server state.

Do not rely only on local UI mutation.

---

# 29. OBSERVABILITY

Warehouse CRUD operations do not need to become image-generation events unless the existing observability architecture requires it.

Generation events remain associated with:

```text
job
candidate
provider
model
latency
outcome
```

Do not pollute generation metrics with ordinary Admin browsing.

---

# 30. TESTS

### Repository

Test:

* persona filtering
* status filtering
* sorting
* pagination
* candidate lookup
* remark persistence
* status transitions

### API

Test:

* authorized admin
* unauthorized access
* missing image
* wrong persona
* invalid status
* pagination
* filtering

### Security

Test:

* user cannot browse another persona's warehouse
* private reference cannot appear
* asset access remains authorized
* filesystem path is never exposed

### Integration

Test:

```text
Generate image
→ candidate created
→ asset stored
→ warehouse lists image
→ open image
→ shortlist
→ remark
→ save
→ reload
→ state remains correct
```

---

# 31. ADMIN MANUAL TEST

Cursor must manually verify:

### Test 1 — Empty warehouse

Create/select a persona with no generated images.

Expected:

```text
Empty state
```

### Test 2 — Generate

Generate an image using the previous task.

Expected:

```text
Image appears in warehouse.
```

### Test 3 — Metadata

Open image.

Verify:

```text
timestamp
seed prompt
model
provider
status
remark
```

### Test 4 — Shortlist

Shortlist image.

Reload page.

Expected:

```text
SHORTLISTED
```

### Test 5 — Remark

Add:

```text
Good face, improve hair in next generation.
```

Reload.

Expected:

```text
remark persists
```

### Test 6 — Decline

Decline another candidate.

Reload.

Expected:

```text
DECLINED
```

### Test 7 — Filtering

Filter:

```text
SHORTLISTED
```

Expected only shortlisted images.

### Test 8 — Pagination

Generate enough test records to verify pagination.

### Test 9 — Authorization

Attempt access using a non-admin context.

Expected:

```text
403 / appropriate authorization failure
```

---

# 32. DO NOT IMPLEMENT

Do NOT implement in this task:

* social media posting
* Instagram integration
* automatic publishing
* daily image scheduling
* storyline
* LoRA
* model benchmarking
* automatic best-image selection
* user-facing persona gallery

Those are separate tasks.

---

# 33. DEFINITION OF DONE

The task is complete only when:

* [ ] Persona has an Image Warehouse
* [ ] Generated images appear automatically
* [ ] Images persist after job completion
* [ ] Date/time is displayed
* [ ] Status is displayed
* [ ] Seed prompt is displayed
* [ ] Model/provider are displayed
* [ ] Admin remark works
* [ ] Shortlist works
* [ ] Decline works
* [ ] Save works
* [ ] Filtering works
* [ ] Sorting works
* [ ] Pagination works
* [ ] Image detail works
* [ ] Durable asset access works
* [ ] Authorization works
* [ ] Private references remain separate
* [ ] Retention does not delete protected assets
* [ ] Candidate lineage is preserved or documented as next-task work
* [ ] Automated tests pass
* [ ] Admin UI manually tested

---

# 34. REQUIRED IMPLEMENTATION REPORT

Update:

`docs/23 sept/23-04_IMAGE_WAREHOUSE_IMPLEMENTATION.md`

with:

## Status

```text
PASS
PASS WITH FINDINGS
BLOCKED
```

## Existing implementation

What already existed before this task.

## Backend

* files changed
* APIs
* database
* repository
* status model
* storage

## Admin UI

* screens
* filters
* actions
* detail view
* empty/error states

## Tests

```text
passed:
failed:
skipped:
```

## Manual verification

Document each manual test and result.

## Known gaps

Explicitly list anything not implemented.

## Next dependency

State what the next task requires from this implementation.

## Git

```text
branch:
commit:
pushed:
```

---

# 35. STOP CONDITION

When this task passes its acceptance criteria:

STOP.

Do not begin publishing or social-post functionality.

The next task should handle:

**IMAGE REGENERATION / CORRECTION LOOP**

where Admin can select a generated image, provide correction instructions, and generate a new candidate while preserving the original candidate and lineage.

```
```
