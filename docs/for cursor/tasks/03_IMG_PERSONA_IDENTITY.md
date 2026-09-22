# IMG-03 — Persona → Image Identity Layer

## Objective
Establish exactly how a persona's visual identity is represented and supplied to image generation.

## First step
Inspect the existing Persona Core and the image pipeline discovered in IMG-01/02. Reuse existing data; do not duplicate persona definitions.

## Requirements
Identify which persona attributes are image-relevant, such as:
- stable appearance
- hair
- facial/visual traits
- body/physical descriptors where already part of the fictional persona definition
- recurring style
- wardrobe tendencies
- visual preferences
- reference images if already supported

Create or reuse a clearly bounded **image identity representation** rather than dumping the entire Persona Core into prompts.

Define precedence:
`system/image constraints > persona identity > application defaults > user scene request`.

User scene requests must not silently overwrite stable identity attributes unless the existing product design explicitly allows that behavior.

## Consistency
Verify repeated image requests for the same persona receive the same stable identity inputs.

## Security/privacy
Do not expose internal persona data unnecessarily in APIs or logs.

## Tests
Test identity extraction/mapping, missing persona, malformed persona data, and stable repeated mapping.

## Final report
Document the authoritative identity source, transformation path, exact fields used, files/DB changes, tests, live verification, and remaining identity-consistency limitations.
