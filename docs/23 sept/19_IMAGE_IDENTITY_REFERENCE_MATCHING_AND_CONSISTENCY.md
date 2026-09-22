# 19 — IMAGE IDENTITY REFERENCE MATCHING & CONSISTENCY

**Goal:** Make Persona identity consistency a first-class, testable part of image generation.

**Depends on:** Image Pipeline Governance, Persona Visual Identity, Tasks 01–18.

---

## 1. Product objective

Every generated Persona image must be evaluated against the Persona's configured visual identity.

The production flow is:

```text
Persona
  ↓
Visual identity
  ↓
Identity reference images
  ↓
Physical / appearance attributes
  ↓
Seed prompt
  ↓
Image model
  ↓
Generated candidates
  ↓
Admin compares against identity references
  ↓
Shortlist / decline / regenerate
```

The generated image must not become the new identity automatically.

The Persona's approved identity remains the source of truth.

---

# 2. Identity source of truth

Inspect the existing Persona Visual implementation.

The image pipeline must use the currently active visual identity/version.

Identity information may include:

- height
- weight
- body proportions
- skin/color information
- eye color
- hair color
- hair style
- facial characteristics
- body characteristics
- muscularity
- distinctive marks
- moles
- birthmarks
- other configured physical attributes
- dressing/style information where configured
- reference images

Do not duplicate these fields into a second image-specific Persona profile.

---

# 3. Reference-image standard

The system should support a standard reference set.

Recommended identity references:

```text
FACE_CLOSE
FRONT
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

Optional additional references:

```text
FULL_BODY_FRONT
FULL_BODY_BACK
THREE_QUARTER_FRONT
THREE_QUARTER_BACK
OTHER
```

The exact enum/naming should follow the existing Persona Visual implementation if already established.

Admin must know what each uploaded reference represents.

---

# 4. Reference image management

Admin must be able to:

```text
Upload
View
Label
Replace
Remove
Activate/deactivate
```

reference images according to the existing Persona Visual UI.

Each reference must belong to the correct Persona identity/version.

Removing or replacing a reference must not silently rewrite historical generated images.

---

# 5. Identity versioning

When visual identity changes, the image pipeline must be able to determine which identity version was used for a generated image.

Example:

```text
Persona Simran
Identity v1
  ↓
Image A

Persona Simran
Identity v2
  ↓
Image B
```

Do not make historical Image Warehouse candidates appear to have been generated from the latest identity when they were created from an older version.

---

# 6. Generation metadata

Every image job/candidate should retain or be able to resolve:

```text
Persona ID
Identity version
Reference set/version
Seed prompt
Compiled prompt
Provider
Model
Generation timestamp
```

Use existing persistence structures where possible.

Do not create duplicate identity records unnecessarily.

---

# 7. Prompt construction

The production prompt must preserve the existing precedence:

```text
Persona identity
    >
scene requirements
    >
style / wardrobe / composition
    >
seed-specific instructions
```

A seed prompt must not accidentally override the Persona's fixed identity.

Example:

```text
Seed:
"woman with blonde hair swimming at Goa beach"

Persona:
black hair
```

The system must not blindly replace the Persona's configured black hair with blonde hair.

The final behavior should follow the established PromptCompiler identity precedence.

---

# 8. Reference images in generation

Verify the actual configured provider/model path.

Determine whether the current model/provider supports:

- image references;
- multiple references;
- image-to-image;
- reference conditioning;
- other identity-preservation mechanisms.

If the configured model does not support the intended reference mechanism:

```text
NOT SUPPORTED
```

must be recorded.

Do not pretend that attaching an image URL to a prompt guarantees identity preservation.

---

# 9. Generated candidate comparison

Admin Image Warehouse should make it possible to compare:

```text
REFERENCE SET
      vs
CANDIDATE
```

The UI should provide access to the relevant identity references without requiring Admin to navigate through unrelated pages.

A candidate review screen should make identity checking practical.

---

# 10. Admin review checklist

For each candidate Admin should be able to assess:

### Face

- face shape
- facial proportions
- eyes
- nose
- lips
- distinctive facial features

### Hair

- color
- length
- style

### Body

- height/proportions
- body shape
- configured physical characteristics

### Distinctive marks

- birthmarks
- moles
- other configured identifying features

### Overall identity

```text
Clearly same Persona
Possibly same Persona
Identity drift
```

These are review labels, not automatic quality scores.

---

# 11. Do not create automatic identity scores

Unless a dedicated identity-comparison system is explicitly implemented and validated:

Do NOT create:

```text
Identity score: 87%
Similarity: 92%
```

from arbitrary image metrics.

The current requirement is reliable Admin review against the reference set.

---

# 12. Candidate status

Identity review must integrate with the existing Image Warehouse states.

At minimum:

```text
Generated
Shortlisted
Declined
```

Use the existing status model if additional states already exist.

Admin remark should allow identity-specific feedback.

Examples:

```text
"Face is good; hair differs from reference."
"Body proportions consistent."
"Birthmark missing."
"Identity drift — decline."
```

---

# 13. Regeneration with identity correction

Admin may request regeneration after identifying an issue.

Example:

```text
Problem:
Hair does not match reference.

Correction:
Keep the same Persona and scene.
Match the hairstyle and hair color to the active identity references.
```

The regenerated image must:

- create a new candidate;
- preserve original candidate;
- preserve job lineage;
- preserve Persona identity version;
- record correction instruction;
- record provider/model;
- remain independently reviewable.

---

# 14. Identity correction must not mutate Persona

If Admin says:

```text
"Make her hair longer"
```

during image regeneration, that does NOT change Persona identity.

It is a generation instruction unless Admin separately edits the Persona Visual profile.

The two operations are distinct:

```text
Edit Persona identity
```

vs

```text
Correct generated image
```

---

# 15. Reference set changes

If Admin replaces a reference image:

```text
old reference
    ↓
new reference
```

future generations use the active reference set.

Existing candidates retain their historical identity/reference lineage.

Do not retroactively relabel old images as generated from the new reference.

---

# 16. Private reference images

If the Persona implementation supports separately stored private reference images:

- keep them in their existing protected storage;
- do not expose them through public image APIs;
- do not include them in normal user-facing image responses;
- only use them in generation if explicitly supported by the configured image workflow;
- preserve existing access control.

This task must not weaken the existing privacy/security boundary.

---

# 17. Image Warehouse integration

The Warehouse should expose:

```text
Candidate
Persona
Identity version
Reference set
Provider
Model
Seed
Generation time
Status
Admin remark
```

Admin should be able to open:

```text
Candidate
   ↓
Reference comparison
   ↓
Review
   ↓
Shortlist / Decline / Regenerate / Save
```

---

# 18. Identity lineage

A candidate must never become ambiguous about its origin.

Minimum lineage:

```text
Persona
  ↓
Identity version
  ↓
Reference set
  ↓
Image job
  ↓
Candidate
  ↓
Regeneration candidate (optional)
```

For regeneration:

```text
Candidate A
   ↓
Correction instruction
   ↓
Candidate B
```

Candidate A remains intact.

---

# 19. Test matrix

Use at least 2–3 test Personas from Task 18.

For each Persona:

```text
Reference set
    ↓
Generate 4 candidates
    ↓
Review identity consistency
    ↓
Select candidates
    ↓
Regenerate one candidate with correction
    ↓
Review regenerated candidate
```

Record observations.

---

# 20. Identity regression test

Change a non-image generation setting, such as scene:

```text
Beach
→
Mountain
```

Verify that the Persona identity references remain the same.

Then change an image-generation model.

Verify the same identity input is supplied to the new model where supported.

---

# 21. Identity version regression test

1. Generate images using Identity v1.
2. Update Persona visual identity.
3. Generate using Identity v2.
4. Inspect both Warehouse records.

Expected:

```text
Old candidates → Identity v1
New candidates → Identity v2
```

No historical relabeling.

---

# 22. Missing reference test

Remove/deactivate a required reference.

Attempt generation.

The system must either:

- use the remaining supported reference configuration; or
- clearly report that required identity references are unavailable.

Do not silently pretend the missing reference was supplied.

---

# 23. Provider capability test

For each candidate model from Task 18 document:

```text
Reference images supported?
Multiple references supported?
Identity conditioning supported?
Input format supported?
Limitations?
```

If unsupported, record the limitation.

Do not modify the Persona identity system to compensate for provider limitations.

---

# 24. Admin security test

Verify:

- only authorized Admin can view identity references in Admin;
- normal users cannot access private references;
- image candidate APIs retain existing ownership rules;
- reference URLs cannot be used to bypass authorization;
- generated image URLs remain protected as designed.

---

# 25. Acceptance criteria

### AC-01

A generated candidate records the Persona identity version used.

### AC-02

The candidate can be compared against the correct active/historical reference set.

### AC-03

Changing Persona identity creates a distinguishable identity version.

### AC-04

Historical candidates retain their original identity lineage.

### AC-05

Admin can record identity review remarks.

### AC-06

Admin can regenerate a candidate with a correction without overwriting the original.

### AC-07

Regeneration preserves Persona identity lineage.

### AC-08

The configured provider/model receives the intended identity/reference inputs where supported.

### AC-09

Unsupported provider reference capabilities are reported honestly.

### AC-10

Private reference images remain protected.

---

# 26. Definition of Done

- [ ] Standard reference types are supported.
- [ ] Admin can manage reference images.
- [ ] Active identity version is resolved correctly.
- [ ] Image jobs record identity lineage.
- [ ] Candidate records retain identity lineage.
- [ ] Reference comparison is available in Image Warehouse.
- [ ] Admin can record identity observations.
- [ ] Regeneration preserves identity lineage.
- [ ] Original candidates are never overwritten.
- [ ] Identity changes do not rewrite historical candidates.
- [ ] Provider reference capabilities are documented.
- [ ] Private references retain existing security.
- [ ] 2–3 Persona tests are completed or explicitly marked not verified.
- [ ] Existing image tests remain green.
- [ ] New identity/reference tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 27. Cursor implementation instructions

Before changing code:

1. Inspect the existing Persona Visual implementation.
2. Inspect reference image storage.
3. Inspect identity versioning.
4. Inspect `PromptCompiler`.
5. Inspect Image Warehouse/candidate persistence.
6. Inspect regeneration implementation.
7. Inspect provider input format.
8. Reuse existing structures wherever possible.
9. Do not create duplicate Persona identity fields.
10. Do not create automatic identity scores.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Task 19:
Reference types:
Reference management:
Identity version:
Prompt integration:
Provider reference support:
Candidate lineage:
Warehouse comparison:
Regeneration:
Security:
Tests:
Known gaps:
Files changed:
Git commit:
```

---

# 28. Completion report

Cursor must return:

```text
TASK 19 — IDENTITY REFERENCE MATCHING

Status:

Reference images:
Types:
Upload/manage:
Storage/security:

Identity:
Active version:
Historical lineage:

Generation:
Identity supplied:
References supplied:
Provider/model capability:

Warehouse:
Comparison:
Remarks:
Statuses:

Regeneration:
Lineage:
Original preserved:

Testing:
Personas:
Candidates:
Identity review:
Passed:
Failed:
Skipped:

Security:

Known gaps:

Files changed:

Commit:
```

**Critical rule:** A generated image is never considered the Persona's identity merely because it was generated successfully. The configured Persona Visual identity and approved reference set remain the source of truth.
