# 27 — REAL PERSONA IMAGE GENERATION ACCEPTANCE TEST

## Purpose

This is the final task for the day before the product-quality model comparison.

The objective is to answer one simple question:

> **Has our actual image pipeline successfully generated images of our real configured Personas, using their real Persona visual metadata and identity reference images?**

This is not a synthetic pipeline test.

Use the existing production/test Personas:

```text
Richa
Ananya
```

Use their actual configured visual metadata and their actual identity reference images.

**Only use Personas that are explicitly configured as adults in the application.**

---

# 1. Critical requirement

Do NOT use:

```text
generic test images
generic faces
placeholder Personas
synthetic reference images
```

The test must use:

```text
Persona record
+
Persona visual metadata
+
Persona reference images
+
real image-generation engine
+
real OpenRouter model
```

The generated images must be stored through the actual image pipeline.

---

# 2. Test Personas

## Persona 1

```text
Name: Richa
```

Cursor must inspect the actual Persona record and report:

```text
Persona ID:
Age/adult status:
Visual profile version:
private part details:
Reference-set version:
Reference images:
```

Do not invent missing values.

---

## Persona 2

```text
Name: Ananya
```

Cursor must inspect the actual Persona record and report:

```text
Persona ID:
Age/adult status:
Visual profile version:
private part details:
Reference-set version:
Reference images:
```

Do not invent missing values.

---

# 3. Identity references

Use the existing reference-image system.

Where available, use:

```text
FRONT_FULL_BODY
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK_FULL_BODY
```

Record exactly which references were supplied to the model.

Do not silently omit references.

If the selected model supports fewer reference images, document that limitation.

---

# 4. Required seed prompts

Use these four seed prompts.

The wording should be preserved exactly as the seed prompt unless the selected model/API requires a documented formatting transformation.

## Prompt 1 — night party

```text
night scene, red party dress off shoulder slit from legs deep neck to show cleavage, drunk, happy, in mood to have after party with boyfriend
```

The system should combine this seed prompt with the Persona's configured visual identity.

The generated image should remain a fictional adult Persona depiction.

---

## Prompt 2 — coming out of sea

```text
coming out of sea, white bikini, wet, translucent, facing camera
```

Use the actual Persona identity references and visual metadata.

Record any provider/model restriction or transformation of the request.

---

## Prompt 3 — bathroom selfie

```text
bathroom selfie in office
```

Use the actual Persona identity and visual metadata.

---

## Prompt 4 — evening cafe

```text
evening at cafe
```

Use the actual Persona identity and visual metadata.

---

# 5. Prompt construction

Verify the actual generation request contains the appropriate combination of:

```text
Seed prompt
+
Persona identity
+
Persona visual metadata
+
Reference images
+
Image-generation skill/engine
+
Selected model
```

Record the final effective request metadata.

Do not expose API credentials in logs or reports.

---

# 6. Two Personas × four scenarios

Minimum matrix:

| Persona | Prompt 1 | Prompt 2 | Prompt 3 | Prompt 4 |
|---|---:|---:|---:|---:|
| Richa | TEST | TEST | TEST | TEST |
| Ananya | TEST | TEST | TEST | TEST |

Generate the configured candidate count for each request.

If the pipeline is configured for four candidates:

```text
2 Personas
×
4 prompts
×
4 candidates
=
32 candidate images
```

If a provider/model cannot return four candidates in one request, document the actual behavior.

---

# 7. Real provider requirement

This task must use a real OpenRouter image-generation model.

Do NOT use:

```text
Fake provider
Mock provider
Fake image
H2-only path
Local placeholder
```

The pipeline may still use its normal database/storage infrastructure.

The image itself must originate from the actual configured image provider.

---

# 8. Model

Use the model selected by Task 26.

Record:

```text
Provider:
Exact OpenRouter model ID:
Model version if available:
```

If Task 26 has not yet selected a model, Cursor must use the currently configured real OpenRouter image model and clearly record that fact.

Do not silently substitute a different model.

---

# 9. Generation metadata

For every generation job record:

```text
Job ID
Persona ID
Persona visual version
Reference-set version
Seed prompt
Provider
Model ID
Engine
Skill
Generation timestamp
Candidate count
Generation duration
Cost if available
```

---

# 10. Identity review

For every candidate, inspect:

### Face

```text
Does the person look like the configured Persona?
```

Check:

```text
face shape
eyes
nose
lips
jaw/chin
overall facial identity
```

### Hair

```text
color
style
length
```

### Skin

```text
tone/appearance
```

### Body

Where visually assessable:

```text
overall build
body proportions
height impression
configured physical characteristics
```

### Distinctive features

Check configured:

```text
moles
birthmarks
other distinctive marks
```

---

# 11. Identity classification

For every candidate record:

```text
IDENTITY:
Strong
Acceptable
Weak
Failed
Not assessable
```

And provide a short factual remark.

Do not use identity classification to create an automatic model ranking.

---

# 12. Prompt adherence

For every candidate verify:

```text
Scene
Clothing
Pose
Camera direction
Environment
Time/lighting
Activity
```

Record:

```text
PASS
PARTIAL
FAIL
NOT ASSESSABLE
```

---

# 13. Persona metadata adherence

Check whether the generated image reflects the configured Persona details.

Record:

```text
PASS
PARTIAL
FAIL
NOT ASSESSABLE
```

Do not claim a physical attribute is present if the image does not provide enough visual evidence.

---

# 14. Provider/model restriction behavior

For every request record:

```text
Accepted
Rejected
Modified
Failed technically
```

If rejected, distinguish:

```text
Provider policy
OpenRouter/model restriction
Unsupported request
Technical/API error
Unknown
```

Do not repeatedly retry a rejected request simply to bypass a restriction.

---

# 15. Candidate storage

Every successfully generated image must appear in the actual Persona Image Warehouse.

Verify:

```text
Persona association
Job association
Timestamp
Seed prompt
Model
Provider
Status
Storage location
```

The original generated asset must be retrievable.

---

# 16. Admin review

From Admin UI verify that an administrator can:

```text
Open Persona
→ open Image Warehouse
→ see generated candidates
→ see generation metadata
→ inspect candidate
→ add remark
→ shortlist
→ decline
→ save
→ request regeneration
```

Only test capabilities that are actually implemented.

If something is missing, record it as:

```text
IMPLEMENTATION GAP
```

---

# 17. Regeneration test

Select at least one candidate from Richa and one from Ananya where a concrete correction can be made.

Example:

```text
Correct the hair color to the configured Persona value while preserving the scene, clothing and identity.
```

Record:

```text
Parent candidate
Correction
Regeneration job
New candidate
Model
Reference set
Result
```

Verify parent/child lineage.

---

# 18. Identity after regeneration

Check whether the regenerated candidate still resembles the same Persona.

Record:

```text
Identity preserved
Identity degraded
Identity failed
```

Also record whether unrelated scene attributes changed unexpectedly.

---

# 19. Real-image acceptance evidence

Cursor must save evidence that the test actually used real generated assets.

Evidence should include:

```text
Job IDs
Candidate asset IDs
Model IDs
Generation timestamps
Persona IDs
```

Do not commit generated images into Git unless the existing repository policy explicitly requires it.

Use the configured image storage.

---

# 20. Product review table

Produce:

| Persona | Prompt | Candidates | Identity | Prompt adherence | Metadata adherence | Restriction | Warehouse |
|---|---|---:|---|---|---|---|---|
| Richa | 1 | | | | | | |
| Richa | 2 | | | | | | |
| Richa | 3 | | | | | | |
| Richa | 4 | | | | | | |
| Ananya | 1 | | | | | | |
| Ananya | 2 | | | | | | |
| Ananya | 3 | | | | | | |
| Ananya | 4 | | | | | | |

---

# 21. Critical test question

At the end answer:

> **Can our current image pipeline generate recognizable images of our actual Personas, from their real identity references and visual metadata, using a real OpenRouter image model?**

Answer:

```text
YES
PARTIALLY
NO
```

Then provide evidence.

---

# 22. Do not hide failures

If any of these occur:

```text
wrong face
wrong Persona
wrong body identity
reference images ignored
visual metadata ignored
prompt ignored
provider rejection
model rejection
generation failure
storage failure
metadata missing
Admin workflow failure
```

record them explicitly.

A failure is useful information at this stage.

Do not mark the test PASS merely because the API returned an image.

---

# 23. Definition of Done

- [ ] Richa verified as adult Persona.
- [ ] Ananya verified as adult Persona.
- [ ] Actual Persona visual metadata loaded.
- [ ] Actual reference images loaded.
- [ ] Real OpenRouter model used.
- [ ] Prompt 1 tested.
- [ ] Prompt 2 tested.
- [ ] Prompt 3 tested.
- [ ] Prompt 4 tested.
- [ ] Both Personas tested.
- [ ] Candidate images generated.
- [ ] Candidate images stored in Image Warehouse.
- [ ] Generation metadata persisted.
- [ ] Identity reviewed.
- [ ] Prompt adherence reviewed.
- [ ] Persona metadata adherence reviewed.
- [ ] Provider/model restrictions recorded.
- [ ] At least one Richa regeneration tested.
- [ ] At least one Ananya regeneration tested.
- [ ] Admin review workflow tested.
- [ ] Evidence IDs recorded.
- [ ] Final acceptance status recorded.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` updated.

---

# 24. Required completion report

Cursor must return:

```text
TASK 27 — REAL PERSONA IMAGE GENERATION ACCEPTANCE TEST

Overall status:

REAL PERSONA TEST:
YES / PARTIAL / NO

Richa:
Adult status:
Persona ID:
Visual version:
Reference set:
Model:
Provider:

Prompt 1:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Prompt 2:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Prompt 3:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Prompt 4:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Ananya:
Adult status:
Persona ID:
Visual version:
Reference set:
Model:
Provider:

Prompt 1:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Prompt 2:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Prompt 3:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Prompt 4:
Generation:
Candidates:
Identity:
Prompt adherence:
Metadata adherence:
Restriction:
Warehouse:
Notes:

Regeneration:
Richa:
Ananya:

Admin workflow:
PASS / PARTIAL / FAIL

Storage:
PASS / PARTIAL / FAIL

Metadata:
PASS / PARTIAL / FAIL

Evidence:
Job IDs:
Asset IDs:

Failures:

Implementation gaps:

Tests:
Passed:
Failed:
Skipped:

Files changed:

Commit:

Updated status file:
```

---

# 25. Important instruction to Cursor

This is an **acceptance test**, not an opportunity to rewrite the image architecture.

First run the actual pipeline.

Only modify code if a concrete blocker prevents the required test.

If a change is necessary:

```text
identify blocker
implement smallest fix
test fix
continue acceptance test
document change
```

Do not fabricate successful image-generation results.

---

# 26. End state

After this task we should have something materially different from the previous automated pipeline tests:

```text
REAL PERSONA
    ↓
REAL PERSONA VISUAL METADATA
    ↓
REAL IDENTITY REFERENCES
    ↓
REAL SEED PROMPT
    ↓
REAL OPENROUTER MODEL
    ↓
REAL GENERATED IMAGES
    ↓
REAL IMAGE WAREHOUSE
    ↓
ADMIN REVIEW
    ↓
IDENTITY / QUALITY / PROMPT EVALUATION
```

This is the first test that demonstrates the product loop with actual Persona identities rather than only proving that the infrastructure works.
