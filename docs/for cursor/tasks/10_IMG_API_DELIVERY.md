# IMG-10 — Image API & Client Delivery

## Objective
Ensure clients can reliably create, inspect, and retrieve image jobs/results.

## Audit existing API
Document:
- create image
- get job/status
- get result
- cancellation if supported
- retry if supported

## Response requirements
Expose only necessary fields such as:
- jobId
- status
- createdAt
- updatedAt
- assetId
- assetUrl or delivery reference
- error
- attempt/retry metadata where useful

Do not expose credentials, raw provider internals, or sensitive prompt data unnecessarily.

## Status semantics
Ensure clients can distinguish queued/running/success/failure/cancelled/expired as applicable.

## Tests
API contract, authentication, ownership, status transitions, completed result, failed result, malformed IDs, not-found behavior.

## Final report
Include endpoint matrix, request/response contracts, auth behavior, tests, live verification, files, DB changes, and gaps.
