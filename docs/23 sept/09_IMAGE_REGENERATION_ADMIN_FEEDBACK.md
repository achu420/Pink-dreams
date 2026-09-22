# IMAGE REGENERATION & ADMIN FEEDBACK

**Task:** 09 — Candidate Feedback, Regeneration, Versioning & Admin Review  
**Date:** 2026-09-23  
**Parent:** `IMAGE_PIPELINE_GOVERNANCE.md`  
**Depends on:**  
- `02_PERSONA_VISUAL_IDENTITY.md`
- `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
- `IMAGE GENERATION & CANDIDATE CREATION.md`
- `Candidate Generation, 4-Image Output & Candidate Lifecycle.md`
- `IMAGE WAREHOUSE & CANDIDATE LIFECYCLE.md`
- Task 08 — `IMAGE REFERENCE IMAGE SYSTEM.md`

**Status:** IMPLEMENT / VERIFY / HARDEN

---

# 1. Purpose

The admin must be able to review generated candidates and request a corrected regeneration without losing the original candidate.

The intended workflow is:

```text
Seed
  ↓
Generate 4 candidates
  ↓
Admin reviews
  ↓
Candidate selected
  ↓
Admin gives correction / remark
  ↓
Regenerate
  ↓
New candidate(s)
  ↓
Compare with original
  ↓
Shortlist / Save / Decline / Post later
```

A regeneration is a **new generation**, not an overwrite.

The original candidate must remain available for audit and comparison.

---

# 2. Product Requirement

For each generated candidate, admin can:

- view the image
- view generation metadata
- add an admin remark
- request regeneration
- provide a correction/instruction
- see the regenerated result
- compare the original and regenerated candidate
- shortlist
- save
- decline

The system must preserve the complete lineage.

Example:

```text
Generation G1
 ├── Candidate C1
 │    └── Regeneration R1
 │         └── Candidate C5
 │              └── Regeneration R2
 │                   └── Candidate C9
 ├── Candidate C2
 ├── Candidate C3
 └── Candidate C4
```

Do not overwrite C1 when R1 is generated.

---

# 3. What Regeneration Means

A regeneration means:

> Generate a new candidate using the same persona/identity context while applying an explicit correction to the previous generation.

The correction may concern:

- pose
- expression
- framing
- camera angle
- clothing
- lighting
- background
- composition
- scene interpretation
- visual consistency
- other generation-specific characteristics

The correction must not silently modify the persistent persona identity.

For example:

```text
Original:
"Simran at Goa beach, yellow bikini, facing camera."

Correction:
"Keep the same persona and yellow bikini.
Make the camera slightly closer and have her smile naturally."
```

The correction belongs to this generation.

It does not become a permanent persona attribute.

---

# 4. Identity Must Remain Stable

Regeneration must preserve the active persona identity context.

The regeneration should use:

```text
same persona
+
same applicable visual identity
+
same reference-image context
+
original seed
+
admin correction
```

Conceptually:

```text
Persona Identity
        +
Reference Images
        +
Original Seed
        +
Admin Correction
        ↓
   Regeneration
```

A correction such as:

```text
"change hair color to black"
```

must not automatically modify the persona's persistent hair color.

If the admin wants a permanent identity change, that belongs in the Persona Visual Identity workflow.

---

# 5. Original Candidate Preservation

This is mandatory.

When an admin regenerates:

```text
Original Candidate
      ↓
remains unchanged
```

A new generation/candidate is created.

Never:

- replace the original file
- overwrite original prompt
- overwrite original metadata
- change original timestamp
- mutate original provider/model metadata
- silently change original status/history

The system must be able to answer:

> What did the original generation produce?

and:

> What changed in the regeneration?

---

# 6. Parent / Lineage Model

Reuse the existing candidate/job schema where possible.

If supported by the current schema, add:

```text
parentCandidateId
parentGenerationJobId
regenerationReason
regenerationInstruction
generationVersion
```

The exact field names may differ.

Do not create redundant lineage tables if existing generation metadata can represent the relationship cleanly.

Minimum requirement:

```text
new candidate → knows which candidate caused the regeneration
```

The original candidate does not need to point to every future descendant if the existing model can traverse the relationship from child → parent.

---

# 7. Regeneration Request

A regeneration request should contain:

```text
personaId
parentCandidateId
correction/instruction
```

The backend resolves the remaining generation context.

Do not trust the client to provide authoritative:

- model
- provider
- persona identity
- reference-image set
- original generation metadata

The server must resolve these.

---

# 8. Context Used for Regeneration

The regeneration should resolve:

```text
Current Persona
        ↓
Current Visual Identity
        ↓
Current Active Reference Images
        ↓
Original Candidate / Generation Context
        ↓
Original Seed
        ↓
Admin Correction
        ↓
Current Image Model Configuration
```

Important:

### Identity

Use the current active persona identity/reference system according to the Task 08 contract.

### Historical context

Preserve the original generation's metadata.

### Correction

Apply the admin's new instruction only to this regeneration.

### Model/provider

Use the currently configured image-generation model/provider unless the existing product explicitly supports generation-version pinning.

Record what was actually used.

---

# 9. Prompt Construction

Continue using the existing `PromptCompiler`.

Do not introduce another prompt builder.

The conceptual prompt structure is:

```text
PERSONA IDENTITY
+
REFERENCE CONTEXT
+
ORIGINAL SCENE / SEED
+
ADMIN CORRECTION
```

The compiler must prevent the correction from accidentally overriding persistent identity unless the workflow explicitly permits an identity change.

The final resolved prompt/instructions used for the generation must be auditable.

Do not expose provider secrets.

---

# 10. Candidate Count

Default behavior:

```text
Initial generation → 4 candidates
```

For regeneration, use the existing configured candidate count if the system already supports it.

If regeneration is intentionally one candidate rather than four:

- make this explicit in configuration/product behavior
- record the behavior
- keep it deterministic

Do not silently create a different number of images than the UI/API indicates.

---

# 11. Admin Candidate UI

Each candidate should expose:

```text
Image preview

Status
Created date/time

Seed prompt
Correction / regeneration instruction
Model
Provider
Generation ID
Candidate ID

Parent candidate
```

Actions:

```text
[Remark]
[Regenerate]
[Shortlist]
[Save]
[Decline]
```

If an action is not yet implemented, do not render a fake successful action.

---

# 12. Admin Remark

An admin remark is metadata.

It should not automatically trigger generation.

Example:

```text
Remark:
"Face looks good. Lighting is too dark."
```

The admin may then explicitly select:

```text
Regenerate
```

with:

```text
Correction:
"Keep face and composition.
Increase natural morning light."
```

Keep these concepts separate:

```text
Remark
```

vs.

```text
Regeneration Instruction
```

A remark can exist without a regeneration.

---

# 13. Regeneration UI Flow

Recommended UI:

```text
Candidate C1

[Image]

Admin remark:
[________________________]

Correction for regeneration:
[________________________]

[Save Remark]   [Regenerate]
```

After regeneration:

```text
Original C1       Regenerated C5

[image]           [image]

Original           Regeneration #1

Correction:
"Increase natural morning light."

[Shortlist] [Save] [Decline]
```

The UI must make lineage obvious.

---

# 14. Status Model

Reuse the existing candidate lifecycle.

Do not invent conflicting status systems.

The candidate lifecycle may include states such as:

```text
GENERATED
SHORTLISTED
DECLINED
SAVED
POSTED
```

Use the actual existing names if different.

Regeneration does not mean:

```text
original → replaced
```

Instead:

```text
original remains in its existing state
new candidate receives its own state
```

Example:

```text
C1 = DECLINED
C5 = GENERATED
```

or:

```text
C1 = SHORTLISTED
C5 = GENERATED
```

The admin controls each candidate independently.

---

# 15. Warehouse Integration

Every regenerated candidate must appear in the image warehouse.

The warehouse should show:

```text
Candidate
Status
Created At
Persona
Seed
Model
Provider
Parent Candidate
Generation number
Admin remark
Regeneration instruction
```

The warehouse must not flatten regenerated candidates into one image.

Admin should be able to inspect lineage.

---

# 16. Auditability

The system must preserve:

```text
original candidate
original generation job
original seed
original identity version
original reference-image set
original model/provider
original timestamp

+

regeneration instruction
regeneration timestamp
new generation job
new candidate
new model/provider
new identity/reference snapshot
```

This allows later debugging of:

- identity drift
- prompt changes
- model changes
- provider changes
- admin corrections
- quality regressions

---

# 17. Cost / SLA Integration

Regeneration is a new generation and must be observable separately.

The system should record:

```text
initial generation
regeneration
```

as distinguishable generation events.

SLA metrics must include the regeneration request if it uses the normal image generation pipeline.

Cost tracking should count the actual provider generation calls.

Do not count an admin remark as an image generation.

---

# 18. API Requirements

Inspect existing image APIs before adding routes.

Expected capability:

```text
POST /.../candidates/{candidateId}/remark
POST /.../candidates/{candidateId}/regenerate
POST /.../candidates/{candidateId}/shortlist
POST /.../candidates/{candidateId}/save
POST /.../candidates/{candidateId}/decline
```

Exact route naming must follow the existing API conventions.

Do not create duplicate endpoints if equivalent routes already exist.

Every endpoint must:

- require appropriate admin authorization
- verify candidate existence
- verify persona/job relationship
- prevent cross-persona mutation
- return the actual persisted result

---

# 19. Regeneration Security

Admin authorization is required.

The backend must prevent:

- user modifying another persona's candidate
- user regenerating arbitrary candidate IDs
- path manipulation
- arbitrary provider selection through untrusted input
- arbitrary storage paths
- secrets entering correction text logs

Correction text should be treated as untrusted input.

---

# 20. Failure Handling

If regeneration fails:

```text
Original candidate remains intact.
```

The failed regeneration job must be diagnosable.

It must not:

- delete original image
- mark original as failed
- corrupt candidate lifecycle
- remove warehouse entry
- falsely report success

Example:

```text
C1 = SHORTLISTED

Regeneration R1 = FAILED

C1 remains SHORTLISTED
R1 remains FAILED with diagnostic metadata
```

---

# 21. Retry Behavior

Reuse the existing image job retry/failure policy.

Do not implement a second retry system.

If the regeneration job is retryable:

```text
same regeneration job
```

should follow the normal worker retry rules.

Do not create four new candidates for every provider retry unless the existing generation contract explicitly defines retries as new candidate generations.

Provider retry and admin regeneration are different concepts.

---

# 22. Tests

## Unit

Test:

- parent candidate validation
- correction validation
- lineage creation
- original preservation
- identity snapshot
- reference snapshot
- status independence
- authorization
- cost/event classification

## Integration

Test:

```text
create generation
    ↓
4 candidates
    ↓
select C1
    ↓
add remark
    ↓
regenerate C1
    ↓
new generation
    ↓
new candidate
    ↓
C1 unchanged
```

## Replacement/identity test

Verify:

```text
C1 generated with identity/reference V1

persona reference changes

regenerate C1

new candidate uses current generation context as defined by Task 08

C1 metadata remains V1
```

## Failure test

```text
C1 exists

regeneration fails

assert:
C1 still retrievable
C1 status unchanged
failed regeneration diagnosable
```

## Security test

Verify non-admin cannot:

- remark
- regenerate
- alter lifecycle
- access another persona's candidate

---

# 23. Admin Acceptance Test

Cursor must provide a repeatable manual test.

### Test A — remark

```text
1. Generate 4 candidates.
2. Open one candidate.
3. Add admin remark.
4. Save.
5. Refresh.
6. Verify remark persists.
```

### Test B — regeneration

```text
1. Select candidate C1.
2. Enter correction.
3. Click Regenerate.
4. Wait for completion.
5. Verify new candidate exists.
6. Verify C1 still exists unchanged.
7. Verify parent/lineage is visible.
```

### Test C — lifecycle

```text
1. Shortlist original.
2. Regenerate it.
3. Verify original remains shortlisted.
4. Verify regenerated candidate starts in its normal initial state.
5. Change regenerated candidate status.
6. Verify statuses remain independent.
```

### Test D — warehouse

```text
1. Open image warehouse.
2. Find original.
3. Find regenerated candidate.
4. Verify timestamps.
5. Verify parent relationship.
6. Verify generation metadata.
```

---

# 24. Definition of Done

This task is DONE only when:

- [ ] Existing candidate lifecycle was inspected first.
- [ ] Existing generation/job infrastructure is reused.
- [ ] Admin can add candidate remarks.
- [ ] Admin can explicitly request regeneration.
- [ ] Regeneration accepts a correction/instruction.
- [ ] Original candidate is never overwritten.
- [ ] New generation/candidate is created.
- [ ] Parent/lineage is persisted.
- [ ] Identity/reference context is preserved and auditable.
- [ ] `PromptCompiler` remains the single prompt construction path.
- [ ] Regeneration uses the normal worker/provider pipeline.
- [ ] Provider/model actually used is recorded.
- [ ] Regeneration contributes to SLA/cost observability.
- [ ] Failed regeneration leaves original candidate intact.
- [ ] Admin UI uses real backend state.
- [ ] Security/ownership is enforced.
- [ ] Warehouse displays regenerated candidates and lineage.
- [ ] Tests pass.
- [ ] `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` is updated.

---

# 25. Cursor Reporting Requirement

Update:

`IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`

with:

```text
## Task 09 — Image Regeneration & Admin Feedback

Status: DONE / PARTIAL / BLOCKED

Existing implementation:
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

Lineage:
...

Prompt integration:
...

Identity/reference integration:
...

SLA/cost:
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

---

# 26. Scope Boundary

Do NOT implement in this task:

- social publishing
- Instagram posting
- automated content scheduling
- storyline generation
- LoRA training
- automatic image quality scoring unless already implemented
- a second image provider
- a second prompt compiler
- a second candidate warehouse

This task is strictly:

```text
GENERATED CANDIDATE
       ↓
ADMIN FEEDBACK
       ↓
REGENERATION
       ↓
NEW CANDIDATE
       ↓
PRESERVE COMPLETE LINEAGE
```

Complete and test this feature before moving to the next MD.
