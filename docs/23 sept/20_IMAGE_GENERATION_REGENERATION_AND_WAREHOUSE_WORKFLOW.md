# 20 — IMAGE GENERATION, REGENERATION & WAREHOUSE WORKFLOW

**Goal:** Make the complete Admin image-generation workflow usable end-to-end: seed prompt → generation → four candidates → review → remarks → shortlist/save → correction → regeneration → warehouse lineage.

**Depends on:** Image Pipeline Governance, Implementation Status, Tasks 01–19.

---

## 1. Product objective

Admin should be able to enter a simple generation request such as:

```text
Generate an image of the Persona swimming in a yellow bikini
at a Goa beach, early morning, facing the camera.
```

The system must combine:

```text
Persona visual identity
+
active reference images
+
physical/appearance attributes
+
seed prompt
+
configured image model
```

and generate:

```text
4 independent candidates
```

Admin then reviews the candidates and can:

```text
Shortlist
Decline
Save
Add remark
Regenerate with correction
```

Every action must preserve generation lineage.

---

# 2. Complete workflow

The intended workflow is:

```text
Admin selects Persona
        ↓
Admin enters seed prompt
        ↓
System resolves active Persona visual identity
        ↓
System resolves reference images
        ↓
PromptCompiler
        ↓
Configured provider/model
        ↓
Image generation job
        ↓
4 candidates
        ↓
Image Warehouse
        ↓
Admin review
        ↓
Shortlist / Decline / Save
        ↓
Optional correction
        ↓
Regenerate
        ↓
New candidate linked to original
```

---

# 3. Admin entry point

The existing Admin Persona experience should provide an obvious image-generation action.

Example:

```text
Persona
 ├── Visual
 ├── Image Warehouse
 ├── Generate Image
 └── Posts
```

Posts are outside this task's publishing scope.

---

# 4. Generate Image form

Minimum fields:

```text
Persona
Seed prompt
```

Display read-only context:

```text
Active identity version
Reference image count
Provider
Model
```

Do not require Admin to manually copy Persona physical attributes into the seed prompt.

Those come from the Persona Visual system.

---

# 5. Seed prompt

The seed prompt describes the desired scene.

Examples:

```text
Swimming in a yellow bikini at Goa beach.
Early morning. Facing the camera.
```

```text
Walking through a mountain village at sunrise,
wearing a casual jacket and jeans.
```

The seed should not need to repeat the Persona identity.

---

# 6. Prompt construction

The generation request should pass through the existing `PromptCompiler`.

Expected conceptual structure:

```text
IDENTITY
  ↓
REFERENCE / VISUAL CONTEXT
  ↓
SCENE
  ↓
WARDROBE
  ↓
POSE / CAMERA
  ↓
LIGHTING / STYLE
```

Do not create a second prompt-building path for Admin generation.

---

# 7. Four-candidate generation

A normal successful generation request must produce four candidates.

Conceptually:

```text
Job 123
 ├── Candidate 0
 ├── Candidate 1
 ├── Candidate 2
 └── Candidate 3
```

Each candidate is independently persisted.

One failed candidate must not silently overwrite another.

If the provider returns fewer than four images, record the actual result and report the shortfall rather than fabricating candidates.

---

# 8. Candidate status

Use the existing candidate status model.

The product workflow requires at least:

```text
Generated
Shortlisted
Declined
```

If the existing schema has a more appropriate vocabulary, reuse it.

Do not create duplicate status systems.

---

# 9. Image Warehouse

Each Persona should have access to its generated image warehouse.

Minimum information:

```text
Thumbnail
Created date/time
Status
Model
Seed prompt
Admin remark
```

Useful additional metadata:

```text
Job ID
Candidate ID
Identity version
Provider
Generation latency
Cost where available
```

---

# 10. Warehouse filtering

Admin should be able to distinguish:

```text
All
Generated
Shortlisted
Declined
Saved
```

Use only states that actually exist in the implementation.

Do not display a filter that has no backend semantics.

---

# 11. Candidate detail

Opening a candidate should show:

```text
Generated image
Persona
Identity version
Reference set
Seed prompt
Provider
Model
Created time
Status
Admin remark
```

Where available:

```text
Cost
Latency
Job ID
Parent candidate
```

---

# 12. Admin review

Admin actions should be simple:

```text
Shortlist
Decline
Save
Add remark
Regenerate
```

Do not require Admin to edit database fields manually.

---

# 13. Admin remarks

Remarks should be attached to the candidate or the appropriate review record.

Examples:

```text
Face looks consistent.
Hair is slightly different.
Good composition.
Background is excellent.
Body proportions need correction.
```

Remarks must not overwrite the original seed prompt.

---

# 14. Regeneration

Admin should be able to select a candidate and request a correction.

Example:

```text
Original:
Swimming at Goa beach.

Admin correction:
Keep the same Persona and scene.
Match the hairstyle to the reference images.
```

The system creates a new generation.

It must NOT replace the original image.

---

# 15. Regeneration lineage

The new candidate must retain:

```text
parentCandidateId
original job reference
Persona
identity version
reference set
original seed
correction instruction
provider
model
createdAt
```

Conceptual:

```text
Candidate A
   │
   └── correction: "match hairstyle"
          ↓
      Candidate B
```

Candidate A remains available.

---

# 16. Multiple regeneration rounds

Support lineage such as:

```text
A
 ↓ correction 1
B
 ↓ correction 2
C
```

Do not flatten the history.

Admin should be able to understand how C was produced.

---

# 17. Regeneration must use current product rules

A regeneration should resolve the applicable Persona identity/reference state according to the established versioning contract.

If the product requires regeneration against the original identity version, preserve that explicitly.

Do not silently mix identity versions.

---

# 18. Candidate saving

"Save" means retain the candidate in the warehouse according to the existing storage/status semantics.

It does not automatically publish the image.

Publishing belongs to the later Post workflow.

---

# 19. Shortlisting

Shortlisting identifies an image as an Admin-approved candidate for later product use.

It does not automatically:

- publish;
- expose to users;
- create a social post;
- modify Persona identity.

Those are separate workflows.

---

# 20. Declining

Declining a candidate must retain the candidate record unless existing retention rules explicitly remove it later.

The reason/remark should remain available for Admin review.

---

# 21. Image asset handling

Generated assets must use the existing durable object-storage pipeline.

Do not store image binaries directly inside database rows unless that is already the project's established design.

Expected:

```text
candidate
  ↓
assetId
  ↓
ObjectStorage
```

---

# 22. Security

Only authorized Admin users may:

- generate images through Admin;
- inspect the warehouse;
- change candidate status;
- add remarks;
- regenerate;
- access protected Admin assets.

Preserve the existing user-facing asset ownership rules.

Do not expose the entire Persona warehouse through public APIs.

---

# 23. Generation progress

The Admin UI should distinguish:

```text
Queued
Running
Succeeded
Failed
```

For a running generation:

```text
Generating 4 candidates...
```

Do not display success before the job actually reaches the appropriate terminal state.

---

# 24. Partial provider response

If the provider returns:

```text
0 candidates
1 candidate
2 candidates
3 candidates
```

persist what was actually received according to the existing job contract.

The UI should communicate the actual outcome.

Do not manufacture missing images.

---

# 25. Failed generation

When generation fails:

Admin should see:

```text
Failed
Provider/model
Failure classification
Redacted error
Retry information where available
```

Do not expose credentials.

Do not hide permanent provider failures behind endless retries.

---

# 26. Model visibility

Every generated candidate must make it possible to identify:

```text
Provider
Model
```

This is required for the model evaluation work in Task 18.

---

# 27. Identity visibility

Candidate review must make it possible to identify:

```text
Persona
Identity version
Reference set
```

Admin must not have to guess which Persona identity was used.

---

# 28. Cost visibility

Where actual provider cost is available, expose it.

If unavailable:

```text
Cost unavailable
```

Never display `$0` simply because cost data was not returned.

---

# 29. SLA visibility

Generation detail should expose latency where available.

Example:

```text
Generation latency: 18.4s
```

The aggregate SLA dashboard remains Task 16.

Do not duplicate a separate SLA calculation in this workflow.

---

# 30. API design

Reuse existing image APIs where possible.

The workflow needs capabilities equivalent to:

```text
Create generation job
Get job
Get result
Get asset
Update candidate status
Add remark
Regenerate candidate
```

Follow existing project route conventions.

Do not create duplicate endpoints if the current image API already provides the required capability.

---

# 31. Idempotency

Repeated submission of the same generation request must follow the existing image-job idempotency contract.

Do not accidentally create duplicate jobs because the Admin UI retries a request.

Regeneration is intentionally a new generation and must have its own lineage/idempotency semantics.

---

# 32. Acceptance test — basic generation

1. Select a test Persona.
2. Enter a seed prompt.
3. Submit.
4. Wait for completion.
5. Open Image Warehouse.

Verify:

```text
1 job
4 candidates when provider returns 4
correct Persona
correct identity version
correct provider/model
correct seed
```

---

# 33. Acceptance test — review

For one candidate:

```text
Add remark
Shortlist
```

Reload.

Verify status and remark persist.

For another:

```text
Decline
```

Verify status persists.

---

# 34. Acceptance test — regeneration

1. Generate four candidates.
2. Select candidate 1.
3. Add correction.
4. Regenerate.
5. Wait for completion.

Verify:

```text
original candidate still exists
new candidate exists
new candidate references original
correction is retained
identity lineage is retained
provider/model is retained
```

---

# 35. Acceptance test — multiple rounds

Perform:

```text
A → B → C
```

through two correction rounds.

Verify:

```text
C → B → A
```

can be understood through lineage.

---

# 36. Acceptance test — model comparison

Run the same seed/Persona through two configured models.

Verify each candidate records the correct model.

No candidate may inherit the model value from another job.

---

# 37. Acceptance test — failure

Cause a controlled provider failure.

Verify:

```text
job = FAILED
candidate state is truthful
error is redacted
no fake image exists
retry semantics are correct
```

---

# 38. Acceptance test — permissions

Verify:

```text
Admin → can generate/review
Normal user → cannot access Admin warehouse
Unauthenticated → denied
```

Use existing authorization conventions.

---

# 39. Acceptance test — persistence

Restart the application.

Verify:

```text
warehouse remains
candidate metadata remains
assets remain retrievable
statuses remain
remarks remain
lineage remains
```

---

# 40. Acceptance test — storage

Verify generated assets are stored through the configured ObjectStorage implementation.

Verify an asset can be retrieved after generation.

---

# 41. Acceptance test — no publication

Shortlist/save a candidate.

Verify it does NOT automatically become a user-facing post.

The Post workflow is a later task.

---

# 42. Definition of Done

- [ ] Admin can select Persona.
- [ ] Admin can enter seed prompt.
- [ ] Active identity is automatically resolved.
- [ ] Reference images are included according to provider capability.
- [ ] PromptCompiler is used.
- [ ] Generation creates four candidates when provider returns four.
- [ ] Candidates are independently persisted.
- [ ] Warehouse displays generated images.
- [ ] Status management works.
- [ ] Remarks work.
- [ ] Shortlist works.
- [ ] Decline works.
- [ ] Save works according to existing semantics.
- [ ] Regeneration works.
- [ ] Regeneration preserves lineage.
- [ ] Multiple regeneration rounds work.
- [ ] Model/provider metadata is retained.
- [ ] Identity metadata is retained.
- [ ] Cost/latency are visible where available.
- [ ] Failure states are truthful.
- [ ] Admin security works.
- [ ] Assets survive application restart.
- [ ] Shortlisting does not publish.
- [ ] Existing image tests remain green.
- [ ] New workflow tests pass.
- [ ] Implementation status is updated.

---

# 43. Cursor implementation instructions

Before coding:

1. Inspect current Admin image generation.
2. Inspect current Image Warehouse implementation.
3. Inspect candidate persistence.
4. Inspect candidate status model.
5. Inspect image asset APIs.
6. Inspect PromptCompiler.
7. Inspect regeneration support.
8. Inspect existing Admin authorization.
9. Reuse existing APIs/data structures.
10. Do not create duplicate image-generation paths.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Task 20:
Admin generation:
Seed prompt:
4-candidate flow:
Warehouse:
Candidate status:
Remarks:
Shortlist:
Decline:
Save:
Regeneration:
Lineage:
Security:
Persistence:
Tests:
Known gaps:
Files changed:
Git commit:
```

---

# 44. Completion report

Cursor must return:

```text
TASK 20 — GENERATION / REGENERATION / WAREHOUSE

Status:

Admin generation:
Seed prompt:
Persona identity:
References:
Provider:
Model:

Candidates:
Expected:
Actual:

Warehouse:
Statuses:
Remarks:
Shortlist:
Decline:
Save:

Regeneration:
Parent lineage:
Correction:
Multiple rounds:

Persistence:
Storage:
Restart verification:

Security:

Tests:
Passed:
Failed:
Skipped:

Known gaps:

Files changed:

Commit:
```

**Critical rule:** The Image Warehouse is the operational record of generated candidates. Generated images, Persona identity, and published posts are three different concepts and must remain separate.
