# IMG-04 — Image Prompt Construction

## Objective
Create or harden one authoritative image prompt construction path.

## Inspect first
Trace every place image prompts are currently assembled. Remove neither path until you prove which is authoritative.

## Required prompt structure
Where supported by the actual product:
- identity
- user scene/request
- pose
- clothing
- camera
- lighting
- environment
- style
- negative constraints if supported

Do not blindly concatenate Persona Core, conversation history, or arbitrary metadata.

## Precedence
Document and test:
`system/safety constraints > stable persona identity > application image defaults > user image request`.

Prevent accidental duplicated instructions and uncontrolled prompt growth.

## Observability
Persist only safe diagnostic prompt metadata required by existing conventions. Never persist provider credentials or authorization headers.

## Tests
Cover deterministic construction, missing optional fields, long input, conflicting attributes, persona identity preservation, and escaping/sanitization where applicable.

## Final report
Include prompt architecture, precedence rules, example structured output (without sensitive data), exact files changed, tests, live verification, and known gaps.
