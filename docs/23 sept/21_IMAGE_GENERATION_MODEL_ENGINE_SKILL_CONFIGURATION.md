# 21 — IMAGE GENERATION MODEL, ENGINE & SKILL CONFIGURATION

**Goal:** Make the image-generation provider/model and its image-generation engine/skill configuration explicitly manageable, testable, and traceable from Admin without creating a second generation architecture.

**Depends on:** Image Pipeline Governance, Tasks 01–20.

---

# 1. Product objective

The image pipeline must not hard-code a model in a way that makes model evaluation or controlled replacement difficult.

Admin should be able to understand:

```text
Which provider is being used?
Which image model is being used?
Which engine/skill configuration is being used?
What prompt/configuration is being sent?
What configuration produced a candidate?
```

The system should support changing the configured image-generation model/engine/skill while preserving historical generation lineage.

---

# 2. Core concepts

Keep these concepts separate:

```text
Provider
Model
Image Engine
Image Skill
Generation Job
Candidate
```

Conceptually:

```text
Provider
   ↓
Model
   ↓
Image Engine
   ↓
Image Skill / configuration
   ↓
Generation Job
   ↓
Candidates
```

Use the project's existing terminology and abstractions if they already exist.

Do not introduce duplicate provider/model abstractions.

---

# 3. Provider

A Provider identifies the external inference service.

Example:

```text
OpenRouter
```

The provider configuration must not contain the secret itself in the database or Admin UI.

Secrets remain environment/secret-manager configuration.

---

# 4. Model

A Model identifies the actual image model selected through the provider.

Admin must be able to see the configured model identifier.

Example conceptual value:

```text
provider/model-name
```

Do not assume a particular model is permanently selected by this task.

The model must remain configurable so that model evaluation can be performed.

---

# 5. Unrestricted-model requirement

For the current product testing phase, the selected image-generation provider/model must be chosen based on whether it can support the application's intended fictional Persona image-generation requirements.

Do not describe a model as “unrestricted” merely because the application has no local filter.

Document the actual provider/model capabilities and applicable provider restrictions.

The pipeline must not claim that OpenRouter removes upstream model safety policies.

---

# 6. Image Engine

The Image Engine is the application-level generation mechanism.

It is responsible for:

```text
Resolve Persona identity
Resolve reference images
Build generation context
Compile prompt
Select provider/model
Submit generation request
Receive candidates
Persist results
```

The engine should not contain Persona-specific hard-coded identity information.

---

# 7. Image Skill

An Image Skill represents reusable generation behavior/configuration.

Examples:

```text
Persona image generation
Identity-preserving scene generation
Regeneration from candidate
```

A skill may define:

```text
prompt instructions
output requirements
reference-image rules
generation defaults
model requirements
```

Reuse the project's existing Skill abstraction if one already exists.

Do not create a second skill framework only for images.

---

# 8. Configuration hierarchy

The effective image-generation configuration should have a clear precedence.

Recommended:

```text
System image configuration
        ↓
Selected image engine
        ↓
Selected image skill
        ↓
Provider/model configuration
        ↓
Persona identity
        ↓
Seed prompt
```

The exact precedence must follow existing engine/skill governance if already implemented.

Document the actual implementation in the status file.

---

# 9. Admin configuration

Admin should be able to inspect the current configuration.

Minimum display:

```text
Image provider
Image model
Engine
Skill
Active/inactive status
Updated timestamp
```

If the project already has an Engine/Skill admin interface, integrate image configuration there instead of creating a separate screen.

---

# 10. Model update

Admin should be able to change the active image model through the approved configuration mechanism.

Changing the active model must affect future jobs.

It must not rewrite historical candidates.

Example:

```text
Before:
Model A
   ↓
Candidate 1

Change configuration

After:
Model B
   ↓
Candidate 2
```

Candidate 1 remains recorded as Model A.

---

# 11. Engine update

If engines are configurable in the existing architecture, Admin should be able to select/update the image engine using the same governance mechanism.

Do not allow an arbitrary engine identifier to bypass validation.

---

# 12. Skill update

Admin should be able to inspect and update the image skill/configuration using the existing Skill mechanism.

Any update must be versioned or otherwise traceable.

A candidate must remain attributable to the skill/configuration that produced it.

---

# 13. Configuration versioning

Every generation should retain enough metadata to determine:

```text
Provider
Model
Engine
Skill
Configuration/version
```

Historical images must remain reproducible at the metadata level even after configuration changes.

---

# 14. Prompt transparency

Admin should be able to inspect, where permitted:

```text
Seed prompt
Compiled generation prompt
Relevant generation configuration
```

The UI must distinguish:

```text
Admin seed
```

from:

```text
System/engine-generated prompt
```

Do not overwrite the original seed with the compiled prompt.

---

# 15. Secrets

Never display or persist:

```text
OPENROUTER_API_KEY
DATABASE_PASSWORD
other provider secrets
```

Admin configuration should expose identifiers and non-secret configuration only.

---

# 16. Model capability metadata

For every configured model, document capabilities relevant to the image pipeline:

```text
Image generation
Reference image support
Multiple reference support
Image editing / regeneration support
Output count
Input/output format
Provider limitations
```

If the provider/model does not support a capability:

```text
NOT SUPPORTED
```

Do not silently emulate or claim support.

---

# 17. Model testing mode

The architecture must make controlled model comparison possible.

Example:

```text
Persona A
Seed X

Model A → candidates
Model B → candidates
Model C → candidates
```

Each result must retain its model identity.

Do not overwrite one model's results with another's.

---

# 18. Model evaluation dataset

Use the test Personas established by the image-pipeline testing plan.

Recommended evaluation dimensions:

```text
Identity consistency
Reference adherence
Prompt adherence
Scene composition
Pose/camera adherence
Visual quality
Generation latency
Failure rate
Cost
```

These are observations for product evaluation.

Do not create an automatic overall winner or score unless explicitly implemented as a separate validated evaluation system.

---

# 19. Controlled A/B generation

Admin should be able to run the same:

```text
Persona
Identity
Reference set
Seed prompt
```

against different models.

Keep constant:

```text
Persona
identity version
seed prompt
reference set
```

Change only:

```text
model
```

where technically possible.

This makes model comparison meaningful.

---

# 20. Candidate lineage during model tests

Example:

```text
Evaluation Run 1
 ├── Model A
 │    ├── Candidate A1
 │    ├── Candidate A2
 │    ├── Candidate A3
 │    └── Candidate A4
 │
 └── Model B
      ├── Candidate B1
      ├── Candidate B2
      ├── Candidate B3
      └── Candidate B4
```

Each candidate must remain independently addressable.

---

# 21. Configuration validation

Before activating a model/engine/skill configuration, validate:

```text
Provider configured
Model identifier present
Required credentials available
Required capability supported
Configuration syntactically valid
```

Do not wait until an image job fails to discover an obviously invalid configuration.

---

# 22. Runtime failure

If the configured provider/model becomes unavailable:

```text
Job = FAILED
```

with a redacted diagnostic.

Do not silently switch to a different model unless an explicit fallback policy exists.

If fallback is implemented later, it must be recorded in the candidate metadata.

---

# 23. Fallback policy

Do not implement automatic model fallback in this task unless the existing architecture already requires it.

If fallback exists:

```text
requested model
    ↓
fallback model
```

must be recorded explicitly.

Admin must be able to determine which model actually produced the image.

---

# 24. Admin audit trail

Configuration changes should retain:

```text
who changed it
what changed
previous value
new value
timestamp
```

Use the existing Admin audit mechanism.

Do not create a parallel audit framework if one already exists.

---

# 25. Existing candidates after model change

Changing:

```text
Provider
Model
Engine
Skill
```

must NOT:

- regenerate existing images;
- rewrite candidate metadata;
- change historical status;
- change Persona identity;
- change warehouse lineage.

Only future generation jobs use the new configuration.

---

# 26. Generation snapshot

At job creation time, capture the effective configuration needed for lineage.

Conceptually:

```text
Generation Job
 ├── provider
 ├── model
 ├── engine
 ├── skill
 ├── configuration version
 ├── Persona identity version
 ├── reference set
 └── seed prompt
```

Do not depend only on today's active configuration to interpret yesterday's job.

---

# 27. Admin workflow

Expected workflow:

```text
Admin
 ↓
Image Configuration
 ↓
Inspect active Provider / Model / Engine / Skill
 ↓
Change model/configuration
 ↓
Validate
 ↓
Activate
 ↓
Generate test image
 ↓
Inspect candidate metadata
```

---

# 28. Configuration rollback

Admin should be able to restore the previous valid configuration through the existing configuration/versioning mechanism.

Rollback must affect future generations only.

Historical candidates remain unchanged.

---

# 29. Test — model change

1. Configure Model A.
2. Generate candidates.
3. Confirm candidate metadata.
4. Configure Model B.
5. Generate candidates.
6. Confirm new candidates use Model B.
7. Confirm old candidates still show Model A.

---

# 30. Test — engine/skill change

1. Generate with Engine/Skill version A.
2. Update configuration to version B.
3. Generate again.
4. Confirm lineage identifies the correct configuration for each candidate.

---

# 31. Test — same seed across models

Use:

```text
same Persona
same identity
same references
same seed
```

Generate using two models.

Verify:

```text
different model metadata
separate jobs
separate candidates
same source Persona identity
same source seed
```

---

# 32. Test — secret protection

Verify Admin/API responses never return:

```text
API key
database password
provider secret
```

Check logs as well.

Secrets must not appear in:

```text
HTTP response
database metadata
candidate metadata
application logs
error messages
```

---

# 33. Test — invalid configuration

Configure an intentionally invalid model identifier in a safe test environment.

Expected:

```text
configuration rejected
```

or:

```text
job fails clearly and safely
```

depending on the existing architecture.

No fake successful candidate.

---

# 34. Test — historical integrity

After changing model/engine/skill:

Verify old candidates still show their original:

```text
model
engine
skill/configuration
identity
seed
```

---

# 35. Acceptance criteria

### AC-01
Current provider/model is visible to Admin.

### AC-02
Image engine/skill configuration follows existing application architecture.

### AC-03
Future jobs use the active configuration.

### AC-04
Historical candidates retain the configuration that produced them.

### AC-05
Model changes do not rewrite historical candidates.

### AC-06
Engine/skill changes do not rewrite historical candidates.

### AC-07
Admin can run controlled model comparisons.

### AC-08
Candidate metadata identifies the actual provider/model.

### AC-09
Reference-image capabilities are honestly represented.

### AC-10
Secrets are never exposed.

### AC-11
Configuration changes are auditable where existing audit infrastructure supports it.

### AC-12
Invalid configuration cannot silently generate fake/successful results.

---

# 36. Definition of Done

- [ ] Provider is configurable through existing architecture.
- [ ] Model is configurable through existing architecture.
- [ ] Engine configuration is traceable.
- [ ] Skill configuration is traceable.
- [ ] Effective configuration is captured at generation time.
- [ ] Historical candidates retain configuration lineage.
- [ ] Admin can inspect current configuration.
- [ ] Model comparison is possible.
- [ ] Same Persona/seed can be tested against multiple models.
- [ ] Provider/model capability limitations are documented.
- [ ] Configuration changes are auditable where supported.
- [ ] Secrets remain protected.
- [ ] Invalid configuration is handled safely.
- [ ] Existing image tests remain green.
- [ ] New configuration/model tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 37. Cursor implementation instructions

Before coding:

1. Inspect existing Provider abstraction.
2. Inspect OpenRouter integration.
3. Inspect image model configuration.
4. Inspect Engine abstraction.
5. Inspect Skill abstraction.
6. Inspect Admin configuration UI.
7. Inspect configuration persistence.
8. Inspect audit/versioning infrastructure.
9. Inspect generation job metadata.
10. Reuse existing architecture.

Do NOT:

- create a second Provider abstraction;
- create a second Skill framework;
- store API keys in DB;
- hard-code one permanent model;
- silently change models;
- rewrite historical candidate metadata.

After implementation update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Task 21:
Provider:
Model:
Engine:
Skill:
Admin configuration:
Versioning:
Generation snapshot:
Model comparison:
Audit:
Security:
Tests:
Known gaps:
Files changed:
Git commit:
```

---

# 38. Completion report

Cursor must return:

```text
TASK 21 — MODEL / ENGINE / SKILL CONFIGURATION

Status:

Provider:
Model:
Engine:
Skill:

Admin:
Current configuration:
Can update:
Validation:
Audit:

Generation:
Configuration snapshot:
Candidate metadata:

Model comparison:
Same Persona:
Same seed:
Models tested:

Security:
Secrets protected:

Tests:
Passed:
Failed:
Skipped:

Known gaps:

Files changed:

Commit:
```

**Critical rule:** Model, Engine, Skill, Persona identity, and seed prompt are different layers. Changing one must not silently mutate the others or rewrite historical image candidates.
