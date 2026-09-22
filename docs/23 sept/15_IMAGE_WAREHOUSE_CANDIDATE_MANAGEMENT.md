# 15 — IMAGE WAREHOUSE & CANDIDATE MANAGEMENT

**Depends on:**
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- `09_IMAGE_REGENERATION_ADMIN_FEEDBACK.md`
- `10_IMAGE_REVIEW_SHORTLIST_ADMIN_WORKFLOW.md`
- `13_IMAGE_IDENTITY_CONSISTENCY_REFERENCE_MATCHING.md`
- `14_IMAGE_GENERATION_ENGINE_SKILL_CONFIGURATION.md`

**Goal:** Provide a complete Admin image warehouse inside each Persona where every generated candidate can be found, inspected, remarked on, shortlisted, declined, saved, regenerated, and eventually marked for posting.

This task covers the **image warehouse and candidate review lifecycle**. Actual publishing/post distribution remains a separate future task.

---

# 1. Product model

Every generated image belongs to a generation job and a Persona.

Conceptually:

```text
PERSONA
   │
   └── IMAGE WAREHOUSE
          │
          ├── Generation 001
          │      ├── Candidate 1
          │      ├── Candidate 2
          │      ├── Candidate 3
          │      └── Candidate 4
          │
          ├── Generation 002
          │      └── ...
          │
          └── Generation N
```

The warehouse is the historical source of truth for generated images.

Do not treat the chat message attachment as the warehouse.

---

# 2. Candidate lifecycle

Use the existing candidate/job status model where available.

The product-level candidate workflow must support at minimum:

```text
GENERATED
    ↓
ADMIN REVIEW
    ├── SHORTLISTED
    ├── DECLINED
    └── SAVED / RETAINED
```

A candidate may later become:

```text
POST-READY / MARKED_FOR_POST
```

Actual publishing is explicitly outside this task.

Do not create duplicate status systems if the current implementation already has equivalent candidate states.

---

# 3. Required candidate information

For every candidate Admin should be able to inspect:

```text
Candidate ID
Persona
Generation/job ID
Created date/time
Candidate image
Candidate status
Admin remark
Seed prompt
Provider
Model
Identity version
Reference-image set
Generation settings where available
Cost where available
Latency where available
Regeneration lineage where applicable
```

Do not expose sensitive internal data unnecessarily.

---

# 4. Warehouse view

Inside a Persona Admin page:

```text
Persona
 ├── Profile
 ├── Visual Identity
 ├── Image Warehouse
 └── Posts
```

For this task:

```text
Image Warehouse
```

must be functional.

Suggested layout:

```text
IMAGE WAREHOUSE

Filters:
[All] [Generated] [Shortlisted] [Declined] [Saved]

Date:
[From] [To]

Model:
[All models]

Generation:
[All]

---------------------------------

[Image]  [Image]  [Image]  [Image]

status   status   status   status
time     time     time     time

---------------------------------
```

Follow existing Admin UI patterns.

---

# 5. Candidate detail

Clicking a candidate should open a detail view.

Example:

```text
Candidate

[ LARGE IMAGE ]

Status: SHORTLISTED

Persona:
Simran Kohli

Generated:
2026-09-23 08:14

Model:
<model>

Identity:
V5

References:
Front
Face Close
Left
Right
Back

Seed:
"Swimming in yellow bikini..."

Admin remark:
"Face matches. Hair slightly off."

[Edit Remark]
[Shortlist]
[Decline]
[Regenerate]
[Save]
[Mark for Post]
```

Only show actions valid for the current status.

---

# 6. Candidate selection

Admin must be able to select one or more candidates from a generation.

Example:

```text
Generation 123
4 candidates

[✓] Candidate 1
[ ] Candidate 2
[✓] Candidate 3
[ ] Candidate 4

[Shortlist Selected]
[Decline Selected]
[Save Selected]
```

Bulk actions must not accidentally modify candidates outside the selection.

---

# 7. Shortlisting

Admin can mark one or more generated candidates:

```text
SHORTLISTED
```

Shortlisted images remain in the warehouse.

Shortlisting must not delete or alter the original image.

---

# 8. Declining

Admin can mark a candidate:

```text
DECLINED
```

Declining must not immediately delete the image.

The image remains available according to retention policy and audit rules.

Admin should be able to add a remark.

Example:

```text
Declined

Reason:
Face mismatch

Remark:
"Eyes and jaw differ from reference."
```

---

# 9. Admin remarks

Every candidate must support Admin remarks.

Requirements:

- editable by authorized Admin
- timestamped if existing audit conventions support it
- preserved with candidate
- visible in candidate detail
- not included in provider prompt unless explicitly used as regeneration feedback

Do not confuse an Admin review remark with a generation instruction.

---

# 10. Regeneration from candidate

Admin must be able to regenerate from an existing candidate.

Example:

```text
Candidate 3

Remark:
"Keep face exactly like reference.
Change bikini to yellow."

[Regenerate]
```

The new generation must retain lineage:

```text
parentCandidateId = Candidate 3
```

or equivalent existing relationship.

The original candidate remains unchanged.

---

# 11. Regeneration context

A regeneration request should preserve:

```text
Persona
Identity version
Reference images
Original scene intent
Relevant generation configuration
```

and add:

```text
Admin correction
```

The correction must not silently replace the entire original seed.

---

# 12. Generation lineage

Admin should be able to understand:

```text
Generation 100
   ↓
Candidate 3
   ↓
Regeneration 101
   ↓
Candidate 2
   ↓
Regeneration 102
```

At minimum store:

```text
parentCandidateId
```

where supported.

Do not create a complicated version tree if the current data model can provide simple parent-child lineage.

---

# 13. Image save/retain behavior

The product requirement says Admin can "save" an image.

Clarify this operationally:

A generated candidate already exists in the warehouse.

Therefore:

```text
Save
```

should represent the Admin's retention/selection action rather than creating a duplicate binary.

If the existing implementation has a `SAVED` state, reuse it.

If not, use the smallest equivalent mechanism.

Do not duplicate the same asset file merely because Admin clicks Save.

---

# 14. Mark for post

Admin should be able to mark an image:

```text
MARKED_FOR_POST
```

This creates a handoff to the future Post system.

It does **not** publish the image.

Expected behavior:

```text
Image Warehouse
      ↓
Mark for Post
      ↓
Post candidate
      ↓
Future Post workflow
```

Do not implement social publishing in this task.

---

# 15. Post section placeholder

Inside Persona:

```text
Posts
```

may exist as a placeholder/navigation area.

For this task it only needs enough data linkage to support:

```text
candidate → marked for post
```

Actual post scheduling, captions, platforms, publishing, analytics etc. remain deferred.

---

# 16. Filters

Minimum useful filters:

```text
Status
Date range
Model
Generation
```

Optional:

```text
Provider
Identity version
Marked for post
Regenerated
```

Do not build a complex search engine if simple repository queries are sufficient.

---

# 17. Sorting

Default:

```text
Newest first
```

Additional sorting may include:

```text
Oldest
Status
Model
```

Do not create subjective ranking such as:

```text
Best image
Highest quality
Most attractive
```

unless based on an explicit future scoring mechanism.

---

# 18. Pagination

The warehouse must not load every historical image into one Admin page.

Implement pagination using the project's existing pagination conventions.

Example:

```text
20 / page
```

The exact page size should follow existing Admin UI/API conventions.

---

# 19. Thumbnail handling

Warehouse list view should use thumbnails/previews rather than downloading full-size images unnecessarily.

Candidate detail may load the larger asset.

Reuse existing asset routes/storage.

Do not create public image URLs merely for Admin convenience.

---

# 20. Asset authorization

Every warehouse image access must verify Admin authorization.

For user-facing asset routes, use the existing conversation/ownership rules.

Never rely solely on:

```text
candidateId
assetId
```

being difficult to guess.

---

# 21. Cross-persona protection

Admin operations must prevent accidental modification of another Persona's candidate through manipulated IDs.

Test:

```text
Persona A
Candidate A

request Candidate B using Persona A route
```

Expected:

```text
404/403 according to existing security contract
```

No candidate mutation.

---

# 22. Candidate deletion

Do not introduce unrestricted hard deletion.

If deletion is required by existing retention/admin contracts:

- use the existing retention policy
- preserve audit semantics
- ensure message references are respected

A candidate marked for post or referenced by a message must not be casually deleted.

---

# 23. Warehouse and chat attachment

A candidate may be attached to a chat assistant message.

The relationship should be:

```text
Candidate
   ↓
Asset
   ↓
Message attachment
```

Chat attachment does not remove the candidate from the warehouse.

If a message references the asset, retention must continue protecting it according to the existing retention rules.

---

# 24. Cost visibility

Candidate detail should expose available cost information.

Example:

```text
Model: ...
Provider: ...
Cost: $...
```

If cost is unavailable:

```text
Cost: unavailable
```

Never display fake zero cost.

---

# 25. SLA visibility

Candidate/job detail should expose relevant timing:

```text
Queued
Started
Completed
Total latency
```

This supports Admin SLA tracking.

Do not duplicate the SLA calculation logic in the UI.

Use the existing backend observability/SLA APIs.

---

# 26. Generation grouping

The warehouse should make it clear which candidates came from the same generation.

Example:

```text
Generation #123
Prompt: Swimming in Goa...
Model: Model A
Identity: V5

[Candidate 1]
[Candidate 2]
[Candidate 3]
[Candidate 4]
```

This is important for model evaluation and Admin review.

---

# 27. Model evaluation grouping

When Task 12 runs multiple models, the warehouse must preserve model separation.

Example:

```text
Evaluation E12

Model A
  Candidate 1–4

Model B
  Candidate 1–4

Model C
  Candidate 1–4
```

Admin must be able to identify which model produced each image.

---

# 28. Candidate status transitions

Implement a deterministic state transition policy.

At minimum test:

```text
GENERATED → SHORTLISTED
GENERATED → DECLINED
GENERATED → SAVED
GENERATED → MARKED_FOR_POST
```

and valid transitions from shortlisted/saved candidates according to the existing product model.

Invalid transitions must be rejected rather than silently changing status.

---

# 29. Concurrent Admin updates

Two Admin actions must not corrupt candidate state.

Example:

```text
Admin A → SHORTLIST
Admin B → DECLINE
```

The final result must follow the persistence layer's concurrency semantics.

Do not claim strong distributed locking unless actually implemented.

At minimum ensure updates are atomic and no partial state is written.

---

# 30. API requirements

Use existing API conventions.

Required capabilities:

```text
GET  persona image warehouse
GET  candidate
PATCH candidate status
PATCH candidate remark
POST candidate regenerate
POST candidate mark-for-post
```

Bulk operations may use:

```text
POST /.../candidates/bulk-action
```

only if consistent with existing API patterns.

Exact routes should follow the current project.

---

# 31. Database requirements

Reuse:

```text
generated_candidates
image jobs
assets
messages
```

where possible.

Only add fields/tables required for missing warehouse behavior.

Potential candidate metadata:

```text
status
admin_remark
parent_candidate_id
marked_for_post_at
```

Do not create duplicate candidate storage.

---

# 32. Acceptance Test — Generate and warehouse

1. Generate 4 candidates.
2. Open Persona → Image Warehouse.

Expected:

- all 4 candidates visible
- correct timestamps
- correct generation grouping
- correct model
- correct identity version
- correct status

---

# 33. Acceptance Test — Shortlist

1. Select candidates 1 and 3.
2. Click Shortlist.

Expected:

```text
1 → SHORTLISTED
3 → SHORTLISTED
2 → unchanged
4 → unchanged
```

---

# 34. Acceptance Test — Decline

1. Decline candidate 2.
2. Add remark.

Expected:

- candidate remains in warehouse
- status = DECLINED
- remark persisted
- timestamp retained where supported

---

# 35. Acceptance Test — Regenerate

1. Open candidate 3.
2. Add correction.
3. Regenerate.

Expected:

```text
original candidate remains
new generation created
new candidate created
parentCandidateId = candidate 3
identity version preserved
reference set preserved
correction recorded
```

---

# 36. Acceptance Test — Mark for post

1. Select candidate.
2. Mark for post.

Expected:

```text
candidate remains in warehouse
status/link indicates marked for post
no external publishing occurs
```

---

# 37. Acceptance Test — Filters

Generate candidates on different dates/models/statuses.

Verify each filter returns only matching records.

---

# 38. Acceptance Test — Pagination

Create more candidates than one page.

Verify:

- no missing candidates
- no duplicates
- stable ordering
- correct page boundaries

---

# 39. Acceptance Test — Security

Attempt:

- another persona's candidate
- another persona's asset
- manipulated candidate ID
- unauthenticated Admin endpoint

Expected:

```text
access denied / not found
```

according to existing security conventions.

---

# 40. Acceptance Test — Message retention

1. Attach candidate asset to a chat message.
2. Run retention cleaner.

Expected:

```text
message-referenced asset is retained
```

according to existing retention contract.

---

# 41. Definition of Done

This task is complete when:

- [ ] Persona Image Warehouse exists in Admin.
- [ ] Generated candidates are visible.
- [ ] Candidates are grouped by generation.
- [ ] Candidate metadata is visible.
- [ ] Status is visible.
- [ ] Admin remarks work.
- [ ] Shortlist works.
- [ ] Decline works.
- [ ] Save/retain behavior works.
- [ ] Regeneration works.
- [ ] Regeneration lineage is preserved.
- [ ] Mark-for-post works without publishing.
- [ ] Filters work.
- [ ] Pagination works.
- [ ] Candidate asset access is authorized.
- [ ] Cross-persona access is blocked.
- [ ] Model and identity metadata remain traceable.
- [ ] Chat attachment does not remove warehouse records.
- [ ] Retention respects message references.
- [ ] Cost/SLA metadata is displayed when available.
- [ ] Existing pipeline tests remain green.
- [ ] New warehouse/API/security tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 42. Cursor implementation rule

Before coding:

1. Inspect `generated_candidates`.
2. Inspect current candidate status/state machine.
3. Inspect existing Admin visual/image routes.
4. Inspect existing candidate review UI.
5. Inspect regeneration implementation.
6. Inspect message attachment implementation.
7. Inspect retention cleaner.
8. Inspect asset authorization.
9. Reuse existing models and routes.
10. Do not create a second image warehouse.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Warehouse UI:
Candidate lifecycle:
Candidate detail:
Remarks:
Shortlist:
Decline:
Save:
Regeneration:
Lineage:
Mark for post:
Filters:
Pagination:
Security:
Chat attachment:
Retention:
Cost/SLA:
Tests:
Known gaps:
Git commit:
```

---

# 43. Completion principle

The Admin must be able to answer, from the Persona's Image Warehouse:

> What images have we generated for this persona, when were they generated, which model generated them, which identity version/references were used, what did Admin decide about each image, what corrections were requested, and which images have been marked for future posting?

The warehouse is the operational history of image generation.

Publishing is a separate system.
