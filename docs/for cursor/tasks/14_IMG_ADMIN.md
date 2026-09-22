# IMG-14 — Image Admin Controls & Operations

## Objective
Integrate image operations into the existing admin architecture without inventing backend capabilities.

## First inspect
Review the existing Admin UI, AI settings, Observability patterns, auth gates, and image backend capabilities.

## Admin visibility
Where backend support exists, provide:
- recent image jobs
- status
- provider/model
- latency
- failures
- retries
- stuck jobs
- recent failures
- configuration
- SLA metrics

## Configuration
Expose only settings that are genuinely runtime-editable. For code/environment/provider defaults, show their source instead of pretending they are editable.

## Operations
Any retry/cancel/requeue action must:
- be admin-gated
- be explicit
- be idempotent/safe
- record the resulting state

## UI
Use the existing dark plum/rose Admin design language and existing patterns for loading, empty, error, tables, filters, accessibility, and escaping.

## Do not
Create fake Revenue/Transactions-style data, fake image metrics, or UI controls without backend semantics.

## Tests
Admin authorization, API contract, UI rendering, action safety, and live verification.

## Final report
Include UI/API/database changes, admin capabilities, security, tests, live verification, and gaps.
