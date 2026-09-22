# IMG-15 — Image Storage & Retention

## Objective
Understand and harden image/job storage lifecycle.

## Audit
Determine:
- storage backend
- asset key structure
- DB records
- job retention
- asset retention
- failed assets
- orphan assets
- temporary files
- cleanup jobs
- storage growth

Do not infer production growth from dev/test data.

## Requirements
If retention/cleanup is missing and required, implement using existing scheduling conventions.

Cleanup must be:
- ownership-safe
- idempotent
- observable
- bounded
- resistant to deleting active/referenced assets

## Tests
Referenced asset preservation, expired asset cleanup, orphan cleanup, repeated cleanup, active-job safety, storage failure.

## Final report
Actual storage model, retention rules, cleanup implementation, tests, live verification, and unresolved capacity risks.
