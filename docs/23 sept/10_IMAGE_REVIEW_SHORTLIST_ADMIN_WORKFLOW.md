# 10 — IMAGE REVIEW, SHORTLIST & ADMIN WORKFLOW

**Depends on:**  
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- `02_PERSONA_VISUAL_IDENTITY.md`
- `IMAGE_GENERATION & CANDIDATE CREATION.md`
- `Candidate Generation, 4-Image Output & Candidate Lifecycle.md`
- `IMAGE WAREHOUSE & CANDIDATE LIFECYCLE.md`
- `09_IMAGE_REGENERATION_ADMIN_FEEDBACK.md`

**Goal:** Make the generated-image review process complete and testable from the Admin UI through backend persistence.

---

## 1. Purpose

The image pipeline must not stop at generation.

After a generation request produces candidates, an admin must be able to:

1. See all candidates belonging to the generation request.
2. Inspect each candidate.
3. See the persona/visual identity context used for generation.
4. See generation metadata.
5. Add an admin remark to an individual candidate.
6. Shortlist one or more candidates.
7. Decline a candidate.
8. Save a candidate to the persona image warehouse.
9. Send a candidate into the later publishing workflow.
10. Regenerate a candidate using the correction/feedback workflow from Task 09.
11. Preserve the complete history and lineage.

This task is about **review and lifecycle control**, not publishing automation.

---

# 2. Required Admin Workflow

The intended flow is:

```text
Admin
  ↓
Persona
  ↓
Image Generation / Request
  ↓
4 generated candidates
  ↓
Candidate Review
  ├── Shortlist
  ├── Decline
  ├── Add remark
  ├── Regenerate
  └── Save
          ↓
     Image Warehouse
          ↓
     Later: Post workflow
```

Publishing/posting is a separate future feature.

---

# 3. Candidate Review Screen

For every generation request, Admin UI must display the candidate set.

Minimum information:

### Generation-level

- Generation/job ID
- Persona
- Persona visual identity version used
- Created timestamp
- Current job status
- Provider
- Model
- Seed prompt
- Compiled prompt reference/metadata
- Generation duration
- Attempt count
- Cost if available
- Number of candidates

### Candidate-level

- Candidate ID
- Preview image
- Candidate index
- Status
- Created timestamp
- Parent candidate ID, when regenerated
- Generation/job ID
- Admin remark
- Asset ID / asset URL
- Model/provider metadata where available
- Seed/prompt reference
- Selected/shortlisted state
- Post-ready state if applicable

Do not expose secrets such as provider API keys.

---

# 4. Candidate Status Model

Use explicit persisted states.

Minimum lifecycle:

```text
GENERATED
   ├── SHORTLISTED
   ├── DECLINED
   ├── SAVED
   └── REGENERATED
```

The implementation may use the existing project's exact enum/state names if already established.

Do not introduce duplicate lifecycle models if the existing candidate lifecycle already supports the required states.

### Important

`REGENERATED` must not mean that the original candidate is deleted.

The original candidate remains part of the warehouse/history.

The regenerated candidate gets its own candidate ID.

---

# 5. Shortlisting

Admin can shortlist one or more candidates from a generation.

Requirements:

- Multiple candidates may be shortlisted.
- Shortlisting one candidate must not automatically delete or decline others.
- Shortlisting must be persisted.
- Refreshing the Admin UI must preserve the state.
- Another admin/session must see the persisted state.
- The action must be auditable through existing image observability where available.

Example:

```text
Generation #123

Candidate A → SHORTLISTED
Candidate B → GENERATED
Candidate C → SHORTLISTED
Candidate D → DECLINED
```

---

# 6. Declining

Admin can decline a candidate.

Requirements:

- Decline is persisted.
- Candidate remains visible in history.
- Declined assets are not silently deleted.
- Admin may optionally provide a remark/reason.
- Declined candidates cannot accidentally appear as publish-ready.
- A declined candidate may be used as the parent/reference for a later regeneration if the existing regeneration design allows it.

---

# 7. Admin Remarks

Admin remarks must be attached to the specific candidate.

Example:

```text
Candidate: c_123

Status: SHORTLISTED

Remark:
"Face is good. Hair should be slightly longer and expression should
look more natural in the next regeneration."
```

Requirements:

- Add remark.
- Update remark.
- Persist remark.
- Display remark after refresh.
- Preserve previous candidate versions.
- Do not put arbitrary admin remarks into the immutable generation prompt.
- Task 09 regeneration may explicitly use the remark as correction input.

---

# 8. Save to Persona Image Warehouse

When Admin chooses **Save**, the candidate becomes a retained persona image.

The warehouse entry must retain:

- Persona ID
- Candidate ID
- Asset ID
- Generation ID/job ID
- Created timestamp
- Current lifecycle status
- Admin remark
- Seed prompt
- Prompt metadata/reference
- Provider
- Model
- Visual identity version
- Parent candidate ID if applicable

The stored image must continue to be retrievable after the generation job has completed.

Do not duplicate the physical image unnecessarily if the existing storage model already supports durable asset references.

---

# 9. Candidate → Post

Posting is **not implemented as part of this task** unless an existing post model already exists and the implementation can add the smallest additive reference.

If an existing post workflow exists:

```text
candidate
   ↓
saved/approved image
   ↓
post reference
```

If it does not exist:

- Do not build the complete publishing system.
- Do not invent social-platform integrations.
- Keep a future-compatible status/reference only if the current schema already supports it.

The Admin UI must not pretend that an image was published when no publishing backend exists.

---

# 10. Regeneration Integration

Task 09 owns the regeneration mechanics.

This task must integrate with it.

Admin should be able to:

```text
Candidate
   ↓
Add correction
   ↓
Regenerate
   ↓
New candidate
```

The new candidate must contain lineage:

```text
parentCandidateId = originalCandidateId
```

The original candidate remains unchanged.

The new candidate must use:

```text
Persona visual identity
        +
Reference images
        +
Original generation context
        +
Admin correction
        +
Seed/scene
```

Do not construct a second independent prompt-generation path in the Admin UI.

Reuse the existing `PromptCompiler` / image-generation service.

---

# 11. Persona Identity Visibility

The review screen should make it possible for Admin to understand what identity was used.

Display a compact identity summary, for example:

```text
Persona: Simran Kohli

Visual identity:
- Height: configured
- Build/body profile: configured
- Skin/complexion: configured
- Eyes: configured
- Hair: configured
- Distinguishing marks: configured
- Reference images: 5
- Identity version: V3
```

Do not expose sensitive/private visual fields unnecessarily in every candidate card.

Provide a dedicated identity view/edit workflow through the Persona Visual Identity admin screen.

The image generation service remains the source of truth for identity data.

---

# 12. Reference Image Visibility

Admin must be able to see which reference images belong to the active visual identity.

Standard identity reference categories should be supported by the existing Persona Visual Identity implementation, such as:

- Front
- Face close-up
- Left profile
- Right profile
- Back

Additional reference categories may exist if already supported.

Private/sensitive reference images, where supported by the existing persona model, must remain separately controlled and must not automatically appear in ordinary candidate review.

---

# 13. Admin Actions

Candidate card should support only actions that are actually backed by the backend.

Minimum:

```text
View
Add/Edit Remark
Shortlist
Decline
Save
Regenerate
```

Optional:

```text
Remove from shortlist
View lineage
View generation metadata
```

Do not create UI controls for backend functionality that does not exist.

---

# 14. Bulk Actions

If simple to implement safely, support:

```text
Select multiple
    ↓
Shortlist selected
```

Do not make bulk decline/delete mandatory for this task.

Individual candidate operations must work first.

---

# 15. Security

Every Admin action must be authenticated and authorized.

Verify:

- Admin can access candidate metadata.
- Admin can retrieve candidate asset.
- Non-admin cannot access Admin review APIs.
- Candidate IDs cannot be used to access unrelated personas.
- Asset IDs cannot bypass ownership/access controls.
- Admin actions cannot modify another unrelated record through manipulated IDs.

Do not rely only on hiding buttons in the UI.

Authorization must be enforced server-side.

---

# 16. Persistence

Do not make Admin review state UI-only.

After:

```text
shortlist
decline
remark
save
```

the state must survive:

- Browser refresh
- Admin UI reload
- Application restart
- Another authenticated Admin session

Use existing repositories/tables where possible.

Avoid creating duplicate tables if the current `generated_candidates` model can represent the required state.

---

# 17. Observability

Use the existing image observability mechanism.

Record meaningful events such as:

```text
CANDIDATE_VIEWED          (optional)
CANDIDATE_SHORTLISTED
CANDIDATE_DECLINED
CANDIDATE_REMARK_UPDATED
CANDIDATE_SAVED
CANDIDATE_REGENERATION_REQUESTED
```

Do not block the core Admin operation because telemetry fails.

Never log:

- API keys
- Authorization headers
- secrets
- private credentials

---

# 18. SLA / Cost

Review actions themselves should not create fake generation metrics.

Generation SLA and cost remain associated with the actual generation job/provider call.

When regeneration occurs:

```text
Original generation
        +
Regeneration generation
```

must remain separately measurable.

Admin should be able to distinguish:

- Initial generation
- Regeneration
- Candidate review time, if already supported
- Provider latency
- Provider/model
- Cost, when provider supplies usable cost information

Do not invent cost values.

---

# 19. API Requirements

Reuse existing image APIs where possible.

Expected capabilities:

```text
GET candidate/generation
GET candidate
POST candidate/{id}/shortlist
POST candidate/{id}/decline
POST candidate/{id}/remark
POST candidate/{id}/save
POST candidate/{id}/regenerate
```

Exact routes may follow the existing API conventions.

Prefer one existing candidate command endpoint if the current architecture already uses command-style operations.

Requirements:

- Validate input.
- Authenticate.
- Authorize.
- Persist transactionally.
- Return updated state.
- Return clear errors.
- Be idempotent where practical.

---

# 20. Admin UI Requirements

The Admin UI must provide a usable review workflow.

Minimum:

### Generation list

```text
Generation ID
Persona
Created
Status
Provider
Model
Candidates
```

### Candidate grid

Each candidate:

```text
[IMAGE]

Status
Candidate ID
Created
Remark
Parent candidate (if any)

[Shortlist]
[Decline]
[Save]
[Regenerate]
```

### Candidate detail

Show:

- Full image
- Generation metadata
- Persona
- Visual identity version
- Reference-image summary
- Prompt/seed metadata
- Current status
- Remark
- Lineage
- SLA/cost metadata where available

---

# 21. Do Not Build

Do not expand this task into:

- Social media publishing
- Scheduling
- Storyline automation
- Daily image generation
- LoRA training
- Automatic content factory
- Automatic public posting
- New provider architecture
- Cloud object storage
- A second prompt compiler

Those are separate concerns.

---

# 22. Acceptance Tests

## Test 1 — Candidate review

1. Create image generation request.
2. Generate four candidates.
3. Open Admin candidate review.
4. Verify all four appear.
5. Verify metadata is displayed.
6. Verify assets load.

Expected: PASS.

---

## Test 2 — Shortlist

1. Shortlist candidate A.
2. Refresh page.
3. Re-open generation.

Expected:

```text
A = SHORTLISTED
```

and other candidates retain their own states.

---

## Test 3 — Decline

1. Decline candidate B.
2. Add optional remark.
3. Refresh.

Expected:

```text
B = DECLINED
remark persisted
```

---

## Test 4 — Save

1. Save candidate A.
2. Open Persona Image Warehouse.

Expected:

```text
candidate A
asset A
persona
timestamp
generation metadata
```

are visible.

---

## Test 5 — Regeneration

1. Add correction to candidate C.
2. Click Regenerate.
3. Wait for completion.
4. Open candidate list.

Expected:

```text
C = original candidate
C2 = new candidate
C2.parentCandidateId = C
```

C must remain unchanged.

---

## Test 6 — Restart persistence

1. Shortlist candidate.
2. Add remark.
3. Restart application.
4. Open Admin UI.

Expected all persisted state remains.

---

## Test 7 — Security

Attempt candidate/asset access with:

- unauthenticated request
- non-admin user
- unrelated candidate ID
- unrelated asset ID

Expected: authorization failure.

---

## Test 8 — No fake publishing

Save candidate.

Expected:

- candidate is saved in warehouse
- no claim that it has been published
- no social post created unless an existing publishing backend explicitly supports it

---

## Test 9 — Observability

Perform:

```text
shortlist
decline
remark
save
regenerate
```

Expected corresponding image events where the existing observability design supports them.

Telemetry failure must not break the Admin action.

---

# 23. Definition of Done

This task is complete only when:

- [ ] Candidate review works in Admin UI.
- [ ] Candidate metadata is visible.
- [ ] Candidate assets are retrievable.
- [ ] Shortlist persists.
- [ ] Decline persists.
- [ ] Remarks persist.
- [ ] Save-to-warehouse persists.
- [ ] Regeneration integrates with Task 09.
- [ ] Candidate lineage is preserved.
- [ ] Original candidates are never overwritten by regeneration.
- [ ] Security is enforced server-side.
- [ ] Existing image observability is used.
- [ ] No fake publishing state exists.
- [ ] Backend tests pass.
- [ ] Admin/integration tests pass.
- [ ] Security tests pass.
- [ ] Existing image pipeline tests remain green.
- [ ] Implementation status is updated.

---

# 24. Cursor Implementation Rule

Before changing code:

1. Inspect the current candidate lifecycle.
2. Inspect the existing image warehouse implementation.
3. Inspect Task 09 implementation.
4. Reuse existing repositories/services/routes where possible.
5. Do not duplicate candidate state models.
6. Do not create a second generation path.
7. Do not break existing image pipeline tests.

After implementation, update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Implemented:
Not implemented:
Reused:
Files changed:
Database changes:
API changes:
Admin UI changes:
Tests:
Known gaps:
Git commit:
```

Do not claim completion unless the feature has been tested end-to-end through the Admin UI and backend.
