# IMG-08 — Image Retry, Timeout & Failure Policy

## Objective
Define deterministic failure handling for image generation.

## Classify failures
Audit actual provider/application errors and classify them as retryable or permanent.

Potential retryable classes:
- transient upstream 5xx
- network timeout
- temporary network/storage failure
- rate limiting where safe

Potential permanent classes:
- invalid request
- unsupported configuration
- safety rejection
- malformed non-retryable input

Use actual provider semantics rather than assumptions.

## Retry policy
Where missing, implement:
- maximum attempts
- bounded backoff
- jitter where appropriate
- timeout
- final FAILED state
- durable failure reason

Never infinite-loop.

## Tests
Every retryable class, permanent class, max-attempt behavior, timeout, duplicate prevention, and final error reporting.

## Live verification
Use safe failure simulation or existing test provider hooks. Do not deliberately damage production traffic.

## Final report
Include failure matrix, retry policy, exact behavior, tests, live evidence, and gaps.
