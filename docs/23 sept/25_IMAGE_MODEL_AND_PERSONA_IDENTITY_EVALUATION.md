# 25 — IMAGE MODEL & PERSONA IDENTITY EVALUATION

**Purpose:** Run the first controlled product-quality evaluation of the Image Pipeline using real image models and 2–3 Personas.

**Depends on:** Tasks 18–24.

**This is a product evaluation task, not an infrastructure task.**

The goal is to determine, with evidence:

1. which configured image models can produce acceptable outputs;
2. how consistently a Persona's identity is preserved;
3. how well the system follows Persona visual metadata;
4. how well reference images maintain identity;
5. how regeneration responds to corrections;
6. latency and cost for comparable generations.

Do **not** automatically declare a model "best". Capture comparable evidence for product review.

---

# 1. Evaluation principle

Every model must receive the same effective inputs wherever technically possible:

```text
Persona
+
Persona visual metadata
+
standard reference-image set
+
same seed prompt
+
same generation settings
```

Do not change the prompt to make one model look better unless the evaluation explicitly records that difference.

---

# 2. Models

Evaluate **2–3 OpenRouter-supported image models**.

The exact model identifiers must be selected from the currently available/configured OpenRouter catalog.

Record:

```text
Provider:
Model ID:
Model display name:
Generation parameters:
Reference-image support:
Input image limits:
Output resolution:
```

If a model cannot support the required reference-image workflow, mark that capability explicitly instead of silently changing the test.

---

# 3. Important product requirement

The image-generation model should be capable of supporting the product's intended fictional-persona use cases without the evaluation assuming that every model supports every request.

Record provider/model restrictions factually:

```text
Supported
Restricted
Rejected
Unknown
```

Do not modify prompts to bypass provider safeguards.

---

# 4. Persona sample

Use **2–3 Personas** with meaningfully different visual identities.

Recommended evaluation set:

```text
Persona A — female
Persona B — male
Persona C — optional additional Persona
```

The actual Personas must come from the existing application.

Do not create temporary Personas unless necessary for testing.

---

# 5. Persona metadata snapshot

Before generating, capture the exact Persona visual configuration used.

At minimum:

```text
Height
Weight/build
Skin/body color or tone
Eye color
Hair color
Hair style
Muscularity/body build
Distinctive marks
Moles
Birthmarks
Other configured physical attributes
```

For female Personas, where configured:

```text
Breast/body proportions
Cup size
Belly
Hips
```

For male Personas, use the corresponding configured body attributes.

Do not invent values that are not present in the Persona record.

---

# 6. Reference image standard

Each Persona should have a standard identity reference set.

Recommended set:

```text
01 — Front / full-body
02 — Face close-up
03 — Left profile
04 — Right profile
05 — Back / full-body
```

Optional:

```text
06 — Three-quarter face
07 — Three-quarter body
08 — Additional distinctive-feature reference
```

Each reference must be explicitly labeled in the Persona system.

Example:

```text
FRONT_FULL_BODY
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK_FULL_BODY
```

Do not rely on filenames alone.

---

# 7. Reference-image metadata

For every reference image record:

```text
Persona ID
Reference type
Asset ID
Upload timestamp
Active/inactive status
Admin remark if any
```

If versioning exists, record the active reference-set version.

---

# 8. Identity baseline

Before model comparison, confirm that the reference set itself is internally coherent.

Admin/product reviewer should inspect:

```text
face
hair
eyes
skin tone
body proportions
distinctive marks
overall silhouette
```

Record:

```text
REFERENCE SET ACCEPTED
```

or:

```text
REFERENCE SET NEEDS CORRECTION
```

Do not evaluate model quality against a reference set that has not been accepted.

---

# 9. Test prompt set

Use a fixed baseline prompt set.

Minimum scenarios:

### Scenario A — simple portrait

```text
Persona facing camera in a natural environment.
```

### Scenario B — full body

```text
Persona standing naturally, full body visible.
```

### Scenario C — movement

```text
Persona performing a simple physical activity.
```

### Scenario D — environment

```text
Persona in a recognizable outdoor environment.
```

### Scenario E — clothing variation

```text
Persona wearing a specified outfit.
```

### Scenario F — difficult composition

```text
Persona facing a specified direction or pose while maintaining identity.
```

The exact wording should be stored with each generation.

---

# 10. Seed prompt

Every test generation must preserve the exact seed prompt.

Store:

```text
seedPrompt
```

Do not overwrite the original prompt after regeneration.

Regeneration must have separate lineage.

---

# 11. Generation input snapshot

Every candidate should be traceable to:

```text
Persona ID
Persona visual version
Reference-set version
Seed prompt
Provider
Model
Engine
Skill
Generation configuration
```

If the implementation already persists this, verify it.

If not, record the missing metadata as a pipeline gap.

---

# 12. Candidate count

For each test generation:

```text
4 candidates
```

unless the selected model technically cannot provide the configured count.

If fewer than four are returned:

```text
record actual count
record provider/model limitation
```

Do not fabricate candidates.

---

# 13. Model comparison matrix

For each:

```text
Persona × Scenario × Model
```

record:

```text
Job ID
Candidate IDs
Generation time
Success/failure
Candidate count
Cost status/value
Reviewer notes
```

Example:

| Persona | Scenario | Model | Job | Candidates | Duration | Cost | Result |
|---|---|---|---|---:|---:|---:|---|
| A | Portrait | Model 1 | | | | | |
| A | Portrait | Model 2 | | | | | |
| A | Portrait | Model 3 | | | | | |

---

# 14. Identity evaluation dimensions

Each candidate should be reviewed for:

### Face identity

```text
Facial structure
Eyes
Nose
Lips
Jaw/chin
Overall recognizability
```

### Hair

```text
Color
Style
Length
General appearance
```

### Skin

```text
Tone
General appearance
```

### Body identity

```text
Height impression
Body proportions
Build
Muscularity
Configured physical attributes
```

### Distinctive markers

```text
Moles
Birthmarks
Other configured distinctive features
```

---

# 15. Reference similarity

Compare generated candidates against the reference set.

Review:

```text
front ↔ generated front
face close ↔ generated face
left profile ↔ generated left profile
right profile ↔ generated right profile
back ↔ generated back
```

The purpose is identity consistency, not pixel-level image similarity.

---

# 16. Visual quality dimensions

Separately review:

```text
Anatomical coherence
Hands
Eyes
Face
Hair
Clothing
Lighting
Background
Pose
Composition
Image artifacts
```

Do not allow visual-quality observations to be confused with identity observations.

---

# 17. Prompt adherence

For each candidate review:

```text
Did the model follow the requested:
- location?
- clothing?
- pose?
- camera direction?
- time/lighting?
- activity?
```

Record failures separately from identity failures.

---

# 18. Persona metadata adherence

Verify whether generated images reflect configured Persona details.

Examples:

```text
hair color
eye color
body build
height impression
distinctive marks
configured physical attributes
```

If a detail is not visually determinable from the output, mark:

```text
NOT ASSESSABLE
```

Do not infer correctness from insufficient evidence.

---

# 19. Identity consistency across candidates

For each four-image batch ask:

```text
Do all four candidates appear to be the same Persona?
```

Record:

```text
Consistent
Mostly consistent
Inconsistent
```

Also record the reason.

---

# 20. Identity consistency across scenarios

Compare the same Persona across:

```text
portrait
full body
movement
environment
clothing
difficult pose
```

Record whether the identity remains recognizable.

---

# 21. Identity consistency across models

For the same Persona and prompt:

```text
Model 1
Model 2
Model 3
```

Review the reference consistency independently for each.

Do not average identity and image-quality observations into one unexplained number.

---

# 22. Regeneration test

Select candidates that have a concrete correctable issue.

Example:

```text
Hair color incorrect.
```

Admin gives a correction:

```text
Keep the same Persona identity and scene, but correct the hair color to the configured Persona value.
```

Regenerate.

Verify:

```text
parent candidate
correction
new candidate
model
reference set
lineage
```

are all traceable.

---

# 23. Regeneration preservation

After correction, verify the system preserves unrelated properties where requested:

```text
Persona identity
scene
pose
clothing
camera
```

unless the correction explicitly changes them.

Record which properties changed unexpectedly.

---

# 24. Candidate review workflow

Admin must be able to:

```text
View candidate
Select candidate
Add remark
Decline candidate
Save candidate
Request regeneration
```

If a capability is not yet implemented, record it as a blocker for the relevant Admin task rather than pretending it exists.

---

# 25. Warehouse lineage

For every selected candidate verify the Warehouse retains:

```text
generation job
Persona
seed prompt
provider
model
engine
skill
reference set
identity version
generation timestamp
Admin remarks
candidate status
```

---

# 26. Candidate statuses

Use the existing product status model.

Expected conceptual states:

```text
GENERATED
SHORTLISTED
DECLINED
SAVED
POSTED
```

Do not introduce duplicate status systems if the repository already has equivalent states.

---

# 27. Product reviewer protocol

The same reviewer should review comparable candidates where practical.

Review in this order:

1. Identity
2. Prompt adherence
3. Persona metadata adherence
4. Visual quality
5. Artifacts
6. Overall usability

This prevents background quality from dominating identity evaluation.

---

# 28. Blind model review

Where practical, temporarily hide model names from the reviewer.

Use:

```text
Candidate A
Candidate B
Candidate C
```

Then reveal model metadata after review.

This reduces model-name bias during visual evaluation.

---

# 29. Do not use automated face recognition as the sole decision

Automated similarity measurements may be used as supporting evidence if already available.

They must not replace human visual review for this product evaluation.

Do not create a new biometric identification system for this task.

---

# 30. Evaluation record

Create a structured evaluation record for every candidate:

```text
Evaluation ID
Persona
Scenario
Model
Job
Candidate
Reviewer
Identity observations
Prompt adherence observations
Persona metadata observations
Visual quality observations
Artifacts
Admin decision
Remarks
Timestamp
```

---

# 31. Model restrictions

For every tested model record:

```text
generation accepted
generation rejected
specific provider/model restriction
```

Do not label a model "unrestricted".

Use factual language:

```text
Accepted this test request
Rejected this test request
Provider/model policy restriction encountered
Unknown
```

---

# 32. Cost comparison

For comparable successful generations record:

```text
Model
Jobs
Candidates
Duration
Recorded/estimated cost
```

Do not declare a cost winner automatically.

---

# 33. Latency comparison

Record:

```text
Model
Generation duration
Candidate count
Failure/timeouts
```

Use the same measurement boundaries for all models.

---

# 34. Failure analysis

Classify failures separately:

```text
Provider rejection
Provider error
Timeout
Invalid response
Storage error
Pipeline error
Prompt adherence failure
Identity failure
Visual artifact
```

A provider rejection is not the same thing as a technical pipeline failure.

---

# 35. Minimum evaluation dataset

Before making a product decision, aim for:

```text
2–3 Personas
×
2–3 Models
×
at least 4 scenarios
×
4 candidates
```

This creates enough examples to observe consistency rather than relying on one image.

If operational constraints prevent this, document exactly what was tested.

---

# 36. Evaluation report

Cursor must produce:

```text
Model:
Persona:
Scenario:
Candidates:
Identity:
Prompt adherence:
Persona metadata adherence:
Visual quality:
Artifacts:
Latency:
Cost:
Restrictions/failures:
Regeneration result:
Admin decision:
Remarks:
```

Then provide aggregate tables.

---

# 37. No automatic winner

The system/report must not output:

```text
BEST MODEL
WINNER
RECOMMENDED MODEL
```

Instead report measured observations:

```text
Model A:
- identity observations
- quality observations
- latency
- cost
- restrictions

Model B:
...
```

The product team will make the model-selection decision after reviewing the evidence.

---

# 38. Acceptance criteria

### AC-01
2–3 real Personas can be evaluated.

### AC-02
Each Persona has a documented reference set.

### AC-03
Reference images have explicit types.

### AC-04
Persona visual metadata is included in generation input.

### AC-05
Seed prompts are preserved.

### AC-06
Model/provider metadata is preserved.

### AC-07
Four candidates are generated where supported.

### AC-08
Candidate lineage is preserved.

### AC-09
Candidates can be reviewed in Admin.

### AC-10
Regeneration preserves parent/child lineage.

### AC-11
Identity can be reviewed separately from image quality.

### AC-12
Prompt adherence can be reviewed separately from identity.

### AC-13
Model restrictions are recorded factually.

### AC-14
Latency and cost are recorded where available.

### AC-15
No model is automatically ranked or declared the winner.

---

# 39. Definition of Done

- [ ] 2–3 Personas selected.
- [ ] Reference sets verified.
- [ ] Reference labels verified.
- [ ] Persona visual metadata captured.
- [ ] 2–3 image models selected.
- [ ] Model capabilities/restrictions documented.
- [ ] Baseline prompts created.
- [ ] Real generation completed.
- [ ] Four candidates tested where supported.
- [ ] Identity reviewed.
- [ ] Prompt adherence reviewed.
- [ ] Persona metadata adherence reviewed.
- [ ] Visual quality reviewed.
- [ ] Candidate lineage verified.
- [ ] Regeneration tested.
- [ ] Cost recorded where available.
- [ ] Latency recorded.
- [ ] Failures documented.
- [ ] Admin workflow verified.
- [ ] Evaluation report created.
- [ ] No automatic model winner produced.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` updated.

---

# 40. Cursor instructions

Before implementation/testing:

1. Read Tasks 18–24.
2. Read `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`.
3. Inspect current Persona visual/reference implementation.
4. Inspect current image-generation request construction.
5. Inspect current model configuration.
6. Inspect current candidate lineage.
7. Inspect current Warehouse/Admin UI.
8. Inspect current regeneration implementation.
9. Reuse existing structures.

Do not redesign the image pipeline for the evaluation.

If something required for evaluation is missing:

1. identify the smallest required implementation;
2. implement it;
3. test it;
4. document it.

Do not silently work around missing product behavior.

---

# 41. Required completion report

Cursor must return:

```text
TASK 25 — IMAGE MODEL & PERSONA IDENTITY EVALUATION

Status:

Personas tested:
Models tested:

Reference sets:
PASS / PARTIAL / FAIL

Generation:
PASS / PARTIAL / FAIL

Candidates:
Expected:
Actual:

Identity evaluation:
PASS / PARTIAL / FAIL

Persona metadata adherence:
PASS / PARTIAL / FAIL

Prompt adherence:
PASS / PARTIAL / FAIL

Visual quality review:
PASS / PARTIAL / FAIL

Regeneration:
PASS / PARTIAL / FAIL

Warehouse lineage:
PASS / PARTIAL / FAIL

Admin review workflow:
PASS / PARTIAL / FAIL

Cost:
AVAILABLE / ESTIMATED / UNAVAILABLE

Latency:
Recorded / Not recorded

Model restrictions:
Summary:

Failures:

Known gaps:

Tests:
Passed:
Failed:
Skipped:

Files changed:

Commit:

Evaluation evidence location:
```

**Critical rule:** This task produces evidence for product decisions. It must not silently convert a small sample into a universal conclusion about model quality or restrictions.
