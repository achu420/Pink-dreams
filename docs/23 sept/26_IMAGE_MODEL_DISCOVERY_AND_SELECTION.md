# 26 — IMAGE MODEL DISCOVERY & SELECTION

**Purpose:** Identify and select the OpenRouter image-generation models to use for the product's real image-generation evaluation.

**Primary product requirement:**

> The system should support the broadest practical range of fictional Persona image-generation use cases, with the least restrictive provider/model behavior available to us through OpenRouter.

This is a **model-discovery and selection task**.

It must happen before the controlled model-comparison exercise.

---

# 1. Critical interpretation of the requirement

The product wants broad generation freedom.

However, do **not** describe a model as literally:

```text
100% unrestricted
uncensored
will generate anything
```

unless this has been directly verified and can legitimately be supported by the provider/model documentation and live testing.

Instead classify models using factual terms:

```text
Broad support
Some restrictions
Significant restrictions
Request rejected
Capability unavailable
Unknown
```

The purpose of this task is to discover what actually works.

---

# 2. Model discovery principle

Cursor must not assume that the initial candidate list is complete.

Start with a candidate pool and inspect the **current OpenRouter catalog** available at execution time.

Initial candidates to investigate may include, where currently available for image generation:

```text
FLUX.2 family
Qwen Image family
Seedream family
Krea image models
Recraft image models
Grok Imagine / image models
```

These are starting candidates, **not mandatory selections**.

Cursor may add or remove candidates based on:

- current OpenRouter availability;
- image-generation capability;
- provider/model restrictions;
- reference-image support;
- Persona identity requirements;
- output quality;
- pricing;
- practical API compatibility.

---

# 3. Do not confuse model availability with capability

For every candidate verify:

```text
Currently available through OpenRouter?
Image generation?
Text-to-image?
Image-to-image?
Reference images?
Multiple reference images?
Output count?
Resolution?
API compatibility?
```

Record unknown values as:

```text
UNKNOWN
```

Do not infer them from a model's name.

---

# 4. Required candidate record

Create a candidate table containing:

| Field | Value |
|---|---|
| Model name | |
| Exact OpenRouter model ID | |
| Provider | |
| Image generation | |
| Text-to-image | |
| Image-to-image | |
| Reference images | |
| Number of references | |
| Multiple references | |
| Output count | |
| Output resolution | |
| Current availability | |
| Pricing | |
| Restrictions | |
| Source/evidence | |
| Candidate status | |

---

# 5. Restriction classification

For each model investigate restrictions in three layers.

## Layer 1 — documented provider/model behavior

Look for official documentation describing:

```text
allowed use
disallowed content
safety filters
moderation
generation limitations
image-input limitations
```

Record the source.

---

## Layer 2 — OpenRouter behavior

Verify:

```text
OpenRouter model availability
API interface
provider routing
model-specific parameters
request limitations
```

Do not assume OpenRouter removes the underlying provider's restrictions.

---

## Layer 3 — live behavior

Where credentials and testing environment are available, perform controlled generation requests.

Record:

```text
accepted
rejected
filtered
modified
failed technically
```

A live rejection should not automatically be interpreted as a model policy rejection; distinguish:

```text
provider policy rejection
OpenRouter error
invalid request
unsupported parameter
temporary provider failure
unknown
```

---

# 6. Intended product-use test categories

The objective is not to test random prompts.

Test categories should represent the actual fictional Persona product.

At minimum investigate:

```text
Portrait
Full-body
Fashion/clothing
Swimwear
Romantic/attractive styling
Different poses
Different environments
Travel/lifestyle scenes
Physical activities
Close-up
Profile views
Back view
Reference-image identity preservation
Regeneration/correction
```

If the product later requires other categories, add them to the evaluation.

---

# 7. Adult-content capability

If the product requires fictional adult Persona generation involving mature themes, this capability must be evaluated **separately** from ordinary image quality.

For each model record:

```text
Supported
Restricted
Rejected
Unknown
```

Do not infer adult-content capability from general image quality.

Do not use ambiguous labels such as:

```text
uncensored = yes
```

unless there is clear evidence.

---

# 8. Safety behavior record

For any rejected test request record:

```text
Model
Provider
Prompt category
HTTP/API result
Provider error/category
OpenRouter error/category
Timestamp
```

Do not store credentials or sensitive request headers.

Do not repeatedly probe the same rejected request.

---

# 9. Reference-image requirement

Because Persona consistency is a core requirement, reference-image support is a major selection criterion.

The preferred model should support the application's standard reference set:

```text
FRONT_FULL_BODY
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK_FULL_BODY
```

If a model supports fewer references, record the limitation.

Do not silently remove references to make a model appear compatible.

---

# 10. Identity-preservation requirement

A model should be considered viable for product testing only if it can reasonably participate in the Persona identity workflow.

The evaluation should examine:

```text
face consistency
hair consistency
eye consistency
skin appearance
body/build consistency
distinctive marks
overall recognizability
```

Do not require pixel-level matching.

---

# 11. Prompt-control requirement

Evaluate whether the model follows:

```text
scene
clothing
pose
camera direction
environment
lighting/time
activity
```

A visually attractive image that ignores the seed prompt is not equivalent to a successful generation.

---

# 12. Candidate scoring framework

Do **not** create one hidden composite score.

Instead record independent dimensions:

| Dimension | Observation |
|---|---|
| Availability | |
| Reference support | |
| Identity consistency | |
| Prompt adherence | |
| Visual quality | |
| Generation reliability | |
| Restriction behavior | |
| Latency | |
| Cost | |
| Output control | |
| Regeneration | |

This keeps the product decision transparent.

---

# 13. Initial candidate pool

Cursor should investigate at least the following families if currently available:

### FLUX

Investigate current:

```text
FLUX.2 variants
other currently available FLUX image models
```

Record exact OpenRouter IDs.

---

### Qwen Image

Investigate current:

```text
Qwen Image variants
```

Record exact OpenRouter IDs and current reference/image-input capabilities.

---

### Seedream

Investigate current:

```text
Seedream variants
```

Record exact OpenRouter IDs and restrictions.

---

### Krea

Investigate current:

```text
Krea image models
```

Record exact OpenRouter IDs and reference capabilities.

---

### Recraft

Investigate current:

```text
Recraft image models
```

Record exact OpenRouter IDs and restrictions.

---

### Grok / xAI image models

Investigate current:

```text
Grok image-generation models
```

Record exact OpenRouter IDs and restrictions.

---

# 14. Additional candidates

Cursor may add any model that appears more suitable.

Examples of reasons to add:

```text
better reference-image support
broader generation support
better identity consistency
lower cost
faster generation
better output control
better OpenRouter availability
```

Every added candidate must have the same candidate record.

---

# 15. Candidate elimination

A candidate may be removed before product comparison if it clearly fails a hard requirement.

Examples:

```text
not an image-generation model
no required image input/reference capability
not currently available
cannot be called through the application's provider abstraction
critical unsupported API behavior
```

Record the reason.

Do not silently delete candidates from the research table.

---

# 16. Selection for actual comparison

After discovery select **2–3 models** for Task 25.

Selection should be based on the product's requirements:

```text
broad generation capability
reference-image compatibility
Persona identity potential
prompt control
practical availability
cost
latency
```

Do not select solely on benchmark reputation.

---

# 17. Selection report

Cursor must produce:

```text
SELECTED MODEL 1
Exact model ID:
Provider:
Why selected:
Reference support:
Restrictions:
Known limitations:

SELECTED MODEL 2
Exact model ID:
Provider:
Why selected:
Reference support:
Restrictions:
Known limitations:

SELECTED MODEL 3
Exact model ID:
Provider:
Why selected:
Reference support:
Restrictions:
Known limitations:
```

If only two models genuinely meet the requirements, select two.

Do not add a third just to satisfy a number.

---

# 18. OpenRouter configuration

For each selected model verify the application can configure it without code duplication.

Prefer configuration such as:

```text
IMAGE_MODEL=<model-id>
```

or the repository's existing engine/model configuration.

Do not hard-code model-specific logic unless the model's API genuinely requires it.

---

# 19. Model metadata persistence

Every generated candidate must retain:

```text
provider
model ID
generation configuration
```

The model used for an old candidate must never change merely because the active configuration changes.

---

# 20. Model switching

Test:

```text
Model A active
 ↓
generation
 ↓
Model B active
 ↓
generation
```

Verify:

```text
old candidate → Model A
new candidate → Model B
```

No historical metadata should be rewritten.

---

# 21. Fallback behavior

If the system supports provider/model fallback, document it explicitly.

For example:

```text
Requested model
 ↓
failure
 ↓
fallback model
```

The candidate metadata must show the **actual model that generated the image**.

Do not claim a requested model generated an image when a fallback actually generated it.

---

# 22. No silent fallback

If fallback is not part of the product contract:

```text
provider failure
```

must remain a visible generation failure.

Do not silently switch models.

---

# 23. Test prompt set

Use a small standardized discovery prompt set:

```text
D1 — portrait
D2 — full body
D3 — clothing variation
D4 — environment
D5 — pose/direction
D6 — reference identity
```

For any adult-content capability testing, use the product's approved controlled test cases and record provider/model response without attempting to circumvent a rejection.

---

# 24. Discovery output

Create a final table:

| Model | Available | Reference support | Broad-use behavior | Identity potential | Cost | Latency | Selected |
|---|---|---|---|---|---|---|---|
| Candidate A | | | | | | | |
| Candidate B | | | | | | | |
| Candidate C | | | | | | | |

The table is descriptive.

It must not contain:

```text
Winner
Best
#1
Recommended
```

---

# 25. Evidence requirements

For every factual claim about a model use one of:

```text
Official provider documentation
OpenRouter model page/catalog
Live application test
Repository implementation
```

Record which source supports each important capability/restriction.

Do not present remembered model behavior as current fact.

---

# 26. Currentness requirement

Model catalogs change.

Therefore the report must include:

```text
Discovery date:
OpenRouter catalog checked:
Selected model IDs:
```

A future evaluation must re-check availability instead of assuming this list remains current.

---

# 27. Security

Do not put:

```text
OPENROUTER_API_KEY
DATABASE_PASSWORD
authorization headers
session cookies
```

into the model-discovery MD file.

Use environment variables.

---

# 28. Definition of Done

- [ ] Current OpenRouter image-model catalog inspected.
- [ ] Initial candidate families investigated.
- [ ] Additional candidates considered.
- [ ] Exact model IDs recorded.
- [ ] Provider recorded.
- [ ] Image capabilities verified.
- [ ] Reference-image support verified.
- [ ] Restrictions documented.
- [ ] Live behavior tested where credentials permit.
- [ ] Technical failures separated from policy restrictions.
- [ ] Candidate elimination reasons documented.
- [ ] 2–3 actual comparison models selected, if available.
- [ ] Model switching verified.
- [ ] Historical model metadata verified.
- [ ] Fallback behavior documented.
- [ ] No silent model fallback.
- [ ] Model discovery report completed.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` updated.

---

# 29. Cursor implementation instructions

Before changing code:

1. Read Tasks 18–25.
2. Read `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`.
3. Inspect the current OpenRouter/provider implementation.
4. Inspect how image model IDs are configured.
5. Inspect how provider/model metadata is persisted.
6. Inspect reference-image handling.
7. Inspect image generation request construction.
8. Inspect current Admin model configuration.

Do not redesign the provider architecture.

If model switching already works, test it rather than rebuilding it.

If model configuration is hard-coded and prevents the evaluation, make the smallest configuration change necessary.

---

# 30. Required completion report

Cursor must return exactly this structure:

```text
TASK 26 — IMAGE MODEL DISCOVERY & SELECTION

Discovery date:

OpenRouter catalog checked:

Initial candidates:

Additional candidates:

Models eliminated:
- Model:
  Reason:

Selected models:

MODEL 1
Name:
Exact OpenRouter ID:
Provider:
Image generation:
Reference support:
Restrictions:
Live result:
Known limitations:

MODEL 2
Name:
Exact OpenRouter ID:
Provider:
Image generation:
Reference support:
Restrictions:
Live result:
Known limitations:

MODEL 3
Name:
Exact OpenRouter ID:
Provider:
Image generation:
Reference support:
Restrictions:
Live result:
Known limitations:

Model switching:
PASS / FAIL

Historical metadata:
PASS / FAIL

Fallback behavior:

Technical failures:

Policy/restriction observations:

Security:

Tests:
Passed:
Failed:
Skipped:

Files changed:

Commit:

Updated status file:
```

---

# 31. Final product gate

The outcome of this task is **not**:

```text
We found an unrestricted model.
```

The outcome must be:

```text
These are the currently available candidate models.
These are their documented capabilities.
These are their observed restrictions.
These are the 2–3 models selected for controlled product comparison.
```

Then Task 25 can perform the actual side-by-side Persona evaluation.

**Critical rule:** The product goal is maximum practical generation freedom for the intended fictional Persona use cases. Do not claim that a provider/model is censorship-free unless current evidence genuinely supports that claim.
