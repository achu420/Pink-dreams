# IMAGE PIPELINE --- IMPLEMENTATION SUMMARY FOR CURSOR

## Purpose

This document is the implementation-oriented starting point for the
Image Pipeline work.

The existing product architecture is built around this loop:

``` text
USER NEED
   ↓
UNDERSTAND WHAT USER WANTS
   ↓
SELECT APPROPRIATE SKILL
   ↓
USE RELEVANT MEMORY
   ↓
RESPOND AS THE PERSONA
   ↓
MAKE THE INTERACTION USEFUL / ENJOYABLE
   ↓
CONTINUE NATURALLY
```

The image pipeline must fit into that architecture rather than becoming
a separate personality/intent system.

The product definition explicitly separates:

``` text
Intent Discovery
"What does the user want right now?"
        ↓
Skill Selection
"Which behavior should the persona use?"
        ↓
Memory Selection
"Which memories about the user matter?"
        ↓
Persona + Engine
"How would the persona respond?"
        ↓
Generation
"Produce the actual response."
```

The persona is a core product element and is expected to retain
personality, preferences, humor, opinions, emotional style, interests,
boundaries, and history.

The image pipeline should therefore produce images that are consistent
with the selected persona and the current user intent.

------------------------------------------------------------------------

# 1. IMPORTANT IMPLEMENTATION PRINCIPLE

Do NOT build a second conversational architecture for images.

Do NOT create:

``` text
Image Intent Engine
Image Persona Engine
Image Memory Engine
Image Conversation Engine
```

unless the existing codebase proves that a separate component is
required.

Instead, reuse the existing architecture wherever possible:

``` text
Existing user message
        ↓
Existing context
        ↓
Existing intent discovery
        ↓
Existing skill selection
        ↓
Existing memory selection
        ↓
Existing persona + engine context
        ↓
IMAGE PIPELINE
        ↓
Image generation provider/model
        ↓
Validation
        ↓
Storage
        ↓
Delivery
        ↓
Observability
```

The image pipeline is a generation modality, not a replacement for the
existing conversation architecture.

------------------------------------------------------------------------

# 2. IMAGE PIPELINE RESPONSIBILITIES

The image pipeline should own image-specific concerns:

1.  Determine that the selected behavior requires image generation.
2.  Construct an image-generation request from already-resolved
    application context.
3.  Preserve persona identity and visual continuity where the existing
    data supports it.
4.  Resolve image-specific model/provider configuration.
5.  Generate the image.
6.  Validate the result.
7.  Persist the generated asset and metadata.
8.  Return the asset to the existing conversation/request flow.
9.  Capture failures and latency.
10. Support regeneration/editing only where the existing product
    requirements and APIs support them.

It should NOT independently decide the user's overall relationship
intent.

------------------------------------------------------------------------

# 3. PRODUCT CONTEXT

The product supports multiple use cases including:

-   companionship
-   friendship
-   emotional support
-   romance
-   romantic intimacy
-   sexual stimulation
-   flirting practice
-   dating practice
-   relationship guidance
-   conversation/social skills
-   entertainment
-   general advice/problem solving/learning

Sexual stimulation is explicitly treated as a distinct skill/use case
rather than automatically collapsing every sexual request into romance
or foreplay.

This matters to images because an image request must retain the selected
skill/context instead of guessing its meaning again.

For example:

``` text
USER:
"Show me Simran on a mountain road."

Existing system:
intent → skill → memory → persona
                         ↓
                    image request
```

The image generator should receive the already-resolved context rather
than re-classifying the request from scratch.

------------------------------------------------------------------------

# 4. REQUIRED IMAGE REQUEST CONTEXT

Before an image request reaches the image generator, establish which of
these values already exist in the current codebase:

``` text
conversationId
turnRequestId
userId
personaId
personaVersion
selectedSkillKey
intent
memory/context selection
persona context
engine context
image request type
prompt/instructions
reference images, if supported
```

Do not invent new persistence fields simply because they would be
useful.

First inspect the repository and document:

-   what already exists
-   what can be reused
-   what is missing
-   what must actually be added

------------------------------------------------------------------------

# 5. IMAGE REQUEST TYPES

The implementation should explicitly distinguish the image operation
type if the existing product needs these modes:

``` text
GENERATE
REGENERATE
EDIT
REFERENCE_BASED_GENERATION
```

If the existing application only supports generation today, implement
only the supported path first.

Do not build speculative image-editing infrastructure unless existing
code or requirements require it.

------------------------------------------------------------------------

# 6. PERSONA VISUAL CONSISTENCY

This is one of the most important image-pipeline concerns.

The persona should not visually change randomly from image to image when
the product expects continuity.

The pipeline should inspect the existing Persona model/data and identify
whether it already contains:

-   appearance
-   face/identity description
-   hair
-   body/build
-   age
-   clothing preferences
-   style
-   recurring visual traits
-   reference images
-   image-generation instructions
-   negative constraints

If these already exist, reuse them.

If they do not exist, do NOT silently invent a new schema. Document the
missing capability and propose the smallest required addition.

The image prompt should conceptually combine:

``` text
PERSONA VISUAL IDENTITY
+
CURRENT SCENE / USER REQUEST
+
CURRENT SKILL / BEHAVIOR CONTEXT
+
RELEVANT MEMORY
+
IMAGE STYLE / TECHNICAL PARAMETERS
```

Do not allow transient user wording to overwrite stable persona identity
accidentally.

------------------------------------------------------------------------

# 7. MEMORY IN IMAGE GENERATION

Memory should be used selectively.

The existing product principle is:

``` text
Memory Selection
"Which memories about the user matter right now?"
```

For image generation, only relevant memory should influence the image.

Examples of potentially useful memory:

``` text
user likes mountain trips
user prefers realistic photography
user previously requested a specific outfit
user and persona discussed a recurring location
```

Do not inject the entire memory store into an image prompt.

Do not create a second independent memory-selection mechanism without
repository evidence that it is necessary.

------------------------------------------------------------------------

# 8. PROMPT CONSTRUCTION

Prompt construction should be deterministic and inspectable.

Conceptual structure:

``` text
[PERSONA IDENTITY]

[CURRENT SCENE]

[RELEVANT USER REQUEST]

[RELEVANT MEMORY]

[VISUAL STYLE]

[TECHNICAL REQUIREMENTS]

[NEGATIVE / EXCLUSION CONSTRAINTS]
```

Keep stable persona information separate from dynamic scene information.

Example conceptual representation:

``` text
PERSONA:
Simran's established visual identity.

SCENE:
Mountain road in Uttarakhand.

ACTION:
Riding a bicycle.

MOOD:
Excited, natural, candid.

STYLE:
Photorealistic social-media photograph.

CONSTRAINTS:
Preserve established persona appearance.
```

The exact prompt format must follow the existing provider/model
integration after repository inspection.

------------------------------------------------------------------------

# 9. MODEL / PROVIDER ROUTING

Inspect the existing LLM/provider configuration architecture before
adding image configuration.

Determine whether the project already has:

-   provider abstraction
-   model configuration
-   runtime settings
-   admin settings
-   provider fallback
-   timeout handling
-   retry handling
-   observability

If there is an existing generic provider abstraction that can safely
support image generation, reuse it.

If image providers require a different API contract, create a separate
image-provider interface rather than forcing image APIs into a text-only
interface.

Conceptual abstraction:

``` text
ImageGenerationClient
        ↓
ObservableImageGenerationClient
        ↓
Provider implementation
```

Do not modify the existing text `LlmClient` merely to make image
generation fit.

------------------------------------------------------------------------

# 10. IMAGE GENERATION RESULT

The image generation layer should return a structured result.

Conceptual shape:

``` text
ImageGenerationResult
├── success
├── assetId / storage reference
├── provider
├── model
├── latency
├── width
├── height
├── mimeType
├── generation metadata
└── error information
```

Use existing project naming and data conventions where available.

Do not duplicate existing asset/storage models.

------------------------------------------------------------------------

# 11. STORAGE

Inspect the existing image/file storage implementation first.

Determine:

-   where generated images are stored
-   whether storage is local/object storage/database-backed
-   how URLs are generated
-   how access is authenticated
-   how assets are linked to conversations/messages
-   whether cleanup exists
-   whether metadata is stored separately

The pipeline should store the generated asset once and reference it from
the conversation response.

Avoid storing large binary images directly in relational rows unless the
existing architecture already does that.

------------------------------------------------------------------------

# 12. DELIVERY

The user-facing conversation response should be able to identify the
generated image as an assistant output.

Reuse the existing message/persistence format where possible.

Conceptually:

``` text
User message
      ↓
Image request
      ↓
Image generation
      ↓
Asset persistence
      ↓
Assistant message
      ↓
Image reference
```

Do not create a second conversation history just for images.

------------------------------------------------------------------------

# 13. FAILURE PATHS

Image generation must not silently fail.

At minimum distinguish:

``` text
INVALID_REQUEST
PROVIDER_ERROR
TIMEOUT
CONTENT/POLICY_REJECTION
GENERATION_FAILURE
STORAGE_FAILURE
DELIVERY_FAILURE
UNKNOWN_ERROR
```

Only use categories that fit the existing application conventions.

Critical rule:

A storage/observability failure must not accidentally corrupt the
already-generated asset or make the whole conversation unusable unless
the product genuinely requires atomic behavior.

Follow the existing failure-boundary conventions from the text pipeline
where applicable.

------------------------------------------------------------------------

# 14. RETRY / REGENERATION

Regeneration must not accidentally reuse an identical random request
unless that is intentional.

The system should preserve enough metadata to understand:

``` text
original request
persona
skill
prompt/context
model
provider
generation attempt
```

If regeneration is supported, each attempt should be independently
observable.

Do not implement unlimited automatic retries.

Respect existing retry conventions.

------------------------------------------------------------------------

# 15. OBSERVABILITY

The existing project has an observability architecture for LLM
exchanges.

Before implementing new observability, inspect whether image calls can
safely use the same framework.

At minimum image-generation telemetry should eventually make it possible
to answer:

``` text
Which image call happened?
Which turn?
Which conversation?
Which persona?
Which skill?
Which model?
Which provider?
How long did it take?
Did it succeed?
Did it fail?
Why did it fail?
```

Potential metrics:

``` text
image_generation_count
image_generation_success_rate
image_generation_failure_rate
image_generation_latency
image_generation_by_model
image_generation_by_provider
image_generation_by_skill
```

Do not assume text LLM metrics automatically cover image generation.

------------------------------------------------------------------------

# 16. SLA

Image generation should be tracked separately from conversational text
SLA.

Do NOT mix:

``` text
text generation latency
```

with:

``` text
image generation latency
```

into one misleading metric.

The existing observability work established that on-path and
asynchronous workloads need to be distinguished.

Apply the same principle to image work.

For every image call, determine:

``` text
on-path image generation
async image generation
```

if both exist.

------------------------------------------------------------------------

# 17. SECURITY

Inspect every user-controlled value before inserting it into:

-   HTML
-   JavaScript
-   provider payloads
-   logs
-   metadata
-   filenames
-   storage keys

Never expose provider credentials.

Never persist authorization headers.

Never return provider secrets in admin endpoints.

Follow the existing `escapeHtml()` and admin-security conventions for UI
work.

------------------------------------------------------------------------

# 18. ADMIN REQUIREMENTS

Do not immediately redesign the admin UI again.

First inspect the existing admin architecture and determine what image
controls already exist.

Eventually administrators should be able to understand image
configuration, but only expose settings that are actually implemented.

Potential future controls:

``` text
image model
image provider
default dimensions
quality
style
timeout
retry policy
enabled/disabled
```

Do not display a setting as editable unless changing it actually changes
runtime behavior.

This follows the same principle already established for AI Runtime
Settings.

------------------------------------------------------------------------

# 19. TESTING STRATEGY

The implementation should have tests for:

### Request construction

``` text
persona context included
scene included
relevant skill included
irrelevant memory excluded
```

### Provider behavior

``` text
success
provider failure
timeout
malformed response
```

### Persistence

``` text
asset saved
message references asset
storage failure handled
```

### Attribution

``` text
conversationId
turnRequestId
persona
skill
model
provider
```

### Regeneration

``` text
new generation attempt
separate observability record
same persona identity
updated generation attempt metadata
```

### Security

``` text
no API key leakage
no authorization header persistence
safe user-controlled strings
```

### End-to-end

At least one real or integration-tested path:

``` text
user request
→ existing intent/skill
→ image pipeline
→ provider
→ storage
→ assistant message
```

------------------------------------------------------------------------

# 20. IMPLEMENTATION ORDER

Cursor should work in this order.

## Phase 1 --- Repository audit

Do NOT change code yet.

Find and document:

``` text
1. Existing image generation code
2. Existing image provider clients
3. Existing asset/file storage
4. Existing message attachment/image representation
5. Persona visual fields
6. Existing skill architecture
7. Existing memory selection
8. Existing observability
9. Existing admin configuration
10. Existing image tests
```

Output:

``` text
IMAGE_PIPELINE_AUDIT.md
```

with:

``` text
EXISTS
MISSING
REUSABLE
NEEDS CHANGE
```

Do not implement anything until this audit is complete.

------------------------------------------------------------------------

# 21. PHASE 2 --- ARCHITECTURE GAP REPORT

After the audit, produce:

``` text
IMAGE_PIPELINE_GAP_ANALYSIS.md
```

Include:

``` text
Current flow
Target flow
Missing components
Files that need modification
New files required
Database changes
API changes
Admin changes
Test changes
Risks
```

Do not make speculative schema changes.

------------------------------------------------------------------------

# 22. PHASE 3 --- MINIMUM PIPELINE

Implement the smallest complete path:

``` text
existing conversation
        ↓
image-capable skill/intent
        ↓
image request
        ↓
image provider
        ↓
asset storage
        ↓
assistant message
```

Keep the first implementation simple.

Do not simultaneously redesign:

-   Persona
-   Memory
-   Conversation Engine
-   Intent Engine
-   Admin
-   Text generation

------------------------------------------------------------------------

# 23. PHASE 4 --- ATTRIBUTION

Once generation works, ensure the image call is attributable to the same
turn:

``` text
turnRequestId
conversationId
persona
skill
model
provider
```

Then expose this through observability.

------------------------------------------------------------------------

# 24. PHASE 5 --- VISUAL CONSISTENCY

After the basic pipeline works, improve:

``` text
persona visual identity
reference-image handling
memory selection
prompt construction
regeneration consistency
```

Do not mix these concerns into the provider adapter.

------------------------------------------------------------------------

# 25. PHASE 6 --- FAILURE HARDENING

Test:

``` text
provider unavailable
timeout
invalid provider response
storage failure
duplicate request
regeneration
partial failure
```

Verify that failures do not corrupt conversation state.

------------------------------------------------------------------------

# 26. PHASE 7 --- ADMIN

Only after the runtime path is proven should admin controls be added.

Admin must reflect actual runtime configuration.

No fake controls.

No UI-only settings.

No configuration that requires redeploy while pretending to be
live-editable.

------------------------------------------------------------------------

# 27. PHASE 8 --- PERFORMANCE / SLA

Only after enough real image traffic exists:

``` text
measure
→ classify
→ identify bottleneck
→ optimize
```

Do not optimize image latency from a handful of test calls.

------------------------------------------------------------------------

# 28. NON-GOALS

Do NOT:

-   rewrite the Conversation Engine
-   rewrite Intent Discovery
-   rewrite Memory
-   rewrite Persona Core
-   duplicate skill selection
-   duplicate user-profile logic
-   add speculative providers
-   add speculative database tables
-   redesign the whole admin UI
-   optimize latency before measurement
-   fabricate production-scale conclusions
-   create fake configuration controls

------------------------------------------------------------------------

# 29. DEFINITION OF DONE

The image pipeline is complete only when:

``` text
[ ] Existing architecture audited
[ ] Image request can be routed through existing intent/skill context
[ ] Persona visual identity is preserved
[ ] Relevant memory is used appropriately
[ ] Image prompt is constructed deterministically
[ ] Provider abstraction works
[ ] Image generation succeeds
[ ] Image is stored
[ ] Assistant message references the asset
[ ] Failures are handled
[ ] Regeneration works if required
[ ] Image calls are observable
[ ] Turn/conversation/skill attribution works
[ ] Secrets are not persisted
[ ] Admin controls reflect real runtime behavior
[ ] Tests cover success and failure paths
[ ] Live/integration verification completed
[ ] No unrelated conversation architecture was changed
```

------------------------------------------------------------------------

# 30. CRITICAL INSTRUCTION TO CURSOR

**Do not assume the architecture described above already exists in the
repository.**

First inspect the actual code.

When something is missing:

1.  identify it,
2.  explain why it is required,
3.  propose the smallest change,
4.  implement it,
5.  test it,
6.  report exactly what changed.

Do not claim a feature is complete because an interface, UI field, or
database column exists.

A feature is complete only when the runtime path has been verified.

The existing product philosophy is that the system should understand
what the user wants, select the appropriate skill, use relevant memory,
and then respond as the persona. The image pipeline must preserve that
architecture rather than bypassing it.
