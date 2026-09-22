# 13 — IMAGE IDENTITY CONSISTENCY & REFERENCE MATCHING

**Depends on:**
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- Persona Visual Identity implementation
- Image generation/candidate lifecycle
- `09_IMAGE_REGENERATION_ADMIN_FEEDBACK.md`
- `10_IMAGE_REVIEW_SHORTLIST_ADMIN_WORKFLOW.md`
- `12_IMAGE_MODEL_EVALUATION.md`

**Goal:** Make persona identity consistency a first-class, testable part of the image pipeline. Every generated image must be traceable to the visual identity and reference-image set used to generate it, and Admin must be able to review whether the result matches the intended persona.

---

# 1. Product requirement

The product will always maintain reference images for a persona's visual identity.

The image pipeline must use those references together with the structured visual identity.

Conceptually:

```text
Persona
  ↓
Active Visual Identity Version
  ↓
Structured Physical / Visual Details
  +
Reference Images
  ↓
PromptCompiler
  ↓
Image Model
  ↓
Generated Candidates
  ↓
Identity Consistency Review
```

Reference images are not optional for the normal persona image-generation workflow.

If the current implementation permits generation without references, do not silently claim that the requirement is satisfied. Document the existing behavior and close the gap.

---

# 2. Standard reference-image categories

The system should support a standard reference set.

Minimum categories:

```text
FRONT
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

The exact enum names should follow the existing Persona Visual Identity implementation.

Optional additional categories may exist.

Do not create duplicate reference-image models if the current persona implementation already supports these concepts.

---

# 3. Reference image requirements

Each identity reference should retain:

- Reference ID
- Persona ID
- Visual identity version
- Category
- Asset ID/path
- Created timestamp
- Active/inactive status
- Admin metadata where supported

The image pipeline must know exactly which reference set was active when a generation occurred.

---

# 4. Identity versioning

Generation must bind to a specific visual identity version.

Example:

```text
Persona: Simran
Identity Version: V4
Generation: IMG-123
```

If Admin later changes:

```text
hair
body details
eye color
reference images
```

existing generated images must not silently change their historical identity metadata.

Future generations use the new active version.

---

# 5. Identity snapshot

At generation time, preserve enough information to reconstruct what was used.

Minimum:

```text
personaId
visualIdentityVersion
referenceImageIds
```

Where practical, also preserve a generation-time identity snapshot/reference.

Do not rely exclusively on the current persona record for historical audit.

---

# 6. Structured visual identity

The generation pipeline must use the existing persona visual identity fields.

Examples include configured fields such as:

- height
- weight/build
- body proportions
- skin/complexion
- eye color
- hair color
- hairstyle
- distinguishing marks
- muscularity
- other supported physical attributes

For sensitive/private visual fields:

- follow the existing Persona Visual Identity security/storage design
- do not expose them unnecessarily in ordinary candidate review
- do not copy them into logs

The image-generation implementation must use the fields that are intended for image generation by the existing persona model.

---

# 7. Identity precedence

The generation path must have a deterministic precedence.

Recommended conceptual order:

```text
Identity constraints
        >
Reference identity
        >
Scene requirements
        >
Styling / presentation
        >
Optional creative details
```

The exact precedence must follow the existing PromptCompiler contract.

Do not let a scene prompt accidentally override persistent persona identity.

Example:

```text
Persona:
brown eyes

Seed:
"blue eyes, looking at camera"

```

The system must follow the established identity-precedence rule rather than blindly copying contradictory seed text.

If the current PromptCompiler already implements this, reuse it and add tests rather than creating another compiler.

---

# 8. Reference-image delivery to provider

The provider adapter must receive the reference images in the format supported by the selected model/provider.

Requirements:

- Preserve reference ordering where meaningful.
- Preserve category metadata internally.
- Handle unsupported reference formats explicitly.
- Do not silently drop all references.
- Record when a provider/model cannot accept the required reference set.

If a model has a different image-input contract, adapt inside the provider abstraction rather than changing persona identity semantics.

---

# 9. Generated-image metadata

Every generated candidate should be traceable to:

```text
Persona
Visual identity version
Reference-image set
Generation/job
Provider
Model
Seed prompt
```

This allows Admin to answer:

> "Which identity references produced this image?"

without guessing.

---

# 10. Admin identity preview

Inside the Persona Admin visual-identity area, show the configured reference set.

Example:

```text
SIMRAN KOHLI — VISUAL IDENTITY V4

[Front]       [Face Close]
[Left]        [Right]
[Back]

Identity details
----------------
Height: ...
Build: ...
Skin: ...
Eyes: ...
Hair: ...
Distinguishing marks: ...

[Save Identity]
```

The exact fields must come from the existing persona implementation.

---

# 11. Generation review identity context

When reviewing a candidate, Admin should be able to open:

```text
Identity used
```

and see:

```text
Persona
Identity version
Reference categories
Reference thumbnails where permitted
```

This must be read-only from the candidate review screen.

Changing the persona identity must happen through the Persona Visual Identity workflow.

---

# 12. Identity mismatch reporting

Admin must be able to mark a generated candidate as having an identity problem.

Do not automatically classify an image as a mismatch without evidence.

Possible review state/reason:

```text
IDENTITY_MISMATCH
```

or an equivalent existing candidate review reason.

Examples:

```text
Face does not match reference
Hair differs materially
Body proportions inconsistent
Distinguishing mark missing
```

Use existing candidate remark infrastructure where possible.

---

# 13. Identity consistency is a review signal, not an automatic truth score

The first implementation should not pretend that a simple automated similarity score proves identity.

The system should support:

```text
Admin review
+
reference visibility
+
identity metadata
+
optional future automated score
```

If an automated identity comparison is later introduced, its result must be clearly labeled as an automated signal.

Do not present it as ground truth.

---

# 14. Optional automated identity check

If the existing architecture already has a suitable image-comparison mechanism, it may be integrated behind an abstraction.

Example:

```text
IdentityConsistencyChecker
```

Possible output:

```text
status: PASS / REVIEW / FAIL
score: optional
method: provider/model/version
```

But this is **optional for this task**.

Do not add a new external vision model merely to satisfy this document if the current system has no such infrastructure.

The mandatory requirement is traceability + Admin review.

---

# 15. Admin candidate comparison

The candidate review UI should allow Admin to compare:

```text
Reference face
        vs
Generated candidate
```

and, where useful:

```text
Front reference
Face-close reference
Generated image
```

The purpose is visual inspection.

Do not automatically rank candidates.

---

# 16. Regeneration integration

When Admin says:

```text
"Face does not match reference."
```

and requests regeneration:

The new generation must retain:

```text
same persona
same active identity version unless Admin intentionally changes it
same relevant reference set
original scene
admin correction
```

The regeneration must not accidentally use the current identity version if the original generation was based on an older explicitly selected version, unless the Admin intentionally requests the newer identity.

This preserves lineage and reproducibility.

---

# 17. Identity changes

When Admin edits the persona visual identity:

```text
V4
 ↓
Edit
 ↓
V5
```

Do not mutate V4 in place if the current architecture supports versioning.

New generations should use V5.

Existing candidates remain associated with V4.

---

# 18. Reference replacement

If Admin replaces a reference:

```text
FRONT V4
   ↓
new front image
   ↓
V5
```

the old reference must remain traceable to historical generations.

Do not replace the underlying asset in a way that makes historical generations point to a different face reference.

---

# 19. Missing reference handling

If the required standard reference set is incomplete:

The system must have an explicit behavior.

Preferred:

```text
Generation request
      ↓
Validate identity
      ↓
Missing required reference
      ↓
Clear Admin error
```

Do not silently generate as if a complete reference set existed.

If the product intentionally allows partial reference sets for a specific model, that exception must be explicit and recorded.

---

# 20. Model evaluation integration

Task 12 model evaluation must use the same identity reference set when comparing models.

Example:

```text
Evaluation E123

Persona: Simran
Identity: V5
References: Front + Face + Left + Right + Back

Model A → 4 candidates
Model B → 4 candidates
Model C → 4 candidates
```

This prevents the evaluation from comparing:

```text
Model A + one identity reference
```

against:

```text
Model B + five identity references
```

unless that difference is intentionally being tested.

---

# 21. Image warehouse metadata

Saved warehouse images should retain:

```text
personaId
identityVersion
referenceSet
candidateId
generationId
provider
model
seed prompt
createdAt
status
admin remark
```

This is necessary for future content review.

---

# 22. Security

Reference images can contain highly identifying visual information.

Enforce existing authorization.

Verify:

- unauthenticated users cannot retrieve Admin reference assets
- one persona's references cannot be accessed through another persona's IDs
- private reference assets remain separately protected
- image URLs/assets are not publicly exposed unintentionally
- logs do not contain private image URLs or credentials

Use the existing asset authorization mechanism.

Do not create a parallel security model.

---

# 23. API requirements

Reuse existing persona/image APIs where possible.

Required capabilities:

```text
GET persona visual identity
GET identity references
GET generation identity context
```

If missing, add the smallest endpoints needed.

Possible conceptual routes:

```text
GET /v1/admin/personas/{personaId}/visual-identity
GET /v1/admin/personas/{personaId}/visual-identity/references
GET /v1/admin/images/jobs/{jobId}/identity
```

Exact paths must follow existing routing conventions.

---

# 24. Database requirements

Do not create a duplicate persona identity database.

Reuse the current Persona Visual Identity implementation.

Only add generation-time identity/reference snapshot fields if the existing image job/candidate schema cannot already provide traceability.

Potential fields:

```text
visual_identity_version
reference_image_ids
```

Use normalized tables or JSON according to existing project conventions.

---

# 25. Acceptance Test — Complete reference set

1. Configure one persona.
2. Create:
   - front
   - face close
   - left
   - right
   - back
3. Save identity version.
4. Generate image.
5. Inspect generation metadata.

Expected:

```text
correct persona
correct identity version
correct reference set
```

---

# 26. Acceptance Test — Identity update

1. Generate image using V4.
2. Change hair/reference image.
3. Save V5.
4. Generate another image.

Expected:

```text
old generation → V4
new generation → V5
```

No historical metadata changes.

---

# 27. Acceptance Test — Reference replacement

1. Generate using reference A.
2. Replace reference A.
3. Create V5.
4. Generate again.

Expected:

```text
generation 1 → old reference
generation 2 → new reference
```

---

# 28. Acceptance Test — Missing reference

Remove a required reference.

Attempt generation.

Expected:

- clear validation result
- no fake successful generation
- no silent fallback
- Admin knows which reference is missing

If the existing product contract intentionally permits partial references, test the documented exception instead.

---

# 29. Acceptance Test — Model comparison

Run the same persona/reference set through two models.

Verify:

- same identity version
- same reference IDs
- same logical seed
- different model IDs
- separate generation jobs
- candidates grouped correctly

---

# 30. Acceptance Test — Regeneration

1. Generate candidate.
2. Mark identity mismatch.
3. Add correction.
4. Regenerate.

Expected:

```text
original candidate preserved
new candidate created
parentCandidateId preserved
identity version preserved
reference set preserved
correction recorded
```

---

# 31. Acceptance Test — Security

Try accessing another persona's reference asset using:

- direct asset ID
- manipulated persona ID
- manipulated candidate ID

Expected:

```text
401/403/404 according to existing security contract
```

No asset leakage.

---

# 32. Definition of Done

This task is complete only when:

- [ ] Standard reference categories are supported.
- [ ] Persona identity version is bound to generation.
- [ ] Reference IDs are traceable.
- [ ] Generation metadata identifies the identity version.
- [ ] Existing PromptCompiler is reused.
- [ ] Identity precedence is tested.
- [ ] Admin can inspect identity context.
- [ ] Admin can compare candidate against references.
- [ ] Identity mismatch can be recorded.
- [ ] Regeneration preserves identity context.
- [ ] Identity updates create/retain historical versions according to the existing versioning model.
- [ ] Reference replacement does not corrupt historical generations.
- [ ] Missing-reference behavior is explicit.
- [ ] Model evaluation uses a controlled identity/reference set.
- [ ] Reference assets remain secured.
- [ ] Existing image pipeline tests remain green.
- [ ] New unit/integration/security tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 33. Cursor Implementation Rule

Before coding:

1. Inspect the existing Persona Visual Identity implementation.
2. Inspect reference-image storage and categories.
3. Inspect identity versioning.
4. Inspect PromptCompiler.
5. Inspect image job/candidate metadata.
6. Inspect Task 09 regeneration.
7. Inspect Task 12 model evaluation.
8. Reuse existing asset authorization.
9. Do not create a second persona identity system.
10. Do not introduce an automated face-recognition dependency unless already supported and explicitly required.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Implemented:
Existing identity components reused:
Reference categories:
Identity versioning:
Generation metadata:
Reference snapshot:
Admin UI:
API:
DB changes:
Security:
Tests:
Known gaps:
Git commit:
```

The task is complete only when an Admin can trace a generated image back to the exact persona identity version and reference-image set that produced it, and can visually review identity consistency before saving/shortlisting the candidate.
