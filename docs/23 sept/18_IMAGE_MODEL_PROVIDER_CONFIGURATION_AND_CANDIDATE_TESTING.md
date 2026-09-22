# 18 — IMAGE MODEL / PROVIDER CONFIGURATION & CANDIDATE TESTING

**Goal:** Make image-model selection explicit, configurable, testable, and ready for tomorrow's controlled candidate-generation evaluation.

**Depends on:** Image Pipeline Governance, Implementation Status, Tasks 01–17.

---

## 1. Product objective

The image pipeline must not silently depend on an unknown or hardcoded image model.

Admin must be able to determine:

- Which provider is being used.
- Which image model is being used.
- How the model is configured.
- Whether the configured model can actually generate the requested image.
- What output quality the model produces for different Personas.
- What restrictions/errors occur.
- What each generation costs when cost data is available.
- Which model produced each candidate.

The system must support controlled comparison of **2–3 candidate image models** through OpenRouter.

This task is about **model/provider infrastructure and evaluation readiness**, not selecting a permanent winner.

---

# 2. Important product rule

The product requirement is:

> Prefer an image model that gives the product sufficient creative freedom for its intended fictional Persona image-generation use cases, subject to the actual provider/model policies and configured account access.

Do not implement artificial application restrictions beyond the product's own safety/security requirements.

Do not claim that a model is "unrestricted" merely because the application does not add a filter.

The provider/model's actual capabilities and policies remain authoritative.

---

# 3. Provider architecture

Inspect the existing image provider abstraction.

The pipeline should remain:

```text
ImageGenerationService
        ↓
ImageGenerationHandler
        ↓
ImageProvider interface
        ↓
OpenRouter provider
        ↓
configured image model
```

Do not bypass the provider abstraction.

Do not put OpenRouter-specific logic throughout the image pipeline.

---

# 4. Model configuration

The image model must be configurable without code changes.

Use the existing project configuration conventions.

At minimum support:

```text
IMAGE_PROVIDER
OPENROUTER_API_KEY
OPENROUTER_IMAGE_MODEL
```

If the existing implementation already uses another model configuration variable, preserve the established convention rather than creating a duplicate.

Cursor must inspect the repository first.

---

# 5. No hardcoded production model

Search the entire image-generation path for:

```text
model =
model:
image model
OpenRouter model
```

Ensure there is no hidden hardcoded production model overriding configuration.

A default model may exist for development/test only if it is explicitly documented.

Admin must be able to see the effective provider/model.

---

# 6. Effective configuration

Expose the effective non-secret image configuration to Admin.

Example:

```text
Image Provider
OpenRouter

Image Model
<configured model>

Storage
LocalFile

Live provider
Configured / Not configured
```

Never expose:

```text
OPENROUTER_API_KEY
Authorization header
secret values
```

---

# 7. Configuration validation

At application startup or image-generation request time, validate:

- provider is supported
- required provider credentials exist for live generation
- model identifier is present
- model configuration is syntactically valid
- required image-generation capability is configured

If configuration is invalid, fail clearly.

Do not silently fall back to a different production model.

---

# 8. OpenRouter integration

Use the existing OpenRouter implementation.

Verify that it correctly:

1. receives the compiled image prompt;
2. sends the configured model;
3. requests image generation;
4. handles the provider response;
5. retrieves image bytes or provider asset information;
6. validates the resulting asset;
7. stores the asset;
8. records provider/model metadata.

Do not create a second OpenRouter client.

---

# 9. Provider error handling

Classify at minimum:

```text
Authentication failure
Invalid model
Unsupported image capability
Provider rejection
Rate limit
Timeout
Provider 5xx
Malformed response
Invalid image asset
Storage failure
```

Preserve the existing retry policy.

Do not retry permanent configuration/authentication errors indefinitely.

Do not hide provider rejection reasons from Admin.

Redact secrets.

---

# 10. Candidate generation requirement

For a normal generation request:

```text
1 request
    ↓
configured image model
    ↓
4 candidates
```

The four candidates must be tracked independently.

Each candidate should retain enough lineage to answer:

```text
Which Persona?
Which identity version?
Which reference set?
Which seed prompt?
Which compiled prompt?
Which provider?
Which model?
Which job?
Which candidate index?
When generated?
What was the result?
```

Reuse the existing candidate/warehouse schema where possible.

---

# 11. Candidate metadata

Every generated candidate should have:

```text
candidateId
jobId
personaId
personaVisualVersion / identity version
reference set/version where available
provider
model
candidate index
createdAt
status
assetId
seed prompt / generation request reference
admin remark
```

Do not duplicate huge prompt/reference payloads unnecessarily if the current architecture already stores them elsewhere.

---

# 12. Reference-image awareness

The image-generation request must use the Persona's configured visual identity/reference images according to the existing Persona Visual implementation.

The model evaluation must not accidentally test:

```text
seed prompt only
```

when the intended production path is:

```text
Persona visual identity
+
reference images
+
physical attributes
+
style/wardrobe
+
scene seed
```

The exact reference-image mechanism must follow the already implemented Persona Visual contract.

---

# 13. Test Personas

Prepare **2–3 existing test Personas**.

Do not alter production Personas unnecessarily.

Each test Persona should have:

- completed visual identity;
- reference images;
- physical attributes;
- appearance details;
- identity version;
- enough data for the normal production image-generation path.

If suitable Personas already exist, reuse them.

---

# 14. Standard candidate test prompt

Create a controlled seed prompt that exercises:

- full-body composition;
- recognizable Persona identity;
- clothing;
- environment;
- lighting;
- camera-facing pose.

Example:

```text
Generate an image of the selected Persona swimming at a
Goa beach in a yellow bikini during early morning,
facing the camera, natural realistic photography.
```

The exact test prompt can be adjusted to match the Persona.

The purpose is controlled comparison, not marketing content.

---

# 15. Model comparison

Evaluate **2–3 image models** through the same pipeline.

For each model:

```text
Persona A
→ 4 candidates

Persona B
→ 4 candidates

Persona C
→ 4 candidates
```

Use the same:

- Persona data;
- reference images;
- seed prompt;
- generation settings where supported;
- candidate count.

Only change the model unless the model requires a documented capability-specific parameter.

---

# 16. Evaluation record

For every test generation record:

```text
Model
Provider
Persona
Job ID
Candidate IDs
Generation timestamp
Latency
Cost if available
Success/failure
Provider error if failed
```

This allows later comparison based on evidence.

---

# 17. Identity consistency evaluation

For each candidate Admin should be able to inspect:

```text
Reference images
Generated candidate
```

The system should make it easy to compare identity consistency.

At minimum evaluate visually:

- face similarity;
- hair;
- eye color;
- major physical attributes;
- distinctive marks;
- body proportions;
- overall recognizable identity.

Do not implement an automatic face/identity score unless such functionality already exists or is separately specified.

This task must not invent a numerical "identity score."

---

# 18. Candidate quality review

Admin must be able to mark candidates according to the existing Image Warehouse workflow.

At minimum:

```text
Shortlisted
Declined
Saved
```

If the existing status model includes additional states, reuse it.

Admin should be able to add remarks.

Example:

```text
"Face is close but hair is incorrect."
"Body proportions match; background excellent."
"Identity drift — reject."
```

---

# 19. Regeneration test

Select one generated candidate.

Provide a correction such as:

```text
Keep the same Persona identity and scene.
Correct the hairstyle to match the reference images.
```

Regenerate.

The new generation must retain lineage to:

```text
original candidate
original job
Persona
identity version
model
correction instruction
```

Do not overwrite the original candidate.

---

# 20. Model comparison must not modify identity data

Model testing must not mutate:

- Persona visual attributes;
- reference images;
- identity version;
- Persona profile.

Testing creates generated candidates.

It does not change the source Persona.

---

# 21. Admin model configuration UI

If the existing Admin UI architecture supports configuration editing, provide:

```text
Provider
Model
Live/test status
```

If runtime configuration is intentionally environment-only, show the effective configuration read-only.

Do not invent a database-backed configuration system solely for this task.

---

# 22. Test generation UI

Admin must have a clear path:

```text
Persona
   ↓
Generate Image
   ↓
Seed prompt
   ↓
Configured model
   ↓
Generate 4 candidates
   ↓
Image Warehouse
```

The UI must make clear which model produced the candidates.

---

# 23. Cost tracking

For every model test:

```text
Cost per job
Cost per candidate
Total test cost
```

only where actual provider cost is available.

If unavailable:

```text
Cost: unavailable
```

Never treat missing cost as zero.

---

# 24. Latency tracking

Record:

```text
request time
job start
provider start
provider completion
asset storage completion
job completion
```

where the existing architecture supports those timestamps.

Admin should be able to inspect total generation latency.

---

# 25. Live OpenRouter test

This task is the first intended live model-evaluation gate.

Cursor must NOT fabricate success.

Run live testing only when:

```text
OPENROUTER_API_KEY
```

is available.

Use environment variables only.

Never write credentials into:

- source code;
- Markdown;
- Git;
- screenshots;
- logs.

---

# 26. Live test matrix

For each configured model:

```text
Model 1
  Persona A → 4 candidates
  Persona B → 4 candidates
  Persona C → 4 candidates

Model 2
  Persona A → 4 candidates
  Persona B → 4 candidates
  Persona C → 4 candidates

Model 3
  Persona A → 4 candidates
  Persona B → 4 candidates
  Persona C → 4 candidates
```

If a model cannot support the required image generation mode, record:

```text
NOT COMPATIBLE
```

with the actual provider response.

Do not silently substitute another model.

---

# 27. Testing output

Cursor must produce a model-test report containing:

```text
MODEL:
Provider:
Version/config:

Persona:
Job:
Candidates:

Success:
Failure:
Latency:
Cost:

Identity observations:
Visual observations:
Provider restrictions/errors:

Admin review:
Shortlisted:
Declined:
Remarks:
```

Repeat for each model/persona combination.

---

# 28. Do not select a model automatically

This task does **not** declare a winner.

The output is evidence for the product decision.

The system must make model comparison possible and preserve the evidence.

---

# 29. Security tests

Verify:

- API key never appears in API responses;
- API key never appears in Admin UI;
- API key never appears in logs;
- normal users cannot change image model configuration;
- normal users cannot access provider diagnostics;
- generated assets retain existing ownership/security rules.

---

# 30. Regression tests

Do not break:

- Fake provider tests;
- H2 tests;
- image worker;
- image storage;
- candidate persistence;
- Persona visual identity;
- Admin image generation;
- chat attachment;
- SLA;
- retention;
- multi-instance storage.

Run the complete relevant imaging test suite.

---

# 31. Definition of Done

- [ ] Provider/model configuration is explicit.
- [ ] No hidden production model override exists.
- [ ] Effective model is visible to Admin.
- [ ] OpenRouter uses configured model.
- [ ] Provider errors are classified.
- [ ] Four candidates are generated per normal request.
- [ ] Candidate lineage is persisted.
- [ ] Persona visual identity/reference images are used.
- [ ] 2–3 test Personas can be used.
- [ ] 2–3 models can be evaluated.
- [ ] Same controlled test path is used for comparison.
- [ ] Candidate review/remarks work.
- [ ] Regeneration preserves lineage.
- [ ] Cost is captured where available.
- [ ] Latency is captured.
- [ ] Secrets are protected.
- [ ] Live testing is clearly marked verified/not verified.
- [ ] Existing image tests remain green.
- [ ] Implementation status is updated.

---

# 32. Mandatory implementation-status update

After implementation, update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

Add:

```text
Task 18:
Provider:
Model configuration:
Admin visibility:
Candidate generation:
Candidate lineage:
Reference-image usage:
Model comparison:
Live OpenRouter:
Cost:
Latency:
Security:
Tests:
Known gaps:
Files changed:
Git commit:
```

---

# 33. Completion report

Cursor must return:

```text
TASK 18 — IMAGE MODEL / PROVIDER CONFIGURATION

Status:

Provider:
Configured model:
Configuration source:

Admin:
Model visible:
Generation flow:

Candidate generation:
Candidates/request:
Lineage:

Reference images:
Used:
Verified:

Model comparison:
Models tested:
Personas tested:

OpenRouter live:
PASS / FAIL / NOT RUN
Reason:

Cost:
Latency:

Security:

Tests:
Passed:
Failed:
Skipped:

Known gaps:

Files changed:

Commit:
```

**Critical:** If OpenRouter credentials are unavailable, implement and test everything possible with the existing Fake provider, then explicitly report the live-provider portion as `NOT VERIFIED`. Never fabricate live image results.
