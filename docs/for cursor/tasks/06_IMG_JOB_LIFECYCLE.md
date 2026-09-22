# IMG-06 — Image Job & State Machine

## Objective
Make the existing image job lifecycle explicit, durable, and safe.

## Inspect first
Identify the real job table/entity, worker, queue mechanism, and status fields.

## Lifecycle
Use only states justified by the existing architecture. A typical lifecycle may be:
`CREATED → QUEUED → RUNNING → RETRYING → SUCCEEDED/FAILED/CANCELLED/EXPIRED`.

Do not add states merely for aesthetics.

## Requirements
- explicit valid transitions
- invalid transition protection
- timestamps
- attempt count where applicable
- error classification
- ownership
- correlation/request ID
- provider job ID if applicable
- terminal-state semantics

A worker crash must not leave a job permanently RUNNING.

## Concurrency
Verify duplicate workers cannot process the same job concurrently unless explicitly supported.

## Tests
Transition matrix, duplicate pickup, restart/crash simulation, terminal states, stale-running behavior.

## Final report
Include actual state machine, transition rules, schema changes, worker interaction, tests, live verification, and gaps.
