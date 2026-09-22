# IMAGE REFERENCE IMAGE SYSTEM

**Task:** 08 — Reference Image Management, Identity Anchoring & Reference Usage  
**Date:** 2026-09-23  
**Parent:** `IMAGE_PIPELINE_GOVERNANCE.md`  
**Depends on:** `02_PERSONA_VISUAL_IDENTITY.md`, existing image pipeline implementation  
**Status:** IMPLEMENT / VERIFY / HARDEN  
**Goal:** Make persona reference images a first-class, persistent input to image generation so generated candidates remain visually consistent with the persona.

---

## 1. Purpose

Every persona that is used for image generation should have a controlled set of reference images.

These references are the visual anchor for the persona.

The image pipeline must not treat the seed prompt as the only source of identity.

The intended generation contract is:

```text
Persona
  +
Persona Visual Identity
  +
Active Reference Images
  +
Seed Prompt
  +
Image Model / Provider Configuration
       ↓
Image Generation
       ↓
4 Candidates
       ↓
Identity / Quality Review
```

The reference-image system must be implemented as part of the existing persona/image pipeline, not as a separate image-generation mechanism.

---

# 2. Product Requirement

Admin manages reference images from the persona's visual-identity area.

For each reference image, admin can:

- upload
- view
- label
- activate/deactivate
- replace
- remove
- inspect metadata
- identify its reference type

The image pipeline uses the currently active reference images when generating new images.

Updating the persona's reference images must affect future generations.

Previously generated candidates must remain historically reproducible/auditable and must not silently change because the persona was later updated.

---

# 3. Standard Reference Types

Implement a controlled reference-type vocabulary.

Required types:

```text
FACE_CLOSE
FRONT
LEFT_PROFILE
RIGHT_PROFILE
BACK
```

Recommended optional type:

```text
FULL_BODY
```

Optional intimate/private reference images are supported separately and must never be required for ordinary image generation.

If the current schema already supports another naming convention, reuse it rather than creating duplicate concepts. Document the mapping.

---

# 4. Reference Image Model

Inspect the existing implementation first.

Reuse existing persona visual/reference-image entities where possible.

Do not create duplicate identity storage if an equivalent model already exists.

At minimum, every reference image needs:

```text
id
personaId
referenceType
storageKey / assetId
mimeType
width
height
active
createdAt
updatedAt
```

Recommended:

```text
displayName
adminRemark
sortOrder
source
checksum
fileSize
version
```

The system must preserve which reference image/version was active when a generation occurred.

---

# 5. Active Reference Set

The generation pipeline must resolve:

```text
personaId
    ↓
current persona visual identity
    ↓
current active reference images
    ↓
generation request
```

Only active references should normally be supplied to generation.

Inactive/deleted references must not accidentally enter a new generation request.

Do not rely on UI state alone.

The backend must perform the active-reference resolution.

---

# 6. Reference Selection Rules

The implementation must define deterministic selection behavior.

Default selection:

```text
FACE_CLOSE
FRONT
LEFT_PROFILE
RIGHT_PROFILE
BACK
FULL_BODY (if available)
```

If the model/provider has a maximum number of reference images:

- use a deterministic priority order
- document the limit
- never randomly select references
- log which references were actually supplied

If fewer than all standard references exist:

- generation may continue if the minimum requirements are satisfied
- missing reference types must be visible in diagnostics/admin UI
- do not fabricate missing references

Recommended minimum:

```text
FACE_CLOSE + FRONT
```

But if the existing product contract already defines another minimum, preserve that contract and document it.

---

# 7. Image Storage

Reference images must use the existing object-storage abstraction.

Do not introduce a second storage implementation.

Supported storage behavior must remain compatible with the existing image pipeline:

```text
ObjectStorage
  ↓
LocalFileObjectStorage / configured implementation
```

Reference storage keys must be portable and must not contain:

- absolute filesystem paths
- user-controlled `..` traversal
- machine-specific temporary paths

Recommended logical key:

```text
personas/{personaId}/references/{referenceId}
```

Do not expose physical storage paths through the API.

---

# 8. Upload Validation

At the HTTP/admin boundary validate:

### File

- MIME type
- file size
- readable image
- supported image format
- decoded dimensions

### Security

Reject:

- path traversal
- arbitrary filesystem paths
- unsupported MIME types
- malformed image payloads
- files exceeding configured size limits

Reuse existing `LocalFileObjectStorage` validation where applicable.

Do not duplicate validation logic unnecessarily.

---

# 9. Reference Image Metadata

The admin UI should make it clear what each reference represents.

Example:

```text
Persona: Simran Kohli

Reference Images

[Face Close]
Active
Uploaded: 2026-09-23 09:14

[Front]
Active
Uploaded: 2026-09-23 09:15

[Left Profile]
Active
Uploaded: 2026-09-23 09:16

[Right Profile]
Active
Uploaded: 2026-09-23 09:17

[Back]
Active
Uploaded: 2026-09-23 09:18
```

For each reference provide:

- preview
- type
- status
- timestamp
- optional admin remark
- activate/deactivate
- replace
- remove

---

# 10. Replacement Semantics

Replacing a reference image must not rewrite historical generations.

Correct behavior:

```text
Reference V1
    ↓
Generation A
    ↓
Candidate A1

Reference replaced

Reference V2
    ↓
Generation B
    ↓
Candidate B1
```

Generation A must retain the information needed to determine that it used Reference V1.

Do not mutate old generation records to point at the newest reference.

---

# 11. Identity Snapshot at Generation Time

This is mandatory.

When a generation job is created, persist enough identity information to establish what was used.

At minimum record:

```text
personaId
visualIdentityVersion
referenceImageIds
referenceImageVersions/checksums where available
seedPrompt
model
provider
generation configuration
createdAt
```

Do not store secrets.

The exact schema should follow existing pipeline conventions.

If the existing generation/job model already has a suitable metadata JSON field, prefer extending it rather than creating unnecessary tables.

---

# 12. Prompt Compiler Integration

The existing `PromptCompiler` remains the single prompt-construction path.

Do not create a second prompt builder.

The compiler receives:

```text
persona visual identity
+
selected reference metadata
+
seed prompt
+
generation configuration
```

The seed prompt describes the requested scene.

The persona identity describes who should appear.

The reference images provide visual anchoring.

Conceptually:

```text
IDENTITY
  ↓
Who is this person?

REFERENCE IMAGES
  ↓
What should this person visually resemble?

SEED
  ↓
What is this person doing / wearing / where are they?

GENERATION
  ↓
Produce candidate
```

The implementation must avoid allowing a seed prompt to silently override persistent persona identity.

---

# 13. Identity Priority

For conflicting information, use this conceptual priority:

```text
Active persona visual identity
        >
Active reference images
        >
Generation-specific seed
```

The seed may specify scene-specific changes such as:

- location
- pose
- activity
- clothing
- lighting
- camera angle
- expression

The seed must not silently redefine persistent identity attributes.

If a user/admin explicitly requests a persistent identity change, that belongs in persona visual identity management, not an individual generation seed.

---

# 14. Private / Intimate References

The product may support optional private reference images.

These must be treated separately from ordinary identity references.

Requirements:

- separate classification
- separate storage namespace where practical
- never include them in ordinary generation automatically
- explicit opt-in by the generation workflow
- never expose them through ordinary public/user asset APIs
- admin-only access
- audit access where the existing security model supports it

Do not make intimate references a prerequisite for normal image generation.

Do not place sensitive reference bytes into prompts, logs, telemetry, or error messages.

---

# 15. API / Backend Requirements

Inspect the existing persona visual APIs before adding routes.

Implement missing capabilities only.

Expected operations:

```text
GET    persona references
POST   upload reference
PATCH  update reference metadata/status
DELETE remove reference
POST   replace reference
```

Exact route names should follow existing API conventions.

Every mutation must enforce admin authorization.

The backend must verify that the referenced persona exists.

The backend must not trust a client-provided `personaId` for authorization.

---

# 16. Admin UI Requirements

Persona admin flow should become:

```text
Persona
  ↓
Visual Identity
  ↓
Physical / visual attributes
  ↓
Reference Images
```

Reference image management must be usable without database/manual intervention.

Admin must be able to:

1. See current references.
2. Upload a reference.
3. Assign its type.
4. Activate/deactivate it.
5. Replace it.
6. Remove it.
7. See which reference types are missing.
8. See when references were last updated.

Do not build a fake UI state.

Every displayed status must come from backend data.

---

# 17. Generation Preview / Diagnostics

Before generation, the admin should be able to determine which references will be used.

Recommended display:

```text
Generation Identity Context

Persona: Simran Kohli

Active references:
✓ Face Close
✓ Front
✓ Left Profile
✓ Right Profile
✓ Back

Visual Identity:
Version 4

Seed:
"Swimming in a yellow bikini in Goa beach..."
```

If the current UI cannot provide a pre-generation preview, expose the resolved reference IDs in the generation/job diagnostics.

---

# 18. Candidate Metadata

Every generated candidate should be traceable to:

```text
generationJobId
personaId
visualIdentityVersion
referenceImageIds
seedPrompt
provider
model
createdAt
```

This allows an admin to answer:

> "Why does this generated image look like this?"

without guessing.

---

# 19. Identity Matching

Do not invent a sophisticated face-recognition system in this task unless the repository already contains one.

First implement deterministic reference usage and traceability.

If an identity-matching/scoring service already exists:

- reuse it
- document its inputs/outputs
- test it

If it does not exist:

```text
STATUS: DEFERRED
```

but the data model and generation metadata must be designed so identity scoring can be added later.

The first version can therefore use:

```text
Reference consistency = reference images supplied + admin visual review
```

rather than pretending that automated identity scoring exists.

---

# 20. Tests

This feature is not complete until backend + admin behavior is testable.

### Unit tests

Test:

- reference type validation
- active/inactive filtering
- deterministic ordering
- replacement semantics
- reference metadata validation
- path traversal rejection
- unsupported MIME rejection
- size limits
- generation snapshot creation

### Integration tests

Test:

```text
create persona
    ↓
upload references
    ↓
activate references
    ↓
create generation job
    ↓
resolve active references
    ↓
persist reference snapshot
    ↓
worker receives generation context
```

### Update test

```text
Reference V1
    ↓
Generation A

replace with V2

Generation B

assert:
A references V1
B references V2
```

### Security tests

Test:

- non-admin cannot modify references
- user cannot access private references
- persona ownership/authorization is enforced
- arbitrary storage paths are rejected
- inactive/private references are not exposed through ordinary endpoints

---

# 21. Admin Acceptance Test

Cursor must provide a repeatable test.

### Test A — upload

```text
1. Open Persona.
2. Open Visual Identity.
3. Upload Face Close reference.
4. Upload Front reference.
5. Upload Left Profile.
6. Upload Right Profile.
7. Upload Back.
8. Verify all appear in admin.
```

### Test B — generation

```text
1. Start image generation for the persona.
2. Enter a seed.
3. Create generation.
4. Inspect job metadata.
5. Verify active reference IDs are recorded.
6. Verify the worker receives the expected references.
7. Generate 4 candidates.
```

### Test C — replacement

```text
1. Replace Front reference.
2. Generate again.
3. Verify new generation references the new image.
4. Verify old generation still references the old image.
```

### Test D — disable

```text
1. Deactivate Left Profile.
2. Generate again.
3. Verify Left Profile is not included.
4. Verify the generation metadata reflects the active set.
```

---

# 22. Definition of Done

This task is DONE only when:

- [ ] Existing implementation was inspected first.
- [ ] Existing persona visual/reference functionality is reused where possible.
- [ ] Standard reference types are supported.
- [ ] Admin can upload references.
- [ ] Admin can label references.
- [ ] Admin can activate/deactivate references.
- [ ] Admin can replace references.
- [ ] Admin can remove references.
- [ ] Backend validates uploads.
- [ ] Storage uses existing object-storage abstraction.
- [ ] Active references are resolved server-side.
- [ ] Generation captures a reference/identity snapshot.
- [ ] Old generations are not changed by later reference updates.
- [ ] PromptCompiler remains the single prompt construction path.
- [ ] Private references are separated from ordinary references.
- [ ] Reference IDs are available in generation diagnostics.
- [ ] Tests cover upload, selection, replacement, security, and generation snapshot.
- [ ] Admin UI is connected to real backend data.
- [ ] No fake metrics/statuses are introduced.
- [ ] Relevant tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 23. Cursor Reporting Requirement

At completion, update:

`IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`

Add:

```text
## Task 08 — Image Reference Image System

Status: DONE / PARTIAL / BLOCKED

Existing implementation found:
...

Reused:
...

Implemented:
...

Admin UI:
...

Backend:
...

Database:
...

Storage:
...

Generation integration:
...

Tests:
...

Commands run:
...

Test result:
...

Known gaps:
...

Files changed:
...

Commit:
...
```

Do not claim DONE if any Definition-of-Done item is unverified.

If an existing implementation already satisfies an item, mark it **VERIFIED / REUSED** rather than reimplementing it.

---

# 24. Scope Boundary

Do NOT implement in this task:

- social posting
- Instagram publishing
- storyline generation
- daily image scheduling
- LoRA training
- automated content factory
- automated identity scoring unless already present
- a new image provider
- a second prompt compiler
- a second storage system

This task is specifically:

```text
PERSONA
  ↓
REFERENCE IMAGES
  ↓
IDENTITY SNAPSHOT
  ↓
IMAGE GENERATION
```

Complete and test this feature before moving to the next MD task.
