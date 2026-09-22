# IMG-16 — Image Failure-Path & Integration Test Hardening

## Objective
Build the test coverage required to trust the complete image pipeline.

## Test layers

### Unit
- request validation
- identity mapping
- prompt construction
- provider mapping
- configuration resolution
- retry classification
- state transitions

### Integration
- request → job
- job → worker
- worker → provider
- provider → asset
- asset → persistence
- status/result API

### Failure
- timeout
- provider 4xx/5xx
- malformed provider response
- storage failure
- DB failure
- worker restart
- duplicate request
- stale RUNNING job
- max retries
- unauthorized access

### Security
- cross-user access
- secret leakage
- unsafe remote URL
- oversized/malformed payload
- MIME mismatch

## Requirements
Do not weaken existing tests or delete tests merely to obtain a green suite.

Run targeted image tests first, then the relevant broader suite.

Document pre-existing flakes separately.

## Final report
Provide before/after test counts, tests added, failures, pre-existing failures, live verification, and remaining untested risks.
