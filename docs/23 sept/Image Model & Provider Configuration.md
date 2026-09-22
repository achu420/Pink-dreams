# IMAGE PIPELINE — TASK 04

## Image Model & Provider Configuration

**Date:** 2026-09-23
**Status:** READY FOR CURSOR IMPLEMENTATION
**Depends on:**

* `IMAGE_PIPELINE_GOVERNANCE.md`
* `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
* `IMAGE_PERSONA_VISUAL_IDENTITY.md`

---

## 1. Purpose

Implement the **image-model/provider configuration layer** so the image pipeline can use an OpenRouter image-generation model selected by configuration, without hard-coding a provider/model into the generation workflow.

This task is specifically about:

1. OpenRouter as the provider.
2. Configurable image model.
3. Model configuration visible to Admin.
4. Backend actually using the configured model.
5. No fake/admin-only configuration.
6. Generation requests recording exactly which model/provider was used.
7. A testable model-selection path before we start evaluating image quality tomorrow.

---

# 2. Product Requirement

The product owner must be able to change the image-generation model without changing application code.

The desired flow is:

```text
Admin
  ↓
Image Generation Settings
  ↓
Provider = OpenRouter
  ↓
Image Model = configured model
  ↓
Save
  ↓
User/Admin requests image
  ↓
Image Pipeline reads effective configuration
  ↓
OpenRouter
  ↓
Selected image model
  ↓
4 generated candidates
```

The model must **not** be hard-coded inside:

* `ImageGenerationService`
* `ImageGenerationHandler`
* `PromptCompiler`
* worker
* persona
* skill

---

# 3. Important Requirement — Model Selection

For the initial image-generation evaluation, use an **OpenRouter-supported image generation model**.

Do not create a fake "unrestricted model" abstraction.

The system should allow us to configure whichever OpenRouter image model we choose.

### Required configuration

At minimum:

```text
IMAGE_PROVIDER=openrouter
IMAGE_MODEL=<configured image model>
OPENROUTER_API_KEY=<secret>
```

Use the project's existing configuration conventions where possible.

Do **not** commit API keys.

---

# 4. Do NOT Assume a Specific Model Is Permanently Correct

The goal of this task is **model configurability**, not deciding which model is best.

Tomorrow we will evaluate 2–3 personas and compare generated results.

Therefore:

```text
Model A
      ↓
generate candidates
      ↓
evaluate

Model B
      ↓
generate candidates
      ↓
evaluate
```

The architecture must allow this without code changes.

---

# 5. Configuration Ownership

Determine whether the current project already has an AI/settings configuration system.

Reuse it if appropriate.

Do not create a second unrelated configuration system if one already exists.

The final architecture should have one clear concept of:

```text
Effective Image Generation Configuration
```

containing at least:

```text
provider
model
enabled
```

Optionally:

```text
timeout
maxCandidates
size
quality
```

only if the underlying provider supports them and the existing architecture needs them.

Do not add speculative settings.

---

# 6. Admin UI

Add an **Image Generation Settings** section to the existing Admin UI.

It must display:

### Provider

```text
OpenRouter
```

### Model

Example:

```text
<currently configured model>
```

### Enabled

```text
ON / OFF
```

### Save

Admin must be able to update the model.

---

# 7. No Fake Configuration

This is critical.

If the Admin UI displays:

```text
Image Model: XYZ
```

then the backend must actually use:

```text
XYZ
```

for the next generation request.

Do not implement:

```text
UI says model A
backend continues using model B
```

Do not implement a UI-only setting.

---

# 8. Effective Configuration

Every image-generation job must resolve the model at job creation/execution according to the project's existing configuration semantics.

Determine whether configuration should be:

### Option A — snapshot at job creation

```text
Create Job
   ↓
model=A
   ↓
queue
   ↓
worker
   ↓
uses A
```

This is preferred if configuration can change while jobs are queued.

OR, if existing architecture already defines a different reliable behavior, preserve it.

Document which behavior is implemented.

---

# 9. Job Metadata

Every image generation job must retain enough information to determine what actually happened.

At minimum:

```text
provider
model
```

should be available in:

* job detail
* candidate metadata where appropriate
* observability
* Admin UI

Example:

```json
{
  "jobId": "...",
  "provider": "openrouter",
  "model": "...",
  "status": "SUCCEEDED"
}
```

This is important because tomorrow we need to compare output quality **and** know exactly which model produced each image.

---

# 10. Observability

Reuse the existing image observability implementation.

Every generation should record:

```text
provider
model
start time
end time
latency
status/outcome
candidate count
error information
```

If token/cost information is available from the provider response, preserve it.

Do not fabricate cost values.

---

# 11. Cost Tracking

The existing requirement is that Admin must eventually be able to track image-generation cost.

For this task:

### Implement if provider response already exposes reliable usage/cost data.

Otherwise:

```text
cost = UNKNOWN
```

is acceptable.

Do **not** estimate or invent a cost.

Record enough provider/model information that cost analysis can be added later.

---

# 12. Admin Generation View

The Admin image-generation area should show the effective model used by each job.

Example:

```text
Image Job
--------------------------------
Job ID:       abc123
Persona:      Simran Kohli
Provider:     OpenRouter
Model:        <model>
Status:       SUCCEEDED
Candidates:   4
Started:      ...
Completed:    ...
Latency:      ...
```

This allows us to diagnose:

> "Which model generated this image?"

without checking server logs.

---

# 13. Provider Abstraction

Reuse the existing provider abstraction from the image pipeline.

Do not create another provider interface.

Desired architecture:

```text
ImageGenerationService
        ↓
ImageGenerationHandler
        ↓
ImageProvider
        ↓
OpenRouterImageProvider
```

Configuration determines the selected provider/model.

---

# 14. OpenRouter Request

Verify the existing OpenRouter image implementation.

It must send:

```text
configured model
+
compiled prompt
+
reference images where applicable
+
generation parameters
```

Do not bypass the existing:

```text
Persona Visual Identity
        ↓
PromptCompiler
        ↓
ImageGenerationHandler
```

pipeline.

---

# 15. Reference Images

This task must preserve the upcoming reference-image architecture.

The provider layer must be capable of receiving reference images.

Expected future request:

```text
Persona
   ↓
Visual Identity
   ↓
Reference Images
   ↓
Seed Prompt
   ↓
PromptCompiler
   ↓
Image Model
```

Do not implement reference-image matching logic in the provider.

That belongs to the identity/generation pipeline.

---

# 16. Candidate Count

The product requirement is:

> One generation request should produce **4 candidates**.

Verify the existing implementation.

If the current pipeline already supports this, reuse it.

If it does not, make the minimum change necessary so the generation request can explicitly request:

```text
candidateCount = 4
```

The Admin UI should show:

```text
Generated: 4 candidates
```

when four are successfully produced.

Do not claim four if fewer were actually generated.

---

# 17. Model Switching Test

Create an automated test proving:

```text
Configure Model A
        ↓
Create generation job
        ↓
Job records Model A
```

Then:

```text
Configure Model B
        ↓
Create another generation job
        ↓
Job records Model B
```

The test must prove the backend configuration is actually used.

---

# 18. Admin Test

Create an Admin/API integration test:

```text
GET image settings
        ↓
verify current model

PUT image settings
        ↓
set Model B

GET image settings
        ↓
verify Model B
```

Then create a generation job and verify:

```text
job.model == Model B
```

---

# 19. Security

API keys must never appear in:

* Admin UI
* job response
* job detail
* image observability
* logs
* database fields intended for display
* CSV/JSON exports

Never expose:

```text
OPENROUTER_API_KEY
Authorization: Bearer ...
```

---

# 20. Configuration Validation

Reject invalid configuration early.

At minimum:

```text
provider missing
model missing
```

must not silently result in a fake/default image model.

If OpenRouter is selected but the required API key is missing:

```text
generation should fail clearly
```

with a safe error message.

Never expose the secret.

---

# 21. Failure Behaviour

Test:

### Missing model

```text
generation → rejected clearly
```

### Missing API key

```text
generation → failed
```

### Provider failure

```text
job → FAILED/retry according to existing retry policy
```

### Invalid model

```text
provider error
→ captured in job/observability
→ no false SUCCEEDED
```

---

# 22. What This Task Must NOT Do

Do **not** implement:

* image quality scoring
* reference-image similarity scoring
* automatic candidate selection
* post publishing
* social media publishing
* storyline generation
* daily image generation
* LoRA
* image editing
* user-facing image feed
* content factory

Those are separate tasks.

---

# 23. Definition of Done

This task is complete only when all of the following are true:

### Backend

* [ ] Image provider configuration exists.
* [ ] OpenRouter provider is supported.
* [ ] Image model is configurable.
* [ ] No image model is hard-coded into generation logic.
* [ ] Effective model is used by generation.
* [ ] Job records provider/model.
* [ ] Observability records provider/model.
* [ ] API key remains secret.
* [ ] Existing image pipeline architecture is reused.

### Admin

* [ ] Image provider visible.
* [ ] Image model visible.
* [ ] Admin can update image model.
* [ ] Save actually changes backend configuration.
* [ ] Job detail shows provider/model.
* [ ] No secrets displayed.

### Tests

* [ ] Configuration read test.
* [ ] Configuration update test.
* [ ] Model-switch test.
* [ ] Job metadata test.
* [ ] Missing configuration test.
* [ ] Provider failure test.
* [ ] Secret-redaction test.
* [ ] Existing imaging tests remain green.

---

# 24. Required Cursor Report

Create:

```text
docs/23 sept/IMAGE_TASK_04_MODEL_PROVIDER_STATUS.md
```

The report must contain:

```text
# Image Task 04 — Model/Provider Status

## 1. What already existed

## 2. What I changed

## 3. Backend implementation

## 4. Admin implementation

## 5. Configuration source

## 6. Effective model resolution

## 7. Job metadata

## 8. Observability

## 9. Security

## 10. Tests executed

## 11. Test results

## 12. Files changed

## 13. Known gaps

## 14. What is NOT implemented

## 15. Exact manual test procedure

## 16. Git commit
```

Do not mark something complete merely because the code exists.

Every feature must have evidence.

---

# 25. Manual Acceptance Test

Cursor must provide an exact procedure that I can run from Admin.

Example:

```text
1. Open Admin
2. Open Image Generation Settings
3. Verify current provider
4. Verify current model
5. Change model
6. Save
7. Open image generation
8. Select a test persona
9. Enter seed prompt
10. Generate
11. Open job detail
12. Verify provider
13. Verify model
14. Verify 4 candidates
15. Verify observability
```

The actual procedure must match the implemented UI/API.

---

# 26. Important Tomorrow Requirement

Tomorrow we will start actual image-quality evaluation.

Therefore this task must leave us with a clean ability to answer:

> **Which image model generated this candidate?**

and:

> **Can I switch models and generate another set of candidates without changing code?**

That is the primary purpose of this task.

---

# 27. Stop Condition

After implementing and testing this task:

**STOP.**

Do not continue into image-quality evaluation or the next image task.

Update:

```text
IMAGE_TASK_04_MODEL_PROVIDER_STATUS.md
```

with exactly what is:

```text
DONE
PARTIALLY DONE
NOT DONE
```

and wait for the next instruction.
