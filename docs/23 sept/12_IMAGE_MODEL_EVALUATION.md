# 12 — IMAGE MODEL EVALUATION & CANDIDATE COMPARISON

**Depends on:**
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- `IMAGE_GENERATION & CANDIDATE CREATION.md`
- `02_PERSONA_VISUAL_IDENTITY.md`
- `10_IMAGE_REVIEW_SHORTLIST_ADMIN_WORKFLOW.md`
- `11_IMAGE_COST_SLA_ANALYTICS.md`

**Goal:** Add a controlled, repeatable way for Admin to evaluate image models through the existing OpenRouter image pipeline before selecting the production model.

---

# 1. Purpose

The system must not assume that the first configured image model is the best model for the product.

We need to test multiple candidate image models against the same:

- Persona
- Visual identity
- Reference images
- Seed prompt
- Generation settings
- Candidate count

The purpose is to compare actual outputs and operational data.

The system must support testing **2–3 candidate image models** initially.

This is an evaluation workflow, not an automatic model-selection algorithm.

---

# 2. Product Requirement

Admin should be able to configure/test candidate models available through OpenRouter.

For each evaluation:

```text
Persona
+
Visual identity
+
Reference images
+
Same seed prompt
+
Model A
+
Model B
+
Optional Model C
```

Generate comparable candidate sets.

Example:

```text
Simran
Seed:
"Swimming in a yellow bikini at a Goa beach,
early morning, facing camera."

Model A → 4 candidates
Model B → 4 candidates
Model C → 4 candidates
```

The Admin then compares the results.

---

# 3. Critical Rule — Do Not Call Models “Unrestricted” Without Verification

The desired production model is one that does not unnecessarily restrict legitimate image-generation use cases required by the product.

However:

- Do not claim a model is "unrestricted" merely because it is available through OpenRouter.
- Do not bypass provider safety systems.
- Record actual provider/model behavior.
- Document restrictions encountered during evaluation.
- Separate product requirements from provider policy.

The evaluation result must state what was actually observed.

---

# 4. Model Candidate Configuration

Admin must be able to identify candidate models.

Minimum metadata:

```text
Provider
Model ID
Display name
Enabled/disabled
Evaluation status
Notes
```

Example:

```text
OpenRouter
model-id-1
Evaluation
```

Do not hardcode only one model into the UI.

The existing provider abstraction must remain intact.

---

# 5. Model Selection

The evaluation workflow should allow:

```text
Select Model A
Select Model B
Select Model C (optional)
```

For each evaluation run, persist the exact model ID.

This is critical because model availability/configuration can change later.

Never infer historical model identity from the current configuration.

---

# 6. Same Input Rule

For a fair comparison, each model must receive the same logical input.

Keep constant:

- Persona
- Visual identity version
- Reference images
- Seed prompt
- Scene
- Requested pose
- Requested wardrobe
- Generation count
- Relevant image settings

Only change:

```text
model
```

unless the evaluation explicitly documents another difference.

---

# 7. Prompt Compilation

Do not create a second prompt compiler for model evaluation.

Use the existing:

```text
PromptCompiler
```

and existing persona visual identity resolution.

The evaluation must preserve the same identity-over-scene precedence used by the production image pipeline.

---

# 8. Reference Images

Every evaluation should record the reference-image set/version used.

Example:

```text
Visual Identity Version: V3

References:
- front
- face_close
- left_profile
- right_profile
- back
```

The actual supported reference categories must follow the current Persona Visual Identity implementation.

If a model/provider cannot accept the same reference-image format, record that as an evaluation limitation instead of silently changing the comparison.

---

# 9. Evaluation Run

Create a persisted evaluation run.

Minimum:

```text
evaluationId
personaId
visualIdentityVersion
seedPrompt
createdAt
models
candidateCount
status
```

Each model gets its own generation/job records.

Suggested relationship:

```text
Evaluation
 ├── Model A generation
 │    ├── candidate 1
 │    ├── candidate 2
 │    ├── candidate 3
 │    └── candidate 4
 │
 ├── Model B generation
 │    ├── candidate 1
 │    ├── candidate 2
 │    ├── candidate 3
 │    └── candidate 4
 │
 └── Model C generation
      └── ...
```

Do not duplicate image storage infrastructure.

Reuse the existing image job/candidate pipeline.

---

# 10. Candidate Count

Default evaluation target:

```text
4 candidates per model
```

This matches the normal image-generation workflow.

If the current provider/model only supports a different count, record the actual count.

Do not fake four candidates by duplicating the same image.

---

# 11. Evaluation Metadata

Every evaluation candidate should retain:

- Evaluation ID
- Generation/job ID
- Candidate ID
- Persona ID
- Visual identity version
- Reference-image identifiers
- Provider
- Model ID
- Seed prompt
- Compiled prompt reference/metadata
- Created timestamp
- Generation latency
- Recorded cost, if available
- Candidate status

This allows later investigation.

---

# 12. Admin Comparison UI

Create an Admin comparison view.

Example:

```text
IMAGE MODEL EVALUATION

Persona: Simran Kohli
Identity: V3

Seed:
Swimming in a yellow bikini at a Goa beach,
early morning, facing camera.

--------------------------------------------------

MODEL A
[IMG] [IMG] [IMG] [IMG]

Latency:
Cost:
Failures:

--------------------------------------------------

MODEL B
[IMG] [IMG] [IMG] [IMG]

Latency:
Cost:
Failures:

--------------------------------------------------

MODEL C
[IMG] [IMG] [IMG] [IMG]

Latency:
Cost:
Failures:
```

The UI should make visual comparison easy.

---

# 13. Candidate Review

Each candidate must still support the normal review actions:

```text
View
Shortlist
Decline
Remark
Save
Regenerate
```

Reuse Task 10.

Do not create a separate incompatible candidate lifecycle for evaluation images.

---

# 14. Model-Level Notes

Admin should be able to add evaluation notes.

Example:

```text
Model:
Model A

Notes:
"Good face consistency.
Weak hands.
Good lighting.
Yellow bikini preserved."
```

These are evaluation notes, not generation prompts.

Persist them separately from candidate remarks where the current data model supports it.

---

# 15. Evaluation Dimensions

The system should allow Admin to assess at least:

### Identity consistency

Does the generated person remain visually consistent with the reference identity?

### Scene adherence

Does the output follow the seed/scene request?

### Pose adherence

Does the generated pose match the requested pose?

### Wardrobe adherence

Does clothing match the request?

### Image quality

Visual quality as judged by Admin.

### Naturalness

Does the result appear coherent/natural?

### Artifact quality

Hands, face, body, background, anatomy and other visible artifacts.

### Provider restrictions

Did the provider/model reject or alter the request?

These are review dimensions, not automated truth scores.

---

# 16. Do Not Automatically Rank Models

The system must collect evidence.

It must not automatically declare:

```text
Model A is best
```

unless a future explicit product decision system is designed.

For this task:

```text
Admin evaluates
```

and records observations.

---

# 17. Optional Structured Rating

If implemented, ratings should be independent dimensions.

Example:

```text
Identity consistency: 1–5
Scene adherence: 1–5
Image quality: 1–5
Pose adherence: 1–5
Artifact quality: 1–5
```

Do not convert these into a single "winner" score.

If ratings are added, show the underlying dimensions.

---

# 18. Model Restriction Testing

Evaluation should explicitly record provider behavior.

Possible result:

```text
Model A:
Generated successfully.

Model B:
Rejected request.

Model C:
Generated but modified requested content.
```

Do not hide failed/rejected candidates.

They are important evaluation evidence.

---

# 19. Cost Comparison

Use Task 11's real cost data.

For each model show:

```text
Generation count
Successful generations
Failed generations
Recorded cost
Cost coverage
Average recorded cost
Average latency
```

If cost is unknown:

```text
UNKNOWN
```

Never treat unknown as zero.

---

# 20. SLA Comparison

Use the existing SLA definitions.

Show:

```text
Model
p50
p95
success rate
SLA compliance
```

Only calculate metrics where enough data exists.

Do not change SLA targets per model merely to make one model look better.

---

# 21. Production Model Selection

This task does **not** automatically change the production model.

After evaluation, Admin/product owner can decide which model to configure as production.

The system should record the decision separately from raw evaluation results.

Example:

```text
Evaluation:
EV-123

Models tested:
A
B
C

Production model:
[configured separately]
```

Do not silently switch production configuration after an evaluation.

---

# 22. Model Configuration

The production model must remain configurable through the existing image provider configuration.

Expected concept:

```text
IMAGE_PROVIDER=openrouter
IMAGE_MODEL=<model-id>
```

Use the project's existing configuration names if they already exist.

Do not create duplicate environment variables.

---

# 23. OpenRouter Integration

Reuse the existing OpenRouter provider.

The evaluation should send model ID as configuration/input rather than creating separate provider classes for every model.

Architecture:

```text
ImageGenerationService
        ↓
ImageProvider
        ↓
OpenRouterImageProvider
        ↓
modelId
```

Do not create:

```text
ModelAProvider
ModelBProvider
ModelCProvider
```

unless technically required by provider differences.

---

# 24. Failure Handling

If one model fails:

```text
Model A → SUCCESS
Model B → FAILURE
Model C → SUCCESS
```

the evaluation itself should remain usable.

Do not fail the entire evaluation merely because one model failed.

The Admin UI must clearly show which model failed and why, subject to safe error redaction.

---

# 25. Security

Evaluation is Admin-only.

Verify:

- unauthenticated access rejected
- normal user access rejected
- Admin access allowed
- model IDs validated
- arbitrary provider configuration cannot execute unsafe/unapproved functionality
- secrets are never returned
- generated assets follow existing authorization rules

---

# 26. API

Reuse existing image generation APIs where possible.

Expected capability:

```text
POST /v1/admin/images/evaluations
GET  /v1/admin/images/evaluations
GET  /v1/admin/images/evaluations/{evaluationId}
POST /v1/admin/images/evaluations/{evaluationId}/notes
```

Exact routes should follow the existing Admin API conventions.

Do not duplicate existing job/status/result APIs.

---

# 27. Persistence

Persist the evaluation itself and its relationship to existing image jobs.

Prefer:

```text
image_evaluation
image_evaluation_models
```

or an equivalent design only if the existing schema cannot represent evaluation metadata.

Do not create unnecessary duplicate candidate/image tables.

The existing:

```text
image_jobs
generated_candidates
image_generation_events
```

should remain the source of generation truth.

---

# 28. Acceptance Test — Two Models

1. Configure Model A.
2. Configure Model B.
3. Create evaluation for one persona.
4. Use one seed prompt.
5. Use the same identity version.
6. Use the same reference set.
7. Generate four candidates per model.
8. Open comparison UI.

Expected:

```text
Model A → 4 candidates
Model B → 4 candidates
```

with independent metadata.

---

# 29. Acceptance Test — Three Models

Repeat with:

```text
A
B
C
```

Expected:

```text
3 model groups
4 candidates per group
12 candidates total
```

or the actual supported candidate count if provider limitations are documented.

---

# 30. Acceptance Test — Model Failure

Force Fake provider failure for Model B.

Expected:

```text
A → success
B → failure
C → success
```

The evaluation remains visible.

---

# 31. Acceptance Test — Identity Consistency

Run the same persona against two models using the same references.

Verify the Admin can inspect:

- reference set
- identity version
- outputs
- model metadata

Do not require an automated identity score in this task.

---

# 32. Acceptance Test — Repeatability

Run the same evaluation configuration twice.

Expected:

- separate evaluation IDs
- separate generation jobs
- same persona identity version
- same seed prompt
- same model IDs
- no accidental overwrite

Do not assume image outputs will be pixel-identical unless deterministic seeding is explicitly supported.

---

# 33. Acceptance Test — Production Safety

Perform evaluation.

Verify:

```text
production IMAGE_MODEL
```

does not change automatically.

---

# 34. Definition of Done

This task is complete only when:

- [ ] 2–3 model evaluation is supported.
- [ ] Evaluation is Admin-only.
- [ ] Same persona/input/reference set can be used across models.
- [ ] Exact model IDs are persisted.
- [ ] Existing PromptCompiler is reused.
- [ ] Existing Persona Visual Identity is reused.
- [ ] Existing image job/candidate pipeline is reused.
- [ ] Four candidates per model are supported where provider allows.
- [ ] Provider failures remain visible.
- [ ] Candidate lifecycle works.
- [ ] Candidate lineage works for regeneration.
- [ ] Model comparison UI works.
- [ ] Provider/model metadata is visible.
- [ ] Cost/latency/SLA data is shown where available.
- [ ] Unknown cost is not treated as zero.
- [ ] Provider restrictions are recorded rather than hidden.
- [ ] Production model is not changed automatically.
- [ ] Security tests pass.
- [ ] Integration tests pass.
- [ ] Existing image pipeline tests remain green.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 35. Cursor Implementation Rule

Before coding:

1. Inspect current OpenRouter provider implementation.
2. Inspect current model configuration.
3. Inspect current candidate/job schema.
4. Inspect current Persona Visual Identity implementation.
5. Inspect PromptCompiler.
6. Inspect Task 10 candidate review implementation.
7. Inspect Task 11 analytics.
8. Reuse all existing infrastructure.
9. Do not introduce a second image-generation path.
10. Do not silently change the production model.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Implemented:
Reused:
Models supported:
Evaluation API:
Admin UI:
Database changes:
Tests:
Provider restrictions observed:
Known gaps:
Git commit:
```

The task is complete only when Admin can run a real evaluation through OpenRouter using 2–3 configured candidate models and compare the resulting candidates without changing production configuration.
