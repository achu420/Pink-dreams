# IMG-02 — Image Request Contract & Validation

## Objective
Using the verified IMG-01 architecture, make the image request contract explicit, safe, and deterministic.

## First step
Read the IMG-01 findings and inspect the actual implementation again. Do not implement assumptions that conflict with the repository.

## Requirements
Audit and, where missing, implement:
- required/optional request fields
- prompt length limits
- image count limits
- dimensions/aspect-ratio validation
- supported formats/styles
- persona/reference-image identifiers
- model/provider fields
- invalid combinations
- request size limits
- authentication and ownership validation
- clear machine-readable errors

Validation must happen before expensive provider work.

## Idempotency
Determine whether duplicate image requests can create duplicate jobs. If missing and the existing architecture supports it safely, add an idempotency mechanism.

Verify:
- same request repeated
- concurrent duplicate
- client retry after timeout
- completed job repeated
- failed job repeated

Do not invent a second job system.

## Tests
Add focused unit/integration tests for every validation rule and idempotency behavior implemented.

## Live verification
Safely test valid, invalid, duplicate, unauthorized, and boundary requests where the existing environment permits.

## Do not change
Prompt semantics, provider routing, Persona Core behavior, chat generation, or unrelated APIs.

## Final report
Include exact request schema, validation rules, idempotency semantics, files, DB changes, APIs, tests, live verification, known gaps, and commit hash.
