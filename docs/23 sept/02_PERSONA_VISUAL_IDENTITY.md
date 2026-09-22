# 24_PERSONA_VISUAL_IDENTITY_AND_REFERENCE_IMAGES.md

````md
# TASK 24 — PERSONA VISUAL IDENTITY & REFERENCE IMAGE SYSTEM

**Date:** 2026-09-23  
**Phase:** System Quality / Image Pipeline  
**Depends on:** Image Pipeline core implementation and Task 23 governance  
**Status:** READY FOR IMPLEMENTATION  
**Owner:** Cursor

---

# 1. PURPOSE

Build the complete **Persona Visual Identity** system that becomes the authoritative visual source for image generation.

The image pipeline must NOT rely only on a text description of a persona.

For every persona that is enabled for image generation, the system must be able to provide:

1. Structured physical/visual attributes
2. Appearance details
3. Dressing/style information
4. Identity reference images
5. The active/latest visual identity configuration
6. Appropriate reference images to the image-generation model

The objective is visual consistency.

A generated image of a persona should represent the same fictional person across different:

- scenes
- poses
- clothing
- locations
- lighting
- image-generation requests
- image-generation sessions

---

# 2. IMPORTANT GOVERNANCE RULE

This task is NOT complete when the database/backend exists.

This task is complete only when all of the following work together:

```text
Admin UI
   ↓
Persona Visual Identity
   ↓
Backend/API
   ↓
Database
   ↓
Reference Image Storage
   ↓
Image Pipeline Identity Resolution
   ↓
Image Generation Request
````

The feature must be manually testable from the Admin UI.

Do not implement backend-only functionality and mark this task complete.

---

# 3. SCOPE

This task covers:

* Persona visual identity data
* Physical attributes
* Appearance attributes
* Dressing/style attributes
* Private visual attributes
* Reference-image management
* Standard reference-image categories
* Reference image storage
* Active/reference selection
* Persona → image pipeline identity resolution
* Admin UI
* APIs
* Persistence
* Security
* Tests
* Documentation of the actual implementation

This task does NOT cover:

* Image generation itself
* Image model comparison
* Candidate selection
* Image warehouse
* Image posting
* Image regeneration
* Image SLA dashboard
* Image cost dashboard
* Content publishing

Those will be separate tasks.

---

# 4. SOURCE OF TRUTH

The Persona Visual Identity is the authoritative source for the image pipeline.

When generating an image:

```text
Persona
   ↓
Active Visual Identity
   ↓
Structured visual attributes
   +
Reference images
   ↓
Prompt/image request
```

The image pipeline must never silently use stale persona visual information when an updated active visual identity exists.

---

# 5. VISUAL IDENTITY DATA

Create a structured visual identity associated with a persona.

The exact database representation may reuse an existing model if one already exists.

Do NOT create duplicate competing identity systems.

First inspect the existing implementation.

If an existing visual identity system already satisfies a requirement:

```text
REUSE
```

If partially implemented:

```text
EXTEND
```

Only create a new system where functionality genuinely does not exist.

---

# 6. PHYSICAL ATTRIBUTES

The Admin UI must support structured physical attributes.

At minimum investigate/support:

## General

* Height
* Weight
* Body build
* Muscularity
* Skin tone / skin color
* Overall body description

## Face

* Face shape
* Eye color
* Eye shape
* Eyebrow description
* Nose description
* Lip description
* Facial structure
* Other distinguishing facial characteristics

## Hair

* Hair color
* Hair style
* Hair length
* Hair texture

## Body

* Bust/chest
* Waist/belly
* Hips
* Body proportions
* Muscularity
* Other distinguishing body characteristics

## Distinguishing marks

* Birthmarks
* Moles
* Scars
* Tattoos
* Other permanent identifying features

The implementation should distinguish between:

```text
structured attribute
```

and

```text
free-form description
```

where useful.

Do not force every visual characteristic into an artificial enum if the existing architecture does not require it.

---

# 7. GENDER-SPECIFIC ATTRIBUTES

The system should support appropriate attributes for different persona presentations.

Do not hard-code a female-only schema.

For example:

```text
Female persona
Male persona
Other supported persona presentation
```

should be able to have relevant physical characteristics.

Use neutral/common fields where possible and persona-specific fields only where necessary.

---

# 8. PRIVATE VISUAL ATTRIBUTES

The persona visual identity may contain sensitive/private fictional-character visual information.

Examples may include:

* breast characteristics
* nipple characteristics
* butt/body characteristics
* genital/private anatomy descriptions

These must be treated separately from ordinary public appearance information.

The architecture must support:

```text
PUBLIC_VISUAL_IDENTITY
PRIVATE_VISUAL_IDENTITY
```

or an equivalent access-controlled representation.

Private reference images, if supported, must NOT automatically become public persona images.

---

# 9. PRIVATE REFERENCE IMAGES

Private reference images are optional.

They must be stored separately from ordinary identity references.

Requirements:

* Explicitly marked as private
* Separate access control
* Never exposed by ordinary public image APIs
* Never accidentally included in normal persona profile responses
* Only supplied to an image-generation workflow when explicitly required and authorized
* Must not appear in ordinary Admin UI lists without an appropriate indication

Do not duplicate image bytes unnecessarily if the existing storage abstraction can safely represent access scope.

---

# 10. STANDARD IDENTITY REFERENCE IMAGES

Define a standard set of identity reference slots.

Required standard slots:

```text
FRONT
FACE_CLOSE
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

Optional:

```text
PRIVATE
OTHER
```

The implementation may use enums or an equivalent representation.

Each reference image should have metadata such as:

* reference ID
* persona ID
* visual identity ID/version
* reference type
* storage key / asset ID
* active/inactive status
* created timestamp
* updated timestamp
* optional admin remark

---

# 11. REFERENCE IMAGE RULES

The Admin must be able to:

* Upload a reference image
* Select its reference type
* View it
* Replace it
* Remove it
* Mark it active/inactive where applicable
* Add an admin remark
* See when it was uploaded

The system must prevent ambiguous identity state.

For example, if the architecture expects one active FRONT reference, replacing FRONT should not result in multiple silently-active FRONT images unless multiple references are intentionally supported.

Cursor must inspect the existing model before deciding the exact cardinality.

---

# 12. IDENTITY VERSIONING

Investigate whether the existing Persona/Visual Identity architecture already supports versioning.

If it does:

* reuse it.

If it does not:

* introduce the smallest safe mechanism needed to prevent image jobs from silently using an unexpected identity state.

At minimum, an image-generation job should be able to identify which visual identity state/version was used.

Example:

```text
Persona: Simran
Visual Identity: v4
Generation Job: abc123
```

If the admin later changes:

```text
hair color
```

to a new value, old generated images should remain historically understandable.

Do not rewrite historical image metadata merely because the persona's current identity changed.

---

# 13. IMAGE PIPELINE INTEGRATION

This is a critical requirement.

The image pipeline must resolve:

```text
personaId
   ↓
active visual identity
   ↓
visual attributes
   ↓
active reference images
```

The generation request must be able to receive this identity information.

The image-generation path must NOT allow a caller to bypass the persona identity resolution simply by supplying arbitrary visual metadata.

The exact precedence should be documented.

Expected conceptual precedence:

```text
PERSONA IDENTITY
      >
SCENE REQUEST
```

The scene request describes what the persona is doing.

The persona identity describes who the persona is.

Example seed:

```text
Generate Simran swimming in a yellow bikini
on a Goa beach in the early morning,
facing the camera.
```

The seed describes:

```text
scene
pose
clothing
location
lighting/time
```

The Visual Identity supplies:

```text
face
body
hair
eyes
skin
distinctive marks
reference images
```

The scene must not silently overwrite permanent identity characteristics.

---

# 14. REFERENCE IMAGE DELIVERY TO MODEL

The existing image provider abstraction must be inspected.

Determine whether it supports:

* image URLs
* binary/image input
* multipart references
* provider-specific reference-image fields

Use the existing provider abstraction where possible.

Do NOT create provider-specific logic inside the Persona layer.

Desired architecture:

```text
PersonaVisualIdentity
        ↓
IdentityResolver
        ↓
ImageGenerationRequest
        ↓
Provider abstraction
        ↓
OpenRouter image model
```

Provider-specific formatting belongs in the provider adapter.

---

# 15. PROMPT COMPILER INTEGRATION

The existing `PromptCompiler` must remain the central prompt-construction path.

Do not create a second prompt compiler.

The compiler should receive identity information and scene information separately.

Conceptually:

```text
Visual Identity
       +
Reference Images
       +
Seed Scene
       ↓
PromptCompiler
       ↓
ImageGenerationRequest
```

The final implementation must document exactly how identity information enters the request.

---

# 16. ADMIN UI

Add/complete a **Visual Identity** section inside Persona administration.

Conceptually:

```text
PERSONA
├── Basic Information
├── Personality
├── Skills
├── Visual Identity
│   ├── Physical Attributes
│   ├── Appearance
│   ├── Distinguishing Marks
│   ├── Dressing / Style
│   ├── Private Visual Details
│   └── Reference Images
└── ...
```

Do not create a separate unrelated admin system if Persona administration already exists.

---

# 17. ADMIN — PHYSICAL ATTRIBUTES UI

The admin must be able to:

* View current values
* Edit values
* Save changes
* Reload and verify persistence

The UI should make structured attributes understandable.

Example:

```text
Height:        168 cm
Weight:        56 kg
Body build:    Slim
Muscularity:   Low
Eye color:     Brown
Hair color:    Dark brown
Hair style:    Long, wavy
Skin tone:     ...
```

Use appropriate controls for structured values.

Use text areas for descriptive characteristics where appropriate.

---

# 18. ADMIN — PRIVATE DETAILS UI

Private details should be visually and functionally separated from ordinary visual attributes.

The Admin UI should make the distinction obvious.

Do not expose private information through ordinary persona endpoints accidentally.

---

# 19. ADMIN — REFERENCE IMAGE UI

The UI should show reference slots clearly.

Example:

```text
REFERENCE IMAGES

[ FRONT ]
[image]
Status: Active
Uploaded: ...
[Replace] [Remove]

[ FACE CLOSE ]
[image]
Status: Active
[Replace] [Remove]

[ LEFT PROFILE ]
[image]

[ RIGHT PROFILE ]
[image]

[ BACK ]
[image]

[ PRIVATE REFERENCES ]
[restricted section]
```

The exact UI can follow existing Admin UI conventions.

---

# 20. STORAGE

Reuse the existing image/object-storage abstraction.

Do NOT introduce another storage mechanism.

Reference images must have stable storage identifiers.

The storage key must not expose unsafe filesystem paths.

Apply the existing:

* path traversal protection
* MIME validation
* size limits
* authorization
* storage abstraction

---

# 21. API REQUIREMENTS

Inspect existing persona APIs first.

Add only the endpoints actually needed.

Potential structure:

```text
GET    /v1/admin/personas/{personaId}/visual-identity
PUT    /v1/admin/personas/{personaId}/visual-identity

POST   /v1/admin/personas/{personaId}/visual-identity/references
GET    /v1/admin/personas/{personaId}/visual-identity/references
DELETE /v1/admin/personas/{personaId}/visual-identity/references/{referenceId}
PUT    /v1/admin/personas/{personaId}/visual-identity/references/{referenceId}
```

These are examples, not mandatory paths.

Reuse existing route conventions.

---

# 22. SECURITY

Verify:

* Only authorized admins can modify visual identity
* Users cannot modify visual identity
* Private reference images cannot be accessed through public APIs
* Persona ownership/access rules remain intact
* Storage keys are not directly exposed if that would bypass authorization
* Deleted references cannot continue to be served accidentally
* Invalid persona IDs do not leak information

Test cross-persona access.

---

# 23. IMAGE GENERATION INPUT CONTRACT

The generation pipeline must be able to produce an internal representation similar to:

```text
ImageGenerationContext

personaId
visualIdentityId/version

physicalAttributes
appearanceAttributes
distinguishingMarks
style/dressing information

referenceImages:
    FRONT
    FACE_CLOSE
    LEFT_PROFILE
    RIGHT_PROFILE
    BACK
    optional PRIVATE

seedPrompt
```

Do not necessarily use this exact class.

Reuse existing domain objects where appropriate.

---

# 24. IMPORTANT: DO NOT IMPLEMENT MODEL COMPARISON YET

The future pipeline will compare generated candidates against reference images.

That is NOT this task.

For now, establish the clean identity/reference foundation.

Future task:

```text
Generated Candidate
       ↓
Identity comparison
       ↓
Similarity / consistency signal
       ↓
Admin review
```

Do not fabricate an identity score in this task.

---

# 25. DATA FLOW

The final implementation should support this flow:

```text
ADMIN
  │
  ▼
Persona
  │
  ▼
Visual Identity
  │
  ├── Physical attributes
  ├── Appearance
  ├── Distinguishing marks
  ├── Dressing/style
  └── Reference images
          │
          ▼
      Object Storage
          │
          ▼
User/Admin image request
          │
          ▼
Persona ID
          │
          ▼
Active Visual Identity Resolver
          │
          ▼
Prompt Compiler
          │
          ├── Identity
          ├── References
          └── Scene seed
          │
          ▼
Image Provider
```

---

# 26. TEST REQUIREMENTS

At minimum add tests for:

## Identity persistence

* Create visual identity
* Update visual identity
* Reload and verify values

## Reference images

* Upload FRONT
* Upload FACE_CLOSE
* Upload LEFT_PROFILE
* Upload RIGHT_PROFILE
* Upload BACK
* Replace reference
* Remove reference
* Verify metadata

## Private references

* Upload private reference
* Verify private classification
* Verify unauthorized access is blocked

## Security

* Non-admin cannot modify identity
* User cannot access private reference
* Persona A cannot access Persona B references

## Image pipeline integration

Given:

```text
personaId = X
```

verify that the generation context contains:

```text
X's active visual identity
X's active references
```

and does not use another persona's identity.

## Update behavior

Update:

```text
hair color
```

Then create a new generation request.

Verify the new value is used.

## Historical integrity

If versioning exists, verify that an old generation remains associated with the identity state used at generation time.

---

# 27. MANUAL ADMIN TEST

Cursor must provide a manual test procedure.

Example:

### Step 1

Open Admin → Persona.

### Step 2

Select a test persona.

### Step 3

Open Visual Identity.

### Step 4

Set several physical attributes.

### Step 5

Upload:

```text
front.jpg
face-close.jpg
left-profile.jpg
right-profile.jpg
back.jpg
```

### Step 6

Save.

### Step 7

Reload the page.

### Step 8

Verify every value and image remains present.

### Step 9

Change one physical characteristic.

### Step 10

Verify image-generation context uses the new value.

### Step 11

Attempt unauthorized access.

### Step 12

Verify access is rejected.

---

# 28. ACCEPTANCE CRITERIA

This task is COMPLETE only if:

* [ ] Persona has editable Visual Identity
* [ ] Physical attributes are persisted
* [ ] Appearance attributes are persisted
* [ ] Distinguishing marks are persisted
* [ ] Dressing/style information is persisted
* [ ] Private visual information is separately controlled
* [ ] Standard reference-image slots exist
* [ ] Reference images can be uploaded
* [ ] Reference images can be replaced
* [ ] Reference images can be removed
* [ ] Reference metadata persists
* [ ] Reference images use durable storage
* [ ] Admin UI supports the complete workflow
* [ ] APIs support the workflow
* [ ] Authorization is enforced
* [ ] Image pipeline resolves identity from personaId
* [ ] Active/latest identity is used
* [ ] Reference images are available to image generation
* [ ] Existing PromptCompiler/provider architecture is reused
* [ ] Tests cover persistence
* [ ] Tests cover security
* [ ] Tests cover identity resolution
* [ ] Tests cover reference management
* [ ] Manual Admin UI test succeeds
* [ ] No unrelated image-generation features are introduced

---

# 29. DO NOT DO

Do NOT:

* Build the image warehouse in this task
* Build publishing/posting
* Build candidate scoring
* Build identity similarity scoring
* Build daily image generation
* Build storyline generation
* Build LoRA training
* Replace the existing image provider abstraction
* Create a second PromptCompiler
* Create a second storage abstraction
* Hard-code one persona's identity
* Hard-code one image model
* Put private reference images into public persona responses
* Mark the task complete based only on unit tests

---

# 30. IMPLEMENTATION INVESTIGATION

Before modifying code, inspect:

* Existing Persona model
* Existing Persona admin routes
* Existing Persona admin UI
* Existing Visual Identity implementation
* Existing reference-image implementation
* Existing image storage abstraction
* Existing PromptCompiler
* Existing ImageGenerationRequest
* Existing ImageGenerationHandler
* Existing provider abstraction
* Existing authorization rules

First classify each requirement:

```text
EXISTS — REUSE
PARTIAL — EXTEND
MISSING — IMPLEMENT
NOT NEEDED — DOCUMENT
```

Avoid duplicate architecture.

---

# 31. REQUIRED IMPLEMENTATION REPORT

Cursor MUST update:

`docs/for cursor/24_PERSONA_VISUAL_IDENTITY_AND_REFERENCE_IMAGES_STATUS.md`

The file must contain:

## A. Current state before work

What already existed.

## B. Requirement mapping

| Requirement         | Existing | Action | Final Status |
| ------------------- | -------- | ------ | ------------ |
| Physical attributes |          |        |              |
| Appearance          |          |        |              |
| Private attributes  |          |        |              |
| Reference images    |          |        |              |
| Admin UI            |          |        |              |
| API                 |          |        |              |
| Storage             |          |        |              |
| Identity resolution |          |        |              |
| Security            |          |        |              |
| Tests               |          |        |              |

## C. Files changed

List every changed/new file.

## D. Database changes

Document migrations/schema changes.

## E. API changes

Document actual endpoints.

## F. Admin UI changes

Describe what an administrator can now do.

## G. Image pipeline integration

Document exactly how persona visual identity reaches image generation.

## H. Security

Document authorization and private-reference handling.

## I. Tests

Report:

```text
Total:
Passed:
Failed:
Skipped:
```

Also list the important tests.

## J. Manual verification

Document the exact Admin UI workflow tested.

## K. Known gaps

Anything not implemented.

## L. Final verdict

Use only:

```text
PASS
PASS WITH FINDINGS
BLOCKED
```

Do not call it PASS if any acceptance criterion is incomplete.

---

# 32. STOP CONDITION

When this task is complete:

**STOP.**

Do not automatically continue to Task 25.

Task 25 will be issued separately after review of this implementation report.

The purpose is to allow the product owner to inspect the actual implementation before additional image-pipeline functionality is layered on top.

---

# 33. FINAL OUTPUT REQUIRED FROM CURSOR

At completion, return:

```text
TASK 24 COMPLETE

Verdict:
Commit:
Branch:
Tests:
Admin UI verified:
Backend verified:
Reference storage verified:
Identity resolution verified:

Known gaps:
...

Status report:
docs/for cursor/24_PERSONA_VISUAL_IDENTITY_AND_REFERENCE_IMAGES_STATUS.md
```

Do not claim functionality that was not actually implemented and tested.

```
```
