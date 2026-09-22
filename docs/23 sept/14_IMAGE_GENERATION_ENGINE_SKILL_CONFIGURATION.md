# 14 — IMAGE GENERATION ENGINE & SKILL CONFIGURATION

**Depends on:**
- `IMAGE_PIPELINE_GOVERNANCE.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- `12_IMAGE_MODEL_EVALUATION.md`
- `13_IMAGE_IDENTITY_CONSISTENCY_REFERENCE_MATCHING.md`
- Existing engine / skill architecture
- Existing OpenRouter provider abstraction

**Goal:** Make image generation model/provider configuration explicit, Admin-manageable, testable, and separate from persona identity and scene intent.

---

# 1. Product requirement

Image generation must not hardcode a model inside the persona.

The system should have:

```text
Persona
   ↓
Image-generation skill / configuration
   ↓
Selected provider + model
   ↓
Image generation engine
   ↓
Candidates
```

Persona identity answers:

> Who should be generated?

The seed prompt answers:

> What should the persona be doing / wearing / where should they be?

The image engine configuration answers:

> Which image model/provider should generate it?

These concerns must remain separate.

---

# 2. Current architecture first

Before changing code, Cursor MUST inspect:

- current image provider interface
- OpenRouter provider
- Fake provider
- PromptCompiler
- skill registry / skill configuration
- existing engine configuration
- Admin configuration patterns
- environment-variable configuration
- model evaluation implementation from Task 12

Reuse existing abstractions.

Do not create a second skill system or second provider registry.

If an equivalent component already exists, extend it.

---

# 3. OpenRouter requirement

The production image path must support OpenRouter.

The model identifier must be configurable.

Do not hardcode one permanent image model.

Conceptually:

```text
IMAGE_PROVIDER=openrouter
IMAGE_MODEL=<configured OpenRouter image model>
```

Use the existing project configuration conventions.

---

# 4. Model selection

The configured model must be recorded on every generation.

Example:

```text
provider: openrouter
model: <model-id>
```

A candidate must therefore be traceable to the exact model used.

Do not infer the model later from current configuration.

---

# 5. Candidate model evaluation

Task 12 will allow Admin to test multiple image models.

Example:

```text
Model A
Model B
Model C
```

For a controlled evaluation:

```text
same persona
same identity version
same references
same seed
same generation settings where applicable
```

Only the model should change unless the evaluation explicitly tests another variable.

---

# 6. Model configuration

At minimum support:

```text
Provider
Model ID
Enabled / disabled
Default / selected status
```

Where already supported by the provider, additional configuration may include:

```text
aspect ratio
resolution
quality
steps
guidance
seed
reference strength
output count
```

Do not invent provider-specific fields that the selected model cannot support.

Provider-specific settings must remain inside provider configuration.

---

# 7. Default model

There must be one deterministic default image model for normal generation.

Admin must be able to determine which model is currently configured.

If the system has no Admin UI for changing it yet:

- expose the active configuration clearly
- implement the smallest Admin configuration surface
- do not hide the value in source code

---

# 8. Skill separation

Image generation skill configuration must not contain persona identity.

Example:

```text
IMAGE_GENERATION_SKILL

purpose:
Generate persona images from a scene seed.

provider:
OpenRouter

model:
configured model

prompt strategy:
PromptCompiler
```

Persona data remains in Persona Visual Identity.

---

# 9. Skill lifecycle

If the existing skill architecture supports lifecycle:

```text
DRAFT
ACTIVE
INACTIVE
```

follow it.

Only an active image-generation skill/configuration should be eligible for normal generation.

Do not create a separate activation mechanism if one already exists.

---

# 10. Configuration precedence

Define one deterministic precedence.

Recommended:

```text
Explicit generation request
        ↓
Active image skill configuration
        ↓
Application/provider defaults
```

Persona identity must always be resolved separately.

Environment configuration may provide deployment-level defaults.

Do not allow random precedence between:

- env
- DB
- skill
- request
- hardcoded constants

Document the actual precedence implemented by the repository.

---

# 11. Explicit model override

The architecture should permit Admin/testing workflows to explicitly select a model.

Example:

```text
Generate
Persona: Simran
Model: Model B
```

This is particularly important for Task 12 model evaluation.

Normal user generation may use the configured default.

Evaluation/Admin workflows may select a specific enabled model.

---

# 12. Provider abstraction

The image generation service should depend on an abstraction such as the existing:

```text
ImageProvider
```

not directly on OpenRouter.

Conceptually:

```text
ImageGenerationService
        ↓
ImageProvider
        ↓
OpenRouterImageProvider
```

Fake provider remains available for automated tests.

Do not couple business logic to OpenRouter response formats.

---

# 13. Provider configuration

OpenRouter credentials must remain deployment secrets.

Required conceptually:

```text
OPENROUTER_API_KEY
```

Never store the key in:

- persona records
- skill records
- database
- Admin UI
- image candidate metadata
- logs
- git
- Markdown documentation

Only the configured model/provider identifier may be visible to Admin.

---

# 14. Unrestricted model requirement

The image pipeline must not introduce an artificial application-level restriction that prevents the selected image model from handling the product's intended generation categories.

However:

- provider/model policies still apply
- the application must not misrepresent provider capabilities
- existing safety/security controls must remain intact
- no implementation should bypass provider safeguards

For this task, "unrestricted" means:

> Do not unnecessarily constrain the image model through the application architecture.

---

# 15. Prompt construction

The image engine must receive the output of the existing PromptCompiler.

Conceptually:

```text
Persona Visual Identity
        +
Reference Images
        +
Seed Prompt
        +
Image Skill / style configuration
        ↓
PromptCompiler
        ↓
Provider Request
```

Do not build a second prompt builder inside the provider.

---

# 16. Seed prompt

The Admin/user seed should remain recognizable in the generation metadata.

Example:

```text
Generate image of persona swimming in a yellow bikini
in Goa beach, early morning, facing camera.
```

The system may transform it through PromptCompiler.

Persist:

```text
original seed prompt
compiled prompt metadata where appropriate
```

Do not expose private identity data in ordinary logs.

---

# 17. Model-specific prompt adaptation

If different image models require different prompt formatting:

```text
PromptCompiler
       ↓
Provider/model adapter
       ↓
model request
```

Do not duplicate persona identity rules across model adapters.

Identity remains a single source of truth.

---

# 18. Generation count

Normal candidate generation should support:

```text
4 candidates
```

where the selected provider/model supports the requested output count.

If the provider only supports one image per request:

```text
4 provider requests
```

may be used.

The resulting candidates must still belong to the same generation job/evaluation group.

---

# 19. Determinism and seed

Where the model supports seeds:

- preserve the seed in generation metadata
- allow Admin/evaluation workflows to control it
- use the same seed for fair model comparisons where supported

Where a model does not support seeds:

```text
seed: unsupported
```

must be represented honestly.

Do not invent seed determinism.

---

# 20. Cost metadata

Every completed generation should capture available cost information.

At minimum:

```text
provider
model
usage information if available
estimated/actual cost if available
```

If OpenRouter/provider does not return usable cost information:

```text
cost: unavailable
```

Do not fabricate a price.

This feeds the Admin cost/SLA dashboard.

---

# 21. Latency metadata

Record:

```text
queuedAt
startedAt
completedAt
provider latency
total generation latency
```

where available through the existing observability system.

Use the existing image observability infrastructure.

---

# 22. Error handling

Provider errors must preserve:

```text
provider
model
failure category
retryable/permanent classification
sanitized error
```

Never store the OpenRouter API key or authorization header.

---

# 23. Admin UI

Admin should be able to inspect:

```text
IMAGE GENERATION

Provider: OpenRouter
Model: <active model>
Status: Active

[Change Model]
```

For evaluation:

```text
Available models

[ ] Model A
[ ] Model B
[ ] Model C

Persona: Simran
Identity: V5
References: 5
Seed: ...
Candidates: 4

[Run Evaluation]
```

Use existing Admin UI conventions.

Do not build a separate application.

---

# 24. Admin model configuration

Admin should be able to:

- view active provider
- view active model
- select an enabled model for evaluation
- identify which model generated a candidate
- change the normal default model if the existing configuration architecture permits runtime configuration

If runtime editing is not supported by the current configuration system, expose the configured value and provide the documented deployment configuration path instead.

Do not create fake editable controls that do not persist.

---

# 25. Skill editing

If the current Admin skill system permits editing:

Admin should be able to inspect:

```text
Image generation skill
Purpose
Prompt strategy
Provider
Model
Status
```

If editing model configuration through skills is not currently supported, document the gap rather than duplicating configuration.

---

# 26. Candidate metadata

Every candidate should identify:

```text
generationJobId
personaId
identityVersion
provider
model
seedPrompt
seed where supported
createdAt
status
```

Optional:

```text
cost
latency
resolution
aspectRatio
```

---

# 27. Model switching safety

Changing the active model must not modify existing candidates.

Example:

```text
Candidate A
model = Model-1
```

Admin switches:

```text
default = Model-2
```

Candidate A remains:

```text
model = Model-1
```

Future generations use Model-2.

---

# 28. Model removal

If a model is disabled:

- existing candidates remain accessible
- historical metadata remains valid
- new normal generations do not use it
- historical evaluation records remain readable

Do not delete historical model metadata.

---

# 29. Model availability failure

If configured model is unavailable:

```text
generation request
       ↓
configuration validation
       ↓
clear failure
```

Do not silently switch to another model unless an explicit fallback policy already exists.

If fallback exists, record the actual model used.

---

# 30. Acceptance Test — Normal generation

1. Configure OpenRouter.
2. Configure Model A.
3. Select a persona.
4. Generate 4 candidates.
5. Inspect candidates.

Expected:

- provider = OpenRouter
- model = Model A
- correct persona
- correct identity version
- correct reference set
- seed recorded
- candidates accessible

---

# 31. Acceptance Test — Model switch

1. Generate with Model A.
2. Change default to Model B.
3. Generate again.

Expected:

```text
Generation 1 → Model A
Generation 2 → Model B
```

Historical candidates remain unchanged.

---

# 32. Acceptance Test — Model evaluation

Run:

```text
Persona X
Identity Vx
Reference set R
Seed S
```

against:

```text
Model A
Model B
Model C
```

Expected:

- same identity inputs
- same seed where supported
- separate jobs
- model recorded on every candidate
- Admin can compare outputs
- no candidate is silently attributed to another model

---

# 33. Acceptance Test — Fake provider

Run the same generation through Fake provider.

Expected:

- existing automated pipeline remains green
- provider abstraction is respected
- candidate metadata identifies Fake provider
- no OpenRouter dependency in unit tests

---

# 34. Acceptance Test — Credential safety

Verify:

```text
API key absent from logs
API key absent from DB
API key absent from candidate metadata
API key absent from generated Markdown
API key absent from git diff
```

---

# 35. Acceptance Test — Provider failure

Simulate:

```text
timeout
5xx
invalid model
provider rejection
```

Expected:

- correct retry classification
- sanitized error
- no secret leakage
- job reaches correct terminal state
- observability event recorded

---

# 36. Acceptance Test — Cost/SLA

Complete one generation.

Admin should be able to determine:

```text
model
provider
latency
cost, if available
```

If cost is unavailable, display:

```text
Cost: unavailable
```

not zero.

---

# 37. Definition of Done

This task is complete when:

- [ ] OpenRouter remains behind provider abstraction.
- [ ] Image model is configurable.
- [ ] Active/default model is deterministic.
- [ ] Admin can inspect active model.
- [ ] Evaluation workflow can select models.
- [ ] Provider/model is persisted per candidate/job.
- [ ] Persona identity remains separate from engine configuration.
- [ ] PromptCompiler remains the single prompt construction path.
- [ ] Four-candidate generation is supported.
- [ ] Seed handling is explicit.
- [ ] Cost metadata is captured when available.
- [ ] SLA/latency metadata is captured.
- [ ] Provider failures remain classified.
- [ ] API secrets never enter persistence/logs/UI.
- [ ] Existing Fake provider tests remain green.
- [ ] OpenRouter live smoke can be run when credentials are supplied.
- [ ] Existing image pipeline behavior is not regressed.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 38. Cursor implementation rule

Before coding:

1. Inspect current provider abstraction.
2. Inspect OpenRouter provider.
3. Inspect current model configuration.
4. Inspect image skill implementation.
5. Inspect PromptCompiler.
6. Inspect candidate/job metadata.
7. Inspect Admin skill/configuration UI.
8. Inspect Task 12 model evaluation.
9. Reuse existing configuration and skill systems.
10. Do not create duplicate provider/model/skill registries.

After coding, update:

```text
IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md
```

with:

```text
Provider configuration:
Active model:
Model selection:
Skill integration:
Admin UI:
Prompt integration:
Candidate metadata:
Cost:
SLA:
Security:
Tests:
OpenRouter live verification:
Known gaps:
Git commit:
```

---

# 39. Completion principle

The image engine must be replaceable without changing:

```text
Persona identity
Reference-image system
Candidate warehouse
Admin review workflow
Chat integration
```

Only the provider/model layer should change.

The intended architecture is:

```text
PERSONA
  │
  ├── Visual Identity
  │      ├── Physical attributes
  │      └── Reference images
  │
  └── Seed / Scene
           │
           ▼
     IMAGE GENERATION SKILL
           │
           ▼
       PROMPT COMPILER
           │
           ▼
      IMAGE PROVIDER
           │
      ┌────┼────┐
      ▼    ▼    ▼
   Model A Model B Model C
      │    │    │
      └────┼────┘
           ▼
       CANDIDATES
           │
           ▼
      ADMIN REVIEW
```

This task must not move persona identity into the model configuration layer.
